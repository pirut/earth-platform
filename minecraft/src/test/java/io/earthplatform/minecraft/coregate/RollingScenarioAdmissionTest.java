package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.coregate.RollingIndexedScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure admission and exact-boundary adversaries. No GUI or resource reload is simulated as proof. */
class RollingScenarioAdmissionTest {
    private static final String DIGEST = "a".repeat(64), OTHER_DIGEST = "b".repeat(64);
    private static final CubePos CENTER = new CubePos(0, 8, 0);
    // These durable identities are the preserved frozen014 failed AFTER_RELOAD observation.
    private static final String WORLD = "4bd0352d7ba333c499dbebdf31bb3c9e0fd08350a379fb52d78fe42f47ef0297";
    private static final String PROCESS = "338a30fb-d984-4665-a9cf-e1b62cf9e9e5";
    private static final String ROOT = "d69d2d7e56545953bb8c94c6a15f7e24d6cbce0e9398c6ec952f228b35dd74a5";
    private static final String QUERY = "a45d3b58304df235ed63c96e43f41d3f04a96146b439722f50493d8a6cdd9103";
    private static final String SET = "462e330a25ee8e707ea35bb5244281fb3a3c14cf70c0ad9fc5d52d2428c2b47e";

    private static RollingScenarioClientWitness witness(long epoch, long publication, long resources,
            long coverage, long reload, String digest) {
        return new RollingScenarioClientWitness(epoch, publication, resources, coverage, reload, digest);
    }
    private static RecoveryExpectation expected(boolean reloadVerified) {
        return new RecoveryExpectation(WORLD, PROCESS, 1, 1, 1, reloadVerified,
                2, 2, 2, 2, 6, ROOT, QUERY, SET);
    }
    private static final class BoundaryHost implements Driver {
        long epoch = 2, publication = 1, resources = 1;
        String digest = DIGEST, process = PROCESS, root = ROOT, query = QUERY, set = SET;
        long index = 6, revisionA = 2, revisionB = 2;
        int materialA = 2, materialB = 2;
        boolean collisionReady = true, pending, ready = true;
        RollingScenarioClientWitness client;
        RollingIndexedScenario runner;
        int directories, moves, issues, checkpoints, aborts;
        boolean current() {
            return collisionReady && !pending && RollingScenarioClientWitness.current(client, epoch,
                    publication, resources, digest);
        }
        boolean receive(RollingScenarioClientWitness receipt) {
            if (!RollingScenarioClientWitness.current(receipt, epoch, publication, resources, digest)) return false;
            client = receipt; return true;
        }
        boolean admit(Mode mode, RecoveryExpectation expectation) {
            if (runner != null || !current()) return false;
            directories++; moves++;
            runner = new RollingIndexedScenario(this, mode, CENTER, expectation); return true;
        }
        public Observation observe() {
            return new Observation(WORLD, process, client == null ? 0 : client.reloadSequence(), epoch,
                    resources, ready && current(), pending, true, true, publication,
                    client == null ? 0 : client.coverage(), index, root, query, set, Focus.ORIGINAL,
                    revisionA, revisionB, materialA, materialB, 27, 27, CENTER,
                    new Pose(15.5, 129, 12.5, 0, 31), 0, Outcome.NONE, List.of(), "");
        }
        public void issue(Action action) { issues++; }
        public void checkpoint(Receipt receipt, boolean screenshot) { assertTrue(screenshot); checkpoints++; }
        public void abort(String reason) { assertFalse(reason.isBlank()); aborts++; }
        void exactReceipt(long reload) { assertTrue(receive(witness(epoch, publication, resources, 1, reload, digest))); }
        void assertUntouched() {
            assertNull(runner); assertEquals(0, directories); assertEquals(0, moves);
            assertEquals(0, issues); assertEquals(0, checkpoints); assertEquals(0, aborts);
        }
    }

    @Test void missingReceiptCannotAdmitAnyModeOrCreateEvidenceMoveOrFail() {
        for (var mode : Mode.values()) {
            var host = new BoundaryHost();
            for (int attempt = 0; attempt < 4; attempt++) {
                assertFalse(host.admit(mode, mode == Mode.EXERCISE ? null : expected(true)));
                host.assertUntouched();
            }
        }
    }

    @Test void delayedExactReceiptAdmitsOnlyOnANewExplicitCommandAndUsesTheActualReloadSequence() {
        var host = new BoundaryHost(); assertFalse(host.admit(Mode.AFTER_RELOAD, expected(false)));
        host.assertUntouched(); host.exactReceipt(2); host.assertUntouched();
        assertTrue(host.admit(Mode.AFTER_RELOAD, expected(false)));
        host.runner.tick(0);
        assertEquals(Status.WAITING_EXTERNAL_RESTART, host.runner.status(), host.runner.report().failure());
        assertEquals(2, host.runner.report().expectation().priorReloadSequence());
        assertEquals(2, host.runner.report().expectation().priorEpoch());
        assertEquals(1, host.runner.report().expectation().priorResourceGeneration());
        assertEquals(1, host.directories); assertEquals(1, host.moves);
        assertEquals(0, host.issues); assertEquals(0, host.aborts); assertEquals(1, host.checkpoints);
        assertFalse(host.admit(Mode.AFTER_RELOAD, expected(false)), "No duplicate runner or automatic retry");
    }

