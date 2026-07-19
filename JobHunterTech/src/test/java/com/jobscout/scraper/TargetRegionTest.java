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
