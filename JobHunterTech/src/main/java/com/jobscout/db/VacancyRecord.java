package com.jobscout.db;

public record VacancyRecord(
        String source,
        String url,
        String title,
        String company,
        String location,
        String rawText) {
}
