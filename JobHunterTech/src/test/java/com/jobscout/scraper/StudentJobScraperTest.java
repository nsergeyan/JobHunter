package com.jobscout.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobscout.db.SchemaInitializer;
import com.jobscout.db.VacancyRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentJobScraperTest {

    private static final String SITEMAP_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://www.studentjob.nl/vacatures/1-machine-learning-engineer-intern</loc></url>
              <url><loc>https://www.studentjob.nl/vacatures/2-weekend-supermarket-job</loc></url>
              <url><loc>https://www.studentjob.nl/vacatures/3-data-scientist-fulltime</loc></url>
            </urlset>
            """;

    private static final String DUTCH_SITEMAP_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://www.studentjob.nl/vacatures/1-machine-learning-engineer-intern</loc></url>
              <url><loc>https://www.studentjob.nl/vacatures/4-data-analyst-intern</loc></url>
            </urlset>
            """;

    private static final String FOREIGN_SITEMAP_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://www.studentjob.nl/vacatures/1-machine-learning-engineer-intern</loc></url>
              <url><loc>https://www.studentjob.nl/vacatures/5-data-analyst-intern-berlin</loc></url>
            </urlset>
            """;

    private static String jobPostingJson(String title) {
        return """
                {
                  "@context": "http://schema.org",
                  "@type": "JobPosting",
                  "title": "%s",
                  "description": "<p>Join our <strong>ML</strong> team.</p>",
                  "employmentType": ["FULL_TIME"],
                  "hiringOrganization": {"name": "Acme"},
                  "jobLocation": [
                    {"address": {"addressLocality": "Amsterdam", "addressCountry": "NL"}}
                  ]
                }
                """.formatted(title);
    }

    private static final String JOB_POSTING_JSON = jobPostingJson("Machine Learning Engineer Intern");

    private static String dutchJobPostingJson() {
        return """
                {
                  "@context": "http://schema.org",
                  "@type": "JobPosting",
                  "title": "Data Analyst Intern",
                  "description": "<p>You analyze data.</p>",
                  "employmentType": ["FULL_TIME"],
                  "hiringOrganization": {"name": "Acme NL"},
                  "jobLocation": [
                    {"address": {"addressLocality": "Utrecht", "addressCountry": "NL"}}
                  ]
                }
                """;
    }

    private static String foreignJobPostingJson() {
        return """
                {
                  "@context": "http://schema.org",
                  "@type": "JobPosting",
                  "title": "Data Analyst Intern - Berlin",
                  "description": "<p>Join our data team in Berlin.</p>",
                  "employmentType": ["FULL_TIME"],
                  "hiringOrganization": {"name": "Acme DE"},
                  "jobLocation": [
                    {"address": {"addressLocality": "Berlin", "addressCountry": "DE"}}
                  ]
                }
                """;
    }

    private static String pageHtml(String postingJson, String language, String title) {
        return """
                <html><head>
                <meta property="languageRequirements" content="%s" />
                </head><body>
                <h1 itemprop="title">%s</h1>
                <script type="application/ld+json">%s</script>
                </body></html>
                """.formatted(language, title, postingJson);
    }

    private static FakeHttpFetcher fetcherFor(String sitemapXml, Map<String, String> pagesBySuffix) {
        return new FakeHttpFetcher((url, body) -> {
            if (url.endsWith("job_openings.xml")) {
                return sitemapXml;
            }
            for (var entry : pagesBySuffix.entrySet()) {
                if (url.endsWith(entry.getKey())) {
                    return entry.getValue();
                }
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
    void fetchCandidateUrlsFiltersToRelevantInternships() {
        StudentJobScraper scraper = new StudentJobScraper(fetcherFor(SITEMAP_XML, Map.of()));

        List<String> urls = scraper.fetchCandidateUrls();

        assertEquals(List.of("https://www.studentjob.nl/vacatures/1-machine-learning-engineer-intern"), urls);
    }

    @Test
    void runStoresMatchedPosting(@TempDir Path tmpDir) throws SQLException {
        String jobPage = pageHtml(JOB_POSTING_JSON, "Engels", "Machine Learning Engineer Intern");
        StudentJobScraper scraper = new StudentJobScraper(
                fetcherFor(SITEMAP_XML, Map.of("/1-machine-learning-engineer-intern", jobPage)));

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(1, count);

            try (ResultSet rs = conn.createStatement()
                    .executeQuery("SELECT title, company, location, raw_text FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("Machine Learning Engineer Intern", rs.getString("title"));
                assertEquals("Acme", rs.getString("company"));
                assertEquals("Amsterdam, NL", rs.getString("location"));
                assertEquals("Join our \nML\n team.", rs.getString("raw_text"));
            }
        }
    }

    @Test
    void toVacancyMapsJsonLdFields() throws Exception {
        StudentJobScraper scraper = new StudentJobScraper(fetcherFor(SITEMAP_XML, Map.of()));
        JsonNode posting = new ObjectMapper().readTree(JOB_POSTING_JSON);

        VacancyRecord vacancy = scraper.toVacancy(
                "https://www.studentjob.nl/vacatures/1-machine-learning-engineer-intern",
                "Machine Learning Engineer Intern",
                posting);

        assertEquals("Machine Learning Engineer Intern", vacancy.title());
        assertEquals("Acme", vacancy.company());
        assertEquals("Amsterdam, NL", vacancy.location());
        assertEquals("Join our \nML\n team.", vacancy.rawText());
    }

    @Test
    void extractRequiredLanguageReadsTheMetaTag() {
        String html = pageHtml(JOB_POSTING_JSON, "Engels", "Machine Learning Engineer Intern");
        assertEquals("Engels", StudentJobScraper.extractRequiredLanguage(html));

        String dutchHtml = pageHtml(JOB_POSTING_JSON, "Nederlands", "Machine Learning Engineer Intern");
        assertEquals("Nederlands", StudentJobScraper.extractRequiredLanguage(dutchHtml));
    }

    @Test
    void runUsesH1TitleNotJsonLdTitleWhenTheyDiffer(@TempDir Path tmpDir) throws SQLException {
        String mismatchedPostingJson = jobPostingJson("ICT / IT / Programmeur");
        String jobPage = pageHtml(mismatchedPostingJson, "Engels", "Machine Learning Engineer Intern");
        StudentJobScraper scraper = new StudentJobScraper(
                fetcherFor(SITEMAP_XML, Map.of("/1-machine-learning-engineer-intern", jobPage)));

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(1, count);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT title FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("Machine Learning Engineer Intern", rs.getString("title"));
            }
        }
    }

    @Test
    void runSkipsPostingsThatRequireDutch(@TempDir Path tmpDir) throws SQLException {
        String englishJobPage = pageHtml(JOB_POSTING_JSON, "Engels", "Machine Learning Engineer Intern");
        String dutchJobPage = pageHtml(dutchJobPostingJson(), "Nederlands", "Data Analyst Intern");
        StudentJobScraper scraper = new StudentJobScraper(fetcherFor(DUTCH_SITEMAP_XML, Map.of(
                "/1-machine-learning-engineer-intern", englishJobPage,
                "/4-data-analyst-intern", dutchJobPage)));

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(1, count);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT title FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("Machine Learning Engineer Intern", rs.getString("title"));
            }
        }
    }

    @Test
    void runSkipsPostingsOutsideTheNetherlands(@TempDir Path tmpDir) throws SQLException {
        String englishJobPage = pageHtml(JOB_POSTING_JSON, "Engels", "Machine Learning Engineer Intern");
        String foreignJobPage = pageHtml(foreignJobPostingJson(), "Engels", "Data Analyst Intern - Berlin");
        StudentJobScraper scraper = new StudentJobScraper(fetcherFor(FOREIGN_SITEMAP_XML, Map.of(
                "/1-machine-learning-engineer-intern", englishJobPage,
                "/5-data-analyst-intern-berlin", foreignJobPage)));

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(1, count);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT title FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("Machine Learning Engineer Intern", rs.getString("title"));
            }
        }
    }
}
