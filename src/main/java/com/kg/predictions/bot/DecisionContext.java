package com.kg.predictions.bot;

import com.kg.predictions.model.LiveState;

/**
 * Immutable snapshot of everything a {@link TradeDecider} needs to judge one
 * candidate trade on one game's already-chosen side. Built by {@link TradingBot}
 * after the cheap structural + deterministic pre-screen passes, so a decider
 * (notably {@link ClaudeDecider}) is only ever invoked on plausible candidates.
 *
 * @param eventTicker         Kalshi event id (one game)
 * @param marketId            Kalshi market ticker for the backed side
 * @param teamAbbrev          backed team abbreviation
 * @param homeSide            true if the backed side is the home team
 * @param live               live MLB game state
 * @param yesBidCents        best YES bid for the backed side, cents
 * @param yesAskCents        best YES ask for the backed side, cents
 * @param impliedPct         market-implied win chance (0-100) from the book midpoint
 * @param heldContracts      contracts already held on this market
 * @param bidsPlaced         order placements already made on this game
 * @param cashCents          account cash available, cents
 * @param portfolioValueCents current value of all open positions, cents
 * @param config             risk limits + AI settings
 */
public record DecisionContext(
        String eventTicker,
        String marketId,
        String teamAbbrev,
        boolean homeSide,
        LiveState live,
        int yesBidCents,
        int yesAskCents,
        double impliedPct,
        int heldContracts,
        int bidsPlaced,
        int cashCents,
        int portfolioValueCents,
        BotConfig config
) {
    /** The closed-form model's win probability (0-1) for the backed side. */
    public double deterministicWinProb() {
        double pHome = WinProbability.homeWinProb(live);
        return homeSide ? pHome : 1.0 - pHome;
    }
}
