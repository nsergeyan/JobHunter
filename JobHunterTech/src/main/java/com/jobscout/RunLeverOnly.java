package com.jobscout;

import com.jobscout.db.SchemaInitializer;
import com.jobscout.scraper.HttpFetcher;
import com.jobscout.scraper.JdkHttpFetcher;
import com.jobscout.scraper.lever.LeverScraper;
import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Standalone entry point: exercises only the Lever scraper against the real DB. */
public final class RunLeverOnly {
    private RunLeverOnly() {
    }

    public static void main(String[] args) throws SQLException {
        Dotenv dotenv = Dotenv.configure().directory("..").ignoreIfMissing().systemProperties().load();

        String databasePath = Path.of("..", dotenv.get("DATABASE_PATH", "data/job_scout.db"))
                .normalize()
                .toString();
        SchemaInitializer.initDb(databasePath);

        HttpFetcher fetcher = new JdkHttpFetcher();

        try (Connection conn = SchemaInitializer.openConnection(databasePath)) {
            int leverCount = new LeverScraper(fetcher).run(conn);
            System.out.println("Upserted " + leverCount + " vacancies from Lever-hosted companies.");
        }
    }
}
