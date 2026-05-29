package com.kg.predictions.model;

/**
 * Live game state pulled from the MLB Stats API (statsapi.mlb.com).
 * Kalshi exposes prices but no score or inning, so this fills that gap.
 *
 * @param awayAbbrev    away team abbreviation (MLB Stats spelling)
 * @param homeAbbrev    home team abbreviation (MLB Stats spelling)
 * @param awayScore     away runs
 * @param homeScore     home runs
 * @param detailedState e.g. "In Progress", "Final", "Pre-Game", "Scheduled"
 * @param inningOrdinal e.g. "4th" (null when not in progress)
 * @param inningState   e.g. "Top", "Bottom", "Middle", "End" (null when not in progress)
 */
public record LiveScore(
        String awayAbbrev,
        String homeAbbrev,
        int awayScore,
        int homeScore,
        String detailedState,
        String inningOrdinal,
        String inningState
) {
    public boolean inProgress() {
        return detailedState != null && detailedState.toLowerCase().contains("progress");
    }

    public boolean isFinal() {
        return detailedState != null && detailedState.toLowerCase().startsWith("final");
    }

    /** Short human description of where the game is, used for the "time left" column. */
    public String progressLabel() {
        if (inProgress() && inningState != null && inningOrdinal != null) {
            return inningState + " " + inningOrdinal;
        }
        return detailedState == null ? "?" : detailedState;
    }
}
