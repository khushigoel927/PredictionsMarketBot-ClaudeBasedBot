package com.kg.predictions.bot;

import com.kg.predictions.model.LiveState;

/**
 * Analytical live win-probability estimate for a baseball game, from the home
 * team's perspective. Self-contained — no external WP service or bulky tables.
 *
 * <p>Approach:
 * <ol>
 *   <li>The batting team's <em>current</em> half-inning contributes its
 *       run-expectancy from a 24-value RE24 table keyed by base state + outs.</li>
 *   <li>Each remaining full half-inning contributes the league mean
 *       {@link #MU} runs.</li>
 *   <li>Each team's final score = current score + Poisson(expected remaining
 *       runs); P(home wins) is the convolution of the two Poissons, with ties
 *       (→ extra innings) scored as a coin flip.</li>
 * </ol>
 *
 * <p>This is an approximation, not a guarantee. The bot pairs it with a high
 * win-prob floor and edge threshold so it only acts where the estimate is
 * robust (notably large late-inning leads). Walk-off scoring caps don't affect
 * the win/lose indicator, so they're intentionally not modelled; reliever
 * quality is out of scope (handled as a short post-change cooldown by the bot).
 */
public final class WinProbability {

    /** League-average runs per half-inning. */
    private static final double MU = 0.48;
    private static final int MAX_ADD = 15; // runs added beyond current score to enumerate

    /** RE24[outs][baseState] — expected runs in the rest of the current half-inning.
     *  baseState bits: 1=first, 2=second, 4=third. */
    private static final double[][] RE = {
            {0.481, 0.859, 1.100, 1.437, 1.350, 1.784, 1.964, 2.292}, // 0 outs
            {0.254, 0.509, 0.664, 0.884, 0.950, 1.130, 1.376, 1.541}, // 1 out
            {0.098, 0.224, 0.319, 0.429, 0.353, 0.478, 0.580, 0.752}, // 2 outs
    };

    private WinProbability() {}

    /** Probability (0-1) the home team wins, given the current live state. */
    public static double homeWinProb(LiveState s) {
        int inning = Math.max(1, s.inning());
        boolean top = s.topInning();
        int outs = Math.min(2, Math.max(0, s.outs()));
        int base = s.baseStateIndex();

        double awayCurrentHalf = top ? RE[outs][base] : 0.0;
        double homeCurrentHalf = top ? 0.0 : RE[outs][base];

        // Full half-innings still entirely upcoming through the bottom of the 9th.
        int awayFullTops = Math.max(0, 9 - inning);
        int homeFullBottoms = top ? Math.max(0, 10 - inning) : Math.max(0, 9 - inning);

        double rAway = awayCurrentHalf + MU * awayFullTops;
        double rHome = homeCurrentHalf + MU * homeFullBottoms;

        double[] pAway = poissonPmf(rAway);
        double[] pHome = poissonPmf(rHome);

        double pHomeWin = 0.0;
        for (int x = 0; x <= MAX_ADD; x++) {
            int awayFinal = s.awayScore() + x;
            for (int y = 0; y <= MAX_ADD; y++) {
                int homeFinal = s.homeScore() + y;
                double prob = pAway[x] * pHome[y];
                if (homeFinal > awayFinal) pHomeWin += prob;
                else if (homeFinal == awayFinal) pHomeWin += 0.5 * prob; // tie -> extras ~ coin flip
            }
        }
        return Math.max(0.0, Math.min(1.0, pHomeWin));
    }

    /** Poisson pmf for k = 0..MAX_ADD (small upper tail truncated; negligible for these means). */
    private static double[] poissonPmf(double lambda) {
        double[] p = new double[MAX_ADD + 1];
        double term = Math.exp(-lambda); // k = 0
        p[0] = term;
        for (int k = 1; k <= MAX_ADD; k++) {
            term *= lambda / k;
            p[k] = term;
        }
        return p;
    }
}
