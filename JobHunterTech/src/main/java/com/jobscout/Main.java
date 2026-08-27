package com.jobscout;

import com.jobscout.db.SchemaInitializer;
import com.jobscout.scraper.HttpFetcher;
import com.jobscout.scraper.JdkHttpFetcher;
import com.jobscout.scraper.ashby.AshbyScraper;
import com.jobscout.scraper.greenhouse.GreenhouseScraper;
import com.jobscout.scraper.lever.LeverScraper;
import com.jobscout.scraper.magnetme.MagnetMeScraper;
import com.jobscout.scraper.smartrecruiters.SmartRecruitersScraper;
import com.jobscout.scraper.workday.WorkdayScraper;
import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Entry point: init the shared SQLite database, then run each scraper against it.
 *
 * The scrapers run in parallel, which is worth explaining because it looks like it
 * should breach the politeness rules and does not. Pacing lives in HostRateLimiter
 * inside the one shared HttpFetcher, and it is per host: two scrapers overlapping
 * still cannot make any single server see requests closer together than the
 * configured delay. What parallelism removes is the pointless part of the old
 * behaviour, where a slow Workday tenant held up a Greenhouse fetch aimed at a
 * completely different machine.
 *
 * Each scraper gets its own database connection, since a JDBC Connection is not
 * safe to share across threads. SchemaInitializer.openConnection puts the database
 * in WAL mode with a generous busy timeout so concurrent writes queue instead of
 * failing.
 *
 * Set SCRAPER_PARALLELISM=1 to go back to running them one at a time, which makes
 * the interleaved log output much easier to read while debugging a single scraper.
 */
public final class Main {
    private Main() {
    }

    /** One scraper's work, given a connection of its own. */
    @FunctionalInterface
    private interface ScraperJob {
        int run(Connection conn);
    }

    public static void main(String[] args) throws SQLException {
        SleepPrevention.preventSleepWhileRunning();

        // .env lives at the repo root (shared with the Python ranking code), one
        // directory up from this Gradle project. systemProperties() makes its values
        // readable via System.getProperty(), which JdkHttpFetcher also checks -- Java
        // can't inject into System.getenv() the way python-dotenv can.
        Dotenv dotenv = Dotenv.configure().directory("..").ignoreIfMissing().systemProperties().load();

        String databasePath = Path.of("..", dotenv.get("DATABASE_PATH", "data/job_scout.db"))
                .normalize()
                .toString();
        SchemaInitializer.initDb(databasePath);

        // One fetcher for everyone: its rate limiter is the single place that knows
        // when each host was last contacted.
        HttpFetcher fetcher = new JdkHttpFetcher();

        Map<String, ScraperJob> jobs = new LinkedHashMap<>();
        jobs.put("Workday", conn -> new WorkdayScraper(fetcher).run(conn));
        jobs.put("Greenhouse", conn -> new GreenhouseScraper(fetcher).run(conn));
        jobs.put("Ashby", conn -> new AshbyScraper(fetcher).run(conn));
        jobs.put("Lever", conn -> new LeverScraper(fetcher).run(conn));
        jobs.put("SmartRecruiters", conn -> new SmartRecruitersScraper(fetcher).run(conn));
        jobs.put("Magnet.me", conn -> new MagnetMeScraper(fetcher).run(conn));

        // SCRAPER_SOURCES narrows the run to a comma-separated subset, matched on the
        // labels above, case-insensitively. Useful when one board is misbehaving and
        // you want to iterate on it without waiting out the other five, and for
        // smoke-testing a change without pulling a whole sitemap.
        String only = env("SCRAPER_SOURCES", "");
        if (!only.isBlank()) {
            List<String> wanted = Arrays.stream(only.split(","))
                    .map(s -> s.strip().toLowerCase(Locale.ROOT))
                    .filter(s -> !s.isEmpty())
                    .toList();
            jobs.keySet().removeIf(label -> !wanted.contains(label.toLowerCase(Locale.ROOT)));
            if (jobs.isEmpty()) {
                throw new IllegalStateException("SCRAPER_SOURCES='" + only + "' matched no scrapers. "
                        + "Known names: Workday, Greenhouse, Ashby, Lever, SmartRecruiters, Magnet.me");
            }
        }

        int parallelism = Math.max(1, Integer.parseInt(env("SCRAPER_PARALLELISM", "4")));
        System.out.println("Running " + jobs.size() + " scrapers with parallelism " + parallelism
                + " (per-host pacing still applies)");

        runAll(jobs, databasePath, parallelism);
    }

    private static void runAll(Map<String, ScraperJob> jobs, String databasePath, int parallelism) {
        List<Callable<String>> tasks = new ArrayList<>();
        jobs.forEach((label, job) -> tasks.add(() -> {
            // A JDBC Connection is not thread-safe, so each scraper opens its own.
            try (Connection conn = SchemaInitializer.openConnection(databasePath)) {
                return "Upserted " + job.run(conn) + " vacancies from " + label + ".";
            }
        }));

        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, tasks.size()))) {
            List<Future<String>> results = new ArrayList<>();
            for (Callable<String> task : tasks) {
                results.add(pool.submit(task));
            }

            List<String> labels = new ArrayList<>(jobs.keySet());
            System.out.println("\n=== Scrape summary ===");
            for (int i = 0; i < results.size(); i++) {
                try {
                    System.out.println("  " + results.get(i).get());
                } catch (ExecutionException exc) {
                    // One scraper blowing up must not cost the results of the other five,
                    // which have already written their postings to the database.
                    System.err.println("  " + labels.get(i) + " failed: " + exc.getCause());
                } catch (InterruptedException exc) {
                    Thread.currentThread().interrupt();
                    System.err.println("  Interrupted while waiting for " + labels.get(i));
                    return;
                }
            }
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
