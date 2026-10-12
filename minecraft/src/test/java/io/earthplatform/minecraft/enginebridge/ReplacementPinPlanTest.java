package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplacementPinPlanTest {
    private static final CubePos CURRENT = new CubePos(0, 8, 0), PIN = new CubePos(100, 8, 100);
    @Test void allPinModesAndAdjacentDirectionsKeepExact213Owner55MeshBounds() {
        for (boolean currentPinned : new boolean[]{false, true}) for (boolean candidatePinned : new boolean[]{false, true})
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                var candidate = CURRENT.offset(dx, dy, dz);
                var plan = RollingCubePlan.create(PIN, CURRENT, candidate, currentPinned, candidatePinned);
                Set<CubePos> old = new HashSet<>(CubeTickets.box(CURRENT, 2, 2, 125));
                Set<CubePos> next = new HashSet<>(CubeTickets.box(candidate, 2, 2, 125));
                if (currentPinned) old.addAll(CubeTickets.box(PIN, 1, 1, 27));
                if (candidatePinned) next.addAll(CubeTickets.box(PIN, 1, 1, 27));
                Set<CubePos> union = new HashSet<>(old); union.addAll(next);
                Set<CubePos> added = new HashSet<>(next); added.removeAll(old);
                Set<CubePos> retired = new HashSet<>(old); retired.removeAll(next);
                assertEquals(old, Set.copyOf(plan.committedOwners())); assertEquals(next, Set.copyOf(plan.nextCommittedOwners()));
                assertEquals(union, Set.copyOf(plan.stagedOwners())); assertEquals(added, Set.copyOf(plan.addedOwners()));
                assertEquals(retired, Set.copyOf(plan.retiredOwners()));
                assertTrue(union.size() <= 213); assertTrue(added.size() <= 88); assertTrue(retired.size() <= 88);
                assertTrue(plan.currentTargets().size() + plan.candidateTargets().size() + plan.pinnedTargets().size() <= 55);
                assertTrue(plan.stagedTargets().size() <= 47); assertTrue(plan.committedTargets().size() <= 28);
                plan.validate();
            }
    }
    @Test void diagonalRetirementAndReacquisitionAttain88WithoutWideningResidentUnion() {
        var retired = RollingCubePlan.create(PIN, CURRENT, CURRENT.offset(1, 1, 1), true, false);
        assertEquals(88, retired.retiredOwners().size()); assertEquals(61, retired.addedOwners().size());
        assertEquals(213, retired.stagedOwners().size()); assertEquals(125, retired.nextCommittedOwners().size());
        var restored = RollingCubePlan.create(PIN, CURRENT, CURRENT.offset(1, 1, 1), false, true);
        assertEquals(88, restored.addedOwners().size()); assertEquals(61, restored.retiredOwners().size());
        assertEquals(213, restored.stagedOwners().size()); assertEquals(152, restored.nextCommittedOwners().size());
    }
    @Test void retiredPinIsNotAHiddenSourceOrTargetAndLegacyDefaultStaysPinned() {
        var old = RollingCubePlan.stationary(PIN, CURRENT);
        assertTrue(old.currentPinned()); assertTrue(old.candidatePinned()); assertEquals(152, old.committedOwners().size());
        var retired = RollingCubePlan.stationary(PIN, CURRENT, false);
        assertFalse(retired.currentPinned()); assertFalse(retired.candidatePinned());
        assertEquals(125, retired.committedOwners().size()); assertEquals(27, retired.committedTargets().size());
        assertTrue(retired.pinnedOwners().isEmpty()); assertTrue(retired.pinnedTargets().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RollingCubePlan.create(PIN, CURRENT, null, true, false));
    }
    @Test void equalCenterPinRestoreIsExplicitAndBoundaryEditRestrictionIsUnchanged() {
        var restored = RollingCubePlan.create(PIN, CURRENT, CURRENT, false, true);
        assertEquals(27, restored.addedOwners().size()); assertTrue(restored.retiredOwners().isEmpty());
        assertTrue(restored.isEditableInterior(PIN.minBlockX() + 1, PIN.minBlockY() + 1, PIN.minBlockZ() + 1));
        assertFalse(restored.isEditableInterior(PIN.minBlockX(), PIN.minBlockY() + 1, PIN.minBlockZ() + 1));
    }
}
