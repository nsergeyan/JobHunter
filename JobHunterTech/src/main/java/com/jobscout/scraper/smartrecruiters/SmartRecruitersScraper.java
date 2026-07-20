package com.jobscout.scraper.smartrecruiters;

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
import java.util.Set;

/**
 * Scraper for companies whose careers site runs on SmartRecruiters. Two-step
 * fetch like Workday: paginated list (100/page via offset+limit) for title +
 * a real structured `experienceLevel` field (SmartRecruiters' own
 * entry_level/associate/mid_senior_level/director/executive/internship/
 * not_applicable classification -- more reliable than our regex heuristics
 * where it's available), then a per-job detail fetch for the full description.
 * Confirmed live: Delivery Hero, 1064 total postings.
 *
 * Verification note: SmartRecruiters' own careers HTML page returns a
 * genuinely per-tenant title (unlike Ashby/Workable's generic-shell false
 * positives), but a guessed slug can still collide with an unrelated small
 * business -- always confirm via a real totalFound count from the postings
 * API, not just the page title.
 */
public class SmartRecruitersScraper extends BaseScraper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PAGE_SIZE = 100;

    // Categorically too senior -- skip before even fetching detail.
    private static final Set<String> EXCLUDED_EXPERIENCE_LEVELS = Set.of(
            "mid_senior_level", "director", "executive");

    // Grows by hand as more SmartRecruiters-hosted companies are identified.
    public static final List<SmartRecruitersCompany> SMARTRECRUITERS_COMPANIES = List.of(
            new SmartRecruitersCompany("Delivery Hero", "deliveryhero"),
            new SmartRecruitersCompany("Wise", "wise"),
            new SmartRecruitersCompany("Bosch", "BoschGroup"),
            new SmartRecruitersCompany("ServiceNow", "servicenow"),
            new SmartRecruitersCompany("Canva", "canva"));

    private final List<SmartRecruitersCompany> companies;
    private final int pageSize;

    public SmartRecruitersScraper(HttpFetcher fetcher) {
        this(fetcher, SMARTRECRUITERS_COMPANIES, PAGE_SIZE);
    }

    public SmartRecruitersScraper(HttpFetcher fetcher, List<SmartRecruitersCompany> companies) {
        this(fetcher, companies, PAGE_SIZE);
    }

    /** Exposes pageSize so tests can verify pagination without mocking 100+ postings per page. */
    SmartRecruitersScraper(HttpFetcher fetcher, List<SmartRecruitersCompany> companies, int pageSize) {
        super(fetcher);
        this.companies = companies;
        this.pageSize = pageSize;
    }

    @Override
    public String sourceName() {
        return "smartrecruiters";
    }

    /** Returns (title, id) pairs for postings that pass the relevance + experienceLevel pre-filter. */
    public List<JobListing> fetchCandidateJobs(SmartRecruitersCompany company) {
        List<JobListing> candidates = new ArrayList<>();
        int offset = 0;
        int total = Integer.MAX_VALUE;

        while (offset < total) {
            String url = "https://api.smartrecruiters.com/v1/companies/" + company.companyIdentifier()
                    + "/postings?offset=" + offset + "&limit=" + pageSize;
            JsonNode response = parse(fetcher.get(url), url);

            total = response.path("totalFound").asInt(0);
            // Same reasoning as WorkdayScraper: a large company's raw listing can take
            // many rate-limited pages, and this loop had no logging -- silent for minutes.
            System.out.println(company.company() + ": fetched postings " + offset + "-"
                    + Math.min(offset + pageSize, total) + " of " + total);
            JsonNode content = response.path("content");
            if (!content.isArray() || content.isEmpty()) {
                break;
            }
            for (JsonNode posting : content) {
                String title = posting.path("name").asText("");
                String id = posting.path("id").asText(null);
                String experienceLevelId = posting.path("experienceLevel").path("id").asText("");
                if (id != null && ScraperPatterns.isCandidateTitle(title)
                        && !EXCLUDED_EXPERIENCE_LEVELS.contains(experienceLevelId)) {
                    candidates.add(new JobListing(title, id));
                }
            }
            offset += pageSize;
        }
        return candidates;
    }

    public JsonNode fetchDetail(SmartRecruitersCompany company, JobListing listing) {
        String detailUrl = detailUrl(company, listing);
        return parse(fetcher.get(detailUrl), detailUrl);
    }

    static boolean isInTargetRegion(JsonNode detail) {
        JsonNode location = detail.path("location");
        if (TargetRegion.isInScopeByCountryCode(location.path("country").asText(""))) {
            return true;
        }
        return TargetRegion.textMentionsTargetRegion(location.path("fullLocation").asText(""));
    }

    private static String descriptionText(JsonNode detail) {
        String jobDescriptionHtml = detail.path("jobAd").path("sections").path("jobDescription").path("text").asText("");
        return JobPostingHtml.htmlToText(jobDescriptionHtml);
    }

    public VacancyRecord toVacancy(SmartRecruitersCompany company, JobListing listing, JsonNode detail) {
        String title = detail.path("name").asText(listing.title());
        String url = detail.path("postingUrl").asText(detailUrl(company, listing));
        String location = detail.path("location").path("fullLocation").asText(null);
        String rawText = descriptionText(detail);

        return new VacancyRecord(sourceName(), url, title, company.company(), location, rawText);
    }

    public int run(Connection conn) {
        int count = 0;
        for (SmartRecruitersCompany company : companies) {
            for (JobListing listing : fetchCandidateJobs(company)) {
                // Already evaluated (accepted or rejected) on a previous run -- skip the
                // detail fetch entirely rather than re-requesting and re-filtering it.
                if (SeenPostingRepository.isSeen(conn, sourceName(), listing.id())) {
                    continue;
                }

                JsonNode detail;
                try {
                    detail = fetchDetail(company, listing);
                } catch (ScraperException exc) {
                    System.out.println("Skipping " + company.company() + " " + listing.id() + ": " + exc.getMessage());
                    continue;
                }

                if (!isInTargetRegion(detail)) {
                    System.out.println("Skipping " + company.company() + " \"" + listing.title() + "\": outside Europe/US");
                    SeenPostingRepository.markSeen(conn, sourceName(), listing.id(), false);
                    continue;
                }

                String description = descriptionText(detail);
                if (SeniorityFilter.isSeniorRole(description)) {
                    System.out.println("Skipping " + company.company() + " \"" + listing.title()
                            + "\": description indicates a senior role");
                    SeenPostingRepository.markSeen(conn, sourceName(), listing.id(), false);
                    continue;
                }

                if (SeniorityFilter.requiresTooMuchExperience(description)) {
                    System.out.println("Skipping " + company.company() + " \"" + listing.title()
                            + "\": requires more than 2 years of experience");
                    SeenPostingRepository.markSeen(conn, sourceName(), listing.id(), false);
                    continue;
                }

                VacancyRecord vacancy = toVacancy(company, listing, detail);
                VacancyRepository.upsertVacancy(conn, vacancy);
                SeenPostingRepository.markSeen(conn, sourceName(), listing.id(), true);
                count++;
            }
        }
        return count;
    }

    private static String detailUrl(SmartRecruitersCompany company, JobListing listing) {
        return "https://api.smartrecruiters.com/v1/companies/" + company.companyIdentifier() + "/postings/" + listing.id();
    }

    private static JsonNode parse(String json, String url) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception exc) {
            throw new ScraperException("Could not parse SmartRecruiters response from " + url, exc);
        }
    }

    public record JobListing(String title, String id) {
    }
}
