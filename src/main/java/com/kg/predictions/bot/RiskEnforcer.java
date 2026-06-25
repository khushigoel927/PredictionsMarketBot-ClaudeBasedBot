package com.kg.predictions.bot;

import java.util.Optional;

/**
 * The single authority for hard risk limits. It takes a decider's raw suggestion
 * and intersects it with the configured risk envelope: a decider can decline a
 * trade or be <em>more</em> conservative, but it can never widen a limit. Any
 * malformed or out-of-range suggestion is treated as a decline.
 *
 * <p>This is the layer that guarantees "the AI can decline a trade but can never
 * bypass a risk limit": every value Claude returns is clamped here before it can
 * reach {@link com.kg.predictions.kalshi.KalshiOrders}, which then re-checks the
 * environment gate as defense in depth.
 */
public final class RiskEnforcer {

    /** Apply all hard limits; empty result means "do not trade". */
    public Optional<TradeIntent> enforce(DecisionContext ctx, Optional<RawDecision> rawOpt) {
        if (rawOpt.isEmpty()) return Optional.empty();
        RawDecision raw = rawOpt.get();
        if (!raw.trade()) return Optional.empty();

        BotConfig c = ctx.config();

        // 1. Win-probability sanity + floor. The floor cannot be lowered by a decider;
        //    an out-of-range estimate is rejected outright (treated as malformed).
        double winProb = raw.winProbability();
        if (!(winProb >= 0.0 && winProb <= 1.0)) return Optional.empty();
        if (winProb < c.winProbFloor) return Optional.empty();
        int fairCents = (int) Math.round(winProb * 100);

        // 2. Price: ceiling, maker-below-ask, and min-edge envelope. Clamp the
        //    suggestion down into the envelope; never up.
        int ask = ctx.yesAskCents();
        if (ask <= 0 || ask > c.maxPriceCents) return Optional.empty();
        int envelopeMax = Math.min(c.maxPriceCents, Math.min(fairCents - c.minEdgeCents, ask - 1));
        if (envelopeMax < 1) return Optional.empty();
        int price = raw.suggestedPriceCents();
        if (price < 1 || price > envelopeMax) price = envelopeMax;
        if ((fairCents - price) < c.minEdgeCents) return Optional.empty();

        // 3. Size: per-order, per-game, account-exposure and cash caps.
        int gameRoom = c.maxContractsPerGame - ctx.heldContracts();
        if (gameRoom <= 0) return Optional.empty();
        int requested = raw.suggestedContracts() > 0 ? raw.suggestedContracts() : c.maxContractsPerOrder;
        int size = Math.min(requested, Math.min(c.maxContractsPerOrder, gameRoom));
        size = capByAccountExposure(ctx, c, price, size);
        size = capByCash(ctx, c, price, size);
        if (size <= 0) return Optional.empty();

        return Optional.of(new TradeIntent(price, size, fairCents, raw.reasoning(), raw.source()));
    }

    /** Shrink size so total open-position value stays under the account cap. */
    private int capByAccountExposure(DecisionContext ctx, BotConfig c, int price, int size) {
        if (c.maxAccountExposureCents <= 0) return size; // 0 = disabled
        int room = c.maxAccountExposureCents - ctx.portfolioValueCents();
        if (room <= 0) return 0;
        return Math.min(size, room / price);
    }

    /** Shrink size so cash stays above the reserve after paying for the order
     *  (1 contract costs {@code price} cents). A failed balance read surfaces as
     *  zero cash here, which fail-closes the trade. */
    private int capByCash(DecisionContext ctx, BotConfig c, int price, int size) {
        int spendable = ctx.cashCents() - c.minCashReserveCents;
        if (spendable <= 0) return 0;
        return Math.min(size, spendable / price);
    }
}
