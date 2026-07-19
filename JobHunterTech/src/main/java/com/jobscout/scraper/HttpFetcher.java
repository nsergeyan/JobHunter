package com.jobscout.scraper;

/**
 * Testability seam: production code talks to the real network, tests supply a fake
 * implementation keyed by URL instead -- mirrors the httpx.MockTransport pattern used
 * in the original Python scraper tests.
 */
public interface HttpFetcher {
    String get(String url);

    String post(String url, String jsonBody);
}
