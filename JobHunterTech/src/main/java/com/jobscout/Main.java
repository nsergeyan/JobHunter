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
import java.sql.DriverManager;
import java.sql.SQLException;

/** Entry point: init the shared SQLite database, then run each scraper against it. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws SQLException {
        SleepPrevention.preventSleepWhileRunning();

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

            int greenhouseCount = new GreenhouseScraper(fetcher).run(conn);
            System.out.println("Upserted " + greenhouseCount + " vacancies from Greenhouse-hosted companies.");

            int ashbyCount = new AshbyScraper(fetcher).run(conn);
            System.out.println("Upserted " + ashbyCount + " vacancies from Ashby-hosted companies.");

            int leverCount = new LeverScraper(fetcher).run(conn);
            System.out.println("Upserted " + leverCount + " vacancies from Lever-hosted companies.");

            int smartRecruitersCount = new SmartRecruitersScraper(fetcher).run(conn);
            System.out.println("Upserted " + smartRecruitersCount + " vacancies from SmartRecruiters-hosted companies.");

            int magnetMeCount = new MagnetMeScraper(fetcher).run(conn);
            System.out.println("Upserted " + magnetMeCount + " vacancies from Magnet.me.");
        }
    }
}
