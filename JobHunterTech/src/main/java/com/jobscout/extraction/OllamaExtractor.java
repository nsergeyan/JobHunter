package com.jobscout.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobscout.scraper.HttpFetcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Calls a local Ollama server's /api/chat endpoint to pull the same structured
 * fields as GeminiExtractor, fully offline -- no API key, no rate limit, no cost.
 * Uses the exact same instructions and field descriptions (see ExtractionPrompt) so
 * results are comparable model-vs-model, not prompt-vs-prompt.
 *
 * Ollama's "format" field takes standard JSON Schema (lowercase types), unlike
 * Gemini's uppercase Schema.type quirk -- the two schema builders differ only in
 * casing, everything else is shared via ExtractionPrompt.
 */
public class OllamaExtractor implements Extractor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectNode RESPONSE_SCHEMA = buildResponseSchema();

    private final HttpFetcher fetcher;
    private final String baseUrl;
    private final String model;

    public OllamaExtractor(HttpFetcher fetcher, String baseUrl, String model) {
        this.fetcher = fetcher;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public VacancyExtraction extract(String rawText) {
        String responseBody = fetcher.post(baseUrl + "/api/chat", buildRequestBody(rawText));
        return parseResponse(responseBody);
    }

    private String buildRequestBody(String rawText) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        message.put("content", ExtractionPrompt.PREFIX + rawText);
        ArrayNode messages = MAPPER.createArrayNode();
        messages.add(message);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.set("messages", messages);
        root.put("stream", false);
        root.set("format", RESPONSE_SCHEMA);

        return root.toString();
    }

    private static ObjectNode buildResponseSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode skills = MAPPER.createObjectNode();
        skills.put("type", "array");
        ObjectNode skillsItems = MAPPER.createObjectNode();
        skillsItems.put("type", "string");
        skills.set("items", skillsItems);
        skills.put("description", ExtractionPrompt.SKILLS_DESCRIPTION);
        properties.set("skills", skills);

        properties.set("seniority", enumField(ExtractionPrompt.SENIORITY_VALUES, ExtractionPrompt.SENIORITY_DESCRIPTION));
        properties.set("salary_min", typedField("integer", ExtractionPrompt.SALARY_MIN_DESCRIPTION));
        properties.set("salary_max", typedField("integer", ExtractionPrompt.SALARY_MAX_DESCRIPTION));
        properties.set("salary_currency", typedField("string", ExtractionPrompt.SALARY_CURRENCY_DESCRIPTION));
        properties.set("salary_period", enumField(ExtractionPrompt.SALARY_PERIOD_VALUES, ExtractionPrompt.SALARY_PERIOD_DESCRIPTION));
        properties.set("language_requirement", typedField("string", ExtractionPrompt.LANGUAGE_REQUIREMENT_DESCRIPTION));
        properties.set("remote_policy", enumField(ExtractionPrompt.REMOTE_POLICY_VALUES, ExtractionPrompt.REMOTE_POLICY_DESCRIPTION));

        schema.set("properties", properties);

        ArrayNode required = MAPPER.createArrayNode();
        required.add("skills");
        required.add("seniority");
        required.add("salary_min");
        required.add("salary_max");
        required.add("salary_currency");
        required.add("salary_period");
        required.add("language_requirement");
        required.add("remote_policy");
        schema.set("required", required);

        return schema;
    }

    private static ObjectNode typedField(String type, String description) {
        ObjectNode field = MAPPER.createObjectNode();
        field.put("type", type);
        field.put("description", description);
        return field;
    }

    private static ObjectNode enumField(List<String> values, String description) {
        ObjectNode field = MAPPER.createObjectNode();
        field.put("type", "string");
        ArrayNode enumNode = MAPPER.createArrayNode();
        values.forEach(enumNode::add);
        field.set("enum", enumNode);
        field.put("description", description);
        return field;
    }

    private VacancyExtraction parseResponse(String responseBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (JsonProcessingException exc) {
            throw new ExtractionException("Ollama response was not valid JSON: " + exc.getMessage(), exc);
        }

        JsonNode contentNode = root.path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
            throw new ExtractionException("No message content in Ollama response: " + responseBody);
        }

        JsonNode fields;
        try {
            fields = MAPPER.readTree(contentNode.asText());
        } catch (JsonProcessingException exc) {
            throw new ExtractionException("Ollama's structured output was not valid JSON: " + contentNode.asText(), exc);
        }

        List<String> skills = new ArrayList<>();
        fields.path("skills").forEach(node -> skills.add(node.asText()));

        return new VacancyExtraction(
                skills,
                fields.path("seniority").asText("unknown"),
                nullableInt(fields.path("salary_min")),
                nullableInt(fields.path("salary_max")),
                nullableString(fields.path("salary_currency")),
                fields.path("salary_period").asText("unknown"),
                nullableString(fields.path("language_requirement")),
                fields.path("remote_policy").asText("unknown"),
                model);
    }

    /** Same defensive normalization as GeminiExtractor: a "required" field the model
     * has nothing to say about tends to come back as 0 / "" rather than omitted. */
    private static Integer nullableInt(JsonNode node) {
        int value = node.asInt(0);
        return value == 0 ? null : value;
    }

    private static String nullableString(JsonNode node) {
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }
}
