package com.kg.predictions.model;

/**
 * A single Kalshi binary market: "does THIS team win the game?".
 * Every game (event) has two of these, one per team.
 *
 * <p>Prices are stored in whole cents (0-100). On Kalshi a "Yes" contract pays
 * out $1 if the team wins, so the price in cents is also the market-implied
 * probability of that team winning. The bid is the best price someone will pay
 * for Yes; the ask (offer) is the cheapest price someone will sell Yes at.
 *
 * @param marketId   Kalshi market ticker, e.g. {@code KXMLBGAME-26MAY281840CHCPIT-CHC}
 * @param teamName   human label from Kalshi, e.g. {@code "Chicago C"}
 * @param teamAbbrev team abbreviation parsed from the market ticker, e.g. {@code "CHC"}
 * @param home       true if this is the home team
 * @param yesBid     best bid for Yes, in cents
 * @param yesAsk     best ask/offer for Yes, in cents
 * @param noBid      best bid for No, in cents
 * @param noAsk      best ask/offer for No, in cents
 * @param lastCents  last traded price, in cents
 * @param yesBidSize contracts resting at the Yes bid
 * @param yesAskSize contracts resting at the Yes ask
 */
public record Quote(
        String marketId,
        String teamName,
        String teamAbbrev,
        boolean home,
        int yesBid,
        int yesAsk,
        int noBid,
        int noAsk,
        int lastCents,
        int yesBidSize,
        int yesAskSize
) {
    /**
     * Market-implied chance this team wins, as a percentage (0-100).
     * Uses the midpoint of the Yes bid/ask spread; falls back to the last
     * traded price when the book is one-sided or empty.
     */
    public double impliedChancePct() {
        if (yesBid > 0 && yesAsk > 0) {
            return (yesBid + yesAsk) / 2.0;
        }
        if (yesAsk > 0) return yesAsk;
        if (yesBid > 0) return yesBid;
        return lastCents;
    }
}
