package io.earthplatform.minecraft.coregate;

import org.junit.jupiter.api.Test;

import static io.earthplatform.minecraft.coregate.NativeDistanceProgressWatchdog.Lane.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual owner-thread state, packet producer, and clock adapter. */
class NativeDistanceProgressStateAdversarialTest {
    private static final long SECOND = 1_000_000_000L;
    private static final String HASH = "a".repeat(64), PLAN = "b".repeat(64);
    private static NativeDistanceChangePayload request(long serial, int selected) {
        return NativeDistanceChangePayload.request(41, serial, selected, selected, 0);
    }
    private static NativeDistanceProgressState.ClockSample clock(long activeNanos) {
        return new NativeDistanceProgressState.ClockSample(activeNanos, null);
    }
    private static NativeDistanceProgressState state(boolean server) {
        return new NativeDistanceProgressState(request(1, 24), server, 0, false, 10);
    }

    @Test void allTransportAndPollingMethodsLeaveUsefulCreditUnchanged() {
        var state = state(false);
        assertTrue(state.header(42, 3, 1, HASH, PLAN));
        for (int i = 0; i < 100; i++) {
            assertTrue(state.matchesRequest(state.packet(i)));
            state.sendDue(i); state.ackDue(i);
        }
        state.observe(119 * SECOND, false);
        assertEquals(119 * SECOND, state.checkpoint().usefulIdleNanos());
        assertArrayEquals(new long[NativeDistanceProgressWatchdog.LANE_COUNT], state.checkpoint().rawVector());
        assertEquals(0, state.checkpoint().lastPeerStamp());
        state.observe(120 * SECOND, false);
        assertEquals("useful-work-idle", state.checkpoint().failure());
    }

    @Test void settingAbaRetainsEpisodeClockButCreatesExactFreshTransportBinding() {
        var state = state(false);
        state.header(42, 3, 1, HASH, PLAN);
        state.credit(FACE_SCANS, 100);
        var cancelled = state.packet(10);
        state.observe(30 * SECOND, false);
        state.request(request(2, 12));
        state.header(43, 4, 2, HASH, PLAN);
        state.credit(FACE_SCANS, 50);
        state.observe(60 * SECOND, false);
        state.request(request(3, 24));
        state.header(44, 5, 3, HASH, PLAN);
        assertFalse(state.accept(cancelled));
        state.credit(FACE_SCANS, 100);
        assertFalse(state.watchdog.failed());
        assertEquals(30 * SECOND, state.checkpoint().usefulIdleNanos());
        assertEquals(60 * SECOND, state.checkpoint().activeNanos());
        assertEquals(100, state.packet(0).completed()[FACE_SCANS.ordinal()]);
        assertEquals(41, state.checkpoint().episode());
        assertEquals(3, state.checkpoint().serial());
    }

    @Test void sameJobCannotSwitchPlanAndLateCancelledPacketCannotAdoptIdentity() {
        var state = state(false);
        assertTrue(state.header(42, 3, 1, HASH, PLAN));
        state.credit(SOURCE_COLUMNS, 130);
        var old = state.packet(10);
        assertFalse(state.header(42, 3, 1, HASH, "c".repeat(64)));
        assertEquals(PLAN, state.checkpoint().planIdentity());
        state.request(request(2, 24));
        assertTrue(state.header(43, 4, 2, HASH, "c".repeat(64)));
        assertFalse(state.accept(old));
        assertEquals(43, state.checkpoint().actorEpoch());
        assertEquals(0, state.checkpoint().rawVector()[SOURCE_COLUMNS.ordinal()]);
    }

    @Test void exactServerWallClockIgnoresKnownPauseAndChargesActualUnpausedTime() {
        var state = state(true);
        for (int i = 0; i < 100_000; i++) state.serverTick(10, clock(0));
        assertEquals(0, state.checkpoint().activeNanos());
        state.serverTick(30, clock(SECOND));
        assertEquals(SECOND, state.checkpoint().activeNanos());
        state.credit(ACTOR_SNAPSHOTS, 1);
        state.serverTick(30, clock(SECOND));
        assertEquals(0, state.checkpoint().usefulIdleNanos());
        state.serverTick(2_430, clock(121 * SECOND));
        assertEquals("useful-work-idle", state.checkpoint().failure());
        state.credit(ACTOR_SNAPSHOTS, 2);
        assertEquals(1, state.checkpoint().rawVector()[ACTOR_SNAPSHOTS.ordinal()]);
    }

