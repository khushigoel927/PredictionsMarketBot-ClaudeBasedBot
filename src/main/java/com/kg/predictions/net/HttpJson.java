package com.kg.predictions.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Tiny shared HTTP GET helper that returns parsed JSON. */
public final class HttpJson {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpJson() {}

    /** GET the URL and parse the body as a JSON object. */
    public static JsonObject getObject(String url) throws IOException, InterruptedException {
        return getObject(url, Map.of());
    }

    /** GET the URL with extra headers (e.g. Kalshi auth) and parse the body as JSON. */
    public static JsonObject getObject(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "PredictionsMarket/1.0")
                .timeout(Duration.ofSeconds(20))
                .GET();
        headers.forEach(b::header);

        HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " -> HTTP " + resp.statusCode() + ": "
                    + truncate(resp.body()));
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /** POST a JSON body with extra headers and parse the response as a JSON object. */
    public static JsonObject postObject(String url, Map<String, String> headers, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "PredictionsMarket/1.0")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        headers.forEach(b::header);

        HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("POST " + url + " -> HTTP " + resp.statusCode() + ": "
                    + truncate(resp.body()));
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /** DELETE with extra headers; returns the HTTP status code (does not throw on 4xx). */
    public static int delete(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "PredictionsMarket/1.0")
                .timeout(Duration.ofSeconds(20))
                .DELETE();
        headers.forEach(b::header);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
