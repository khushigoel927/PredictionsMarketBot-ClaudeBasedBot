package com.kg.predictions.bot;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Delegates the win-probability estimate <em>and</em> the trade/no-trade call to
 * the Claude API, replacing the closed-form model on the happy path. The model
 * is asked for a strict structured decision; its numbers are then clamped to the
 * hard limits by {@link RiskEnforcer} before anything is placed.
 *
 * <p>The AI introduces failure modes the deterministic engine never had, so this
 * class fails safe to {@code fallback} (the {@link DeterministicDecider}) on:
 * <ul>
 *   <li>an exhausted daily call budget ({@link BotConfig#aiMaxCallsPerDay});</li>
 *   <li>timeouts, rate limits, connection or API errors (the SDK retries 429/5xx
 *       with backoff first, then we catch and fall back);</li>
 *   <li>a missing, malformed, or out-of-range structured response.</li>
 * </ul>
 *
 * <p>Cost is bounded three ways: {@link TradingBot} only calls this for
 * candidates that already clear the deterministic pre-screen; the request uses a
 * small {@code maxTokens} and a frozen (cacheable) system prompt; and a hard
 * daily call budget caps the worst case.
 */
public final class ClaudeDecider implements TradeDecider {

    private static final String SYSTEM_PROMPT = """
            You are a disciplined trading assistant for Kalshi MLB single-game win markets.
            Each market is a binary contract that settles at $1 if the named team wins and $0
            otherwise, so the price in whole cents equals the market-implied win probability.
            You are given the live game state, the order book for the backed team, the current
            position, and the bot's hard risk limits.

            Your job: estimate the backed team's probability of winning from the game state, and
            decide whether to rest a careful maker (post-only) buy order below the ask. Only
            choose to trade when you have a genuine edge — your fair value must clear the ask by
            at least the configured minimum edge — and never above the configured maximum price.
            When uncertain, decline. The bot enforces every risk limit after you, so suggesting a
            value outside the limits will simply be clamped or rejected; propose your honest best
            estimate. Respond only with the structured decision.""";

    /** Strict structured decision the model must return. */
    @JsonClassDescription("Trade decision for one Kalshi MLB win-market side")
    public record AiDecision(
            @JsonPropertyDescription("true to rest a maker buy order, false to skip this side")
            boolean trade,
            @JsonPropertyDescription("estimated probability (0.0-1.0) the backed team wins")
            double winProbability,
            @JsonPropertyDescription("confidence 0-100 in this estimate")
            int confidence,
            @JsonPropertyDescription("suggested maker limit price in whole cents (1-99), below the ask")
            int suggestedLimitPriceCents,
            @JsonPropertyDescription("suggested order size in contracts (0 if not trading)")
            int suggestedContracts,
            @JsonPropertyDescription("one or two sentence rationale")
            String reasoning
    ) {}

    private final AnthropicClient client;
    private final BotConfig config;
    private final TradeDecider fallback;

    // Daily call budget. Touched only by the single engine thread, but guarded
    // so a future multi-threaded caller stays correct.
    private final Object budgetLock = new Object();
    private LocalDate budgetDay = LocalDate.now(ZoneOffset.UTC);
    private int callsToday = 0;

    public ClaudeDecider(BotConfig config, TradeDecider fallback) {
        this.config = config;
        this.fallback = fallback;
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .timeout(Duration.ofSeconds(config.aiTimeoutSeconds))
                .maxRetries(2)
                .build();
    }

    /** True when an API key is present, so the decider can be constructed. */
    public static boolean isConfigured() {
        String k = System.getenv("ANTHROPIC_API_KEY");
        return k != null && !k.isBlank();
    }

    @Override
    public Optional<RawDecision> decide(DecisionContext ctx) {
        if (!claimDailyCall()) {
            return fallback.decide(ctx); // budget exhausted -> deterministic
        }
        try {
            AiDecision dec = callClaude(ctx);
            if (dec == null || !valid(dec)) {
                return fallback.decide(ctx); // missing / malformed / out-of-range
            }
            return Optional.of(new RawDecision(
                    dec.trade(), dec.winProbability(), dec.suggestedLimitPriceCents(),
                    dec.suggestedContracts(), dec.reasoning(), "claude"));
        } catch (Exception e) {
            System.err.println("[bot] Claude decide failed (" + e.getMessage()
                    + "); using deterministic fallback");
            return fallback.decide(ctx);
        }
    }

    private AiDecision callClaude(DecisionContext ctx) {
        StructuredMessageCreateParams<AiDecision> params = MessageCreateParams.builder()
                .model(config.aiModel)
                .maxTokens(1024L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .addUserMessage(userContext(ctx))
                .outputConfig(AiDecision.class)
                .build();

        return client.messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElse(null);
    }

    /** Render the live snapshot + risk envelope the model reasons over. */
    private static String userContext(DecisionContext ctx) {
        var s = ctx.live();
        BotConfig c = ctx.config();
        int gameRoom = c.maxContractsPerGame - ctx.heldContracts();
        int bidsLeft = c.maxBidsPerGame - ctx.bidsPlaced();
        return """
                BACKED TEAM: %s (%s)
                GAME STATE: inning %d %s, %d out(s), bases[1B=%b 2B=%b 3B=%b], score home %d - away %d
                MARKET (backed side, cents): yes_bid=%d yes_ask=%d implied_win=%.0f%%
                POSITION: held=%d contracts, placements_so_far=%d
                ACCOUNT: cash=%d¢ portfolio_value=%d¢
                RISK LIMITS (enforced after you): max_price=%d¢ min_edge=%d¢ \
                max_contracts_per_order=%d remaining_game_room=%d remaining_placements=%d
                """.formatted(
                ctx.teamAbbrev(), ctx.homeSide() ? "home" : "away",
                s.inning(), s.topInning() ? "top" : "bottom", s.outs(),
                s.onFirst(), s.onSecond(), s.onThird(), s.homeScore(), s.awayScore(),
                ctx.yesBidCents(), ctx.yesAskCents(), ctx.impliedPct(),
                ctx.heldContracts(), ctx.bidsPlaced(),
                ctx.cashCents(), ctx.portfolioValueCents(),
                c.maxPriceCents, c.minEdgeCents, c.maxContractsPerOrder,
                Math.max(0, gameRoom), Math.max(0, bidsLeft));
    }

    /** Range-validate the structured fields; anything off is treated as malformed. */
    private static boolean valid(AiDecision d) {
        return d.winProbability() >= 0.0 && d.winProbability() <= 1.0
                && d.suggestedLimitPriceCents() >= 0 && d.suggestedLimitPriceCents() <= 99
                && d.suggestedContracts() >= 0;
    }

    /** Reserve one call against today's budget; false when exhausted. */
    private boolean claimDailyCall() {
        synchronized (budgetLock) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (!today.equals(budgetDay)) {
                budgetDay = today;
                callsToday = 0;
            }
            if (callsToday >= config.aiMaxCallsPerDay) return false;
            callsToday++;
            return true;
        }
    }
}
