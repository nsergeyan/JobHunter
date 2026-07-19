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
import com.jobscout.scraper.TargetRegion;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scraper for companies whose careers site is hosted on Workday (cxs) rather than
 * Greenhouse/Lever. Confirmed working against Zendesk: robots.txt on
 * {host}/robots.txt allows /{site}/, and the site's own JavaScript calls this same
 * public JSON API to render listings -- no auth, no captcha.
 *
 * Two-step fetch per company, same "list page then detail page" shape as the
 * Magnet.me/StudentJob scrapers: POST .../jobs (paginated) for the listing, then
 * GET .../job/<path> per matched posting for the full description.
 *
 * Unlike Magnet.me/StudentJob.nl (which are NL-only student job boards),
 * Workday-hosted companies are typically global -- so this scraper filters on
 * seniority (checked twice: cheaply against the title before fetching details,
 * then again against the full description after fetching, since some companies
 * only state seniority in the description body) and location (needs the fetched
 * detail's structured country field; a "Krakow, Poland" style locationsText
 * string on the list response isn't reliable enough to filter on before fetching).
 */
public class WorkdayScraper extends BaseScraper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PAGE_SIZE = 20;

    // Titles containing one of these read as a senior role...
    private static final Pattern SENIOR_TITLE_PATTERN = Pattern.compile(
            "\\bsenior\\b|\\bsr\\.?\\b|\\bstaff\\b|\\bprincipal\\b|\\blead\\b|\\bdirector\\b|\\bmanager\\b"
                    + "|\\bvp\\b|\\bvice president\\b|\\bchief\\b|\\bhead of\\b",
            Pattern.CASE_INSENSITIVE);

    // ...unless it also explicitly says junior/intern/graduate -- keep those regardless.
    private static final Pattern JUNIOR_INDICATOR_PATTERN = Pattern.compile(
            "\\bintern(ship)?\\b|\\bjunior\\b|\\bjr\\.?\\b|\\bgraduate\\b|\\bentry[- ]level\\b|\\bnew grad\\b",
            Pattern.CASE_INSENSITIVE);

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

    /** Returns (title, externalPath) pairs for postings that pass the relevance + seniority filters. */
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
                if (externalPath != null && isCandidateTitle(title)) {
                    candidates.add(new JobListing(title, externalPath));
                }
            }
            offset += pageSize;
        }
        return candidates;
    }

    static boolean isCandidateTitle(String title) {
        return ScraperPatterns.RELEVANCE_TITLE_PATTERN.matcher(title).find() && !isSeniorRole(title);
    }

    /**
     * Some companies (Zendesk included) don't put seniority in the job title at
     * all -- "AI Agent Abuse Prevention Engineer" turned out to open with "Zendesk
     * is hiring a Senior Staff-level technical leader..." in the description. So
     * this same check also runs against the full description after fetching detail,
     * not just the title.
     */
    static boolean isSeniorRole(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean senior = SENIOR_TITLE_PATTERN.matcher(text).find();
        boolean juniorSignal = JUNIOR_INDICATOR_PATTERN.matcher(text).find();
        return senior && !juniorSignal;
    }

    public JsonNode fetchDetail(WorkdayCompany company, JobListing listing) {
        String detailUrl = detailUrl(company, listing);
        return parse(fetcher.get(detailUrl), detailUrl);
    }

    public VacancyRecord toVacancy(WorkdayCompany company, JobListing listing, JsonNode detail) {
        JsonNode info = detail.path("jobPostingInfo");
        String title = info.path("title").asText(listing.title());
        String url = info.path("externalUrl").asText(detailUrl(company, listing));
        String location = info.path("location").asText(null);
        String rawText = JobPostingHtml.htmlToText(info.path("jobDescription").asText(""));
        String companyName = detail.path("hiringOrganization").path("name").asText(company.company());

        return new VacancyRecord(sourceName(), url, title, companyName, location, rawText);
    }

    /** Europe + US -- see TargetRegion. Structured country field first, free-text location as fallback. */
    static boolean isInTargetRegion(JsonNode detail) {
        JsonNode info = detail.path("jobPostingInfo");
        String country = info.path("country").path("descriptor").asText("");
        if (TargetRegion.isInScope(country)) {
            return true;
        }
        return TargetRegion.textMentionsTargetRegion(info.path("location").asText(""));
    }

    public int run(Connection conn) {
        int count = 0;
        for (WorkdayCompany company : companies) {
            for (JobListing listing : fetchCandidateJobs(company)) {
                JsonNode detail;
                try {
                    detail = fetchDetail(company, listing);
                } catch (ScraperException exc) {
                    System.out.println("Skipping " + company.company() + " " + listing.externalPath() + ": " + exc.getMessage());
                    continue;
                }

                if (!isInTargetRegion(detail)) {
                    System.out.println("Skipping " + company.company() + " \"" + listing.title() + "\": outside Europe/US");
                    continue;
                }

                String description = detail.path("jobPostingInfo").path("jobDescription").asText("");
                if (isSeniorRole(description)) {
                    System.out.println("Skipping " + company.company() + " \"" + listing.title()
                            + "\": description indicates a senior role");
                    continue;
                }

                VacancyRecord vacancy = toVacancy(company, listing, detail);
                VacancyRepository.upsertVacancy(conn, vacancy);
                count++;
            }
        }
        return count;
    }

    private static String detailUrl(WorkdayCompany company, JobListing listing) {
        return "https://" + company.host() + "/wday/cxs/" + company.tenant() + "/" + company.site() + listing.externalPath();
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
