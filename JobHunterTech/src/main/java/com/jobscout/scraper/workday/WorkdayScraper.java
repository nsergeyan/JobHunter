package com.jobscout.scraper.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobscout.db.VacancyRecord;
import com.jobscout.db.VacancyRepository;
import com.jobscout.scraper.BaseScraper;
import com.jobscout.scraper.HttpFetcher;
import com.jobscout.scraper.JobPostingHtml;
import com.jobscout.scraper.ScraperException;
import com.jobscout.scraper.ScraperPatterns;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Scraper for companies whose careers site is hosted on Workday (cxs) rather than
 * Greenhouse/Lever. Confirmed working against Zendesk: robots.txt on
 * {host}/robots.txt allows /{site}/, and the site's own JavaScript calls this same
 * public JSON API to render listings -- no auth, no captcha.
 *
 * Two-step fetch per company, same "list page then detail page" shape as the
 * Magnet.me/StudentJob scrapers: POST .../jobs (paginated) for the listing, then
 * GET .../job/<path> per matched posting for the full description.
 */
public class WorkdayScraper extends BaseScraper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PAGE_SIZE = 20;

    // Grows by hand as more Workday-hosted companies are identified (find the
    // tenant/site by visiting the company's careers page and reading its URL).
    public static final List<WorkdayCompany> WORKDAY_COMPANIES = List.of(
            new WorkdayCompany("Zendesk", "zendesk.wd1.myworkdayjobs.com", "zendesk", "zendesk"));

    private final List<WorkdayCompany> companies;
    private final int pageSize;

    public WorkdayScraper(HttpFetcher fetcher) {
        this(fetcher, WORKDAY_COMPANIES, PAGE_SIZE);
    }

    public WorkdayScraper(HttpFetcher fetcher, List<WorkdayCompany> companies) {
        this(fetcher, companies, PAGE_SIZE);
    }

    /** Exposes pageSize so tests can verify pagination without mocking 20+ postings per page. */
    WorkdayScraper(HttpFetcher fetcher, List<WorkdayCompany> companies, int pageSize) {
        super(fetcher);
        this.companies = companies;
        this.pageSize = pageSize;
    }

    @Override
    public String sourceName() {
        return "workday";
    }

    /** Returns (title, externalPath) pairs for postings whose title passes the relevance filter. */
    public List<JobListing> fetchCandidateJobs(WorkdayCompany company) {
        List<JobListing> candidates = new ArrayList<>();
        int offset = 0;
        int total = Integer.MAX_VALUE;

        while (offset < total) {
            String url = "https://" + company.host() + "/wday/cxs/" + company.tenant() + "/" + company.site() + "/jobs";
            String body = "{\"limit\":%d,\"offset\":%d,\"searchText\":\"\"}".formatted(pageSize, offset);
            JsonNode response = parse(fetcher.post(url, body), url);

            total = response.path("total").asInt(0);
            JsonNode postings = response.path("jobPostings");
            if (!postings.isArray() || postings.isEmpty()) {
                break;
            }
            for (JsonNode posting : postings) {
                String title = posting.path("title").asText("");
                String externalPath = posting.path("externalPath").asText(null);
                if (externalPath != null && ScraperPatterns.RELEVANCE_TITLE_PATTERN.matcher(title).find()) {
                    candidates.add(new JobListing(title, externalPath));
                }
            }
            offset += pageSize;
        }
        return candidates;
    }

    public VacancyRecord toVacancy(WorkdayCompany company, JobListing listing) {
        String detailUrl = "https://" + company.host() + "/wday/cxs/" + company.tenant() + "/" + company.site() + listing.externalPath();
        JsonNode detail = parse(fetcher.get(detailUrl), detailUrl);
        JsonNode info = detail.path("jobPostingInfo");

        String title = info.path("title").asText(listing.title());
        String url = info.path("externalUrl").asText(detailUrl);
        String location = info.path("location").asText(null);
        String rawText = JobPostingHtml.htmlToText(info.path("jobDescription").asText(""));
        String companyName = detail.path("hiringOrganization").path("name").asText(company.company());

        return new VacancyRecord(sourceName(), url, title, companyName, location, rawText);
    }

    public int run(Connection conn) {
        int count = 0;
        for (WorkdayCompany company : companies) {
            for (JobListing listing : fetchCandidateJobs(company)) {
                VacancyRecord vacancy;
                try {
                    vacancy = toVacancy(company, listing);
                } catch (ScraperException exc) {
                    System.out.println("Skipping " + company.company() + " " + listing.externalPath() + ": " + exc.getMessage());
                    continue;
                }
                VacancyRepository.upsertVacancy(conn, vacancy);
                count++;
            }
        }
        return count;
    }

    private static JsonNode parse(String json, String url) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception exc) {
            throw new ScraperException("Could not parse Workday response from " + url, exc);
        }
    }

    public record JobListing(String title, String externalPath) {
    }
}
