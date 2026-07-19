package com.jobscout.scraper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetRegionTest {

    @Test
    void isInScopeMatchesStructuredCountryNames() {
        assertTrue(TargetRegion.isInScope("Germany"));
        assertTrue(TargetRegion.isInScope("United States of America"));
        assertFalse(TargetRegion.isInScope("India"));
        assertFalse(TargetRegion.isInScope(null));
    }

    @Test
    void isInScopeByCountryCodeMatchesIsoAlpha2Codes() {
        // Lever's "country" field uses ISO 3166-1 alpha-2 codes, not full names.
        assertTrue(TargetRegion.isInScopeByCountryCode("GB"));
        assertTrue(TargetRegion.isInScopeByCountryCode("us"));
        assertTrue(TargetRegion.isInScopeByCountryCode("DE"));
        assertFalse(TargetRegion.isInScopeByCountryCode("IN"));
        assertFalse(TargetRegion.isInScopeByCountryCode(null));
    }

    @Test
    void textMentionsTargetRegionMatchesFullCountryNamesAndUsStateAbbreviations() {
        assertTrue(TargetRegion.textMentionsTargetRegion("Austin, Texas, United States of America"));
        assertTrue(TargetRegion.textMentionsTargetRegion("Berlin, Germany"));

        // Real-world case: Greenhouse formats US locations as "City, ST" with no
        // "United States" spelled out at all -- this slipped past the filter
        // before US state abbreviations were added, silently excluding real US
        // postings from every Greenhouse company.
        assertTrue(TargetRegion.textMentionsTargetRegion("San Francisco, CA"));
        assertTrue(TargetRegion.textMentionsTargetRegion("New York City, NY"));
        assertTrue(TargetRegion.textMentionsTargetRegion("Washington, DC"));

        assertFalse(TargetRegion.textMentionsTargetRegion("Bangalore, India"));
        assertFalse(TargetRegion.textMentionsTargetRegion("Ontario, CAN"));
        assertFalse(TargetRegion.textMentionsTargetRegion(""));
    }
}
