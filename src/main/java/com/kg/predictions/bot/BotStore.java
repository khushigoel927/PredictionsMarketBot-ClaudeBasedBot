package com.kg.predictions.bot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, read-optimized store of the bot's per-game state for the UI.
 * The bot (single engine thread) publishes an immutable snapshot per traded
 * game; the web layer reads it concurrently.
 */
public final class BotStore {

    /**
     * @param side          team abbreviation the bot is backing (its YES side)
     * @param bidsPlaced    number of order placements so far (max 100/game)
     * @param contracts     filled contracts currently held
     * @param avgPriceCents average entry price in cents
     * @param modelWinPct   bot's current model win probability for the held side (0-100)
     * @param expectedPayoffDollars expected profit at settlement = contracts × (winProb − avgPrice)
     */
    public record GameBotState(
            String eventTicker,
            String side,
            int bidsPlaced,
            int contracts,
            int avgPriceCents,
            int modelWinPct,
            double expectedPayoffDollars
    ) {}

    private final Map<String, GameBotState> states = new ConcurrentHashMap<>();

    public void publish(GameBotState state) {
        states.put(state.eventTicker(), state);
    }

    /** Bot state for one game, or null if the bot hasn't traded it. */
    public GameBotState get(String eventTicker) {
        return states.get(eventTicker);
    }
}
