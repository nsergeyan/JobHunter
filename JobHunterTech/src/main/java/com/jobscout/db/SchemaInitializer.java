package com.jobscout.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;


public final class SchemaInitializer {
    private SchemaInitializer() {
    }

    public static void initDb(String dbPath) {
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exc) {
            throw new UncheckedIOException("Could not create parent directory for " + dbPath, exc);
        }

        String schemaSql = readSchemaResource();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement stmt = conn.createStatement()) {
            for (String statement : schemaSql.split(";")) {
                String trimmed = statement.strip();
                if (!trimmed.isEmpty()) {
                    stmt.executeUpdate(trimmed);
                }
            }
            // CREATE TABLE IF NOT EXISTS in schema.sql only helps on a fresh DB -- it's a
            // no-op against a table that already exists with an older column set. SQLite
            // has no "ADD COLUMN IF NOT EXISTS", so new columns on existing tables need an
            // explicit, checked migration here instead.
            addColumnIfMissing(conn, "vacancy_extractions", "salary_period", "TEXT");
            addColumnIfMissing(conn, "vacancy_extractions", "summary", "TEXT");
            addColumnIfMissing(conn, "vacancies", "external_id", "TEXT");
            addColumnIfMissing(conn, "seen_postings", "filter_version", "INTEGER");
            addColumnIfMissing(conn, "vacancies", "closed_at", "TEXT");
            // Rows written before this column existed were judged by the filters as they
            // stood then, which is version 1 -- backfill instead of leaving them NULL, so
            // upgrading doesn't silently trigger a detail-fetch of every posting ever
            // rejected. Re-checking those is a deliberate act: bump FilterVersion.CURRENT.
            stmt.executeUpdate("UPDATE seen_postings SET filter_version = 1 WHERE filter_version IS NULL");
            // Real job identity. Created here (not in schema.sql) so it runs AFTER the
            // column migration above -- on an upgraded DB the column doesn't exist while
            // schema.sql is executing. IF NOT EXISTS makes it a no-op once present.
            stmt.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_vacancies_source_external_id "
                            + "ON vacancies (source, external_id)");
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to initialize database at " + dbPath, exc);
        }
    }

    /**
     * A connection configured for concurrent use.
     *
     * WAL lets readers and a writer coexist instead of locking each other out, and
     * busy_timeout makes a thread that finds the write lock taken WAIT for it rather
     * than failing instantly with SQLITE_BUSY. Both matter now that scrapers run in
     * parallel, each on its own connection: without them, two scrapers finishing a
     * posting at the same moment is enough to lose one.
     *
     * WAL is a property of the database file and persists once set. busy_timeout is
     * per connection, so it has to be set on every one.
     */
    public static Connection openConnection(String dbPath) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=30000");
        } catch (SQLException exc) {
            conn.close();
            throw exc;
        }
        return conn;
    }

    private static void addColumnIfMissing(Connection conn, String table, String column, String columnType) {
        try (Statement checkStmt = conn.createStatement();
                ResultSet rs = checkStmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return;
                }
            }
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to inspect columns of " + table, exc);
        }
        try (Statement alterStmt = conn.createStatement()) {
            alterStmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + columnType);
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to add column " + column + " to " + table, exc);
        }
    }

    private static String readSchemaResource() {
        try (InputStream in = SchemaInitializer.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not found on classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new UncheckedIOException("Failed to read schema.sql", exc);
        }
    }
}
