package com.jobscout.scraper.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobscout.db.SeenPostingRepository;
import com.jobscout.db.VacancyRecord;
import com.jobscout.db.VacancyRepository;
import com.jobscout.scraper.BaseScraper;
import com.jobscout.scraper.HttpFetcher;
import com.jobscout.scraper.JobPostingHtml;
import com.jobscout.scraper.ScraperException;
import com.jobscout.scraper.ScraperPatterns;
import com.jobscout.scraper.SeniorityFilter;
import com.jobscout.scraper.TargetRegion;

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
 * old Magnet.me/StudentJob scrapers: POST .../jobs (paginated) for the listing,
 * then GET .../job/<path> per matched posting for the full description.
 *
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

    // Grows by hand as more Workday-hosted companies are identified (find the
    // tenant/site by visiting the company's careers page and reading its URL).
    public static final List<WorkdayCompany> WORKDAY_COMPANIES = List.of(
            new WorkdayCompany("Zendesk", "zendesk.wd1.myworkdayjobs.com", "zendesk", "zendesk"),
            new WorkdayCompany("Workday", "workday.wd5.myworkdayjobs.com", "workday", "Workday"),
            new WorkdayCompany("Samsung", "sec.wd3.myworkdayjobs.com", "sec", "Samsung_Careers"),
            new WorkdayCompany("NVIDIA", "nvidia.wd5.myworkdayjobs.com", "nvidia", "NVIDIAExternalCareerSite"),
            new WorkdayCompany("Philips", "philips.wd3.myworkdayjobs.com", "philips", "jobs-and-careers"),
            new WorkdayCompany("Deutsche Bank", "db.wd3.myworkdayjobs.com", "db", "DBWebsite"),
            new WorkdayCompany("AstraZeneca", "astrazeneca.wd3.myworkdayjobs.com", "astrazeneca", "Careers"),
            new WorkdayCompany("Barclays", "barclays.wd3.myworkdayjobs.com", "barclays", "External_Career_Site_Barclays"),
            new WorkdayCompany("ING", "ing.wd3.myworkdayjobs.com", "ing", "ICSGBLCOR"),
            new WorkdayCompany("ASML", "asml.wd3.myworkdayjobs.com", "asml", "ASMLEXT1"),
            new WorkdayCompany("Booking Holdings", "priceline.wd1.myworkdayjobs.com", "priceline", "BookingHoldings"));

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
        // Workday reports a truthful "total" only on the first page (offset 0); later
        // pages return 0 while still serving results, so capture it once and use it as
        // the loop bound. Paging past the end makes Workday wrap back to page 1, so we
        // stop on the captured total rather than on a short/empty page alone.
        int total = -1;

        while (true) {
            String url = "https://" + company.host() + "/wday/cxs/" + company.tenant() + "/" + company.site() + "/jobs";
            String body = "{\"limit\":%d,\"offset\":%d,\"searchText\":\"\"}".formatted(pageSize, offset);
            JsonNode response = parse(fetcher.post(url, body), url);

            if (total < 0) {
                total = response.path("total").asInt(0);
            }
            JsonNode postings = response.path("jobPostings");
            int returned = postings.isArray() ? postings.size() : 0;
            // This loop can be dozens/hundreds of rate-limited requests for a large
            // company (e.g. 2000 postings / 20 per page = 100 requests) -- without this,
            // the console goes silent for minutes before any per-job logging kicks in.
            System.out.println(company.company() + ": fetched postings " + offset + "-"
                    + (offset + returned) + " of " + total);
            if (returned == 0) {
                break;
            }
            for (JsonNode posting : postings) {
                String title = posting.path("title").asText("");
                String externalPath = posting.path("externalPath").asText(null);
                if (externalPath != null && ScraperPatterns.isCandidateTitle(title)) {
                    candidates.add(new JobListing(title, externalPath));
                }
            }
            offset += pageSize;
            if (offset >= total) {
                break;
            }
        }
        return candidates;
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
        // Some tenants (e.g. Capital One) return hiringOrganization.name as an empty
        // string rather than omitting it -- .asText(fallback) only substitutes the
        // fallback for a MISSING field, not a present-but-blank one, so check blankness
        // explicitly rather than relying on the Jackson default-value parameter.
        String companyName = detail.path("hiringOrganization").path("name").asText("");
        if (companyName.isBlank()) {
            companyName = company.company();
        }

        // externalPath is /job/{location}/{title-slug}_{reqId}. Only the reqId tail is
        // stable -- the location/title slug changes if the posting is edited -- so key
        // identity on that, not the whole path.
        String externalPath = listing.externalPath();
        int sep = externalPath.lastIndexOf('_');
        String externalId = sep >= 0 ? externalPath.substring(sep + 1) : externalPath;
        return new VacancyRecord(sourceName(), externalId, url, title, companyName, location, rawText);
    }

    /** Europe only -- see TargetRegion. Structured country field first, free-text location as fallback. */
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
                // Already evaluated (accepted or rejected) on a previous run -- skip the
                // detail fetch entirely rather than re-requesting and re-filtering it.
                if (SeenPostingRepository.isSeen(conn, sourceName(), listing.externalPath())) {
                    continue;
                }

                JsonNode detail;
                try {
                    detail = fetchDetail(company, listing);
                } catch (ScraperException exc) {
                    System.out.println("Skipping " + company.company() + " " + listing.externalPath() + ": " + exc.getMessage());
                    continue;
                }

                if (!isInTargetRegion(detail)) {
                    System.out.println("Skipping " + company.company() + " \"" + listing.title() + "\": outside Europe");
                    SeenPostingRepository.markSeen(conn, sourceName(), listing.externalPath(), false);
                    continue;
                }

                String description = detail.path("jobPostingInfo").path("jobDescription").asText("");
                if (SeniorityFilter.isSeniorRole(description)) {
                    System.out.println("Skipping " + company.company() + " \"" + listing.title()
                            + "\": description indicates a senior role");
                    SeenPostingRepository.markSeen(conn, sourceName(), listing.externalPath(), false);
                    continue;
                }

                if (SeniorityFilter.requiresTooMuchExperience(description)) {
                    System.out.println("Skipping " + company.company() + " \"" + listing.title()
                            + "\": requires more than 2 years of experience");
                    SeenPostingRepository.markSeen(conn, sourceName(), listing.externalPath(), false);
                    continue;
                }

                VacancyRecord vacancy = toVacancy(company, listing, detail);
                VacancyRepository.upsertVacancy(conn, vacancy);
                SeenPostingRepository.markSeen(conn, sourceName(), listing.externalPath(), true);
                count++;
                System.out.println("Accepted " + company.company() + " \"" + listing.title() + "\" (" + count + " so far)");
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
