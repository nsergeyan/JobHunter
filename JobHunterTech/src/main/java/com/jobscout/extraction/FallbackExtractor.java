package com.jobscout.extraction;

/**
 * Tries a primary extractor first; if it throws, retries the same posting with a
 * secondary one. Distinct from GeminiExtractor's own key rotation (which round-robins
 * across multiple Gemini keys to spread free-tier quota) -- this falls back to a
 * different provider entirely, so a Gemini outage or exhausted quota mid-run doesn't
 * stall the whole batch waiting on it.
 */
public class FallbackExtractor implements Extractor {
    private final Extractor primary;
    private final Extractor secondary;

    public FallbackExtractor(Extractor primary, Extractor secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public VacancyExtraction extract(String rawText) {
        try {
            return primary.extract(rawText);
        } catch (RuntimeException exc) {
            System.out.println("Primary extractor failed (" + exc.getMessage() + "), falling back to secondary.");
            return secondary.extract(rawText);
        }
    }
}
