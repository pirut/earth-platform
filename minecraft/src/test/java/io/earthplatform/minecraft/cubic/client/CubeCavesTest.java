package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.earthplatform.minecraft.cubic.play.EarthPlaces;
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

    @Test void meshesAcrossALongTeleportDoNotOverflowTheSearch() {
        // Old and new near meshes can span a continent and several kilometres in height.
        assertNull(CubeCaves.reach(8, 8, 8, faces(), -600_000, -500, 0, 0, 0, 50_000));
        // A smaller jump fits in an int but still leaves too much empty space to flood.
        assertNull(CubeCaves.reach(8, 8, 8, faces(), -100, -40, -15, 100, 40, 15));
    }

    /**
     * The reported crash: teleporting from Africa to Everest on a default world, then drawing once the new
     * ground has meshes while the old ones are still loaded. The int volume of that box is negative, which is
     * the count fastutil refuses.
     */
    @Test void africaToEverestDoesNotSizeTheCaveSetFromANegativeCount() {
        MercatorGrid grid = new MercatorGrid(EarthSettings.at(EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE).metresPerBlock());
        EarthPlaces.Place africa = EarthPlaces.byId("serengeti").orElseThrow();
        EarthPlaces.Place everest = EarthPlaces.byId("everest").orElseThrow();
        int ax = cube(grid.blockX(new GeoPoint(africa.latitude(), africa.longitude())));
        int az = cube(grid.blockZ(new GeoPoint(africa.latitude(), africa.longitude())));
        int ex = cube(grid.blockX(new GeoPoint(everest.latitude(), everest.longitude())));
        int ez = cube(grid.blockZ(new GeoPoint(everest.latitude(), everest.longitude())));
        // The Serengeti plateau and Everest's summit. The int product's sign depends on the exact span;
        // these heights are the ones that wrap negative and hit fastutil.
        int ay = 1400 >> 4, ey = 8849 >> 4;
        int reach = Cubic.MAX_HORIZONTAL_RADIUS, vertical = Cubic.VERTICAL_RADIUS;
        int minX = Math.min(ax, ex) - reach, maxX = Math.max(ax, ex) + reach;
        int minY = Math.min(ay, ey) - vertical, maxY = Math.max(ay, ey) + vertical;
        int minZ = Math.min(az, ez) - reach, maxZ = Math.max(az, ez) + reach;
        int width = maxX - minX + 1, height = maxY - minY + 1, depth = maxZ - minZ + 1;
        int product = width * height * depth;
        assertTrue(product < 0, "int volume wrapped to " + product + " for " + width + "x" + height + "x" + depth);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () -> new LongOpenHashSet(product));
        assertEquals("The expected number of elements must be nonnegative", rejected.getMessage());
        // Standing on the Everest landing, inside the box, is when the old sizing ran.
        assertNull(CubeCaves.reach((ex << 4) + 8, (ey << 4) + 8, (ez << 4) + 8, faces(), minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static int cube(double block) { return (int) Math.floor(block) >> 4; }

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

    @Test void shadowReachesOnlyDownTheLightsWay() {
        double[] cave = {0, 0, 0, 32, 16, 32};
        // Light straight overhead: shadows fall straight down.
        assertTrue(CubeCaves.shadowReaches(0, 64, 0, 16, 80, 16, 0, -1, 0, cave), "above the cave");
        assertFalse(CubeCaves.shadowReaches(0, -64, 0, 16, -48, 16, 0, -1, 0, cave), "below it: the shadow falls away");
        assertFalse(CubeCaves.shadowReaches(64, 64, 0, 80, 80, 16, 0, -1, 0, cave), "beside it");
        assertTrue(CubeCaves.shadowReaches(8, 4, 8, 12, 8, 12, 0, -1, 0, cave), "inside it");
        // A low light: a cube up and to the side reaches the cave only along the slant.
        double s = Math.sqrt(0.5);
        assertTrue(CubeCaves.shadowReaches(-80, 64, 0, -64, 80, 16, s, -s, 0, cave));
        assertFalse(CubeCaves.shadowReaches(80, 64, 0, 96, 80, 16, s, -s, 0, cave));
    }
}
