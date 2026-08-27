package com.jobscout.scraper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    private static final RetryPolicy POLICY = new RetryPolicy(3, 2, 60);

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void transientFailuresAreRetried(int statusCode) {
        assertTrue(RetryPolicy.isRetryableStatus(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 410})
    void permanentFailuresAreNotRetried(int statusCode) {
        // A wrong Greenhouse board token 404s forever. Retrying burns the rate-limit
        // budget and delays the real error reaching the log.
        assertFalse(RetryPolicy.isRetryableStatus(statusCode));
    }

    @Test
    void backoffGrowsExponentiallyFromTheBaseDelay() {
        // Jitter adds up to 10%, so assert on the band rather than an exact value.
        assertBetween(2, 2.2, POLICY.delaySeconds(1, RetryPolicy.NO_RETRY_AFTER));
        assertBetween(4, 4.4, POLICY.delaySeconds(2, RetryPolicy.NO_RETRY_AFTER));
        assertBetween(8, 8.8, POLICY.delaySeconds(3, RetryPolicy.NO_RETRY_AFTER));
    }

    @Test
    void serverSuppliedRetryAfterWins() {
        // The server is the only party that knows when it will accept traffic again,
        // so its answer beats our guess even when it is longer.
        assertBetween(30, 33, POLICY.delaySeconds(1, 30));
    }

    @Test
    void waitIsCappedSoOneRudeServerCannotStallTheRun() {
        assertBetween(60, 66, POLICY.delaySeconds(1, 7200));
        assertBetween(60, 66, POLICY.delaySeconds(10, RetryPolicy.NO_RETRY_AFTER));
    }

    @Test
    void zeroDelayStaysZeroSoTestsDoNotSleep() {
        assertEquals(0, RetryPolicy.none().delaySeconds(1, RetryPolicy.NO_RETRY_AFTER));
    }

    @Test
    void policyMustAllowAtLeastOneAttempt() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 1, 1));
    }

    private static void assertBetween(double low, double high, double actual) {
        assertTrue(actual >= low && actual <= high,
                "expected " + actual + " to be within [" + low + ", " + high + "]");
    }
}
