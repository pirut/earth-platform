package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.coregate.RollingIndexedScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent pure deadline/receipt adversaries. These tests do not attest a Minecraft GUI run. */
class RollingScenarioDeadlineIndependentReviewTest {
    private static final long SECOND = 1_000_000_000L;
    private static final class Run {
        final RollingIndexedScenarioTest.DriverImpl d = new RollingIndexedScenarioTest.DriverImpl();
        final RollingIndexedScenario r = RollingIndexedScenarioTest.start(d);
        final long travelStarted;
        long now;
        Run() { this(0); }
        Run(long start) {
            d.auto = false; now = start; r.tick(now);
            for (int i = 0; i < 5; i++) { d.apply(d.actions.getLast()); r.tick(++now); }
            assertEquals(Kind.TRAVEL_AWAY, d.actions.getLast().kind());
            assertEquals(Status.RUNNING, r.status(), r.report().failure());
            travelStarted = now; pending();
        }
        void pending() { d.ready = false; d.pending = true; d.outcome = Outcome.NONE; d.travel = List.of(); }
        void hop() {
            int delta = d.actions.getLast().kind() == Kind.RETURN ? 1 : -1;
            d.center = d.center.offset(delta, 0, 0); d.pub += 10; d.coverage += 10;
            var next = new ArrayList<>(d.travel);
            next.add(new TravelStep(d.center, d.pub, d.coverage, d.query, true)); d.travel = next;
        }
        void tickAt(long offset) { now = travelStarted + offset; r.tick(now); }
        void readyResult(int neighborhoods) {
            d.ready = true; d.pending = false; d.completed = d.actions.getLast().id();
            d.outcome = Outcome.APPLIED; d.na = d.nb = neighborhoods;
        }
        void returnRoute() {
            for (int i = 1; i <= 5; i++) { hop(); tickAt(i); }
            readyResult(0); tickAt(6);
            assertEquals(Kind.RETURN, d.actions.getLast().kind());
            pending();
        }
        void failOnce() {
            assertEquals(Status.FAIL, r.status(), r.report().failure()); assertEquals(1, d.aborts);
            int actions = d.actions.size(); r.tick(now + 1); r.cancel("Must not replace original failure");
            assertEquals(1, d.aborts); assertEquals(actions, d.actions.size());
            assertTrue(r.report().transcript().size() <= MAX_TRANSCRIPT);
        }
    }

    @Test void constantsAndSnapshotsKeepTheOriginalStallAndSeparateFiniteRouteCaps() {
        assertEquals(2_400, STEP_TICKS); assertEquals(120 * SECOND, STEP_NANOS);
        assertEquals(12_000, TRAVEL_TICKS); assertEquals(600 * SECOND, TRAVEL_NANOS);
        var x = new Run(); var first = x.r.report().deadline();
        assertTrue(first.travelActive()); assertEquals(5, first.requiredTravelHops());
        assertEquals(0, first.acceptedTravelHops()); assertEquals(STEP_TICKS, first.stallTickLimit());
        assertEquals(STEP_NANOS, first.stallNanosLimit()); assertEquals(TRAVEL_TICKS, first.routeTickLimit());
        assertEquals(TRAVEL_NANOS, first.routeNanosLimit());
        x.hop(); x.tickAt(100 * SECOND);
        assertEquals(0, first.acceptedTravelHops(), "Prior diagnostics are immutable values");
        assertTrue(first.acceptedTravel().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> x.r.report().deadline().acceptedTravel().clear());
        assertEquals(1, x.r.report().deadline().acceptedTravelHops());
    }

    @Test void committedHopProofRenewsStallEvenWhilePlayerHasMovedAndFinalObservationIsPending() {
        var x = new Run();
        for (int i = 1; i <= 3; i++) {
            x.hop(); x.tickAt(i * 100 * SECOND);
            assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
            assertEquals(i, x.r.report().deadline().acceptedTravelHops());
            assertEquals(6, x.d.actions.size());
        }
        assertEquals(6, x.r.report().transcript().size(), "Progress alone must not create checkpoints");
    }

