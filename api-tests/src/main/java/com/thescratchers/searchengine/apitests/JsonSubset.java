package com.thescratchers.searchengine.apitests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Optional;

/**
 * Compares an expected JSON fragment with an actual response body.
 *
 * <p>Rules: an expected object only constrains the keys it lists (the actual object may have
 * more); an expected array must have exactly the same length and every element must match in
 * order; scalars and {@code null} must be equal. This lets a case state only what it checks, for
 * example the {@code book_id}s of the results without repeating every field.
 */
final class JsonSubset {

    private JsonSubset() {
    }

    /** Returns a description of the first difference, or empty if {@code actual} matches. */
    static Optional<String> mismatch(JsonElement expected, JsonElement actual) {
        return mismatch(expected, actual, "$");
    }

    private static Optional<String> mismatch(JsonElement expected, JsonElement actual, String path) {
        if (expected.isJsonObject()) {
            if (!actual.isJsonObject()) {
                return Optional.of(path + ": expected an object but was " + actual);
            }
            JsonObject actualObject = actual.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : expected.getAsJsonObject().entrySet()) {
                String childPath = path + "." + entry.getKey();
                if (!actualObject.has(entry.getKey())) {
                    return Optional.of(childPath + ": missing");
                }
                Optional<String> child = mismatch(entry.getValue(), actualObject.get(entry.getKey()), childPath);
                if (child.isPresent()) {
                    return child;
                }
            }
            return Optional.empty();
        }
        if (expected.isJsonArray()) {
            if (!actual.isJsonArray()) {
                return Optional.of(path + ": expected an array but was " + actual);
            }
            JsonArray expectedArray = expected.getAsJsonArray();
            JsonArray actualArray = actual.getAsJsonArray();
            if (expectedArray.size() != actualArray.size()) {
                return Optional.of(path + ": expected " + expectedArray.size() + " elements but was "
                        + actualArray.size() + " " + actualArray);
            }
            for (int i = 0; i < expectedArray.size(); i++) {
                Optional<String> child = mismatch(expectedArray.get(i), actualArray.get(i), path + "[" + i + "]");
                if (child.isPresent()) {
                    return child;
                }
            }
            return Optional.empty();
        }
        return expected.equals(actual)
                ? Optional.empty()
                : Optional.of(path + ": expected " + expected + " but was " + actual);
    }
}
