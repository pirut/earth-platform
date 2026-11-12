package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
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
}
