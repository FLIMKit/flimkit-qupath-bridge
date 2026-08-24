package io.github.flimkit.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhasorWindowTest {

    private static JsonObject parse(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void anEmptyCursorReportsOnlyItsPixelCount() {
        var line = PhasorWindow.describe(parse("{\"id\": \"c1\", \"n_pixels\": 0}"));

        assertEquals("c1: 0 px", line);
    }

    @Test
    void aPopulatedCursorReportsBothLifetimes() {
        var line = PhasorWindow.describe(parse("""
                {"id": "c1", "n_pixels": 128, "tau_phi_ns": 2.345,
                 "tau_mod_ns": 2.567, "mean_g": 0.301, "mean_s": 0.402}
                """));

        assertTrue(line.startsWith("c1: 128 px"), line);
        assertTrue(line.contains("tau_phi 2.35"), line);
        assertTrue(line.contains("tau_m 2.57"), line);
        assertTrue(line.contains("(G 0.301, S 0.402)"), line);
    }

    @Test
    void aNullLifetimeIsLeftOutRatherThanPrintedAsNull() {
        var line = PhasorWindow.describe(parse("""
                {"id": "c1", "n_pixels": 5, "tau_phi_ns": null, "tau_mod_ns": 2.0}
                """));

        assertFalse(line.contains("null"), line);
        assertFalse(line.contains("tau_phi"), line);
        assertTrue(line.contains("tau_m 2.00"), line);
    }

    @Test
    void aCursorWithPixelsButNoStatsStillReportsTheCount() {
        var line = PhasorWindow.describe(parse("{\"id\": \"c2\", \"n_pixels\": 7}"));

        assertEquals("c2: 7 px", line);
    }
}
