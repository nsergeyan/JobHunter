package com.jobscout.scraper;

import com.jobscout.db.ScrapeRunRepository;
import com.jobscout.db.VacancyRepository;

import java.sql.Connection;
import java.time.Instant;

/**
 * Bookkeeping for one company's slice of a scrape: counts what the board
 * returned, records the outcome in scrape_runs, and closes postings that have
 * disappeared from the board.
 *
 * Used as a try-with-resources block around each company's loop, so the row gets
 * written on every path out, including the early `continue` when a fetch fails.
 *
 * The closing rule is deliberately cautious. Postings are only closed when the
 * board's listing was read in full and without error (see listingComplete), so a
 * timeout or a 500 leaves everything alone rather than declaring a whole company's
 * jobs dead. Within a successful listing, every posting the board returned gets
 * its last_seen refreshed even if the filters reject it, so "no longer matches
 * what I am looking for" never gets mistaken for "no longer exists".
 */
public final class CompanyScrape implements AutoCloseable {
    private final Connection conn;
    private final String source;
    private final String company;
    private final String startedAt;
    private final boolean closesStalePostings;

    private int fetched;
    private int accepted;
    private int rejected;
    private String error;
    private boolean listingComplete;

    CompanyScrape(Connection conn, String source, String company, boolean closesStalePostings) {
        this.conn = conn;
        this.source = source;
        this.company = company;
        this.closesStalePostings = closesStalePostings;
        this.startedAt = Instant.now().toString();
    }

    /** A posting the board returned, before any filtering. */
    public void listed(String externalId) {
        fetched++;
        VacancyRepository.touchLastSeen(conn, source, externalId);
    }

    /** Passed every filter and was stored. */
    public void stored() {
        accepted++;
    }

    /** Returned by the board but filtered out. Still counts as seen, never as gone. */
    public void filteredOut() {
        rejected++;
    }

    /** The board could not be read. Suppresses closing for this company. */
    public void failed(String message) {
        this.error = message;
    }

    /** The whole listing was read successfully, so absent postings really are absent. */
    public void listingComplete() {
        this.listingComplete = true;
    }

    @Override
    public void close() {
        if (closesStalePostings && listingComplete && error == null) {
            int closed = VacancyRepository.closeStale(conn, source, company, startedAt);
            if (closed > 0) {
                System.out.println("  " + company + ": " + closed + " posting(s) no longer listed, marked closed");
            }
        }
        ScrapeRunRepository.record(conn, source, company, startedAt, Instant.now().toString(),
                fetched, accepted, rejected, error);
    }
}
