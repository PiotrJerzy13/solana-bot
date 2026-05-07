package com.solanabot.solana_bot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.List;

/**
 * Handles all communication with the Jupiter Token API V2.
 */
public class JupiterClient {

    private static final String LITE_BASE_URL   = "https://lite-api.jup.ag/tokens/v2";
    private static final String KEYED_BASE_URL  = "https://api.jup.ag/tokens/v2";
    private static final Set<String> SUPPORTED_CATEGORY_INTERVALS = Set.of("5m", "1h", "6h", "24h");

    private final HttpClient httpClient;
    private final Gson gson;
    private final String apiKey;
    private final String baseUrl;

    public JupiterClient() {
        this(null);
    }

    public JupiterClient(String apiKey) {
        this.apiKey  = apiKey;
        this.baseUrl = (apiKey != null && !apiKey.isBlank()) ? KEYED_BASE_URL : LITE_BASE_URL;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();
    }

    // ── Public API ────────────────────────────────────────────

    /**
     * Returns the 30 most recently listed tokens (by first pool creation).
     */
    public List<JupiterToken> getRecentTokens() {
        return fetch(baseUrl + "/recent");
    }

    /**
     * Returns tokens matching a search query (symbol, name, or mint address).
     */
    public List<JupiterToken> searchTokens(String query) {
        String encoded = encode(query);
        return fetch(baseUrl + "/search?query=" + encoded);
    }

    /**
     * Returns top trending tokens over a given interval.
     * @param interval one of: 5m, 1h, 6h, 24h
     */
    public List<JupiterToken> getTrendingTokens(String interval) {
        return fetchCategory("toptrending", interval);
    }

    /**
     * Returns top traded tokens over a given interval.
     * @param interval one of: 5m, 1h, 6h, 24h
     */
    public List<JupiterToken> getTopTradedTokens(String interval) {
        return fetchCategory("toptraded", interval);
    }

    /**
     * Returns verified tokens only.
     */
    public List<JupiterToken> getVerifiedTokens() {
        return fetch(baseUrl + "/tag?query=verified");
    }

    // ── Private helpers ───────────────────────────────────────

    private List<JupiterToken> fetch(String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET();

            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("x-api-key", apiKey);
            }

            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("Jupiter API error " + response.statusCode() + ": " + response.body());
                return Collections.emptyList();
            }

            Type listType = new TypeToken<List<JupiterToken>>() {}.getType();
            return gson.fromJson(response.body(), listType);

        } catch (Exception e) {
            System.err.println("Jupiter API request failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private String encode(String query) {
        return java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
    }

    private List<JupiterToken> fetchCategory(String category, String interval) {
        if (!SUPPORTED_CATEGORY_INTERVALS.contains(interval)) {
            System.err.println("Unsupported Jupiter category interval: " + interval);
            return Collections.emptyList();
        }

        return fetch(baseUrl + "/" + category + "/" + interval + "?limit=50");
    }
}
