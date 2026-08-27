package com.jobscout.scraper;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Politeness pacing, tracked per host rather than globally.
 *
 * The old rule was one sleep after every request, anywhere. That is correct but
 * needlessly strict: it serialises the entire run, so a slow Workday tenant holds
 * up a Greenhouse fetch that would not have touched the same server at all.
 *
 * Pacing per host keeps the promise that actually matters -- no single server sees
 * requests closer together than the configured delay -- while letting different
 * servers proceed independently. Greenhouse's 66 companies all live behind one API
 * host and so are still spaced out one after another, exactly as before. Workday's
 * companies each have their own host and no longer wait on each other.
 *
 * Slots are reserved rather than slept through: a caller atomically claims the next
 * free moment for its host, then waits for it. Two threads hitting the same host
 * therefore get consecutive slots instead of both sleeping the same interval and
 * arriving together.
 */
public final class HostRateLimiter {
    private final ConcurrentHashMap<String, Long> nextFreeSlotNanos = new ConcurrentHashMap<>();
    private final double minDelaySeconds;
    private final double maxDelaySeconds;

    public HostRateLimiter(double minDelaySeconds, double maxDelaySeconds) {
        this.minDelaySeconds = minDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
    }

    /** Host portion of a URL, or the whole string when it will not parse. */
    public static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException exc) {
            return url;
        }
    }

    /**
     * Block until this host is due another request, reserving that slot so no other
     * thread can take it.
     */
    public void acquire(String url) {
        long waitNanos = reserveSlot(hostOf(url)) - System.nanoTime();
        if (waitNanos <= 0) {
            return;
        }
        try {
            Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The instant this caller may proceed. compute() is atomic per key, so the
     * read-and-advance cannot interleave: concurrent callers for one host queue up
     * behind each other instead of all reading the same free slot.
     */
    private long reserveSlot(String host) {
        long[] mySlot = new long[1];
        nextFreeSlotNanos.compute(host, (ignored, nextFree) -> {
            long now = System.nanoTime();
            // A host we have not touched recently is free immediately.
            long slot = (nextFree == null || nextFree < now) ? now : nextFree;
            mySlot[0] = slot;
            return slot + delayNanos();
        });
        return mySlot[0];
    }

    private long delayNanos() {
        // ThreadLocalRandom.nextDouble needs a strictly greater bound, unlike Python's
        // random.uniform -- fall back to a fixed delay when min == max (tests set both
        // to 0 for no pacing at all).
        double seconds = maxDelaySeconds > minDelaySeconds
                ? ThreadLocalRandom.current().nextDouble(minDelaySeconds, maxDelaySeconds)
                : minDelaySeconds;
        return (long) (seconds * 1_000_000_000L);
    }
}
