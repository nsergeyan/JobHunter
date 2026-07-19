package com.jobscout.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared schema.org JobPosting extraction, used by every scraper that reads a
 * page's embedded JSON-LD block instead of parsing rendered markup by hand.
 */
public final class JobPostingHtml {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JobPostingHtml() {
    }

    /** Returns the first `application/ld+json` block whose @type is JobPosting, or null. */
    public static JsonNode extractJobPosting(String pageHtml) {
        Document doc = Jsoup.parse(pageHtml);
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = MAPPER.readTree(script.data());
                if ("JobPosting".equals(node.path("@type").asText(null))) {
                    return node;
                }
            } catch (Exception ignored) {
                // Malformed JSON-LD block -- skip it, same as the Python JSONDecodeError guard.
            }
        }
        return null;
    }

    public static String cleanDescription(JsonNode posting) {
        return htmlToText(posting.path("description").asText(""));
    }

    /**
     * Joins every text node in document order with "\n" -- matches BeautifulSoup's
     * get_text(separator="\n"), which the Python scrapers relied on for raw_text.
     */
    public static String htmlToText(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        List<String> parts = new ArrayList<>();
        doc.body().traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode textNode) {
                    String text = textNode.getWholeText();
                    if (!text.isEmpty()) {
                        parts.add(text);
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
            }
        });
        return String.join("\n", parts).strip();
    }
}
