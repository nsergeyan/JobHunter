package com.jobscout.scraper;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seniority/experience filtering shared by every scraper: keep internship and
 * junior/graduate roles, exclude senior+.
 *
 * Where each check may run matters, and getting it wrong was expensive.
 *
 * isSeniorRole is for TITLES ONLY. Its word list is unusable against a job
 * description, because several entries are ordinary English: "staff" means
 * employees, "lead" is a verb, "manager" turns up in "your hiring manager".
 * Measured against Anthropic's board, "staff" alone rejected 16 of 16 European
 * candidates, including two Fellows Program postings and two reinforcement
 * learning roles in London, purely on boilerplate.
 *
 * Descriptions are judged by requiresTooMuchExperience instead, which looks for
 * things that only appear when a role really is senior: a years-of-experience
 * bar above MAX_JUNIOR_YEARS, responsibility for other people, or the same bar
 * stated as prose. On the same Anthropic board that check caught all 12
 * genuinely senior roles by itself, and across 23 Greenhouse boards it let
 * through 24 postings worth having while still stopping the 2 that were senior.
 */
public final class SeniorityFilter {
    // Titles containing one of these read as a senior role. TITLES ONLY: see the
    // class comment for why this must never be run against a description.
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

    // Some postings spell the number out (e.g. "at least three years hands-on
    // experience") instead of using a digit -- YEARS_EXPERIENCE_PATTERN alone
    // misses those entirely. Only covers one-ten: phrasing above that is rare
    // enough in practice that it's not worth the added pattern complexity, and
    // such postings tend to also trip SENIOR_TITLE_PATTERN anyway.
    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10));
    private static final Pattern YEARS_EXPERIENCE_WORD_PATTERN = Pattern.compile(
            "\\b(" + String.join("|", NUMBER_WORDS.keySet()) + ")\\s*(?:[-–]|to)?\\s*"
                    + "(?:" + String.join("|", NUMBER_WORDS.keySet()) + ")?\\+?\\s*years?\\b(?!\\s*(?:ago|old)\\b)",
            Pattern.CASE_INSENSITIVE);

    // Responsibility for other people is a seniority bar that never mentions years.
    // A phrase rather than a bare word, deliberately: "lead" alone matches "you will
    // lead this project", whereas "leads a team" does not appear unless the role
    // really involves it.
    private static final Pattern LEADS_OTHERS_PATTERN = Pattern.compile(
            "\\b(?:lead|leads|leading|manage|manages|managing|mentor|mentors|mentoring|supervise|supervises)\\s+"
                    + "(?:a\\s+|the\\s+|our\\s+)?(?:team|teams|engineers|developers|others|juniors|reports)\\b",
            Pattern.CASE_INSENSITIVE);

    // The same experience bar written out instead of counted. Catches postings that
    // want a decade of work but never put a number on it.
    private static final Pattern SENIORITY_PROSE_PATTERN = Pattern.compile(
            "\\bextensive experience\\b|\\bproven track record\\b|\\bdeep expertise\\b"
                    + "|\\bsenior[- ]level\\b|\\bstaff[- ]level\\b|\\bprincipal[- ]level\\b",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_JUNIOR_YEARS = 2;

    private SeniorityFilter() {
    }

    /**
     * TITLE ONLY. Do not call this with a job description: the word list contains
     * ordinary English and will reject almost everything. See the class comment.
     */
    public static boolean isSeniorRole(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean senior = SENIOR_TITLE_PATTERN.matcher(text).find();
        boolean juniorSignal = JUNIOR_INDICATOR_PATTERN.matcher(text).find();
        return senior && !juniorSignal;
    }

    /**
     * The description-side seniority check, and the only one safe to run over a
     * whole job description.
     *
     * Three signals, none of which fire on ordinary prose. A years-of-experience
     * bar above MAX_JUNIOR_YEARS, whether or not the word "experience" follows.
     * Responsibility for other people. And the same bar written as prose rather
     * than counted.
     *
     * An explicit junior, intern or graduate signal anywhere disables all three,
     * so dual-track postings survive.
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

        Matcher wordMatcher = YEARS_EXPERIENCE_WORD_PATTERN.matcher(text);
        while (wordMatcher.find()) {
            int years = NUMBER_WORDS.get(wordMatcher.group(1).toLowerCase(Locale.ROOT));
            if (years > MAX_JUNIOR_YEARS) {
                return true;
            }
        }

        return LEADS_OTHERS_PATTERN.matcher(text).find()
                || SENIORITY_PROSE_PATTERN.matcher(text).find();
    }
}
