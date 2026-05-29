package com.kg.predictions.kalshi;

import com.google.gson.JsonObject;
import com.kg.predictions.net.HttpJson;

import java.io.IOException;
import java.util.Set;

/**
 * Places and cancels orders on Kalshi (authenticated). Trading is gated to a
 * configurable set of environments — the safe default is demo only, but
 * production can be enabled by config (see {@code BotConfig.allowedEnvs}). This
 * gate is enforced here as defense in depth: no order can be sent in an env that
 * isn't in {@link #allowedEnvs}.
 */
public final class KalshiOrders {

    private static final String ORDERS_PATH = "/trade-api/v2/portfolio/orders";

    private final KalshiAuth auth;
    private final Set<String> allowedEnvs;

    public KalshiOrders(KalshiAuth auth, Set<String> allowedEnvs) {
        this.auth = auth;
        this.allowedEnvs = Set.copyOf(allowedEnvs);
    }

    /** Result of an order placement. */
    public record OrderResult(String orderId, String status) {}

    /**
     * Place a careful resting (post-only) limit buy. {@code side} is "yes" or "no";
     * {@code priceCents} is the limit price in cents (1-99).
     *
     * @throws IllegalStateException if the active environment isn't allowed to trade
     * @throws IOException           if Kalshi rejects the order (e.g. post-only would cross)
     */
    public OrderResult placeLimit(String ticker, String side, int priceCents, int count,
                                  String clientOrderId) throws IOException, InterruptedException {
        assertTradingAllowed();

        JsonObject body = new JsonObject();
        body.addProperty("ticker", ticker);
        body.addProperty("client_order_id", clientOrderId);
        body.addProperty("action", "buy");
        body.addProperty("side", side);
        body.addProperty("count", count);
        body.addProperty("type", "limit");
        body.addProperty("post_only", true);
        if ("yes".equals(side)) {
            body.addProperty("yes_price", priceCents);
        } else {
            body.addProperty("no_price", priceCents);
        }

        JsonObject resp = HttpJson.postObject(
                KalshiEnv.restBase() + "/portfolio/orders",
                auth.headers("POST", ORDERS_PATH),
                body.toString());

        JsonObject order = resp.has("order") && resp.get("order").isJsonObject()
                ? resp.getAsJsonObject("order") : resp;
        return new OrderResult(str(order, "order_id"), str(order, "status"));
    }

    /** Cancel a resting order; returns true on success. */
    public boolean cancel(String orderId) throws IOException, InterruptedException {
        assertTradingAllowed();
        String path = ORDERS_PATH + "/" + orderId;
        int status = HttpJson.delete(KalshiEnv.restBase() + "/portfolio/orders/" + orderId,
                auth.headers("DELETE", path));
        return status / 100 == 2;
    }

    private void assertTradingAllowed() {
        if (!allowedEnvs.contains(KalshiEnv.name())) {
            throw new IllegalStateException(
                    "Trading not allowed in environment '" + KalshiEnv.name()
                            + "' (allowed: " + allowedEnvs + ")");
        }
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
