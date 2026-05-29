package com.kg.predictions.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kg.predictions.app.GamesService.Snapshot;
import com.kg.predictions.app.QuoteStore;
import com.kg.predictions.bot.BotStore;
import com.kg.predictions.bot.BotStore.GameBotState;
import com.kg.predictions.kalshi.KalshiEnv;
import com.kg.predictions.model.Game;
import com.kg.predictions.model.LivePrice;
import com.kg.predictions.model.LiveScore;
import com.kg.predictions.model.Quote;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Serializes a {@link Snapshot} into the JSON the web UI consumes. */
public final class GamesJson {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("EEE h:mm a z").withZone(ZoneId.systemDefault());

    private GamesJson() {}

    /**
     * @param prices latest live prices overlaid onto each quote so the initial
     *               page paint is already current (may be null/empty).
     */
    public static JsonObject build(Snapshot snapshot, Instant now, QuoteStore prices, BotStore bot) {
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", TIME.format(now));
        root.addProperty("env", KalshiEnv.name());

        // Group games by series ticker (sorted for stable ordering).
        Map<String, List<Game>> bySeries = snapshot.games().stream()
                .collect(Collectors.groupingBy(Game::seriesTicker, TreeMap::new, Collectors.toList()));

        JsonArray seriesArr = new JsonArray();
        for (Map.Entry<String, List<Game>> entry : bySeries.entrySet()) {
            String ticker = entry.getKey();
            List<Game> games = entry.getValue();

            JsonObject series = new JsonObject();
            series.addProperty("ticker", ticker);
            series.addProperty("title", snapshot.seriesTitles().getOrDefault(ticker, ticker));
            series.addProperty("liveCount", games.stream().filter(Game::isLive).count());
            series.addProperty("total", games.size());

            JsonArray gamesArr = new JsonArray();
            games.stream()
                    .sorted(Comparator.comparing(Game::isLive).reversed()
                            .thenComparing(g -> g.startTime() == null ? Instant.MAX : g.startTime()))
                    .forEach(g -> gamesArr.add(gameJson(g, now, prices, bot)));
            series.add("games", gamesArr);

            seriesArr.add(series);
        }
        root.add("series", seriesArr);
        return root;
    }

    private static JsonObject gameJson(Game g, Instant now, QuoteStore prices, BotStore bot) {
        JsonObject o = new JsonObject();
        o.addProperty("eventId", g.eventId());
        o.addProperty("title", g.title());
        o.addProperty("live", g.isLive());
        o.addProperty("state", g.timeLeft(now));
        o.addProperty("firstPitch", g.startTime() == null ? null : TIME.format(g.startTime()));

        GameBotState bs = bot == null ? null : bot.get(g.eventId());
        if (bs != null) {
            JsonObject b = new JsonObject();
            b.addProperty("side", bs.side());
            b.addProperty("bidsPlaced", bs.bidsPlaced());
            b.addProperty("contracts", bs.contracts());
            b.addProperty("avgPriceCents", bs.avgPriceCents());
            b.addProperty("modelWinPct", bs.modelWinPct());
            b.addProperty("expectedPayoffDollars", bs.expectedPayoffDollars());
            o.add("bot", b);
        }

        LiveScore s = g.live();
        o.addProperty("hasScore", s != null);
        if (s != null) {
            o.addProperty("awayScore", s.awayScore());
            o.addProperty("homeScore", s.homeScore());
            o.addProperty("scoreLabel", s.progressLabel());
        }

        o.add("away", quoteJson(g.away(), prices));
        o.add("home", quoteJson(g.home(), prices));
        return o;
    }

    private static JsonObject quoteJson(Quote q, QuoteStore prices) {
        // Overlay the latest live price when we have one, so the first paint is current.
        LivePrice live = prices == null ? null : prices.get(q.marketId());
        int bid = live != null ? live.yesBid() : q.yesBid();
        int ask = live != null ? live.yesAsk() : q.yesAsk();
        int bidSize = live != null ? live.yesBidSize() : q.yesBidSize();
        int askSize = live != null ? live.yesAskSize() : q.yesAskSize();
        long win = Math.round(live != null ? live.impliedChancePct() : q.impliedChancePct());

        JsonObject o = new JsonObject();
        o.addProperty("name", q.teamName());
        o.addProperty("abbrev", q.teamAbbrev());
        o.addProperty("marketId", q.marketId());
        o.addProperty("home", q.home());
        o.addProperty("bid", bid);
        o.addProperty("ask", ask);
        o.addProperty("bidSize", bidSize);
        o.addProperty("askSize", askSize);
        o.addProperty("win", win);
        return o;
    }
}
