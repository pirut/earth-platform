package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.BoundedCubeLoader;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.ImmutableCube;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure Java contract tests: no registry boot, Minecraft classes, disk, wall-clock sleeps or GUI. */
class RollingCubePlanTest {
    private static final CubePos ORIGIN = new CubePos(0, 0, 0);
    private static final CubePos DISJOINT_PIN = new CubePos(100, 0, 100);

    @Test void steadyWindowAndPinHaveExactAttainableBounds() {
        var plan = RollingCubePlan.stationary(DISJOINT_PIN, ORIGIN);
        assertEquals(DISJOINT_PIN, plan.pinCenter());
        assertEquals(ORIGIN, plan.currentCenter());
        assertNull(plan.candidateCenter());
        assertFalse(plan.hasCandidate());
        assertEquals(125, plan.currentOwners().size());
        assertEquals(27, plan.pinnedOwners().size());
        assertEquals(152, plan.committedOwners().size());
        assertEquals(28, plan.committedTargets().size());
        assertEquals(List.of(DISJOINT_PIN), plan.pinnedTargets());
        assertEquals(plan.committedOwners(), plan.stagedOwners());
        assertEquals(plan.committedOwners(), plan.nextCommittedOwners());
        assertTrue(plan.addedOwners().isEmpty());
        assertTrue(plan.retiredOwners().isEmpty());
        assertEquals(plan.committedTargets(), plan.stagedTargets());
        assertTrue(plan.candidateOwners().isEmpty());
        assertTrue(plan.candidateTargets().isEmpty());
        plan.validate();
    }

