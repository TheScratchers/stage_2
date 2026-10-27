package com.thescratchers.searchengine.apitests;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonSubsetTest {

    @Test
    void objectsOnlyConstrainTheListedKeys() {
        assertEquals(Optional.empty(), mismatch("{\"total\":2}", "{\"total\":2,\"offset\":0}"));
    }

    @Test
    void reportsAMissingKeyWithItsPath() {
        assertEquals(Optional.of("$.results[0].score: missing"),
                mismatch("{\"results\":[{\"score\":1}]}", "{\"results\":[{\"book_id\":1}]}"));
    }

    @Test
    void reportsADifferentValueWithItsPath() {
        assertEquals(Optional.of("$.error.code: expected \"NOT_FOUND\" but was \"INVALID_PARAMETER\""),
                mismatch("{\"error\":{\"code\":\"NOT_FOUND\"}}", "{\"error\":{\"code\":\"INVALID_PARAMETER\"}}"));
    }

    @Test
    void arraysMustHaveTheSameLengthAndOrder() {
        assertEquals(Optional.of("$: expected 2 elements but was 3 [1,2,3]"), mismatch("[1,2]", "[1,2,3]"));
        assertEquals(Optional.of("$[0]: expected 2 but was 1"), mismatch("[2,1]", "[1,2]"));
        assertEquals(Optional.empty(), mismatch("[{\"book_id\":2}]", "[{\"book_id\":2,\"score\":4}]"));
    }

    @Test
    void nullMustBeExplicit() {
        assertEquals(Optional.empty(), mismatch("{\"author\":null}", "{\"author\":null}"));
        assertEquals(Optional.of("$.author: missing"), mismatch("{\"author\":null}", "{}"));
        assertEquals(Optional.of("$.author: expected null but was \"X\""), mismatch("{\"author\":null}", "{\"author\":\"X\"}"));
    }

    @Test
    void typesMustMatch() {
        assertEquals(Optional.of("$.filters: expected an object but was []"), mismatch("{\"filters\":{}}", "{\"filters\":[]}"));
        assertEquals(Optional.of("$.year: expected 1998 but was \"1998\""), mismatch("{\"year\":1998}", "{\"year\":\"1998\"}"));
    }

    private static Optional<String> mismatch(String expected, String actual) {
        return JsonSubset.mismatch(JsonParser.parseString(expected), JsonParser.parseString(actual));
    }
}
