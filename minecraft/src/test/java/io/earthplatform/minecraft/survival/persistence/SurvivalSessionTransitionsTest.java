package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpointTest.*;
import static io.earthplatform.minecraft.survival.persistence.SurvivalSessionTransitions.*;
import static org.junit.jupiter.api.Assertions.*;

/** Model/ownership tests only. Harness never constructs native players or proves teleport/save behavior. */
class SurvivalSessionTransitionsTest {
    @BeforeAll static void init() { setup(); }
    static NativePlayerPoseSnapshot safeEarthPose() {
        return new NativePlayerPoseSnapshot(binding.dimension(), -19344520.5, 7314.875, -4297490.75,
                -127.25F, 42.5F, 0, 0, 0, 0, true, GameType.SURVIVAL, null,
                new NativePlayerPoseSnapshot.AbilityState(false, false, false, false, true, 0.05F, 0.1F));
    }
    static Boundary emptyEarth() {
        return new Boundary(1000, new NativeInventorySnapshot(java.util.Collections.nCopies(41,
                NativeStackSnapshot.capture(ItemStack.EMPTY, registries)), 0),
                new NativeFoodSnapshot(20, 0, 5, 0), safeEarthPose(), List.of(), List.of());
    }
    static OrdinaryState ordinary() { return new OrdinaryState(inventory(), food(), ordinaryPose()); }
    static Boundary copy(Boundary value) {
        return new Boundary(value.gameTime(), value.inventory(), value.food(), value.pose(), value.items(), value.scheduledTicks());
    }
    static NativeSurvivalCheckpoint image(NativeSurvivalCheckpoint basis, Boundary boundary, Session session,
            UUID owner, long generation) {
        return new NativeSurvivalCheckpoint(LIMITS, binding, owner, generation,
                basis == null ? 0 : basis.commitSequence(), basis == null ? Map.of() : basis.actorSequences(),
                basis == null ? Map.of() : basis.cubes(), boundary, session);
    }
    static final class Harness implements Port {
        final Object identity = new Object();
        Object currentIdentity = identity;
        UUID epoch = UUID.randomUUID();
        Binding currentBinding = binding;
        NativeSurvivalCheckpoint durable;
        OrdinaryState ordinary = ordinary();
        Boundary seed = emptyEarth(), liveEarth;
        boolean healthy = true, held, quiescent = true, settleEarth = true, settleOrdinary = true, saveReady;
        boolean failApplyEarth, failApplyOrdinary, failSave, failBegin, driftEarth, activeReady=true, failActiveReady;
        boolean returnReady = true, failReturnReady;
        Runnable onReturnReady = () -> {};
        int savedEarthChecks;
        int captures, initialPlans, earthApplications, ordinaryApplications, saves, releases;
        final List<String> log = new ArrayList<>();
        final List<CheckpointRequest> requests = new ArrayList<>();
        Operation operation;
        Runnable onApply = () -> {}, onRelease = () -> {}, onCheckpointPoll = () -> {}, onSavePoll = () -> {}, onActiveReady=()->{}, onSavedExact=()->{};
        public Object authority() { return currentIdentity; }
        public Binding binding() { return currentBinding; }
        public UUID ownerEpoch() { return epoch; }
        public void requireHealthy() { if (!healthy) throw new SurvivalRefusal("Native connection/owner lost"); }
        public NativeSurvivalCheckpoint durable() { return durable; }
        public void hold() { held = true; log.add("hold"); }
        public boolean heldAndQuiescent() { if (!held) throw new SurvivalRefusal("Hold lost"); return quiescent; }
        public OrdinaryState captureOrdinary() { captures++; log.add("capture ordinary"); return ordinary; }
        public void requireOrdinaryExact(Session session) {
            if (!ordinary.equals(new OrdinaryState(session.ordinaryEscrow(), session.ordinaryFood(), session.ordinaryPose())))
                throw new SurvivalRefusal("Ordinary data changed under hold");
        }
        public Boundary initialEarthBoundary() { initialPlans++; return seed; }
        public PreparedChange prepareEarth(Boundary planned) {
            return new PreparedChange() {
                public void applyOnce(NativeSurvivalCheckpoint permit) {
                    assertTrue(held); assertSame(durable, permit); assertSame(planned, permit.boundary());
                    earthApplications++; log.add("apply Earth"); onApply.run();
                    if (failApplyEarth) throw new SurvivalRefusal("Partially applied Earth install");
                    liveEarth = planned;
                    ordinary = new OrdinaryState(planned.inventory(), planned.food(), planned.pose());
                }
                public Result pollSettled() { return settleEarth ? Result.succeeded() : Result.pending(); }
            };
        }
        public PreparedChange prepareOrdinary(Session session) {
            return new PreparedChange() {
                public void applyOnce(NativeSurvivalCheckpoint permit) {
                    assertTrue(held); assertSame(durable, permit); assertEquals(SessionPhase.RETURNING, permit.session().phase());
                    ordinaryApplications++; log.add("apply ordinary"); onApply.run();
                    if (failApplyOrdinary) throw new SurvivalRefusal("Partially applied ordinary restore");
                    ordinary = new OrdinaryState(session.ordinaryEscrow(), session.ordinaryFood(), session.ordinaryPose());
                }
                public Result pollSettled() { return settleOrdinary ? Result.succeeded() : Result.pending(); }
            };
        }
        public void requireInstalledEarthExact(NativeSurvivalCheckpoint active) {
            if (driftEarth || !liveEarth.equals(active.boundary())) throw new SurvivalRefusal("Live Earth drifted under hold");
        }
        public boolean returnReady() {
            onReturnReady.run();
            if (failReturnReady) throw new SurvivalRefusal("Return readiness authority failed");
            return returnReady;
        }
        public boolean activeSaveReady() {
            onActiveReady.run();
            if(failActiveReady)throw new SurvivalRefusal("Readiness callback failed");
            return activeReady;
        }
        public void requireSavedEarthExact(NativeSurvivalCheckpoint active) {
            savedEarthChecks++; onSavedExact.run();
            if(driftEarth || !liveEarth.equals(active.boundary()))throw new SurvivalRefusal("Saved live boundary changed");
        }
        public CheckpointOperation beginCheckpoint(CheckpointRequest request) {
            assertTrue(held); assertSame(identity, request.authority()); assertSame(durable, request.previous());
            if (failBegin) throw new SurvivalRefusal("Unknown partial checkpoint setup");
            requests.add(request); log.add("freeze " + request.session().phase());
            Boundary frozen = request.kind() == CaptureKind.LIVE_EARTH ? liveEarth : request.retainedBoundary();
            operation = new Operation(image(durable, frozen, request.session(), epoch, request.previousGeneration() + 1));
            return operation;
        }
        final class Operation implements CheckpointOperation {
            NativeSurvivalCheckpoint frozen;
            boolean writerFinished, ownerPollReady, failed, physicalOwnerDrained=true;
            int retries;
            Operation(NativeSurvivalCheckpoint frozen) { this.frozen = frozen; }
            public Result poll() {
                onCheckpointPoll.run();
                if (failed) return Result.failed(new SurvivalRefusal("Store failure"));
                if (!writerFinished || !ownerPollReady) return Result.pending();
                durable = frozen; log.add("ack " + durable.session().phase()); return Result.succeeded();
            }
            public boolean retryReady(){return physicalOwnerDrained;}
            public void retryExact() { assertTrue(failed); retries++; failed = false; writerFinished = ownerPollReady = false; }
        }
        public SaveOperation saveOrdinary(Session session, NativeSurvivalCheckpoint returning) {
            assertTrue(held); assertSame(durable, returning); requireOrdinaryExact(session);
            saves++; log.add("start ordinary save");
            return () -> {
                onSavePoll.run();
                return failSave ? Result.failed(new SurvivalRefusal("Ordinary save failed"))
                        : saveReady ? Result.succeeded() : Result.pending();
            };
        }
        public void release() { onRelease.run(); assertTrue(held); releases++; held = false; log.add("release"); }
        void complete() { operation.writerFinished = operation.ownerPollReady = true; }
    }
    static SurvivalSessionTransitions start(Harness host) { return new SurvivalSessionTransitions(host); }
    static void activate(Harness host, SurvivalSessionTransitions transitions) {
        transitions.enter(); transitions.tick(); host.complete(); transitions.tick(); transitions.tick();
        host.complete(); transitions.tick(); assertEquals(State.ACTIVE, transitions.state());
    }
    static void restoreOrdinary(Harness host, SurvivalSessionTransitions transitions) {
        transitions.leave(); transitions.tick(); host.complete(); transitions.tick(); transitions.tick();
        assertEquals(State.ORDINARY_SAVE, transitions.state());
    }
    static void finishReturn(Harness host, SurvivalSessionTransitions transitions) {
        restoreOrdinary(host, transitions); host.saveReady = true; transitions.tick(); host.complete(); transitions.tick();
        assertEquals(State.RETURNED, transitions.state());
    }


