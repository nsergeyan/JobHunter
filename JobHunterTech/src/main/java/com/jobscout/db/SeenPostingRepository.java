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
 *
 * Every record is stamped with the filter version that produced it (see
 * FilterVersion). A posting only counts as "seen" when it was evaluated under the
 * CURRENT version, so changing what the filters accept automatically re-opens
 * everything they previously rejected instead of leaving it buried.
 */
public final class SeenPostingRepository {
    private static final String IS_SEEN_SQL = """
            SELECT 1 FROM seen_postings
            WHERE source = ? AND external_id = ? AND filter_version = ?
            """;

    // DO UPDATE, not DO NOTHING: after a filter-version bump a posting gets
    // re-evaluated, and the fresh verdict plus its new version has to replace the
    // stale row, otherwise it would be re-fetched again on every subsequent run.
    private static final String MARK_SEEN_SQL = """
            INSERT INTO seen_postings (source, external_id, accepted, seen_at, filter_version)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (source, external_id) DO UPDATE SET
                accepted = excluded.accepted,
                seen_at = excluded.seen_at,
                filter_version = excluded.filter_version
            """;

    private SeenPostingRepository() {
    }

    public static boolean isSeen(Connection conn, String source, String externalId, int filterVersion) {
        try (PreparedStatement stmt = conn.prepareStatement(IS_SEEN_SQL)) {
            stmt.setString(1, source);
            stmt.setString(2, externalId);
            stmt.setInt(3, filterVersion);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to check seen_postings for " + source + "/" + externalId, exc);
        }
    }

    public static void markSeen(Connection conn, String source, String externalId, boolean accepted,
            int filterVersion) {
        try (PreparedStatement stmt = conn.prepareStatement(MARK_SEEN_SQL)) {
            stmt.setString(1, source);
            stmt.setString(2, externalId);
            stmt.setInt(3, accepted ? 1 : 0);
            stmt.setString(4, Instant.now().toString());
            stmt.setInt(5, filterVersion);
            stmt.executeUpdate();
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to record seen_postings for " + source + "/" + externalId, exc);
        }
    }
}