    @Test void wallExpiryPrecedesObservationAndCannotBeRescuedByANewReadyHop() {
        var x = new Run(); int[] observations = {0}; x.d.onObserve = () -> observations[0]++;
        x.hop(); x.tickAt(STEP_NANOS + 1);
        x.failOnce(); assertEquals(0, observations[0]); assertEquals(0, x.r.report().deadline().acceptedTravelHops());
    }

    @Test void exactlyAtStallBoundaryProgressIsStillTimelyButPlusOneIsNot() {
        var x = new Run(); x.hop(); x.tickAt(STEP_NANOS);
        assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
        x.hop(); x.tickAt(2 * STEP_NANOS); assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
        x.hop(); x.tickAt(3 * STEP_NANOS + 1); x.failOnce();
        assertEquals(2, x.r.report().deadline().acceptedTravelHops());
    }

    @Test void repeatedPrefixAndPacketCountersCannotRenewAndMayAwaitANewerClientWitness() {
        var x = new Run(); x.hop(); x.tickAt(30 * SECOND);
        x.d.center = x.d.center.offset(-1, 0, 0); x.d.pub += 20; x.d.coverage += 20;
        for (int i = 1; i <= 5; i++) {
            x.tickAt((30 + i * 20) * SECOND);
            assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
        }
        assertEquals(1, x.r.report().deadline().acceptedTravelHops());
        x.tickAt(30 * SECOND + STEP_NANOS + 1); x.failOnce();
        assertEquals(6, x.d.actions.size());
    }

    @Test void noPrefixPacketActivityCannotRenewTheOriginalTravelStall() {
        var x = new Run();
        for (int i = 1; i <= 11; i++) {
            x.d.pub++; x.d.coverage++; x.tickAt(i * 10 * SECOND);
            assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
        }
        x.tickAt(STEP_NANOS + 1); x.failOnce();
    }

    @Test void incompleteOrRevokedQueryAuthorityCannotRenewANewOtherwiseValidPrefix() {
        for (boolean incomplete : List.of(false, true)) {
            var x = new Run(); x.hop(); x.tickAt(30 * SECOND); x.hop();
            if (incomplete) x.d.complete = false; else x.d.current = false;
            x.tickAt(60 * SECOND);
            assertEquals(1, x.r.report().deadline().acceptedTravelHops());
            x.tickAt(30 * SECOND + STEP_NANOS + 1); x.failOnce();
        }
    }

    @Test void everyPreviouslyAcceptedHopFieldIsImmutableEvenInAnOtherwiseLongerPrefix() {
        for (int field = 0; field < 5; field++) {
            var x = new Run(); x.hop(); x.tickAt(10 * SECOND); var old = x.d.travel.getFirst(); x.hop();
            var changed = new TravelStep(field == 0 ? old.center().offset(1, 0, 0) : old.center(),
                    old.publication() + (field == 1 ? 1 : 0), old.coverageGeneration() + (field == 2 ? 1 : 0),
                    field == 3 ? "changed-query" : old.queryIdentity(), field != 4);
            var prefix = new ArrayList<>(x.d.travel); prefix.set(0, changed); x.d.travel = prefix;
            x.tickAt(20 * SECOND); x.failOnce();
            assertEquals(1, x.r.report().deadline().acceptedTravelHops(), "field " + field);
        }
    }

    @Test void anAcceptedPrefixCannotShrinkOrDisappearWhileStillPending() {
        for (int retained : List.of(0, 1)) {
            var x = new Run(); x.hop(); x.hop(); x.tickAt(10 * SECOND);
            x.d.travel = List.copyOf(x.d.travel.subList(0, retained)); x.tickAt(20 * SECOND); x.failOnce();
        }
    }

