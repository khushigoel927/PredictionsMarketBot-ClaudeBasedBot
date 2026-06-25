package com.kg.predictions.bot;

import com.kg.predictions.app.GamesService.Snapshot;
import com.kg.predictions.app.QuoteStore;
import com.kg.predictions.bot.BotStore.GameBotState;
import com.kg.predictions.kalshi.KalshiEnv;
import com.kg.predictions.kalshi.KalshiOrders;
import com.kg.predictions.kalshi.KalshiPortfolio;
import com.kg.predictions.kalshi.KalshiPortfolio.Balance;
import com.kg.predictions.kalshi.KalshiPortfolio.Position;
import com.kg.predictions.mlb.MlbLiveClient;
import com.kg.predictions.model.Game;
import com.kg.predictions.model.LivePrice;
import com.kg.predictions.model.LiveState;
import com.kg.predictions.model.Quote;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Automated trading engine. Each cycle it scans the matched live MLB games in
 * innings ≥ {@link BotConfig#minInning}, estimates win probability
 * ({@link WinProbability}), and — only when there is a strong, high-confidence
 * edge — rests a careful post-only limit order on the favored team. It places a
 * small number of high-quality orders (hard caps per game) and publishes
 * per-game state to {@link BotStore} for the UI.
 *
 * <p>Environment-gated: arms only when the active {@link KalshiEnv} is in
 * {@link BotConfig#allowedEnvs} (default demo only) and credentials exist.
 */
public final class TradingBot {

    private final BotConfig config;
    private final KalshiOrders orders;
    private final KalshiPortfolio portfolio;
    private final MlbLiveClient mlbLive;
    private final QuoteStore quoteStore;
    private final BotStore botStore;
    private final Supplier<Snapshot> snapshotSupplier;
    private final TradeDecider decider;
    private final RiskEnforcer enforcer;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "trading-bot");
                t.setDaemon(true);
                return t;
            });

    /** Bot's working state per game (engine-thread only). */
    private final Map<String, GameWork> work = new ConcurrentHashMap<>();

    public TradingBot(BotConfig config, KalshiOrders orders, KalshiPortfolio portfolio,
                      MlbLiveClient mlbLive, QuoteStore quoteStore, BotStore botStore,
                      Supplier<Snapshot> snapshotSupplier, TradeDecider decider,
                      RiskEnforcer enforcer) {
        this.config = config;
        this.orders = orders;
        this.portfolio = portfolio;
        this.mlbLive = mlbLive;
        this.quoteStore = quoteStore;
        this.botStore = botStore;
        this.snapshotSupplier = snapshotSupplier;
        this.decider = decider;
        this.enforcer = enforcer;
    }

    /** Arms the bot if allowed; otherwise logs why it stays idle. */
    public void start() {
        if (!config.enabled) {
            System.out.println("Trading bot: DISABLED (BOT_ENABLED=false)");
            return;
        }
        if (orders == null || portfolio == null) {
            System.out.println("Trading bot: idle (no Kalshi credentials)");
            return;
        }
        if (!config.allowedEnvs.contains(KalshiEnv.name())) {
            System.out.println("Trading bot: idle (env '" + KalshiEnv.name()
                    + "' not in allowed " + config.allowedEnvs + ")");
            return;
        }
        System.out.println("Trading bot: ARMED for environment '" + KalshiEnv.name()
                + "' (innings " + config.minInning + "+, win-prob ≥ " + config.winProbFloor
                + ", edge ≥ " + config.minEdgeCents + "¢, ≤" + config.maxBidsPerGame + " bids/game)");
        scheduler.scheduleWithFixedDelay(this::cycleSafely,
                config.cycleSeconds, config.cycleSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void cycleSafely() {
        try {
            cycle();
        } catch (Exception e) {
            System.err.println("[bot] cycle error: " + e.getMessage());
        }
    }

    private void cycle() {
        Snapshot snap = snapshotSupplier.get();
        if (snap == null) return;

        Map<String, Position> positions = fetchPositionsQuietly();
        Balance balance = fetchBalanceQuietly();
        long now = System.currentTimeMillis();

        for (Game g : snap.games()) {
            if (g.gamePk() == 0) continue; // unmatched to an MLB game
            try {
                processGame(g, positions, balance, now);
            } catch (Exception e) {
                System.err.println("[bot] " + g.eventId() + ": " + e.getMessage());
            }
        }
    }

    private void processGame(Game g, Map<String, Position> positions, Balance balance, long now)
            throws Exception {
        LiveState ls = mlbLive.fetchLiveState(g.gamePk());
        if (!ls.live() || ls.inning() < config.minInning) return;

        double pHome = WinProbability.homeWinProb(ls);
        GameWork w = work.get(g.eventId());

        // Lock to a side once we've started trading a game; otherwise pick the favorite.
        boolean homeSide = (w != null && w.locked) ? w.homeSide : (pHome >= 0.5);
        double pDet = homeSide ? pHome : 1.0 - pHome;
        Quote q = homeSide ? g.home() : g.away();

        // Bullpen change → brief cooldown to avoid trading into post-change uncertainty.
        if (w != null && w.lastPitcherId != 0 && ls.defensePitcherId() != w.lastPitcherId) {
            w.cooldownUntilMs = now + config.pitchChangeCooldownSec * 1000L;
        }
        if (w != null) w.lastPitcherId = ls.defensePitcherId();

        int yesBid = bid(q);
        int yesAsk = ask(q);
        int fairDet = (int) Math.round(pDet * 100);
        int held = positions.getOrDefault(q.marketId(), new Position(0, 0)).count();

        // Cheap structural + deterministic candidacy pre-screen. This is the cost
        // gate: only spend an API call on games the closed-form model already likes.
        boolean cooldownOk = (w == null) || now >= w.cooldownUntilMs;
        boolean capsOk = (w == null || w.bidsPlaced < config.maxBidsPerGame)
                && held < config.maxContractsPerGame;
        boolean candidate = cooldownOk && capsOk
                && yesAsk > 0 && yesAsk <= config.maxPriceCents
                && pDet >= config.winProbFloor
                && (fairDet - yesAsk) >= config.minEdgeCents;

        if (candidate) {
            // The decider judges (Claude on the happy path, deterministic fallback);
            // the enforcer then clamps its suggestion to every hard risk limit.
            DecisionContext ctx = new DecisionContext(
                    g.eventId(), q.marketId(), q.teamAbbrev(), homeSide, ls,
                    yesBid, yesAsk, q.impliedChancePct(), held,
                    w != null ? w.bidsPlaced : 0,
                    balance.cashCents(), balance.portfolioValueCents(), config);
            Optional<TradeIntent> intent = enforcer.enforce(ctx, decider.decide(ctx));
            if (intent.isPresent()) {
                w = ensureWork(g, homeSide, q);
                maybePlace(w, q, intent.get(), now);
            }
        }

        publish(g, w, q, pDet, positions);
    }

    /** Place or re-price the single resting order for this game, honoring pacing + caps.
     *  The intent is already risk-clamped by {@link RiskEnforcer}. */
    private void maybePlace(GameWork w, Quote q, TradeIntent intent, long now) throws Exception {
        int makerPrice = intent.makerPriceCents();
        int fairCents = intent.fairCents();
        int size = intent.contracts();
        if (size <= 0) return;

        boolean materialMove = Math.abs(fairCents - w.lastFairCents) >= config.materialMoveCents;
        boolean priceChanged = w.openOrderPriceCents != makerPrice;
        boolean pacedOk = now - w.lastActionMs >= config.rePriceMinIntervalSec * 1000L;

        if (w.openOrderId != null && !priceChanged) return;          // already resting at this price
        if (w.openOrderId != null && !materialMove) return;          // fair hasn't moved enough to re-price
        if (!pacedOk) return;                                        // respect min interval
        if (w.bidsPlaced >= config.maxBidsPerGame) return;

        if (w.openOrderId != null) {
            try { orders.cancel(w.openOrderId); } catch (Exception ignore) { } // cancels don't count as bids
            w.openOrderId = null;
        }

        try {
            KalshiOrders.OrderResult r =
                    orders.placeLimit(q.marketId(), "yes", makerPrice, size, UUID.randomUUID().toString());
            w.openOrderId = r.orderId();
            w.openOrderPriceCents = makerPrice;
            w.bidsPlaced++;             // a "bid" = one successful placement
            w.lastActionMs = now;
            w.lastFairCents = fairCents;
            System.out.printf("[bot] %s: bid #%d  %s yes @ %d¢ x%d  (fair %d¢, %s)%n",
                    w.eventTicker, w.bidsPlaced, q.teamAbbrev(), makerPrice, size, fairCents, intent.source());
        } catch (Exception e) {
            // e.g. post-only would cross, or transient error -> treat as skip, no bid counted
            w.openOrderId = null;
            System.err.println("[bot] " + w.eventTicker + " place skipped: " + e.getMessage());
        }
    }

    private GameWork ensureWork(Game g, boolean homeSide, Quote q) {
        return work.computeIfAbsent(g.eventId(), id -> {
            GameWork nw = new GameWork(id);
            nw.homeSide = homeSide;
            nw.locked = true;
            nw.teamAbbrev = q.teamAbbrev();
            return nw;
        });
    }

    private void publish(Game g, GameWork w, Quote q, double p, Map<String, Position> positions) {
        if (w == null || w.bidsPlaced == 0) return; // only show games we've actually traded
        Position pos = positions.getOrDefault(q.marketId(), new Position(0, 0));
        int avgCents = pos.count() > 0 ? (int) Math.round(pos.avgPriceDollars() * 100) : 0;
        double expectedPayoff = pos.count() * (p - pos.avgPriceDollars());
        botStore.publish(new GameBotState(
                g.eventId(), w.teamAbbrev, w.bidsPlaced, pos.count(),
                avgCents, (int) Math.round(p * 100), expectedPayoff));
    }

    private Map<String, Position> fetchPositionsQuietly() {
        try {
            return portfolio.fetchPositions();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Account balance, or zeroed on failure — which fail-closes the cash/exposure
     *  caps in {@link RiskEnforcer} so the bot won't trade blind to its balance. */
    private Balance fetchBalanceQuietly() {
        try {
            return portfolio.fetchBalance();
        } catch (Exception e) {
            return new Balance(0, 0);
        }
    }

    /** Best live YES bid/ask in cents, preferring the live QuoteStore over the snapshot. */
    private int bid(Quote q) {
        LivePrice lp = quoteStore.get(q.marketId());
        return lp != null ? lp.yesBid() : q.yesBid();
    }

    private int ask(Quote q) {
        LivePrice lp = quoteStore.get(q.marketId());
        return lp != null ? lp.yesAsk() : q.yesAsk();
    }

    /** Mutable per-game working state owned by the engine thread. */
    private static final class GameWork {
        final String eventTicker;
        boolean locked;
        boolean homeSide;
        String teamAbbrev;
        int bidsPlaced;
        String openOrderId;
        int openOrderPriceCents;
        int lastFairCents;
        long lastActionMs;
        long lastPitcherId;
        long cooldownUntilMs;

        GameWork(String eventTicker) {
            this.eventTicker = eventTicker;
        }
    }
}
