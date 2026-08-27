package com.jobscout.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour these pin down is why filter_version exists: a rejection recorded
 * under one set of scrape-time filters must not silently outlive them.
 */
class SeenPostingRepositoryTest {

    @Test
    void postingIsSeenOnlyUnderTheVersionThatEvaluatedIt(@TempDir Path tmpDir) throws SQLException {
        String dbPath = tmpDir.resolve("test.db").toString();
        SchemaInitializer.initDb(dbPath);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            SeenPostingRepository.markSeen(conn, "workday", "ext-1", false, 1);

            assertTrue(SeenPostingRepository.isSeen(conn, "workday", "ext-1", 1),
                    "same filter version should still skip the detail fetch");
            assertFalse(SeenPostingRepository.isSeen(conn, "workday", "ext-1", 2),
                    "after a filter change the posting must be re-evaluated, not stay buried");
        }
    }

    @Test
    void reEvaluationReplacesTheStaleVerdict(@TempDir Path tmpDir) throws SQLException {
        String dbPath = tmpDir.resolve("test.db").toString();
        SchemaInitializer.initDb(dbPath);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            // Rejected under v1, then accepted once the filters loosened under v2.
            SeenPostingRepository.markSeen(conn, "workday", "ext-1", false, 1);
            SeenPostingRepository.markSeen(conn, "workday", "ext-1", true, 2);

            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(
                            "SELECT COUNT(*) c, MAX(accepted) accepted, MAX(filter_version) v FROM seen_postings")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("c"), "re-evaluation must update in place, not insert a second row");
                assertEquals(1, rs.getInt("accepted"), "the new verdict should replace the old one");
                assertEquals(2, rs.getInt("v"), "the row should carry the version that produced the new verdict");
            }

            assertTrue(SeenPostingRepository.isSeen(conn, "workday", "ext-1", 2),
                    "once re-marked at the current version it must stop being re-fetched every run");
        }
    }

    @Test
    void legacyRowsAreBackfilledToVersionOne(@TempDir Path tmpDir) throws SQLException {
        String dbPath = tmpDir.resolve("test.db").toString();

        // A pre-migration database: seen_postings without the filter_version column.
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE seen_postings (source TEXT NOT NULL, external_id TEXT NOT NULL, "
                    + "accepted INTEGER NOT NULL, seen_at TEXT NOT NULL, PRIMARY KEY (source, external_id))");
            stmt.executeUpdate("INSERT INTO seen_postings VALUES ('workday', 'legacy-1', 0, '2026-07-20T10:00:00Z')");
        }

        SchemaInitializer.initDb(dbPath);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            // Backfilled rather than left NULL, so upgrading does not trigger a
            // detail-fetch of every posting ever rejected.
            assertTrue(SeenPostingRepository.isSeen(conn, "workday", "legacy-1", 1),
                    "legacy rows should count as evaluated under version 1");
            assertFalse(SeenPostingRepository.isSeen(conn, "workday", "legacy-1", 2),
                    "and should re-open on the next deliberate version bump");
        }
    }
}
