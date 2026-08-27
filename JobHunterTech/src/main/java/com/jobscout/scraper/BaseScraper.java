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
}
