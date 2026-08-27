package com.jobscout.scraper;

import java.util.concurrent.ThreadLocalRandom;

/**
 * How hard to retry a failed request, and how long to wait in between.
 *
 * Why this exists: before it, any failed request cost a whole company's postings
 * for that day. A single 429 from a busy job board, or one connection reset,
 * printed "Skipping Acme" and moved on, and you would not find out until you
 * noticed a company had quietly stopped appearing in the digest.
 *
 * @param maxAttempts      total tries including the first, so 1 disables retrying
 * @param baseDelaySeconds first backoff, doubling on each further attempt
 * @param maxDelaySeconds  ceiling, so a server asking for a 2-hour wait does not
 *                         stall the whole run
 */
public record RetryPolicy(int maxAttempts, double baseDelaySeconds, double maxDelaySeconds) {

    /** Sentinel for "the server did not tell us how long to wait". */
    public static final long NO_RETRY_AFTER = -1;

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
        }
    }

    /** No retrying at all: one attempt, fail immediately. */
    public static RetryPolicy none() {
        return new RetryPolicy(1, 0, 0);
    }

    /**
     * Which failures are worth another go. 429 means "you are going too fast" and
     * 5xx means the server is having a bad moment: both usually clear on their own.
     * A 404 from a wrong board token, or a 401 from a bad key, will say exactly the
     * same thing on the tenth try, so retrying those only burns the rate-limit
     * budget and delays the real error reaching you.
     */
    public static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    /**
     * How long to wait before the next attempt.
     *
     * A server-supplied Retry-After always wins: it is the one party that actually
     * knows when it will accept traffic again. Otherwise back off exponentially
     * from baseDelaySeconds. Either way the wait is capped, then jittered, so a
     * batch of requests rate-limited at the same moment does not march back in
     * lockstep and trip the same limit again.
     *
     * @param completedAttempts how many tries have already failed, starting at 1
     * @param retryAfterSeconds the server's own answer, or NO_RETRY_AFTER
     */
    public double delaySeconds(int completedAttempts, long retryAfterSeconds) {
        double requested = retryAfterSeconds >= 0
                ? retryAfterSeconds
                : baseDelaySeconds * Math.pow(2, completedAttempts - 1);
        double capped = Math.min(requested, maxDelaySeconds);
        if (capped <= 0) {
            return 0;
        }
        return capped + ThreadLocalRandom.current().nextDouble(0, capped * 0.1);
    }
}
