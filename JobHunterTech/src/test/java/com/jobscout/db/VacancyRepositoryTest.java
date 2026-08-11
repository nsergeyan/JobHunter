package com.jobscout.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VacancyRepositoryTest {

    @Test
    void initDbCreatesVacanciesTable(@TempDir Path tmpDir) throws SQLException {
        String dbPath = tmpDir.resolve("test.db").toString();
        SchemaInitializer.initDb(dbPath);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='vacancies'")) {
            assertTrue(rs.next());
            assertEquals("vacancies", rs.getString("name"));
        }
    }

    @Test
    void upsertInsertsThenUpdatesOnConflict(@TempDir Path tmpDir) throws SQLException {
        String dbPath = tmpDir.resolve("test.db").toString();
        SchemaInitializer.initDb(dbPath);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            VacancyRecord first = new VacancyRecord(
                    "studentjob", "ext-1", "https://example.com/1", "ML Intern", "Acme", "Amsterdam, NL", "Join us");
            VacancyRepository.upsertVacancy(conn, first);

            VacancyRecord updated = new VacancyRecord(
                    "studentjob", "ext-1", "https://example.com/1", "ML Intern (updated)", "Acme", "Amsterdam, NL", "Join us now");
            VacancyRepository.upsertVacancy(conn, updated);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT title, raw_text FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("ML Intern (updated)", rs.getString("title"));
                assertEquals("Join us now", rs.getString("raw_text"));
                assertTrue(!rs.next(), "expected exactly one row, dedup on (source, external_id) failed");
            }
        }
    }

    @Test
    void upsertDedupsSameJobWhenUrlChanges(@TempDir Path tmpDir) throws SQLException {
        String dbPath = tmpDir.resolve("test.db").toString();
        SchemaInitializer.initDb(dbPath);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            // Same job (same source + external_id) resurfacing under a different url -- the
            // Greenhouse absolute_url drift that used to create a duplicate second row.
            VacancyRepository.upsertVacancy(conn, new VacancyRecord(
                    "greenhouse", "7437779002", "https://job-boards.greenhouse.io/janestreet/jobs/7437779002",
                    "ML Engineer", "Jane Street", "London", "desc"));
            VacancyRepository.upsertVacancy(conn, new VacancyRecord(
                    "greenhouse", "7437779002",
                    "https://www.janestreet.com/join-jane-street/apply/7437779002?gh_jid=7437779002",
                    "ML Engineer", "Jane Street", "London", "desc"));

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) c, MAX(url) url FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("c"), "same job under two urls must collapse to one row");
                assertEquals("https://www.janestreet.com/join-jane-street/apply/7437779002?gh_jid=7437779002",
                        rs.getString("url"), "kept row's url should refresh to the latest scrape");
            }
        }
    }
}
