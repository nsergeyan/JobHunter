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

    // --- description-side seniority, added after the "staff" incident ---------

    /**
     * The exact failure that motivated splitting these checks. Anthropic's postings
     * all contain the word "staff" in boilerplate, and isSeniorRole rejected 16 of
     * 16 European candidates on it, including two Fellows Program roles and two
     * reinforcement learning roles in London.
     */
    private static final String ANTHROPIC_STYLE_BOILERPLATE =
            "We are looking for a Research Engineer to join our reinforcement learning team. "
            + "You will work alongside staff across the company on model training. "
            + "Your hiring manager will guide onboarding, and you will lead individual "
            + "projects end to end. We encourage applications from candidates at all levels.";

    @Test
    void ordinaryDescriptionProseIsNotTreatedAsSenior() {
        // "staff", "manager" and "lead" all appear here as ordinary English.
        assertFalse(SeniorityFilter.requiresTooMuchExperience(ANTHROPIC_STYLE_BOILERPLATE));
    }

    @Test
    void responsibilityForOthersStillCountsAsSenior() {
        // The bar stated without a number. "lead" alone must not trigger it, but
        // leading a team must.
        assertTrue(SeniorityFilter.requiresTooMuchExperience(
                "You will lead a team of engineers building our payments platform."));
        assertTrue(SeniorityFilter.requiresTooMuchExperience(
                "The role involves mentoring junior developers across two squads."
                    .replace("junior ", "")));
        assertFalse(SeniorityFilter.requiresTooMuchExperience(
                "You will lead this project from prototype to production."));
    }

    @Test
    void experienceStatedAsProseStillCountsAsSenior() {
        assertTrue(SeniorityFilter.requiresTooMuchExperience(
                "We are after extensive experience with distributed systems."));
        assertTrue(SeniorityFilter.requiresTooMuchExperience(
                "You bring a proven track record of shipping ML products."));
        assertTrue(SeniorityFilter.requiresTooMuchExperience(
                "This is a senior-level position on the platform team."));
    }

    @Test
    void aJuniorSignalStillDisablesEveryDescriptionCheck() {
        // Dual-track postings must survive all three signals, not just the years one.
        assertFalse(SeniorityFilter.requiresTooMuchExperience(
                "Graduate scheme. You will lead a team eventually and need 5 years to reach "
                + "principal-level, but we hire at entry-level."));
    }

    @Test
    void isSeniorRoleIsStillCorrectOnTitles() {
        // Unchanged, and still the right tool for the job it was left doing.
        assertTrue(SeniorityFilter.isSeniorRole("Staff Software Engineer"));
        assertTrue(SeniorityFilter.isSeniorRole("Engineering Manager"));
        assertFalse(SeniorityFilter.isSeniorRole("Software Engineer"));
        assertFalse(SeniorityFilter.isSeniorRole("Senior/Junior Data Scientist"));
    }
}
