package io.github.flimkit.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ZStackResultsTest {

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static final String ONE_STACK = """
            {"output_dir": "/data/out",
             "products": [{"file": "/data/out/RegionA.ome.zarr",
                           "image_id": "RegionA",
                           "group_dir": "/data/out/RegionA",
                           "unit": "ns",
                           "format": "ome-zarr",
                           "axes": "CZYX",
                           "channels": ["intensity", "tau_mean_int", "alpha_1"],
                           "units": ["photons", "ns", ""],
                           "n_z": 12,
                           "voxel_size_um": [2.0, 0.284, 0.284],
                           "z_series_csv": "/data/out/RegionA/RegionA_zseries.csv",
                           "taus_ns": [0.4123, 2.8710],
                           "pooled": {"nexp": 2,
                                      "total_pooled_photons": 1731281.0,
                                      "estimate_irf": "machine_irf",
                                      "user_supplied_tau": false,
                                      "calibrated_chi2_pearson": 151.3766}}],
             "stacks": [{"label": "RegionA"}]}
            """;

    @Test
    public void aVolumeIsReadFromTheProducts() {
        var volumes = ZStackResults.volumes(parse(ONE_STACK));

        assertEquals(1, volumes.size());
        var volume = volumes.get(0);
        assertEquals("RegionA", volume.label());
        assertEquals(Path.of("/data/out/RegionA.ome.zarr"), volume.file());
        assertEquals(12, volume.nZ());
        assertTrue(volume.isZarr());
        assertEquals(java.util.List.of("intensity", "tau_mean_int", "alpha_1"),
                volume.channels());
    }

    @Test
    public void channelsCarryTheirUnits() {
        var volume = ZStackResults.volumes(parse(ONE_STACK)).get(0);

        assertEquals(java.util.List.of("intensity (photons)", "tau_mean_int (ns)",
                "alpha_1"), volume.labelled());
    }

    @Test
    public void theDescriptionSaysWhatWasLocked() {
        String described = ZStackResults.volumes(parse(ONE_STACK)).get(0).describe();

        assertTrue(described.contains("12 slices"), described);
        assertTrue(described.contains("tau1 = 0.4123 ns"), described);
        assertTrue(described.contains("tau2 = 2.8710 ns"), described);
        assertTrue(described.contains("2.0000 x 0.2840 x 0.2840"), described);
        assertTrue(described.contains("RegionA_zseries.csv"), described);
    }

    @Test
    public void aVolumeKnowsHowToBeFetchedInstead() {
        var volume = ZStackResults.volumes(parse(ONE_STACK)).get(0);

        assertTrue(volume.canBeFetched());
        assertEquals(2.0, volume.zStepUm());
        assertEquals(0.284, volume.pixelSizeUm());
    }

    @Test
    public void aVolumeFromAnOlderBridgeCannotBeFetched() {
        var volume = ZStackResults.volumes(parse("""
                {"products": [{"file": "/data/out/RegionA.ome.zarr",
                               "image_id": "RegionA", "format": "ome-zarr"}]}
                """)).get(0);

        assertFalse(volume.canBeFetched());
        assertEquals(1.0, volume.zStepUm());
        assertEquals(0.0, volume.pixelSizeUm());
    }

    @Test
    public void theDescriptionCarriesThePooledFitThatDecidedTheLifetimes() {
        String described = ZStackResults.volumes(parse(ONE_STACK)).get(0).describe();

        assertTrue(described.contains("Pooled photons: 1,731,281"), described);
        assertTrue(described.contains("IRF: machine_irf"), described);
        assertTrue(described.contains("Pooled calibrated chi-squared: 151.377"),
                described);
        assertTrue(described.contains("Components: 2"), described);
    }

    @Test
    public void aRunWithoutAPooledFitStillDescribesItself() {
        var volume = ZStackResults.volumes(parse("""
                {"products": [{"file": "/data/out/RegionA.ome.tif",
                               "image_id": "RegionA", "format": "ome-tiff",
                               "n_z": 4}]}
                """)).get(0);

        assertTrue(volume.pooledLines().isEmpty());
        assertTrue(volume.describe().contains("4 slices"), volume.describe());
    }

    @Test
    public void suppliedLifetimesAreCalledOutAsNotFitted() {
        var volume = ZStackResults.volumes(parse("""
                {"products": [{"file": "/data/out/RegionA.ome.tif",
                               "image_id": "RegionA", "format": "ome-tiff",
                               "pooled": {"user_supplied_tau": true}}]}
                """)).get(0);

        assertTrue(volume.pooledLines().contains(
                "Lifetimes were supplied, not fitted"), volume.describe());
    }

    @Test
    public void aResultWithoutProductsReadsAsEmpty() {
        assertTrue(ZStackResults.volumes(parse("{\"output_dir\": \"/data/out\"}")).isEmpty());
        assertTrue(ZStackResults.volumes(null).isEmpty());
    }

    @Test
    public void aProductWithoutAFileIsSkipped() {
        assertTrue(ZStackResults.volumes(parse(
                "{\"products\": [{\"image_id\": \"RegionA\"}]}")).isEmpty());
    }

    @Test
    public void aStackThatCouldNotBeWrittenIsReported() {
        var problems = ZStackResults.problems(parse("""
                {"products": [],
                 "stacks": [{"label": "RegionA", "error": "no slice maps"},
                            {"label": "RegionB"}]}
                """));

        assertEquals(1, problems.size());
        assertEquals("RegionA: no slice maps", problems.get(0));
    }

    @Test
    public void aZarrStoreOffersItsMetadataFilesToo() {
        var candidates = ZStackResults.openableAs(
                Path.of("/data/out/RegionA.ome.zarr"), "ome-zarr");

        assertEquals(Path.of("/data/out/RegionA.ome.zarr"), candidates.get(0));
        assertTrue(candidates.contains(Path.of("/data/out/RegionA.ome.zarr/zarr.json")));
        assertTrue(candidates.contains(Path.of("/data/out/RegionA.ome.zarr/.zattrs")));
    }

    @Test
    public void aPlainFileIsOnlyEverItself() {
        var candidates = ZStackResults.openableAs(
                Path.of("/data/out/RegionA.ome.tif"), "ome-tiff");

        assertEquals(1, candidates.size());
        assertFalse(ZStackResults.volumes(parse("""
                {"products": [{"file": "/data/out/RegionA.ome.tif",
                               "image_id": "RegionA",
                               "group_dir": "/data/out/RegionA",
                               "format": "ome-tiff"}]}
                """)).get(0).isZarr());
    }

    @Test
    public void aScanIsDescribedBeforeAnythingRuns() {
        String described = FlimKitBridgeExtension.describeStacks(parse("""
                {"n_stacks": 2, "n_slices": 20,
                 "stacks": [{"label": "RegionA", "n_slices": 12,
                             "z_first": 1, "z_last": 12},
                            {"label": "RegionB", "n_slices": 8,
                             "z_first": 1, "z_last": 8}]}
                """));

        assertTrue(described.startsWith("Found 2 z-stacks:"), described);
        assertTrue(described.contains("RegionA - 12 slices (z 1 to 12)"), described);
        assertTrue(described.contains("RegionB - 8 slices (z 1 to 8)"), described);
    }
}
