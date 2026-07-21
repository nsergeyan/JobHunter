package com.jobscout;

import com.jobscout.db.SchemaInitializer;
import com.jobscout.scraper.HttpFetcher;
import com.jobscout.scraper.JdkHttpFetcher;
import com.jobscout.scraper.magnetme.MagnetMeScraper;
import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Scratch entry point: exercises only the Magnet.me scraper against the real DB. */
public final class RunMagnetOnly {
    private RunMagnetOnly() {
    }

    public static void main(String[] args) throws SQLException {
        Dotenv dotenv = Dotenv.configure().directory("..").ignoreIfMissing().systemProperties().load();

        String databasePath = Path.of("..", dotenv.get("DATABASE_PATH", "data/job_scout.db"))
                .normalize()
                .toString();
        SchemaInitializer.initDb(databasePath);

        HttpFetcher fetcher = new JdkHttpFetcher();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            int magnetMeCount = new MagnetMeScraper(fetcher).run(conn);
            System.out.println("Upserted " + magnetMeCount + " vacancies from Magnet.me.");
        }
    }
}
