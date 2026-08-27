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

    /**
     * Refresh last_seen for a posting we saw on a board, whether or not it passed
     * the filters. No-op when we never stored it.
     *
     * This is what separates "this posting is gone" from "this posting no longer
     * matches my filters". Only postings absent from the board entirely fall behind
     * the cutoff and get closed.
     */
    private static final String TOUCH_SQL = """
            UPDATE vacancies SET last_seen = ? WHERE source = ? AND external_id = ?
            """;

    /**
     * Mark this company's postings closed when they were not seen in the run that
     * started at `cutoff`. Only ever called after a company's listing was fetched
     * in full and without error, so an outage cannot mass-close a board.
     */
    private static final String CLOSE_STALE_SQL = """
            UPDATE vacancies SET closed_at = ?
            WHERE source = ? AND company = ? AND closed_at IS NULL AND last_seen < ?
            """;

    private VacancyRepository() {
    }

    public static void touchLastSeen(Connection conn, String source, String externalId) {
        String now = Instant.now().toString();
        try (PreparedStatement stmt = conn.prepareStatement(TOUCH_SQL)) {
            stmt.setString(1, now);
            stmt.setString(2, source);
            stmt.setString(3, externalId);
            stmt.executeUpdate();
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to touch last_seen for " + source + "/" + externalId, exc);
        }
    }

    public static int closeStale(Connection conn, String source, String company, String cutoff) {
        try (PreparedStatement stmt = conn.prepareStatement(CLOSE_STALE_SQL)) {
            stmt.setString(1, Instant.now().toString());
            stmt.setString(2, source);
            stmt.setString(3, company);
            stmt.setString(4, cutoff);
            return stmt.executeUpdate();
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to close stale vacancies for " + source + "/" + company, exc);
        }
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
