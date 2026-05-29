package com.kg.predictions.model;

import java.time.Duration;
import java.time.Instant;

/**
 * One tradeable baseball game = one Kalshi event, holding the two team markets
 * (away + home) and, once joined against the MLB Stats API, its live score.
 */
public final class Game {

    private final String eventId;       // Kalshi event ticker, e.g. KXMLBGAME-26MAY281840CHCPIT
    private final String seriesTicker;  // e.g. KXMLBGAME
    private final String title;         // e.g. "Chicago C vs Pittsburgh Winner?"
    private final Instant startTime;    // scheduled first pitch (occurrence_datetime)
    private final Quote away;
    private final Quote home;

    private LiveScore live;             // null until matched against MLB Stats
    private long gamePk;                // MLB Stats gamePk of the matched game (0 if unmatched)

    public Game(String eventId, String seriesTicker, String title,
                Instant startTime, Quote away, Quote home) {
        this.eventId = eventId;
        this.seriesTicker = seriesTicker;
        this.title = title;
        this.startTime = startTime;
        this.away = away;
        this.home = home;
    }

    public String eventId()      { return eventId; }
    public String seriesTicker() { return seriesTicker; }
    public String title()        { return title; }
    public Instant startTime()   { return startTime; }
    public Quote away()          { return away; }
    public Quote home()          { return home; }
    public LiveScore live()      { return live; }
    public long gamePk()         { return gamePk; }

    public void attachLive(LiveScore score) { this.live = score; }
    public void attachGamePk(long gamePk)   { this.gamePk = gamePk; }

    public boolean isLive() { return live != null && live.inProgress(); }

    /**
     * Text for the "time left" column. Baseball has no clock, so:
     *  - live games show the inning ("Top 4th"),
     *  - finished games show "Final",
     *  - upcoming games show a countdown to first pitch ("starts in 2h 14m").
     */
    public String timeLeft(Instant now) {
        if (live != null && (live.inProgress() || live.isFinal())) {
            return live.progressLabel();
        }
        if (startTime != null) {
            Duration d = Duration.between(now, startTime);
            if (d.isNegative()) return "started";
            long h = d.toHours();
            long m = d.toMinutesPart();
            return h > 0 ? "starts in %dh %02dm".formatted(h, m)
                         : "starts in %dm".formatted(m);
        }
        return "—";
    }
}
