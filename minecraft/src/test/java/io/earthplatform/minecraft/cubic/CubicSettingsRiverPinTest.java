package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.geography.globe.RiverMap;
import io.earthplatform.geography.globe.SurfaceWaterSource;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies HYDRO-05 version pinning for global-coordinate {@link RiverMap} joins in {@link CubicSettings}. */
class CubicSettingsRiverPinTest {
    @Test
    void newWorldsPinGlobalJoinV2WhileLegacyPinsStayOnV1(@TempDir Path tempDir) throws Exception {
        assertEquals(RiverMap.V2_ID, CubicSettings.riversPin());
        assertTrue(CubicSettings.isGlobalRiverJoin(RiverMap.V2_ID), "v2 pin enables global join");
        assertFalse(CubicSettings.isGlobalRiverJoin(SurfaceWaterSource.DATASET_ID), "legacy v1 pin preserves window-local join");
        assertFalse(CubicSettings.isGlobalRiverJoin(""), "pre-river pin stays non-v2");
        assertThrows(IllegalStateException.class, () -> CubicSettings.isGlobalRiverJoin("unknown-rivers-v99"));

        CubicSettings.pinEarth(tempDir, EarthSettings.defaults(), "Test Home");
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(tempDir.resolve("generator.properties"), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        assertEquals(RiverMap.V2_ID, props.getProperty("earth.rivers"), "newly pinned worlds must use RiverMap.V2_ID");
    }
}
