package com.jobscout.scraper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostRateLimiterTest {

    @Test
    void hostIsExtractedFromTheUrl() {
        assertEquals("boards-api.greenhouse.io",
                HostRateLimiter.hostOf("https://boards-api.greenhouse.io/v1/boards/anthropic/jobs?content=true"));
        assertEquals("ing.wd3.myworkdayjobs.com",
                HostRateLimiter.hostOf("https://ing.wd3.myworkdayjobs.com/wday/cxs/ing/ICSGBLCOR/job/x"));
    }

    @Test
    void unparseableUrlFallsBackToTheWholeString() {
        // Never throw out of pacing: a weird URL should still be rate limited under
        // some key rather than bypassing the limiter entirely.
        assertEquals("not a url", HostRateLimiter.hostOf("not a url"));
    }

    @Test
    void requestsToOneHostAreSpacedOut() {
        HostRateLimiter limiter = new HostRateLimiter(0.05, 0.05);
        long started = System.nanoTime();
        for (int i = 0; i < 4; i++) {
            limiter.acquire("https://example.com/a");
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        // Four requests means three gaps of 50ms. The first is free.
        assertTrue(elapsedMillis >= 140, "expected at least 3 gaps of 50ms, took " + elapsedMillis + "ms");
    }

    @Test
    void differentHostsDoNotWaitOnEachOther() {
        HostRateLimiter limiter = new HostRateLimiter(0.2, 0.2);
        long started = System.nanoTime();
        limiter.acquire("https://one.example.com/a");
        limiter.acquire("https://two.example.com/a");
        limiter.acquire("https://three.example.com/a");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        // This is the whole point of the change: three different servers, no waiting.
        assertTrue(elapsedMillis < 100, "different hosts should not block each other, took " + elapsedMillis + "ms");
    }

    @Test
    void concurrentCallersForOneHostQueueRatherThanCollide() throws Exception {
        // The failure this guards against: every thread reads the same "next free"
        // instant, sleeps the same interval, and they all hit the server together.
        HostRateLimiter limiter = new HostRateLimiter(0.05, 0.05);
        int threads = 4;

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            long started = System.nanoTime();
            List<Callable<Long>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    limiter.acquire("https://example.com/a");
                    return System.nanoTime();
                });
            }
            List<Future<Long>> results = pool.invokeAll(tasks);
            long last = 0;
            for (Future<Long> result : results) {
                last = Math.max(last, result.get());
            }
            long spanMillis = (last - started) / 1_000_000L;
            assertTrue(spanMillis >= 140,
                    "four concurrent callers on one host should still be spaced out, took " + spanMillis + "ms");
        }
    }

    @Test
    void zeroDelayMeansNoPacing() {
        HostRateLimiter limiter = new HostRateLimiter(0, 0);
        long started = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            limiter.acquire("https://example.com/a");
        }
        assertTrue((System.nanoTime() - started) / 1_000_000L < 100, "zero delay should not sleep");
    }
}
