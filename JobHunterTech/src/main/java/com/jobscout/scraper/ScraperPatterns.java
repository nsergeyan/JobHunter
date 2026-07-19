package com.jobscout.scraper;

import java.util.regex.Pattern;

/** Relevance filter shared by every scraper: DS/AI/software-engineering roles only. */
public final class ScraperPatterns {
    /**
     * For plain-English job titles (e.g. Workday postings read "Software Engineer
     * II", not "software-engineer") -- a space or hyphen both count as the word
     * separator.
     */
    public static final Pattern RELEVANCE_TITLE_PATTERN = Pattern.compile(
            "data[\\s-]scien|machine[\\s-]learning|\\bai\\b|artificial[\\s-]intelligence|data[\\s-]analy"
                    + "|\\bml\\b|data[\\s-]engineer|software[\\s-]engineer|software[\\s-]develop|\\bdeveloper\\b"
                    + "|backend|back[\\s-]end|full[\\s-]stack|fullstack"
                    + "|\\bprogrammer\\b|devops",
            Pattern.CASE_INSENSITIVE);

    /**
     * Not interested in mobile or frontend roles, even if the title otherwise looks
     * relevant -- a true exclusion, not just an omission from RELEVANCE_TITLE_PATTERN,
     * since a title like "Frontend Developer" still matches that pattern's generic
     * \bdeveloper\b and would otherwise slip through.
     */
    public static final Pattern EXCLUDED_ROLE_PATTERN = Pattern.compile(
            "\\bandroid\\b|\\bios\\b|\\breact native\\b|\\bflutter\\b|\\bmobile\\b"
                    + "|frontend|front[\\s-]end",
            Pattern.CASE_INSENSITIVE);

    private ScraperPatterns() {
    }

    /** Relevant, not excluded (mobile/frontend), and not senior -- the shared title-only pre-filter every scraper uses. */
    public static boolean isCandidateTitle(String title) {
        return RELEVANCE_TITLE_PATTERN.matcher(title).find()
                && !EXCLUDED_ROLE_PATTERN.matcher(title).find()
                && !SeniorityFilter.isSeniorRole(title);
    }
}
