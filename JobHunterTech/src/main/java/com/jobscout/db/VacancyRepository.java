package com.jobscout.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

public final class VacancyRepository {
    private static final String UPSERT_SQL = """
            INSERT INTO vacancies (source, external_id, url, title, company, location, raw_text, scraped_at, first_seen, last_seen)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source, external_id) DO UPDATE SET
                url = excluded.url,
                title = excluded.title,
                company = excluded.company,
                location = excluded.location,
                raw_text = excluded.raw_text,
                scraped_at = excluded.scraped_at,
                last_seen = excluded.last_seen
            """;

    private VacancyRepository() {
    }

    public static void upsertVacancy(Connection conn, VacancyRecord vacancy) {
        String now = Instant.now().toString();
        try (PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
            stmt.setString(1, vacancy.source());
            stmt.setString(2, vacancy.externalId());
            stmt.setString(3, vacancy.url());
            stmt.setString(4, vacancy.title());
            stmt.setString(5, vacancy.company());
            stmt.setString(6, vacancy.location());
            stmt.setString(7, vacancy.rawText());
            stmt.setString(8, now);
            stmt.setString(9, now);
            stmt.setString(10, now);
            stmt.executeUpdate();
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to upsert vacancy " + vacancy.url(), exc);
        }
    }
}
