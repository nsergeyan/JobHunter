package com.jobscout.db;

/** Minimal projection of a vacancy row needed to run LLM extraction against it. */
public record VacancyToExtract(long id, String rawText) {
}