    @Test void serverWallClockRegressionFailsClosedWithoutEscapingCleanupPath() {
        var state = state(true);
        assertDoesNotThrow(() -> state.serverTick(11, clock(-1)));
        assertTrue(state.watchdog.failed(), "Clock regression must retain a terminal checkpoint");
        assertEquals(0, state.checkpoint().activeNanos());
        state.credit(ACTOR_SNAPSHOTS, 1);
        assertEquals(0, state.checkpoint().rawVector()[ACTOR_SNAPSHOTS.ordinal()]);
    }

    @Test void wrongOriginAndRegressionDoNotRenewUsefulWork() {
        var state = state(false);
        assertThrows(IllegalArgumentException.class, () -> state.credit(ACTOR_SNAPSHOTS, 1));
        state.credit(FACE_SCANS, 100);
        state.observe(5 * SECOND, false);
        state.credit(FACE_SCANS, 99);
        assertEquals("local-progress-rollback:FACE_SCANS", state.checkpoint().failure());
        assertEquals(100, state.checkpoint().rawVector()[FACE_SCANS.ordinal()]);
        assertEquals(5 * SECOND, state.checkpoint().usefulIdleNanos());
    }

    @Test void newerSerialCannotChangePredecessorEpochOrForgetFiniteFailure() {
        var state = state(false);
        assertThrows(IllegalArgumentException.class,
                () -> state.request(NativeDistanceChangePayload.request(42, 2, 24, 24, 0)));
        assertThrows(IllegalArgumentException.class, () -> state.request(request(1, 12)));
        state.observe(120 * SECOND, false);
        var cutoff = state.checkpoint();
        state.request(request(2, 12));
        assertFalse(state.header(43, 1, 1, HASH, PLAN));
        state.credit(FACE_SCANS, 1);
        assertEquals(cutoff.failure(), state.checkpoint().failure());
        assertEquals(cutoff.serial(), state.checkpoint().serial());
        assertEquals(cutoff.activeNanos(), state.checkpoint().activeNanos());
    }

    @Test void emittedPacketsAreImmutableAndDoNotAliasLaterLocalCompletion() {
        var state = state(false);
        state.credit(FACE_SCANS, 4096);
        var first = state.packet(10);
        state.credit(FACE_SCANS, 8192);
        var second = state.packet(11);
        assertEquals(first.observation() + 1, second.observation());
        assertEquals(4096, first.completed()[FACE_SCANS.ordinal()]);
        assertEquals(8192, second.completed()[FACE_SCANS.ordinal()]);
        first.completed()[FACE_SCANS.ordinal()] = 1;
        assertEquals(8192, state.packet(12).completed()[FACE_SCANS.ordinal()]);
    }

    @Test void frozenClientCannotBeMaskedByContinuingAuthoritativeServerWork() {
        var state = state(true);
        long tick = 10;
        for (int second : new int[]{1, 30, 60, 90, 119}) {
            state.serverTick(++tick, clock(second * SECOND));
            state.credit(ACTOR_SNAPSHOTS, second);
        }
        state.serverTick(++tick, clock(120 * SECOND));
        assertEquals("peer-liveness-idle", state.checkpoint().failure());
        assertEquals(SECOND, state.checkpoint().usefulIdleNanos());
    }

    @Test void authenticatedClientSequenceRenewsOnlyServerPeerAndIgnoresItsClaimedServerTick() {
        var server = state(true);
        var client = state(false);
        server.serverTick(30, clock(SECOND));
        assertTrue(server.accept(client.packet(Long.MAX_VALUE)));
        assertEquals(1, server.checkpoint().lastPeerStamp(), "Use client observation, not the client's claimed server tick");
        assertEquals(SECOND, server.checkpoint().usefulIdleNanos());
        server.serverTick(50, clock(2 * SECOND));
        assertTrue(server.accept(client.packet(0)));
        assertEquals(2, server.checkpoint().lastPeerStamp());
        assertEquals(0, server.checkpoint().peerIdleNanos());
        assertEquals(2 * SECOND, server.checkpoint().usefulIdleNanos());
        assertFalse(server.accept(server.packet(50)), "Same-origin reports are never peer evidence");
    }

