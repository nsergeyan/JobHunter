package com.jobscout.scraper.lever;

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

import java.sql.Connection;
import java.util.ArrayList;
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

    private final List<LeverCompany> companies;

    public LeverScraper(HttpFetcher fetcher) {
        this(fetcher, CompanyRegistry.load("lever", entry -> new LeverCompany(
                CompanyRegistry.requiredField(entry, "company"),
                CompanyRegistry.requiredField(entry, "siteToken"))));
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

    /**
     * Different Lever-hosted companies overlap their description fields differently --
     * e.g. Aircall repeats the exact same text in openingPlain and descriptionPlain,
     * while Palantir's descriptionPlain already contains the entirety of
     * descriptionBodyPlain as a substring (opening blurb + body concatenated). An exact
     * equality check only catches the first pattern; a raw concatenation of all fields
     * doubles up content either way, which was confusing the model (one Palantir
     * posting came back with a one-line non-answer "summary" after its description
     * appeared twice in the input). Containment, not equality, is the right test: skip
     * a candidate if it's already covered by something kept, and drop anything already
     * kept that a later, bigger candidate turns out to fully contain.
     */
    private static String descriptionText(JsonNode job) {
        List<String> candidates = List.of(
                job.path("openingPlain").asText(""),
                job.path("descriptionPlain").asText(""),
                job.path("descriptionBodyPlain").asText(""),
                listsText(job),
                job.path("additionalPlain").asText(""));

        List<String> parts = new ArrayList<>();
        for (String raw : candidates) {
            String candidate = raw.strip();
            if (candidate.isEmpty()) {
                continue;
            }
            boolean alreadyCovered = false;
            for (int i = parts.size() - 1; i >= 0; i--) {
                String kept = parts.get(i);
                if (kept.contains(candidate)) {
                    alreadyCovered = true;
                    break;
                }
                if (candidate.contains(kept)) {
                    parts.remove(i);
                }
            }
            if (!alreadyCovered) {
                parts.add(candidate);
            }
        }

        return String.join("\n\n", parts).strip();
    }

    /**
     * Lever splits a posting's structured sections (e.g. "Key Responsibilities",
     * "Qualifications") into a separate "lists" array of {text: heading, content: HTML},
     * distinct from the four *Plain fields above. Most postings put the actual
     * role/requirements content here rather than in descriptionBodyPlain -- skipping this
     * field means raw_text ends up with company boilerplate but no responsibilities or
     * requirements at all.
     */
    private static String listsText(JsonNode job) {
        List<String> sections = new ArrayList<>();
        for (JsonNode list : job.path("lists")) {
            // Lever's heading text (e.g. "Key Responsibilities:") already carries its own
            // trailing punctuation -- don't add a second colon on top of it.
            String heading = list.path("text").asText("").strip();
            String content = JobPostingHtml.htmlToText(list.path("content").asText(""));
            if (content.isBlank()) {
                continue;
            }
            sections.add(heading.isBlank() ? content : heading + "\n" + content);
        }
        return String.join("\n\n", sections);
    }

    /**
     * The board's own stable posting id, pulled out separately because the scrape
     * loop needs it BEFORE filtering: a posting that merely failed a filter must
     * still count as seen, or it would look like it had closed.
     */
    private static String externalIdOf(JsonNode job) {
        return job.path("id").asText(job.path("hostedUrl").asText(null));
    }

    public VacancyRecord toVacancy(LeverCompany company, JsonNode job) {
        String title = job.path("text").asText("");
        String url = job.path("hostedUrl").asText(null);
        // Lever returns the posting's stable id; hostedUrl already ends in it. Fall back
        // to the url if a future response shape omits the field.
        String externalId = externalIdOf(job);
        String location = job.path("categories").path("location").asText(null);
        String rawText = descriptionText(job);

        return new VacancyRecord(sourceName(), externalId, url, title, company.company(), location, rawText);
    }

    public int run(Connection conn) {
        int count = 0;
        for (LeverCompany company : companies) {
            try (CompanyScrape scrape = beginFullListing(conn, company.company())) {
                JsonNode jobs;
                try {
                    jobs = fetchJobs(company);
                } catch (ScraperException exc) {
                    scrape.failed(exc.getMessage());
                    System.out.println("Skipping " + company.company() + ": " + exc.getMessage());
                    continue;
                }

                if (!jobs.isArray()) {
                    scrape.failed("response contained no jobs array -- the board's shape may have changed");
                    continue;
                }
                for (JsonNode job : jobs) {
                    String title = job.path("text").asText("");
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

                    String description = descriptionText(job);
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
