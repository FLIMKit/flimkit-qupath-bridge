package io.github.flimkit.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

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

    private static final JsonObject NO_OPTIONS = new JsonObject();

    private static PhasorWindow.Cursor ellipse() {
        return new PhasorWindow.Cursor("c1", 0.3, 0.4, 0.05);
    }

    private static PhasorWindow.Cursor outline(String id) {
        return new PhasorWindow.Cursor(id, List.of(
                new double[] {0.25, 0.35},
                new double[] {0.35, 0.35},
                new double[] {0.35, 0.45}));
    }

    @Test
    void anEllipseGoesOnTheWireAsACentreAndARadius() {
        var body = parse(PhasorWindow.requestBody(
                List.of(ellipse()), NO_OPTIONS, false));
        var entry = body.getAsJsonArray("cursors").get(0).getAsJsonObject();

        assertEquals("c1", entry.get("id").getAsString());
        assertFalse(entry.has("type"));
        assertEquals(0.3, entry.get("center_g").getAsDouble(), 1e-9);
        assertEquals(0.05, entry.get("radius").getAsDouble(), 1e-9);
    }

    @Test
    void anOutlineGoesOnTheWireAsAPolygon() {
        var body = parse(PhasorWindow.requestBody(
                List.of(outline("c1")), NO_OPTIONS, false));
        var entry = body.getAsJsonArray("cursors").get(0).getAsJsonObject();

        assertEquals("polygon", entry.get("type").getAsString());
        assertFalse(entry.has("center_g"));
        var vertices = entry.getAsJsonArray("vertices");
        assertEquals(3, vertices.size());
        assertEquals(0.25, vertices.get(0).getAsJsonArray().get(0).getAsDouble(), 1e-9);
        assertEquals(0.35, vertices.get(0).getAsJsonArray().get(1).getAsDouble(), 1e-9);
    }

    @Test
    void anOutlineAndAnEllipseTravelTogether() {
        var cursors = parse(PhasorWindow.requestBody(
                List.of(ellipse(), outline("c2")), NO_OPTIONS, false))
                .getAsJsonArray("cursors");

        assertEquals(2, cursors.size());
        assertFalse(cursors.get(0).getAsJsonObject().has("type"));
        assertEquals("polygon",
                cursors.get(1).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void theLabelsFlagAsksForALabelImage() {
        assertFalse(parse(PhasorWindow.requestBody(
                List.of(ellipse()), NO_OPTIONS, false)).has("output"));
        assertEquals("labels", parse(PhasorWindow.requestBody(
                List.of(ellipse()), NO_OPTIONS, true)).get("output").getAsString());
    }

    @Test
    void settingsRideOnTheBodyOnlyOnceChosen() {
        assertFalse(parse(PhasorWindow.requestBody(
                List.of(ellipse()), NO_OPTIONS, false)).has("options"));

        var chosen = parse("{\"phasor_filter\": \"median\"}");

        assertEquals("median",
                parse(PhasorWindow.requestBody(List.of(ellipse()), chosen, false))
                        .getAsJsonObject("options").get("phasor_filter").getAsString());
    }

    @Test
    void everyCursorCarriesThePhotonFloor() {
        var body = parse(PhasorWindow.requestBody(
                List.of(ellipse()), NO_OPTIONS, false));

        assertEquals(1.0, body.get("min_photons").getAsDouble(), 1e-9);
    }

    @Test
    void anEmptyOptionsObjectIsAnEmptyQuery() {
        assertEquals("", PhasorWindow.optionsQuery(NO_OPTIONS));
    }

    @Test
    void chosenOptionsBecomeAQueryString() {
        assertEquals("phasor_filter=median", PhasorWindow.optionsQuery(
                parse("{\"phasor_filter\": \"median\"}")));
    }

    @Test
    void aQueryValueIsEscaped() {
        assertEquals("irf=machine%2Ba", PhasorWindow.optionsQuery(
                parse("{\"irf\": \"machine+a\"}")));
    }
}