    @Test void all27AdjacentDirectionsHaveExactOwnerAndTargetOverlapFormula() {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            var candidate = ORIGIN.offset(dx, dy, dz);
            var plan = RollingCubePlan.create(DISJOINT_PIN, ORIGIN, candidate);
            int ownerOverlap = (5 - Math.abs(dx)) * (5 - Math.abs(dy)) * (5 - Math.abs(dz));
            int targetOverlap = (3 - Math.abs(dx)) * (3 - Math.abs(dy)) * (3 - Math.abs(dz));
            assertEquals(125 + 125 - ownerOverlap + 27, plan.stagedOwners().size());
            assertEquals(27 + 27 - targetOverlap + 1, plan.stagedTargets().size());
            assertEquals(ownerOverlap, intersection(plan.currentOwners(), plan.candidateOwners()));
            assertEquals(125 - ownerOverlap, plan.addedOwners().size());
            assertEquals(125 - ownerOverlap, plan.retiredOwners().size());
            var nextCommitted = RollingCubePlan.stationary(DISJOINT_PIN, candidate).committedOwners();
            assertEquals(nextCommitted, plan.nextCommittedOwners());
            assertEquals(targetOverlap, intersection(plan.currentTargets(), plan.candidateTargets()));
            assertTrue(RollingCubePlan.adjacent(ORIGIN, candidate));
            assertEquals(candidate, plan.candidateCenter());
            assertTrue(plan.hasCandidate());
        }
    }

    @Test void diagonalAttains213DistinctOwnersAnd47TargetsBut55MeshGenerations() {
        var plan = RollingCubePlan.create(DISJOINT_PIN, ORIGIN, new CubePos(1, 1, 1));
        assertEquals(213, plan.stagedOwners().size());
        assertEquals(47, plan.stagedTargets().size());
        assertEquals(55, plan.currentTargets().size() + plan.candidateTargets().size() + plan.pinnedTargets().size());
        assertEquals(213, RollingCubePlan.MAX_STAGED_OWNERS);
        assertEquals(47, RollingCubePlan.MAX_STAGED_TARGETS);
        assertEquals(55, RollingCubePlan.MAX_STAGED_MESH_GENERATIONS);
        assertTrue(RollingCubePlan.MAX_STAGED_OWNERS < 256);
    }

    @Test void arbitraryPinOverlapNeverWidensTheBoundAndSetsAreExact() {
        // Every relative overlap case is represented: once any axis is beyond +/- 4, the
        // pin is disjoint from both adjacent radius-2 windows on that axis. +/- 5 adds it.
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            var candidate = ORIGIN.offset(dx, dy, dz);
            Set<CubePos> windows = manualBox(ORIGIN, 2);
            windows.addAll(manualBox(candidate, 2));
            Set<CubePos> targets = manualBox(ORIGIN, 1);
            targets.addAll(manualBox(candidate, 1));
            for (int px = -5; px <= 5; px++) for (int py = -5; py <= 5; py++) for (int pz = -5; pz <= 5; pz++) {
                var pin = new CubePos(px, py, pz);
                var plan = RollingCubePlan.create(pin, ORIGIN, candidate);
                var pinSet = manualBox(pin, 1);
                assertEquals(windows.size() + 27 - intersection(windows, pinSet), plan.stagedOwners().size());
                assertEquals(targets.size() + (targets.contains(pin) ? 0 : 1), plan.stagedTargets().size());
                assertTrue(plan.stagedOwners().containsAll(windows));
                assertTrue(plan.stagedOwners().containsAll(pinSet));
                assertTrue(plan.stagedTargets().containsAll(targets));
                assertTrue(plan.stagedTargets().contains(pin));
                assertTrue(plan.stagedOwners().size() <= 213);
                assertTrue(plan.committedOwners().size() <= 152);
                assertTrue(plan.nextCommittedOwners().size() <= 152);
                assertTrue(plan.addedOwners().size() <= 61);
                assertTrue(plan.retiredOwners().size() <= 61);
                assertTrue(plan.stagedTargets().size() <= 47);
                assertTrue(plan.committedTargets().size() <= 28);
            }
        }
    }

    @Test void feetDetermineSignedThreeDimensionalCenterIncludingSubnormalAndBoundaries() {
        assertEquals(new CubePos(0, 0, 0), RollingCubePlan.centerFromFeet(0, 0, 0));
        assertEquals(new CubePos(-1, -1, -1), RollingCubePlan.centerFromFeet(-.1, -.1, -.1));
        assertEquals(new CubePos(-1, -1, -1), RollingCubePlan.centerFromFeet(-Double.MIN_VALUE, -Double.MIN_VALUE, -Double.MIN_VALUE));
        assertEquals(new CubePos(-1, -1, -1), RollingCubePlan.centerFromFeet(-16, -16, -16));
        assertEquals(new CubePos(-2, -2, -2), RollingCubePlan.centerFromFeet(Math.nextDown(-16d), Math.nextDown(-16d), Math.nextDown(-16d)));
        assertEquals(new CubePos(1, 1, 1), RollingCubePlan.centerFromFeet(16, 16, 16));
        assertEquals(new CubePos(0, 0, 0), RollingCubePlan.centerFromFeet(Math.nextDown(16d), Math.nextDown(16d), Math.nextDown(16d)));
        assertEquals(new CubePos(12, 250, -13), RollingCubePlan.centerFromFeet(199, 4001, -199));
    }

    @Test void fullHeightEndpointsFitExactRealPaddingWithoutExpandingGameplay() {
        var low = RollingCubePlan.centerFromFeet(0, -11008, 0);
        var high = RollingCubePlan.centerFromFeet(0, Math.nextDown(8864d), 0);
        assertEquals(-688, low.y());
        assertEquals(553, high.y());
        assertEquals(-688, RollingCubePlan.MIN_WINDOW_CENTER_Y);
        assertEquals(553, RollingCubePlan.MAX_WINDOW_CENTER_Y);
        var lowPlan = RollingCubePlan.create(low, low, low.offset(1, 1, -1));
        var highPlan = RollingCubePlan.create(high, high, high.offset(-1, -1, 1));
        assertEquals(-11040, lowPlan.stagedOwners().stream().mapToLong(CubePos::minBlockY).min().orElseThrow());
        assertEquals(8896, highPlan.stagedOwners().stream().mapToLong(p -> p.minBlockY() + 16).max().orElseThrow());
        for (var plan : List.of(lowPlan, highPlan)) for (var cube : plan.stagedOwners()) {
            assertTrue(cube.minBlockY() >= -11040);
            assertTrue(cube.minBlockY() + 16 <= 8896);
        }
        assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.centerFromFeet(0, Math.nextDown(-11008d), 0));
        assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.centerFromFeet(0, 8864, 0));
        assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.stationary(low, low.offset(0, -1, 0)));
        assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.stationary(high, high.offset(0, 1, 0)));
    }

    @Test void pinHasItsOwnRadiusOnePaddingValidation() {
        var lowPin = new CubePos(0, -689, 0);
        var highPin = new CubePos(0, 554, 0);
        assertEquals(-11040, RollingCubePlan.stationary(lowPin, ORIGIN).pinnedOwners().stream().mapToLong(CubePos::minBlockY).min().orElseThrow());
        assertEquals(8896, RollingCubePlan.stationary(highPin, ORIGIN).pinnedOwners().stream().mapToLong(p -> p.minBlockY() + 16).max().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.stationary(lowPin.offset(0, -1, 0), ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.stationary(highPin.offset(0, 1, 0), ORIGIN));
    }

    @Test void all4096CellsAtSignedAndNativeEndpointCubesEnforceImmutableShell() {
        for (var editable : List.of(ORIGIN, new CubePos(-7, -33, -9), new CubePos(0, -688, 0), new CubePos(0, 553, 0))) {
            var plan = RollingCubePlan.stationary(editable, editable);
            int accepted = 0;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                boolean expected = x >= 1 && x <= 14 && y >= 1 && y <= 14 && z >= 1 && z <= 14;
                long bx = editable.minBlockX() + x, by = editable.minBlockY() + y, bz = editable.minBlockZ() + z;
                assertEquals(expected, RollingCubePlan.isEditableInterior(editable, bx, by, bz));
                assertEquals(expected, plan.isEditableInterior(bx, by, bz));
                if (expected) accepted++;
            }
            assertEquals(14 * 14 * 14, accepted);
            for (int boundary : new int[]{0, 15}) {
                assertFalse(plan.isEditableInterior(editable.minBlockX() + boundary, editable.minBlockY() + 7, editable.minBlockZ() + 7));
                assertFalse(plan.isEditableInterior(editable.minBlockX() + 7, editable.minBlockY() + boundary, editable.minBlockZ() + 7));
                assertFalse(plan.isEditableInterior(editable.minBlockX() + 7, editable.minBlockY() + 7, editable.minBlockZ() + boundary));
            }
        }
    }

    @Test void editPredicateRejectsOtherCubesLongOverflowAndRenderOnlyPadding() {
        for (long bad : new long[]{-1, 16, Long.MIN_VALUE, Long.MAX_VALUE}) {
            assertFalse(RollingCubePlan.isEditableInterior(ORIGIN, bad, 7, 7));
            assertFalse(RollingCubePlan.isEditableInterior(ORIGIN, 7, bad, 7));
            assertFalse(RollingCubePlan.isEditableInterior(ORIGIN, 7, 7, bad));
        }
        assertFalse(RollingCubePlan.isEditableInterior(new CubePos(0, -689, 0), 7, -11017, 7));
        assertFalse(RollingCubePlan.isEditableInterior(new CubePos(0, 554, 0), 7, 8871, 7));
    }

    @Test void nativeHorizontalLimitsValidateEntireWindowsAndRejectIntegerOverflow() {
        int lo = RollingCubePlan.NATIVE_XZ_MIN + 2, hi = RollingCubePlan.NATIVE_XZ_MAX - 2;
        var minimum = new CubePos(lo, 0, lo);
        var maximum = new CubePos(hi, 0, hi);
        assertEquals(125, RollingCubePlan.stationary(minimum, minimum).currentOwners().size());
        assertEquals(125, RollingCubePlan.stationary(maximum, maximum).currentOwners().size());
        assertEquals(minimum, RollingCubePlan.centerFromFeet((long) lo * 16, 0, (long) lo * 16));
        assertEquals(maximum, RollingCubePlan.centerFromFeet((long) hi * 16 + 15, 0, (long) hi * 16 + 15));
        for (var invalid : List.of(minimum.offset(-1, 0, 0), minimum.offset(0, 0, -1), maximum.offset(1, 0, 0),
                maximum.offset(0, 0, 1), new CubePos(Integer.MIN_VALUE, 0, 0), new CubePos(Integer.MAX_VALUE, 0, 0),
                new CubePos(0, Integer.MIN_VALUE, 0), new CubePos(0, Integer.MAX_VALUE, 0))) {
            assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.stationary(ORIGIN, invalid));
            assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.nextStep(ORIGIN, invalid));
        }
        assertFalse(RollingCubePlan.adjacent(new CubePos(Integer.MIN_VALUE, 0, 0), new CubePos(Integer.MAX_VALUE, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.centerFromFeet((long) (hi + 1) * 16, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.centerFromFeet(Math.nextDown((double) lo * 16), 0, 0));
    }

    @Test void nonFiniteOrEnormousFeetAndNullCentersFailClosed() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE, -Double.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.centerFromFeet(bad, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.centerFromFeet(0, bad, 0));
            assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.centerFromFeet(0, 0, bad));
        }
        assertThrows(NullPointerException.class, () -> RollingCubePlan.stationary(null, ORIGIN));
        assertThrows(NullPointerException.class, () -> RollingCubePlan.stationary(ORIGIN, null));
        assertThrows(NullPointerException.class, () -> RollingCubePlan.nextStep(ORIGIN, null));
    }

    @Test void teleportAdmissionIsRejectedEvenWhenRawUnionWouldFitHostBudget() {
        for (var goal : List.of(new CubePos(2, 0, 0), new CubePos(0, -2, 0), new CubePos(0, 0, 2), new CubePos(100, 100, -100))) {
            assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.create(ORIGIN, ORIGIN, goal));
        }
        // Two x-separated windows have only 175 owners here, below 256; adjacency is an
        // independent requirement, rather than a size-only test that permits teleports.
        var boxes = manualBox(ORIGIN, 2);
        boxes.addAll(manualBox(new CubePos(2, 0, 0), 2));
        assertEquals(175, boxes.size());
    }

    @Test void coalescedGoalStepsMoveAllAxesAndNeverAppendAnUnboundedPath() {
        var current = new CubePos(9, 9, -9);
        assertEquals(new CubePos(8, 10, -8), RollingCubePlan.nextStep(current, new CubePos(-99, 99, 99)));
        assertEquals(new CubePos(10, 8, -10), RollingCubePlan.nextStep(current, new CubePos(99, -99, -99)));
        assertEquals(current, RollingCubePlan.nextStep(current, current));
        var goal = new CubePos(-10, -11, 12);
        int steps = 0;
        while (!current.equals(goal)) {
            var next = RollingCubePlan.nextStep(current, goal);
            assertTrue(RollingCubePlan.adjacent(current, next));
            RollingCubePlan.create(DISJOINT_PIN, current, next).validate();
            current = next;
            steps++;
        }
        assertEquals(21, steps);
    }

    @Test void everyExposedListIsCanonicalImmutableAndIndependentOfLaterPlans() {
        var plan = RollingCubePlan.create(new CubePos(-4, -4, -4), ORIGIN, new CubePos(-1, -1, -1));
        var before = List.copyOf(plan.stagedOwners());
        for (var list : lists(plan)) {
            assertEquals(new ArrayList<>(new TreeSet<>(list)), list);
            assertEquals(new HashSet<>(list).size(), list.size());
            assertThrows(UnsupportedOperationException.class, () -> list.add(ORIGIN));
            assertThrows(UnsupportedOperationException.class, list::clear);
            assertThrows(UnsupportedOperationException.class, () -> list.set(0, ORIGIN));
        }
        RollingCubePlan.create(DISJOINT_PIN, new CubePos(40, 40, 40), new CubePos(41, 41, 41));
        assertEquals(before, plan.stagedOwners());
    }

    @Test void cancellingCandidateKeepsCurrentAndPinnedTicketOwnershipAndFrozenPlan() {
        for (var pin : List.of(ORIGIN, DISJOINT_PIN, new CubePos(3, 0, 0))) {
            var candidate = new CubePos(1, 1, 1);
            var plan = RollingCubePlan.create(pin, ORIGIN, candidate);
            var tickets = new CubeTickets(213, 3);
            tickets.update("pin", pin, 1, 1);
            tickets.update("current", ORIGIN, 2, 2);
            tickets.update("candidate", candidate, 2, 2);
            assertEquals(Set.copyOf(plan.stagedOwners()), tickets.positions());
            var frozen = plan.stagedOwners();
            tickets.remove("candidate");
            assertEquals(Set.copyOf(plan.committedOwners()), tickets.positions());
            assertSame(frozen, plan.stagedOwners());
            assertTrue(plan.hasCandidate(), "Cancellation does not mutate retained snapshots");
            assertTrue(plan.retiredOwners().stream().noneMatch(plan.pinnedOwners()::contains));
            assertTrue(plan.addedOwners().stream().noneMatch(plan.committedOwners()::contains));
            for (var pos : plan.committedOwners()) {
                int expected = (plan.currentOwners().contains(pos) ? 1 : 0) + (plan.pinnedOwners().contains(pos) ? 1 : 0);
                assertEquals(expected, tickets.references(pos));
            }
            tickets.remove("current");
            assertEquals(Set.copyOf(plan.pinnedOwners()), tickets.positions());
            tickets.remove("pin");
            assertEquals(0, tickets.size());
        }
    }

    @Test void commitKeepsCandidateAndPinWithoutDroppingSharedCubes() {
        var plan = RollingCubePlan.create(DISJOINT_PIN, ORIGIN, new CubePos(1, 1, 1));
        var tickets = new CubeTickets(213, 3);
        tickets.update("pin", plan.pinCenter(), 1, 1);
        tickets.update("current", plan.currentCenter(), 2, 2);
        tickets.update("candidate", plan.candidateCenter(), 2, 2);
        var removed = tickets.remove("current").removed();
        var after = RollingCubePlan.stationary(plan.pinCenter(), plan.candidateCenter());
        assertEquals(Set.copyOf(after.committedOwners()), tickets.positions());
        assertEquals(after.committedOwners(), plan.nextCommittedOwners());
        assertEquals(removed, Set.copyOf(plan.retiredOwners()));
        assertEquals(61, removed.size());
        assertTrue(removed.stream().noneMatch(after.committedOwners()::contains));
        assertEquals(152, tickets.size());
    }

    @Test void cancelledUncooperativeProviderRetainsPhysicalSlotUntilItActuallySettles() {
        var executor = new ArrayDeque<Runnable>();
        var sources = new ArrayList<CompletableFuture<ImmutableCube>>();
        var tokens = new ArrayList<CancellationToken>();
        var loader = new BoundedCubeLoader((pos, cancellation) -> {
            var source = new CompletableFuture<ImmutableCube>();
            sources.add(source);
            tokens.add(cancellation);
            return source;
        }, executor::addLast, 1, 1, 1);
        var first = loader.request(ORIGIN);
        executor.removeFirst().run();
        first.close();
        assertTrue(first.future().isCancelled());
        assertTrue(tokens.getFirst().isCancelled());
        assertFalse(sources.getFirst().isDone());
        assertEquals(1, loader.stats().running());
        var replacement = loader.request(ORIGIN.offset(1, 0, 0));
        assertEquals(1, loader.stats().running());
        assertEquals(1, loader.stats().queued());
        assertThrows(RejectedExecutionException.class, () -> loader.request(ORIGIN.offset(2, 0, 0)));
        assertTrue(executor.isEmpty(), "Cancellation cannot admit a second physical provider");
        sources.getFirst().complete(ImmutableCube.uniform(0));
        assertEquals(1, loader.stats().running());
        assertEquals(0, loader.stats().queued());
        executor.removeFirst().run();
        replacement.close();
        loader.close();
        assertEquals(1, loader.stats().running(), "Even close retains the unsettled physical provider");
        sources.get(1).complete(ImmutableCube.uniform(0));
        assertEquals(0, loader.stats().running());
    }

    private static List<List<CubePos>> lists(RollingCubePlan p) {
        return List.of(p.currentOwners(), p.candidateOwners(), p.pinnedOwners(), p.committedOwners(), p.stagedOwners(),
                p.nextCommittedOwners(), p.addedOwners(), p.retiredOwners(),
                p.currentTargets(), p.candidateTargets(), p.pinnedTargets(), p.committedTargets(), p.stagedTargets());
    }

    private static int intersection(java.util.Collection<CubePos> a, java.util.Collection<CubePos> b) {
        var intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return intersection.size();
    }

    private static Set<CubePos> manualBox(CubePos center, int radius) {
        var result = new HashSet<CubePos>();
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) for (int z = -radius; z <= radius; z++)
            result.add(new CubePos(center.x() + x, center.y() + y, center.z() + z));
        return result;
    }
}