    @Test void onlyBootstrapEnrichmentMayChangeProducerHeaderWithinAnAttempt() {
        var state = state(false);
        assertTrue(state.header(42, 0, 0, "", ""));
        assertTrue(state.header(42, 3, 1, "", ""));
        assertTrue(state.header(42, 3, 1, HASH, PLAN));
        for (long[] identity : new long[][]{{43, 3, 1}, {42, 4, 1}, {42, 3, 2}, {0, 0, 0}})
            assertFalse(state.header(identity[0], identity[1], identity[2], HASH, PLAN));
        assertFalse(state.header(42, 3, 1, "c".repeat(64), PLAN));
        assertFalse(state.header(42, 3, 1, HASH, "c".repeat(64)));
        assertFalse(state.header(42, 3, 1, "", ""));
        assertEquals(42, state.checkpoint().actorEpoch());
        assertEquals(3, state.checkpoint().resourceGeneration());
    }

    @Test void observationOverflowCannotEmitMalformedPacketOrEscapeTerminalFailure() throws Exception {
        var state = state(false);
        var observation = NativeDistanceProgressState.class.getDeclaredField("observation");
        observation.setAccessible(true); observation.setLong(state, Long.MAX_VALUE);
        assertNull(assertDoesNotThrow(() -> state.packet(0)));
        assertEquals("observation-overflow", state.checkpoint().failure());
        assertNull(state.packet(0));
    }

    @Test void firstAckAfterEachApprovedHeaderEnrichmentRemainsValidWithoutResettingEpisode() {
        var request = request(1, 24);
        var client = new NativeDistanceProgressState(request, false, 0, false, 0);
        var server = new NativeDistanceProgressState(request, true, 0, false, 0);
        server.credit(SERVER_DRAINED, 1);
        assertTrue(client.accept(server.packet(1)));
        client.credit(CLIENT_DRAINED, 1);
        assertTrue(server.accept(client.packet(0)));
        for (int phase = 1; phase <= 3; phase++) {
            long resources = phase >= 2 ? 1 : 0, publication = phase >= 2 ? 1 : 0;
            String digest = phase == 3 ? HASH : "", plan = phase == 3 ? PLAN : "";
            assertTrue(server.header(42, resources, publication, digest, plan));
            var ahead = server.packet(phase + 1);
            assertFalse(client.accept(ahead), "An ACK cannot itself adopt a new producer identity");
            client.observe(phase * SECOND, false); server.serverTick(phase * 20L, clock(phase * SECOND));
            assertTrue(client.header(42, resources, publication, digest, plan));
            assertTrue(client.accept(server.packet(phase + 1)), "First matching ACK after metadata is fresh");
            assertTrue(server.accept(client.packet(0)));
        }
        assertEquals(41, client.checkpoint().episode()); assertEquals(41, server.checkpoint().episode());
        assertEquals(3 * SECOND, client.checkpoint().activeNanos());
        assertEquals(3 * SECOND, server.checkpoint().activeNanos());
        assertEquals(3 * SECOND, client.checkpoint().usefulIdleNanos(), "Header/heartbeat exchanges never renew work");
        assertEquals(3 * SECOND, server.checkpoint().usefulIdleNanos());
        assertEquals(0, client.checkpoint().peerIdleNanos());
        assertEquals(0, server.checkpoint().peerIdleNanos());
    }

