package com.jobscout.scraper.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobscout.db.VacancyRecord;
import com.jobscout.db.VacancyRepository;
import com.jobscout.scraper.BaseScraper;
import com.jobscout.scraper.CompanyScrape;
import com.jobscout.scraper.CompanyRegistry;
import com.jobscout.scraper.HttpFetcher;
import com.jobscout.scraper.JobPostingHtml;
import com.jobscout.scraper.ScraperException;
import com.jobscout.scraper.ScraperPatterns;
import com.jobscout.scraper.SeniorityFilter;
import com.jobscout.scraper.TargetRegion;
import org.jsoup.parser.Parser;

import java.sql.Connection;
import java.util.List;

/**
 * Scraper for companies whose careers site runs on Greenhouse. Unlike Workday,
 * Greenhouse's public job board API (boards-api.greenhouse.io) returns the full
 * job list AND full descriptions in a single request when called with
 * ?content=true -- confirmed live (e.g. Anthropic returns all 411 jobs, full
 * descriptions included, in one call) -- no pagination, no separate per-job
 * detail fetch needed, unlike Workday's two-step shape.
 *
 * One quirk: the "content" field comes back HTML-entity-encoded within the JSON
 * string itself (e.g. the string value is literally "&lt;div&gt;", not "<div>"),
 * so it needs Parser.unescapeEntities() before Jsoup can read it as real markup.
 */
public class GreenhouseScraper extends BaseScraper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<GreenhouseCompany> companies;

    public GreenhouseScraper(HttpFetcher fetcher) {
        this(fetcher, CompanyRegistry.load("greenhouse", entry -> new GreenhouseCompany(
                CompanyRegistry.requiredField(entry, "company"),
                CompanyRegistry.requiredField(entry, "boardToken"))));
    }

    public GreenhouseScraper(HttpFetcher fetcher, List<GreenhouseCompany> companies) {
        super(fetcher);
        this.companies = companies;
    }

    @Override
    public String sourceName() {
        return "greenhouse";
    }

    public JsonNode fetchJobs(GreenhouseCompany company) {
        String url = "https://boards-api.greenhouse.io/v1/boards/" + company.boardToken() + "/jobs?content=true";
        String json = fetcher.get(url);
        try {
            return MAPPER.readTree(json);
        } catch (Exception exc) {
            throw new ScraperException("Could not parse Greenhouse response from " + url, exc);
        }
    }

    /**
     * The board's own stable posting id, pulled out separately because the scrape
     * loop needs it BEFORE filtering: a posting that merely failed a filter must
     * still count as seen, or it would look like it had closed.
     */
    private static String externalIdOf(JsonNode job) {
        return job.path("id").asText(job.path("absolute_url").asText(null));
    }

    public VacancyRecord toVacancy(GreenhouseCompany company, JsonNode job) {
        String title = job.path("title").asText("");
        String url = job.path("absolute_url").asText(null);
        // Greenhouse's numeric job id is stable even when absolute_url changes.
        String externalId = externalIdOf(job);
        String location = job.path("location").path("name").asText(null);
        String companyName = job.path("company_name").asText(company.company());
        String rawText = JobPostingHtml.htmlToText(descriptionHtml(job));

        return new VacancyRecord(sourceName(), externalId, url, title, companyName, location, rawText);
    }

    private static String descriptionHtml(JsonNode job) {
        return Parser.unescapeEntities(job.path("content").asText(""), false);
    }

    public int run(Connection conn) {
        int count = 0;
        for (GreenhouseCompany company : companies) {
            try (CompanyScrape scrape = beginFullListing(conn, company.company())) {
                JsonNode response;
                try {
                    response = fetchJobs(company);
                } catch (ScraperException exc) {
                    scrape.failed(exc.getMessage());
                    System.out.println("Skipping " + company.company() + ": " + exc.getMessage());
                    continue;
                }

                JsonNode jobs = response.path("jobs");
                if (!jobs.isArray()) {
                    scrape.failed("response contained no jobs array -- the board's shape may have changed");
                    continue;
                }
                scrape.boardReturned(jobs.size());
                for (JsonNode job : jobs) {
                    String title = job.path("title").asText("");
                    scrape.listed(externalIdOf(job));
                    if (!ScraperPatterns.isCandidateTitle(title)) {
                        scrape.filteredOut();
                        continue;
                    }

                    String location = job.path("location").path("name").asText("");
                    if (!TargetRegion.textMentionsTargetRegion(location)) {
                        System.out.println("Skipping " + company.company() + " \"" + title + "\": outside Europe");
                        scrape.filteredOut();
                        continue;
                    }

                    String description = descriptionHtml(job);
                    if (SeniorityFilter.isSeniorRole(description)) {
                        System.out.println("Skipping " + company.company() + " \"" + title
                                + "\": description indicates a senior role");
                        scrape.filteredOut();
                        continue;
                    }

                    if (SeniorityFilter.requiresTooMuchExperience(description)) {
                        System.out.println("Skipping " + company.company() + " \"" + title
                                + "\": requires more than 2 years of experience");
                        scrape.filteredOut();
                        continue;
                    }

                    VacancyRecord vacancy = toVacancy(company, job);
                    VacancyRepository.upsertVacancy(conn, vacancy);
                    scrape.stored();
                    count++;
                }
                scrape.listingComplete();
            }
        }
        return count;
    }
}
