package io.github.flimkit.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionCheckTest {

    private static JsonObject parse(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void aMatchingProtocolIsNotAWarning() {
        var status = parse("""
                {"protocol_version": 1, "bridge_version": "0.9.9",
                 "flimkit_version": "0.13.2"}
                """);

        assertNull(FlimKitBridgeExtension.mismatchWarning(status));
    }

    @Test
    void aDifferentServerVersionOnTheSameProtocolIsNotAWarning() {
        var status = parse("""
                {"protocol_version": 1, "bridge_version": "99.0.0",
                 "flimkit_version": "0.13.2"}
                """);

        assertNull(FlimKitBridgeExtension.mismatchWarning(status),
                "the server and the jar version independently now");
    }

    @Test
    void aDifferentProtocolIsAWarningThatNamesBothSides() {
        var status = parse("""
                {"protocol_version": 2, "bridge_version": "1.0.0",
                 "flimkit_version": "0.13.2"}
                """);

        var warning = FlimKitBridgeExtension.mismatchWarning(status);

        assertNotNull(warning);
        assertTrue(warning.contains("2"), warning);
        assertTrue(warning.contains(String.valueOf(
                FlimKitBridgeExtension.PROTOCOL_VERSION)), warning);
    }

    @Test
    void aStatusWithoutAProtocolVersionIsTreatedAsTooOld() {
        var status = parse("{\"bridge_version\": \"0.1.0\"}");

        var warning = FlimKitBridgeExtension.mismatchWarning(status);

        assertNotNull(warning);
        assertTrue(warning.contains("flimkit-bridge"), warning);
    }

    @Test
    void unreadableTextIsNotAWarning() {
        assertNull(FlimKitBridgeExtension.mismatchWarning((String) null));
        assertNull(FlimKitBridgeExtension.mismatchWarning("not json"));
    }
}
