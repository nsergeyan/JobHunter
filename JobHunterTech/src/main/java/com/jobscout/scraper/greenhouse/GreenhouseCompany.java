package com.jobscout.scraper.greenhouse;

/**
 * One Greenhouse-hosted company's job board config. `boardToken` is the slug in
 * boards-api.greenhouse.io/v1/boards/{boardToken}/jobs -- usually but not always
 * the same as the company's name (verify by hitting the API directly, don't guess).
 */
public record GreenhouseCompany(String company, String boardToken) {
}
