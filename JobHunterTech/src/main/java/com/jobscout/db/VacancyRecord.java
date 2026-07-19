package com.jobscout.db;

/** Same shape as the Python @dataclass VacancyRecord. */
public record VacancyRecord(
        String source,
        String url,
        String title,
        String company,
        String location,
        String rawText) {
}
