package com.jobscout.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobscout.db.VacancyRecord;
import com.jobscout.db.VacancyRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * Magnet.me scraper, focused on Data Science / AI internships in the Netherlands.
 *
 * Magnet.me's robots.txt disallows crawling their search/listing pages (any URL
 * containing query= or country=), so instead of searching directly this: (1)
 * downloads their public sitemap of opportunity URLs, (2) filters the URL list for
 * internship + DS/AI-relevant titles, (3) visits only those specific opportunity
 * pages (plain, non-query URLs -- allowed).
 *
 * Each opportunity page embeds a schema.org JobPosting as JSON-LD.
 */
public class MagnetMeScraper extends BaseScraper {
    private static final String SITEMAP_URL = "https://magnet.me/sitemaps/en-opportunities.xml";
    private static final Pattern INTERNSHIP_PATTERN =
            Pattern.compile("\\b(intern|internship)\\b", Pattern.CASE_INSENSITIVE);

    // Specific postings magnet.me's robots.txt calls out as crawled too often --
    // excluded defensively even though they're unlikely to match the filters above.
    private static final List<String> EXCLUDED_URL_SUFFIXES = List.of(
            "/nl-NL/vacature/70731/account-support-manager-afh",
            "/nl-NL/vacature/71574/one-finance-junior-consultant---accenture-consulting",
            "/en-GB/opportunity/64360/junior-android-developer---mobgen-accenture-interactive");

    public MagnetMeScraper(HttpFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "magnetme";
    }

    public List<String> fetchCandidateUrls() {
        String xml = fetcher.get(SITEMAP_URL);
        List<String> candidates = new ArrayList<>();
        for (String url : SitemapReader.readLocs(xml)) {
            if (INTERNSHIP_PATTERN.matcher(url).find()
                    && ScraperPatterns.RELEVANCE_PATTERN.matcher(url).find()
                    && EXCLUDED_URL_SUFFIXES.stream().noneMatch(url::endsWith)) {
                candidates.add(url);
            }
        }
        return candidates;
    }

    public VacancyRecord toVacancy(String url, JsonNode posting) {
        String rawText = JobPostingHtml.cleanDescription(posting);
        String company = posting.path("hiringOrganization").path("name").asText(null);
        JsonNode address = posting.path("jobLocation").path("address");
        String location = joinLocation(
                address.path("addressLocality").asText(null),
                address.path("addressCountry").asText(null));

        JsonNode titleNode = posting.path("title");
        if (titleNode.isMissingNode()) {
            throw new NoSuchElementException("missing 'title'");
        }
        return new VacancyRecord(sourceName(), url, titleNode.asText(), company, location, rawText);
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

            String country = posting.path("jobLocation").path("address").path("addressCountry").asText(null);
            if (country != null && !country.equals("NL")) {
                System.out.println("Skipping " + url + ": not in the Netherlands (" + country + ")");
                continue;
            }

            List<String> requiredLanguages = extractRequiredLanguages(html);
            boolean requiresDutch = requiredLanguages.stream()
                    .anyMatch(lang -> lang.strip().equalsIgnoreCase("dutch"));
            if (requiresDutch) {
                System.out.println("Skipping " + url + ": requires Dutch (" + String.join(", ", requiredLanguages) + ")");
                continue;
            }

            VacancyRecord vacancy;
            try {
                vacancy = toVacancy(url, posting);
            } catch (NoSuchElementException exc) {
                System.out.println("Skipping malformed posting at " + url + ": " + exc.getMessage());
                continue;
            }

            VacancyRepository.upsertVacancy(conn, vacancy);
            count++;
        }
        return count;
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

    /**
     * Reads the page's own "Required language(s)" box (data-testid="languages") --
     * Magnet.me's own structured field, far more reliable than guessing from the
     * free-text description.
     */
    static List<String> extractRequiredLanguages(String pageHtml) {
        Document doc = Jsoup.parse(pageHtml);
        Element container = doc.selectFirst("[data-testid=languages]");
        if (container == null) {
            return List.of();
        }
        List<Element> directDivs = directChildDivs(container);
        if (directDivs.size() < 2) {
            return List.of();
        }
        Element valueDiv = directDivs.get(1);
        List<String> languages = new ArrayList<>();
        for (Element entry : directChildDivs(valueDiv)) {
            Elements spans = entry.select("span");
            if (!spans.isEmpty()) {
                languages.add(spans.first().text());
            }
        }
        return languages;
    }

    private static List<Element> directChildDivs(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Element child : parent.children()) {
            if (child.tagName().equals("div")) {
                result.add(child);
            }
        }
        return result;
    }
}
