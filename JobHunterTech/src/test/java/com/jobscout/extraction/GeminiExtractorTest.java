package com.jobscout.extraction;

import com.jobscout.scraper.FakeHttpFetcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiExtractorTest {

    private static final String ENDPOINT_PREFIX =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";

    private static String generateContentResponse(String modelOutputJsonEscaped) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\""
                + modelOutputJsonEscaped + "\"}]}}]}";
    }

    @Test
    void extractParsesStructuredFieldsFromCandidateText() {
        String modelOutput = "{\\\"skills\\\":[\\\"Python\\\",\\\"SQL\\\"],\\\"seniority\\\":\\\"junior\\\","
                + "\\\"salary_min\\\":50000,\\\"salary_max\\\":65000,\\\"salary_currency\\\":\\\"USD\\\","
                + "\\\"salary_period\\\":\\\"yearly\\\","
                + "\\\"language_requirement\\\":\\\"English\\\",\\\"remote_policy\\\":\\\"remote\\\"}";
        FakeHttpFetcher fetcher = new FakeHttpFetcher((url, body) -> {
            assertEquals(ENDPOINT_PREFIX + "test-key", url);
            return generateContentResponse(modelOutput);
        });
        GeminiExtractor extractor = new GeminiExtractor(fetcher, List.of("test-key"), "gemini-2.5-flash");

        VacancyExtraction result = extractor.extract("We need a junior Python engineer, remote, $50k-65k USD.");

        assertEquals(List.of("Python", "SQL"), result.skills());
        assertEquals("junior", result.seniority());
        assertEquals(50000, result.salaryMin());
        assertEquals(65000, result.salaryMax());
        assertEquals("USD", result.salaryCurrency());
        assertEquals("yearly", result.salaryPeriod());
        assertEquals("English", result.languageRequirement());
        assertEquals("remote", result.remotePolicy());
        assertEquals("gemini-2.5-flash", result.model());
    }

    @Test
    void extractTreatsZeroAndEmptyPlaceholdersAsAbsent() {
        // The schema marks every field required (some Gemini SDKs ignore "optional"
        // markers), so when info is missing the model fills in 0 / "" rather than
        // omitting the key -- extractor should normalize those back to null.
        String modelOutput = "{\\\"skills\\\":[],\\\"seniority\\\":\\\"unknown\\\",\\\"salary_min\\\":0,"
                + "\\\"salary_max\\\":0,\\\"salary_currency\\\":\\\"\\\",\\\"salary_period\\\":\\\"unknown\\\","
                + "\\\"language_requirement\\\":\\\"\\\",\\\"remote_policy\\\":\\\"unknown\\\"}";
        FakeHttpFetcher fetcher = new FakeHttpFetcher((url, body) -> generateContentResponse(modelOutput));
        GeminiExtractor extractor = new GeminiExtractor(fetcher, List.of("test-key"), "gemini-2.5-flash");

        VacancyExtraction result = extractor.extract("Vague posting with no real details.");

        assertTrue(result.skills().isEmpty());
        assertEquals("unknown", result.seniority());
        assertNull(result.salaryMin());
        assertNull(result.salaryMax());
        assertNull(result.salaryCurrency());
        assertEquals("unknown", result.salaryPeriod());
        assertNull(result.languageRequirement());
    }

    @Test
    void extractRoundRobinsAcrossMultipleApiKeys() {
        List<String> requestedUrls = new java.util.ArrayList<>();
        String modelOutput = "{\\\"skills\\\":[],\\\"seniority\\\":\\\"unknown\\\",\\\"salary_min\\\":0,"
                + "\\\"salary_max\\\":0,\\\"salary_currency\\\":\\\"\\\",\\\"salary_period\\\":\\\"unknown\\\","
                + "\\\"language_requirement\\\":\\\"\\\",\\\"remote_policy\\\":\\\"unknown\\\"}";
        FakeHttpFetcher fetcher = new FakeHttpFetcher((url, body) -> {
            requestedUrls.add(url);
            return generateContentResponse(modelOutput);
        });
        GeminiExtractor extractor = new GeminiExtractor(fetcher, List.of("key-a", "key-b"), "gemini-2.5-flash");

        for (int i = 0; i < 3; i++) {
            extractor.extract("posting " + i);
        }

        assertEquals(
                List.of(ENDPOINT_PREFIX + "key-a", ENDPOINT_PREFIX + "key-b", ENDPOINT_PREFIX + "key-a"),
                requestedUrls);
    }

    @Test
    void extractThrowsExtractionExceptionWhenNoCandidateTextPresent() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher((url, body) -> "{\"candidates\":[]}");
        GeminiExtractor extractor = new GeminiExtractor(fetcher, List.of("test-key"), "gemini-2.5-flash");

        assertThrows(ExtractionException.class, () -> extractor.extract("some posting"));
    }

    @Test
    void extractThrowsExtractionExceptionWhenResponseIsNotValidJson() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher((url, body) -> "not json");
        GeminiExtractor extractor = new GeminiExtractor(fetcher, List.of("test-key"), "gemini-2.5-flash");

        assertThrows(ExtractionException.class, () -> extractor.extract("some posting"));
    }
}
