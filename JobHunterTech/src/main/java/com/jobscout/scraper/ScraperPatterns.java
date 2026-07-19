package com.jobscout.scraper;

import java.util.regex.Pattern;

/** Relevance filter shared by every scraper: DS/AI/software-engineering roles only. */
public final class ScraperPatterns {
    /** For URL slugs (Magnet.me, StudentJob.nl candidate URLs use hyphens, e.g. "data-scientist-intern"). */
    public static final Pattern RELEVANCE_PATTERN = Pattern.compile(
            "data-scien|machine-learning|\\bai\\b|artificial-intelligence|data-analy|\\bml\\b|data-engineer"
                    + "|software-engineer|software-develop|\\bdeveloper\\b|backend|back-end|frontend|front-end"
                    + "|full-stack|fullstack|\\bprogrammer\\b|devops",
            Pattern.CASE_INSENSITIVE);

    /**
     * For plain-English job titles (Workday postings read "Software Engineer II",
     * not "software-engineer") -- same keywords as RELEVANCE_PATTERN, but a space
     * or hyphen both count as the word separator instead of requiring a hyphen.
     */
    public static final Pattern RELEVANCE_TITLE_PATTERN = Pattern.compile(
            "data[\\s-]scien|machine[\\s-]learning|\\bai\\b|artificial[\\s-]intelligence|data[\\s-]analy"
                    + "|\\bml\\b|data[\\s-]engineer|software[\\s-]engineer|software[\\s-]develop|\\bdeveloper\\b"
                    + "|backend|back[\\s-]end|frontend|front[\\s-]end|full[\\s-]stack|fullstack"
                    + "|\\bprogrammer\\b|devops",
            Pattern.CASE_INSENSITIVE);

    private ScraperPatterns() {
    }
}
