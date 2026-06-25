package com.kg.predictions.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.kg.predictions.app.GamesService;
import com.kg.predictions.app.GamesService.Snapshot;
import com.kg.predictions.app.QuoteStore;
import com.kg.predictions.bot.BotConfig;
import com.kg.predictions.bot.BotStore;
import com.kg.predictions.bot.ClaudeDecider;
import com.kg.predictions.bot.DeterministicDecider;
import com.kg.predictions.bot.RiskEnforcer;
import com.kg.predictions.bot.TradeDecider;
import com.kg.predictions.bot.TradingBot;
import com.kg.predictions.feed.PollingPriceFeed;
import com.kg.predictions.feed.PriceFeed;
import com.kg.predictions.feed.WebSocketPriceFeed;
import com.kg.predictions.kalshi.KalshiAuth;
import com.kg.predictions.kalshi.KalshiEnv;
import com.kg.predictions.kalshi.KalshiOrders;
import com.kg.predictions.kalshi.KalshiPortfolio;
import com.kg.predictions.kalshi.KalshiPortfolio.Balance;
import com.kg.predictions.mlb.MlbLiveClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Web UI for the live baseball markets, built on the JDK's bundled
 * {@link HttpServer} (no extra dependencies).
 *
 * <ul>
 *   <li>{@code GET /}           – the HTML page (resources/index.html)</li>
 *   <li>{@code GET /api/games}  – game list + scores + current prices (initial paint)</li>
 *   <li>{@code GET /api/stream} – Server-Sent Events: real-time bid/ask changes</li>
 * </ul>
 *
 * Prices flow from a {@link PriceFeed} (Kalshi WebSocket when credentials are
 * configured, else 2s REST polling) into a {@link QuoteStore}, which pushes
 * changes to connected browsers through the {@link SseBroadcaster}. The game
 * list + MLB scores are refreshed on a slower timer.
 */
public final class WebServer {

    private static final Duration GAMES_CACHE_TTL = Duration.ofSeconds(20);
    private static final Duration BALANCE_TTL = Duration.ofSeconds(5);
    private static final Gson GSON = new Gson();

    private final GamesService service;
    private final int port;

