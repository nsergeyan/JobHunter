package com.jobscout.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobscout.extraction.VacancyExtraction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ExtractionRepository {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FIND_UNEXTRACTED_SQL = """
            SELECT id, raw_text FROM vacancies
            WHERE raw_text IS NOT NULL AND raw_text != ''
              AND id NOT IN (SELECT vacancy_id FROM vacancy_extractions)
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO vacancy_extractions
                (vacancy_id, summary, skills, seniority, salary_min, salary_max, salary_currency, salary_period,
                 language_requirement, remote_policy, extraction_model, extracted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (vacancy_id) DO UPDATE SET
                summary = excluded.summary,
                skills = excluded.skills,
                seniority = excluded.seniority,
                salary_min = excluded.salary_min,
                salary_max = excluded.salary_max,
                salary_currency = excluded.salary_currency,
                salary_period = excluded.salary_period,
                language_requirement = excluded.language_requirement,
                remote_policy = excluded.remote_policy,
                extraction_model = excluded.extraction_model,
                extracted_at = excluded.extracted_at
            """;

    private ExtractionRepository() {
    }

    public static List<VacancyToExtract> findUnextractedVacancies(Connection conn) {
        List<VacancyToExtract> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(FIND_UNEXTRACTED_SQL);
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(new VacancyToExtract(rs.getLong("id"), rs.getString("raw_text")));
            }
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to query unextracted vacancies", exc);
        }
        return result;
    }

    public static void saveExtraction(Connection conn, long vacancyId, VacancyExtraction extraction) {
        String skillsJson;
        try {
            skillsJson = MAPPER.writeValueAsString(extraction.skills());
        } catch (JsonProcessingException exc) {
            throw new RuntimeException("Failed to serialize skills for vacancy " + vacancyId, exc);
        }

        try (PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
            stmt.setLong(1, vacancyId);
            stmt.setString(2, extraction.summary());
            stmt.setString(3, skillsJson);
            stmt.setString(4, extraction.seniority());
            setNullableInt(stmt, 5, extraction.salaryMin());
            setNullableInt(stmt, 6, extraction.salaryMax());
            stmt.setString(7, extraction.salaryCurrency());
            stmt.setString(8, extraction.salaryPeriod());
            stmt.setString(9, extraction.languageRequirement());
            stmt.setString(10, extraction.remotePolicy());
            stmt.setString(11, extraction.model());
            stmt.setString(12, Instant.now().toString());
            stmt.executeUpdate();
        } catch (SQLException exc) {
            throw new RuntimeException("Failed to save extraction for vacancy " + vacancyId, exc);
        }
    }

    private static void setNullableInt(PreparedStatement stmt, int index, Integer value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, Types.INTEGER);
        } else {
            stmt.setInt(index, value);
        }
    }
}
