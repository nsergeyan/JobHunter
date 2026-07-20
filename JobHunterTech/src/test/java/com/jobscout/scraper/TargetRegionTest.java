package com.jobscout.scraper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetRegionTest {

    @Test
    void isInScopeMatchesStructuredCountryNames() {
        assertTrue(TargetRegion.isInScope("Germany"));
        assertFalse(TargetRegion.isInScope("United States of America"));
        assertFalse(TargetRegion.isInScope("India"));
        assertFalse(TargetRegion.isInScope(null));
    }

    @Test
    void isInScopeByCountryCodeMatchesIsoAlpha2Codes() {
        // Lever's "country" field uses ISO 3166-1 alpha-2 codes, not full names.
        assertTrue(TargetRegion.isInScopeByCountryCode("GB"));
        assertFalse(TargetRegion.isInScopeByCountryCode("us"));
        assertTrue(TargetRegion.isInScopeByCountryCode("DE"));
        assertFalse(TargetRegion.isInScopeByCountryCode("IN"));
        assertFalse(TargetRegion.isInScopeByCountryCode(null));
    }

    @Test
    void textMentionsTargetRegionMatchesFullCountryNamesOnly() {
        assertFalse(TargetRegion.textMentionsTargetRegion("Austin, Texas, United States of America"));
        assertTrue(TargetRegion.textMentionsTargetRegion("Berlin, Germany"));

        // US postings (Greenhouse's "City, ST" format, with or without "United
        // States" spelled out) are out of scope now -- Europe-only.
        assertFalse(TargetRegion.textMentionsTargetRegion("San Francisco, CA"));
        assertFalse(TargetRegion.textMentionsTargetRegion("New York City, NY"));
        assertFalse(TargetRegion.textMentionsTargetRegion("Washington, DC"));

        assertFalse(TargetRegion.textMentionsTargetRegion("Bangalore, India"));
        assertFalse(TargetRegion.textMentionsTargetRegion("Ontario, CAN"));
        assertFalse(TargetRegion.textMentionsTargetRegion(""));
    }
}