    @Test void eachAuthorityMismatchIsRefusedWithoutReplacingAnAcceptedWitness() {
        var host = new BoundaryHost(); host.exactReceipt(2); var exact = host.client;
        var wrong = List.of(witness(1, 1, 1, 1, 2, DIGEST), witness(2, 2, 1, 1, 2, DIGEST),
                witness(2, 1, 2, 1, 2, DIGEST), witness(2, 1, 1, 1, 2, OTHER_DIGEST));
        for (var receipt : wrong) {
            assertFalse(host.receive(receipt)); assertSame(exact, host.client); assertTrue(host.current());
            assertFalse(RollingScenarioClientWitness.current(receipt, 2, 1, 1, DIGEST));
        }
    }

    @Test void delayedReceiptForThePriorPublicationCannotAdmitTheNewPairEvenWithTheSameDigest() {
        var host = new BoundaryHost(); var old = witness(2, 1, 1, 1, 2, DIGEST);
        host.publication = 2;
        assertFalse(host.receive(old)); assertFalse(host.admit(Mode.AFTER_RELOAD, expected(false)));
        host.assertUntouched(); host.exactReceipt(2); assertTrue(host.admit(Mode.AFTER_RELOAD, expected(false)));
    }

    @Test void receiptCurrentnessIsRecheckedAtAdmissionAndDuringActionReadiness() {
        for (int change = 0; change < 4; change++) {
            var host = new BoundaryHost(); host.exactReceipt(2);
            switch (change) {
                case 0 -> host.epoch++;
                case 1 -> host.publication++;
                case 2 -> host.resources++;
                case 3 -> host.digest = OTHER_DIGEST;
                default -> throw new AssertionError();
            }
            assertFalse(host.current()); assertFalse(host.admit(Mode.AFTER_RELOAD, expected(false)));
            host.assertUntouched();
        }
    }

    @Test void repeatedPairDigestCannotResurrectAWitnessAcrossPublicationOrSessionAba() {
        var host = new BoundaryHost(); host.exactReceipt(2); var old = host.client;
        host.publication = 2; host.digest = OTHER_DIGEST; host.exactReceipt(2);
        host.publication = 3; host.digest = DIGEST;
        assertFalse(host.current()); assertFalse(host.receive(old));
        assertFalse(host.admit(Mode.AFTER_RELOAD, expected(false))); host.assertUntouched();
        host.epoch = 3; host.publication = 1; host.resources = 1;
        assertFalse(host.receive(old), "Repeated publication/resource/digest still needs the new exact epoch");
        assertFalse(host.admit(Mode.AFTER_RELOAD, expected(false))); host.assertUntouched();
    }

    @Test void realHandshakePublicationNumbersPreventDigestAbaFromReopeningTheOldWitness() {
        var handshake = new RollingPublicationHandshake(2);
        commit(handshake, DIGEST); var old = witness(2, 1, 1, 1, 2, DIGEST);
        assertTrue(RollingScenarioClientWitness.current(old, 2, handshake.committedPublication(),
                handshake.resourceGeneration(), handshake.committedDigest()));
        commit(handshake, OTHER_DIGEST); commit(handshake, DIGEST);
        assertEquals(3, handshake.committedPublication());
        assertFalse(RollingScenarioClientWitness.current(old, 2, handshake.committedPublication(),
                handshake.resourceGeneration(), handshake.committedDigest()));
    }
    private static void commit(RollingPublicationHandshake handshake, String digest) {
        long publication = handshake.begin(); handshake.ready(digest);
        assertTrue(handshake.prepared(2, publication, 1, digest)); handshake.grant();
        assertTrue(handshake.pairReady(2, publication, 1, digest)); handshake.committed();
    }

    @Test void anExactWitnessDoesNotOverrideCollisionOrPendingActionReadiness() {
        for (boolean pending : List.of(false, true)) {
            var host = new BoundaryHost(); host.exactReceipt(2);
            if (pending) host.pending = true; else host.collisionReady = false;
            assertFalse(host.admit(Mode.AFTER_RELOAD, expected(false))); host.assertUntouched();
        }
    }

    @Test void knownZeroIsDistinctFromMissingAndIsValidForAnActualNewJvm() {
        var host = new BoundaryHost(); assertFalse(host.current());
        host.process = "different-jvm"; host.epoch = 1; host.exactReceipt(0);
        assertTrue(host.current()); assertEquals(0, host.client.reloadSequence());
        assertTrue(host.admit(Mode.AFTER_RESTART, expected(true))); host.runner.tick(0);
        assertEquals(Status.PASS, host.runner.status()); assertEquals(0, host.issues); assertEquals(0, host.aborts);
    }

