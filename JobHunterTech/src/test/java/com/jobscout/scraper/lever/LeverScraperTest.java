package com.jobscout.scraper.lever;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeverScraperTest {

    private static final LeverCompany TEST_CO = new LeverCompany("TestCo", "testco");
    private static final String JOBS_URL = "https://api.lever.co/v0/postings/testco?mode=json";

    private static String jobJson(String title, String countryCode, String location, String descriptionPlain) {
        return """
                {
                  "text": "%s",
                  "hostedUrl": "https://jobs.lever.co/testco/abc123",
                  "country": "%s",
                  "categories": {"location": "%s"},
                  "openingPlain": "",
                  "descriptionPlain": "%s",
                  "descriptionBodyPlain": "",
                  "additionalPlain": ""
                }
                """.formatted(title, countryCode, location, descriptionPlain);
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
    void runParsesBareJsonArrayAndStoresJuniorEuropeVacancy(@TempDir Path tmpDir) throws SQLException {
        String job = jobJson(
                "Machine Learning Engineer", "GB", "London, United Kingdom",
                "Build and ship ML models with the team.");
        // Lever's response is a bare JSON array, not wrapped in {"jobs": [...]}.
        String jobsList = "[" + job + "]";
        LeverScraper scraper = new LeverScraper(fetcherFor(jobsList), List.of(TEST_CO));

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(1, count);

            try (ResultSet rs = conn.createStatement()
                    .executeQuery("SELECT source, title, company, location, raw_text, url FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("lever", rs.getString("source"));
                assertEquals("Machine Learning Engineer", rs.getString("title"));
                assertEquals("TestCo", rs.getString("company"));
                assertEquals("London, United Kingdom", rs.getString("location"));
                assertEquals("Build and ship ML models with the team.", rs.getString("raw_text"));
                assertEquals("https://jobs.lever.co/testco/abc123", rs.getString("url"));
            }
        }
    }

    @Test
    void runSkipsPostingsOutsideEuropeAndUS(@TempDir Path tmpDir) throws SQLException {
        String job = jobJson("Data Scientist", "IN", "Bangalore, India", "Analyze data at scale.");
        String jobsList = "[" + job + "]";
        LeverScraper scraper = new LeverScraper(fetcherFor(jobsList), List.of(TEST_CO));

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
                "AI Platform Engineer", "US", "Remote, United States",
                "We are hiring a Senior Staff engineer to lead this effort.");
        String tooMuchExperience = jobJson(
                "Applied Scientist", "FR", "Paris, France",
                "3-5 years experience in machine learning required.");
        String jobsList = "[" + seniorInDescription + "," + tooMuchExperience + "]";
        LeverScraper scraper = new LeverScraper(fetcherFor(jobsList), List.of(TEST_CO));

        try (Connection conn = freshDb(tmpDir)) {
            assertEquals(0, scraper.run(conn));
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS c FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("c"));
            }
        }
    }
}
