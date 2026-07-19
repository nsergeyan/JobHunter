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
                    "studentjob", "https://example.com/1", "ML Intern", "Acme", "Amsterdam, NL", "Join us");
            VacancyRepository.upsertVacancy(conn, first);

            VacancyRecord updated = new VacancyRecord(
                    "studentjob", "https://example.com/1", "ML Intern (updated)", "Acme", "Amsterdam, NL", "Join us now");
            VacancyRepository.upsertVacancy(conn, updated);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT title, raw_text FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("ML Intern (updated)", rs.getString("title"));
                assertEquals("Join us now", rs.getString("raw_text"));
                assertTrue(!rs.next(), "expected exactly one row, dedup on (source, url) failed");
            }
        }
    }
}
