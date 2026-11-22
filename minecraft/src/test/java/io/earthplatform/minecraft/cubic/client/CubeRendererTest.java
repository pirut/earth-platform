package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.gen.SurfacePacketFixtures;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

class CubeRendererTest {
    private static final long FRAME_60 = 16_000_000L;

    @Test void staleMeshesGiveWayToNewerOnesOnlyWhileTheCubeHasAMeshAndNotForever() {
        assertFalse(CubeRenderer.dropStale(3, 3, true, 0));
        assertTrue(CubeRenderer.dropStale(3, 4, true, 0));
        assertFalse(CubeRenderer.dropStale(3, 4, false, 0));
        assertTrue(CubeRenderer.dropStale(3, 4, true, CubeRenderer.STALE_DROPS - 1));
        assertFalse(CubeRenderer.dropStale(3, 4, true, CubeRenderer.STALE_DROPS));
    }

    /** A clock the test moves: each upload takes {@code cost}. */
    private static final class Clock {
        long now, cost;
        long read() { return now; }
    }

    /** Drains the queue, each mesh taking the clock's cost; meshes named "stale" are thrown away. */
    private static List<String> drain(ArrayDeque<String> queue, CubeUploadBudget budget, Clock clock) {
        List<String> uploaded = new ArrayList<>();
        CubeRenderer.drain(queue, mesh -> {
            clock.now += clock.cost;
            if (mesh.startsWith("stale")) return false;
            uploaded.add(mesh);
            return true;
        }, budget, clock::read);
        return uploaded;
    }

    @Test void aShadowPassThatFoundNothingLeavesTheViewItsWholeShare() {
        CubeUploadBudget budget = new CubeUploadBudget(CubeWorkBudget.uploadNanos(FRAME_60));
        Clock clock = new Clock();
        ArrayDeque<String> queue = new ArrayDeque<>();
        assertEquals(List.of(), drain(queue, budget, clock));
        // The meshes finish while the shadow map is drawn; the view's call comes 3 ms into the frame.
        queue.addAll(List.of("a", "b", "c"));
        clock.now = 3_000_000L;
        clock.cost = 100_000L;
        assertEquals(List.of("a", "b", "c"), drain(queue, budget, clock));
    }

    @Test void timeDrawingBetweenTheCallsIsNotCountedButUploadTimeIs() {
        CubeUploadBudget budget = new CubeUploadBudget(2_000_000L);
        Clock clock = new Clock();
        clock.cost = 1_000_000L;
        ArrayDeque<String> queue = new ArrayDeque<>(List.of("shadow"));
        assertEquals(List.of("shadow"), drain(queue, budget, clock));
        clock.now = 10_000_000L;
        clock.cost = 400_000L;
        queue.addAll(List.of("a", "b", "c", "d", "e"));
        // 1 ms spent: a, b and c bring it to 2.2 ms, and d waits.
        assertEquals(List.of("a", "b", "c"), drain(queue, budget, clock));
        assertEquals(List.of("d", "e"), List.copyOf(queue));
    }

    @Test void theFirstUploadAlwaysGoesAndOnlyAnUploadUsesThatUp() {
        CubeUploadBudget budget = new CubeUploadBudget(0);
        Clock clock = new Clock();
        clock.cost = 5_000_000L;
        ArrayDeque<String> queue = new ArrayDeque<>(List.of("stale1", "stale2", "a", "b"));
        assertEquals(List.of("a"), drain(queue, budget, clock));
        assertEquals(List.of("b"), List.copyOf(queue));
    }

    @Test void withNoBudgetEveryMeshGoesUp() {
        Clock clock = new Clock();
        clock.cost = 5_000_000L;
        ArrayDeque<String> queue = new ArrayDeque<>(List.of("a", "b", "c"));
        assertEquals(List.of("a", "b", "c"), drain(queue, null, clock));
    }

    @Test void theNearestCubesAreSnapshottedFirstWhenTheBudgetStopsPartway() {
        // Three ready cubes found farthest, middle, nearest, with 48 slots free: all would fit, but at 600 us a
        // snapshot a 1 ms budget stops after two.
        long[] order = {CubeRenderer.orderEntry(300, 0), CubeRenderer.orderEntry(200, 1), CubeRenderer.orderEntry(100, 2)};
        int count = Math.min(order.length, 48);
        Clock clock = new Clock();
        CubeWorkBudget budget = new CubeWorkBudget(0, 1_000_000L, count);
        List<Integer> submitted = new ArrayList<>();
        CubeRenderer.submitNearest(order, budget, clock::read, i -> { submitted.add(i); clock.now += 600_000L; });
        assertEquals(List.of(2, 1), submitted);
    }

