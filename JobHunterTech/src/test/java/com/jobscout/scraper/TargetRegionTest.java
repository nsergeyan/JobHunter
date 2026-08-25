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

    @Test
    void textMentionsTargetRegionMatchesUkPostingsThatNeverNameTheCountry() {
        // Real location strings from the Greenhouse boards. None of these contain
        // "united kingdom", so matching on full country names alone dropped them.
        assertTrue(TargetRegion.textMentionsTargetRegion("London, England"));
        assertTrue(TargetRegion.textMentionsTargetRegion("London, UK"));
        assertTrue(TargetRegion.textMentionsTargetRegion("Edinburgh, Scotland"));
        assertTrue(TargetRegion.textMentionsTargetRegion("Cardiff, Wales"));

        // A multi-office posting is in scope as long as one office is.
        assertTrue(TargetRegion.textMentionsTargetRegion(
                "London, UK; Ontario, CAN; Remote-Friendly, United States; San Francisco, CA"));
    }

    @Test
    void ukAbbreviationMatchesWholeWordsOnly() {
        // "uk" as a substring appears inside place names that are not the UK, so
        // the abbreviation has to match on a word boundary.
        assertFalse(TargetRegion.textMentionsTargetRegion("Fukuoka, Japan"));
        assertFalse(TargetRegion.textMentionsTargetRegion("Tsukuba, Japan"));
        assertFalse(TargetRegion.textMentionsTargetRegion("Bukit Timah, Singapore"));
    }
}
