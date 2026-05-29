package com.kg.predictions.app;

import com.kg.predictions.model.LivePrice;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * The single source of truth for current market prices. A {@code PriceFeed}
 * (WebSocket or polling) writes updates here; the SSE broadcaster reads them.
 *
 * <p>{@link #update} fires the registered change listener <em>only when the
 * price actually changed</em>, so the browser is pushed a diff rather than a
 * steady stream of no-op ticks.
 */
public final class QuoteStore {

    private final Map<String, LivePrice> prices = new ConcurrentHashMap<>();

    /** Notified with (marketId, newPrice) whenever a stored price changes. */
    private volatile BiConsumer<String, LivePrice> onChange = (id, p) -> {};

    public void setChangeListener(BiConsumer<String, LivePrice> listener) {
        this.onChange = listener == null ? (id, p) -> {} : listener;
    }

    /** Store the latest price for a market; notify listener if it differs. */
    public void update(String marketId, LivePrice price) {
        if (marketId == null || price == null) return;
        LivePrice previous = prices.put(marketId, price);
        if (!Objects.equals(previous, price)) {
            onChange.accept(marketId, price);
        }
    }

    /** Latest known price for a market, or {@code null} if none seen yet. */
    public LivePrice get(String marketId) {
        return prices.get(marketId);
    }

    public Map<String, LivePrice> snapshot() {
        return Map.copyOf(prices);
    }
}
