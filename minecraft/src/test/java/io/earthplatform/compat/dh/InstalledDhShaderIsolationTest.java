package io.earthplatform.compat.dh;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Existing installed-DH proofs retain their DH-only runtime even in the shader candidate. */
class InstalledDhShaderIsolationTest {
    @Test void installedDhOnlyPartitionDoesNotLoadShaderModsOrPhoton() {
        assertEquals("installed-dh", System.getProperty("earthplatform.test.partition"));
        assertNull(System.getProperty("earthplatform.test.isolatedDhRuntime"));
        ClassLoader loader = getClass().getClassLoader();
        assertNotNull(loader.getResource("com/seibel/distanthorizons/api/DhApi.class"));
        assertNull(loader.getResource("net/irisshaders/iris/api/v0/IrisApi.class"));
        assertNull(loader.getResource("net/caffeinemc/mods/sodium/service/SodiumWorkarounds.class"));
        assertNull(loader.getResource("shaders/world1/dh_terrain.fsh"));
    }
}
