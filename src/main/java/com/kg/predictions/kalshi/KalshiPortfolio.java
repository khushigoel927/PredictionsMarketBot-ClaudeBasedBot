package com.kg.predictions.kalshi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kg.predictions.net.HttpJson;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the authenticated account balance from Kalshi
 * ({@code GET /portfolio/balance}). Requires {@link KalshiAuth} credentials;
 * the call is signed with the same RSA-PSS scheme as the WebSocket handshake.
 */
public final class KalshiPortfolio {

    /** Path used both for the request and (host-less) for the auth signature. */
    private static final String BALANCE_PATH = "/trade-api/v2/portfolio/balance";

    private final KalshiAuth auth;

    public KalshiPortfolio(KalshiAuth auth) {
        this.auth = auth;
    }

    /**
     * @param cashCents           available balance (cash) in cents
     * @param portfolioValueCents current value of all open positions in cents
     */
    public record Balance(int cashCents, int portfolioValueCents) {
        /** Total account value = cash + positions. */
        public int totalCents() {
            return cashCents + portfolioValueCents;
        }
    }

    public Balance fetchBalance() throws IOException, InterruptedException {
        String url = KalshiEnv.restBase() + "/portfolio/balance";
        JsonObject o = HttpJson.getObject(url, auth.headers("GET", BALANCE_PATH));
        return new Balance(asInt(o, "balance"), asInt(o, "portfolio_value"));
    }

    /** A held position in one market: net contract count and total cost in cents. */
    public record Position(int count, int exposureCents) {
        public double avgPriceDollars() {
            return count == 0 ? 0.0 : (exposureCents / 100.0) / count;
        }
    }

    /** Current market positions keyed by market ticker. */
    public Map<String, Position> fetchPositions() throws IOException, InterruptedException {
        String path = "/trade-api/v2/portfolio/positions";
        JsonObject o = HttpJson.getObject(KalshiEnv.restBase() + "/portfolio/positions",
                auth.headers("GET", path));

        Map<String, Position> out = new HashMap<>();
        JsonElement arr = o.get("market_positions");
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement el : arr.getAsJsonArray()) {
                JsonObject p = el.getAsJsonObject();
                String ticker = p.has("ticker") ? p.get("ticker").getAsString() : null;
                if (ticker == null) continue;
                int count = Math.abs(asInt(p, "position"));
                int exposure = Math.abs(asInt(p, "market_exposure"));
                if (count > 0) out.put(ticker, new Position(count, exposure));
            }
        }
        return out;
    }

    private static int asInt(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
    }
}
