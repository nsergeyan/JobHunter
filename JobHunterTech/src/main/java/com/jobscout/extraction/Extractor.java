package com.jobscout.extraction;

/** A provider that can pull structured fields out of a raw job posting. */
public interface Extractor {
    VacancyExtraction extract(String rawText);
}
