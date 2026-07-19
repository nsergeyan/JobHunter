package com.jobscout.scraper;

/**
 * Test double for HttpFetcher: routes by URL (and POST body, for Workday's paginated
 * requests) to canned responses instead of hitting the network -- mirrors the
 * httpx.MockTransport handler(request) pattern used in the original Python tests.
 */
public class FakeHttpFetcher implements HttpFetcher {
    @FunctionalInterface
    public interface Responder {
        String respond(String url, String body);
    }

    private final Responder responder;

    public FakeHttpFetcher(Responder responder) {
        this.responder = responder;
    }

    @Override
    public String get(String url) {
        return responder.respond(url, null);
    }

    @Override
    public String post(String url, String jsonBody) {
        return responder.respond(url, jsonBody);
    }
}
