package com.kg.predictions.bot;

/**
 * A decider's raw judgment, <em>before</em> hard risk limits are applied by
 * {@link RiskEnforcer}. {@link #winProbability} is the decider's own estimate
 * for the backed side (Claude's estimate in the AI path, the closed-form model's
 * in the deterministic path) — it drives the fair-value and edge checks.
 *
 * @param trade               whether the decider wants to rest a maker order
 * @param winProbability      decider's win-prob estimate for the backed side (0-1)
 * @param suggestedPriceCents suggested maker limit price, cents (1-99)
 * @param suggestedContracts  suggested order size in contracts (>= 0)
 * @param reasoning           short rationale (logged + shown in the UI)
 * @param source              "deterministic" or "claude"
 */
public record RawDecision(
        boolean trade,
        double winProbability,
        int suggestedPriceCents,
        int suggestedContracts,
        String reasoning,
        String source
) {
    public static RawDecision noTrade(String source, String reasoning) {
        return new RawDecision(false, 0.0, 0, 0, reasoning, source);
    }
}
