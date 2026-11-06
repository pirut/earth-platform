package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.CubeKey;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CubeCavesTest {
    private static Long2LongOpenHashMap faces() {
        Long2LongOpenHashMap map = new Long2LongOpenHashMap();
        map.defaultReturnValue(CubeCaves.OPEN);
        return map;
    }

    @Test void openWorldReachesEveryCube() {
        LongOpenHashSet reached = CubeCaves.reach(8, 8, 8, faces(), -4, -2, -4, 4, 2, 4);
        assertNotNull(reached);
        assertEquals(9 * 5 * 9, reached.size());
    }

    @Test void cameraOutsideTheCubesCullsNothing() {
        assertNull(CubeCaves.reach(8, 8 + 16 * 40, 8, faces(), -4, -2, -4, 4, 2, 4));
    }

    @Test void solidWallHidesWhatIsBehindItButIsItselfDrawn() {
        Long2LongOpenHashMap map = faces();
        for (int y = -2; y <= 2; y++) for (int z = -4; z <= 4; z++) map.put(CubeKey.pack(2, y, z), 0L);
        LongOpenHashSet reached = CubeCaves.reach(8, 8, 8, map, -4, -2, -4, 4, 2, 4);
        for (int y = -2; y <= 2; y++) for (int z = -4; z <= 4; z++) {
            assertTrue(reached.contains(CubeKey.pack(2, y, z)), "the wall's own faces show");
            assertFalse(reached.contains(CubeKey.pack(3, y, z)), "behind the wall");
            assertFalse(reached.contains(CubeKey.pack(4, y, z)), "behind the wall");
        }
    }

    @Test void holeInTheWallShowsTheCubesBehindIt() {
        Long2LongOpenHashMap map = faces();
        for (int y = -2; y <= 2; y++) for (int z = -4; z <= 4; z++) if (y != 0 || z != 0) map.put(CubeKey.pack(2, y, z), 0L);
        LongOpenHashSet reached = CubeCaves.reach(8, 8, 8, map, -4, -2, -4, 4, 2, 4);
        assertTrue(reached.contains(CubeKey.pack(3, 0, 0)));
        assertTrue(reached.contains(CubeKey.pack(3, 1, 1)), "spreads out again behind the hole");
        assertTrue(reached.contains(CubeKey.pack(4, -2, 3)));
    }

    @Test void tunnelBendsOnlyWhereItsAirJoinsTheFaces() {
        // A cube whose air joins only west and east: through it a search goes straight on, never up.
        VisGraph graph = new VisGraph();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (y < 6 || y > 9 || z < 6 || z > 9) graph.setOpaque(new BlockPos(x, y, z));
        }
        long tube = CubeCaves.of(graph.resolve());
        assertTrue(CubeCaves.joins(tube, Direction.WEST.ordinal(), Direction.EAST.ordinal()));
        assertFalse(CubeCaves.joins(tube, Direction.WEST.ordinal(), Direction.UP.ordinal()));
        Long2LongOpenHashMap map = faces();
        // Rock everywhere but a tube along x through y 0, z 0.
        for (int x = -4; x <= 4; x++) for (int y = -2; y <= 2; y++) for (int z = -4; z <= 4; z++) {
            if (x == 0 && y == 0 && z == 0) continue;
            map.put(CubeKey.pack(x, y, z), y == 0 && z == 0 ? tube : 0L);
        }
        LongOpenHashSet reached = CubeCaves.reach(8, 8, 8, map, -4, -2, -4, 4, 2, 4);
        assertTrue(reached.contains(CubeKey.pack(4, 0, 0)), "down the tube");
        assertTrue(reached.contains(CubeKey.pack(0, 1, 0)), "the rock round the camera's cube is drawn");
        assertFalse(reached.contains(CubeKey.pack(3, 1, 0)), "rock beside the tube");
        assertFalse(reached.contains(CubeKey.pack(3, 0, 2)));
    }

    @Test void fullyOpenGraphIsOpen() {
        assertEquals(CubeCaves.OPEN, CubeCaves.of(new VisGraph().resolve()));
    }
}
