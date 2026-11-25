package io.earthplatform.minecraft.cubic.play;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.BteDymaxionProjection;
import io.earthplatform.geography.globe.GlobeProjection;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code earthplatform:reached_place}: real places by latitude and longitude, whatever the world's home. */
class PlaceTriggerTest {
    /** A world's grid the way the Earth world type makes one: a block is a metre across at the home latitude. */
    private static GlobeProjection world(double homeLatitude) {
        return new MercatorGrid(1 / Math.cos(Math.toRadians(homeLatitude)));
    }

    private static PlaceTrigger.Instance at(double lat, double lon, double radius) {
        return new PlaceTrigger.Instance(Optional.empty(), lat, lon, radius, MinMaxBounds.Doubles.ANY);
    }

    /** What the server asks of a player standing at block (x, z) in a world: the world's own projection, then the criterion. */
    private static boolean standing(GlobeProjection grid, PlaceTrigger.Instance place, double x, double z, double elevation) {
        GeoPoint here = grid.tryGeographic(x, z);
        return here != null && place.matches(here.latitude(), here.longitude(), elevation);
    }

    private static final double[][] LANDMARKS = {
            {37.7459, -119.5332},   // Half Dome
            {48.8584, 2.2945},      // Eiffel Tower
            {27.9881, 86.9250},     // Mount Everest
            {-33.8568, 151.2153},   // Sydney Opera House
            {-13.1631, -72.5450},   // Machu Picchu
    };

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // The same landmark, in worlds started at different latitudes ----------------------------------

    @Test void aLandmarkTriggersAtItsOwnBlocksInEveryWorld() {
        for (double home : new double[]{0, 37.7, 64}) {
            GlobeProjection grid = world(home);
            for (double[] landmark : LANDMARKS) {
                PlaceTrigger.Instance place = at(landmark[0], landmark[1], 100);
                double[] block = grid.toBlock(landmark[0], landmark[1]);
                // The block the landmark is at, and the ones around it.
                assertTrue(standing(grid, place, block[0], block[1], 0), "home " + home + " landmark " + landmark[0] + "," + landmark[1]);
                double perBlock = grid.groundMetresPerBlock(block[0], block[1]);
                double inside = 90 / perBlock, outside = 110 / perBlock;
                assertTrue(standing(grid, place, block[0] + inside, block[1], 0), "east inside, home " + home);
                assertTrue(standing(grid, place, block[0], block[1] - inside, 0), "north inside, home " + home);
                assertTrue(standing(grid, place, block[0] - inside * 0.7, block[1] + inside * 0.7, 0), "diagonal inside, home " + home);
                assertFalse(standing(grid, place, block[0] + outside, block[1], 0), "east outside, home " + home);
                assertFalse(standing(grid, place, block[0], block[1] + outside, 0), "south outside, home " + home);
                assertFalse(standing(grid, place, block[0] - outside, block[1] - outside, 0), "diagonal outside, home " + home);
            }
        }
    }

    @Test void theSameLandmarkIsAtDifferentBlocksInDifferentWorlds() {
        double[] equator = world(0).toBlock(48.8584, 2.2945), yosemite = world(37.7).toBlock(48.8584, 2.2945), arctic = world(64).toBlock(48.8584, 2.2945);
        assertNotEquals(equator[0], yosemite[0], 1);
        assertNotEquals(yosemite[0], arctic[0], 1);
        // A fixed block position is a different place in each: the reason the criterion is in degrees.
        PlaceTrigger.Instance paris = at(48.8584, 2.2945, 100);
        assertTrue(standing(world(0), paris, equator[0], equator[1], 0));
        assertFalse(standing(world(37.7), paris, equator[0], equator[1], 0));
        assertFalse(standing(world(64), paris, equator[0], equator[1], 0));
    }

