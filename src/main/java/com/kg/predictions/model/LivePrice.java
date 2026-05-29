package com.kg.predictions.model;

/**
 * Current top-of-book prices for a single Kalshi market, in whole cents.
 * Fed by a {@code PriceFeed} (WebSocket or polling) into the QuoteStore and
 * pushed to the browser for real-time bid/ask updates.
 *
 * @param yesBid     best Yes bid, in cents
 * @param yesAsk     best Yes ask/offer, in cents
 * @param lastCents  last traded price, in cents
 * @param yesBidSize contracts resting at the Yes bid
 * @param yesAskSize contracts resting at the Yes ask
 */
public record LivePrice(int yesBid, int yesAsk, int lastCents, int yesBidSize, int yesAskSize) {

    /** Market-implied win chance (%), mirroring {@link Quote#impliedChancePct()}. */
    public double impliedChancePct() {
        if (yesBid > 0 && yesAsk > 0) return (yesBid + yesAsk) / 2.0;
        if (yesAsk > 0) return yesAsk;
        if (yesBid > 0) return yesBid;
        return lastCents;
    }
}
