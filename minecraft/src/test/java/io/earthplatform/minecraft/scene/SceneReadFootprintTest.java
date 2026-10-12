package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure footprint tests. No registry, native model, GL, or Minecraft singleton is involved. */
class SceneReadFootprintTest {
    private static SceneReadFootprint.Query state(int x, int y, int z) {
        return new SceneReadFootprint.Query(SceneReadFootprint.Kind.STATE, x, y, z, 0);
    }
    @Test void haloIncludesEveryDiagonalAtNegativeAndExtremeSignedCoordinatesWithoutOverflow() {
        for (var root : java.util.List.of(new CubePos(-100, -688, -200), new CubePos(100, 553, 200),
                new CubePos(Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1),
                new CubePos(Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1))) {
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                var queried = root.offset(x, y, z);
                assertDoesNotThrow(() -> SceneReadFootprint.requireHaloOwner(root, queried));
            }
        }
        for (int distance : new int[]{-256, -2, 2, 256}) {
            var root = new CubePos(-10, -688, -20);
            for (var queried : java.util.List.of(root.offset(distance, 0, 0), root.offset(0, distance, 0), root.offset(0, 0, distance)))
                assertThrows(io.earthplatform.minecraft.residency.SparseFacadeException.class,
                        () -> SceneReadFootprint.requireHaloOwner(root, queried));
        }
        var min = new CubePos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        var max = new CubePos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertThrows(io.earthplatform.minecraft.residency.SparseFacadeException.class, () -> SceneReadFootprint.requireHaloOwner(min, max));
        assertThrows(io.earthplatform.minecraft.residency.SparseFacadeException.class, () -> SceneReadFootprint.requireHaloOwner(max, min));
    }
    @Test void deduplicatesExactQueriesButNeverAliasesPackedNativeY() {
        var recorder = new SceneReadFootprint.Recorder();
        Object stone = new Object();
        recorder.record(state(-1, -11000, -1), SceneReadFootprint.Value.identity(stone));
        recorder.record(state(-1, -11000, -1), SceneReadFootprint.Value.identity(stone));
        recorder.record(state(-1, -6904, -1), SceneReadFootprint.Value.identity(stone));
        var footprint = recorder.seal();
        assertEquals(2, footprint.readCount()); assertEquals(2, footprint.positionCount()); assertEquals(2, footprint.ownerCount());
        assertEquals(2 * 128 + 2 * 64, footprint.accountingBytes());
    }
    @Test void unchangedOwnerSkipsValuesWhileChangedOwnerGetsExactIdentityComparison() {
        var recorder = new SceneReadFootprint.Recorder();
        Object first = new Object(), second = new Object();
        recorder.record(state(1, 2, 3), SceneReadFootprint.Value.identity(first));
        recorder.record(state(17, 2, 3), SceneReadFootprint.Value.identity(second));
        var footprint = recorder.seal();
        var calls = new AtomicInteger();
        var pass = footprint.validate(Set.of(new CubePos(1, 0, 0)), query -> {
            calls.incrementAndGet(); assertEquals(17, query.x()); return SceneReadFootprint.Value.identity(second);
        });
        assertTrue(pass.matches()); assertEquals(1, pass.compared()); assertEquals(1, calls.get());
        assertFalse(footprint.validate(Set.of(new CubePos(1, 0, 0)), query -> SceneReadFootprint.Value.identity(first)).matches());
    }
    @Test void globalsAlwaysReplayEvenWhenNoNeighborChanged() {
        var recorder = new SceneReadFootprint.Recorder();
        var query = new SceneReadFootprint.Query(SceneReadFootprint.Kind.HEIGHT, 0, 0, 0, 0);
        recorder.record(query, SceneReadFootprint.Value.scalar(19872));
        var footprint = recorder.seal();
        assertTrue(footprint.validate(Set.of(), ignored -> SceneReadFootprint.Value.scalar(19872)).matches());
        assertFalse(footprint.validate(Set.of(), ignored -> SceneReadFootprint.Value.scalar(19936)).matches());
        assertEquals(0, footprint.positionCount());
    }
    @Test void overflowDisablesReuseWithoutRetainingMoreMetadataOrBreakingOriginalCompilation() {
        var recorder = new SceneReadFootprint.Recorder(2);
        for (int i = 0; i < 100; i++) recorder.record(state(i, 0, 0), SceneReadFootprint.Value.scalar(i));
        recorder.requireValid();
        assertEquals(0, recorder.size());
        var footprint = recorder.seal();
        assertFalse(footprint.reusable());
        assertEquals(0, footprint.accountingBytes());
        var result = footprint.validate(Set.of(new CubePos(0, 0, 0)), ignored -> fail("Overflow must never replay"));
        assertFalse(result.matches()); assertEquals(0, result.compared());
    }
    @Test void maximumBudgetBoundsBothStorageAndOneReplay() {
        var recorder = new SceneReadFootprint.Recorder();
        // 8 cubes, each with 4096 exact positional inputs.
        for (int i = 0; i < SceneReadFootprint.MAX_READS; i++) {
            recorder.record(state(i & 127, (i >>> 7) & 15, i >>> 11), SceneReadFootprint.Value.scalar(i));
        }
        var footprint = recorder.seal();
        assertEquals(SceneReadFootprint.MAX_READS, footprint.readCount());
        var result = footprint.validate(footprint.owners(), query -> SceneReadFootprint.Value.scalar(query.x() | query.y() << 7 | query.z() << 11));
        assertTrue(result.matches()); assertEquals(SceneReadFootprint.MAX_READS, result.compared());
        assertTrue(footprint.accountingBytes() <= (long) SceneReadFootprint.MAX_READS * 128 + 256 * 64);
    }
    @Test void inconsistentReadUnknownAndCancellationAreStickyEvenIfCallerCatches() {
        var recorder = new SceneReadFootprint.Recorder();
        recorder.record(state(0, 0, 0), SceneReadFootprint.Value.scalar(1));
        assertThrows(IllegalStateException.class, () -> recorder.record(state(0, 0, 0), SceneReadFootprint.Value.scalar(2)));
        assertThrows(IllegalStateException.class, recorder::seal);
        var unknown = new SceneReadFootprint.Recorder(); unknown.invalidate();
        assertThrows(IllegalStateException.class, unknown::seal);
        var cancelled = new SceneReadFootprint.Recorder(); cancelled.cancel();
        assertThrows(IllegalStateException.class, () -> cancelled.record(state(0, 0, 0), SceneReadFootprint.Value.scalar(1)));
        assertThrows(IllegalStateException.class, cancelled::seal);
    }
    @Test void sealedRecorderCannotAcceptLateCallbackReadsAndCapturedValuesRemainImmutable() {
        var recorder = new SceneReadFootprint.Recorder();
        recorder.record(state(0, 0, 0), SceneReadFootprint.Value.scalar(1));
        var footprint = recorder.seal();
        assertThrows(IllegalStateException.class, () -> recorder.record(state(0, 0, 0), SceneReadFootprint.Value.scalar(1)));
        assertTrue(footprint.validate(footprint.owners(), ignored -> SceneReadFootprint.Value.scalar(1)).matches());
    }
    @Test void offOwnerCannotRecordSealOrInvalidate() throws InterruptedException {
        var recorder = new SceneReadFootprint.Recorder();
        var error = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { recorder.record(state(0, 0, 0), SceneReadFootprint.Value.scalar(1)); }
            catch (Throwable failure) { error.set(failure); }
        });
        thread.start(); thread.join();
        assertInstanceOf(IllegalStateException.class, error.get());
        assertEquals(0, recorder.seal().readCount());
    }
    @Test void equalityOverridesCannotMasqueradeAsExactNativeIdentity() {
        class Equal { @Override public boolean equals(Object other) { return true; } }
        Object first = new Equal(), second = new Equal();
        assertEquals(first, second);
        assertFalse(SceneReadFootprint.Value.identity(first).same(SceneReadFootprint.Value.identity(second)));
    }
}
