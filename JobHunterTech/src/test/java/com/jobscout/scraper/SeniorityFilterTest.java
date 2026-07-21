package com.jobscout.scraper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeniorityFilterTest {

    @Test
    void isSeniorRoleCatchesSeniorityKeywordsUnlessJuniorIndicatorPresent() {
        assertTrue(SeniorityFilter.isSeniorRole("Senior Data Engineer"));
        assertTrue(SeniorityFilter.isSeniorRole("Staff Software Engineer"));
        assertTrue(SeniorityFilter.isSeniorRole("Engineering Manager"));
        // Real-world case: seniority stated only in body text, not the title --
        // matches the actual Zendesk "AI Agent Abuse Prevention Engineer" posting.
        assertTrue(SeniorityFilter.isSeniorRole(
                "We are hiring a Senior Staff-level technical leader to own this area."));

        assertFalse(SeniorityFilter.isSeniorRole("Software Engineer II"));
        assertFalse(SeniorityFilter.isSeniorRole("Software Engineer Intern"));
        assertFalse(SeniorityFilter.isSeniorRole("Junior Data Analyst"));
        assertFalse(SeniorityFilter.isSeniorRole(
                "This Senior-mentored internship program pairs you with a senior engineer."));
    }

    @Test
    void requiresTooMuchExperienceCatchesYearsPhrasesAboveTheJuniorThreshold() {
        // Real Zendesk posting text ("Applied ML Scientist") -- no "senior" keyword
        // anywhere, but asks for 3-5 years, which is above the 0-2 year junior bar.
        assertTrue(SeniorityFilter.requiresTooMuchExperience(
                "3–5 years' experience in applied machine learning, data science, or a related field"));
        assertTrue(SeniorityFilter.requiresTooMuchExperience("5+ years of experience required"));

        assertFalse(SeniorityFilter.requiresTooMuchExperience("0-2 years of experience is a plus"));
        assertFalse(SeniorityFilter.requiresTooMuchExperience("No prior experience required, just curiosity"));

        // Explicit junior/intern signal overrides even a high years mention.
        assertFalse(SeniorityFilter.requiresTooMuchExperience(
                "This internship is for students; some roles convert to 5+ years senior tracks later"));

        // Real Philips "Data Scientist - Agentic AI Engineer" posting -- spells the
        // number out instead of using a digit, which the digit-only pattern misses.
        assertTrue(SeniorityFilter.requiresTooMuchExperience(
                "You have at least three years hands-on experience developing AI/ML systems"));
        assertTrue(SeniorityFilter.requiresTooMuchExperience("Five years of experience required"));

        assertFalse(SeniorityFilter.requiresTooMuchExperience("Two years of experience is a plus"));
    }
}
