package com.kg.predictions.feed;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kg.predictions.app.QuoteStore;
import com.kg.predictions.kalshi.KalshiClient;
import com.kg.predictions.model.LivePrice;
import com.kg.predictions.net.HttpJson;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Credential-free fallback feed: every {@value #INTERVAL_SECONDS}s it batch-reads
 * the markets via {@code GET /markets?tickers=...} and pushes any changes into the
 * {@link QuoteStore}. Used when no Kalshi WebSocket credentials are configured.
 */
public final class PollingPriceFeed implements PriceFeed {

    private static final int INTERVAL_SECONDS = 2;
    private static final int BATCH = 100; // tickers per request

    private final QuoteStore store;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "price-poller");
                t.setDaemon(true);
                return t;
            });

    private final List<String> tickers = new CopyOnWriteArrayList<>();

    public PollingPriceFeed(QuoteStore store) {
        this.store = store;
    }

    @Override
    public void start(Collection<String> marketTickers) {
        resubscribe(marketTickers);
        scheduler.scheduleWithFixedDelay(this::pollOnce, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void resubscribe(Collection<String> marketTickers) {
        tickers.clear();
        tickers.addAll(marketTickers);
    }

    private void pollOnce() {
        try {
            List<String> all = new ArrayList<>(tickers);
            for (int i = 0; i < all.size(); i += BATCH) {
                List<String> batch = all.subList(i, Math.min(i + BATCH, all.size()));
                pollBatch(batch);
            }
        } catch (Exception e) {
            // transient network errors are expected; next tick retries
            System.err.println("[poller] " + e.getMessage());
        }
    }

    private void pollBatch(List<String> batch) throws Exception {
        String csv = String.join(",", batch);
        String url = KalshiClient.BASE + "/markets?limit=1000&tickers="
                + URLEncoder.encode(csv, StandardCharsets.UTF_8);

        JsonObject page = HttpJson.getObject(url);
        JsonElement marketsEl = page.get("markets");
        if (marketsEl == null || !marketsEl.isJsonArray()) return;

        for (JsonElement el : marketsEl.getAsJsonArray()) {
            JsonObject m = el.getAsJsonObject();
            String ticker = str(m, "ticker");
            if (ticker == null) continue;
            store.update(ticker, new LivePrice(
                    KalshiClient.dollarsToCents(str(m, "yes_bid_dollars")),
                    KalshiClient.dollarsToCents(str(m, "yes_ask_dollars")),
                    KalshiClient.dollarsToCents(str(m, "last_price_dollars")),
                    KalshiClient.fpToInt(str(m, "yes_bid_size_fp")),
                    KalshiClient.fpToInt(str(m, "yes_ask_size_fp"))));
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    @Override
    public String name() {
        return "REST polling (" + INTERVAL_SECONDS + "s)";
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
