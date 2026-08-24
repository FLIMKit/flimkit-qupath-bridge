package io.github.flimkit.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FitDialogTest {

    private static JsonObject entry(String appliesTo) {
        return JsonParser.parseString(
                "{\"key\": \"k\", \"applies_to\": " + appliesTo + "}").getAsJsonObject();
    }

    @Test
    void aSettingAppliesToTheModeItNames() {
        assertTrue(FitDialog.appliesTo(entry("[\"roi\", \"per_pixel\"]"), "roi"));
        assertTrue(FitDialog.appliesTo(entry("[\"roi\", \"per_pixel\"]"), "per_pixel"));
    }

    @Test
    void aSettingDoesNotApplyToAModeItOmits() {
        assertFalse(FitDialog.appliesTo(entry("[\"per_pixel\"]"), "roi"));
        assertFalse(FitDialog.appliesTo(entry("[]"), "roi"));
    }

    @Test
    void thePhasorModeIsJustAnotherMode() {
        assertTrue(FitDialog.appliesTo(entry("[\"phasor\"]"), "phasor"));
        assertFalse(FitDialog.appliesTo(entry("[\"phasor\"]"), "roi"));
    }
}
