package com.kg.predictions;

import com.kg.predictions.app.GamesService;
import com.kg.predictions.app.GamesService.Snapshot;
import com.kg.predictions.app.LiveGamesReport;
import com.kg.predictions.web.WebServer;

import java.time.Instant;
import java.util.List;

/**
 * Lists every baseball game currently tradeable on Kalshi, joins each with the
 * live score + inning from the MLB Stats API, and displays them grouped by
 * series.
 *
 * <p>Data sources (both public, no auth):
 *   - Kalshi market data: market/event IDs, bid/ask, implied win chances
 *   - MLB Stats API:      live score + inning ("time left")
 *
 * <p>Usage:
 *   - {@code mvn -q compile exec:java}                    → web UI at http://localhost:8080
 *   - {@code mvn -q compile exec:java -Dexec.args=console} → one-shot console report
 */
public class Main {

    /** Kalshi series to display. Each is a recurring single-game baseball market. */
    private static final List<String> BASEBALL_SERIES = List.of("KXMLBGAME");

    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        GamesService service = new GamesService(BASEBALL_SERIES);
        boolean console = args.length > 0 && args[0].equalsIgnoreCase("console");

        try {
            if (console) {
                Snapshot snap = service.load();
                new LiveGamesReport(snap.seriesTitles()).print(snap.games(), Instant.now());
            } else {
                new WebServer(service, resolvePort()).start();
                Thread.currentThread().join(); // keep the server alive
            }
        } catch (Exception e) {
            System.err.println("Failed to start live baseball app: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Web UI port: the {@code PORT} env var if set and valid, else {@value #DEFAULT_PORT}. */
    private static int resolvePort() {
        String env = System.getenv("PORT");
        if (env != null && env.matches("\\d+")) {
            return Integer.parseInt(env);
        }
        return DEFAULT_PORT;
    }
}
