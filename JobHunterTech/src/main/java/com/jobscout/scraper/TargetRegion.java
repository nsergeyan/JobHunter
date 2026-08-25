package com.jobscout.scraper;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Geographic scope shared by every scraper: Europe only.
 *
 * Previously Europe + United States (see git history around 2026-07-20 if that
 * scope is ever worth revisiting) -- narrowed to Europe-only given how difficult
 * visa sponsorship has become for non-US candidates in US tech roles, regardless
 * of whether a company nominally offers it. Same target companies (Oracle,
 * Google, etc.) are still in scope -- just their EU-based postings, not US ones.
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

    // The UK is the one country ATS boards rarely name in full. Greenhouse
    // locations read "London, England" (Figma) or "London, UK" (Anthropic, Monzo,
    // GoCardless), so matching only on "united kingdom" silently dropped every one
    // of them as out of scope -- 112 postings across 16 of the configured boards
    // when this was found.
    private static final Set<String> UK_SUBDIVISIONS = Set.of(
            "england", "scotland", "wales", "northern ireland", "great britain");

    // "uk" has to match as a whole word, not a substring: "Fukuoka" and "Tsukuba"
    // both contain the letters "uk" and are emphatically not in scope.
    private static final Pattern UK_ABBREVIATION = Pattern.compile("\\buk\\b");

    // ISO 3166-1 alpha-2 codes -- Lever's "country" field uses these (e.g. "GB"),
    // not full names like Workday/Ashby do.
    private static final Set<String> IN_SCOPE_COUNTRY_CODES = Set.of(
            "al", "ad", "at", "by", "be", "ba", "bg", "hr", "cy", "cz", "dk", "ee", "fi", "fr",
            "de", "gr", "hu", "is", "ie", "it", "xk", "lv", "li", "lt", "lu", "mt", "md", "mc",
            "me", "nl", "mk", "no", "pl", "pt", "ro", "sm", "rs", "sk", "si", "es", "se", "ch",
            "ua", "gb", "va");

    private TargetRegion() {
    }

    /** Exact match against a structured country field (e.g. Workday's country.descriptor). */
    public static boolean isInScope(String countryName) {
        if (countryName == null || countryName.isBlank()) {
            return false;
        }
        String normalized = countryName.toLowerCase(Locale.ROOT).strip();
        return EUROPEAN_COUNTRIES.contains(normalized) || UK_SUBDIVISIONS.contains(normalized);
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
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String country : EUROPEAN_COUNTRIES) {
            if (normalized.contains(country)) {
                return true;
            }
        }
        for (String subdivision : UK_SUBDIVISIONS) {
            if (normalized.contains(subdivision)) {
                return true;
            }
        }
        return UK_ABBREVIATION.matcher(normalized).find();
    }
}
