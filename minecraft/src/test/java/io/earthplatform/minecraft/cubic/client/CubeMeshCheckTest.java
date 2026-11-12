package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.minecraft.cubic.gen.SurfacePacketFixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CubeMeshCheckTest {
    @BeforeAll static void bootstrap() { SurfacePacketFixtures.biomes(); }

    @Test void theMeshCheckCatchesAWrongCaveGraph() {
        // skipInterior's answer for a sealed cube: no mesh, dark, no face joined. The plain mesh found a cave joining two.
        CubeMesher.Built skipped = new CubeMesher.Built(1, 0, List.of(), new byte[4096], 0L);
        CubeMesher.Built plain = new CubeMesher.Built(1, 0, List.of(), new byte[4096], 1L << 7);
        assertNotNull(CubeMesher.differ(skipped, plain, false));
        assertTrue(CubeMesher.differ(skipped, plain, false).startsWith("visibility"));
        assertNull(CubeMesher.differ(plain, new CubeMesher.Built(1, 0, List.of(), new byte[4096], 1L << 7), false));
    }
}
