package com.jobscout.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobscout.db.VacancyRecord;
import com.jobscout.db.VacancyRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * StudentJob.nl scraper, focused on Data Science / AI / software-engineering internships.
 *
 * studentjob.nl's robots.txt disallows crawling the English-locale job path
 * (/vacancies*) but not the Dutch-locale path (/vacatures/), which is where actual
 * listings live -- so that's the path visited here. Candidate URLs are discovered
 * from the public sitemap and filtered by title before fetching, rather than using
 * the (disallowed) search pages.
 *
 * The page's own <h1 itemprop="title"> is used over the JSON-LD title -- on some
 * postings JSON-LD holds a generic occupational category instead of the real role
 * name, but the h1 was accurate on every sample checked.
 */
public class StudentJobScraper extends BaseScraper {
    private static final String SITEMAP_URL = "https://www.studentjob.nl/sitemap/job_openings.xml";
    private static final Pattern INTERNSHIP_PATTERN =
            Pattern.compile("\\b(stage|intern|internship)\\b", Pattern.CASE_INSENSITIVE);

    public StudentJobScraper(HttpFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "studentjob";
    }

    public List<String> fetchCandidateUrls() {
        String xml = fetcher.get(SITEMAP_URL);
        List<String> candidates = new ArrayList<>();
        for (String url : SitemapReader.readLocs(xml)) {
            if (INTERNSHIP_PATTERN.matcher(url).find()
                    && ScraperPatterns.RELEVANCE_PATTERN.matcher(url).find()) {
                candidates.add(url);
            }
        }
        return candidates;
    }

    public VacancyRecord toVacancy(String url, String title, JsonNode posting) {
        String rawText = JobPostingHtml.cleanDescription(posting);
        String company = posting.path("hiringOrganization").path("name").asText(null);
        JsonNode address = firstLocation(posting).path("address");
        String location = joinLocation(
                address.path("addressLocality").asText(null),
                address.path("addressCountry").asText(null));
        return new VacancyRecord(sourceName(), url, title, company, location, rawText);
    }

    public int run(Connection conn) {
        int count = 0;
        for (String url : fetchCandidateUrls()) {
            String html;
            try {
                html = fetcher.get(url);
            } catch (ScraperException exc) {
                System.out.println("Skipping " + url + ": " + exc.getMessage());
                continue;
            }

            JsonNode posting = JobPostingHtml.extractJobPosting(html);
            if (posting == null) {
                System.out.println("Skipping " + url + ": no JobPosting data found on page");
                continue;
            }

            String country = firstLocation(posting).path("address").path("addressCountry").asText(null);
            if (country != null && !country.equals("NL")) {
                System.out.println("Skipping " + url + ": not in the Netherlands (" + country + ")");
                continue;
            }

            String requiredLanguage = extractRequiredLanguage(html);
            if (requiredLanguage != null && requiredLanguage.toLowerCase().contains("nederlands")) {
                System.out.println("Skipping " + url + ": requires Dutch (" + requiredLanguage + ")");
                continue;
            }

            String title = extractTitle(html);
            if (title == null || title.isBlank()) {
                title = posting.path("title").asText(null);
            }
            if (title == null || title.isBlank()) {
                System.out.println("Skipping " + url + ": could not determine a title");
                continue;
            }

            VacancyRepository.upsertVacancy(conn, toVacancy(url, title, posting));
            count++;
        }
        return count;
    }

    private static JsonNode firstLocation(JsonNode posting) {
        JsonNode jobLocation = posting.path("jobLocation");
        if (jobLocation.isArray()) {
            return jobLocation.isEmpty() ? jobLocation.path(0) : jobLocation.get(0);
        }
        return jobLocation;
    }

    private static String joinLocation(String locality, String country) {
        List<String> parts = new ArrayList<>();
        if (locality != null && !locality.isBlank()) {
            parts.add(locality);
        }
        if (country != null && !country.isBlank()) {
            parts.add(country);
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    static String extractRequiredLanguage(String pageHtml) {
        Document doc = Jsoup.parse(pageHtml);
        Element tag = doc.selectFirst("meta[property=languageRequirements]");
        return tag == null ? null : tag.attr("content");
    }

    static String extractTitle(String pageHtml) {
        Document doc = Jsoup.parse(pageHtml);
        Element tag = doc.selectFirst("[itemprop=title]");
        return tag == null ? null : tag.text();
    }
}
