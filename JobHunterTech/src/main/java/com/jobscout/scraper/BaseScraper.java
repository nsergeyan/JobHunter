package com.jobscout.scraper;

/** Shared base for scrapers: holds the HttpFetcher (real or fake) subclasses fetch through. */
public abstract class BaseScraper {
    protected final HttpFetcher fetcher;

    protected BaseScraper(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public abstract String sourceName();
}
