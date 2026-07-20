package com.jobscout.extraction;

import java.util.List;

/** Structured fields pulled from a raw job posting by an LLM extraction call. */
public record VacancyExtraction(
        List<String> skills,
        String seniority,
        Integer salaryMin,
        Integer salaryMax,
        String salaryCurrency,
        String salaryPeriod,
        String languageRequirement,
        String remotePolicy,
        String model) {
}
