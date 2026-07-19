package com.jobscout.scraper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seniority/experience filtering shared by every scraper: keep internship and
 * junior/graduate roles, exclude senior+. Split into two checks because some
 * companies (confirmed on real Zendesk and Workday postings) state seniority
 * only in the description body, or avoid the word "senior" entirely while still
 * asking for years of experience above what fits "junior".
 */
public final class SeniorityFilter {
    // Titles/descriptions containing one of these read as a senior role...
    private static final Pattern SENIOR_TITLE_PATTERN = Pattern.compile(
            "\\bsenior\\b|\\bsr\\.?\\b|\\bstaff\\b|\\bprincipal\\b|\\blead\\b|\\bdirector\\b|\\bmanager\\b"
                    + "|\\bvp\\b|\\bvice president\\b|\\bchief\\b|\\bhead of\\b",
            Pattern.CASE_INSENSITIVE);

    // ...unless it also explicitly says junior/intern/graduate -- keep those regardless.
    private static final Pattern JUNIOR_INDICATOR_PATTERN = Pattern.compile(
            "\\bintern(ship)?\\b|\\bjunior\\b|\\bjr\\.?\\b|\\bgraduate\\b|\\bentry[- ]level\\b|\\bnew grad\\b",
            Pattern.CASE_INSENSITIVE);

    // Catches "3+ years experience" / "3-5 years' experience" / "3+ years in
    // data infrastructure" -- some postings avoid the word "senior" entirely but
    // still require more experience than fits "junior" (e.g. a real Zendesk
    // posting titled "Applied ML Scientist" asking for 3-5 years, or a real
    // Reddit posting asking for "3+ years in data infrastructure/platform
    // engineering" with no "experience" nearby at all). Deliberately does not
    // require the word "experience" to follow -- excludes only "N years ago"/
    // "N years old" (company-age phrasing), since almost every other "N years"
    // mention in a job posting is a seniority bar, not incidental.
    private static final Pattern YEARS_EXPERIENCE_PATTERN = Pattern.compile(
            "\\b(\\d{1,2})\\s*(?:[-–]|to)?\\s*\\d{0,2}\\+?\\s*years?\\b(?!\\s*(?:ago|old)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_JUNIOR_YEARS = 2;

    private SeniorityFilter() {
    }

    public static boolean isSeniorRole(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean senior = SENIOR_TITLE_PATTERN.matcher(text).find();
        boolean juniorSignal = JUNIOR_INDICATOR_PATTERN.matcher(text).find();
        return senior && !juniorSignal;
    }

    /**
     * A posting can avoid the word "senior" entirely while still asking for more
     * experience than fits "junior" -- this scans for "N years" style phrases
     * (regardless of whether "experience" follows) and flags anything above
     * MAX_JUNIOR_YEARS, unless an explicit junior/intern/graduate signal is
     * present (e.g. a dual-track posting).
     */
    public static boolean requiresTooMuchExperience(String text) {
        if (text == null || text.isBlank() || JUNIOR_INDICATOR_PATTERN.matcher(text).find()) {
            return false;
        }
        Matcher matcher = YEARS_EXPERIENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) > MAX_JUNIOR_YEARS) {
                return true;
            }
        }
        return false;
    }
}
