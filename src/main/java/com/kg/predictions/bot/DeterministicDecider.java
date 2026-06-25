package com.kg.predictions.bot;

import java.util.Optional;

/**
 * The original closed-form strategy: {@link WinProbability} + edge versus the
 * ask. It estimates the backed side's win probability analytically and wants to
 * trade only when the model fair value beats the ask by the configured edge.
 *
 * <p>Used both as a standalone engine (when AI is disabled or unconfigured) and
 * as the fallback for {@link ClaudeDecider} whenever an API call can't be made
 * or its result can't be trusted. The hard caps still run afterwards in
 * {@link RiskEnforcer}, so this only expresses the model's preference.
 */
public final class DeterministicDecider implements TradeDecider {

    @Override
    public Optional<RawDecision> decide(DecisionContext ctx) {
        BotConfig c = ctx.config();
        double p = ctx.deterministicWinProb();
        int fairCents = (int) Math.round(p * 100);
        int ask = ctx.yesAskCents();

        boolean edge = p >= c.winProbFloor
                && ask > 0 && ask <= c.maxPriceCents
                && (fairCents - ask) >= c.minEdgeCents;
        if (!edge) {
            return Optional.of(RawDecision.noTrade("deterministic", "no model edge vs ask"));
        }

        // Careful maker price: rest just below the bid+1 / fair-edge / ask, as the
        // original engine did. RiskEnforcer re-clamps this into the risk envelope.
        int suggested = Math.min(Math.min(ctx.yesBidCents() + 1, fairCents - c.minEdgeCents), ask - 1);
        return Optional.of(new RawDecision(
                true, p, suggested, c.maxContractsPerOrder,
                "model edge " + (fairCents - ask) + "¢ vs ask", "deterministic"));
    }
}
