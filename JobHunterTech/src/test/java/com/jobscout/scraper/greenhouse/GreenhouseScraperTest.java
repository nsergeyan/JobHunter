package com.jobscout.scraper.greenhouse;

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

class GreenhouseScraperTest {

    private static final GreenhouseCompany TEST_CO = new GreenhouseCompany("TestCo", "testco");
    private static final String JOBS_URL = "https://boards-api.greenhouse.io/v1/boards/testco/jobs?content=true";

    // Greenhouse's real API returns "content" HTML-entity-double-encoded -- the
    // JSON string value is literally "&lt;p&gt;...&lt;/p&gt;", not "<p>...</p>".
    private static String jobJson(String title, String locationName, String contentHtml) {
        String encodedContent = contentHtml.replace("<", "&lt;").replace(">", "&gt;");
        return """
                {
                  "title": "%s",
                  "absolute_url": "https://job-boards.greenhouse.io/testco/jobs/1",
                  "location": {"name": "%s"},
                  "company_name": "TestCo",
                  "content": "%s"
                }
                """.formatted(title, locationName, encodedContent);
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
    void runDecodesEntityEncodedContentAndStoresJuniorEuropeVacancy(@TempDir Path tmpDir) throws SQLException {
        String job = jobJson(
                "Machine Learning Engineer",
                "Berlin, Germany",
                "<p>Build <strong>models</strong> with us.</p>");
        String jobsList = "{\"jobs\":[" + job + "]}";
        GreenhouseScraper scraper = new GreenhouseScraper(fetcherFor(jobsList), List.of(TEST_CO));

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(1, count);

            try (ResultSet rs = conn.createStatement()
                    .executeQuery("SELECT source, title, company, location, raw_text, url FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("greenhouse", rs.getString("source"));
                assertEquals("Machine Learning Engineer", rs.getString("title"));
                assertEquals("TestCo", rs.getString("company"));
                assertEquals("Berlin, Germany", rs.getString("location"));
                assertEquals("Build \nmodels\n with us.", rs.getString("raw_text"));
                assertEquals("https://job-boards.greenhouse.io/testco/jobs/1", rs.getString("url"));
            }
        }
    }

    @Test
    void runSkipsPostingsOutsideEurope(@TempDir Path tmpDir) throws SQLException {
        String job = jobJson("Data Scientist", "Bangalore, India", "<p>Analyze data.</p>");
        String jobsList = "{\"jobs\":[" + job + "]}";
        GreenhouseScraper scraper = new GreenhouseScraper(fetcherFor(jobsList), List.of(TEST_CO));

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
                "AI Platform Engineer", "Remote, United States",
                "<p>We need a Senior Staff engineer to lead this effort.</p>");
        String tooMuchExperience = jobJson(
                "Applied Scientist", "Paris, France",
                "<p>3-5 years' experience in machine learning required.</p>");
        String jobsList = "{\"jobs\":[" + seniorInDescription + "," + tooMuchExperience + "]}";
        GreenhouseScraper scraper = new GreenhouseScraper(fetcherFor(jobsList), List.of(TEST_CO));

        try (Connection conn = freshDb(tmpDir)) {
            assertEquals(0, scraper.run(conn));
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS c FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("c"));
            }
        }
    }
}
