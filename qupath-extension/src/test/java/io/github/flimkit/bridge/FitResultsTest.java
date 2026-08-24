package io.github.flimkit.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FitResultsTest {

    private static JsonObject parse(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void aCleanReplyHasNoErrors() {
        var payload = parse("""
                {"results": [{"name": "a", "tau_mean_ns": 2.1},
                             {"name": "b", "tau_mean_ns": 2.4}]}
                """);

        assertTrue(FitResults.errors(payload).isEmpty());
    }

    @Test
    void everyFailureIsNamedWithItsReason() {
        var payload = parse("""
                {"results": [{"name": "a", "tau_mean_ns": 2.1},
                             {"name": "b", "error": "too few photons"}]}
                """);

        var found = FitResults.errors(payload);

        assertEquals(1, found.size());
        assertEquals("b: too few photons", found.get(0));
    }

    @Test
    void anEmptyResultListIsNotAnError() {
        assertTrue(FitResults.errors(parse("{\"results\": []}")).isEmpty());
    }
}
