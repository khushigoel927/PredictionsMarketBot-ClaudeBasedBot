package com.kg.predictions.app;

import com.kg.predictions.mlb.MlbStatsClient.MlbGame;
import com.kg.predictions.mlb.TeamAliases;
import com.kg.predictions.model.Game;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Joins Kalshi games to MLB Stats games (for score + inning), matching on the
 * canonical away/home team pair and choosing the schedule entry whose first
 * pitch is closest to Kalshi's. The closest-start rule disambiguates the same
 * two teams meeting on consecutive days of a series.
 */
public final class GameMatcher {

    /** Max gap between the two feeds' start times to still call it the same game. */
    private static final Duration MAX_GAP = Duration.ofHours(18);

    private GameMatcher() {}

    /** Mutates each Game by attaching its live score when a match is found. */
    public static void attachLiveScores(List<Game> games, List<MlbGame> mlbGames) {
        for (Game game : games) {
            String awayCanon = TeamAliases.canon(game.away().teamAbbrev());
            String homeCanon = TeamAliases.canon(game.home().teamAbbrev());

            MlbGame best = null;
            long bestGap = Long.MAX_VALUE;

            for (MlbGame mlb : mlbGames) {
                if (!awayCanon.equals(mlb.awayCanon()) || !homeCanon.equals(mlb.homeCanon())) {
                    continue;
                }
                long gap = gapMillis(game.startTime(), mlb.start());
                if (gap < bestGap) {
                    bestGap = gap;
                    best = mlb;
                }
            }

            if (best != null && bestGap <= MAX_GAP.toMillis()) {
                game.attachLive(best.score());
                game.attachGamePk(best.gamePk());
            }
        }
    }

    private static long gapMillis(Instant a, Instant b) {
        if (a == null || b == null) return Long.MAX_VALUE;
        return Math.abs(Duration.between(a, b).toMillis());
    }
}
