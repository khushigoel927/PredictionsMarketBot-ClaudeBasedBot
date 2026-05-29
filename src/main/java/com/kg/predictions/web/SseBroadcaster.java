package com.kg.predictions.web;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.kg.predictions.model.LivePrice;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans out price changes to all connected browsers via Server-Sent Events.
 * Each open {@code /api/stream} request is held here; {@link #broadcast} writes
 * one {@code data: {json}} event per change and prunes connections that died.
 *
 * <p>A periodic {@link #heartbeat()} keeps idle connections alive (and flushes
 * through any buffering proxy), and {@link #addClient} sends a full price
 * snapshot on connect so a freshly loaded or reconnected page is immediately in
 * sync without waiting for the next price change.
 */
public final class SseBroadcaster {

    private final List<OutputStream> clients = new CopyOnWriteArrayList<>();

    /**
     * Register a long-lived SSE response stream, prime it, and send the current
     * price snapshot so the browser syncs immediately.
     */
    public void addClient(HttpExchange exchange, Map<String, LivePrice> snapshot) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no"); // disable proxy buffering
        exchange.sendResponseHeaders(200, 0); // 0 => chunked, stays open

        OutputStream os = exchange.getResponseBody();
        // Comment line makes EventSource fire "open" immediately.
        write(os, ": connected\n\n");
        // Initial full sync.
        for (Map.Entry<String, LivePrice> e : snapshot.entrySet()) {
            write(os, frame(e.getKey(), e.getValue()));
        }
        clients.add(os);
    }

    /** Push a single market's new price to every connected browser. */
    public void broadcast(String marketId, LivePrice price) {
        String frame = frame(marketId, price);
        for (OutputStream os : clients) {
            if (!write(os, frame)) {
                clients.remove(os); // connection closed; drop it
            }
        }
    }

    /** Keep-alive comment sent to every client; prunes dead connections. */
    public void heartbeat() {
        for (OutputStream os : clients) {
            if (!write(os, ": ping\n\n")) {
                clients.remove(os);
            }
        }
    }

    private static String frame(String marketId, LivePrice price) {
        JsonObject o = new JsonObject();
        o.addProperty("marketId", marketId);
        o.addProperty("bid", price.yesBid());
        o.addProperty("ask", price.yesAsk());
        o.addProperty("last", price.lastCents());
        o.addProperty("win", Math.round(price.impliedChancePct()));
        o.addProperty("bidSize", price.yesBidSize());
        o.addProperty("askSize", price.yesAskSize());
        return "data: " + o + "\n\n";
    }

    /** Returns false if the write failed (client disconnected). */
    private boolean write(OutputStream os, String text) {
        try {
            os.write(text.getBytes(StandardCharsets.UTF_8));
            os.flush();
            return true;
        } catch (IOException e) {
            try { os.close(); } catch (IOException ignored) { }
            return false;
        }
    }

    public int clientCount() {
        return clients.size();
    }
}
