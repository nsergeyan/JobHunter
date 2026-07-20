package com.jobscout;

import com.jobscout.db.ExtractionRepository;
import com.jobscout.db.SchemaInitializer;
import com.jobscout.db.VacancyToExtract;
import com.jobscout.extraction.Extractor;
import com.jobscout.extraction.GeminiExtractor;
import com.jobscout.extraction.OllamaExtractor;
import com.jobscout.extraction.VacancyExtraction;
import com.jobscout.scraper.HttpFetcher;
import com.jobscout.scraper.JdkHttpFetcher;
import io.github.cdimascio.dotenv.Dotenv;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the LLM extraction step (build-order step 2): pulls structured
 * fields (skills, seniority, salary, language requirement, remote policy) out of
 * every scraped vacancy that hasn't been extracted yet.
 *
 * EXTRACTION_PROVIDER picks Gemini (API, rate-limited, needs a key) or Ollama (local,
 * unlimited, needs `ollama serve` running) -- both implement the same Extractor
 * interface and write the same VacancyExtraction shape, so the rest of this class
 * doesn't care which one is active.
 *
 * Run this after Main (the scrapers). It's a separate, idempotent pass -- rerunning
 * only processes vacancies missing from vacancy_extractions, so partial failures or
 * newly scraped postings are picked up cheaply on the next run.
 */
public final class ExtractionMain {
    private ExtractionMain() {
    }

    public static void main(String[] args) throws SQLException {
        SleepPrevention.preventSleepWhileRunning();

        Dotenv dotenv = Dotenv.configure().directory("..").ignoreIfMissing().systemProperties().load();

        String databasePath = Path.of("..", dotenv.get("DATABASE_PATH", "data/job_scout.db"))
                .normalize()
                .toString();
        SchemaInitializer.initDb(databasePath);

        String provider = env("EXTRACTION_PROVIDER", "ollama");
        String model;
        Extractor extractor = switch (provider) {
            case "ollama" -> {
                model = env("OLLAMA_MODEL", "qwen3:8b");
                HttpFetcher fetcher = new JdkHttpFetcher(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                        env("SCRAPER_USER_AGENT", "job-scout-bot/0.1 (personal project)"),
                        0, 0); // local server -- no rate-limit delay needed
                yield new OllamaExtractor(fetcher, env("OLLAMA_BASE_URL", "http://localhost:11434"), model);
            }
            case "gemini" -> {
                List<String> apiKeys = Arrays.stream(env("GEMINI_API_KEYS", "").split(","))
                        .map(String::strip)
                        .filter(key -> !key.isEmpty())
                        .toList();
                if (apiKeys.isEmpty()) {
                    throw new IllegalStateException("No Gemini API keys found. Set GEMINI_API_KEYS in .env "
                            + "(comma-separated if you have several).");
                }
                model = env("GEMINI_MODEL", "gemini-2.5-flash");
                HttpFetcher fetcher = new JdkHttpFetcher(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                        env("SCRAPER_USER_AGENT", "job-scout-bot/0.1 (personal project)"),
                        Double.parseDouble(env("GEMINI_MIN_DELAY_SECONDS", "4")),
                        Double.parseDouble(env("GEMINI_MAX_DELAY_SECONDS", "8")));
                yield new GeminiExtractor(fetcher, apiKeys, model);
            }
            default -> throw new IllegalStateException(
                    "Unknown EXTRACTION_PROVIDER '" + provider + "' -- expected 'ollama' or 'gemini'.");
        };

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            List<VacancyToExtract> pending = ExtractionRepository.findUnextractedVacancies(conn);
            System.out.println("Found " + pending.size() + " vacancies to extract (provider=" + provider
                    + ", model=" + model + ").");

            int succeeded = 0;
            int failed = 0;
            for (VacancyToExtract vacancy : pending) {
                try {
                    VacancyExtraction extraction = extractor.extract(vacancy.rawText());
                    ExtractionRepository.saveExtraction(conn, vacancy.id(), extraction);
                    succeeded++;
                    System.out.println("Extracted vacancy " + vacancy.id() + ": " + extraction.seniority()
                            + ", " + extraction.remotePolicy() + ", " + extraction.skills().size() + " skills"
                            + " (" + succeeded + "/" + pending.size() + ")");
                } catch (RuntimeException exc) {
                    // Covers both ExtractionException (bad/unparseable response) and
                    // ScraperException (HTTP failure, e.g. rate limit) from the shared
                    // JdkHttpFetcher -- one bad posting shouldn't abort the whole batch.
                    System.err.println("Failed to extract vacancy " + vacancy.id() + ": " + exc.getMessage());
                    failed++;
                }
            }
            System.out.println("Extraction complete: " + succeeded + " succeeded, " + failed + " failed.");
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
