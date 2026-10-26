package com.thescratchers.searchengine.search.query;

/**
 * A request parameter is missing, malformed or out of range. The message names the parameter and
 * the rule it breaks, and is returned to the client as an {@code INVALID_PARAMETER} error (400).
 */
public class InvalidParameterException extends IllegalArgumentException {

    public InvalidParameterException(String message) {
        super(message);
    }
}
