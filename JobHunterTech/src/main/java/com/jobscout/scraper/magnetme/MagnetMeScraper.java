package com.jobscout.scraper.magnetme;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobscout.db.SeenPostingRepository;
import com.jobscout.db.VacancyRecord;
import com.jobscout.db.VacancyRepository;
import com.jobscout.scraper.BaseScraper;
import com.jobscout.scraper.HttpFetcher;
import com.jobscout.scraper.JobPostingHtml;
import com.jobscout.scraper.ScraperException;
import com.jobscout.scraper.ScraperPatterns;
import com.jobscout.scraper.SeniorityFilter;
import com.jobscout.scraper.SitemapReader;
import com.jobscout.scraper.TargetRegion;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Magnet.me scraper -- unlike the ATS platform scrapers, this discovers
 * postings across whatever companies list on Magnet.me rather than a
 * hand-curated company list, which is the point: it surfaces DS/AI/software
 * companies (mostly Dutch) that don't run any of the five ATS platforms
 * scraped elsewhere.
 *
 * Magnet.me's robots.txt disallows crawling their search/listing pages (any URL
 * containing query= or country=), so instead of searching directly this: (1)
 * downloads their public sitemap of opportunity URLs, (2) filters the URL list
 * with the same title-relevance pre-filter every other scraper uses, (3) visits
 * only those specific opportunity pages (plain, non-query URLs -- allowed).
 *
 * Each opportunity page embeds a schema.org JobPosting as JSON-LD.
 */
public class MagnetMeScraper extends BaseScraper {
    private static final String SITEMAP_URL = "https://magnet.me/sitemaps/en-opportunities.xml";

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

    /** Sitemap URL slugs read like plain job titles, so the shared title pre-filter applies directly. */
    public List<String> fetchCandidateUrls() {
        String xml = fetcher.get(SITEMAP_URL);
        List<String> candidates = new ArrayList<>();
        for (String url : SitemapReader.readLocs(xml)) {
            if (ScraperPatterns.isCandidateTitle(url) && EXCLUDED_URL_SUFFIXES.stream().noneMatch(url::endsWith)) {
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
            // Already evaluated (accepted or rejected) on a previous run -- skip the
            // page fetch entirely. The sitemap can list thousands of URLs.
            if (SeenPostingRepository.isSeen(conn, sourceName(), url)) {
                continue;
            }

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
                SeenPostingRepository.markSeen(conn, sourceName(), url, false);
                continue;
            }

            String country = posting.path("jobLocation").path("address").path("addressCountry").asText(null);
            if (country != null && !TargetRegion.isInScopeByCountryCode(country)) {
                System.out.println("Skipping " + url + ": outside Europe (" + country + ")");
                SeenPostingRepository.markSeen(conn, sourceName(), url, false);
                continue;
            }

            List<String> requiredLanguages = extractRequiredLanguages(html);
            boolean requiresDutch = requiredLanguages.stream()
                    .anyMatch(lang -> lang.strip().equalsIgnoreCase("dutch"));
            if (requiresDutch) {
                System.out.println("Skipping " + url + ": requires Dutch (" + String.join(", ", requiredLanguages) + ")");
                SeenPostingRepository.markSeen(conn, sourceName(), url, false);
                continue;
            }

            String rawText = JobPostingHtml.cleanDescription(posting);
            if (SeniorityFilter.isSeniorRole(rawText)) {
                System.out.println("Skipping " + url + ": description indicates a senior role");
                SeenPostingRepository.markSeen(conn, sourceName(), url, false);
                continue;
            }

            if (SeniorityFilter.requiresTooMuchExperience(rawText)) {
                System.out.println("Skipping " + url + ": requires more than 2 years of experience");
                SeenPostingRepository.markSeen(conn, sourceName(), url, false);
                continue;
            }

            VacancyRecord vacancy;
            try {
                vacancy = toVacancy(url, posting);
            } catch (NoSuchElementException exc) {
                System.out.println("Skipping malformed posting at " + url + ": " + exc.getMessage());
                SeenPostingRepository.markSeen(conn, sourceName(), url, false);
                continue;
            }

            VacancyRepository.upsertVacancy(conn, vacancy);
            SeenPostingRepository.markSeen(conn, sourceName(), url, true);
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
