package com.kg.predictions.mlb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kg.predictions.model.LiveScore;
import com.kg.predictions.net.HttpJson;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads live MLB game state (score + inning) from the free, unauthenticated
 * MLB Stats API. Kalshi has no score/inning data, so this is the second feed
 * we join against.
 */
public final class MlbStatsClient {

    private static final String BASE = "https://statsapi.mlb.com/api/v1/schedule";

    /** A scheduled MLB game with its canonical team pair, start time and live state. */
    public record MlbGame(long gamePk, String awayCanon, String homeCanon, Instant start, LiveScore score) {}

    /**
     * Fetch every MLB game scheduled between two dates (inclusive). A date range
     * is used because a Kalshi game's UTC start can land on a neighbouring
     * calendar day from the official MLB game date.
     */
    public List<MlbGame> fetchGames(LocalDate startDate, LocalDate endDate)
            throws IOException, InterruptedException {

        String url = BASE + "?sportId=1&hydrate=linescore,team"
                + "&startDate=" + startDate + "&endDate=" + endDate;

        JsonObject root = HttpJson.getObject(url);
        List<MlbGame> games = new ArrayList<>();

        for (JsonElement dateEl : arr(root, "dates")) {
            for (JsonElement gameEl : arr(dateEl.getAsJsonObject(), "games")) {
                MlbGame g = toGame(gameEl.getAsJsonObject());
                if (g != null) games.add(g);
            }
        }
        return games;
    }

    private static MlbGame toGame(JsonObject g) {
        JsonObject teams = obj(g, "teams");
        if (teams == null) return null;

        JsonObject away = obj(teams, "away");
        JsonObject home = obj(teams, "home");
        if (away == null || home == null) return null;

        String awayAb = abbrev(away);
        String homeAb = abbrev(home);
        String state  = str(obj(g, "status"), "detailedState");

        JsonObject ls = obj(g, "linescore");
        String inningOrdinal = str(ls, "currentInningOrdinal");
        String inningState   = str(ls, "inningState");

        LiveScore score = new LiveScore(
                awayAb, homeAb,
                intVal(away, "score"), intVal(home, "score"),
                state, inningOrdinal, inningState
        );

        return new MlbGame(
                longVal(g, "gamePk"),
                TeamAliases.canon(awayAb),
                TeamAliases.canon(homeAb),
                parseInstant(str(g, "gameDate")),
                score
        );
    }

    private static String abbrev(JsonObject teamSide) {
        JsonObject team = obj(teamSide, "team");
        return team == null ? "?" : str(team, "abbreviation");
    }

    // --- small JSON helpers ------------------------------------------------

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e == null || !e.isJsonObject() ? null : e.getAsJsonObject();
    }

    private static JsonArray arr(JsonObject o, String key) {
        if (o == null) return new JsonArray();
        JsonElement e = o.get(key);
        return e == null || !e.isJsonArray() ? new JsonArray() : e.getAsJsonArray();
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

    private static Instant parseInstant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
