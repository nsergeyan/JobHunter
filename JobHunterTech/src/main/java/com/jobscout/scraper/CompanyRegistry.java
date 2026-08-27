package com.jobscout.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Loads the per-platform company lists from config/companies.json.
 *
 * Why they left the source. Roughly 140 companies were hardcoded across five
 * scraper classes, so adding one meant editing Java and recompiling. They are
 * data, not behaviour: the list changes far more often than the code that reads
 * it, and keeping them in a file means a company can be added, or temporarily
 * commented out when its board breaks, without touching the build.
 *
 * The file lives at the repo root rather than in src/main/resources so it is not
 * baked into the jar. Override the location with COMPANIES_PATH in .env.
 *
 * Parsed once and cached: five scrapers each ask for their own slice, and there
 * is no reason to read the same file five times.
 */
public final class CompanyRegistry {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_PATH = "config/companies.json";

    private static JsonNode cached;

    private CompanyRegistry() {
    }

    /**
     * Every configured company for one platform, mapped into that platform's own
     * record type.
     *
     * @param platform top-level key in companies.json, e.g. "greenhouse"
     * @param mapper   turns one JSON entry into the scraper's company record
     */
    public static <T> List<T> load(String platform, Function<JsonNode, T> mapper) {
        JsonNode entries = root().path(platform);
        if (!entries.isArray() || entries.isEmpty()) {
            throw new ScraperException("No companies configured for platform '" + platform + "' in "
                    + configPath().toAbsolutePath());
        }
        List<T> companies = new ArrayList<>();
        for (JsonNode entry : entries) {
            companies.add(mapper.apply(entry));
        }
        return List.copyOf(companies);
    }

    /**
     * Read a required string field, failing loudly with the offending entry.
     *
     * A typo'd key would otherwise yield a company whose board token is null,
     * which does not fail here at all: it fails much later as a puzzling 404
     * against a URL with "null" in it, on one company out of 140.
     */
    public static String requiredField(JsonNode entry, String field) {
        JsonNode value = entry.path(field);
        if (value.isMissingNode() || value.asText("").isBlank()) {
            throw new ScraperException("Company entry " + entry + " in " + configPath().toAbsolutePath()
                    + " is missing required field '" + field + "'");
        }
        return value.asText();
    }

    private static synchronized JsonNode root() {
        if (cached == null) {
            Path path = configPath();
            try {
                cached = MAPPER.readTree(Files.readString(path));
            } catch (IOException exc) {
                throw new ScraperException("Could not read company config at " + path.toAbsolutePath()
                        + " -- set COMPANIES_PATH in .env if it lives elsewhere", exc);
            }
        }
        return cached;
    }

    private static Path configPath() {
        // The Java project runs with its own directory as the working directory, so
        // the repo root that holds the config is one level up -- same convention as
        // DATABASE_PATH in Main.
        return Path.of("..", env("COMPANIES_PATH", DEFAULT_PATH)).normalize();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
