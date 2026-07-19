package com.jobscout.scraper;

/** Thrown when a scraper can't get usable data (bad response, changed layout, etc.). */
public class ScraperException extends RuntimeException {
    public ScraperException(String message) {
        super(message);
    }

    public ScraperException(String message, Throwable cause) {
        super(message, cause);
    }
}
