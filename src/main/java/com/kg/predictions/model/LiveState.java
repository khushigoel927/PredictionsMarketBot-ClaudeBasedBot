package com.kg.predictions.model;

/**
 * Rich live game state from the MLB Stats live feed — the inputs the trading
 * bot's win-probability model needs. Distinct from {@link LiveScore} (which is
 * the lightweight score+inning used by the display).
 *
 * @param inning            current inning number (1-based; 0 if unknown)
 * @param topInning         true if top half (away batting, home pitching)
 * @param outs              outs in the current half-inning (0-2; 3 at change)
 * @param onFirst           runner on first
 * @param onSecond          runner on second
 * @param onThird           runner on third
 * @param homeScore         home runs
 * @param awayScore         away runs
 * @param defensePitcherId  id of the pitcher currently on the mound (for bullpen-change detection)
 * @param live              game is in progress
 * @param isFinal           game has ended
 */
public record LiveState(
        int inning,
        boolean topInning,
        int outs,
        boolean onFirst,
        boolean onSecond,
        boolean onThird,
        int homeScore,
        int awayScore,
        long defensePitcherId,
        boolean live,
        boolean isFinal
) {
    /** Home lead in runs (negative if home trails). */
    public int homeLead() {
        return homeScore - awayScore;
    }

    /** Base state index 0-7 (bit0=first, bit1=second, bit2=third). */
    public int baseStateIndex() {
        return (onFirst ? 1 : 0) | (onSecond ? 2 : 0) | (onThird ? 4 : 0);
    }
}
