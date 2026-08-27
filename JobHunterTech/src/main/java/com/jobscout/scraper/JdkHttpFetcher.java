package com.jobscout.scraper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Real HttpFetcher backed by the JDK's built-in HttpClient. Applies a random delay
 * after every request (rate limiting) and a fixed User-Agent, same as the Python
 * BaseScraper -- both are read from environment variables so behavior stays
 * configurable via .env without code changes.
 *
 * Transient failures (429, 5xx, dropped connections) are retried per RetryPolicy,
 * honoring a server-supplied Retry-After when there is one. Permanent ones (404 on
 * a wrong board token, 401 on a bad key) fail on the first try, so a real
 * configuration error still surfaces immediately instead of being buried under a
 * minute of pointless backoff.
 */
public class JdkHttpFetcher implements HttpFetcher {
    private final HttpClient client;
    private final String userAgent;
    private final double minDelaySeconds;
    private final double maxDelaySeconds;
    private final Duration requestTimeout;
    private final RetryPolicy retryPolicy;

    public JdkHttpFetcher() {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                env("SCRAPER_USER_AGENT", "job-scout-bot/0.1 (personal project)"),
                Double.parseDouble(env("SCRAPER_MIN_DELAY_SECONDS", "2")),
                Double.parseDouble(env("SCRAPER_MAX_DELAY_SECONDS", "5")),
                null);
    }

    public JdkHttpFetcher(HttpClient client, String userAgent, double minDelaySeconds, double maxDelaySeconds) {
        this(client, userAgent, minDelaySeconds, maxDelaySeconds, null);
    }

    /** Retry settings from .env, so they can be tuned without a recompile. */
    public static RetryPolicy retryPolicyFromEnv() {
        return new RetryPolicy(
                Integer.parseInt(env("SCRAPER_MAX_ATTEMPTS", "3")),
                Double.parseDouble(env("SCRAPER_RETRY_BASE_SECONDS", "2")),
                Double.parseDouble(env("SCRAPER_RETRY_MAX_SECONDS", "60")));
    }

    /**
     * requestTimeout bounds a single request's total wall-clock time (unlike
     * HttpClient's connectTimeout, which only covers the TCP handshake) -- null means
     * no bound. Added for local Ollama calls: a thinking model constrained by a strict
     * JSON schema can occasionally wedge the grammar sampler into a state with zero
     * forward progress (confirmed live: 10+ minutes at 0% CPU, connection still open),
     * which connectTimeout alone can't catch since the connection itself is fine.
     */
    public JdkHttpFetcher(HttpClient client, String userAgent, double minDelaySeconds, double maxDelaySeconds,
            Duration requestTimeout) {
        this(client, userAgent, minDelaySeconds, maxDelaySeconds, requestTimeout, retryPolicyFromEnv());
    }

    public JdkHttpFetcher(HttpClient client, String userAgent, double minDelaySeconds, double maxDelaySeconds,
            Duration requestTimeout, RetryPolicy retryPolicy) {
        this.client = client;
        this.userAgent = userAgent;
        this.minDelaySeconds = minDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        this.requestTimeout = requestTimeout;
        this.retryPolicy = retryPolicy;
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .GET();
        applyRequestTimeout(builder);
        return send(url, builder.build());
    }

    @Override
    public String post(String url, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        applyRequestTimeout(builder);
        return send(url, builder.build());
    }

    private void applyRequestTimeout(HttpRequest.Builder builder) {
        if (requestTimeout != null) {
            builder.timeout(requestTimeout);
        }
    }

    /**
     * One attempt's result: either a body, or the failure plus whether that kind of
     * failure is worth another go and how long the server asked us to wait.
     */
    private record Attempt(String body, ScraperException failure, boolean retryable, long retryAfterSeconds) {
        static Attempt succeeded(String body) {
            return new Attempt(body, null, false, RetryPolicy.NO_RETRY_AFTER);
        }

        static Attempt failed(ScraperException failure, boolean retryable, long retryAfterSeconds) {
            return new Attempt(null, failure, retryable, retryAfterSeconds);
        }
    }

    private String send(String url, HttpRequest request) {
        for (int attempt = 1; ; attempt++) {
            Attempt result = attemptOnce(url, request);
            if (result.body() != null) {
                return result.body();
            }
            if (!result.retryable() || attempt >= retryPolicy.maxAttempts()) {
                throw result.failure();
            }
            double waitSeconds = retryPolicy.delaySeconds(attempt, result.retryAfterSeconds());
            System.out.println("  retrying " + redactQuery(url) + " in " + String.format("%.1f", waitSeconds)
                    + "s (attempt " + (attempt + 1) + " of " + retryPolicy.maxAttempts() + "): "
                    + result.failure().getMessage());
            sleepSeconds(waitSeconds);
        }
    }

    private Attempt attemptOnce(String url, HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                // Include the response body -- APIs like Gemini put the actual reason
                // (rate limit vs. quota exhausted vs. bad request) in a JSON error body,
                // and a bare status code isn't enough to tell those apart.
                ScraperException failure = new ScraperException("Request to " + redactQuery(url)
                        + " failed with status " + response.statusCode() + ": " + response.body());
                return Attempt.failed(failure, RetryPolicy.isRetryableStatus(response.statusCode()),
                        retryAfterSeconds(response));
            }
            return Attempt.succeeded(response.body());
        } catch (java.io.IOException exc) {
            // Transport-level: connection reset, DNS hiccup, read timeout. Worth another
            // go, unlike a 404, which will still be a 404 on the tenth try.
            ScraperException failure = new ScraperException(
                    "Request to " + redactQuery(url) + " failed: " + exc.getMessage(), exc);
            return Attempt.failed(failure, true, RetryPolicy.NO_RETRY_AFTER);
        } catch (InterruptedException exc) {
            // Someone is shutting us down. Restore the flag and stop, never retry.
            Thread.currentThread().interrupt();
            ScraperException failure = new ScraperException(
                    "Request to " + redactQuery(url) + " was interrupted", exc);
            return Attempt.failed(failure, false, RetryPolicy.NO_RETRY_AFTER);
        } finally {
            // Politeness delay belongs to every attempt, including failed ones: a
            // server that just rate-limited us is the last one to hammer.
            sleepBetweenRequests();
        }
    }

    private static long retryAfterSeconds(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(JdkHttpFetcher::parseRetryAfter)
                .orElse(RetryPolicy.NO_RETRY_AFTER);
    }

    /**
     * Retry-After comes in two legal shapes: delta-seconds ("120") or an HTTP-date
     * ("Wed, 21 Oct 2026 07:28:00 GMT"). Real job boards send both, so parse both
     * and fall back to plain backoff on anything unrecognised.
     */
    static long parseRetryAfter(String value) {
        String trimmed = value.strip();
        try {
            return Math.max(0, Long.parseLong(trimmed));
        } catch (NumberFormatException notSeconds) {
            // Fall through to the HTTP-date form.
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0, Duration.between(ZonedDateTime.now(when.getZone()), when).toSeconds());
        } catch (DateTimeParseException notADate) {
            return RetryPolicy.NO_RETRY_AFTER;
        }
    }

    /** Query strings can carry secrets (e.g. Gemini's ?key=...) -- never let them reach logs/exceptions. */
    private static String redactQuery(String url) {
        int queryIndex = url.indexOf('?');
        return queryIndex == -1 ? url : url.substring(0, queryIndex) + "?[redacted]";
    }

    private void sleepBetweenRequests() {
        // ThreadLocalRandom.nextDouble requires a strictly greater upper bound, unlike
        // Python's random.uniform -- fall back to a fixed delay when min == max (tests
        // set both to 0 for a no-op sleep).
        double seconds = maxDelaySeconds > minDelaySeconds
                ? ThreadLocalRandom.current().nextDouble(minDelaySeconds, maxDelaySeconds)
                : minDelaySeconds;
        sleepSeconds(seconds);
    }

    private static void sleepSeconds(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }
}
