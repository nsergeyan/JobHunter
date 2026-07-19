package com.jobscout.scraper;

import java.util.Locale;
import java.util.Set;

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

    /** Substring search against free-text location strings (e.g. "Austin, Texas, United States"). */
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
        for (String usName : US_NAMES) {
            if (normalized.contains(usName)) {
                return true;
            }
        }
        return false;
    }
}