    @Test void diagnosticCheckpointCadenceIsBoundedAndCannotChangeClocksOrCredits() {
        var state = state(false);
        assertTrue(state.checkpointDue(0, false));
        assertFalse(state.checkpointDue(10 * SECOND, false), "An unchanged poll is not a log-worthy completion");
        state.credit(FACE_SCANS, 1);
        assertFalse(state.checkpointDue(SECOND, false));
        assertTrue(state.checkpointDue(5 * SECOND, false));
        assertFalse(state.checkpointDue(5 * SECOND + 1, true));
        assertTrue(state.checkpointDue(6 * SECOND, true));
        assertEquals(0, state.checkpoint().activeNanos());
        assertEquals(0, state.checkpoint().lastPeerStamp());
        assertEquals(1, state.checkpoint().rawVector()[FACE_SCANS.ordinal()]);
        var bounded = state(false);
        for (int log = 0; log < 512; log++) assertTrue(bounded.checkpointDue(log * 5 * SECOND, true));
        assertFalse(bounded.checkpointDue(10_000 * SECOND, true));
        bounded.watchdog.fail("terminal-after-receipt-cap");
        assertEquals("terminal-after-receipt-cap", bounded.checkpoint().failure(), "Terminal checkpoint remains available after bounded diagnostic log cap");
    }

    @Test void lowTpsActualTickAcksRemainLiveButRepeatedSameTickNeverRenewPeer() {
        var request = request(1, 24);
        var client = new NativeDistanceProgressState(request, false, 0, false, 0);
        var server = new NativeDistanceProgressState(request, true, 0, false, 0);
        long tick = 0;
        for (int second : new int[]{1, 60, 119, 178, 237}) {
            server.serverTick(++tick, clock(second * SECOND));
            assertTrue(server.ackDue(second * SECOND), "ACK cadence is elapsed time at an actual tick, not every twentieth tick");
            assertFalse(server.ackDue(second * SECOND + 1));
            client.observe(second * SECOND, false); client.credit(FACE_SCANS, second);
            assertTrue(client.accept(server.packet(tick)));
            assertTrue(server.accept(client.packet(0)));
            assertEquals(0, client.checkpoint().peerIdleNanos());
            assertFalse(client.watchdog.failed());
        }
        client.observe(356 * SECOND, false); client.credit(FACE_SCANS, 356);
        assertTrue(client.accept(server.packet(tick)));
        assertEquals(119 * SECOND, client.checkpoint().peerIdleNanos());
        client.observe(357 * SECOND, false);
        assertEquals("peer-liveness-idle", client.watchdog.failure());
        assertEquals(SECOND, client.checkpoint().usefulIdleNanos());
    }

    @Test void sharedActualPauseTimelineExemptsA125SecondMenuAndChargesPriorEdge() {
        var timeline = new NativeDistanceProgressState.ActualPauseClock(0, false);
        var server = new NativeDistanceProgressState(request(1, 24), true, 0, false, 0);
        timeline.edge(5 * SECOND, true);
        server.serverTick(1, clock(timeline.sample(5 * SECOND)));
        assertEquals(5 * SECOND, server.checkpoint().activeNanos());
        assertEquals(5 * SECOND, timeline.sample(130 * SECOND));
        server.serverTick(1, clock(timeline.sample(130 * SECOND)));
        assertFalse(server.watchdog.failed());
        timeline.edge(130 * SECOND, false);
        server.serverTick(2, clock(timeline.sample(131 * SECOND)));
        assertEquals(6 * SECOND, server.checkpoint().activeNanos());
        server.serverTick(3, clock(timeline.sample(245 * SECOND)));
        assertEquals(120 * SECOND, server.checkpoint().activeNanos());
        assertEquals("useful-work-idle", server.watchdog.failure());
    }

    @Test void slowServerWallClockTotalCapCannotBeExtendedByContinuingWorkAndPeerReports() {
        var timeline = new NativeDistanceProgressState.ActualPauseClock(0, false);
        var server = new NativeDistanceProgressState(request(1, 24), true, 0, false, 0);
        var client = new NativeDistanceProgressState(request(1, 24), false, 0, false, 0);
        long tick = 0;
        for (int second = 60; second < 1800; second += 60) {
            server.serverTick(++tick, clock(timeline.sample(second * SECOND)));
            client.credit(FACE_SCANS, second);
            assertTrue(server.accept(client.packet(0)));
            assertFalse(server.watchdog.failed());
        }
        server.serverTick(++tick, clock(timeline.sample(1800 * SECOND)));
        assertEquals(30, tick, "Only thirty actual ticks elapsed; deadline must still be thirty wall minutes");
        assertEquals("episode-active-time-limit", server.watchdog.failure());
        assertEquals(1800 * SECOND, server.checkpoint().activeNanos());
    }