    @Test void theRadiusIsRealMetresNotBlocks() {
        // 150 m of ground east of the Eiffel Tower is a different number of blocks in each world.
        for (double home : new double[]{0, 37.7, 64}) {
            GlobeProjection grid = world(home);
            double[] tower = grid.toBlock(48.8584, 2.2945);
            double perBlock = grid.groundMetresPerBlock(tower[0], tower[1]);
            // A block is a metre at the home latitude, and cos(48.86°) / cos(home) of a metre here.
            assertEquals(Math.cos(Math.toRadians(48.8584)) / Math.cos(Math.toRadians(home)), perBlock, 1e-3);
            PlaceTrigger.Instance tower150 = at(48.8584, 2.2945, 150);
            assertTrue(standing(grid, tower150, tower[0] + 140 / perBlock, tower[1], 0), "140 m, home " + home);
            assertFalse(standing(grid, tower150, tower[0] + 160 / perBlock, tower[1], 0), "160 m, home " + home);
        }
    }

    @Test void theConversionRoundTripsThroughTheWorldsProjection() {
        for (double home : new double[]{0, 37.7, 64}) {
            GlobeProjection grid = world(home);
            for (double[] landmark : LANDMARKS) {
                double[] block = grid.toBlock(landmark[0], landmark[1]);
                GeoPoint back = grid.tryGeographic(block[0], block[1]);
                assertEquals(0, PlaceTrigger.metresBetween(landmark[0], landmark[1], back.latitude(), back.longitude()), 0.01);
            }
        }
    }

    // Distance ------------------------------------------------------------------------------------

    @Test void distanceIsTheGreatCircle() {
        // A degree of latitude along a meridian.
        assertEquals(111_195, PlaceTrigger.metresBetween(10, 20, 11, 20), 1);
        // A degree of longitude shrinks with the cosine of latitude.
        assertEquals(111_195 * Math.cos(Math.toRadians(60)), PlaceTrigger.metresBetween(60, 0, 60, 1), 50);
        assertEquals(0, PlaceTrigger.metresBetween(12.5, -45, 12.5, -45), 0);
        // Half way round the Earth.
        assertEquals(Math.PI * 6_371_008.8, PlaceTrigger.metresBetween(0, 0, 0, 180), 1);
        assertEquals(Math.PI * 6_371_008.8, PlaceTrigger.metresBetween(90, 0, -90, 0), 1);
    }

    // Edge cases ----------------------------------------------------------------------------------

    @Test void theAntimeridianIsNoEdge() {
        // Taveuni, Fiji: the date line runs through the island. 2 km east of 180° is at 179.98° W.
        PlaceTrigger.Instance fiji = at(-16.85, 180, 3000);
        assertTrue(fiji.matches(-16.85, -179.98, 0));
        assertTrue(fiji.matches(-16.85, 179.98, 0));
        assertTrue(fiji.matches(-16.85, 180, 0));
        assertTrue(fiji.matches(-16.85, -180, 0));
        assertFalse(fiji.matches(-16.85, -179.9, 0));
        assertFalse(fiji.matches(-16.85, 179.9, 0));
        // And a place named at -180 is the same as one at 180.
        assertTrue(at(0, -180, 1000).matches(0, 179.9999, 0));
        assertTrue(at(0, 179.9999, 1000).matches(0, -179.9999, 0));
    }

    @Test void theAntimeridianInAWorldIsTheWorldsEdgeOnBothSides() {
        GlobeProjection grid = world(37.7);
        PlaceTrigger.Instance fiji = at(-16.85, 180, 3000);
        double[] edge = grid.toBlock(-16.85, 180);
        double perBlock = grid.groundMetresPerBlock(edge[0], edge[1]);
        // The Earth repeats east and west: the block 2 km east of the edge, and the same spot in the next copy of the world.
        assertTrue(standing(grid, fiji, edge[0] + 2000 / perBlock, edge[1], 0));
        assertTrue(standing(grid, fiji, edge[0] - 2000 / perBlock, edge[1], 0));
        double width = grid.worldWidthBlocks();
        assertTrue(standing(grid, fiji, edge[0] + 2000 / perBlock + width, edge[1], 0));
        assertTrue(standing(grid, fiji, edge[0] - 2000 / perBlock - width, edge[1], 0));
        assertFalse(standing(grid, fiji, edge[0] + 4000 / perBlock, edge[1], 0));
        assertFalse(standing(grid, fiji, edge[0] - 4000 / perBlock - width, edge[1], 0));
    }

