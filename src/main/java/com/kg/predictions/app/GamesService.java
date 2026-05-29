package com.kg.predictions.app;

import com.kg.predictions.kalshi.KalshiClient;
import com.kg.predictions.mlb.MlbStatsClient;
import com.kg.predictions.mlb.MlbStatsClient.MlbGame;
import com.kg.predictions.model.Game;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads tradeable baseball games from Kalshi and joins them with live MLB
 * scores. Shared by both the console report and the web UI so the data logic
 * lives in exactly one place.
 */
public final class GamesService {

    /** Result of a load: the games plus a ticker -> friendly title map. */
    public record Snapshot(List<Game> games, Map<String, String> seriesTitles) {
        /** Every market (Kalshi) ticker across all games — for feed subscription. */
        public List<String> marketTickers() {
            List<String> tickers = new ArrayList<>(games.size() * 2);
            for (Game g : games) {
                tickers.add(g.away().marketId());
                tickers.add(g.home().marketId());
            }
            return tickers;
        }
    }

    private final List<String> seriesTickers;
    private final KalshiClient kalshi = new KalshiClient();
    private final MlbStatsClient mlb = new MlbStatsClient();

    public GamesService(List<String> seriesTickers) {
        this.seriesTickers = seriesTickers;
    }

    public Snapshot load() throws IOException, InterruptedException {
        List<Game> games = new ArrayList<>();
        Map<String, String> seriesTitles = new LinkedHashMap<>();

        for (String series : seriesTickers) {
            seriesTitles.put(series, kalshi.fetchSeriesTitle(series));
            games.addAll(kalshi.fetchOpenGames(series));
        }

        if (!games.isEmpty()) {
            LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
            List<MlbGame> mlbGames = mlb.fetchGames(today.minusDays(1), today.plusDays(2));
            GameMatcher.attachLiveScores(games, mlbGames);
        }

        return new Snapshot(games, seriesTitles);
    }
}