    @Test void sharedPauseTimelineNeverForgivesUnknownGapAndFailsClosedOnRegressionOrOverflow() {
        var active = new NativeDistanceProgressState.ActualPauseClock(0, false);
        assertEquals(200 * SECOND, active.sample(200 * SECOND));
        active.edge(199 * SECOND, true);
        assertEquals("pause-clock-regression", active.failure());
        assertEquals(200 * SECOND, active.sample(9999 * SECOND));
        var overflow = new NativeDistanceProgressState.ActualPauseClock(Long.MIN_VALUE, true);
        overflow.edge(Long.MAX_VALUE, false);
        assertEquals("pause-clock-overflow", overflow.failure());
        assertEquals(0, overflow.sample(Long.MAX_VALUE));
        var state = state(true);
        state.serverTick(11, new NativeDistanceProgressState.ClockSample(0, overflow.failure()));
        assertEquals("pause-clock-overflow", state.watchdog.failure());
    }

    @Test void clockRegistryRejectsDifferentServerEpisodeAndStaleConnectionRevocation() {
        Object server = new Object(), connection = new Object(), other = new Object();
        try {
            NativeDistanceProgressState.bindPauseClock(server, connection, 41, true);
            assertEquals(0, NativeDistanceProgressState.serverClock(server, 41).activeNanos());
            assertNull(NativeDistanceProgressState.serverClock(server, 41).failure());
            assertEquals("pause-clock-owner-changed", NativeDistanceProgressState.serverClock(other, 41).failure());
            assertEquals("pause-clock-owner-changed", NativeDistanceProgressState.serverClock(server, 42).failure());
            NativeDistanceProgressState.observeActualPause(server, other, 41, false);
            NativeDistanceProgressState.revokePauseClock(server, other, 41);
            assertEquals(0, NativeDistanceProgressState.serverClock(server, 41).activeNanos());
            assertNull(NativeDistanceProgressState.serverClock(server, 41).failure());
            NativeDistanceProgressState.bindPauseClock(server, connection, 41, true);
            assertEquals(0, NativeDistanceProgressState.serverClock(server, 41).activeNanos());
            NativeDistanceProgressState.revokePauseClock(server, connection, 41);
            assertEquals("pause-clock-owner-changed", NativeDistanceProgressState.serverClock(server, 41).failure());
        } finally { NativeDistanceProgressState.revokePauseClock(server, connection, 41); }
    }

    @Test void conflictingLiveClockBindCannotResetSameEpisodeBeforeFirstServerObservation() {
        Object server = new Object(), connection = new Object(), other = new Object();
        try {
            assertTrue(NativeDistanceProgressState.bindPauseClock(server, connection, 41, true));
            assertFalse(NativeDistanceProgressState.bindPauseClock(server, other, 41, false), "Different connection must not reset same server/episode before first sample");
            assertFalse(NativeDistanceProgressState.bindPauseClock(server, connection, 42, false));
            assertFalse(NativeDistanceProgressState.bindPauseClock(other, connection, 41, false));
            assertEquals(0, NativeDistanceProgressState.serverClock(server, 41).activeNanos());
            assertNull(NativeDistanceProgressState.serverClock(server, 41).failure());
            assertTrue(NativeDistanceProgressState.bindPauseClock(server, connection, 41, true));
            NativeDistanceProgressState.revokePauseClock(server, connection, 41);
            assertTrue(NativeDistanceProgressState.bindPauseClock(server, other, 42, true));
            assertEquals("pause-clock-owner-changed", NativeDistanceProgressState.serverClock(server, 41).failure());
            assertNull(NativeDistanceProgressState.serverClock(server, 42).failure());
        } finally {
            NativeDistanceProgressState.revokePauseClock(server, connection, 41);
            NativeDistanceProgressState.revokePauseClock(server, other, 42);
        }
    }
}
