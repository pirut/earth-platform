package io.earthplatform.minecraft.coregate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingPublicationHandshakeTest {
    private static final String A = "a".repeat(64), B = "b".repeat(64);
    private static RollingPublicationHandshake ready() {
        var h = new RollingPublicationHandshake(7);
        assertEquals(1, h.begin()); h.ready(A); return h;
    }
    @Test void exactTwoPhaseHandshakeAndMouseIdentity() {
        var h = ready();
        assertFalse(h.acceptsMouse(7, 1, 3));
        assertTrue(h.prepared(7, 1, 3, A));
        assertFalse(h.pairReady(7, 1, 3, A));
        h.grant(); assertTrue(h.pairReady(7, 1, 3, A)); h.committed();
        assertTrue(h.acceptsMouse(7, 1, 3));
        assertFalse(h.acceptsMouse(6, 1, 3));
        assertFalse(h.acceptsMouse(7, 0, 3));
        assertFalse(h.acceptsMouse(7, 1, 4));
    }
    @Test void rejectsWrongEpochPublicationManifestAndResources() {
        var h = ready();
        assertFalse(h.prepared(6, 1, 3, A));
        assertFalse(h.prepared(7, 2, 3, A));
        assertFalse(h.prepared(7, 1, 3, B));
        assertFalse(h.prepared(7, 1, 0, A));
        assertTrue(h.prepared(7, 1, 3, A)); h.grant();
        assertFalse(h.pairReady(7, 1, 4, A));
        assertFalse(h.pairReady(7, 1, 3, B));
        assertFalse(h.pairReady(7, 2, 3, A));
        assertTrue(h.pairReady(7, 1, 3, A));
        assertFalse(h.pairReady(7, 1, 3, A));
    }
    @Test void preparedRemainsPendingUntilCallerProvesBodyAndFences() {
        var h = ready(); assertTrue(h.prepared(7, 1, 3, A));
        assertEquals(RollingPublicationHandshake.State.PREPARED, h.state());
        assertThrows(IllegalStateException.class, h::committed);
        assertThrows(IllegalStateException.class, h::begin);
        assertFalse(h.prepared(7, 1, 3, A));
        assertEquals(RollingPublicationHandshake.State.PREPARED, h.state());
    }
    @Test void sameManifestNewEditCannotReplayOldPairOrChangeResources() {
        var h = ready(); h.prepared(7, 1, 3, A); h.grant(); h.pairReady(7, 1, 3, A); h.committed();
        assertEquals(2, h.begin()); h.ready(A);
        assertFalse(h.prepared(7, 1, 3, A));
        assertFalse(h.prepared(7, 2, 4, A));
        assertFalse(h.pairReady(7, 1, 3, A));
        assertTrue(h.prepared(7, 2, 3, A)); h.grant();
        assertFalse(h.pairReady(7, 1, 3, A));
        assertTrue(h.pairReady(7, 2, 3, A)); h.committed();
        assertEquals(2, h.committedPublication());
    }
    @Test void resourceFencePermanentlyKillsEveryHandshakeState() {
        for (int progress = 0; progress < 5; progress++) {
            var h = ready();
            if (progress > 0) h.prepared(7, 1, 3, A);
            if (progress > 1) h.grant();
            if (progress > 2) h.pairReady(7, 1, 3, A);
            if (progress > 3) h.committed();
            h.close();
            assertFalse(h.prepared(7, 1, 3, A));
            assertFalse(h.pairReady(7, 1, 3, A));
            assertFalse(h.acceptsMouse(7, 1, 3));
            assertThrows(IllegalStateException.class, h::begin);
            assertThrows(IllegalStateException.class, h::grant);
            assertThrows(IllegalStateException.class, h::committed);
        }
    }
}
