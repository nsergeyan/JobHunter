package com.jobscout.scraper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Real HttpFetcher backed by the JDK's built-in HttpClient. Applies a random delay
 * after every request (rate limiting) and a fixed User-Agent, same as the Python
 * BaseScraper -- both are read from environment variables so behavior stays
 * configurable via .env without code changes.
 */
public class JdkHttpFetcher implements HttpFetcher {
    private final HttpClient client;
    private final String userAgent;
    private final double minDelaySeconds;
    private final double maxDelaySeconds;

    public JdkHttpFetcher() {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                env("SCRAPER_USER_AGENT", "job-scout-bot/0.1 (personal project)"),
                Double.parseDouble(env("SCRAPER_MIN_DELAY_SECONDS", "2")),
                Double.parseDouble(env("SCRAPER_MAX_DELAY_SECONDS", "5")));
    }

    public JdkHttpFetcher(HttpClient client, String userAgent, double minDelaySeconds, double maxDelaySeconds) {
        this.client = client;
        this.userAgent = userAgent;
        this.minDelaySeconds = minDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
    }

    private static String env(String key, String fallback) {
        // Real exported env vars take priority; .env-loaded values (via
        // Dotenv.systemProperties() in Main) are the fallback -- Java has no
        // supported way to inject into System.getenv() itself the way
        // python-dotenv's load_dotenv() mutates os.environ.
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public String get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        return send(url, request);
    }

    @Override
    public String post(String url, String jsonBody) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return send(url, request);
    }

    private String send(String url, HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ScraperException(
                        "Request to " + url + " failed with status " + response.statusCode());
            }
            return response.body();
        } catch (java.io.IOException | InterruptedException exc) {
            throw new ScraperException("Request to " + url + " failed: " + exc.getMessage(), exc);
        } finally {
            sleepBetweenRequests();
        }
    }

    private void sleepBetweenRequests() {
        // ThreadLocalRandom.nextDouble requires a strictly greater upper bound, unlike
        // Python's random.uniform -- fall back to a fixed delay when min == max (tests
        // set both to 0 for a no-op sleep).
        double seconds = maxDelaySeconds > minDelaySeconds
                ? ThreadLocalRandom.current().nextDouble(minDelaySeconds, maxDelaySeconds)
                : minDelaySeconds;
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }
}