    @Test void thePolesHaveNoLongitude() {
        PlaceTrigger.Instance north = at(90, 0, 100), south = at(-90, 0, 100);
        // Any longitude is the same place at a pole.
        for (double lon : new double[]{-180, -90, 0, 45, 179.99}) {
            assertTrue(north.matches(89.9999, lon, 0), "north, longitude " + lon);
            assertTrue(south.matches(-89.9999, lon, 0), "south, longitude " + lon);
        }
        assertTrue(north.matches(90, 123, 0));
        assertTrue(south.matches(-90, -77, 0));
        // 89.998° is 222 m from the pole.
        assertFalse(north.matches(89.998, 10, 0));
        assertFalse(north.matches(-89.9999, 10, 0));
        // A place named at a pole by another longitude is the same place.
        assertTrue(at(90, 77, 100).matches(90, -123, 0));
        // Near, but not at, a pole longitude matters again: two points 1° from the pole on opposite sides are 2° (222 km) apart.
        assertTrue(at(89, 0, 250_000).matches(89, 180, 0));
        assertFalse(at(89, 0, 200_000).matches(89, 180, 0));
    }

    @Test void theDymaxionProjectionReachesThePoles() {
        BteDymaxionProjection grid = new BteDymaxionProjection(1.0);
        for (double lat : new double[]{90, -90}) {
            double[] block = grid.toBlock(lat, 0);
            assertTrue(standing(grid, at(lat, 0, 100), block[0], block[1], 0), "pole " + lat);
            assertTrue(standing(grid, at(lat, 0, 100), block[0] + 20, block[1] + 20, 0), "20 blocks from pole " + lat);
            assertFalse(standing(grid, at(lat, 0, 100), block[0] + 200, block[1] + 200, 0), "280 blocks from pole " + lat);
        }
    }

    @Test void theRadiusBoundaryIsInclusive() {
        // Two places a known distance apart on a meridian.
        double metres = PlaceTrigger.metresBetween(37.7459, -119.5332, 37.7469, -119.5332);
        assertTrue(at(37.7459, -119.5332, metres).matches(37.7469, -119.5332, 0));
        assertTrue(at(37.7459, -119.5332, Math.nextUp(metres)).matches(37.7469, -119.5332, 0));
        assertFalse(at(37.7459, -119.5332, Math.nextDown(metres)).matches(37.7469, -119.5332, 0));
        // And one a known distance away along a parallel, and on a slant.
        double slant = PlaceTrigger.metresBetween(10, 10, 10.0003, 10.0004);
        assertTrue(at(10, 10, slant).matches(10.0003, 10.0004, 0));
        assertFalse(at(10, 10, Math.nextDown(slant)).matches(10.0003, 10.0004, 0));
        // The default 100 m: 0.0008° of latitude is 89 m, 0.0010° is 111 m.
        assertTrue(at(37.7459, -119.5332, 100).matches(37.7467, -119.5332, 0));
        assertFalse(at(37.7459, -119.5332, 100).matches(37.7469, -119.5332, 0));
    }

    @Test void aTinyRadiusStillReachesThePlaceItself() {
        assertTrue(at(1, 2, Double.MIN_NORMAL).matches(1, 2, 0));
        assertFalse(at(1, 2, Double.MIN_NORMAL).matches(1.0001, 2, 0));
    }

    @Test void aHugeRadiusReachesTheOtherSideOfTheWorld() {
        assertTrue(at(0, 0, PlaceTrigger.MAX_RADIUS_METRES).matches(0, 180, 0));
        assertTrue(at(90, 0, PlaceTrigger.MAX_RADIUS_METRES).matches(-90, 0, 0));
    }

    // Height ---------------------------------------------------------------------------------------

    @Test void heightIsRealMetresAboveSeaLevel() {
        PlaceTrigger.Instance summit = new PlaceTrigger.Instance(Optional.empty(), 27.9881, 86.925, 200, MinMaxBounds.Doubles.atLeast(8000));
        assertTrue(summit.matches(27.9881, 86.925, 8848));
        assertTrue(summit.matches(27.9881, 86.925, 8000));
        assertFalse(summit.matches(27.9881, 86.925, 7999.9));
        assertFalse(summit.matches(27.9881, 86.925, 5300));
        PlaceTrigger.Instance dead = new PlaceTrigger.Instance(Optional.empty(), 31.5, 35.5, 5000, MinMaxBounds.Doubles.between(-440, -400));
        assertTrue(dead.matches(31.5, 35.5, -430));
        assertFalse(dead.matches(31.5, 35.5, 0));
        assertFalse(dead.matches(31.5, 35.5, -441));
        // A player's block Y is in metres from sea level at EarthGenerator.SEA: standing on the sea is 0.
        assertEquals(62, EarthGenerator.SEA);
        assertTrue(new PlaceTrigger.Instance(Optional.empty(), 0, 0, 100, MinMaxBounds.Doubles.exactly(0)).matches(0, 0, 62.0 - EarthGenerator.SEA));
    }

