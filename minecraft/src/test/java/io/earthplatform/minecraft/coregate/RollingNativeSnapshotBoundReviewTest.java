package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.earthplatform.minecraft.coregate.RollingGateReplicaTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact admitted payload identity counts, independent of VM heap size and GL allocation. */
class RollingNativeSnapshotBoundReviewTest {
    @BeforeAll static void initializeNativeFixture() { registries(); }

    private static List<EngineCubeSnapshot> snapshots(RollingGateReplica replica) {
        return replica.cubes().values().stream().map(cube -> cube.snapshot()).toList();
    }
    @SafeVarargs private static Set<EngineCubeSnapshot> identityUnion(Collection<EngineCubeSnapshot>... groups) {
        var result = Collections.newSetFromMap(new IdentityHashMap<EngineCubeSnapshot, Boolean>());
        for (var group : groups) result.addAll(group);
        return result;
    }

    @Test void diagonalMovementWithDisjointPinHasExactly213PayloadIdentitiesIncludingOldBindings() {
        try (var replica = empty()) {
            var driver = new Driver(); driver.open(replica);
            for (int step = 1; step <= 5; step++) driver.commit(replica, driver.stage(PIN.offset(step, step, step)));
            assertEquals(152, replica.cubes().size());
            var oldBindings = snapshots(replica);
            var next = driver.stage(PIN.offset(6, 6, 6));
            replica.acceptMetadata(next); driver.fill(replica, next);
            var activeBuilderWholeNeighborhood = snapshots(replica);
            var retained = identityUnion(oldBindings, activeBuilderWholeNeighborhood, snapshots(replica));
            assertEquals(213, retained.size());
            assertEquals(213, retained.stream().map(EngineCubeSnapshot::position).distinct().count());
            assertThrows(IllegalStateException.class, replica::beginEdit, "Edit and movement cannot accumulate separate generations");
            replica.acceptReady(driver.ready(next));
            replica.commitStage(next.generation());
            assertEquals(152, snapshots(replica).size());
        }
    }

    @Test void oneSerializedInteriorReplacementAddsOnlyOneHistoricalPinPayload() {
        try (var replica = empty()) {
            var driver = new Driver(); driver.open(replica);
            for (int step = 1; step <= 5; step++) driver.commit(replica, driver.stage(PIN.offset(step, step, step)));
            var oldBindingsAndPresentation = snapshots(replica);
            var nextMovement = driver.stage(PIN.offset(6, 6, 6));
            replica.beginEdit();
            assertThrows(IllegalStateException.class, () -> replica.acceptMetadata(nextMovement));
            var replacement = edited(driver.pinLease(), 1, CubePos.index(4, 1, 4));
            replica.acceptSnapshot(driver.generation, replacement);
            var duplicate = edited(driver.pinLease(), 2, CubePos.index(4, 1, 4));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(driver.generation, duplicate));
            var retained = identityUnion(oldBindingsAndPresentation, snapshots(replica), List.of(replacement));
            assertEquals(153, retained.size());
            assertEquals(152, retained.stream().map(EngineCubeSnapshot::position).distinct().count());
            assertEquals(2, retained.stream().filter(snapshot -> snapshot.position().equals(PIN)).count());
            for (var old : oldBindingsAndPresentation) {
                if (!old.position().equals(PIN)) assertSame(old, replica.cubes().get(old.position()).snapshot());
            }
            replica.acknowledge("APPLIED", 1);
            assertThrows(IllegalStateException.class, () -> replica.acceptMetadata(nextMovement));
            driver.revisions.put(PIN, 1L); replica.acceptReady(driver.currentReady());
            assertEquals(152, snapshots(replica).size(), "Historical release leaves only current identities");
        }
    }

    @Test void cancellationCannotAdmitAnotherSnapshotGenerationWhileOldOwnersAreRetained() {
        var replica = empty();
        var driver = new Driver(); driver.open(replica);
        var historical = new ArrayList<>(snapshots(replica));
        replica.beginEdit();
        var replacement = edited(driver.pinLease(), 1, CubePos.index(4, 1, 4));
        replica.acceptSnapshot(driver.generation, replacement);
        historical.addAll(snapshots(replica));
        assertEquals(126, identityUnion(historical).size());
        replica.close();
        assertThrows(IllegalStateException.class, () -> replica.acceptSnapshot(driver.generation,
                edited(driver.pinLease(), 2, CubePos.index(4, 1, 4))));
        assertThrows(IllegalStateException.class, replica::beginEdit);
        assertThrows(IllegalStateException.class, () -> replica.acceptMetadata(driver.stage(PIN.offset(1, 1, 1))));
        assertEquals(126, identityUnion(historical).size());
    }
}
