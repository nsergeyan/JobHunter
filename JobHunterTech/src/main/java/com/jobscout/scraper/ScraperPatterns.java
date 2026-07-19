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

    private ScraperPatterns() {
    }
}
