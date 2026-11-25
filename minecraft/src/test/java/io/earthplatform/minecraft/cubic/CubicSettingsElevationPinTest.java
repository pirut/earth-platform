package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.geography.globe.BareEarth;
import io.earthplatform.geography.globe.CopernicusFill;
import io.earthplatform.geography.globe.Dgm5Elevation;
import io.earthplatform.geography.globe.TerrariumTileSource;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies elevation pin upgrades, legacy pre-DGM5 bareEarth freeze, and BareEarth cache namespacing. */
class CubicSettingsElevationPinTest {
    @Test
    void newWorldsUseTerrariumV2AndGlo30FillV3WhileLegacyBaselineStaysFrozen() {
        String current = CubicSettings.elevation();
        assertTrue(current.startsWith(TerrariumTileSource.V2_ID + "+"), "new worlds should use terrarium-v2: " + current);
        assertTrue(current.contains("+" + CopernicusFill.GEOID_ID), "new worlds should use glo30-fill-v3: " + current);
        assertTrue(current.endsWith("+" + Dgm5Elevation.ID), "new worlds should keep DGM5 suffix: " + current);

        String legacy = CubicSettings.legacyBaseline();
        assertTrue(legacy.startsWith(TerrariumTileSource.DATASET_ID + "+"), "legacy baseline stays on terrarium-v1: " + legacy);
        assertTrue(legacy.contains("+" + CopernicusFill.ICE_ID), "legacy baseline stays on glo30-fill-v2: " + legacy);
        assertFalse(legacy.contains(TerrariumTileSource.V2_ID), "legacy baseline must never contain terrarium-v2");
        assertFalse(legacy.contains(CopernicusFill.GEOID_ID), "legacy baseline must never contain glo30-fill-v3");
    }

    @Test
    void preDgm5PinsResolveBareEarthToLegacyBaselineWithoutV2OrGeoid() {
        String legacy = CubicSettings.legacyBaseline();
        for (String preDgm5 : new String[] {
                TerrariumTileSource.DATASET_ID,
                TerrariumTileSource.DATASET_ID + "+" + CopernicusFill.DATASET_ID,
                TerrariumTileSource.DATASET_ID + "+" + CopernicusFill.ICE_ID + "+hrdem-2m-v1+3dep-13-v3+rema-v2.0+ign-rgealti-v1+gsi-dem-v1"
        }) {
            String resolved = CubicSettings.bareEarthPin(preDgm5);
            assertEquals(legacy, resolved, "pre-DGM5 pin must resolve to frozen legacyBaseline()");
            assertFalse(resolved.contains(TerrariumTileSource.V2_ID));
            assertFalse(resolved.contains(CopernicusFill.GEOID_ID));
        }

        String dgm5V1 = legacy + "+" + Dgm5Elevation.ID;
        assertEquals(dgm5V1, CubicSettings.bareEarthPin(dgm5V1), "post-DGM5 v1 save keeps its v1 pin");

        String dgm5V2 = CubicSettings.elevation();
        assertEquals(dgm5V2, CubicSettings.bareEarthPin(dgm5V2), "new v2 save keeps its v2 pin");
    }

    @Test
    void bareEarthCacheNamespacesV2SeparatelyFromLegacyV1() {
        Path root = Path.of("/tmp/earthplatform-cache-test");
        Path legacyBase = root.resolve(BareEarth.ID);

        assertEquals(legacyBase, CubicSettings.bareEarthCache(root, CubicSettings.legacyBaseline()),
                "legacy v1 pin keeps existing bare-earth-v4 folder");
        assertEquals(legacyBase, CubicSettings.bareEarthCache(root, TerrariumTileSource.DATASET_ID + "+" + CopernicusFill.DATASET_ID),
                "v1+glo30-fill-v1 keeps existing bare-earth-v4 folder");

        Path v2Cache = CubicSettings.bareEarthCache(root, CubicSettings.elevation());
        assertNotEquals(legacyBase, v2Cache, "v2 pin must use a namespaced subfolder");
        assertTrue(v2Cache.startsWith(legacyBase), "v2 cache should live inside bare-earth-v4/");

        Path v2Only = CubicSettings.bareEarthCache(root, TerrariumTileSource.V2_ID + "+" + CopernicusFill.ICE_ID);
        Path geoidOnly = CubicSettings.bareEarthCache(root, TerrariumTileSource.DATASET_ID + "+" + CopernicusFill.GEOID_ID);
        assertNotEquals(legacyBase, v2Only);
        assertNotEquals(legacyBase, geoidOnly);
        assertNotEquals(v2Only, geoidOnly);
    }
}
