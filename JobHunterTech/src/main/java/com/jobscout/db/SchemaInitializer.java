package com.jobscout.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates the SQLite database from the bundled schema.sql resource. Loaded via the
 * classpath (not a relative file path) so it works regardless of the working
 * directory a jar is launched from -- the idiomatic Java approach, unlike Python's
 * Path(__file__).parent pattern.
 */
public final class SchemaInitializer {
    private SchemaInitializer() {
    }

    public static void initDb(String dbPath) {
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exc) {
            throw new UncheckedIOException("Could not create parent directory for " + dbPath, exc);
        }

        String schemaSql = readSchemaResource();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement stmt = conn.createStatement()) {
            for (String statement : schemaSql.split(";")) {
                String trimmed = statement.strip();
                if (!trimmed.isEmpty()) {
                    stmt.executeUpdate(trimmed);
                }
            }
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to initialize database at " + dbPath, exc);
        }
    }

    private static String readSchemaResource() {
        try (InputStream in = SchemaInitializer.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not found on classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new UncheckedIOException("Failed to read schema.sql", exc);
        }
    }
}