    @Test void returnReadinessFalseKeepsTheSameActiveOwnerAndControls() {
        var h = new Harness(); var t = start(h); activate(h,t);
        var durable = h.durable; var session = t.sessionId(); var log = List.copyOf(h.log);
        h.returnReady = false;
        assertThrows(SurvivalRefusal.class,t::leave);
        assertEquals(State.ACTIVE,t.state()); assertTrue(t.allowsEarthActions()); assertFalse(h.held);
        assertSame(durable,h.durable); assertEquals(session,t.sessionId()); assertEquals(log,h.log);
        assertNull(t.failure()); assertEquals(2,h.requests.size());
        h.returnReady = true; t.leave(); assertEquals(State.DRAINING_RETURN,t.state()); assertTrue(h.held);
    }
    @Test void returnReadinessExceptionOrCaughtReentryHoldsTheSameFailedOwner() {
        for (boolean reentry : List.of(false,true)) {
            var h = new Harness(); var t = start(h); activate(h,t); var durable = h.durable;
            if (reentry) h.onReturnReady = () -> assertThrows(SurvivalRefusal.class,t::leave);
            else h.failReturnReady = true;
            assertThrows(SurvivalRefusal.class,t::leave);
            assertEquals(State.FAILED,t.state()); assertTrue(h.held); assertFalse(t.allowsEarthActions());
            assertSame(durable,h.durable); assertEquals(2,h.requests.size()); assertNotNull(t.failure());
        }
    }

