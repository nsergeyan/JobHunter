package com.jobscout.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobscout.scraper.HttpFetcher;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Calls the Gemini API's generateContent endpoint to pull structured fields out of a
 * raw job posting: skills, seniority, salary range, language requirement, remote
 * policy. The response is constrained server-side to a JSON schema (Gemini's
 * "controlled generation" -- note its Schema.type values are UPPERCASE, unlike
 * standard JSON Schema), so parsing is plain JSON decoding, no fragile prompt-based
 * extraction.
 *
 * Deliberately targets generateContent rather than the newer Interactions API
 * (v1beta/interactions): Google's docs confirm generateContent "remains fully
 * supported", while Interactions API examples only cover the gemini-3.x line --
 * gemini-2.5-flash compatibility with it isn't documented.
 */
public class GeminiExtractor implements Extractor {
    private static final String ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectNode RESPONSE_SCHEMA = buildResponseSchema();

    private final HttpFetcher fetcher;
    private final List<String> apiKeys;
    private final String model;
    private final AtomicInteger keyIndex = new AtomicInteger(0);

    public GeminiExtractor(HttpFetcher fetcher, List<String> apiKeys, String model) {
        if (apiKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one Gemini API key is required");
        }
        this.fetcher = fetcher;
        this.apiKeys = apiKeys;
        this.model = model;
    }

    @Override
    public VacancyExtraction extract(String rawText) {
        String url = ENDPOINT_TEMPLATE.formatted(model, urlEncode(nextApiKey()));
        String responseBody = fetcher.post(url, buildRequestBody(rawText));
        return parseResponse(responseBody);
    }

    /** Round-robins across configured keys so a single free-tier key isn't rate-limited alone. */
    private String nextApiKey() {
        int index = Math.floorMod(keyIndex.getAndIncrement(), apiKeys.size());
        return apiKeys.get(index);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String buildRequestBody(String rawText) {
        ObjectNode part = MAPPER.createObjectNode();
        part.put("text", ExtractionPrompt.PREFIX + rawText);
        ArrayNode parts = MAPPER.createArrayNode();
        parts.add(part);
        ObjectNode content = MAPPER.createObjectNode();
        content.set("parts", parts);
        content.put("role", "user");
        ArrayNode contents = MAPPER.createArrayNode();
        contents.add(content);

        ObjectNode generationConfig = MAPPER.createObjectNode();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.set("responseSchema", RESPONSE_SCHEMA);

        ObjectNode root = MAPPER.createObjectNode();
        root.set("contents", contents);
        root.set("generationConfig", generationConfig);

        return root.toString();
    }

    /** Gemini's controlled-generation Schema.type values are UPPERCASE -- not standard JSON Schema casing. */
    private static ObjectNode buildResponseSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "OBJECT");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode skills = MAPPER.createObjectNode();
        skills.put("type", "ARRAY");
        ObjectNode skillsItems = MAPPER.createObjectNode();
        skillsItems.put("type", "STRING");
        skills.set("items", skillsItems);
        skills.put("description", ExtractionPrompt.SKILLS_DESCRIPTION);
        properties.set("skills", skills);

        properties.set("seniority", enumField(ExtractionPrompt.SENIORITY_VALUES, ExtractionPrompt.SENIORITY_DESCRIPTION));
        properties.set("salary_min", typedField("INTEGER", ExtractionPrompt.SALARY_MIN_DESCRIPTION));
        properties.set("salary_max", typedField("INTEGER", ExtractionPrompt.SALARY_MAX_DESCRIPTION));
        properties.set("salary_currency", typedField("STRING", ExtractionPrompt.SALARY_CURRENCY_DESCRIPTION));
        properties.set("salary_period", enumField(ExtractionPrompt.SALARY_PERIOD_VALUES, ExtractionPrompt.SALARY_PERIOD_DESCRIPTION));
        properties.set("language_requirement", typedField("STRING", ExtractionPrompt.LANGUAGE_REQUIREMENT_DESCRIPTION));
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
        field.put("type", "STRING");
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
            throw new ExtractionException("Gemini response was not valid JSON: " + exc.getMessage(), exc);
        }

        JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        if (textNode.isMissingNode()) {
            throw new ExtractionException("No candidate text in Gemini response: " + responseBody);
        }

        JsonNode fields;
        try {
            fields = MAPPER.readTree(textNode.asText());
        } catch (JsonProcessingException exc) {
            throw new ExtractionException("Gemini's structured output was not valid JSON: " + textNode.asText(), exc);
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

    /** The schema requires numeric/string fields (some Gemini SDKs ignore optional markers), so
     * the model fills in 0 / "" instead of omitting them -- treat those placeholders as absent. */
    private static Integer nullableInt(JsonNode node) {
        int value = node.asInt(0);
        return value == 0 ? null : value;
    }

    private static String nullableString(JsonNode node) {
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }
}
