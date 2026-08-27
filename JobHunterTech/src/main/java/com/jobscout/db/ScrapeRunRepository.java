package com.jobscout.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * One row per company per scrape: how many postings its board returned, how many
 * survived the filters, and the error if the fetch failed.
 *
 * Why bother. When a job board changes its JSON shape or a board token goes stale,
 * nothing crashes: the scraper prints one "Skipping Acme" line among hundreds and
 * carries on, and the company silently stops appearing in the digest. Weeks can
 * pass before anyone notices. These rows make that visible, and give the digest
 * something concrete to report.
 */
public final class ScrapeRunRepository {
    private static final String INSERT_SQL = """
            INSERT INTO scrape_runs (source, company, started_at, finished_at, fetched, accepted, rejected, error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** Companies whose most recent scrape failed, newest run first. */
    private static final String RECENT_FAILURES_SQL = """
            SELECT source, company, error, finished_at FROM scrape_runs
            WHERE error IS NOT NULL AND finished_at >= ?
            ORDER BY finished_at DESC
            """;

    private ScrapeRunRepository() {
    }

    public static void record(Connection conn, String source, String company, String startedAt,
            String finishedAt, int fetched, int accepted, int rejected, String error) {
        try (PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            stmt.setString(1, source);
            stmt.setString(2, company);
            stmt.setString(3, startedAt);
            stmt.setString(4, finishedAt);
            stmt.setInt(5, fetched);
            stmt.setInt(6, accepted);
            stmt.setInt(7, rejected);
            stmt.setString(8, error);
            stmt.executeUpdate();
        } catch (SQLException exc) {
            // A bookkeeping failure must never take down a scrape that is otherwise
            // working, so this is reported and swallowed rather than thrown.
            System.err.println("Could not record scrape run for " + source + "/" + company + ": " + exc.getMessage());
        }
    }

    public static List<String> recentFailures(Connection conn, String since) {
        List<String> failures = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(RECENT_FAILURES_SQL)) {
            stmt.setString(1, since);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    failures.add(rs.getString("source") + "/" + rs.getString("company")
                            + ": " + rs.getString("error"));
                }
            }
        } catch (SQLException exc) {
            System.err.println("Could not read scrape failures: " + exc.getMessage());
        }
        return failures;
    }
}
