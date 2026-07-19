package com.jobscout.scraper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScraperPatternsTest {

    @Test
    void isCandidateTitleExcludesSeniorRolesUnlessJuniorIndicatorPresent() {
        assertTrue(ScraperPatterns.isCandidateTitle("Software Engineer II"));
        assertTrue(ScraperPatterns.isCandidateTitle("Data Scientist"));
        assertTrue(ScraperPatterns.isCandidateTitle("Software Engineer Intern"));
        assertTrue(ScraperPatterns.isCandidateTitle("Junior Data Analyst"));
        assertTrue(ScraperPatterns.isCandidateTitle("Machine Learning New Grad"));

        assertFalse(ScraperPatterns.isCandidateTitle("Senior Data Engineer"));
        assertFalse(ScraperPatterns.isCandidateTitle("Staff Software Engineer"));
        assertFalse(ScraperPatterns.isCandidateTitle("Engineering Manager"));
        assertFalse(ScraperPatterns.isCandidateTitle("Sales Manager"));
    }

    @Test
    void isCandidateTitleExcludesMobileRoles() {
        assertFalse(ScraperPatterns.isCandidateTitle("Software Engineer, Android"));
        assertFalse(ScraperPatterns.isCandidateTitle("iOS Developer"));
        assertFalse(ScraperPatterns.isCandidateTitle("Mobile Software Engineer"));
        assertFalse(ScraperPatterns.isCandidateTitle("React Native Developer"));
        assertFalse(ScraperPatterns.isCandidateTitle("Flutter Developer"));

        // Non-mobile roles with otherwise-similar wording still pass.
        assertTrue(ScraperPatterns.isCandidateTitle("Backend Software Engineer"));
    }

    @Test
    void isCandidateTitleExcludesFrontendRoles() {
        assertFalse(ScraperPatterns.isCandidateTitle("Frontend Developer"));
        assertFalse(ScraperPatterns.isCandidateTitle("Front-End Engineer"));
    }
}