    @Test void noHeightBoundsMeansAnyHeight() {
        PlaceTrigger.Instance place = at(0, 0, 100);
        assertTrue(place.matches(0, 0, -11000));
        assertTrue(place.matches(0, 0, 8848));
        assertTrue(place.matches(0, 0, 16000));
    }

    // The JSON shape --------------------------------------------------------------------------------

    private static PlaceTrigger.Instance parse(String json) {
        return PlaceTrigger.Instance.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow(IllegalStateException::new);
    }

    @Test void theMinimalConditionsNeedOnlyAPlace() {
        PlaceTrigger.Instance place = parse("{\"latitude\": 37.7459, \"longitude\": -119.5332}");
        assertEquals(37.7459, place.latitude());
        assertEquals(-119.5332, place.longitude());
        assertEquals(100, place.radiusMetres());
        assertTrue(place.elevation().isAny());
        assertTrue(place.player().isEmpty());
    }

    @Test void everyConditionIsRead() {
        PlaceTrigger.Instance place = parse("""
                {"latitude": 27.9881, "longitude": 86.925, "radius_m": 250.5, "elevation_m": {"min": 8000, "max": 9000},
                 "player": [{"condition": "minecraft:survives_explosion"}]}""");
        assertEquals(250.5, place.radiusMetres());
        assertEquals(Optional.of(8000.0), place.elevation().min());
        assertEquals(Optional.of(9000.0), place.elevation().max());
        assertTrue(place.player().isPresent());
        assertTrue(parse("{\"latitude\": 1, \"longitude\": 2, \"elevation_m\": {\"max\": -10}}").elevation().max().isPresent());
    }

    @Test void theShapeRoundTrips() {
        PlaceTrigger.Instance place = parse("{\"latitude\": -33.8568, \"longitude\": 151.2153, \"radius_m\": 60, \"elevation_m\": {\"min\": 0, \"max\": 100}}");
        assertEquals(place, PlaceTrigger.Instance.CODEC.parse(JsonOps.INSTANCE, PlaceTrigger.Instance.CODEC.encodeStart(JsonOps.INSTANCE, place).getOrThrow()).getOrThrow());
    }

    @Test void badConditionsAreRefusedWhenTheAdvancementLoads() {
        for (String bad : new String[]{
                "{\"longitude\": 1}", "{\"latitude\": 1}", "{\"latitude\": 91, \"longitude\": 0}", "{\"latitude\": -90.5, \"longitude\": 0}",
                "{\"latitude\": 0, \"longitude\": 181}", "{\"latitude\": 0, \"longitude\": -180.1}", "{\"latitude\": 0, \"longitude\": 0, \"radius_m\": 0}",
                "{\"latitude\": 0, \"longitude\": 0, \"radius_m\": -5}", "{\"latitude\": 0, \"longitude\": 0, \"radius_m\": 21000000}",
                "{\"latitude\": \"north\", \"longitude\": 0}"}) {
            assertTrue(PlaceTrigger.Instance.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(bad)).error().isPresent(), bad);
        }
        for (String good : new String[]{"{\"latitude\": 90, \"longitude\": 180}", "{\"latitude\": -90, \"longitude\": -180}", "{\"latitude\": 0, \"longitude\": 0, \"radius_m\": 20100000}"}) {
            assertTrue(PlaceTrigger.Instance.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(good)).result().isPresent(), good);
        }
    }

    @Test void theTriggerIsNamedForTheMod() {
        assertEquals("earthplatform:reached_place", PlaceTrigger.ID.toString());
        assertSame(PlaceTrigger.Instance.CODEC, PlaceTrigger.INSTANCE.codec());
    }
}
