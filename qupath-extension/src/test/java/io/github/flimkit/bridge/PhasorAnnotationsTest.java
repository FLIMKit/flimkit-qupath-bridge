package io.github.flimkit.bridge;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhasorAnnotationsTest {

    private static final String SERVER = "test-server";

    private static byte[] twoBlocks() {
        byte[] labels = new byte[16];
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++)
                labels[row * 4 + col] = 1;
        }
        for (int row = 2; row < 4; row++) {
            for (int col = 2; col < 4; col++)
                labels[row * 4 + col] = 2;
        }
        return labels;
    }

    @Test
    void eachLabelBecomesOneAnnotation() {
        var found = PhasorAnnotations.fromLabels(
                twoBlocks(), 4, 4, 1, List.of("Phasor c1", "Phasor c2"), SERVER);

        assertEquals(2, found.size());
        assertEquals("Phasor c1", found.get(0).getName());
        assertEquals("Phasor c2", found.get(1).getName());
        assertEquals("Phasor", found.get(0).getPathClass().getName());
    }

    @Test
    void binningScalesTheRoiBackToFullResolution() {
        var atOne = PhasorAnnotations.fromLabels(
                twoBlocks(), 4, 4, 1, List.of("a", "b"), SERVER);
        var atFour = PhasorAnnotations.fromLabels(
                twoBlocks(), 4, 4, 4, List.of("a", "b"), SERVER);

        var small = atOne.get(0).getROI();
        var large = atFour.get(0).getROI();

        assertEquals(small.getBoundsWidth() * 4, large.getBoundsWidth(), 1e-6);
        assertEquals(small.getBoundsHeight() * 4, large.getBoundsHeight(), 1e-6);
    }

    @Test
    void anEmptyLabelImageProducesNothing() {
        var found = PhasorAnnotations.fromLabels(
                new byte[16], 4, 4, 1, List.of("a"), SERVER);

        assertTrue(found.isEmpty());
    }

    @Test
    void aMistakenSizeIsRejectedRatherThanTraced() {
        var raised = assertThrows(IllegalArgumentException.class,
                () -> PhasorAnnotations.fromLabels(
                        new byte[10], 4, 4, 1, List.of("a"), SERVER));

        assertTrue(raised.getMessage().contains("expected 16"), raised.getMessage());
    }

    @Test
    void binningBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PhasorAnnotations.fromLabels(
                        new byte[16], 4, 4, 0, List.of("a"), SERVER));
    }

    @Test
    void aLabelWithNoNameStillGetsOne() {
        var found = PhasorAnnotations.fromLabels(
                twoBlocks(), 4, 4, 1, List.of("only one"), SERVER);

        assertEquals("only one", found.get(0).getName());
        assertEquals("Phasor cursor 2", found.get(1).getName());
    }

    @Test
    void base64AndBytesAgree() {
        var fromBytes = PhasorAnnotations.fromLabels(
                twoBlocks(), 4, 4, 1, List.of("a", "b"), SERVER);
        var fromText = PhasorAnnotations.fromBase64(
                Base64.getEncoder().encodeToString(twoBlocks()), 4, 4, 1,
                List.of("a", "b"), SERVER);

        assertEquals(fromBytes.size(), fromText.size());
        assertEquals(fromBytes.get(0).getROI().getBoundsWidth(),
                fromText.get(0).getROI().getBoundsWidth(), 1e-6);
    }
}
