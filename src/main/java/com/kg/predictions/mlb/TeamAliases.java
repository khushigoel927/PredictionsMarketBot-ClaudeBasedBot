package com.kg.predictions.mlb;

import java.util.Map;

/**
 * Normalizes team abbreviations to a shared canonical token so Kalshi tickers
 * and MLB Stats API teams can be matched. Most abbreviations already agree;
 * this only fixes the handful that differ between the two feeds.
 */
public final class TeamAliases {

    private static final Map<String, String> CANON = Map.ofEntries(
            Map.entry("OAK", "ATH"),  // Athletics
            Map.entry("ARI", "AZ"),   // Arizona
            Map.entry("CHW", "CWS"),  // Chicago White Sox
            Map.entry("WSN", "WSH"),  // Washington
            Map.entry("SDP", "SD"),   // San Diego
            Map.entry("SFG", "SF"),   // San Francisco
            Map.entry("TBR", "TB"),   // Tampa Bay
            Map.entry("KCR", "KC")    // Kansas City
    );

    private TeamAliases() {}

    public static String canon(String abbrev) {
        if (abbrev == null) return "?";
        String up = abbrev.toUpperCase();
        return CANON.getOrDefault(up, up);
    }
}