    @Test void eachMalformedNewHopFailsWithoutWaitingForFinalReady() {
        for (int malformed = 0; malformed < 9; malformed++) {
            var x = new Run(); long pub = x.d.pub, coverage = x.d.coverage; CubePos start = x.d.center;
            x.hop(); var valid = x.d.travel.getFirst();
            CubePos center = switch (malformed) {
                case 0 -> start.offset(1, 0, 0);
                case 1 -> start.offset(-2, 0, 0);
                case 2 -> start.offset(0, 1, 0);
                case 3 -> start.offset(0, 0, 1);
                case 4 -> start;
                default -> valid.center();
            };
            var bad = new TravelStep(center, malformed == 5 ? pub : valid.publication(),
                    malformed == 6 ? coverage : valid.coverageGeneration(),
                    malformed == 7 ? "unrelated-query" : valid.queryIdentity(), malformed != 8);
            x.d.travel = List.of(bad); x.d.center = bad.center(); x.d.pub = bad.publication();
            x.d.coverage = bad.coverageGeneration(); x.tickAt(SECOND); x.failOnce();
        }
    }

    @Test void directionalRouteCannotBacktrackOvershootOrExceedItsFixedHopCount() {
        for (boolean overshoot : List.of(false, true)) {
            var x = new Run(); x.hop();
            if (overshoot) { for (int i = 0; i < 5; i++) x.hop(); }
            else {
                x.d.center = x.d.center.offset(1, 0, 0); x.d.pub += 10; x.d.coverage += 10;
                var list = new ArrayList<>(x.d.travel);
                list.add(new TravelStep(x.d.center, x.d.pub, x.d.coverage, x.d.query, true)); x.d.travel = list;
            }
            x.tickAt(SECOND); x.failOnce();
        }
    }

    @Test void aNewTailCannotClaimAStaleOrDifferentObservedCommittedPair() {
        for (int field = 0; field < 3; field++) for (int authority = 0; authority < 3; authority++) {
            var x = new Run(); x.hop();
            if (field == 0) x.d.center = x.d.center.offset(-1, 0, 0);
            if (field == 1) x.d.pub++;
            if (field == 2) x.d.coverage++;
            if (authority == 1) x.d.complete = false;
            if (authority == 2) x.d.current = false;
            x.tickAt(SECOND); x.failOnce();
        }
    }

    @Test void pendingProgressNeverPermitsScopeOrDurableStateToChange() {
        for (int field = 0; field < 14; field++) {
            var x = new Run(); x.hop(); x.tickAt(SECOND); x.hop();
            switch (field) {
                case 0 -> x.d.world = "other-world";
                case 1 -> x.d.process = "other-process";
                case 2 -> x.d.reload++;
                case 3 -> x.d.epoch++;
                case 4 -> x.d.resource++;
                case 5 -> x.d.a++;
                case 6 -> x.d.b++;
                case 7 -> x.d.ma = 2;
                case 8 -> x.d.mb = 2;
                case 9 -> x.d.focus = Focus.ORIGINAL;
                case 10 -> x.d.index++;
                case 11 -> x.d.root = "other-root";
                case 12 -> x.d.query = "other-query";
                case 13 -> x.d.set = "other-set";
                default -> throw new AssertionError();
            }
            x.tickAt(2 * SECOND); x.failOnce(); assertEquals(6, x.d.actions.size(), "field " + field);
        }
    }

    @Test void aStaleOrFutureActionChannelCannotRenewTravel() {
        for (long actionId : List.of(4L, 7L)) {
            var x = new Run(); x.hop(); x.d.completed = actionId; x.tickAt(SECOND);
            assertEquals(0, x.r.report().deadline().acceptedTravelHops());
            x.tickAt(STEP_NANOS + 1); x.failOnce(); assertEquals(6, x.d.actions.size());
        }
    }

    @Test void acceptedFullPrefixDoesNotReplaceCurrentFinalReadiness() {
        for (int unavailable = 0; unavailable < 4; unavailable++) {
            var x = new Run(); for (int i = 1; i <= 5; i++) { x.hop(); x.tickAt(i * SECOND); }
            x.readyResult(0);
            if (unavailable == 0) x.d.ready = false;
            if (unavailable == 1) x.d.pending = true;
            if (unavailable == 2) x.d.complete = false;
            if (unavailable == 3) x.d.current = false;
            x.tickAt(6 * SECOND); assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
            assertEquals(6, x.d.actions.size());
            x.tickAt(5 * SECOND + STEP_NANOS + 1); x.failOnce();
        }
    }

