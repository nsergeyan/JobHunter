package com.jobscout.scraper;

import com.jobscout.db.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The property worth protecting is that `fetched` means the same thing for every
 * source.
 *
 * It briefly did not. Greenhouse, Ashby and Lever iterate a company's whole
 * listing, while Workday, SmartRecruiters and Magnet.me apply the title filter
 * while paging and never see the rest. Counting postings as they went by therefore
 * recorded "everything the board has" for one group and "postings worth a detail
 * fetch" for the other, under one column name. A company showing 0 could equally
 * mean a dead board or a healthy board with nothing relevant, and there was no way
 * to tell which from the data.
 */
class CompanyScrapeTest {

    private static int fetchedFor(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT fetched, accepted, rejected FROM scrape_runs")) {
            rs.next();
            return rs.getInt("fetched");
        }
    }

    @Test
    void fetchedReportsTheWholeBoardNotJustWhatWasEvaluated(@TempDir Path tmpDir) throws SQLException {
        String dbPath = tmpDir.resolve("test.db").toString();
        SchemaInitializer.initDb(dbPath);

        try (Connection conn = SchemaInitializer.openConnection(dbPath)) {
            // A board with 500 postings, of which only 3 were worth evaluating.
            try (CompanyScrape scrape = new CompanyScrape(conn, "workday", "Acme", false)) {
                scrape.boardReturned(500);
                for (int i = 0; i < 3; i++) {
                    scrape.listed("ext-" + i);
                }
                scrape.stored();
                scrape.filteredOut();
                scrape.listingComplete();
            }
            assertEquals(500, fetchedFor(conn),
                    "fetched must describe the board, not how many postings this scraper looked at");
        }
    }

    @Test
    void anEmptyBoardIsDistinguishableFromAQuietOne(@TempDir Path tmpDir) throws SQLException {
        String dbPath = tmpDir.resolve("test.db").toString();
        SchemaInitializer.initDb(dbPath);

        try (Connection conn = SchemaInitializer.openConnection(dbPath)) {
            // Nothing on the board at all: a wrong token, or a closed company.
            try (CompanyScrape scrape = new CompanyScrape(conn, "ashby", "Broken", false)) {
                scrape.boardReturned(0);
                scrape.listingComplete();
            }
            assertEquals(0, fetchedFor(conn));
        }

        String other = tmpDir.resolve("other.db").toString();
        SchemaInitializer.initDb(other);
        try (Connection conn = SchemaInitializer.openConnection(other)) {
            // A working board where nothing happened to match the filters. Same
            // accepted count, very different fetched, which is the whole point.
            try (CompanyScrape scrape = new CompanyScrape(conn, "ashby", "Quiet", false)) {
                scrape.boardReturned(240);
                scrape.listingComplete();
            }
            assertEquals(240, fetchedFor(conn));
        }
    }
}
