package com.kg.predictions.bot;

/**
 * A fully risk-checked instruction to rest one maker order. Produced only by
 * {@link RiskEnforcer}: every field is already clamped to the configured limits
 * (price ceiling, min edge, per-order / per-game / account-exposure / cash caps),
 * so {@link TradingBot} can place it without re-validating risk.
 *
 * @param makerPriceCents resting maker price, cents (>= 1, below the ask)
 * @param contracts       order size in contracts (>= 1)
 * @param fairCents       fair value in cents implied by the winning probability
 * @param reasoning       rationale carried through for logging + the UI
 * @param source          which decider produced the underlying judgment
 */
public record TradeIntent(
        int makerPriceCents,
        int contracts,
        int fairCents,
        String reasoning,
        String source
) {}
