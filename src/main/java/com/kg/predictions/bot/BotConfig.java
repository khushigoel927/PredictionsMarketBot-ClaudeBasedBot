package com.kg.predictions.bot;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tunable parameters for the trading bot. Defaults implement the "Moderate"
 * risk profile. Environment gating is configurable (not hardcoded): the bot may
 * only place orders in {@link #allowedEnvs}, which defaults to demo only but can
 * include production via {@code BOT_TRADING_ENVS} once tested.
 */
public final class BotConfig {

    // --- environment gating ---
    public final Set<String> allowedEnvs;   // e.g. {"demo"} or {"demo","production"}
    public final boolean enabled;            // BOT_ENABLED=false disables entirely

    // --- entry gates (Moderate) ---
    public final double winProbFloor;        // only trade a side at/above this model win prob
    public final int minEdgeCents;           // model fair cents - ask must be >= this
    public final int maxPriceCents;          // never pay above this
    public final int minInning;              // only trade at/after this inning

    // --- sizing / caps ---
    public final int maxContractsPerOrder;
    public final int maxContractsPerGame;
    public final int maxBidsPerGame;         // hard cap on order placements per game

    // --- pacing ---
    public final int cycleSeconds;           // engine loop period
    public final int rePriceMinIntervalSec;  // min time between (re)placements per game/side
    public final int materialMoveCents;      // only re-price when fair moves this much
    public final int pitchChangeCooldownSec; // skip trading this long after a bullpen change

    private BotConfig(Set<String> allowedEnvs, boolean enabled, double winProbFloor,
                      int minEdgeCents, int maxPriceCents, int minInning,
                      int maxContractsPerOrder, int maxContractsPerGame, int maxBidsPerGame,
                      int cycleSeconds, int rePriceMinIntervalSec, int materialMoveCents,
                      int pitchChangeCooldownSec) {
        this.allowedEnvs = allowedEnvs;
        this.enabled = enabled;
        this.winProbFloor = winProbFloor;
        this.minEdgeCents = minEdgeCents;
        this.maxPriceCents = maxPriceCents;
        this.minInning = minInning;
        this.maxContractsPerOrder = maxContractsPerOrder;
        this.maxContractsPerGame = maxContractsPerGame;
        this.maxBidsPerGame = maxBidsPerGame;
        this.cycleSeconds = cycleSeconds;
        this.rePriceMinIntervalSec = rePriceMinIntervalSec;
        this.materialMoveCents = materialMoveCents;
        this.pitchChangeCooldownSec = pitchChangeCooldownSec;
    }

    /** Moderate defaults, with environment gating read from {@code BOT_TRADING_ENVS}. */
    public static BotConfig fromEnv() {
        return new BotConfig(
                parseEnvs(System.getenv("BOT_TRADING_ENVS")),
                !"false".equalsIgnoreCase(System.getenv("BOT_ENABLED")),
                0.78,   // winProbFloor
                5,      // minEdgeCents
                92,     // maxPriceCents
                7,      // minInning (innings 7-9)
                10,     // maxContractsPerOrder
                50,     // maxContractsPerGame
                100,    // maxBidsPerGame
                10,     // cycleSeconds
                15,     // rePriceMinIntervalSec
                2,      // materialMoveCents
                90      // pitchChangeCooldownSec
        );
    }

    /** Parse a comma-separated env list; default to demo only when unset/blank. */
    private static Set<String> parseEnvs(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of("demo");
        }
        Set<String> set = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return set.isEmpty() ? Set.of("demo") : set;
    }
}