    @Test void finalAwayReceiptRequiresBothNeighborhoodsCompletelyAbsent() {
        for (boolean badA : List.of(false, true)) {
            var x = new Run(); for (int i = 1; i <= 5; i++) { x.hop(); x.tickAt(i); }
            x.readyResult(0); if (badA) x.d.na = 1; else x.d.nb = 1;
            x.tickAt(6); x.failOnce(); assertEquals(6, x.d.actions.size());
        }
    }

    @Test void returnReceiptRequiresOriginalTargetAndBothFullNeighborhoods() {
        for (int bad = 0; bad < 3; bad++) {
            var x = new Run(); x.returnRoute();
            for (int i = 1; i <= 4; i++) { x.hop(); x.tickAt(6 + i); }
            x.readyResult(27);
            if (bad == 0) x.d.center = x.d.center.offset(1, 0, 0);
            if (bad == 1) x.d.na = 26;
            if (bad == 2) x.d.nb = 26;
            x.tickAt(11); x.failOnce(); assertEquals(7, x.d.actions.size());
        }
    }

    @Test void routeWallCapRemainsEffectiveAfterEveryHopHasRenewedTheStall() {
        var x = new Run();
        for (int i = 1; i <= 5; i++) { x.hop(); x.tickAt(i * 110 * SECOND); }
        x.tickAt(TRAVEL_NANOS); assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
        int[] observations = {0}; x.d.onObserve = () -> observations[0]++; x.readyResult(0);
        x.tickAt(TRAVEL_NANOS + 1); x.failOnce(); assertEquals(0, observations[0]);
        assertEquals(5, x.r.report().deadline().acceptedTravelHops()); assertEquals(6, x.d.actions.size());
    }

    @Test void routeTickCapIsIndependentOfWallClockAndCannotBeRenewedByItsFinalHop() {
        var x = new Run();
        for (int hop = 1; hop <= 5; hop++) {
            for (int tick = 1; tick <= 2_300; tick++) {
                if (tick == 2_300) x.hop(); x.r.tick(x.now);
                assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
            }
        }
        for (int tick = 0; tick < 500; tick++) x.r.tick(x.now);
        assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
        int[] observations = {0}; x.d.onObserve = () -> observations[0]++; x.readyResult(0); x.r.tick(x.now);
        x.failOnce(); assertEquals(0, observations[0]); assertEquals(5, x.r.report().deadline().acceptedTravelHops());
    }

    @Test void tickStallExpiresBeforeObservationEvenWhenWallTimeDoesNotAdvance() {
        var x = new Run(); for (int tick = 0; tick < STEP_TICKS; tick++) x.r.tick(x.now);
        assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
        int[] observations = {0}; x.d.onObserve = () -> observations[0]++; x.hop(); x.r.tick(x.now);
        x.failOnce(); assertEquals(0, observations[0]);
    }

    @Test void signedTickCounterWrapPreservesBothElapsedBudgetsAndProgressRenewal() throws Exception {
        var x = new Run();
        for (String fieldName : List.of("ticks", "stepTick", "routeTick")) {
            var field = RollingIndexedScenario.class.getDeclaredField(fieldName); field.setAccessible(true);
            field.setLong(x.r, Long.MAX_VALUE - 1);
        }
        x.r.tick(x.now); x.hop(); x.r.tick(x.now); x.r.tick(x.now);
        assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
        assertTrue(x.r.report().ticks() < 0); assertEquals(1, x.r.report().deadline().acceptedTravelHops());
        assertEquals(1, x.r.report().deadline().stallElapsedTicks());
        assertEquals(3, x.r.report().deadline().routeElapsedTicks());
    }

    @Test void progressAndAbsoluteWallExpirySupportSignedNanoTimeWrap() {
        var x = new Run(Long.MAX_VALUE - 40 * SECOND);
        for (int i = 1; i <= 5; i++) {
            x.hop(); x.tickAt(i * 110 * SECOND); assertEquals(Status.RUNNING, x.r.status(), x.r.report().failure());
        }
        assertTrue(x.now < 0); x.tickAt(TRAVEL_NANOS + 1); x.failOnce();
        assertTrue(x.r.report().transcript().getLast().elapsedNanos() > TRAVEL_NANOS);
    }