    @Test void activeSavePersistsLiveBoundaryWithoutReinstallOrOrdinaryRestore() {
        var h=new Harness();var t=start(h);activate(h,t);var prior=h.durable;var session=t.sessionId();
        h.liveEarth=new Boundary(1011,inventory(),food(),safeEarthPose(),List.of(),List.of());
        t.saveActive();assertTrue(h.held);assertEquals(State.DRAINING_ACTIVE_SAVE,t.state());
        t.tick();var request=h.requests.getLast();assertEquals(CheckpointPurpose.ACTIVE_SAVE,request.purpose());
        assertEquals(CaptureKind.LIVE_EARTH,request.kind());assertSame(prior,request.previous());
        assertEquals(session,request.session().id());assertEquals(SessionPhase.ACTIVE,request.session().phase());
        h.operation.writerFinished=true;t.tick();assertTrue(h.held);assertEquals(0,h.savedEarthChecks);
        h.complete();t.tick();assertEquals(State.ACTIVE,t.state());assertFalse(h.held);
        assertEquals(prior.generation()+1,h.durable.generation());assertSame(h.liveEarth,h.durable.boundary());
        assertEquals(1,h.earthApplications);assertEquals(0,h.ordinaryApplications+h.saves);assertEquals(1,h.savedEarthChecks);
        assertEquals(2,h.releases);t.tick();assertEquals(2,h.releases);
    }
    @Test void activeSaveReadinessFalsePreservesActiveIngressWithoutHoldOrSubmit() {
        var h=new Harness();var t=start(h);activate(h,t);h.activeReady=false;var log=List.copyOf(h.log);int requests=h.requests.size();
        assertThrows(SurvivalRefusal.class,t::saveActive);assertEquals(State.ACTIVE,t.state());assertFalse(h.held);
        assertTrue(t.allowsEarthActions());assertEquals(log,h.log);assertEquals(requests,h.requests.size());assertNull(t.failure());
        h.activeReady=true;t.saveActive();assertEquals(State.DRAINING_ACTIVE_SAVE,t.state());
    }
    @Test void activeSaveReadinessThrowAndCaughtReentryRemainPermanentlyHeld() {
        for(boolean reentry:List.of(false,true)) {
            var h=new Harness();var t=start(h);activate(h,t);
            if(reentry)h.onActiveReady=()->assertThrows(SurvivalRefusal.class,t::saveActive);else h.failActiveReady=true;
            assertThrows(SurvivalRefusal.class,t::saveActive);assertEquals(State.FAILED,t.state());assertTrue(h.held);
            assertFalse(t.allowsEarthActions());assertEquals(2,h.requests.size());assertNotNull(t.failure());
        }
    }
    @Test void activeSaveOvertakenBaseFailsBeforeSecondCapture() {
        var h=new Harness();var t=start(h);activate(h,t);t.saveActive();
        h.durable=image(h.durable,h.liveEarth,h.durable.session(),h.epoch,h.durable.generation()+1);
        assertThrows(SurvivalRefusal.class,t::tick);assertEquals(State.FAILED,t.state());assertTrue(h.held);assertEquals(2,h.requests.size());
    }
    @Test void activeSaveWriterRetryRetainsExactImageRequestAndOperation() {
        var h=new Harness();var t=start(h);activate(h,t);t.saveActive();t.tick();var op=h.operation;var frozen=op.frozen;var req=h.requests.getLast();
        op.failed=true;t.tick();assertNotNull(t.checkpointFailure());assertTrue(h.held);
        t.retryCheckpoint();assertSame(op,h.operation);assertSame(frozen,op.frozen);assertSame(req,h.requests.getLast());assertEquals(1,op.retries);
        h.complete();t.tick();assertEquals(State.ACTIVE,t.state());assertSame(frozen,h.durable);assertEquals(3,h.requests.size());
        assertEquals(1,h.earthApplications);assertEquals(0,h.ordinaryApplications+h.saves);
    }
    @Test void activeSaveWrongPhaseOrGenerationAcknowledgementCannotRelease() {
        for(boolean phase:List.of(false,true)) {
            var h=new Harness();var t=start(h);activate(h,t);t.saveActive();t.tick();var expected=h.operation.frozen;
            var session=phase?new Session(expected.session().id(),SessionPhase.RETURNING,expected.session().ordinaryEscrow(),expected.session().ordinaryFood(),expected.session().ordinaryPose()):expected.session();
            h.operation.frozen=image(expected,expected.boundary(),session,h.epoch,expected.generation()+(phase?0:1));h.complete();
            assertThrows(SurvivalRefusal.class,t::tick);assertEquals(State.FAILED,t.state());assertTrue(h.held);assertEquals(1,h.releases);assertEquals(0,h.savedEarthChecks);
        }
    }
    @Test void activeSaveLiveBoundaryDriftOrSavedCheckReentryCannotRelease() {
        for(boolean reentry:List.of(false,true)) {
            var h=new Harness();var t=start(h);activate(h,t);t.saveActive();t.tick();
            if(reentry)h.onSavedExact=()->assertThrows(SurvivalRefusal.class,t::saveActive);else h.driftEarth=true;
            h.complete();assertThrows(SurvivalRefusal.class,t::tick);assertEquals(State.FAILED,t.state());assertTrue(h.held);assertEquals(1,h.releases);
        }
    }
    @Test void activeSaveHasNoGenericRetryWhenCaptureFailsBeforeAuthenticImage() {
        var h=new Harness();var t=start(h);activate(h,t);h.failBegin=true;t.saveActive();
        assertThrows(SurvivalRefusal.class,t::tick);assertEquals(State.FAILED,t.state());assertTrue(h.held);
        assertThrows(SurvivalRefusal.class,t::retryCheckpoint);assertEquals(2,h.requests.size());
    }
    @Test void duplicateSaveAndCaughtReentrantReleaseDoNotProduceSecondCheckpoint() {
        var h=new Harness();var t=start(h);activate(h,t);t.saveActive();
        assertThrows(SurvivalRefusal.class,t::saveActive);assertEquals(State.DRAINING_ACTIVE_SAVE,t.state());t.tick();
        h.onRelease=()->assertThrows(SurvivalRefusal.class,t::saveActive);h.complete();
        assertThrows(SurvivalRefusal.class,t::tick);assertEquals(State.FAILED,t.state());assertFalse(t.allowsEarthActions());assertEquals(3,h.requests.size());
    }

