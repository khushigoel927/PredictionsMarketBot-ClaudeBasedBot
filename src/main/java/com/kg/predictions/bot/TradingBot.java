package com.kg.predictions.bot;

import com.kg.predictions.app.GamesService.Snapshot;
import com.kg.predictions.app.QuoteStore;
import com.kg.predictions.bot.BotStore.GameBotState;
import com.kg.predictions.kalshi.KalshiEnv;
import com.kg.predictions.kalshi.KalshiOrders;
import com.kg.predictions.kalshi.KalshiPortfolio;
import com.kg.predictions.kalshi.KalshiPortfolio.Position;
import com.kg.predictions.mlb.MlbLiveClient;
import com.kg.predictions.model.Game;
import com.kg.predictions.model.LivePrice;
import com.kg.predictions.model.LiveState;
import com.kg.predictions.model.Quote;

import java.util.Map;
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
                      Supplier<Snapshot> snapshotSupplier) {
        this.config = config;
        this.orders = orders;
        this.portfolio = portfolio;
        this.mlbLive = mlbLive;
        this.quoteStore = quoteStore;
        this.botStore = botStore;
        this.snapshotSupplier = snapshotSupplier;
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
        long now = System.currentTimeMillis();

        for (Game g : snap.games()) {
            if (g.gamePk() == 0) continue; // unmatched to an MLB game
            try {
                processGame(g, positions, now);
            } catch (Exception e) {
                System.err.println("[bot] " + g.eventId() + ": " + e.getMessage());
            }
        }
    }

    private void processGame(Game g, Map<String, Position> positions, long now) throws Exception {
        LiveState ls = mlbLive.fetchLiveState(g.gamePk());
        if (!ls.live() || ls.inning() < config.minInning) return;

        double pHome = WinProbability.homeWinProb(ls);
        GameWork w = work.get(g.eventId());

        // Lock to a side once we've started trading a game; otherwise pick the favorite.
        boolean homeSide = (w != null && w.locked) ? w.homeSide : (pHome >= 0.5);
        double p = homeSide ? pHome : 1.0 - pHome;
        Quote q = homeSide ? g.home() : g.away();

        // Bullpen change → brief cooldown to avoid trading into post-change uncertainty.
        if (w != null && w.lastPitcherId != 0 && ls.defensePitcherId() != w.lastPitcherId) {
            w.cooldownUntilMs = now + config.pitchChangeCooldownSec * 1000L;
        }
        if (w != null) w.lastPitcherId = ls.defensePitcherId();

        int yesBid = bid(q);
        int yesAsk = ask(q);
        int fairCents = (int) Math.round(p * 100);
        int held = positions.getOrDefault(q.marketId(), new Position(0, 0)).count();

        boolean canTrade =
                p >= config.winProbFloor
                && yesAsk > 0 && yesAsk <= config.maxPriceCents
                && (fairCents - yesAsk) >= config.minEdgeCents
                && (w == null || (now >= w.cooldownUntilMs && w.bidsPlaced < config.maxBidsPerGame))
                && held < config.maxContractsPerGame;

        if (canTrade) {
            // Careful maker price: rest below the ask, below fair by the edge margin.
            int makerPrice = Math.min(Math.min(yesBid + 1, fairCents - config.minEdgeCents), yesAsk - 1);
            makerPrice = Math.max(1, Math.min(makerPrice, config.maxPriceCents));

            boolean edgePreserved = (fairCents - makerPrice) >= config.minEdgeCents && makerPrice >= 1;
            if (edgePreserved) {
                w = ensureWork(g, homeSide, q);
                maybePlace(w, q, makerPrice, fairCents, held, now);
            }
        }

        publish(g, w, q, p, positions);
    }

    /** Place or re-price the single resting order for this game, honoring pacing + caps. */
    private void maybePlace(GameWork w, Quote q, int makerPrice, int fairCents, int held, long now)
            throws Exception {
        boolean materialMove = Math.abs(fairCents - w.lastFairCents) >= config.materialMoveCents;
        boolean priceChanged = w.openOrderPriceCents != makerPrice;
        boolean pacedOk = now - w.lastActionMs >= config.rePriceMinIntervalSec * 1000L;

        if (w.openOrderId != null && !priceChanged) return;          // already resting at this price
        if (w.openOrderId != null && !materialMove) return;          // fair hasn't moved enough to re-price
        if (!pacedOk) return;                                        // respect min interval
        if (w.bidsPlaced >= config.maxBidsPerGame) return;

        int size = Math.min(config.maxContractsPerOrder, config.maxContractsPerGame - held);
        if (size <= 0) return;

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
            System.out.printf("[bot] %s: bid #%d  %s yes @ %d¢ x%d  (fair %d¢)%n",
                    w.eventTicker, w.bidsPlaced, q.teamAbbrev(), makerPrice, size, fairCents);
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
