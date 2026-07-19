package com.jobscout.scraper.lever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobscout.db.VacancyRecord;
import com.jobscout.db.VacancyRepository;
import com.jobscout.scraper.BaseScraper;
import com.jobscout.scraper.HttpFetcher;
import com.jobscout.scraper.ScraperException;
import com.jobscout.scraper.ScraperPatterns;
import com.jobscout.scraper.SeniorityFilter;
import com.jobscout.scraper.TargetRegion;

import java.sql.Connection;
import java.util.List;

/**
 * Scraper for companies whose careers site runs on Lever. Same single-request
 * shape as Greenhouse/Ashby: api.lever.co/v0/postings/{siteToken}?mode=json
 * returns every posting with full text inline, no pagination, no per-job
 * detail fetch -- unlike Greenhouse/Ashby, the response is a bare JSON array,
 * not wrapped in {"jobs": [...]}. Confirmed live: Palantir (274 jobs),
 * Spotify (111 jobs); Mistral AI currently has 0 open postings (a real,
 * valid board that's just empty right now -- same as Zendesk sometimes is).
 *
 * The "country" field is a 2-letter ISO 3166-1 alpha-2 code (e.g. "GB"), not
 * a full name -- TargetRegion.isInScopeByCountryCode() handles that. The
 * description is split across several plain-text fields (opening, main
 * description, body, additional info); concatenated so seniority/experience
 * checks see the full text, not just one section.
 */
public class LeverScraper extends BaseScraper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Grows by hand as more Lever-hosted companies are identified. Verify via
    // api.lever.co/v0/postings/{siteToken}?mode=json directly.
    public static final List<LeverCompany> LEVER_COMPANIES = List.of(
            new LeverCompany("Mistral AI", "mistral"),
            new LeverCompany("Palantir", "palantir"),
            new LeverCompany("Spotify", "spotify"));

    private final List<LeverCompany> companies;

    public LeverScraper(HttpFetcher fetcher) {
        this(fetcher, LEVER_COMPANIES);
    }

    public LeverScraper(HttpFetcher fetcher, List<LeverCompany> companies) {
        super(fetcher);
        this.companies = companies;
    }

    @Override
    public String sourceName() {
        return "lever";
    }

    public JsonNode fetchJobs(LeverCompany company) {
        String url = "https://api.lever.co/v0/postings/" + company.siteToken() + "?mode=json";
        String json = fetcher.get(url);
        try {
            return MAPPER.readTree(json);
        } catch (Exception exc) {
            throw new ScraperException("Could not parse Lever response from " + url, exc);
        }
    }

    static boolean isInTargetRegion(JsonNode job) {
        String countryCode = job.path("country").asText("");
        if (TargetRegion.isInScopeByCountryCode(countryCode)) {
            return true;
        }
        return TargetRegion.textMentionsTargetRegion(job.path("categories").path("location").asText(""));
    }

    private static String descriptionText(JsonNode job) {
        return String.join("\n\n",
                job.path("openingPlain").asText(""),
                job.path("descriptionPlain").asText(""),
                job.path("descriptionBodyPlain").asText(""),
                job.path("additionalPlain").asText("")).strip();
    }

    public VacancyRecord toVacancy(LeverCompany company, JsonNode job) {
        String title = job.path("text").asText("");
        String url = job.path("hostedUrl").asText(null);
        String location = job.path("categories").path("location").asText(null);
        String rawText = descriptionText(job);

        return new VacancyRecord(sourceName(), url, title, company.company(), location, rawText);
    }

    public int run(Connection conn) {
        int count = 0;
        for (LeverCompany company : companies) {
            JsonNode jobs;
            try {
                jobs = fetchJobs(company);
            } catch (ScraperException exc) {
                System.out.println("Skipping " + company.company() + ": " + exc.getMessage());
                continue;
            }

            if (!jobs.isArray()) {
                continue;
            }
            for (JsonNode job : jobs) {
                String title = job.path("text").asText("");
                if (!ScraperPatterns.isCandidateTitle(title)) {
                    continue;
                }

                if (!isInTargetRegion(job)) {
                    System.out.println("Skipping " + company.company() + " \"" + title + "\": outside Europe/US");
                    continue;
                }

                String description = descriptionText(job);
                if (SeniorityFilter.isSeniorRole(description)) {
                    System.out.println("Skipping " + company.company() + " \"" + title
                            + "\": description indicates a senior role");
                    continue;
                }

                if (SeniorityFilter.requiresTooMuchExperience(description)) {
                    System.out.println("Skipping " + company.company() + " \"" + title
                            + "\": requires more than 2 years of experience");
                    continue;
                }

                VacancyRecord vacancy = toVacancy(company, job);
                VacancyRepository.upsertVacancy(conn, vacancy);
                count++;
            }
        }
        return count;
    }
}
