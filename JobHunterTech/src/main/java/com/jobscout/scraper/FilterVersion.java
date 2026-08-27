package com.jobscout.scraper;

/**
 * Version stamp for the scrape-time filters: ScraperPatterns, SeniorityFilter and
 * TargetRegion.
 *
 * Why this exists. seen_postings records that a posting was already evaluated, so
 * two-step scrapers can skip re-fetching its detail page on every rerun. Without a
 * version that record is permanent AND blind to why the posting was rejected:
 * loosen SeniorityFilter and the thousands of postings it already turned down stay
 * invisible forever, because they are still "seen". At the time this was added,
 * 2413 postings sat in exactly that state.
 *
 * BUMP THIS whenever you change what the scrape-time filters accept. Old records
 * then stop matching the current version, so every previously rejected posting is
 * re-evaluated exactly once against the new rules and re-marked at the new version.
 * Leaving it alone keeps the existing skip behavior, so a bump is a deliberate
 * "re-check everything I said no to", not an accident.
 *
 * Be aware a bump costs a full detail-fetch pass over previously rejected postings
 * on the next run, which is slow but rate-limited and idempotent.
 *
 * History:
 *   1 -- initial version, covering the filters as they stood in August 2026.
 */
public final class FilterVersion {
    public static final int CURRENT = 1;

    private FilterVersion() {
    }
}
