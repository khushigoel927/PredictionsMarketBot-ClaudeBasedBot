package com.kg.predictions.bot;

import java.util.Optional;

/**
 * Strategy that judges whether (and how) to trade one candidate side. Returns a
 * {@link RawDecision} (its raw judgment); the hard risk limits are enforced
 * separately and non-negotiably by {@link RiskEnforcer}. An empty result means
 * "no opinion / no trade".
 *
 * <p>Implementations: {@link DeterministicDecider} (closed-form model, also the
 * fallback engine) and {@link ClaudeDecider} (delegates the estimate + decision
 * to the Claude API, falling back to the deterministic decider on any failure).
 */
public interface TradeDecider {
    Optional<RawDecision> decide(DecisionContext ctx);
}
