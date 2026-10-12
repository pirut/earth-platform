package io.earthplatform.minecraft.coregate;

import org.junit.jupiter.api.Test;

import static io.earthplatform.minecraft.coregate.NativeDistanceProgressWatchdog.Lane.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent adversarial tests. The clock and transport are deterministic; no sleep or GL. */
class NativeDistanceProgressWatchdogAdversarialTest {
    private static final long SECOND = 1_000_000_000L;
    private static final String HASH = "a".repeat(64), PLAN = "b".repeat(64);

    private static NativeDistanceProgressWatchdog guard(boolean paused) {
        var result = new NativeDistanceProgressWatchdog(41, 24, 0, paused, true);
        assertTrue(result.bindAttempt(41, 1, 24, 24, 0, 42, 3, 1, HASH, PLAN));
        return result;
    }

    private static NativeDistanceProgressPayload packet(boolean server, long observation, long peer,
            NativeDistanceProgressWatchdog.Lane lane, long value) {
        var counters = new long[NativeDistanceProgressWatchdog.LANE_COUNT];
        counters[lane.ordinal()] = value;
        return new NativeDistanceProgressPayload(41, 41, 1, 24, 24, 0, 42, 3, 1,
                HASH, PLAN, observation, peer, server, counters);
    }

    @Test void actual034TimelineKeepsHealthyWriterBeyondOldWallDeadline() {
        // 06:43:53 selection, menu pause until 06:44:43; actor 06:44:47,
        // recapture 06:45:10, old wall watchdog 06:45:53. Actual timing is evidence,
        // while completed counters below are deterministic representative production milestones.
        var guard = guard(true);
        guard.observe(50 * SECOND, false);
        guard.observe(54 * SECOND, false);
        assertTrue(guard.creditLane(ACTOR_SNAPSHOTS, 1)); guard.observePeer(1);
        guard.observe(77 * SECOND, false);
        assertTrue(guard.creditLane(CAPSULE_CALLBACKS, 86_016)); guard.observePeer(2);
        guard.observe(120 * SECOND, false);
        assertEquals(70 * SECOND, guard.activeNanos());
        assertFalse(guard.failed(), "The actual034 fixed-wall abort must no longer fire");
        for (int second : new int[]{130, 190, 250, 310}) {
            guard.observe(second * SECOND, false);
            assertTrue(guard.creditLane(SOURCE_COLUMNS, second)); guard.observePeer(second);
        }
        assertEquals(260 * SECOND, guard.activeNanos());
        assertFalse(guard.failed(), "Several minutes of genuine preupload work are healthy");
    }

    @Test void clientOriginReportsCannotFabricateIndependentServerLiveness() {
        var guard = guard(false);
        for (int second : new int[]{1, 30, 60, 90, 119}) {
            assertTrue(guard.accept(packet(false, second, second, FACE_SCANS, second), second * SECOND, false));
        }
        assertEquals(0, guard.lastPeerStamp(), "Client-origin peerTick is not authoritative server evidence");
        guard.observe(120 * SECOND, false);
        assertEquals("peer-liveness-idle", guard.failure());
        assertEquals(SECOND, guard.usefulIdleNanos());
    }

    @Test void repeatedPausedAndResumedEdgesCannotEraseChargedIdle() {
        var guard = guard(false);
        long wall = 0;
        for (int second = 1; second <= 119; second++) {
            wall += SECOND;
            guard.observe(wall, true);
            wall += 10_000 * SECOND;
            guard.observe(wall, false);
            assertFalse(guard.failed());
        }
        assertEquals(119 * SECOND, guard.activeNanos());
        guard.observe(wall + SECOND, true);
        assertEquals("useful-work-idle", guard.failure());
        assertFalse(guard.creditLane(FACE_SCANS, 1));
        assertEquals(120 * SECOND, guard.activeNanos());
    }

    @Test void unknownFalseToFalseGapExpiresEvenWithHugeNewCompletedPacket() {
        var guard = guard(false);
        assertFalse(guard.accept(packet(true, 1, 1, ACTOR_SNAPSHOTS, 188), 121 * SECOND, false));
        assertEquals("useful-work-idle", guard.failure());
        assertArrayEquals(new long[NativeDistanceProgressWatchdog.LANE_COUNT], guard.rawVector());
        assertEquals(-1, guard.checkpoint().serverObservation());
        assertEquals(0, guard.lastPeerStamp());
    }

    @Test void allThirtyOneExhaustedPrefixesCannotBuyAnotherIdleInterval() {
        var guard = guard(false);
        long serial = 1;
        for (int selected = 2; selected <= 32; selected++) {
            assertTrue(guard.bindAttempt(41, ++serial, selected, selected, 0, serial, 0, 0, "", ""));
            for (var lane : NativeDistanceProgressWatchdog.Lane.values())
                assertTrue(guard.creditLane(lane, lane.cap()));
        }
        guard.observePeer(1);
        for (int second = 1; second <= 119; second++) {
            guard.observe(second * SECOND, false); guard.observePeer(second + 1);
            int selected = 2 + second % 31;
            assertTrue(guard.bindAttempt(41, ++serial, selected, selected, 0, serial, 0, 0, "", ""));
            for (var lane : NativeDistanceProgressWatchdog.Lane.values())
                assertFalse(guard.creditLane(lane, lane.cap()));
        }
        guard.observe(120 * SECOND, false);
        assertEquals("useful-work-idle", guard.failure());
        assertEquals(31, guard.creditedTable().length);
    }

    @Test void savedFailureCheckpointIsImmutableWhileCleanupAndLateEventsContinue() {
        var guard = guard(false);
        guard.observe(1 * SECOND, false);
        assertTrue(guard.creditLane(FACE_SCANS, 4096)); guard.observePeer(1);
        guard.observe(121 * SECOND, false);
        var cutoff = guard.checkpoint();
        assertTrue(cutoff.failed());
        guard.creditLane(FACE_SCANS, 8192); guard.observePeer(2);
        guard.bindAttempt(41, 2, 12, 12, 0, 43, 4, 2, HASH, PLAN);
        guard.observe(9999 * SECOND, true); guard.fail("cleanup-retry");
        assertEquals("useful-work-idle", cutoff.failure());
        assertEquals(4096, cutoff.rawVector()[FACE_SCANS.ordinal()]);
        assertEquals(4096, guard.rawVector()[FACE_SCANS.ordinal()]);
        assertEquals(121 * SECOND, guard.activeNanos());
        assertEquals(1, guard.serial());
    }

    @Test void signedNanoTimeOriginsAreValidButObservationOverflowNeverBecomesPauseForgiveness() {
        var guard = new NativeDistanceProgressWatchdog(1, 2, Long.MIN_VALUE + 100, false, false);
        guard.observe(Long.MIN_VALUE + 110, true);
        guard.observe(Long.MIN_VALUE + 1_000, false);
        assertEquals(10, guard.activeNanos());
        guard.observe(Long.MAX_VALUE, true);
        assertEquals("clock-overflow", guard.failure());
        assertEquals(10, guard.activeNanos());
    }
}
