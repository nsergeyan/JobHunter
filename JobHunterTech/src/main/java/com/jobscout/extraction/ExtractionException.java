package com.jobscout.extraction;

/** Thrown when an LLM extraction call fails or returns a response we can't parse. */
public class ExtractionException extends RuntimeException {
    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