    private final QuoteStore quoteStore = new QuoteStore();
    private final SseBroadcaster broadcaster = new SseBroadcaster();
    private final BotStore botStore = new BotStore();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "games-refresh");
                t.setDaemon(true);
                return t;
            });

    private PriceFeed feed;
    private TradingBot bot;                // null when no credentials
    private KalshiPortfolio portfolio;     // null when no credentials
    private Snapshot cached;
    private Instant cachedAt;
    private Balance cachedBalance;
    private Instant balanceAt;

    public WebServer(GamesService service, int port) {
        this.service = service;
        this.port = port;
    }

    public void start() throws IOException, InterruptedException {
        // Push live price changes straight out to all connected browsers.
        quoteStore.setChangeListener(broadcaster::broadcast);

        // Initial load gives us the markets to subscribe to.
        Snapshot initial = snapshot();
        startFeed(initial);

        // Keep the subscription in sync as games open/settle.
        scheduler.scheduleWithFixedDelay(this::refreshSubscription,
                5, 5, TimeUnit.MINUTES);

        // Keep SSE connections alive and flush through any buffering layer.
        scheduler.scheduleWithFixedDelay(broadcaster::heartbeat,
                10, 10, TimeUnit.SECONDS);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/stream", this::handleStream);
        server.createContext("/api/games", this::handleApi);
        server.createContext("/", this::handleRoot);
        server.start();

        System.out.println("Live baseball UI running at  http://localhost:" + port + "/");
        System.out.println("Press Ctrl+C to stop.");
    }

    private void startFeed(Snapshot initial) {
        // Load credentials once and share them between the price feed and the
        // authenticated balance lookup.
        KalshiAuth auth = null;
        if (KalshiAuth.isConfigured()) {
            try {
                auth = KalshiAuth.fromEnv();
            } catch (Exception e) {
                System.err.println("Kalshi credentials present but unusable (" + e.getMessage()
                        + "); falling back to polling.");
            }
        }

        if (auth != null) {
            feed = new WebSocketPriceFeed(quoteStore, auth);
            portfolio = new KalshiPortfolio(auth);
        } else {
            feed = new PollingPriceFeed(quoteStore);
        }

        System.out.println("Kalshi environment: " + KalshiEnv.name());
        System.out.println("Price feed: " + feed.name());
        System.out.println("Account balance: " + (portfolio != null ? "enabled" : "unavailable (no API key)"));
        feed.start(initial.marketTickers());

        // Trading bot (needs credentials to place orders; env-gated by BotConfig).
        if (auth != null) {
            BotConfig botConfig = BotConfig.fromEnv();
            KalshiOrders ordersClient = new KalshiOrders(auth, botConfig.allowedEnvs);

            // Decision engine: Claude when enabled + configured, else the closed-form
            // model. The deterministic decider is also Claude's fallback on failure.
            TradeDecider deterministic = new DeterministicDecider();
            TradeDecider decider = deterministic;
            if (botConfig.aiEnabled && ClaudeDecider.isConfigured()) {
                decider = new ClaudeDecider(botConfig, deterministic);
                System.out.println("Trade decider: Claude (" + botConfig.aiModel
                        + "), deterministic fallback");
            } else {
                System.out.println("Trade decider: deterministic"
                        + (botConfig.aiEnabled ? " (AI enabled but ANTHROPIC_API_KEY missing)" : ""));
            }

            bot = new TradingBot(botConfig, ordersClient, portfolio, new MlbLiveClient(),
                    quoteStore, botStore, this::snapshotQuietly, decider, new RiskEnforcer());
            bot.start();
        } else {
            System.out.println("Trading bot: idle (no Kalshi credentials)");
        }
    }

    /** Non-throwing snapshot accessor for the bot. */
    private Snapshot snapshotQuietly() {
        try {
            return snapshot();
        } catch (Exception e) {
            return null;
        }
    }

    private void refreshSubscription() {
        try {
            Snapshot snap = service.load();
            synchronized (this) {
                cached = snap;
                cachedAt = Instant.now();
            }
            if (feed != null) feed.resubscribe(snap.marketTickers());
        } catch (Exception e) {
            System.err.println("[games-refresh] " + e.getMessage());
        }
    }

    // --- handlers ----------------------------------------------------------

    private void handleStream(HttpExchange ex) throws IOException {
        // response stays open; send a full snapshot so the page syncs at once
        broadcaster.addClient(ex, quoteStore.snapshot());
    }

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            Snapshot snap = snapshot();
            JsonObject root = GamesJson.build(snap, Instant.now(), quoteStore, botStore);
            root.add("account", accountJson());
            respond(ex, 200, "application/json; charset=utf-8", GSON.toJson(root));
        } catch (Exception e) {
            String err = GSON.toJson(java.util.Map.of("error", String.valueOf(e.getMessage())));
            respond(ex, 502, "application/json; charset=utf-8", err);
        }
    }

    /** Account cash + portfolio value, or {@code available:false} when unavailable. */
    private JsonObject accountJson() {
        JsonObject a = new JsonObject();
        if (portfolio == null) {
            a.addProperty("available", false);
            return a;
        }
        try {
            Balance b = balance();
            a.addProperty("available", true);
            a.addProperty("cashCents", b.cashCents());
            a.addProperty("portfolioCents", b.portfolioValueCents());
            a.addProperty("totalCents", b.totalCents());
        } catch (Exception e) {
            a.addProperty("available", false);
            a.addProperty("error", String.valueOf(e.getMessage()));
        }
        return a;
    }

    /** Cached balance lookup (refreshes once {@link #BALANCE_TTL} elapses). */
    private synchronized Balance balance() throws IOException, InterruptedException {
        Instant now = Instant.now();
        if (cachedBalance == null || balanceAt == null
                || Duration.between(balanceAt, now).compareTo(BALANCE_TTL) > 0) {
            cachedBalance = portfolio.fetchBalance();
            balanceAt = now;
        }
        return cachedBalance;
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            respond(ex, 404, "text/plain", "Not found");
            return;
        }
        try (InputStream in = getClass().getResourceAsStream("/index.html")) {
            if (in == null) {
                respond(ex, 500, "text/plain", "index.html missing from resources");
                return;
            }
            respond(ex, 200, "text/html; charset=utf-8",
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Returns a cached game-list snapshot, refreshing once the TTL has elapsed. */
    private synchronized Snapshot snapshot() throws IOException, InterruptedException {
        Instant now = Instant.now();
        if (cached == null || cachedAt == null
                || Duration.between(cachedAt, now).compareTo(GAMES_CACHE_TTL) > 0) {
            cached = service.load();
            cachedAt = now;
        }
        return cached;
    }

    private static void respond(HttpExchange ex, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
