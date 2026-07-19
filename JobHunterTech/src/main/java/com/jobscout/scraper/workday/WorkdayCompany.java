package com.jobscout.scraper.workday;

/**
 * One Workday-hosted company's career site config. `site` is the career-site slug
 * in the URL (usually, but not always, the same as `tenant`) -- find both by
 * visiting the company's careers page and reading its URL, e.g.
 * https://{host}/en-US/{site} and the API host itself gives the tenant.
 */
public record WorkdayCompany(String company, String host, String tenant, String site) {
}
