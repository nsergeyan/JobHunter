package com.jobscout.scraper.smartrecruiters;

/**
 * One SmartRecruiters-hosted company's job board config. `companyIdentifier` is
 * the slug in api.smartrecruiters.com/v1/companies/{companyIdentifier}/postings
 * -- verify with a real totalFound count, since a plausible-looking slug can
 * silently resolve to an unrelated company (or return 0 with no error).
 */
public record SmartRecruitersCompany(String company, String companyIdentifier) {
}
