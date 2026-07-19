package com.jobscout.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.List;

/** Shared HTML-to-text cleanup for job description fields. */
public final class JobPostingHtml {
    private JobPostingHtml() {
    }

    /**
     * Joins every text node in document order with "\n" -- matches BeautifulSoup's
     * get_text(separator="\n"), which the original Python scrapers relied on for
     * raw_text (keeps behavior consistent for any labeling/extraction work later).
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
