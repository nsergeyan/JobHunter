package com.jobscout.scraper.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobscout.db.VacancyRecord;
import com.jobscout.db.VacancyRepository;
import com.jobscout.scraper.BaseScraper;
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

    // Grows by hand as more Greenhouse-hosted companies are identified. Verify
    // the boardToken by hitting boards-api.greenhouse.io/v1/boards/{token}/jobs
    // directly -- it isn't always the same as the company's display name.
    public static final List<GreenhouseCompany> GREENHOUSE_COMPANIES = List.of(
            new GreenhouseCompany("Anthropic", "anthropic"),
            new GreenhouseCompany("Scale AI", "scaleai"),
            new GreenhouseCompany("Stripe", "stripe"),
            new GreenhouseCompany("Datadog", "datadog"),
            new GreenhouseCompany("Cloudflare", "cloudflare"),
            new GreenhouseCompany("Airbnb", "airbnb"),
            new GreenhouseCompany("Robinhood", "robinhood"),
            new GreenhouseCompany("Squarespace", "squarespace"),
            new GreenhouseCompany("Duolingo", "duolingo"),
            new GreenhouseCompany("Coinbase", "coinbase"),
            new GreenhouseCompany("Pinterest", "pinterest"),
            new GreenhouseCompany("Roblox", "roblox"),
            new GreenhouseCompany("Databricks", "databricks"),
            new GreenhouseCompany("Figma", "figma"),
            new GreenhouseCompany("DeepMind", "deepmind"),
            new GreenhouseCompany("Discord", "discord"),
            new GreenhouseCompany("Reddit", "reddit"),
            new GreenhouseCompany("Instacart", "instacart"),
            new GreenhouseCompany("DoorDash", "doordashusa"),
            new GreenhouseCompany("Affirm", "affirm"),
            new GreenhouseCompany("Brex", "brex"),
            new GreenhouseCompany("Vercel", "vercel"),
            new GreenhouseCompany("Asana", "asana"));

    private final List<GreenhouseCompany> companies;

    public GreenhouseScraper(HttpFetcher fetcher) {
        this(fetcher, GREENHOUSE_COMPANIES);
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

    public VacancyRecord toVacancy(GreenhouseCompany company, JsonNode job) {
        String title = job.path("title").asText("");
        String url = job.path("absolute_url").asText(null);
        String location = job.path("location").path("name").asText(null);
        String companyName = job.path("company_name").asText(company.company());
        String rawText = JobPostingHtml.htmlToText(descriptionHtml(job));

        return new VacancyRecord(sourceName(), url, title, companyName, location, rawText);
    }

    private static String descriptionHtml(JsonNode job) {
        return Parser.unescapeEntities(job.path("content").asText(""), false);
    }

    public int run(Connection conn) {
        int count = 0;
        for (GreenhouseCompany company : companies) {
            JsonNode response;
            try {
                response = fetchJobs(company);
            } catch (ScraperException exc) {
                System.out.println("Skipping " + company.company() + ": " + exc.getMessage());
                continue;
            }

            JsonNode jobs = response.path("jobs");
            if (!jobs.isArray()) {
                continue;
            }
            for (JsonNode job : jobs) {
                String title = job.path("title").asText("");
                if (!ScraperPatterns.isCandidateTitle(title)) {
                    continue;
                }

                String location = job.path("location").path("name").asText("");
                if (!TargetRegion.textMentionsTargetRegion(location)) {
                    System.out.println("Skipping " + company.company() + " \"" + title + "\": outside Europe/US");
                    continue;
                }

                String description = descriptionHtml(job);
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
