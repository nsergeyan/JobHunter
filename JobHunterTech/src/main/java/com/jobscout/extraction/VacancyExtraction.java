package com.jobscout.extraction;

import java.util.List;

/** Structured fields pulled from a raw job posting by an LLM extraction call. */
public record VacancyExtraction(
        String summary,
        List<String> skills,
        String seniority,
        Integer salaryMin,
        Integer salaryMax,
        String salaryCurrency,
        String salaryPeriod,
        String languageRequirement,
        String remotePolicy,
        String model) {

    /** Both providers' local models have been observed guessing a currency (typically
     * USD, apparently inferred from company nationality rather than the posting text)
     * even when no salary figure was extracted. A currency with no salary behind it is
     * never meaningful, so this invariant is enforced here rather than trusted to the
     * model. */
    public VacancyExtraction {
        if (salaryMin == null && salaryMax == null) {
            salaryCurrency = null;
        }
    }
}
