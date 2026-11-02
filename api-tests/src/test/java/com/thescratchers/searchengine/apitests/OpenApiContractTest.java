package com.thescratchers.searchengine.apitests;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code docs/openapi.yaml} and the services in agreement, using the case files as the
 * evidence of what the services actually do:
 * <ul>
 *   <li>every request of every case file targets a documented operation, and every status code it
 *       expects is documented for that operation;</li>
 *   <li>every documented operation is exercised by at least one case;</li>
 *   <li>every {@code GET /search} example of the specification is exactly the response of the real
 *       pipeline, as recorded in {@code cases/corpus-search.json}.</li>
 * </ul>
 * CORS preflight requests ({@code OPTIONS}) are answered by the CORS support of the Search Service
 * and described in the specification's introduction, so they are not operations of their own.
 */
class OpenApiContractTest {

    private static final Path SPEC = Path.of("..", "docs", "openapi.yaml");
    private static final Path CASES = Path.of("cases");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final Pattern EXAMPLE_REQUEST = Pattern.compile("\\(GET (/search\\?[^)]+)\\)");

    /** Documented operation ("GET /books/{book_id}") → documented status codes. */
    private static final Map<String, Set<String>> OPERATIONS = new LinkedHashMap<>();
    private static final Map<String, Pattern> PATH_PATTERNS = new HashMap<>();
    private static Map<String, Object> spec;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void readSpecification() throws IOException {
        try (Reader reader = Files.newBufferedReader(SPEC, StandardCharsets.UTF_8)) {
            spec = new Yaml().load(reader);
        }
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            PATH_PATTERNS.put(path.getKey(), Pattern.compile(path.getKey().replaceAll("\\{[^/]+}", "[^/]+")));
            for (Map.Entry<String, Object> operation : ((Map<String, Object>) path.getValue()).entrySet()) {
                if (METHODS.contains(operation.getKey())) {
                    Map<String, Object> responses = (Map<String, Object>) ((Map<String, Object>) operation.getValue())
                            .get("responses");
                    Set<String> statuses = new TreeSet<>();
                    responses.keySet().forEach(status -> statuses.add(String.valueOf(status)));
                    OPERATIONS.put(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey(), statuses);
                }
            }
        }
    }

    @Test
    void everyCheckedRequestAndStatusIsDocumented() throws IOException {
        List<String> undocumented = new ArrayList<>();
        for (JsonObject testCase : allCases()) {
            String method = testCase.has("method") ? testCase.get("method").getAsString() : "GET";
            if (method.equals("OPTIONS")) {
                continue;
            }
            String path = testCase.get("path").getAsString().split("\\?", 2)[0];
            String status = testCase.get("status").getAsString();
            String operation = operationFor(method, path);
            if (operation == null) {
                undocumented.add(method + " " + path + " (case '" + testCase.get("name").getAsString() + "')");
            } else if (!OPERATIONS.get(operation).contains(status)) {
                undocumented.add(operation + " answers " + status + " (case '" + testCase.get("name").getAsString() + "')");
            }
        }
        assertEquals(List.of(), undocumented, "checked by the case files but missing from docs/openapi.yaml");
    }

    @Test
    void everyDocumentedOperationIsChecked() throws IOException {
        Set<String> checked = new HashSet<>();
        for (JsonObject testCase : allCases()) {
            String method = testCase.has("method") ? testCase.get("method").getAsString() : "GET";
            String operation = operationFor(method, testCase.get("path").getAsString().split("\\?", 2)[0]);
            if (operation != null) {
                checked.add(operation);
            }
        }
        List<String> unchecked = OPERATIONS.keySet().stream().filter(op -> !checked.contains(op)).toList();
        assertEquals(List.of(), unchecked, "documented in docs/openapi.yaml but not exercised by any case file");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchExamplesAreWhatTheRealPipelineReturns() throws IOException {
        Map<String, JsonElement> pipelineBodies = new HashMap<>();
        for (JsonObject testCase : cases(CASES.resolve("corpus-search.json"))) {
            pipelineBodies.put(testCase.get("path").getAsString(), testCase.get("body"));
        }
        Map<String, Object> examples = node(spec, "paths", "/search", "get", "responses", "200", "content",
                "application/json", "examples");
        Gson gson = new Gson();
        int compared = 0;
        for (Map.Entry<String, Object> example : examples.entrySet()) {
            Map<String, Object> value = (Map<String, Object>) example.getValue();
            Matcher request = EXAMPLE_REQUEST.matcher(String.valueOf(value.get("summary")));
            assertTrue(request.find(), "example " + example.getKey() + " does not name its request");
            JsonElement recorded = pipelineBodies.get(request.group(1));
            assertTrue(recorded != null, "no pipeline case for " + request.group(1));
            assertEquals(recorded, gson.toJsonTree(value.get("value")), "example " + example.getKey());
            compared++;
        }
        assertTrue(compared >= 5, "expected the five documented search examples, found " + compared);
    }

    /** Follows {@code keys} through nested YAML mappings; an unquoted status code is read as a number. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> node(Map<String, Object> root, String... keys) {
        Map<?, ?> current = root;
        for (String key : keys) {
            Object next = current.get(key);
            if (next == null && key.chars().allMatch(Character::isDigit)) {
                next = current.get(Integer.parseInt(key));
            }
            current = (Map<?, ?>) next;
        }
        return (Map<String, Object>) current;
    }

    /**
     * Returns the documented operation for a concrete request, or {@code null}. A literal path
     * ({@code /ingest/list}) wins over a template that also matches it ({@code /ingest/{book_id}}).
     */
    private static String operationFor(String method, String path) {
        String literal = method + " " + path;
        if (OPERATIONS.containsKey(literal)) {
            return literal;
        }
        for (Map.Entry<String, Pattern> documented : PATH_PATTERNS.entrySet()) {
            String operation = method + " " + documented.getKey();
            if (documented.getValue().matcher(path).matches() && OPERATIONS.containsKey(operation)) {
                return operation;
            }
        }
        return null;
    }

    private static List<JsonObject> allCases() throws IOException {
        List<JsonObject> all = new ArrayList<>();
        try (Stream<Path> files = Files.list(CASES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                all.addAll(cases(file));
            }
        }
        assertTrue(all.size() > 50, "expected every case file, found " + all.size() + " cases");
        return all;
    }

    private static List<JsonObject> cases(Path file) throws IOException {
        List<JsonObject> cases = new ArrayList<>();
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        root.getAsJsonArray("cases").forEach(element -> cases.add(element.getAsJsonObject()));
        return cases;
    }
}
