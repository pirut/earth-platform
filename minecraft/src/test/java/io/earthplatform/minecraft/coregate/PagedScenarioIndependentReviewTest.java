package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.coregate.RollingIndexedScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure failure/deadline/boundary adversaries; these are not GUI acceptance evidence. */
class PagedScenarioIndependentReviewTest {
    private static final CubePos A = new CubePos(0, 8, 0);
    private static final class Probe implements Driver {
        String process = "process-one"; long epoch = 1, resources = 1, reload;
        boolean ready, queryComplete = true, current = true;
        int issues, aborts, checkpoints; Runnable onCheckpoint = () -> {};
        public Observation observe() {
            return new Observation("world", process, reload, epoch, resources, ready, false, queryComplete, current,
                    1, 1, 2, "root", "query", "set", Focus.ORIGINAL, 0, 0, 1, 1, 27, 27, A,
                    new Pose(15.5, 129, 12.5, 0, 31), 0, Outcome.NONE, List.of(), "");
        }
        public void issue(Action action) { issues++; }
        public void checkpoint(Receipt receipt, boolean screenshot) {
            assertEquals("SCRIPTED_HARNESS", receipt.inputMode()); assertTrue(screenshot); checkpoints++; onCheckpoint.run();
        }
        public void abort(String reason) { assertFalse(reason.isBlank()); aborts++; }
    }
    private static RecoveryExpectation expected(boolean reloadVerified) {
        return new RecoveryExpectation("world", "process-one", 1, 1, 0, reloadVerified, 0, 0, 1, 1, 2, "root", "query", "set");
    }

    @Test void noActionCanStartFromAnIncompleteQueryEvenIfDriverClaimsExactReady() {
        var driver = new Probe(); driver.ready = true; driver.queryComplete = false;
        var runner = new RollingIndexedScenario(driver, Mode.EXERCISE, A, null);
        runner.tick(0); assertEquals(0, driver.issues); assertEquals(Status.RUNNING, runner.status());
        runner.tick(STEP_NANOS + 1); assertEquals(Status.FAIL, runner.status()); assertEquals(1, driver.aborts);
        runner.tick(STEP_NANOS * 2); assertEquals(1, driver.aborts); assertEquals(0, driver.issues);
    }

    @Test void scopeChangesWhileWaitingForInitialReadinessAbortWithoutAnyAction() {
        for (int change = 0; change < 4; change++) {
            var driver = new Probe(); var runner = new RollingIndexedScenario(driver, Mode.EXERCISE, A, null); runner.tick(0);
            if (change == 0) driver.process = "process-two";
            if (change == 1) driver.reload++;
            if (change == 2) driver.epoch++;
            if (change == 3) driver.resources++;
            runner.tick(1); assertEquals(Status.FAIL, runner.status()); assertEquals(0, driver.issues); assertEquals(1, driver.aborts);
        }
    }

    @Test void failedLastRecoveryCheckpointCannotBeOverwrittenByWaitingOrPass() {
        for (var mode : List.of(Mode.AFTER_RELOAD, Mode.AFTER_RESTART)) {
            var driver = new Probe(); driver.ready = true;
            if (mode == Mode.AFTER_RELOAD) { driver.reload = 1; driver.epoch = 2; }
            else driver.process = "process-two";
            var runner = new RollingIndexedScenario(driver, mode, A, expected(true));
            driver.onCheckpoint = () -> runner.cancel("Mandatory checkpoint failed");
            runner.tick(0);
            assertEquals(Status.FAIL, runner.status()); assertEquals(1, driver.aborts); assertEquals(1, driver.checkpoints);
            assertTrue(runner.report().transcript().getLast().label().startsWith("FAIL:")); assertEquals(0, driver.issues);
        }
    }

    @Test void initialCheckpointReentryCannotIssueTheFirstScriptedActionAfterFailure() {
        var driver = new Probe(); driver.ready = true;
        var runner = new RollingIndexedScenario(driver, Mode.EXERCISE, A, null);
        driver.onCheckpoint = () -> runner.tick(0); runner.tick(0);
        assertEquals(Status.FAIL, runner.status()); assertEquals(0, driver.issues); assertEquals(1, driver.aborts);
    }

    @Test void aNewJvmWithoutVerifiedReloadAndASameJvmRestartBothFail() {
        for (boolean sameProcess : List.of(false, true)) {
            var driver = new Probe(); driver.ready = true; if (!sameProcess) driver.process = "process-two";
            var runner = new RollingIndexedScenario(driver, Mode.AFTER_RESTART, A, expected(sameProcess)); runner.tick(0);
            assertEquals(Status.FAIL, runner.status()); assertEquals(0, driver.issues); assertEquals(1, driver.aborts);
        }
    }
}
