package io.earthplatform.minecraft.coregate;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent packet/state regressions; these make no claim about real GL or Minecraft motion. */
class RollingPreparedPairAdversarialTest {
    private static final long EPOCH = 7, RESOURCES = 3;
    private static final String A = "a".repeat(64), B = "b".repeat(64);
    private record Snapshot(RollingPublicationHandshake.State state, long publication,
                            long committed, long resources, String digest, String committedDigest) {}
    private static Snapshot snapshot(RollingPublicationHandshake h) {
        return new Snapshot(h.state(), h.publication(), h.committedPublication(),
                h.resourceGeneration(), h.digest(), h.committedDigest());
    }
    private static RollingPublicationHandshake at(int phase) {
        var h = new RollingPublicationHandshake(EPOCH);
        if (phase > 0) h.begin();
        if (phase > 1) h.ready(A);
        if (phase > 2) assertTrue(h.prepared(EPOCH, 1, RESOURCES, A));
        if (phase > 3) h.grant();
        if (phase > 4) assertTrue(h.pairReady(EPOCH, 1, RESOURCES, A));
        if (phase > 5) h.committed();
        return h;
    }
    private static void commit(RollingPublicationHandshake h, long id, String digest) {
        assertTrue(h.prepared(EPOCH, id, RESOURCES, digest));
        h.grant(); assertTrue(h.pairReady(EPOCH, id, RESOURCES, digest)); h.committed();
    }

    @Test void cancellationAtEveryStateIsTerminalEvenForAlreadyInFlightExactPackets() {
        for (int phase = 0; phase <= 6; phase++) {
            var h = at(phase); h.close(); var closed = snapshot(h);
            assertEquals(RollingPublicationHandshake.State.CLOSED, closed.state());
            assertFalse(h.pending());
            assertFalse(h.prepared(EPOCH, 1, RESOURCES, A));
            assertFalse(h.pairReady(EPOCH, 1, RESOURCES, A));
            assertFalse(h.acceptsMouse(EPOCH, 1, RESOURCES));
            assertThrows(IllegalStateException.class, h::begin);
            assertThrows(IllegalStateException.class, () -> h.ready(A));
            assertThrows(IllegalStateException.class, h::grant);
            assertThrows(IllegalStateException.class, h::committed);
            h.close(); assertEquals(closed, snapshot(h), "Close is terminal and idempotent at phase " + phase);
        }
    }

    @Test void rejectedPreparedCannotLatchAnAttackersResourceOrManifest() {
        var h = at(2); var before = snapshot(h);
        for (long epoch : new long[]{Long.MIN_VALUE, 0, EPOCH - 1, EPOCH + 1, Long.MAX_VALUE}) {
            assertFalse(h.prepared(epoch, 1, 99, A)); assertEquals(before, snapshot(h));
        }
        for (long publication : new long[]{Long.MIN_VALUE, 0, 2, Long.MAX_VALUE}) {
            assertFalse(h.prepared(EPOCH, publication, 99, A)); assertEquals(before, snapshot(h));
        }
        for (String digest : List.of("", "A".repeat(64), B, A + "0")) {
            assertFalse(h.prepared(EPOCH, 1, 99, digest)); assertEquals(before, snapshot(h));
        }
        assertFalse(h.prepared(EPOCH, 1, 99, null)); assertEquals(before, snapshot(h));
        assertFalse(h.prepared(EPOCH, 1, 0, A)); assertEquals(before, snapshot(h));
        assertFalse(h.prepared(EPOCH, 1, -1, A)); assertEquals(before, snapshot(h));
        assertTrue(h.prepared(EPOCH, 1, RESOURCES, A)); assertEquals(RESOURCES, h.resourceGeneration());
    }

    @Test void invalidPairPacketsAreNonMutatingInEveryLiveState() {
        for (int phase = 0; phase <= 6; phase++) {
            var h = at(phase); var before = snapshot(h);
            assertFalse(h.pairReady(EPOCH - 1, 1, RESOURCES, A));
            assertFalse(h.pairReady(EPOCH, 0, RESOURCES, A));
            assertFalse(h.pairReady(EPOCH, 2, RESOURCES, A));
            assertFalse(h.pairReady(EPOCH, 1, RESOURCES + 1, A));
            assertFalse(h.pairReady(EPOCH, 1, RESOURCES, B));
            assertFalse(h.pairReady(EPOCH, 1, RESOURCES, null));
            assertEquals(before, snapshot(h), "Rejected packet changed phase " + phase);
        }
    }

    @Test void duplicatePreparationCannotRewindGrantedOrAlreadyInstalledPair() {
        for (int phase : new int[]{3, 4, 5, 6}) {
            var h = at(phase); var before = snapshot(h);
            assertFalse(h.prepared(EPOCH, 1, RESOURCES, A));
            assertFalse(h.prepared(EPOCH, 1, RESOURCES + 1, A));
            assertEquals(before, snapshot(h));
        }
    }

    @Test void anUnchangedEditDigestStillNeedsANewPublicationAndExactOldResources() {
        var h = at(2); commit(h, 1, A);
        assertEquals(2, h.begin()); h.ready(A); var before = snapshot(h);
        assertFalse(h.acceptsMouse(EPOCH, 1, RESOURCES));
        assertFalse(h.prepared(EPOCH, 1, RESOURCES, A));
        assertFalse(h.prepared(EPOCH, 2, RESOURCES + 1, A));
        assertFalse(h.pairReady(EPOCH, 1, RESOURCES, A));
        assertEquals(before, snapshot(h)); commit(h, 2, A);
        assertEquals(3, h.begin()); h.ready(B);
        assertFalse(h.prepared(EPOCH, 3, RESOURCES, A));
        commit(h, 3, B);
        assertFalse(h.acceptsMouse(EPOCH, 2, RESOURCES));
        assertTrue(h.acceptsMouse(EPOCH, 3, RESOURCES));
        assertEquals(B, h.committedDigest());
    }

    @Test void noAcknowledgementAllowsSkippingTheServerGrantOrFinalCommit() {
        var h = at(2);
        assertFalse(h.pairReady(EPOCH, 1, RESOURCES, A));
        assertThrows(IllegalStateException.class, h::grant);
        assertThrows(IllegalStateException.class, h::committed);
        assertTrue(h.prepared(EPOCH, 1, RESOURCES, A));
        assertFalse(h.pairReady(EPOCH, 1, RESOURCES, A));
        assertThrows(IllegalStateException.class, h::committed);
        assertEquals(0, h.committedPublication());
        h.grant(); assertEquals(0, h.committedPublication());
        assertTrue(h.pairReady(EPOCH, 1, RESOURCES, A));
        assertFalse(h.acceptsMouse(EPOCH, 1, RESOURCES));
        assertEquals(0, h.committedPublication());
        h.committed(); assertEquals(1, h.committedPublication());
    }
}
