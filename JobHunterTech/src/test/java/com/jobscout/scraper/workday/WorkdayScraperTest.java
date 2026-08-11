package com.jobscout.scraper.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class WorkdayScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final WorkdayCompany TEST_CO =
            new WorkdayCompany("TestCo", "testco.wd1.myworkdayjobs.com", "testco", "testco");

    private static final String LIST_URL = "https://testco.wd1.myworkdayjobs.com/wday/cxs/testco/testco/jobs";

    // total=3, pageSize=2 -> two pages, exercising pagination.
    private static final String PAGE_1_JSON = """
            {"total":3,"jobPostings":[
              {"title":"Software Engineer II","externalPath":"/job/A/Software-Engineer-II_R1"},
              {"title":"Sales Manager","externalPath":"/job/B/Sales-Manager_R2"}
            ]}
            """;
    private static final String PAGE_2_JSON = """
            {"total":3,"jobPostings":[
              {"title":"Data Scientist","externalPath":"/job/C/Data-Scientist_R3"}
            ]}
            """;

    private static final String EUROPE_DETAIL_JSON = """
            {
              "jobPostingInfo": {
                "title": "Software Engineer II",
                "jobDescription": "<p>Build <strong>things</strong> with us.</p>",
                "location": "Krakow, Poland",
                "country": {"descriptor": "Poland"},
                "externalUrl": "https://testco.wd1.myworkdayjobs.com/testco/job/A/Software-Engineer-II_R1"
              },
              "hiringOrganization": {"name": "TestCo"}
            }
            """;

    private static final String OUT_OF_REGION_DETAIL_JSON = """
            {
              "jobPostingInfo": {
                "title": "Software Engineer II",
                "jobDescription": "<p>Build things with us.</p>",
                "location": "Pune, India",
                "country": {"descriptor": "India"},
                "externalUrl": "https://testco.wd1.myworkdayjobs.com/testco/job/A/Software-Engineer-II_R1"
              },
              "hiringOrganization": {"name": "TestCo"}
            }
            """;

    // Real-world case: title has no seniority signal, but the description opens
    // with one -- matches the actual Zendesk "AI Agent Abuse Prevention Engineer"
    // posting that slipped through the title-only filter.
    private static final String SENIOR_IN_DESCRIPTION_DETAIL_JSON = """
            {
              "jobPostingInfo": {
                "title": "AI Agent Abuse Prevention Engineer",
                "jobDescription": "<p>We are hiring a Senior Staff-level technical leader to own this area.</p>",
                "location": "Remote, Texas, United States of America",
                "country": {"descriptor": "United States of America"},
                "externalUrl": "https://testco.wd1.myworkdayjobs.com/testco/job/A/AI-Agent-Abuse-Prevention-Engineer_R1"
              },
              "hiringOrganization": {"name": "TestCo"}
            }
            """;

    // Real-world case: no "senior" anywhere, but asks for 3-5 years experience --
    // matches the actual Zendesk "Applied ML Scientist" posting in Lisbon.
    private static final String TOO_MUCH_EXPERIENCE_DETAIL_JSON = """
            {
              "jobPostingInfo": {
                "title": "Applied ML Scientist",
                "jobDescription": "<p>3-5 years' experience in applied machine learning or a related field.</p>",
                "location": "Lisbon, Portugal",
                "country": {"descriptor": "Portugal"},
                "externalUrl": "https://testco.wd1.myworkdayjobs.com/testco/job/A/Applied-ML-Scientist_R1"
              },
              "hiringOrganization": {"name": "TestCo"}
            }
            """;

    private static FakeHttpFetcher fetcherFor(java.util.function.BiFunction<String, String, String> handler) {
        return new FakeHttpFetcher(handler::apply);
    }

    private static Connection freshDb(Path tmpDir) throws SQLException {
        String dbPath = tmpDir.resolve("test.db").toString();
        SchemaInitializer.initDb(dbPath);
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    @Test
    void fetchCandidateJobsPaginatesAndFiltersByRelevance() {
        WorkdayScraper scraper = new WorkdayScraper(fetcherFor((url, body) -> {
            if (!url.equals(LIST_URL)) {
                throw new AssertionError("Unexpected request: " + url);
            }
            if (body.contains("\"offset\":0")) {
                return PAGE_1_JSON;
            }
            if (body.contains("\"offset\":2")) {
                return PAGE_2_JSON;
            }
            throw new AssertionError("Unexpected request body: " + body);
        }), List.of(TEST_CO), 2);

        List<WorkdayScraper.JobListing> candidates = scraper.fetchCandidateJobs(TEST_CO);

        assertEquals(
                List.of(
                        new WorkdayScraper.JobListing("Software Engineer II", "/job/A/Software-Engineer-II_R1"),
                        new WorkdayScraper.JobListing("Data Scientist", "/job/C/Data-Scientist_R3")),
                candidates);
    }

    // Real Workday behaviour: only the first page (offset 0) reports a truthful
    // "total"; every later page returns "total":0 while still serving results. A loop
    // that trusts the per-page total stops after page 2 and silently drops the rest
    // (e.g. 40 of NVIDIA's 2000 jobs). Here total=6 with pageSize=2 -> the first page
    // says 6, pages 2 and 3 lie with 0, and the scraper must still fetch all three.
    @Test
    void fetchCandidateJobsKeepsPagingWhenLaterPagesReportZeroTotal() {
        String pageZeroTotal6 = """
                {"total":6,"jobPostings":[
                  {"title":"Software Engineer","externalPath":"/job/A/Software-Engineer_R1"},
                  {"title":"Data Scientist","externalPath":"/job/B/Data-Scientist_R2"}
                ]}
                """;
        String pageTwoTotal0 = """
                {"total":0,"jobPostings":[
                  {"title":"ML Engineer","externalPath":"/job/C/ML-Engineer_R3"},
                  {"title":"Backend Engineer","externalPath":"/job/D/Backend-Engineer_R4"}
                ]}
                """;
        String pageThreeTotal0 = """
                {"total":0,"jobPostings":[
                  {"title":"Backend Developer","externalPath":"/job/E/Backend-Developer_R5"},
                  {"title":"Full Stack Engineer","externalPath":"/job/F/Full-Stack-Engineer_R6"}
                ]}
                """;
        WorkdayScraper scraper = new WorkdayScraper(fetcherFor((url, body) -> {
            if (!url.equals(LIST_URL)) {
                throw new AssertionError("Unexpected request: " + url);
            }
            if (body.contains("\"offset\":0")) {
                return pageZeroTotal6;
            }
            if (body.contains("\"offset\":2")) {
                return pageTwoTotal0;
            }
            if (body.contains("\"offset\":4")) {
                return pageThreeTotal0;
            }
            throw new AssertionError("Unexpected request body: " + body);
        }), List.of(TEST_CO), 2);

        List<WorkdayScraper.JobListing> candidates = scraper.fetchCandidateJobs(TEST_CO);

        // All 6 postings across 3 pages, not just the first 2 pages.
        assertEquals(6, candidates.size());
    }

    @Test
    void isInTargetRegionChecksCountryDescriptorAndLocationString() throws Exception {
        JsonNode germanyByCountry = MAPPER.readTree(
                "{\"jobPostingInfo\": {\"country\": {\"descriptor\": \"Germany\"}, \"location\": \"Berlin\"}}");
        assertTrue(WorkdayScraper.isInTargetRegion(germanyByCountry));

        // Europe-only now -- a US posting must NOT pass, even with a full "United
        // States" location string.
        JsonNode usByLocationString = MAPPER.readTree(
                "{\"jobPostingInfo\": {\"location\": \"Austin, Texas, United States of America\"}}");
        assertFalse(WorkdayScraper.isInTargetRegion(usByLocationString));

        JsonNode india = MAPPER.readTree(
                "{\"jobPostingInfo\": {\"country\": {\"descriptor\": \"India\"}, \"location\": \"Pune, India\"}}");
        assertFalse(WorkdayScraper.isInTargetRegion(india));
    }

    @Test
    void runFetchesDetailAndStoresEuropeVacancy(@TempDir Path tmpDir) throws SQLException {
        String detailUrl = "https://testco.wd1.myworkdayjobs.com/wday/cxs/testco/testco/job/A/Software-Engineer-II_R1";
        WorkdayScraper scraper = new WorkdayScraper(fetcherFor((url, body) -> {
            if (url.equals(LIST_URL)) {
                return body.contains("\"offset\":0") ? PAGE_1_JSON : "{\"total\":3,\"jobPostings\":[]}";
            }
            if (url.equals(detailUrl)) {
                return EUROPE_DETAIL_JSON;
            }
            throw new AssertionError("Unexpected request: " + url);
        }), List.of(TEST_CO), 2);

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(1, count);

            try (ResultSet rs = conn.createStatement()
                    .executeQuery("SELECT source, title, company, location, raw_text, url, external_id FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("workday", rs.getString("source"));
                assertEquals("Software Engineer II", rs.getString("title"));
                assertEquals("TestCo", rs.getString("company"));
                assertEquals("Krakow, Poland", rs.getString("location"));
                assertEquals("Build \nthings\n with us.", rs.getString("raw_text"));
                assertEquals("https://testco.wd1.myworkdayjobs.com/testco/job/A/Software-Engineer-II_R1", rs.getString("url"));
                // identity is the stable req id tail, not the whole path
                assertEquals("R1", rs.getString("external_id"));
            }
        }
    }

    @Test
    void runSkipsPostingsWhereOnlyTheDescriptionRevealsSeniority(@TempDir Path tmpDir) throws SQLException {
        String detailUrl = "https://testco.wd1.myworkdayjobs.com/wday/cxs/testco/testco/job/A/AI-Agent-Abuse-Prevention-Engineer_R1";
        String listPage = """
                {"total":1,"jobPostings":[
                  {"title":"AI Agent Abuse Prevention Engineer","externalPath":"/job/A/AI-Agent-Abuse-Prevention-Engineer_R1"}
                ]}
                """;
        WorkdayScraper scraper = new WorkdayScraper(fetcherFor((url, body) -> {
            if (url.equals(LIST_URL)) {
                return listPage;
            }
            if (url.equals(detailUrl)) {
                return SENIOR_IN_DESCRIPTION_DETAIL_JSON;
            }
            throw new AssertionError("Unexpected request: " + url);
        }), List.of(TEST_CO), 20);

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(0, count);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS c FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("c"));
            }
        }
    }

    @Test
    void runSkipsPostingsRequiringMoreThanTwoYearsExperience(@TempDir Path tmpDir) throws SQLException {
        String detailUrl = "https://testco.wd1.myworkdayjobs.com/wday/cxs/testco/testco/job/A/Applied-ML-Scientist_R1";
        String listPage = """
                {"total":1,"jobPostings":[
                  {"title":"Applied ML Scientist","externalPath":"/job/A/Applied-ML-Scientist_R1"}
                ]}
                """;
        WorkdayScraper scraper = new WorkdayScraper(fetcherFor((url, body) -> {
            if (url.equals(LIST_URL)) {
                return listPage;
            }
            if (url.equals(detailUrl)) {
                return TOO_MUCH_EXPERIENCE_DETAIL_JSON;
            }
            throw new AssertionError("Unexpected request: " + url);
        }), List.of(TEST_CO), 20);

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(0, count);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS c FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("c"));
            }
        }
    }

    @Test
    void runSkipsPostingsOutsideEurope(@TempDir Path tmpDir) throws SQLException {
        String detailUrl = "https://testco.wd1.myworkdayjobs.com/wday/cxs/testco/testco/job/A/Software-Engineer-II_R1";
        WorkdayScraper scraper = new WorkdayScraper(fetcherFor((url, body) -> {
            if (url.equals(LIST_URL)) {
                return body.contains("\"offset\":0") ? PAGE_1_JSON : "{\"total\":3,\"jobPostings\":[]}";
            }
            if (url.equals(detailUrl)) {
                return OUT_OF_REGION_DETAIL_JSON;
            }
            throw new AssertionError("Unexpected request: " + url);
        }), List.of(TEST_CO), 2);

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(0, count);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS c FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("c"));
            }
        }
    }
}
