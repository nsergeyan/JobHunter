package com.jobscout.extraction;

import com.jobscout.scraper.FakeHttpFetcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaExtractorTest {

    private static final String BASE_URL = "http://localhost:11434";
    private static final String CHAT_ENDPOINT = BASE_URL + "/api/chat";

    private static String chatResponse(String messageContentJsonEscaped) {
        return "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\""
                + messageContentJsonEscaped + "\"},\"done\":true}";
    }

    @Test
    void extractParsesStructuredFieldsFromMessageContent() {
        String modelOutput = "{\\\"skills\\\":[\\\"Python\\\",\\\"SQL\\\"],\\\"seniority\\\":\\\"junior\\\","
                + "\\\"salary_min\\\":50000,\\\"salary_max\\\":65000,\\\"salary_currency\\\":\\\"USD\\\","
                + "\\\"salary_period\\\":\\\"yearly\\\","
                + "\\\"language_requirement\\\":\\\"English\\\",\\\"remote_policy\\\":\\\"remote\\\"}";
        FakeHttpFetcher fetcher = new FakeHttpFetcher((url, body) -> {
            assertEquals(CHAT_ENDPOINT, url);
            return chatResponse(modelOutput);
        });
        OllamaExtractor extractor = new OllamaExtractor(fetcher, BASE_URL, "qwen3:8b");

        VacancyExtraction result = extractor.extract("We need a junior Python engineer, remote, $50k-65k USD.");

        assertEquals(java.util.List.of("Python", "SQL"), result.skills());
        assertEquals("junior", result.seniority());
        assertEquals(50000, result.salaryMin());
        assertEquals(65000, result.salaryMax());
        assertEquals("USD", result.salaryCurrency());
        assertEquals("yearly", result.salaryPeriod());
        assertEquals("English", result.languageRequirement());
        assertEquals("remote", result.remotePolicy());
        assertEquals("qwen3:8b", result.model());
    }

    @Test
    void extractTreatsZeroAndEmptyPlaceholdersAsAbsent() {
        String modelOutput = "{\\\"skills\\\":[],\\\"seniority\\\":\\\"unknown\\\",\\\"salary_min\\\":0,"
                + "\\\"salary_max\\\":0,\\\"salary_currency\\\":\\\"\\\",\\\"salary_period\\\":\\\"unknown\\\","
                + "\\\"language_requirement\\\":\\\"\\\",\\\"remote_policy\\\":\\\"unknown\\\"}";
        FakeHttpFetcher fetcher = new FakeHttpFetcher((url, body) -> chatResponse(modelOutput));
        OllamaExtractor extractor = new OllamaExtractor(fetcher, BASE_URL, "qwen3:8b");

        VacancyExtraction result = extractor.extract("Vague posting with no real details.");

        assertTrue(result.skills().isEmpty());
        assertEquals("unknown", result.seniority());
        assertNull(result.salaryMin());
        assertNull(result.salaryCurrency());
        assertEquals("unknown", result.salaryPeriod());
    }

    @Test
    void extractThrowsExtractionExceptionWhenNoMessageContentPresent() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher((url, body) -> "{\"model\":\"qwen3:8b\",\"done\":true}");
        OllamaExtractor extractor = new OllamaExtractor(fetcher, BASE_URL, "qwen3:8b");

        assertThrows(ExtractionException.class, () -> extractor.extract("some posting"));
    }

    @Test
    void extractThrowsExtractionExceptionWhenResponseIsNotValidJson() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher((url, body) -> "not json");
        OllamaExtractor extractor = new OllamaExtractor(fetcher, BASE_URL, "qwen3:8b");

        assertThrows(ExtractionException.class, () -> extractor.extract("some posting"));
    }
}
