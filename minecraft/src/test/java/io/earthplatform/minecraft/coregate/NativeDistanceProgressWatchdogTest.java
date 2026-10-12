package io.earthplatform.minecraft.coregate;

import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.coregate.NativeDistanceProgressWatchdog.Lane.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeDistanceProgressWatchdogTest {
    private static final long SECOND = 1_000_000_000L;
    private static final String DIGEST = "a".repeat(64), PLAN = "b".repeat(64);

    private static NativeDistanceProgressWatchdog guard(boolean peer) {
        var guard = new NativeDistanceProgressWatchdog(9, 12, 0, false, peer);
        assertTrue(guard.bindAttempt(7, 1, 12, 12, 0, 11, 22, 33, DIGEST, PLAN));
        return guard;
    }
    private static long[] vector(NativeDistanceProgressWatchdog.Lane lane, long value) {
        long[] values = new long[NativeDistanceProgressWatchdog.LANE_COUNT];
        values[lane.ordinal()] = value;
        return values;
    }
    private static NativeDistanceProgressPayload report(long observation, long peerTick,
            NativeDistanceProgressWatchdog.Lane lane, long value) {
        return new NativeDistanceProgressPayload(9, 7, 1, 12, 12, 0, 11, 22, 33,
                DIGEST, PLAN, observation, peerTick, lane.server(), vector(lane, value));
    }

    @Test void healthyWorkSurvivesFiftyTwoSecondPauseAndMoreThanTwoMinutesWallTime() {
        var guard = guard(true);
        guard.observe(10 * SECOND, false);
        assertTrue(guard.creditLane(FACE_SCANS, 1)); guard.observePeer(1);
        guard.observe(20 * SECOND, true);
        assertTrue(guard.creditLane(FACE_SCANS, 2)); guard.observePeer(2);
        guard.observe(72 * SECOND, false); // The known 52-second pause contributes no active time.
        for (int time : new int[]{110, 160, 220}) {
            guard.observe(time * SECOND, false);
            assertTrue(guard.creditLane(FACE_SCANS, time));
            guard.observePeer(time);
        }
        assertFalse(guard.failed());
        assertEquals(168 * SECOND, guard.activeNanos());
        assertEquals(0, guard.usefulIdleNanos());
        assertEquals(0, guard.peerIdleNanos());
    }

    @Test void defaultUsefulIdleExpiresAtExactlyOneHundredTwentyUnpausedSeconds() {
        var guard = guard(false);
        guard.observe(120 * SECOND - 1, false);
        assertFalse(guard.failed());
        guard.observe(120 * SECOND, false);
        assertEquals("useful-work-idle", guard.failure());
    }

    @Test void localUsefulWorkCannotMaskIndependentPeerFreeze() {
        var guard = guard(true);
        for (int time : new int[]{30, 60, 90, 119}) {
            guard.observe(time * SECOND, false);
            assertTrue(guard.creditLane(FACE_SCANS, time));
        }
        guard.observe(120 * SECOND, false);
        assertEquals("peer-liveness-idle", guard.failure());
        assertEquals(SECOND, guard.usefulIdleNanos());
        assertEquals(120 * SECOND, guard.peerIdleNanos());
    }

    @Test void peerHeartbeatsAndReportSequencesNeverCountAsUsefulWork() {
        var guard = guard(true);
        for (int time : new int[]{30, 60, 90, 119}) {
            assertTrue(guard.accept(report(time, time, SERVER_DRAINED, 0), time * SECOND, false));
        }
        assertFalse(guard.accept(report(120, 120, SERVER_DRAINED, 1), 120 * SECOND, false));
        assertEquals("useful-work-idle", guard.failure());
        assertEquals(119, guard.lastPeerStamp());
        assertEquals(0, guard.currentCredited()[SERVER_DRAINED.ordinal()]);
    }

    @Test void enteringPauseChargesPriorIntervalAndResumeDoesNotChargeKnownPausedGap() {
        var guard = guard(false);
        guard.observe(7 * SECOND, true);
        guard.observe(1_000 * SECOND, true);
        guard.observe(2_000 * SECOND, false);
        assertEquals(7 * SECOND, guard.activeNanos());
        guard.observe(2_003 * SECOND, false);
        assertEquals(10 * SECOND, guard.activeNanos());
        assertFalse(guard.failed());
        var initiallyPaused = new NativeDistanceProgressWatchdog(1, 2, 0, true, false);
        initiallyPaused.observe(10_000 * SECOND, false);
        assertEquals(0, initiallyPaused.activeNanos());
    }

    @Test void unknownLongUnpausedGapIsChargedBeforeLateProgress() {
        var guard = guard(false);
        assertFalse(guard.accept(report(1, 200, SERVER_DRAINED, 1), 200 * SECOND, false));
        assertEquals("useful-work-idle", guard.failure());
        assertEquals(200 * SECOND, guard.activeNanos());
        assertEquals(0, guard.currentCredited()[SERVER_DRAINED.ordinal()]);
    }

    @Test void abaAttemptPrefixesKeepIndependentSettingHighwatersWithoutRenewingIdle() {
        var guard = guard(false);
        assertTrue(guard.creditLane(REPLACEMENT_READS, 10));
        guard.observe(50 * SECOND, false);
        assertTrue(guard.bindAttempt(7, 2, 24, 24, 0, 12, 23, 34, DIGEST, PLAN));
        assertTrue(guard.creditLane(REPLACEMENT_READS, 20));
        guard.observe(100 * SECOND, false);
        assertTrue(guard.bindAttempt(7, 3, 12, 12, 0, 13, 24, 35, DIGEST, PLAN));
        assertEquals(0, guard.rawVector()[REPLACEMENT_READS.ordinal()]);
        assertFalse(guard.creditLane(REPLACEMENT_READS, 10));
        assertEquals(50 * SECOND, guard.usefulIdleNanos());
        assertEquals(10, guard.creditedTable()[12 - 2][REPLACEMENT_READS.ordinal()]);
        assertEquals(20, guard.creditedTable()[24 - 2][REPLACEMENT_READS.ordinal()]);
        guard.observe(169 * SECOND, false);
        assertFalse(guard.failed());
        assertFalse(guard.creditLane(REPLACEMENT_READS, 10));
        guard.observe(170 * SECOND, false);
        assertEquals("useful-work-idle", guard.failure());
    }

    @Test void onlyStrictSemanticAdvanceRenewsAfterResourceActorAndPlanRebinds() {
        var guard = guard(false);
        assertTrue(guard.creditLane(SOURCE_COLUMNS, 20));
        guard.observe(30 * SECOND, false);
        assertTrue(guard.bindAttempt(7, 1, 12, 12, 0, 11, 23, 34, DIGEST, PLAN));
        assertFalse(guard.creditLane(SOURCE_COLUMNS, 20));
        guard.observe(60 * SECOND, false);
        assertTrue(guard.bindAttempt(7, 1, 12, 12, 0, 12, 0, 0, "", ""));
        assertFalse(guard.creditLane(SOURCE_COLUMNS, 20));
        assertEquals(60 * SECOND, guard.usefulIdleNanos());
        assertTrue(guard.creditLane(SOURCE_COLUMNS, 21));
        assertEquals(0, guard.usefulIdleNanos());
        assertEquals(60 * SECOND, guard.activeNanos());
    }

    @Test void sameBindingDoesNotEraseRawCountersOrPermitRollback() {
        var guard = guard(false);
        assertTrue(guard.creditLane(FACE_SCANS, 100));
        assertTrue(guard.bindAttempt(7, 1, 12, 12, 0, 11, 22, 33, DIGEST, PLAN));
        assertEquals(100, guard.rawVector()[FACE_SCANS.ordinal()]);
        assertFalse(guard.creditLane(FACE_SCANS, 99));
        assertEquals("progress-rollback:FACE_SCANS", guard.failure());
        assertEquals(100, guard.currentCredited()[FACE_SCANS.ordinal()]);
    }

    @Test void staleEpisodeEpochSerialActorResourcePublicationDigestAndPlanAreIgnored() {
        var guard = guard(true);
        var good = report(1, 1, SERVER_DRAINED, 1);
        NativeDistanceProgressPayload[] stale = {
            new NativeDistanceProgressPayload(10, 7, 1, 12, 12, 0, 11, 22, 33, DIGEST, PLAN, 1, 1, true, good.completed()),
            new NativeDistanceProgressPayload(9, 8, 1, 12, 12, 0, 11, 22, 33, DIGEST, PLAN, 1, 1, true, good.completed()),
            new NativeDistanceProgressPayload(9, 7, 2, 12, 12, 0, 11, 22, 33, DIGEST, PLAN, 1, 1, true, good.completed()),
            new NativeDistanceProgressPayload(9, 7, 1, 12, 12, 0, 12, 22, 33, DIGEST, PLAN, 1, 1, true, good.completed()),
            new NativeDistanceProgressPayload(9, 7, 1, 12, 12, 0, 11, 23, 33, DIGEST, PLAN, 1, 1, true, good.completed()),
            new NativeDistanceProgressPayload(9, 7, 1, 12, 12, 0, 11, 22, 34, DIGEST, PLAN, 1, 1, true, good.completed()),
            new NativeDistanceProgressPayload(9, 7, 1, 12, 12, 0, 11, 22, 33, "c".repeat(64), PLAN, 1, 1, true, good.completed()),
            new NativeDistanceProgressPayload(9, 7, 1, 12, 12, 0, 11, 22, 33, DIGEST, "d".repeat(64), 1, 1, true, good.completed()),
            new NativeDistanceProgressPayload(9, 7, 1, 12, 10, 10, 11, 22, 33, DIGEST, PLAN, 1, 1, true, good.completed())
        };
        for (var report : stale) assertFalse(guard.accept(report, SECOND, false));
        assertFalse(guard.failed());
        assertEquals(SECOND, guard.usefulIdleNanos());
        assertEquals(0, guard.lastPeerStamp());
        assertTrue(guard.accept(good, SECOND, false));
        assertEquals(1, guard.lastPeerStamp());
    }

    @Test void oldBindingsCannotReplaceInitialRequestEpochOrRewindSerialAndIdentity() {
        var guard = guard(false);
        assertFalse(guard.bindAttempt(8, 2, 12, 12, 0, 12, 23, 34, DIGEST, PLAN));
        assertFalse(guard.bindAttempt(7, 1, 24, 24, 0, 11, 22, 33, DIGEST, PLAN));
        assertFalse(guard.bindAttempt(7, 1, 12, 12, 0, 10, 22, 33, DIGEST, PLAN));
        assertFalse(guard.bindAttempt(7, 1, 12, 12, 0, 11, 21, 33, DIGEST, PLAN));
        assertTrue(guard.bindAttempt(7, 2, 24, 24, 0, 12, 0, 0, "", ""));
        assertFalse(guard.bindAttempt(7, 1, 12, 12, 0, 11, 22, 33, DIGEST, PLAN));
        assertEquals(7, guard.requestEpoch()); assertEquals(2, guard.serial()); assertEquals(24, guard.selected());
    }

    @Test void duplicateObservationsAreIgnoredAndFreshSameAttemptRollbackFailsAtomically() {
        var guard = guard(false);
        assertTrue(guard.accept(report(1, 1, ACTOR_SNAPSHOTS, 2), SECOND, false));
        assertFalse(guard.accept(report(1, 2, ACTOR_SNAPSHOTS, 1), 2 * SECOND, false));
        assertFalse(guard.failed());
        long[] rolledBack = vector(ACTOR_SNAPSHOTS, 1);
        rolledBack[SERVER_DRAINED.ordinal()] = 1;
        var invalid = new NativeDistanceProgressPayload(9, 7, 1, 12, 12, 0, 11, 22, 33,
                DIGEST, PLAN, 2, 3, true, rolledBack);
        assertFalse(guard.accept(invalid, 3 * SECOND, false));
        assertEquals("progress-rollback:ACTOR_SNAPSHOTS", guard.failure());
        assertEquals(0, guard.currentCredited()[SERVER_DRAINED.ordinal()]);
        assertEquals(1, guard.lastPeerStamp());
    }

    @Test void clientAndServerSequencesAreIndependentAndRebindDoesNotResetPeerEvidence() {
        var guard = guard(true);
        assertTrue(guard.accept(report(0, 2, SERVER_DRAINED, 1), SECOND, false));
        assertTrue(guard.accept(report(0, Long.MAX_VALUE, CLIENT_DRAINED, 1), 2 * SECOND, false));
        assertEquals(2, guard.lastPeerStamp());
        assertEquals(SECOND, guard.peerIdleNanos());
        assertTrue(guard.bindAttempt(7, 2, 12, 12, 0, 11, 22, 33, DIGEST, PLAN));
        assertEquals(2, guard.lastPeerStamp());
        assertEquals(SECOND, guard.peerIdleNanos());
        assertEquals(-1, guard.checkpoint().serverObservation());
        assertEquals(-1, guard.checkpoint().clientObservation());
    }

    @Test void clientOriginForgedPeerStampCannotRenewPeerLiveness() {
        var guard = guard(true);
        assertTrue(guard.accept(report(1, Long.MAX_VALUE, CLIENT_DRAINED, 1), 119 * SECOND, false));
        assertEquals(0, guard.lastPeerStamp());
        assertEquals(119 * SECOND, guard.peerIdleNanos());
        guard.observe(120 * SECOND, false);
        assertEquals("peer-liveness-idle", guard.failure());
    }

    @Test void progressCountersRejectNegativeAndCapOverflowWhileFaceCounterRemainsLong() {
        for (var lane : NativeDistanceProgressWatchdog.Lane.values()) {
            var negative = guard(false);
            assertFalse(negative.creditLane(lane, -1)); assertTrue(negative.failed());
            var overflow = guard(false);
            assertFalse(overflow.creditLane(lane, lane.cap() + 1)); assertTrue(overflow.failed());
        }
        var guard = guard(false);
        assertTrue(guard.creditLane(FACE_SCANS, 4_831_838_208L));
        assertTrue(guard.creditLane(DH_UPLOADED_BYTES, 33_554_432L));
        assertEquals(4_831_838_208L, guard.rawVector()[FACE_SCANS.ordinal()]);
        assertEquals(33_554_432L, guard.rawVector()[DH_UPLOADED_BYTES.ordinal()]);
    }

    @Test void thirtyMinuteTotalCannotBeExtendedByHealthyWorkNewSerialsActorsOrSettings() {
        var guard = guard(true);
        for (int seconds = 60; seconds < 1_800; seconds += 60) {
            guard.observe(seconds * SECOND, false);
            assertTrue(guard.bindAttempt(7, seconds, seconds % 120 == 0 ? 12 : 24,
                    seconds, seconds, seconds, "", ""));
            assertTrue(guard.creditLane(REPLACEMENT_READS, seconds));
            guard.observePeer(seconds);
            assertFalse(guard.failed());
        }
        guard.observe(1_800 * SECOND, false);
        assertEquals("episode-active-time-limit", guard.failure());
        assertEquals(1_800 * SECOND, guard.activeNanos());
        assertFalse(guard.creditLane(REPLACEMENT_READS, 1_800));
        assertFalse(guard.bindAttempt(7, 1_800, 12, 1_800, 0, 0, "", ""));
    }

    @Test void exactExpiryAndDisconnectAreTerminalBeforeLateEvidence() {
        var guard = guard(true);
        guard.observe(120 * SECOND, false);
        assertFalse(guard.accept(report(1, 100, SERVER_DRAINED, 1), 120 * SECOND, false));
        guard.observePeer(100); guard.fail("disconnect");
        assertEquals("useful-work-idle", guard.failure());
        assertEquals(0, guard.lastPeerStamp());
        var disconnected = guard(true);
        disconnected.fail("disconnect");
        assertFalse(disconnected.creditLane(FACE_SCANS, 1));
        assertFalse(disconnected.accept(report(1, 1, SERVER_DRAINED, 1), SECOND, false));
        disconnected.observe(SECOND, false);
        assertEquals("disconnect", disconnected.failure());
        assertEquals(0, disconnected.activeNanos());
    }

    @Test void explicitServerClockCountsOnlySuppliedActiveTicksAndCanDisablePeerDeadline() {
        var guard = guard(false);
        for (int tick = 1; tick <= 5_000; tick++) {
            guard.advanceActiveNanos(50_000_000L);
            if (tick % 20 == 0) assertTrue(guard.creditLane(FACE_SCANS, tick));
        }
        assertEquals(250 * SECOND, guard.activeNanos());
        assertFalse(guard.failed());
        // The existing client FACE_SCANS prefix must not be overwritten by a zero vector.
        assertTrue(guard.accept(report(1, 1, SERVER_DRAINED, 1)));
        assertEquals(250 * SECOND, guard.activeNanos());
    }

    @Test void configurableLimitsUseExactBoundariesWithoutSleeping() {
        var guard = new NativeDistanceProgressWatchdog(1, 2, 0, false, false,
                new NativeDistanceProgressWatchdog.Limits(3, 4, 10));
        guard.advanceActiveNanos(2); assertFalse(guard.failed());
        guard.advanceActiveNanos(1); assertEquals("useful-work-idle", guard.failure());
        assertThrows(IllegalArgumentException.class, () -> new NativeDistanceProgressWatchdog.Limits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NativeDistanceProgressWatchdog.Limits(1, -1, 1));
    }

    @Test void timeRegressionNegativeDeltaAndOverflowFailClosed() {
        var regression = guard(false);
        regression.observe(-1, false); assertEquals("clock-regression", regression.failure());
        var negative = guard(false);
        negative.advanceActiveNanos(-1); assertEquals("negative-active-delta", negative.failure());
        var subtraction = new NativeDistanceProgressWatchdog(1, 2, Long.MIN_VALUE, false, false);
        subtraction.observe(Long.MAX_VALUE, false); assertEquals("clock-overflow", subtraction.failure());
        var overflow = new NativeDistanceProgressWatchdog(1, 2, 0, false, false,
                new NativeDistanceProgressWatchdog.Limits(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        overflow.advanceActiveNanos(Long.MAX_VALUE - 10);
        assertTrue(overflow.creditLane(FACE_SCANS, 1));
        overflow.advanceActiveNanos(20); assertEquals("clock-overflow", overflow.failure());
        var peer = guard(true); peer.observePeer(-1); assertEquals("negative-peer-tick", peer.failure());
    }

    @Test void tableHasExactlyThirtyOneSettingsAndSnapshotsCannotMutateState() {
        var guard = guard(false);
        long serial = 1;
        for (int selected = 2; selected <= 32; selected++) {
            assertTrue(guard.bindAttempt(7, ++serial, selected, 11, 22, 33, DIGEST, PLAN));
            assertTrue(guard.creditLane(CLIENT_DRAINED, 1));
        }
        assertEquals(31, guard.creditedTable().length);
        for (int selected = 2; selected <= 32; selected++) {
            assertTrue(guard.bindAttempt(7, ++serial, selected, 11, 22, 33, DIGEST, PLAN));
            assertFalse(guard.creditLane(CLIENT_DRAINED, 1));
        }
        var checkpoint = guard.checkpoint();
        checkpoint.raw()[CLIENT_DRAINED.ordinal()] = 99;
        checkpoint.credited()[30][CLIENT_DRAINED.ordinal()] = 99;
        checkpoint.currentCredited()[CLIENT_DRAINED.ordinal()] = 99;
        guard.rawVector()[CLIENT_DRAINED.ordinal()] = 99;
        guard.creditedTable()[30][CLIENT_DRAINED.ordinal()] = 99;
        assertEquals(1, guard.rawVector()[CLIENT_DRAINED.ordinal()]);
        assertEquals(1, guard.currentCredited()[CLIENT_DRAINED.ordinal()]);
        assertEquals(1, checkpoint.raw()[CLIENT_DRAINED.ordinal()]);
        assertTrue(guard.bindAttempt(7, ++serial, 2, 11, 22, 33, DIGEST, PLAN));
        assertEquals(1, checkpoint.raw()[CLIENT_DRAINED.ordinal()]);
        assertEquals(0, guard.rawVector()[CLIENT_DRAINED.ordinal()]);
    }
}
