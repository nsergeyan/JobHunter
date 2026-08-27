package com.jobscout.scraper.ashby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobscout.db.VacancyRecord;
import com.jobscout.db.VacancyRepository;
import com.jobscout.scraper.BaseScraper;
import com.jobscout.scraper.CompanyScrape;
import com.jobscout.scraper.CompanyRegistry;
import com.jobscout.scraper.HttpFetcher;
import com.jobscout.scraper.ScraperException;
import com.jobscout.scraper.ScraperPatterns;
import com.jobscout.scraper.SeniorityFilter;
import com.jobscout.scraper.TargetRegion;

import java.sql.Connection;
import java.util.List;

/**
 * Scraper for companies whose careers site runs on Ashby. Like Greenhouse, a
 * single request per company (api.ashbyhq.com/posting-api/job-board/{boardName})
 * returns the full job list with descriptions inline -- no pagination, no
 * per-job detail fetch. Confirmed live: OpenAI (724 jobs), Snowflake (411),
 * Cohere (~1174 raw postings before filtering).
 *
 * Nicer than both Workday and Greenhouse on two counts: `address.postalAddress
 * .addressCountry` is a real structured country field (not free-text-only like
 * Greenhouse), and `descriptionPlain` is already plain text -- no HTML parsing
 * or entity-decoding needed at all.
 *
 * Caveat that cost real investigation time: Ashby's own careers HTML page
 * (jobs.ashbyhq.com/{slug}) returns 200 with a generic "Jobs" shell for *any*
 * slug, real or not -- only the posting-api JSON endpoint actually confirms
 * whether a company exists on the platform.
 */
public class AshbyScraper extends BaseScraper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<AshbyCompany> companies;

    public AshbyScraper(HttpFetcher fetcher) {
        this(fetcher, CompanyRegistry.load("ashby", entry -> new AshbyCompany(
                CompanyRegistry.requiredField(entry, "company"),
                CompanyRegistry.requiredField(entry, "boardName"))));
    }

    public AshbyScraper(HttpFetcher fetcher, List<AshbyCompany> companies) {
        super(fetcher);
        this.companies = companies;
    }

    @Override
    public String sourceName() {
        return "ashby";
    }

    public JsonNode fetchJobs(AshbyCompany company) {
        String url = "https://api.ashbyhq.com/posting-api/job-board/" + company.boardName();
        String json = fetcher.get(url);
        try {
            return MAPPER.readTree(json);
        } catch (Exception exc) {
            throw new ScraperException("Could not parse Ashby response from " + url, exc);
        }
    }

    static boolean isInTargetRegion(JsonNode job) {
        String country = job.path("address").path("postalAddress").path("addressCountry").asText("");
        if (TargetRegion.isInScope(country)) {
            return true;
        }
        return TargetRegion.textMentionsTargetRegion(job.path("location").asText(""));
    }

    /**
     * The board's own stable posting id, pulled out separately because the scrape
     * loop needs it BEFORE filtering: a posting that merely failed a filter must
     * still count as seen, or it would look like it had closed.
     */
    private static String externalIdOf(JsonNode job) {
        return job.path("id").asText(job.path("jobUrl").asText(null));
    }

    public VacancyRecord toVacancy(AshbyCompany company, JsonNode job) {
        String title = job.path("title").asText("");
        String url = job.path("jobUrl").asText(null);
        // Ashby returns the posting's stable id; jobUrl already ends in it. Fall back
        // to the url if a future response shape omits the field.
        String externalId = externalIdOf(job);
        String location = job.path("location").asText(null);
        String rawText = job.path("descriptionPlain").asText("").strip();

        return new VacancyRecord(sourceName(), externalId, url, title, company.company(), location, rawText);
    }

    public int run(Connection conn) {
        int count = 0;
        for (AshbyCompany company : companies) {
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
                for (JsonNode job : jobs) {
                    String title = job.path("title").asText("");
                    scrape.listed(externalIdOf(job));
                    if (!ScraperPatterns.isCandidateTitle(title)) {
                        scrape.filteredOut();
                        continue;
                    }

                    if (!isInTargetRegion(job)) {
                        System.out.println("Skipping " + company.company() + " \"" + title + "\": outside Europe");
                        scrape.filteredOut();
                        continue;
                    }

                    String description = job.path("descriptionPlain").asText("");
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
