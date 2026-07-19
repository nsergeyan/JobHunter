package com.jobscout.scraper.ashby;

import com.jobscout.db.SchemaInitializer;
import com.jobscout.scraper.FakeHttpFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AshbyScraperTest {

    private static final AshbyCompany TEST_CO = new AshbyCompany("TestCo", "testco");
    private static final String JOBS_URL = "https://api.ashbyhq.com/posting-api/job-board/testco";

    private static String jobJson(String title, String country, String location, String descriptionPlain) {
        return """
                {
                  "title": "%s",
                  "jobUrl": "https://jobs.ashbyhq.com/testco/abc123",
                  "location": "%s",
                  "address": {"postalAddress": {"addressCountry": "%s"}},
                  "descriptionPlain": "%s"
                }
                """.formatted(title, location, country, descriptionPlain);
    }

    private static FakeHttpFetcher fetcherFor(String jobsListJson) {
        return new FakeHttpFetcher((url, body) -> {
            if (url.equals(JOBS_URL)) {
                return jobsListJson;
            }
            throw new AssertionError("Unexpected request: " + url);
        });
    }

    private static Connection freshDb(Path tmpDir) throws SQLException {
        String dbPath = tmpDir.resolve("test.db").toString();
        SchemaInitializer.initDb(dbPath);
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    @Test
    void isCandidateTitleExcludesSeniorRolesUnlessJuniorIndicatorPresent() {
        assertTrue(AshbyScraper.isCandidateTitle("Machine Learning Engineer"));
        assertTrue(AshbyScraper.isCandidateTitle("Data Scientist Intern"));
        assertFalse(AshbyScraper.isCandidateTitle("Senior Machine Learning Engineer"));
        assertFalse(AshbyScraper.isCandidateTitle("Sales Manager"));
    }

    @Test
    void runStoresJuniorEuropeOrUsVacancy(@TempDir Path tmpDir) throws SQLException {
        String job = jobJson(
                "Machine Learning Engineer", "United States", "San Francisco",
                "Build and ship ML models with the team.");
        String jobsList = "{\"jobs\":[" + job + "]}";
        AshbyScraper scraper = new AshbyScraper(fetcherFor(jobsList), List.of(TEST_CO));

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(1, count);

            try (ResultSet rs = conn.createStatement()
                    .executeQuery("SELECT source, title, company, location, raw_text, url FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("ashby", rs.getString("source"));
                assertEquals("Machine Learning Engineer", rs.getString("title"));
                assertEquals("TestCo", rs.getString("company"));
                assertEquals("San Francisco", rs.getString("location"));
                assertEquals("Build and ship ML models with the team.", rs.getString("raw_text"));
                assertEquals("https://jobs.ashbyhq.com/testco/abc123", rs.getString("url"));
            }
        }
    }

    @Test
    void runSkipsPostingsOutsideEuropeAndUS(@TempDir Path tmpDir) throws SQLException {
        String job = jobJson("Data Scientist", "India", "Bangalore", "Analyze data at scale.");
        String jobsList = "{\"jobs\":[" + job + "]}";
        AshbyScraper scraper = new AshbyScraper(fetcherFor(jobsList), List.of(TEST_CO));

        try (Connection conn = freshDb(tmpDir)) {
            assertEquals(0, scraper.run(conn));
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS c FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("c"));
            }
        }
    }

    @Test
    void runSkipsPostingsWhereDescriptionRevealsSeniorityOrTooMuchExperience(@TempDir Path tmpDir) throws SQLException {
        String seniorInDescription = jobJson(
                "AI Platform Engineer", "United States", "Remote",
                "We are hiring a Senior Staff engineer to lead this effort.");
        String tooMuchExperience = jobJson(
                "Applied Scientist", "France", "Paris",
                "3-5 years experience in machine learning required.");
        String jobsList = "{\"jobs\":[" + seniorInDescription + "," + tooMuchExperience + "]}";
        AshbyScraper scraper = new AshbyScraper(fetcherFor(jobsList), List.of(TEST_CO));

        try (Connection conn = freshDb(tmpDir)) {
            assertEquals(0, scraper.run(conn));
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS c FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("c"));
            }
        }
    }
}
