package com.jobscout.scraper.smartrecruiters;

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

class SmartRecruitersScraperTest {

    private static final SmartRecruitersCompany TEST_CO = new SmartRecruitersCompany("TestCo", "testco");
    private static final String LIST_URL_PREFIX = "https://api.smartrecruiters.com/v1/companies/testco/postings?offset=";

    // totalFound=3, pageSize=2 -> two pages, exercising pagination. One posting
    // is pre-excluded by experienceLevel alone (mid_senior_level), without
    // needing a detail fetch.
    private static final String PAGE_1_JSON = """
            {"totalFound":3,"content":[
              {"name":"Software Engineer II","id":"1","experienceLevel":{"id":"entry_level","label":"Entry Level"}},
              {"name":"Senior Data Engineer","id":"2","experienceLevel":{"id":"mid_senior_level","label":"Mid-Senior Level"}}
            ]}
            """;
    private static final String PAGE_2_JSON = """
            {"totalFound":3,"content":[
              {"name":"Data Scientist Intern","id":"3","experienceLevel":{"id":"internship","label":"Internship"}}
            ]}
            """;

    private static String detailJson(String name, String countryCode, String fullLocation, String descriptionHtml) {
        return """
                {
                  "name": "%s",
                  "postingUrl": "https://jobs.smartrecruiters.com/TestCo/%s",
                  "location": {"country": "%s", "fullLocation": "%s"},
                  "jobAd": {"sections": {"jobDescription": {"text": "%s"}}}
                }
                """.formatted(name, name.replace(" ", "-"), countryCode, fullLocation, descriptionHtml);
    }

    private static FakeHttpFetcher fetcherFor(java.util.function.BiFunction<String, String, String> handler) {
        return new FakeHttpFetcher(handler::apply);
    }

    private static Connection freshDb(Path tmpDir) throws SQLException {
        String dbPath = tmpDir.resolve("test.db").toString();
        SchemaInitializer.initDb(dbPath);
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    @Test
    void fetchCandidateJobsPaginatesAndExcludesByExperienceLevel() {
        SmartRecruitersScraper scraper = new SmartRecruitersScraper(fetcherFor((url, body) -> {
            if (url.equals(LIST_URL_PREFIX + "0&limit=2")) {
                return PAGE_1_JSON;
            }
            if (url.equals(LIST_URL_PREFIX + "2&limit=2")) {
                return PAGE_2_JSON;
            }
            throw new AssertionError("Unexpected request: " + url);
        }), List.of(TEST_CO), 2);

        List<SmartRecruitersScraper.JobListing> candidates = scraper.fetchCandidateJobs(TEST_CO);

        assertEquals(
                List.of(
                        new SmartRecruitersScraper.JobListing("Software Engineer II", "1"),
                        new SmartRecruitersScraper.JobListing("Data Scientist Intern", "3")),
                candidates);
    }

    @Test
    void runFetchesDetailAndStoresJuniorEuropeVacancy(@TempDir Path tmpDir) throws SQLException {
        String detailUrl = "https://api.smartrecruiters.com/v1/companies/testco/postings/1";
        String detail = detailJson(
                "Software Engineer II", "de", "Berlin, Germany",
                "<p>Build things with us.</p>");
        SmartRecruitersScraper scraper = new SmartRecruitersScraper(fetcherFor((url, body) -> {
            if (url.equals(LIST_URL_PREFIX + "0&limit=2")) {
                return PAGE_1_JSON;
            }
            if (url.equals(LIST_URL_PREFIX + "2&limit=2")) {
                return "{\"totalFound\":3,\"content\":[]}";
            }
            if (url.equals(detailUrl)) {
                return detail;
            }
            throw new AssertionError("Unexpected request: " + url);
        }), List.of(TEST_CO), 2);

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(1, count);

            try (ResultSet rs = conn.createStatement()
                    .executeQuery("SELECT source, title, company, location, raw_text, url FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("smartrecruiters", rs.getString("source"));
                assertEquals("Software Engineer II", rs.getString("title"));
                assertEquals("TestCo", rs.getString("company"));
                assertEquals("Berlin, Germany", rs.getString("location"));
                assertEquals("Build things with us.", rs.getString("raw_text"));
                assertEquals("https://jobs.smartrecruiters.com/TestCo/Software-Engineer-II", rs.getString("url"));
            }
        }
    }

    @Test
    void runSkipsPostingsOutsideEurope(@TempDir Path tmpDir) throws SQLException {
        String detailUrl = "https://api.smartrecruiters.com/v1/companies/testco/postings/1";
        String detail = detailJson(
                "Software Engineer II", "in", "Bangalore, India",
                "<p>Build things with us.</p>");
        SmartRecruitersScraper scraper = new SmartRecruitersScraper(fetcherFor((url, body) -> {
            if (url.equals(LIST_URL_PREFIX + "0&limit=2")) {
                return PAGE_1_JSON;
            }
            if (url.equals(LIST_URL_PREFIX + "2&limit=2")) {
                return "{\"totalFound\":3,\"content\":[]}";
            }
            if (url.equals(detailUrl)) {
                return detail;
            }
            throw new AssertionError("Unexpected request: " + url);
        }), List.of(TEST_CO), 2);

        try (Connection conn = freshDb(tmpDir)) {
            assertEquals(0, scraper.run(conn));
        }
    }
}
