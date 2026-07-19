package com.jobscout;

import com.jobscout.db.SchemaInitializer;
import com.jobscout.scraper.HttpFetcher;
import com.jobscout.scraper.JdkHttpFetcher;
import com.jobscout.scraper.MagnetMeScraper;
import com.jobscout.scraper.StudentJobScraper;
import com.jobscout.scraper.workday.WorkdayScraper;
import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Entry point: init the shared SQLite database, then run each scraper against it. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws SQLException {
        // .env lives at the repo root (shared with the future Python ranking-model
        // code), one directory up from this Gradle project. systemProperties() makes
        // its values readable via System.getProperty(), which JdkHttpFetcher also
        // checks -- Java can't inject into System.getenv() the way python-dotenv can.
        Dotenv dotenv = Dotenv.configure().directory("..").ignoreIfMissing().systemProperties().load();

        String databasePath = Path.of("..", dotenv.get("DATABASE_PATH", "data/job_scout.db"))
                .normalize()
                .toString();
        SchemaInitializer.initDb(databasePath);

        HttpFetcher fetcher = new JdkHttpFetcher();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            int workdayCount = new WorkdayScraper(fetcher).run(conn);
            System.out.println("Upserted " + workdayCount + " vacancies from Workday-hosted companies.");

            int studentJobCount = new StudentJobScraper(fetcher).run(conn);
            System.out.println("Upserted " + studentJobCount + " vacancies from StudentJob.nl.");

            int magnetMeCount = new MagnetMeScraper(fetcher).run(conn);
            System.out.println("Upserted " + magnetMeCount + " vacancies from Magnet.me.");
        }
    }
}
