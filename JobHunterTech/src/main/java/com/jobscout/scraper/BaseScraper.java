package com.jobscout.scraper;

import com.jobscout.db.SeenPostingRepository;

import java.sql.Connection;

/** Shared base for scrapers: holds the HttpFetcher (real or fake) subclasses fetch through. */
public abstract class BaseScraper {
    protected final HttpFetcher fetcher;

    protected BaseScraper(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public abstract String sourceName();

    /**
     * True if this posting was already evaluated under the CURRENT filter version,
     * so its detail page can be skipped. Bumping FilterVersion makes this false
     * again for older records, giving each one exactly one fresh evaluation.
     *
     * Wrapped here rather than called directly so the version is named in one place
     * instead of at all eighteen call sites across the scrapers.
     */
    protected boolean alreadyEvaluated(Connection conn, String externalId) {
        return SeenPostingRepository.isSeen(conn, sourceName(), externalId, FilterVersion.CURRENT);
    }

    /** Record this posting's verdict against the current filter version. */
    protected void recordEvaluation(Connection conn, String externalId, boolean accepted) {
        SeenPostingRepository.markSeen(conn, sourceName(), externalId, accepted, FilterVersion.CURRENT);
    }

    /**
     * Bookkeeping for a company whose ENTIRE board listing this scraper walks, so a
     * posting's absence really does mean it is gone. Use as a try-with-resources
     * around that company's loop: the scrape_runs row is written on every path out,
     * and postings missing from a clean full read get closed.
     */
    protected CompanyScrape beginFullListing(Connection conn, String company) {
        return new CompanyScrape(conn, sourceName(), company, true);
    }

    /**
     * Bookkeeping for a source where this scraper only ever sees a SUBSET of what
     * the board lists, so absence proves nothing and nothing is ever closed.
     *
     * Two cases need this. Workday and SmartRecruiters apply the title filter while
     * paging, so a posting renamed to "Senior Engineer" simply stops coming back,
     * which is not the same as it closing. Magnet.me works from a sitemap spanning
     * many companies at once, with no per-company listing to compare against, and
     * passes null for the company.
     */
    protected CompanyScrape beginPartialListing(Connection conn, String company) {
        return new CompanyScrape(conn, sourceName(), company, false);
    }
}
