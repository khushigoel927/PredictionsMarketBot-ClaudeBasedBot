package com.kg.predictions.mlb;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kg.predictions.model.LiveState;
import com.kg.predictions.net.HttpJson;

import java.io.IOException;

/**
 * Reads detailed live game state (outs, base runners, current pitcher) from the
 * MLB Stats live feed: {@code v1.1/game/{gamePk}/feed/live}. Used by the trading
 * bot; the display path uses the lighter schedule+linescore in {@link MlbStatsClient}.
 */
public final class MlbLiveClient {

    private static final String FEED = "https://statsapi.mlb.com/api/v1.1/game/";

    public LiveState fetchLiveState(long gamePk) throws IOException, InterruptedException {
        JsonObject root = HttpJson.getObject(FEED + gamePk + "/feed/live");

        JsonObject liveData = obj(root, "liveData");
        JsonObject ls = obj(liveData, "linescore");
        JsonObject offense = obj(ls, "offense");
        JsonObject teams = obj(ls, "teams");

        String abstractState = str(obj(obj(root, "gameData"), "status"), "abstractGameState");

        return new LiveState(
                intVal(ls, "currentInning"),
                boolVal(ls, "isTopInning"),
                intVal(ls, "outs"),
                has(offense, "first"),
                has(offense, "second"),
                has(offense, "third"),
                intVal(obj(teams, "home"), "runs"),
                intVal(obj(teams, "away"), "runs"),
                longVal(obj(obj(ls, "defense"), "pitcher"), "id"),
                "Live".equalsIgnoreCase(abstractState),
                "Final".equalsIgnoreCase(abstractState));
    }

    // --- JSON helpers ------------------------------------------------------

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static boolean has(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject();
    }

    private static String str(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static int intVal(JsonObject o, String key) {
        if (o == null) return 0;
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsInt();
    }

    private static long longVal(JsonObject o, String key) {
        if (o == null) return 0;
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static boolean boolVal(JsonObject o, String key) {
        if (o == null) return false;
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }
}
