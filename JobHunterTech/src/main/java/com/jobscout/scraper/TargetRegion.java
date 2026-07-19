package com.jobscout.scraper;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Geographic scope shared by every scraper: Europe + United States. Wide net on
 * purpose -- the idea is to surface any decent opportunity worth flying/relocating
 * for, not just same-country roles.
 */
public final class TargetRegion {
    private static final Set<String> EUROPEAN_COUNTRIES = Set.of(
            "albania", "andorra", "austria", "belarus", "belgium", "bosnia and herzegovina",
            "bulgaria", "croatia", "cyprus", "czech republic", "czechia", "denmark", "estonia",
            "finland", "france", "germany", "greece", "hungary", "iceland", "ireland", "italy",
            "kosovo", "latvia", "liechtenstein", "lithuania", "luxembourg", "malta", "moldova",
            "monaco", "montenegro", "netherlands", "north macedonia", "norway", "poland",
            "portugal", "romania", "san marino", "serbia", "slovakia", "slovenia", "spain",
            "sweden", "switzerland", "ukraine", "united kingdom", "vatican city");

    private static final Set<String> US_NAMES = Set.of(
            "united states", "united states of america", "usa", "u.s.", "u.s.a.");

    // ISO 3166-1 alpha-2 codes -- Lever's "country" field uses these (e.g. "GB",
    // "US"), not full names like Workday/Ashby do.
    private static final Set<String> IN_SCOPE_COUNTRY_CODES = Set.of(
            "al", "ad", "at", "by", "be", "ba", "bg", "hr", "cy", "cz", "dk", "ee", "fi", "fr",
            "de", "gr", "hu", "is", "ie", "it", "xk", "lv", "li", "lt", "lu", "mt", "md", "mc",
            "me", "nl", "mk", "no", "pl", "pt", "ro", "sm", "rs", "sk", "si", "es", "se", "ch",
            "ua", "gb", "va", "us");

    // Greenhouse (and likely other ATS platforms) format US locations as "City, ST"
    // -- e.g. "San Francisco, CA", "New York City, NY" -- without spelling out
    // "United States" at all. Matched only right after a comma to avoid false
    // positives from unrelated two-letter substrings.
    private static final Pattern US_STATE_ABBREVIATION_PATTERN = Pattern.compile(
            ",\\s*(?:AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO"
                    + "|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY|DC)\\b");

    private TargetRegion() {
    }

    /** Exact match against a structured country field (e.g. Workday's country.descriptor). */
    public static boolean isInScope(String countryName) {
        if (countryName == null || countryName.isBlank()) {
            return false;
        }
        String normalized = countryName.toLowerCase(Locale.ROOT).strip();
        return EUROPEAN_COUNTRIES.contains(normalized) || US_NAMES.contains(normalized);
    }

    /** Match against a structured 2-letter ISO 3166-1 alpha-2 country code, e.g. Lever's "country" field. */
    public static boolean isInScopeByCountryCode(String isoCode) {
        if (isoCode == null || isoCode.isBlank()) {
            return false;
        }
        return IN_SCOPE_COUNTRY_CODES.contains(isoCode.toLowerCase(Locale.ROOT).strip());
    }

    /**
     * Substring search against free-text location strings, e.g. "Austin, Texas,
     * United States" or Greenhouse's "San Francisco, CA".
     */
    public static boolean textMentionsTargetRegion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (US_STATE_ABBREVIATION_PATTERN.matcher(text).find()) {
            return true;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String country : EUROPEAN_COUNTRIES) {
            if (normalized.contains(country)) {
                return true;
            }
        }
        for (String usName : US_NAMES) {
            if (normalized.contains(usName)) {
                return true;
            }
        }
        return false;
    }
}
