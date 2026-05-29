package com.kg.predictions.kalshi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kg.predictions.model.Game;
import com.kg.predictions.model.Quote;
import com.kg.predictions.net.HttpJson;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads tradeable games from Kalshi's public market-data API.
 * No authentication is required for GET market/event data.
 */
public final class KalshiClient {

    public static final String BASE = KalshiEnv.restBase();

    /**
     * Fetch every open (tradeable) game for a series, with its two team markets.
     * Handles cursor pagination.
     *
     * @param seriesTicker e.g. {@code KXMLBGAME} for MLB single-game markets
     */
    public List<Game> fetchOpenGames(String seriesTicker) throws IOException, InterruptedException {
        List<Game> games = new ArrayList<>();
        String cursor = null;

        do {
            StringBuilder url = new StringBuilder(BASE)
                    .append("/events?with_nested_markets=true&status=open&limit=200")
                    .append("&series_ticker=").append(enc(seriesTicker));
            if (cursor != null && !cursor.isEmpty()) {
                url.append("&cursor=").append(enc(cursor));
            }

            JsonObject page = HttpJson.getObject(url.toString());
            for (JsonElement el : arr(page, "events")) {
                Game g = toGame(el.getAsJsonObject(), seriesTicker);
                if (g != null) games.add(g);
            }
            cursor = str(page, "cursor");
        } while (cursor != null && !cursor.isEmpty());

        return games;
    }

    /**
     * Look up a human-friendly series title (e.g. "Professional Baseball Game").
     * Falls back to the ticker itself if the lookup fails.
     */
    public String fetchSeriesTitle(String seriesTicker) {
        try {
            JsonObject root = HttpJson.getObject(BASE + "/series/" + enc(seriesTicker));
            JsonElement series = root.get("series");
            if (series != null && series.isJsonObject()) {
                String title = str(series.getAsJsonObject(), "title");
                if (title != null && !title.isBlank()) return title;
            }
        } catch (Exception ignored) {
            // non-fatal: title is cosmetic
        }
        return seriesTicker;
    }

    /** Build a Game from an event object that has nested markets. */
    private static Game toGame(JsonObject event, String seriesTicker) {
        String eventId = str(event, "event_ticker");
        JsonArray markets = arr(event, "markets");
        if (eventId == null || markets.size() < 2) {
            return null; // not a standard two-outcome head-to-head game
        }

        // Kalshi orders the markets away-team-first, home-team-second
        // (the event ticker suffix encodes AWAY then HOME, e.g. ...CHCPIT = CHC @ PIT).
        Quote away = toQuote(markets.get(0).getAsJsonObject(), false);
        Quote home = toQuote(markets.get(1).getAsJsonObject(), true);

        String title = str(markets.get(0).getAsJsonObject(), "title");
        Instant start = parseInstant(str(markets.get(0).getAsJsonObject(), "occurrence_datetime"));

        return new Game(eventId, seriesTicker, title, start, away, home);
    }

    private static Quote toQuote(JsonObject m, boolean home) {
        String ticker = str(m, "ticker");
        String abbrev = ticker == null ? "?" : ticker.substring(ticker.lastIndexOf('-') + 1);
        return new Quote(
                ticker,
                str(m, "yes_sub_title"),
                abbrev,
                home,
                cents(m, "yes_bid_dollars"),
                cents(m, "yes_ask_dollars"),
                cents(m, "no_bid_dollars"),
                cents(m, "no_ask_dollars"),
                cents(m, "last_price_dollars"),
                fpToInt(str(m, "yes_bid_size_fp")),
                fpToInt(str(m, "yes_ask_size_fp"))
        );
    }

    // --- small JSON helpers ------------------------------------------------

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? new JsonArray() : e.getAsJsonArray();
    }

    /** Kalshi reports prices as dollar strings like "0.3900"; convert to whole cents. */
    private static int cents(JsonObject o, String key) {
        return dollarsToCents(str(o, key));
    }

    /** Convert a Kalshi dollar string ("0.3900") to whole cents (39). */
    public static int dollarsToCents(String dollars) {
        if (dollars == null || dollars.isEmpty()) return 0;
        return (int) Math.round(Double.parseDouble(dollars) * 100.0);
    }

    /** Convert a Kalshi fixed-point string ("662.00") to a whole number (662). */
    public static int fpToInt(String fp) {
        if (fp == null || fp.isEmpty()) return 0;
        return (int) Math.round(Double.parseDouble(fp));
    }

    private static Instant parseInstant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