    @Test void enteringIsAcknowledgedBeforeOneEarthInstallAndActiveAcknowledgementBeforeRelease() {
        var h = new Harness(); var t = start(h); t.enter();
        assertTrue(h.held); assertFalse(t.allowsEarthActions()); assertFalse(t.allowsOrdinaryControls());
        t.tick(); assertEquals(State.ENTERING_CHECKPOINT, t.state()); assertEquals(0, h.earthApplications);
        h.operation.writerFinished = true; t.tick(); assertEquals(0, h.earthApplications);
        assertEquals(State.ENTERING_CHECKPOINT, t.state(), "Writer completion alone is never a durable acknowledgement");
        h.operation.ownerPollReady = true; t.tick(); assertEquals(1, h.earthApplications); assertTrue(h.held);
        t.tick(); assertEquals(State.ACTIVE_CHECKPOINT, t.state()); assertTrue(h.held);
        h.complete(); t.tick(); assertTrue(t.allowsEarthActions()); assertFalse(h.held);
        assertEquals(List.of("hold", "capture ordinary", "freeze ENTERING", "ack ENTERING", "apply Earth",
                "freeze ACTIVE", "ack ACTIVE", "release"), h.log);
    }
    @Test void returnPersistsEarthBeforeOneRestoreThenWaitsForSaveAndReturnedDurability() {
        var h = new Harness(); var t = start(h); var before = h.ordinary; activate(h, t);
        t.leave(); t.tick(); assertEquals(0, h.ordinaryApplications); assertEquals(0, h.saves);
        h.complete(); t.tick(); assertEquals(1, h.ordinaryApplications); assertEquals(before, h.ordinary); assertEquals(0, h.saves);
        t.tick(); assertEquals(1, h.saves);
        for (int i = 0; i < 8; i++) t.tick();
        assertEquals(State.ORDINARY_SAVE, t.state()); assertTrue(h.held); assertEquals(1, h.ordinaryApplications);
        var finalEarth = h.durable; h.saveReady = true; t.tick();
        assertEquals(State.RETURNED_CHECKPOINT, t.state()); assertSame(finalEarth.boundary(), h.operation.frozen.boundary());
        assertFalse(t.allowsOrdinaryControls()); h.complete(); t.tick();
        assertEquals(State.RETURNED, t.state()); assertTrue(t.allowsOrdinaryControls()); assertFalse(h.held);
        assertEquals(1, h.ordinaryApplications); assertEquals(1, h.saves);
    }
    @Test void laterVisitCapturesFreshOrdinaryEscrowAndUsesRetainedEarthWithNewSessionToken() {
        var h = new Harness(); var t = start(h); activate(h, t);
        h.liveEarth = new Boundary(1011, inventory(), food(), safeEarthPose(), List.of(), List.of());
        finishReturn(h, t); UUID oldSession = t.sessionId(); var retained = h.durable;
        h.ordinary = new OrdinaryState(emptyEarth().inventory(), new NativeFoodSnapshot(12, 7, 1, 2),
                NativeSurvivalCheckpointTest.pose(ResourceLocation.parse("minecraft:the_end"), 94, GameType.ADVENTURE, GameType.SURVIVAL));
        var fresh = h.ordinary; t.enter(); t.tick();
        assertNotEquals(oldSession, t.sessionId()); assertEquals(2, h.captures); assertEquals(1, h.initialPlans);
        assertSame(retained.boundary(), h.requests.getLast().retainedBoundary());
        assertEquals(fresh.inventory(), h.requests.getLast().session().ordinaryEscrow());
        assertEquals(fresh.pose(), h.requests.getLast().session().ordinaryPose());
        h.complete(); t.tick(); t.tick(); h.complete(); t.tick();
        assertEquals(State.ACTIVE, t.state()); assertEquals(inventory(), h.liveEarth.inventory());
    }
    @Test void returnedTombstoneNeverRestoresOrdinaryAgainOnRestart() {
        var first = new Harness(); var t = start(first); activate(first, t); finishReturn(first, t);
        var restarted = new Harness(); restarted.durable = first.durable;
        restarted.ordinary = new OrdinaryState(emptyEarth().inventory(), food(), ordinaryPose());
        var actualOrdinary = restarted.ordinary; var next = start(restarted); next.tick();
        assertEquals(State.ORDINARY, next.state()); assertSame(actualOrdinary, restarted.ordinary);
        assertEquals(0, restarted.ordinaryApplications); assertEquals(0, restarted.captures); assertFalse(restarted.held);
    }
    @Test void everyUnfinishedReloadPhaseRequiresRecoveryRatherThanInventingAnInstallOrSaveReceipt() {
        for (SessionPhase phase : List.of(SessionPhase.ENTERING, SessionPhase.ACTIVE, SessionPhase.RETURNING)) {
            var h = new Harness(); h.durable = image(null, emptyEarth(), new Session(SESSION, phase, inventory(), food(), ordinaryPose()), UUID.randomUUID(), 7);
            var t = start(h); assertEquals(State.RECOVERY_REQUIRED, t.state()); assertTrue(h.held);
            t.tick(); assertThrows(SurvivalRefusal.class, t::enter); assertThrows(SurvivalRefusal.class, t::leave);
            assertEquals(0, h.earthApplications + h.ordinaryApplications + h.saves);
        }
    }
    @Test void checkpointRetryUsesSameRequestAndFrozenOperationWithoutAnotherCaptureOrInstall() {
        var h = new Harness(); var t = start(h); t.enter(); t.tick(); var operation = h.operation; var request = h.requests.getFirst();
        operation.failed = true; t.tick(); assertNotNull(t.checkpointFailure()); assertTrue(h.held);
        t.retryCheckpoint(); assertSame(operation, h.operation); assertSame(request, h.requests.getFirst());
        assertEquals(1, h.captures); assertEquals(1, h.requests.size()); assertEquals(0, h.earthApplications);
        assertEquals(1, operation.retries); h.complete(); t.tick(); assertEquals(1, h.earthApplications);
    }
    @Test void returnedCheckpointRetryNeverReappliesOrdinaryOrRestartsNativeSave() {
        var h = new Harness(); var t = start(h); activate(h, t); restoreOrdinary(h, t);
        h.saveReady = true; t.tick(); var operation = h.operation; operation.failed = true; t.tick();
        t.retryCheckpoint(); h.complete(); t.tick();
        assertEquals(State.RETURNED, t.state()); assertEquals(1, h.ordinaryApplications); assertEquals(1, h.saves);
    }
    @Test void mutationFailureIsStickyWithNoReplayOrGenericRetry() {
        var h = new Harness(); var t = start(h); h.failApplyEarth = true; t.enter(); t.tick(); h.complete();
        assertThrows(SurvivalRefusal.class, t::tick); assertEquals(State.FAILED, t.state());
        h.failApplyEarth = false; for (int i = 0; i < 4; i++) t.tick();
        assertThrows(SurvivalRefusal.class, t::retryCheckpoint); assertThrows(SurvivalRefusal.class, t::enter);
        assertEquals(1, h.earthApplications); assertTrue(h.held); assertFalse(t.allowsEarthActions());
    }
    @Test void ordinaryMutationAndSaveFailuresCannotReinstallEscrow() {
        for (boolean failMutation : List.of(false, true)) {
            var h = new Harness(); var t = start(h); activate(h, t);
            h.failApplyOrdinary = failMutation; h.failSave = !failMutation;
            t.leave(); t.tick(); h.complete();
            if (failMutation) assertThrows(SurvivalRefusal.class, t::tick);
            else { t.tick(); t.tick(); t.tick(); }
            assertEquals(State.FAILED, t.state()); t.tick(); assertEquals(1, h.ordinaryApplications);
            assertTrue(h.held); assertFalse(t.allowsOrdinaryControls()); assertThrows(SurvivalRefusal.class, t::retryCheckpoint);
        }
    }
    @Test void changedOrdinaryBeforeImageDuringEnteringIsRefusedBeforeEarthMutation() {
        var h = new Harness(); var t = start(h); t.enter(); t.tick(); h.complete();
        h.ordinary = new OrdinaryState(emptyEarth().inventory(), food(), ordinaryPose());
        assertThrows(SurvivalRefusal.class, t::tick); assertEquals(0, h.earthApplications); assertTrue(h.held);
    }
    @Test void changedOrdinaryDuringSaveCannotPublishReturnedTombstone() {
        var h = new Harness(); var t = start(h); activate(h, t); restoreOrdinary(h, t); h.saveReady = true;
        h.ordinary = new OrdinaryState(emptyEarth().inventory(), food(), ordinaryPose());
        assertThrows(SurvivalRefusal.class, t::tick); assertEquals(SessionPhase.RETURNING, h.durable.session().phase());
        assertTrue(h.held); assertEquals(1, h.ordinaryApplications);
    }
    @Test void wrongGenerationOwnerSessionOrRetainedBoundaryCannotAcknowledgeTheTransition() {
        for (int fault = 0; fault < 4; fault++) {
            var h = new Harness(); var t = start(h); t.enter(); t.tick(); var expected = h.operation.frozen;
            Boundary boundary = fault == 0 ? copy(expected.boundary()) : expected.boundary();
            UUID owner = fault == 1 ? UUID.randomUUID() : expected.captureOwner();
            long generation = fault == 2 ? expected.generation() + 1 : expected.generation();
            Session session = fault == 3 ? new Session(UUID.randomUUID(), SessionPhase.ENTERING, inventory(), food(), ordinaryPose()) : expected.session();
            h.operation.frozen = image(null, boundary, session, owner, generation); h.complete();
            assertThrows(SurvivalRefusal.class, t::tick); assertEquals(State.FAILED, t.state()); assertEquals(0, h.earthApplications);
        }
    }
    @Test void logoutConnectionReplacementOrAuthorityReplacementCannotAdvancePendingWork() {
        for (int fault = 0; fault < 3; fault++) {
            var h = new Harness(); var t = start(h); t.enter(); t.tick(); h.complete();
            if (fault == 0) h.healthy = false; else if (fault == 1) h.currentIdentity = new Object(); else h.epoch = UUID.randomUUID();
            assertThrows(SurvivalRefusal.class, t::tick); assertEquals(0, h.earthApplications); assertTrue(h.held);
        }
    }
    @Test void initialImportedInventoryAndForeignEscrowDimensionAreRefused() {
        for (boolean imported : List.of(false, true)) {
            var h = new Harness(); var t = start(h);
            if (imported) h.seed = new Boundary(1000, inventory(), food(), safeEarthPose(), List.of(), List.of());
            else h.ordinary = new OrdinaryState(inventory(), food(), safeEarthPose());
            t.enter(); assertThrows(SurvivalRefusal.class, t::tick); assertEquals(0, h.requests.size()); assertTrue(h.held);
        }
    }
    @Test void nativeSettlingAndDrainingCannotBeSkippedByRepeatedTicksOrEarlySaveReadiness() {
        var h = new Harness(); var t = start(h); h.quiescent = false; t.enter(); t.tick();
        assertEquals(0, h.captures); h.quiescent = true; t.tick(); h.complete(); h.settleEarth = false; t.tick();
        for (int i = 0; i < 5; i++) t.tick(); assertEquals(State.SETTLING_EARTH, t.state()); assertEquals(1, h.earthApplications);
        h.settleEarth = true; t.tick(); h.complete(); t.tick();
        h.settleOrdinary = false; h.saveReady = true; t.leave(); t.tick(); h.complete(); t.tick();
        for (int i = 0; i < 5; i++) t.tick(); assertEquals(State.SETTLING_ORDINARY, t.state()); assertEquals(0, h.saves);
    }
    @Test void heldEarthDriftBeforeActiveReleaseFailsClosed() {
        var h = new Harness(); var t = start(h); t.enter(); t.tick(); h.complete(); t.tick(); t.tick();
        h.complete(); h.driftEarth = true; assertThrows(SurvivalRefusal.class, t::tick);
        assertTrue(h.held); assertFalse(t.allowsEarthActions()); assertEquals(0, h.releases);
    }
    @Test void evenCaughtCallbackReentrancyPoisonsTheOuterTransitionBeforeNextMutation() {
        for (int callback = 0; callback < 3; callback++) {
            var h = new Harness(); var t = start(h); t.enter(); t.tick(); h.complete();
            Runnable check = () -> {
                assertFalse(t.allowsEarthActions()); assertFalse(t.allowsOrdinaryControls());
                assertThrows(SurvivalRefusal.class, t::tick);
            };
            if (callback == 0) h.onCheckpointPoll = check;
            else if (callback == 1) h.onApply = check;
            else { t.tick(); t.tick(); h.complete(); h.onRelease = check; }
            assertThrows(SurvivalRefusal.class, t::tick);
            assertEquals(State.FAILED, t.state()); assertFalse(t.allowsEarthActions()); assertFalse(t.allowsOrdinaryControls());
            assertEquals(callback == 0 ? 0 : 1, h.earthApplications);
        }
    }
    @Test void ordinaryChangesInsideSuccessfulCheckpointPollCannotSlipPastPreflight() {
        for (boolean returning : List.of(false, true)) {
            var h = new Harness(); var t = start(h);
            if (returning) {
                activate(h, t); restoreOrdinary(h, t); h.saveReady = true; t.tick();
            } else { t.enter(); t.tick(); }
            h.complete(); h.onCheckpointPoll = () -> h.ordinary = new OrdinaryState(emptyEarth().inventory(), food(), ordinaryPose());
            assertThrows(SurvivalRefusal.class, t::tick); assertEquals(State.FAILED, t.state()); assertTrue(h.held);
            assertEquals(returning ? 1 : 0, h.earthApplications); assertEquals(returning ? 1 : 0, h.releases);
        }
    }
    @Test void ordinaryChangesInsideSavePollCannotPublishReturned() {
        var h = new Harness(); var t = start(h); activate(h, t); restoreOrdinary(h, t); h.saveReady = true;
        h.onSavePoll = () -> h.ordinary = new OrdinaryState(emptyEarth().inventory(), food(), ordinaryPose());
        assertThrows(SurvivalRefusal.class, t::tick); assertEquals(SessionPhase.RETURNING, h.durable.session().phase());
        assertEquals(3, h.requests.size()); assertTrue(h.held);
    }
    @Test void staleOrdinaryEscrowRefusesCheckpointRetryBeforeAnyResubmission() {
        for (boolean returning : List.of(false, true)) {
            var h = new Harness(); var t = start(h);
            if (returning) { activate(h, t); restoreOrdinary(h, t); h.saveReady = true; t.tick(); }
            else { t.enter(); t.tick(); }
            h.operation.failed = true; t.tick(); var operation = h.operation;
            h.ordinary = new OrdinaryState(emptyEarth().inventory(), food(), ordinaryPose());
            assertThrows(SurvivalRefusal.class, t::retryCheckpoint); assertEquals(0, operation.retries); assertTrue(h.held);
        }
    }
    @Test void foreignThreadCannotReadOrAdvanceController() throws Exception {
        var h = new Harness(); var t = start(h); var failure = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { t.enter(); } catch (Throwable cause) { failure.set(cause); }
        });
        thread.start(); thread.join(); assertInstanceOf(SurvivalRefusal.class, failure.get()); assertEquals(State.ORDINARY, t.state());
        assertFalse(h.held); assertEquals(0, h.captures);
    }
    @Test void unknownCheckpointSetupFailureIsHeldAndHasNoInventedRetryToken() {
        var h = new Harness(); var t = start(h); h.failBegin = true; t.enter(); assertThrows(SurvivalRefusal.class, t::tick);
        assertEquals(State.FAILED, t.state()); assertTrue(h.held); assertThrows(SurvivalRefusal.class, t::retryCheckpoint);
        assertEquals(0, h.earthApplications);
    }
    @Test void consecutiveActiveSaveEpochsStayReusableAndClearControllerHistory() throws Exception {
        var h=new Harness();var t=start(h);activate(h,t);long generation=h.durable.generation();
        for(int action=1;action<=4;action++){
            assertTrue(t.allowsEarthActions());
            var old=h.liveEarth;h.liveEarth=new Boundary(old.gameTime()+1,old.inventory(),old.food(),old.pose(),old.items(),old.scheduledTicks());
            h.activeReady=false;assertThrows(SurvivalRefusal.class,t::saveActive);
            assertEquals(State.ACTIVE,t.state());assertFalse(h.held);assertTrue(t.allowsEarthActions());
            h.activeReady=true;t.saveActive();t.tick();assertTrue(h.held);assertFalse(t.allowsEarthActions());
            var operation=h.operation;h.complete();t.tick();assertEquals(State.ACTIVE,t.state());assertFalse(h.held);
            assertSame(operation.frozen,h.durable);assertEquals(++generation,h.durable.generation());
            for(String field:List.of("checkpoint","request","entering","previous","activeSaveBase")){
                var f=SurvivalSessionTransitions.class.getDeclaredField(field);f.setAccessible(true);assertNull(f.get(t),field);
            }
        }
        assertEquals(1,h.earthApplications);assertEquals(0,h.ordinaryApplications);assertEquals(0,h.saves);
    }

    @Test void pendingUncertainWriterDrainDoesNotPoisonAnOtherwiseExactRetry(){
        var h=new Harness();var t=start(h);activate(h,t);t.saveActive();t.tick();
        h.operation.failed=true;h.operation.physicalOwnerDrained=false;t.tick();var frozen=h.operation.frozen;
        assertNotNull(t.checkpointFailure());assertThrows(SurvivalRefusal.class,t::retryCheckpoint);
        assertEquals(State.ACTIVE_SAVE_CHECKPOINT,t.state());assertTrue(h.held);assertNull(t.failure());
        h.operation.physicalOwnerDrained=true;t.retryCheckpoint();assertSame(frozen,h.operation.frozen);
        h.complete();t.tick();assertEquals(State.ACTIVE,t.state());assertFalse(h.held);
    }

}
