package com.kg.predictions.kalshi;

/**
 * Selects which Kalshi environment the app talks to, via the {@code KALSHI_ENV}
 * environment variable:
 *
 * <ul>
 *   <li>{@code KALSHI_ENV=demo} → the demo/sandbox environment</li>
 *   <li>anything else (or unset) → production (default)</li>
 * </ul>
 *
 * Note: demo and production have <em>separate</em> API credentials and separate
 * market data — a production API key will not authenticate against demo, and
 * the demo environment carries its own (test) markets.
 */
public final class KalshiEnv {

    private static final boolean DEMO = "demo".equalsIgnoreCase(System.getenv("KALSHI_ENV"));

    private KalshiEnv() {}

    public static boolean isDemo() {
        return DEMO;
    }

    /** Human label for logging. */
    public static String name() {
        return DEMO ? "demo" : "production";
    }

    /** REST base, e.g. {@code https://.../trade-api/v2}. */
    public static String restBase() {
        return DEMO ? "https://demo-api.kalshi.co/trade-api/v2"
                    : "https://api.elections.kalshi.com/trade-api/v2";
    }

    /** WebSocket URL. */
    public static String wsUrl() {
        return DEMO ? "wss://demo-api.kalshi.co/trade-api/ws/v2"
                    : "wss://api.elections.kalshi.com/trade-api/ws/v2";
    }

    /** Path used in the WebSocket auth signature (same for both environments). */
    public static final String WS_PATH = "/trade-api/ws/v2";
}
