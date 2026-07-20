package com.jobscout.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Tracks postings that two-step scrapers (SmartRecruiters, Workday) have already
 * fetched full detail for and evaluated -- accepted or rejected -- so a rerun can
 * skip the detail-fetch entirely instead of re-requesting and re-filtering the same
 * posting every time.
 */
public final class SeenPostingRepository {
    private static final String IS_SEEN_SQL = """
            SELECT 1 FROM seen_postings WHERE source = ? AND external_id = ?
            """;

    private static final String MARK_SEEN_SQL = """
            INSERT INTO seen_postings (source, external_id, accepted, seen_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (source, external_id) DO NOTHING
            """;

    private SeenPostingRepository() {
    }

    public static boolean isSeen(Connection conn, String source, String externalId) {
        try (PreparedStatement stmt = conn.prepareStatement(IS_SEEN_SQL)) {
            stmt.setString(1, source);
            stmt.setString(2, externalId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to check seen_postings for " + source + "/" + externalId, exc);
        }
    }

    public static void markSeen(Connection conn, String source, String externalId, boolean accepted) {
        try (PreparedStatement stmt = conn.prepareStatement(MARK_SEEN_SQL)) {
            stmt.setString(1, source);
            stmt.setString(2, externalId);
            stmt.setInt(3, accepted ? 1 : 0);
            stmt.setString(4, Instant.now().toString());
            stmt.executeUpdate();
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to record seen_postings for " + source + "/" + externalId, exc);
        }
    }
}
