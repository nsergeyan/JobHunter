package com.jobscout.scraper.magnetme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobscout.db.SchemaInitializer;
import com.jobscout.db.VacancyRecord;
import com.jobscout.scraper.FakeHttpFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagnetMeScraperTest {

    private static final String SITEMAP_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://magnet.me/en/opportunity/1/data-engineer-intern</loc></url>
              <url><loc>https://magnet.me/en/opportunity/2/marketing-internship</loc></url>
              <url><loc>https://magnet.me/en/opportunity/3/data-scientist--full-time</loc></url>
              <url><loc>https://magnet.me/nl-NL/vacature/70731/account-support-manager-afh</loc></url>
            </urlset>
            """;

    private static final String JOB_POSTING_JSON = """
            {
              "@context": "https://schema.org/",
              "@type": "JobPosting",
              "title": "Data Engineer Intern",
              "description": "<p>Join our <strong>data</strong> team.</p>",
              "hiringOrganization": {"name": "Churned"},
              "jobLocation": {
                "address": {"addressLocality": "Amsterdam", "addressCountry": "NL"}
              }
            }
            """;

    private static String languageBox(String... languages) {
        String entries = java.util.Arrays.stream(languages)
                .map(lang -> "<div><span>" + lang + "</span><span> (Fluent)</span></div>")
                .collect(Collectors.joining());
        return """
                <div data-testid="languages">
                  <div>Required language</div>
                  <div>%s</div>
                </div>
                """.formatted(entries);
    }

    private static String jobPageHtml(String postingJson, String... languages) {
        return """
                <html><head></head><body>
                <script type="application/ld+json">%s</script>
                %s
                </body></html>
                """.formatted(postingJson, languageBox(languages));
    }

    private static final String JOB_PAGE_HTML = jobPageHtml(JOB_POSTING_JSON, "English");

    private static FakeHttpFetcher fetcherFor(String sitemapXml, Map<String, String> pagesBySuffix) {
        return new FakeHttpFetcher((url, body) -> {
            if (url.endsWith("en-opportunities.xml")) {
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
    void fetchCandidateUrlsFiltersToRelevantTitles() {
        MagnetMeScraper scraper = new MagnetMeScraper(fetcherFor(SITEMAP_XML, Map.of()));

        List<String> urls = scraper.fetchCandidateUrls();

        // "marketing-internship" isn't DS/AI/software-relevant, and the excluded
        // NL-vacature URL is defensively skipped regardless of title -- but
        // "data-scientist--full-time" now passes since scope covers junior/graduate
        // full-time roles too, not just internships.
        assertEquals(List.of(
                "https://magnet.me/en/opportunity/1/data-engineer-intern",
                "https://magnet.me/en/opportunity/3/data-scientist--full-time"), urls);
    }

    @Test
    void runStoresMatchedPosting(@TempDir Path tmpDir) throws SQLException {
        MagnetMeScraper scraper = new MagnetMeScraper(
                fetcherFor(SITEMAP_XML, Map.of(
                        "/1/data-engineer-intern", JOB_PAGE_HTML,
                        "/3/data-scientist--full-time", JOB_PAGE_HTML)));

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(2, count);

            try (ResultSet rs = conn.createStatement()
                    .executeQuery("SELECT title, company, location, raw_text, external_id FROM vacancies LIMIT 1")) {
                assertTrue(rs.next());
                assertEquals("Data Engineer Intern", rs.getString("title"));
                assertEquals("Churned", rs.getString("company"));
                assertEquals("Amsterdam, NL", rs.getString("location"));
                assertEquals("Join our \ndata\n team.", rs.getString("raw_text"));
                // identity is the opportunity id, not the whole url
                assertEquals("1", rs.getString("external_id"));
            }
        }
    }

    @Test
    void toVacancyMapsJsonLdFields() throws Exception {
        MagnetMeScraper scraper = new MagnetMeScraper(fetcherFor(SITEMAP_XML, Map.of()));
        JsonNode posting = new ObjectMapper().readTree(JOB_POSTING_JSON);

        VacancyRecord vacancy = scraper.toVacancy("https://magnet.me/en/opportunity/1/data-engineer-intern", posting);

        assertEquals("Data Engineer Intern", vacancy.title());
        assertEquals("Churned", vacancy.company());
        assertEquals("Amsterdam, NL", vacancy.location());
        assertEquals("Join our \ndata\n team.", vacancy.rawText());
    }

    @Test
    void extractRequiredLanguagesReadsTheStructuredBox() {
        String dutchJobPageHtml = jobPageHtml(JOB_POSTING_JSON, "English", "Dutch");

        assertEquals(List.of("English"), MagnetMeScraper.extractRequiredLanguages(JOB_PAGE_HTML));
        assertEquals(List.of("English", "Dutch"), MagnetMeScraper.extractRequiredLanguages(dutchJobPageHtml));
    }

    @Test
    void runSkipsPostingsThatRequireDutch(@TempDir Path tmpDir) throws SQLException {
        String dutchPostingJson = """
                {
                  "@context": "https://schema.org/",
                  "@type": "JobPosting",
                  "title": "Data Analyst Intern",
                  "description": "<p>You analyze data. Dutch and English are both spoken in the office.</p>",
                  "hiringOrganization": {"name": "Acme NL"},
                  "jobLocation": {"address": {"addressLocality": "Utrecht", "addressCountry": "NL"}}
                }
                """;
        // The posting's own text is in English, but its structured "Required language"
        // box (the field magnet.me actually surfaces) lists Dutch -- that's the
        // authoritative signal filtered on, not text-guessing.
        String dutchJobPageHtml = jobPageHtml(dutchPostingJson, "English", "Dutch");

        String sitemapWithDutch = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://magnet.me/en/opportunity/1/data-engineer-intern</loc></url>
                  <url><loc>https://magnet.me/en/opportunity/4/data-analyst-intern</loc></url>
                </urlset>
                """;

        MagnetMeScraper scraper = new MagnetMeScraper(fetcherFor(sitemapWithDutch, Map.of(
                "/1/data-engineer-intern", JOB_PAGE_HTML,
                "/4/data-analyst-intern", dutchJobPageHtml)));

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(1, count);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT title FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("Data Engineer Intern", rs.getString("title"));
            }
        }
    }

    @Test
    void runDoesNotSkipBasedOnDescriptionLanguageAlone(@TempDir Path tmpDir) throws SQLException {
        // Description text is in Dutch and there's no "Required language" box at all --
        // only the actual language *requirement* matters, not what language the ad
        // happens to be written in, so this should NOT be skipped.
        String dutchLanguagePostingJson = """
                {
                  "@context": "https://schema.org/",
                  "@type": "JobPosting",
                  "title": "Stage Machine Learning",
                  "description": "<p>Wij zoeken een gemotiveerde stagiair(e) die ons team komt versterken met machine learning modellen.</p>",
                  "hiringOrganization": {"name": "Voorbeeld BV"},
                  "jobLocation": {"address": {"addressLocality": "Rotterdam", "addressCountry": "NL"}}
                }
                """;
        String dutchLanguageJobPageHtml = """
                <html><head></head><body>
                <script type="application/ld+json">%s</script>
                </body></html>
                """.formatted(dutchLanguagePostingJson);

        String sitemapWithDutchLanguage = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://magnet.me/en/opportunity/1/data-engineer-intern</loc></url>
                  <url><loc>https://magnet.me/en/opportunity/5/machine-learning-intern</loc></url>
                </urlset>
                """;

        MagnetMeScraper scraper = new MagnetMeScraper(fetcherFor(sitemapWithDutchLanguage, Map.of(
                "/1/data-engineer-intern", JOB_PAGE_HTML,
                "/5/machine-learning-intern", dutchLanguageJobPageHtml)));

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(2, count);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT title FROM vacancies")) {
                Set<String> titles = new java.util.HashSet<>();
                while (rs.next()) {
                    titles.add(rs.getString("title"));
                }
                assertEquals(Set.of("Data Engineer Intern", "Stage Machine Learning"), titles);
            }
        }
    }

    @Test
    void runSkipsPostingsOutsideEurope(@TempDir Path tmpDir) throws SQLException {
        // Scope is Europe-wide now (not NL-only), so a German posting should be kept --
        // only genuinely out-of-scope countries (e.g. US) get filtered.
        String usPostingJson = """
                {
                  "@context": "https://schema.org/",
                  "@type": "JobPosting",
                  "title": "Data Analyst Intern - New York",
                  "description": "<p>Join our data team in New York.</p>",
                  "hiringOrganization": {"name": "Acme US"},
                  "jobLocation": {"address": {"addressLocality": "New York", "addressCountry": "US"}}
                }
                """;
        String usJobPageHtml = jobPageHtml(usPostingJson, "English");

        String sitemapWithUs = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://magnet.me/en/opportunity/1/data-engineer-intern</loc></url>
                  <url><loc>https://magnet.me/en/opportunity/7/data-analyst-intern-nyc</loc></url>
                </urlset>
                """;

        MagnetMeScraper scraper = new MagnetMeScraper(fetcherFor(sitemapWithUs, Map.of(
                "/1/data-engineer-intern", JOB_PAGE_HTML,
                "/7/data-analyst-intern-nyc", usJobPageHtml)));

        try (Connection conn = freshDb(tmpDir)) {
            int count = scraper.run(conn);
            assertEquals(1, count);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT title FROM vacancies")) {
                assertTrue(rs.next());
                assertEquals("Data Engineer Intern", rs.getString("title"));
            }
        }
    }

    @Test
    void runSkipsAlreadySeenPostingsOnRerun(@TempDir Path tmpDir) throws SQLException {
        MagnetMeScraper scraper = new MagnetMeScraper(
                fetcherFor(SITEMAP_XML, Map.of(
                        "/1/data-engineer-intern", JOB_PAGE_HTML,
                        "/3/data-scientist--full-time", JOB_PAGE_HTML)));

        try (Connection conn = freshDb(tmpDir)) {
            int firstRun = scraper.run(conn);
            assertEquals(2, firstRun);

            // A second scraper instance whose fetcher throws on the job pages -- if the
            // rerun tried to re-fetch them instead of consulting seen_postings, it'd fail.
            MagnetMeScraper rerunScraper = new MagnetMeScraper(fetcherFor(SITEMAP_XML, Map.of()));
            int secondRun = rerunScraper.run(conn);
            assertEquals(0, secondRun);
        }
    }
}
