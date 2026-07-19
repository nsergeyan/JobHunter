package com.jobscout.scraper.ashby;

/**
 * One Ashby-hosted company's job board config. `boardName` is the slug in
 * api.ashbyhq.com/posting-api/job-board/{boardName} -- verify by hitting the API
 * directly (Ashby's own careers pages return 200 for any slug regardless of
 * whether it's real, so the HTML page alone doesn't confirm existence).
 */
public record AshbyCompany(String company, String boardName) {
}
