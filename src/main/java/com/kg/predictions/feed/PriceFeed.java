package com.kg.predictions.feed;

import java.util.Collection;

/**
 * A source of live market prices. Implementations push updates into a
 * {@code QuoteStore}; the rest of the app neither knows nor cares whether the
 * prices arrive via WebSocket push or REST polling.
 */
public interface PriceFeed extends AutoCloseable {

    /** Begin feeding prices for the given market tickers. */
    void start(Collection<String> marketTickers);

    /** Replace the set of subscribed tickers (e.g. when games open/settle). */
    void resubscribe(Collection<String> marketTickers);

    /** Short label for logging, e.g. "Kalshi WebSocket". */
    String name();

    @Override
    void close();
}