    @Test void cancellationAndReentrantObserveCannotAcceptOrIssueAfterFence() {
        for (boolean reentrant : List.of(false, true)) {
            var x = new Run(); x.hop();
            x.d.onObserve = () -> { if (reentrant) x.r.tick(x.now); else x.r.cancel("Explicit cancellation"); };
            x.tickAt(SECOND); x.failOnce(); assertEquals(6, x.d.actions.size());
            assertEquals(0, x.r.report().deadline().acceptedTravelHops());
        }
    }

    @Test void cancellationOrReentryAtFinalTravelCheckpointCannotIssueReturn() {
        for (boolean reentrant : List.of(false, true)) {
            var x = new Run(); for (int i = 1; i <= 5; i++) { x.hop(); x.tickAt(i); }
            x.readyResult(0);
            x.d.onCheckpoint = () -> { if (reentrant) x.r.tick(x.now); else x.r.cancel("Checkpoint cancelled"); };
            x.tickAt(6); x.failOnce(); assertEquals(6, x.d.actions.size());
            var receipt = x.d.receipts.getLast(); assertEquals("TRAVEL_AWAY", receipt.label());
            assertTrue(receipt.deadline().travelActive()); assertEquals(5, receipt.deadline().acceptedTravelHops());
        }
    }

    @Test void progressSnapshotsDefendAgainstMutationOfTheDriversOriginalList() {
        var x = new Run(); x.hop(); var list = new ArrayList<>(x.d.travel); x.d.travel = list;
        Observation snapshot = x.d.observe(); x.tickAt(SECOND); list.clear();
        assertEquals(1, snapshot.travel().size()); assertThrows(UnsupportedOperationException.class, () -> snapshot.travel().clear());
        x.tickAt(2 * SECOND); x.failOnce();
    }

    @Test void publicationAndCoverageMustIncreaseAtEveryHopNotJustAboveTheRouteBaseline() {
        for (boolean duplicatePublication : List.of(false, true)) {
            var x = new Run(); x.hop(); var first = x.d.travel.getFirst(); x.hop(); var second = x.d.travel.getLast();
            var stale = new TravelStep(second.center(), duplicatePublication ? first.publication() : second.publication(),
                    duplicatePublication ? second.coverageGeneration() : first.coverageGeneration(), second.queryIdentity(), true);
            x.d.travel = List.of(first, stale); x.d.pub = stale.publication(); x.d.coverage = stale.coverageGeneration();
            x.tickAt(SECOND); x.failOnce(); assertEquals(0, x.r.report().deadline().acceptedTravelHops());
        }
    }

    @Test void oversizedReceiptInputFailsClosedWithoutRetainingAnUnboundedProof() {
        var x = new Run(); x.hop();
        x.d.travel = java.util.Collections.nCopies(MAX_TRAVEL_STEPS + 1, x.d.travel.getFirst());
        x.tickAt(SECOND); x.failOnce(); assertEquals(0, x.r.report().deadline().acceptedTravelHops());
        assertTrue(x.r.report().deadline().acceptedTravel().isEmpty());
    }

    @Test void ordinaryAndExternalRecoveryStagesKeepTheOriginalDeadline() {
        for (Mode mode : Mode.values()) {
            var d = new RollingIndexedScenarioTest.DriverImpl(); d.ready = false;
            var expected = mode == Mode.EXERCISE ? null : new RecoveryExpectation("world", "process1", 1, 1, 0,
                    mode == Mode.AFTER_RESTART, 0, 0, 1, 1, 0, "root0", "query0", "set0");
            var r = new RollingIndexedScenario(d, mode, RollingIndexedScenarioTest.ORIGINAL, expected);
            r.tick(0); r.tick(STEP_NANOS + 1);
            assertEquals(Status.FAIL, r.status()); assertFalse(r.report().deadline().travelActive());
            assertTrue(d.actions.isEmpty()); assertEquals(1, d.aborts);
        }
    }
}