    @Test void witnessCannotInventPositiveCoverageOrUnknownReloadValues() {
        for (var values : List.of(new long[]{0, 1, 1, 1, 0}, new long[]{1, 0, 1, 1, 0},
                new long[]{1, 1, 0, 1, 0}, new long[]{1, 1, 1, 0, 0}, new long[]{1, 1, 1, 1, -1})) {
            assertThrows(IllegalArgumentException.class, () -> witness(values[0], values[1], values[2],
                    values[3], values[4], DIGEST));
        }
        assertThrows(IllegalArgumentException.class, () -> witness(1, 1, 1, 1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> witness(1, 1, 1, 1, 0, "not-a-digest"));
        assertTrue(RollingScenarioClientWitness.class.isRecord());
    }

    @Test void exactWitnessStillCannotTurnANewSessionIntoAnActualReload() {
        var host = new BoundaryHost(); host.exactReceipt(1);
        assertTrue(host.admit(Mode.AFTER_RELOAD, expected(false))); host.runner.tick(0);
        assertEquals(Status.FAIL, host.runner.status()); assertEquals(1, host.aborts); assertEquals(0, host.issues);
        assertTrue(host.runner.report().failure().contains("Actual resource reload"));
    }

    @Test void exactReceiptCannotAuthorizeWrongReloadProcessOrUnchangedBoundary() {
        for (boolean wrongProcess : List.of(false, true)) {
            var host = new BoundaryHost(); if (wrongProcess) host.process = "foreign-jvm"; else host.epoch = 1;
            host.exactReceipt(2); assertTrue(host.admit(Mode.AFTER_RELOAD, expected(false))); host.runner.tick(0);
            assertEquals(Status.FAIL, host.runner.status()); assertEquals(1, host.aborts); assertEquals(0, host.issues);
        }
    }

    @Test void exactWitnessCannotRepairAnyChangedDurableRecoveryIdentity() {
        for (int change = 0; change < 8; change++) {
            var host = new BoundaryHost(); host.exactReceipt(2);
            switch (change) {
                case 0 -> host.index++;
                case 1 -> host.root = "foreign-root";
                case 2 -> host.query = "foreign-query";
                case 3 -> host.set = "foreign-set";
                case 4 -> host.revisionA++;
                case 5 -> host.revisionB++;
                case 6 -> host.materialA = 0;
                case 7 -> host.materialB = 0;
                default -> throw new AssertionError();
            }
            assertTrue(host.admit(Mode.AFTER_RELOAD, expected(false))); host.runner.tick(0);
            assertEquals(Status.FAIL, host.runner.status(), "Durable field " + change);
            assertTrue(host.runner.report().failure().contains("Reconstruction differs"));
            assertEquals(1, host.aborts); assertEquals(0, host.issues);
        }
    }

    @Test void exactWitnessDoesNotWeakenRestartPriorPhaseOrNewJvmRequirements() {
        for (boolean sameProcess : List.of(false, true)) {
            var host = new BoundaryHost(); if (!sameProcess) host.process = "different-jvm";
            host.exactReceipt(0); assertTrue(host.admit(Mode.AFTER_RESTART, expected(sameProcess)));
            host.runner.tick(0); assertEquals(Status.FAIL, host.runner.status()); assertEquals(1, host.aborts);
        }
    }

    @Test void bypassingAdmissionStillReproducesTheFrozenFailureAndNeverAutoRepairsIt() {
        var host = new BoundaryHost();
        // Deliberately bypass the fixed host gate: preserve the runner's strict first-observation latch.
        var runner = new RollingIndexedScenario(host, Mode.AFTER_RELOAD, CENTER, expected(false));
        runner.tick(0); assertEquals(Status.RUNNING, runner.status());
        host.exactReceipt(2); runner.tick(1);
        assertEquals(Status.FAIL, runner.status()); assertEquals(1, host.aborts); assertEquals(0, host.issues);
        assertTrue(runner.report().failure().contains("World/session/epoch/resource changed"));
        runner.tick(2); assertEquals(Status.FAIL, runner.status()); assertEquals(1, host.aborts);
        assertEquals(2, runner.report().transcript().getLast().after().reloadSequence());
    }

    @Test void screenshotAcknowledgementStillRequiresItsSeparateExactImmutableStamp() {
        var host = new BoundaryHost(); host.exactReceipt(2);
        var stamp = new RollingScenarioCheckpoint(2, 1, 1, 1, DIGEST, "scenario12-0-reconstructed");
        assertTrue(host.current());
        assertTrue(stamp.matches(2, 1, 1, 1, DIGEST, "scenario12-0-reconstructed"));
        assertFalse(stamp.matches(2, 1, 1, 2, DIGEST, "scenario12-0-reconstructed"));
        assertFalse(stamp.matches(2, 1, 1, 1, DIGEST, "scenario12-1-reconstructed"));
        assertFalse(stamp.matches(3, 1, 1, 1, DIGEST, "scenario12-0-reconstructed"));
    }
}
