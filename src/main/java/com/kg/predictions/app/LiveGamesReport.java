package com.kg.predictions.app;

import com.kg.predictions.model.Game;
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

/** Renders the joined games to the console, grouped by Kalshi series. */
public final class LiveGamesReport {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("EEE h:mm a z").withZone(ZoneId.systemDefault());

    private final Map<String, String> seriesTitles; // ticker -> friendly title

    public LiveGamesReport(Map<String, String> seriesTitles) {
        this.seriesTitles = seriesTitles;
    }

    public void print(List<Game> games, Instant now) {
        if (games.isEmpty()) {
            System.out.println("No tradeable baseball games found right now.");
            return;
        }

        // Group by series ticker.
        Map<String, List<Game>> bySeries = games.stream()
                .collect(Collectors.groupingBy(Game::seriesTicker, TreeMap::new, Collectors.toList()));

        System.out.println();
        System.out.println("Kalshi live baseball — " + TIME.format(now));

        for (Map.Entry<String, List<Game>> entry : bySeries.entrySet()) {
            String ticker = entry.getKey();
            List<Game> seriesGames = entry.getValue();
            long liveCount = seriesGames.stream().filter(Game::isLive).count();

            String title = seriesTitles.getOrDefault(ticker, ticker);
            System.out.println();
            System.out.println("=".repeat(78));
            System.out.printf(" SERIES %s — %s   (%d live / %d tradeable)%n",
                    ticker, title, liveCount, seriesGames.size());
            System.out.println("=".repeat(78));

            // Live games first, then by start time.
            seriesGames.stream()
                    .sorted(Comparator.comparing(Game::isLive).reversed()
                            .thenComparing(g -> g.startTime() == null ? Instant.MAX : g.startTime()))
                    .forEach(g -> printGame(g, now));
        }
        System.out.println();
    }

    private void printGame(Game g, Instant now) {
        String tag = g.isLive() ? "[LIVE]" : "[OPEN]";
        System.out.println();
        System.out.printf("%s  %s  vs  %s%n",
                tag, g.away().teamName(), g.home().teamName());
        System.out.printf("   Event ID : %s%n", g.eventId());
        System.out.printf("   State    : %s", g.timeLeft(now));
        if (!g.isLive() && g.startTime() != null) {
            System.out.printf("   (first pitch %s)", TIME.format(g.startTime()));
        }
        System.out.println();
        System.out.printf("   Score    : %s%n", scoreLine(g));

        // Per-team quote table.
        System.out.printf("   %-16s %-34s %5s %5s %5s%n",
                "Team", "Market ID", "Bid", "Ask", "Win%");
        printQuote(g.away());
        printQuote(g.home());
    }

    private void printQuote(Quote q) {
        String label = trim(q.teamName(), 14) + (q.home() ? " (H)" : " (A)");
        System.out.printf("   %-16s %-34s %4d¢ %4d¢ %4.0f%%%n",
                label, q.marketId(),
                q.yesBid(), q.yesAsk(), q.impliedChancePct());
    }

    private String scoreLine(Game g) {
        LiveScore s = g.live();
        if (s == null) {
            return "(not started — no live score yet)";
        }
        return "%s %d  -  %s %d   [%s]".formatted(
                g.away().teamAbbrev(), s.awayScore(),
                g.home().teamAbbrev(), s.homeScore(),
                s.progressLabel());
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