    @Test void everyPickGoesWhileTheBudgetLasts() {
        long[] order = {CubeRenderer.orderEntry(300, 0), CubeRenderer.orderEntry(200, 1), CubeRenderer.orderEntry(1L << 50, 2)};
        List<Integer> submitted = new ArrayList<>();
        CubeRenderer.submitNearest(order, new CubeWorkBudget(0, 1_000_000L, 3), () -> 0, submitted::add);
        assertEquals(List.of(1, 0, 2), submitted);
    }

    private static Cube cube(int cx, int cy, int cz, boolean solid) {
        LevelChunkSection section = new LevelChunkSection(SurfacePacketFixtures.biomes());
        if (solid) section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
        return new Cube(cx, cy, cz, section);
    }

    @Test void sectionDirtyMarksLoadedCubesAtAnyHeightWithoutDoubleBumping() {
        Long2ObjectOpenHashMap<Cube> cubes = new Long2ObjectOpenHashMap<>();
        int[][] coords = {{0, 3, 0}, {12, 550, -7}, {-5, -300, 9}};
        for (int[] c : coords) {
            Cube cube = cube(c[0], c[1], c[2], true);
            cubes.put(cube.key, cube);
        }
        CubeRenderer renderer = new CubeRenderer(cubes::get);
        for (int[] c : coords) {
            long key = CubeKey.pack(c[0], c[1], c[2]);
            renderer.sectionDirty(c[0], c[1], c[2]);
            assertTrue(renderer.dirty.contains(key));
            assertEquals(1, renderer.versions.get(key));
            // A duplicate dirty call (such as markAndNotify after blockChanged, or Sodium after LevelRenderer)
            // leaves the version alone.
            renderer.sectionDirty(c[0], c[1], c[2]);
            assertEquals(1, renderer.versions.get(key));
        }
        assertEquals(coords.length, renderer.pendingCount());
    }

    @Test void sectionDirtyIgnoresUnloadedWrappedAndEmptyCubes() {
        Long2ObjectOpenHashMap<Cube> cubes = new Long2ObjectOpenHashMap<>();
        Cube loaded = cube(1, 3, -2, true);
        Cube air = cube(1, 4, -2, false);
        cubes.put(loaded.key, loaded);
        cubes.put(air.key, air);
        CubeRenderer renderer = new CubeRenderer(cubes::get);

        // Unloaded section.
        renderer.sectionDirty(99, 3, -2);
        // Out-of-range Y that wraps in 20-bit CubeKey to loaded's key.
        renderer.sectionDirty(1, 3 + (1 << 20), -2);
        // Loaded air-only cube with no mesh.
        renderer.sectionDirty(1, 4, -2);

        assertTrue(renderer.dirty.isEmpty());
        assertEquals(0, renderer.versions.get(loaded.key));
        assertEquals(0, renderer.versions.get(air.key));
        assertEquals(0, renderer.pendingCount());
    }

    @Test void sectionDirtyRequeuesInFlightBuilds() {
        Long2ObjectOpenHashMap<Cube> cubes = new Long2ObjectOpenHashMap<>();
        Cube loaded = cube(0, 3, 0, true);
        cubes.put(loaded.key, loaded);
        CubeRenderer renderer = new CubeRenderer(cubes::get);

        renderer.sectionDirty(0, 3, 0);
        assertEquals(1, renderer.versions.get(loaded.key));

        // Simulate submit(): moves key out of dirty and into building while keeping version 1.
        renderer.dirty.remove(loaded.key);
        renderer.building.add(loaded.key);

        // A moving dynamic light dirties the cube again while its previous build is still in flight.
        renderer.sectionDirty(0, 3, 0);
        assertTrue(renderer.dirty.contains(loaded.key));
        assertEquals(2, renderer.versions.get(loaded.key));
        assertTrue(CubeRenderer.dropStale(1, renderer.versions.get(loaded.key), true, 0));

        // Another dirty notification before the next submit does not bump version again.
        renderer.sectionDirty(0, 3, 0);
        assertEquals(2, renderer.versions.get(loaded.key));
    }
}
