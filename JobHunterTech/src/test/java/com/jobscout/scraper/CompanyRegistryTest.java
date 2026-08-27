package com.jobscout.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobscout.scraper.ashby.AshbyScraper;
import com.jobscout.scraper.greenhouse.GreenhouseCompany;
import com.jobscout.scraper.greenhouse.GreenhouseScraper;
import com.jobscout.scraper.lever.LeverScraper;
import com.jobscout.scraper.smartrecruiters.SmartRecruitersScraper;
import com.jobscout.scraper.workday.WorkdayScraper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the real config file. These company lists used to be Java source, where a
 * typo was a compile error -- now they are data, so the failure mode moved from
 * "will not build" to "one company out of 140 quietly stops being scraped".
 */
class CompanyRegistryTest {

    @Test
    void everyPlatformHasCompaniesConfigured() {
        // Reads config/companies.json as it actually ships, not a fixture: the point
        // is to catch a malformed edit to the real file.
        for (String platform : List.of("greenhouse", "workday", "ashby", "lever", "smartrecruiters")) {
            List<String> names = CompanyRegistry.load(platform, entry ->
                    CompanyRegistry.requiredField(entry, "company"));
            assertFalse(names.isEmpty(), platform + " has no companies configured");
        }
    }

    @Test
    void everyScraperCanBuildItselfFromTheConfig() {
        // Each no-arg constructor now reads the config, so a missing identifier field
        // for any one company surfaces here rather than mid-scrape.
        // Never actually called: constructing the scraper is what reads the config.
        FakeHttpFetcher fetcher = new FakeHttpFetcher((url, body) -> {
            throw new AssertionError("no request should be made while building a scraper");
        });
        assertDoesNotThrow(() -> new GreenhouseScraper(fetcher));
        assertDoesNotThrow(() -> new WorkdayScraper(fetcher));
        assertDoesNotThrow(() -> new AshbyScraper(fetcher));
        assertDoesNotThrow(() -> new LeverScraper(fetcher));
        assertDoesNotThrow(() -> new SmartRecruitersScraper(fetcher));
    }

    @Test
    void companyNamesAreUnique() {
        // A duplicated entry means the same board is fetched twice, spending rate
        // limit for nothing.
        List<String> names = CompanyRegistry.load("greenhouse", entry ->
                CompanyRegistry.requiredField(entry, "company"));
        assertEquals(names.size(), names.stream().distinct().count(),
                "duplicate company names in the greenhouse config: " + names);
    }

    @Test
    void unknownPlatformFailsLoudly() {
        ScraperException exc = assertThrows(ScraperException.class,
                () -> CompanyRegistry.load("monster.com", entry -> entry));
        assertTrue(exc.getMessage().contains("monster.com"));
    }

    @Test
    void missingRequiredFieldNamesTheOffendingEntry() {
        ObjectNode entry = new ObjectMapper().createObjectNode();
        entry.put("company", "Acme");

        ScraperException exc = assertThrows(ScraperException.class,
                () -> CompanyRegistry.requiredField(entry, "boardToken"));
        assertTrue(exc.getMessage().contains("boardToken"), "the message should name the missing field");
        assertTrue(exc.getMessage().contains("Acme"), "and show the entry it came from");
    }

    @Test
    void greenhouseCompaniesKeepTheirBoardTokens() {
        // Spot-check that the extraction from Java source into JSON preserved pairs
        // rather than shifting fields, which would silently scrape the wrong boards.
        List<GreenhouseCompany> companies = CompanyRegistry.load("greenhouse", entry ->
                new GreenhouseCompany(CompanyRegistry.requiredField(entry, "company"),
                        CompanyRegistry.requiredField(entry, "boardToken")));

        assertTrue(companies.contains(new GreenhouseCompany("Anthropic", "anthropic")));
        assertTrue(companies.contains(new GreenhouseCompany("DoorDash", "doordashusa")),
                "the token differs from the display name here, which is exactly the case worth pinning");
    }
}
