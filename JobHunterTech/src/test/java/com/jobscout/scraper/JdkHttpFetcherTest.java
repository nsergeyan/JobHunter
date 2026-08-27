package com.jobscout.scraper;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the retry loop itself. The delays are set to zero so the suite stays fast:
 * what is being tested is which failures get another attempt and how many, not the
 * arithmetic of the wait, which RetryPolicyTest pins down separately.
 */
class JdkHttpFetcherTest {

    private static final RetryPolicy INSTANT_RETRIES = new RetryPolicy(3, 0, 0);

    private static JdkHttpFetcher fetcherFor(StubHttpClient client, RetryPolicy policy) {
        return new JdkHttpFetcher(client, "test-agent", 0, 0, null, policy);
    }

    @Test
    void transientFailureIsRetriedUntilItSucceeds() {
        StubHttpClient client = new StubHttpClient()
                .queueStatus(503, "unavailable")
                .queueStatus(503, "unavailable")
                .queueStatus(200, "the body");

        assertEquals("the body", fetcherFor(client, INSTANT_RETRIES).get("https://example.com/jobs"));
        assertEquals(3, client.callCount(), "should have retried twice before succeeding");
    }

    @Test
    void permanentFailureFailsOnTheFirstTry() {
        StubHttpClient client = new StubHttpClient().queueStatus(404, "no such board");

        ScraperException exc = assertThrows(ScraperException.class,
                () -> fetcherFor(client, INSTANT_RETRIES).get("https://example.com/jobs"));

        assertEquals(1, client.callCount(), "a 404 must not be retried");
        assertTrue(exc.getMessage().contains("404"), "the real status should reach the caller");
    }

    @Test
    void retriesAreBoundedAndTheLastFailureSurfaces() {
        StubHttpClient client = new StubHttpClient()
                .queueStatus(429, "slow down")
                .queueStatus(429, "slow down")
                .queueStatus(429, "slow down")
                .queueStatus(200, "never reached");

        ScraperException exc = assertThrows(ScraperException.class,
                () -> fetcherFor(client, INSTANT_RETRIES).get("https://example.com/jobs"));

        assertEquals(3, client.callCount(), "should stop at maxAttempts, not keep going");
        assertTrue(exc.getMessage().contains("429"));
    }

    @Test
    void droppedConnectionIsRetried() {
        StubHttpClient client = new StubHttpClient()
                .queueIoFailure(new IOException("connection reset"))
                .queueStatus(200, "recovered");

        assertEquals("recovered", fetcherFor(client, INSTANT_RETRIES).get("https://example.com/jobs"));
        assertEquals(2, client.callCount());
    }

    @Test
    void retryingCanBeDisabled() {
        StubHttpClient client = new StubHttpClient().queueStatus(503, "unavailable").queueStatus(200, "ok");

        assertThrows(ScraperException.class,
                () -> fetcherFor(client, RetryPolicy.none()).get("https://example.com/jobs"));
        assertEquals(1, client.callCount());
    }

    @Test
    void queryStringIsRedactedFromErrors() {
        // Gemini puts the API key in the query string -- it must never reach a log.
        StubHttpClient client = new StubHttpClient().queueStatus(400, "bad request");

        ScraperException exc = assertThrows(ScraperException.class,
                () -> fetcherFor(client, INSTANT_RETRIES).get("https://example.com/v1?key=SECRET"));

        assertTrue(exc.getMessage().contains("[redacted]"));
        assertTrue(!exc.getMessage().contains("SECRET"), "the key must not leak into the message");
    }

    @Test
    void retryAfterAcceptsPlainSeconds() {
        assertEquals(120, JdkHttpFetcher.parseRetryAfter("120"));
        assertEquals(0, JdkHttpFetcher.parseRetryAfter("  0 "));
    }

    @Test
    void retryAfterAcceptsAnHttpDate() {
        String inTwoMinutes = ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(2)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        long seconds = JdkHttpFetcher.parseRetryAfter(inTwoMinutes);
        assertTrue(seconds > 100 && seconds <= 120, "expected roughly two minutes, got " + seconds);
    }

    @Test
    void unparseableRetryAfterFallsBackToPlainBackoff() {
        assertEquals(RetryPolicy.NO_RETRY_AFTER, JdkHttpFetcher.parseRetryAfter("whenever"));
    }

    /** Minimal HttpClient that replays a queued script of responses and failures. */
    private static final class StubHttpClient extends HttpClient {
        private final Deque<Object> scripted = new ArrayDeque<>();
        private int calls;

        StubHttpClient queueStatus(int statusCode, String body) {
            scripted.add(new StubResponse(statusCode, body));
            return this;
        }

        StubHttpClient queueIoFailure(IOException failure) {
            scripted.add(failure);
            return this;
        }

        int callCount() {
            return calls;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException {
            calls++;
            Object next = scripted.poll();
            if (next instanceof IOException failure) {
                throw failure;
            }
            if (next == null) {
                throw new AssertionError("stub ran out of scripted responses after " + calls + " calls");
            }
            return (HttpResponse<T>) next;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
                HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private record StubResponse(int status, String bodyText) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public String body() {
            return bodyText;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.<String, List<String>>of(), (a, b) -> true);
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("https://example.com")).build();
        }

        @Override
        public URI uri() {
            return URI.create("https://example.com");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }
    }
}
