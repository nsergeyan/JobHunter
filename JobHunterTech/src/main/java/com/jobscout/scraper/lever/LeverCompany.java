package com.jobscout.scraper.lever;

/**
 * One Lever-hosted company's job board config. `siteToken` is the slug in
 * api.lever.co/v0/postings/{siteToken} -- verify by hitting the API directly.
 */
public record LeverCompany(String company, String siteToken) {
}
