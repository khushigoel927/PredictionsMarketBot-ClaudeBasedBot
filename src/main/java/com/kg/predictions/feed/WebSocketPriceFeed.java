package com.kg.predictions.feed;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kg.predictions.app.QuoteStore;
import com.kg.predictions.kalshi.KalshiAuth;
import com.kg.predictions.kalshi.KalshiClient;
import com.kg.predictions.kalshi.KalshiEnv;
import com.kg.predictions.model.LivePrice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real-time price feed over Kalshi's WebSocket. Subscribes to the public
 * {@code ticker} channel for the given markets and pushes each yes_bid/yes_ask
 * update into the {@link QuoteStore}. Auto-reconnects with backoff on drop.
 *
 * <p>The handshake is authenticated with {@link KalshiAuth}; Kalshi requires
 * this even for public market-data channels.
 */
public final class WebSocketPriceFeed implements PriceFeed {

    private static final String WS_URL = KalshiEnv.wsUrl();
    private static final String WS_PATH = KalshiEnv.WS_PATH;
    private static final long MAX_BACKOFF_SECONDS = 30;

    private final QuoteStore store;
    private final KalshiAuth auth;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kalshi-ws");
                t.setDaemon(true);
                return t;
            });
    private final AtomicInteger cmdId = new AtomicInteger(1);

    private final List<String> tickers = new CopyOnWriteArrayList<>();
    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile long backoffSeconds = 1;

    public WebSocketPriceFeed(QuoteStore store, KalshiAuth auth) {
        this.store = store;
        this.auth = auth;
    }

    @Override
    public void start(Collection<String> marketTickers) {
        tickers.clear();
        tickers.addAll(marketTickers);
        connect();
    }

    @Override
    public void resubscribe(Collection<String> marketTickers) {
        tickers.clear();
        tickers.addAll(marketTickers);
        WebSocket ws = socket;
        if (ws != null) {
            sendSubscribe(ws);
        }
    }

    private void connect() {
        if (closed) return;
        try {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder();
            for (Map.Entry<String, String> h : auth.headers("GET", WS_PATH).entrySet()) {
                builder.header(h.getKey(), h.getValue());
            }
            builder.buildAsync(URI.create(WS_URL), new Listener())
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            System.err.println("[kalshi-ws] connect failed: " + err.getMessage());
                            scheduleReconnect();
                        }
                    });
        } catch (Exception e) {
            System.err.println("[kalshi-ws] connect error: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (closed) return;
        long delay = backoffSeconds;
        backoffSeconds = Math.min(backoffSeconds * 2, MAX_BACKOFF_SECONDS);
        System.err.println("[kalshi-ws] reconnecting in " + delay + "s");
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private void sendSubscribe(WebSocket ws) {
        if (tickers.isEmpty()) return;
        JsonObject params = new JsonObject();
        com.google.gson.JsonArray channels = new com.google.gson.JsonArray();
        channels.add("ticker");
        com.google.gson.JsonArray markets = new com.google.gson.JsonArray();
        for (String t : tickers) markets.add(t);
        params.add("channels", channels);
        params.add("market_tickers", markets);

        JsonObject msg = new JsonObject();
        msg.addProperty("id", cmdId.getAndIncrement());
        msg.addProperty("cmd", "subscribe");
        msg.add("params", params);

        ws.sendText(msg.toString(), true);
    }

    private void handleMessage(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String type = root.has("type") ? root.get("type").getAsString() : "";
            if (!"ticker".equals(type) || !root.has("msg")) return;

            JsonObject m = root.getAsJsonObject("msg");
            String ticker = m.has("market_ticker") ? m.get("market_ticker").getAsString() : null;
            if (ticker == null) return;

            // Kalshi has been migrating fields from integer cents (yes_bid) to
            // dollar strings (yes_bid_dollars); accept whichever is present.
            store.update(ticker, new LivePrice(
                    cents(m, "yes_bid", "yes_bid_dollars"),
                    cents(m, "yes_ask", "yes_ask_dollars"),
                    cents(m, "price", "price_dollars", "last_price_dollars"),
                    fp(m, "yes_bid_size_fp", "yes_bid_size"),
                    fp(m, "yes_ask_size_fp", "yes_ask_size")));
        } catch (Exception e) {
            // ignore non-ticker / malformed frames
        }
    }

    /** Price in cents from the first present field (int cents, or a dollar string). */
    private static int cents(JsonObject o, String centsKey, String... dollarKeys) {
        if (o.has(centsKey) && !o.get(centsKey).isJsonNull()) {
            return o.get(centsKey).getAsInt();
        }
        for (String k : dollarKeys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                return KalshiClient.dollarsToCents(o.get(k).getAsString());
            }
        }
        return 0;
    }

    /** Whole-number size from the first present field (fixed-point string or number). */
    private static int fp(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                return KalshiClient.fpToInt(o.get(k).getAsString());
            }
        }
        return 0;
    }

    @Override
    public String name() {
        return "Kalshi WebSocket (ticker)";
    }

    @Override
    public void close() {
        closed = true;
        WebSocket ws = socket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        scheduler.shutdownNow();
    }

    /** Accumulates fragmented text frames and dispatches complete messages. */
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            socket = ws;
            backoffSeconds = 1; // reset after a successful connect
            System.out.println("[kalshi-ws] connected; subscribing to " + tickers.size() + " markets");
            sendSubscribe(ws);
            ws.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handleMessage(buffer.toString());
                buffer.setLength(0);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            System.err.println("[kalshi-ws] closed: " + statusCode + " " + reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            System.err.println("[kalshi-ws] error: " + error.getMessage());
            scheduleReconnect();
        }
    }
}
