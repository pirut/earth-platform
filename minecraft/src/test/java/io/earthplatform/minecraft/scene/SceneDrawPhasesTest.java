package io.earthplatform.minecraft.scene;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class SceneDrawPhasesTest {
    private enum Stage { CAPTURE, SETUP, SUBMIT, CLEAR, RESTORE }
    private enum Stale { NONE, BEFORE_CAPTURE, BEFORE_PASS, BEFORE_SUBMIT }
    private record Token(Stage stage, String detail) {}
    private static final List<String> COMPLETE = List.of("capture", "global", "setup", "submit",
            "shader", "uniform", "sampler", "outer");

    private static final class Timing implements SceneDrawPhases.Timing<Stage, Token> {
        final Thread owner = Thread.currentThread();
        final ArrayDeque<Token> live = new ArrayDeque<>();
        final List<Token> started = new ArrayList<>(), ended = new ArrayList<>();
        Stage failBegin, failEnd;
        final RuntimeException beginFailure = new IllegalStateException("begin");
        final Error endFailure = new AssertionError("end");
        @Override public Token begin(Stage stage, String detail) {
            assertSame(owner, Thread.currentThread());
            if (stage == failBegin) throw beginFailure;
            var token = new Token(stage, detail);
            started.add(token);
            live.push(token);
            return token;
        }
        @Override public void end(Token token) {
            assertSame(owner, Thread.currentThread());
            assertSame(token, live.pop());
            ended.add(token);
            if (token.stage() == failEnd) throw endFailure;
        }
    }

    /** Same nesting as the native owner path, without a graphics context or native libraries. */
    private static final class Draw {
        final SceneDrawPhases<Stage, Token> phases;
        final List<String> actions = new ArrayList<>();
        final Map<String, Throwable> failures = new HashMap<>();
        final Object snapshot = new Object();
        Object restoredSnapshot;
        boolean insideGuard;
        Draw(Timing timing) { phases = new SceneDrawPhases<>(timing); }
        void action(String name) {
            assertTrue(insideGuard, "all work, timing cleanup, and restoration remain owner-guarded");
            actions.add(name);
            SceneDrawPhases.rethrow(failures.get(name));
        }
        boolean draw(Stale stale) {
            insideGuard = true;
            try {
                if (stale == Stale.BEFORE_CAPTURE) return false;
                return phases.run(() -> {
                    Object previous = phases.capture(Stage.CAPTURE, "mesh", () -> {
                        action("capture");
                        return snapshot;
                    });
                    return SceneDrawPhases.restoring(() -> {
                        phases.phase(Stage.SETUP, "mesh", () -> action("global"));
                        if (stale == Stale.BEFORE_PASS) return false;
                        return SceneDrawPhases.restoring(() -> {
                            phases.phase(Stage.SETUP, "pass", () -> action("setup"));
                            if (stale == Stale.BEFORE_SUBMIT) return false;
                            phases.phase(Stage.SUBMIT, "pass", () -> action("submit"));
                            return true;
                        }, () -> phases.phase(Stage.CLEAR, "pass", () -> SceneDrawPhases.cleanupAll(
                                () -> action("shader"), () -> action("uniform"), () -> action("sampler"))));
                    }, () -> phases.phase(Stage.RESTORE, "mesh", () -> {
                        restoredSnapshot = previous;
                        action("outer");
                    }));
                });
            } finally { insideGuard = false; }
        }
    }

    @Test void disabledTimingRunsExactlyTheOwnerActions() {
        var draw = new Draw(null);
        assertTrue(draw.draw(Stale.NONE));
        assertEquals(COMPLETE, draw.actions);
        assertSame(draw.snapshot, draw.restoredSnapshot);
        assertFalse(draw.insideGuard);
    }

    @Test void successfulPhasesKeepTheLabelsAndPairEveryToken() {
        var timing = new Timing();
        var draw = new Draw(timing);
        assertTrue(draw.draw(Stale.NONE));
        assertEquals(COMPLETE, draw.actions);
        assertEquals(List.of(new Token(Stage.CAPTURE, "mesh"), new Token(Stage.SETUP, "mesh"),
                new Token(Stage.SETUP, "pass"), new Token(Stage.SUBMIT, "pass"),
                new Token(Stage.CLEAR, "pass"), new Token(Stage.RESTORE, "mesh")), timing.started);
        assertEquals(timing.started, timing.ended);
        assertTrue(timing.live.isEmpty());
    }

    @ParameterizedTest @EnumSource(Stage.class)
    void beginFailureCannotSkipOwnerWorkOrAnyRestoration(Stage stage) {
        var timing = new Timing();
        timing.failBegin = stage;
        var draw = new Draw(timing);
        assertSame(timing.beginFailure, assertThrows(RuntimeException.class, () -> draw.draw(Stale.NONE)));
        assertEquals(COMPLETE, draw.actions);
        assertSame(draw.snapshot, draw.restoredSnapshot);
        assertEquals(timing.started, timing.ended);
        assertTrue(timing.live.isEmpty());
    }

    @ParameterizedTest @EnumSource(Stage.class)
    void endFailureCannotDiscardSnapshotOrSkipRestoration(Stage stage) {
        var timing = new Timing();
        timing.failEnd = stage;
        var draw = new Draw(timing);
        assertSame(timing.endFailure, assertThrows(Error.class, () -> draw.draw(Stale.NONE)));
        assertEquals(COMPLETE, draw.actions);
        assertSame(draw.snapshot, draw.restoredSnapshot);
        assertEquals(timing.started, timing.ended);
        assertTrue(timing.live.isEmpty());
    }

    @Test void originalWorkFailureWinsOverAllCleanupAndTimingFailures() {
        var timing = new Timing();
        timing.failBegin = Stage.CAPTURE;
        timing.failEnd = Stage.RESTORE;
        var draw = new Draw(timing);
        var work = new IllegalStateException("native submit");
        var shader = new AssertionError("shader clear");
        var uniform = new IllegalArgumentException("uniform restore");
        var sampler = new AssertionError("sampler restore");
        var outer = new IllegalStateException("outer restore");
        draw.failures.put("submit", work);
        draw.failures.put("shader", shader);
        draw.failures.put("uniform", uniform);
        draw.failures.put("sampler", sampler);
        draw.failures.put("outer", outer);
        assertSame(work, assertThrows(IllegalStateException.class, () -> draw.draw(Stale.NONE)));
        assertEquals(COMPLETE, draw.actions);
        assertArrayEquals(new Throwable[] { shader, outer, timing.beginFailure }, work.getSuppressed());
        assertArrayEquals(new Throwable[] { uniform, sampler }, shader.getSuppressed());
        assertArrayEquals(new Throwable[] { timing.endFailure }, timing.beginFailure.getSuppressed());
        assertTrue(timing.live.isEmpty());
    }

    @Test void cleanupFailureStillAttemptsTheRemainingCleanupAndOuterRestore() {
        var draw = new Draw(new Timing());
        var clear = new AssertionError("clear");
        draw.failures.put("shader", clear);
        assertSame(clear, assertThrows(Error.class, () -> draw.draw(Stale.NONE)));
        assertEquals(COMPLETE, draw.actions);
        assertSame(draw.snapshot, draw.restoredSnapshot);
    }

    @Test void globalSetupFailureRestoresTheCapturedOuterOwner() {
        var draw = new Draw(new Timing());
        var setup = new IllegalStateException("global");
        draw.failures.put("global", setup);
        assertSame(setup, assertThrows(RuntimeException.class, () -> draw.draw(Stale.NONE)));
        assertEquals(List.of("capture", "global", "outer"), draw.actions);
        assertSame(draw.snapshot, draw.restoredSnapshot);
    }

    @Test void perPassSetupFailureClearsPassAndRestoresOuterOwner() {
        var draw = new Draw(new Timing());
        var setup = new AssertionError("bind");
        draw.failures.put("setup", setup);
        assertSame(setup, assertThrows(Error.class, () -> draw.draw(Stale.NONE)));
        assertEquals(List.of("capture", "global", "setup", "shader", "uniform", "sampler", "outer"), draw.actions);
    }

    @Test void captureFailureHasNoUnacquiredRestorationOwnerAndPreservesItsError() {
        var timing = new Timing();
        timing.failEnd = Stage.CAPTURE;
        var draw = new Draw(timing);
        var capture = new IllegalStateException("snapshot");
        draw.failures.put("capture", capture);
        assertSame(capture, assertThrows(RuntimeException.class, () -> draw.draw(Stale.NONE)));
        assertEquals(List.of("capture"), draw.actions);
        assertNull(draw.restoredSnapshot);
        assertArrayEquals(new Throwable[] { timing.endFailure }, capture.getSuppressed());
    }

    @ParameterizedTest @EnumSource(value = Stale.class, names = {"BEFORE_CAPTURE", "BEFORE_PASS", "BEFORE_SUBMIT"})
    void staleEarlyReturnsRestoreExactlyTheOwnersAlreadyCaptured(Stale stale) {
        var timing = new Timing();
        var draw = new Draw(timing);
        assertFalse(draw.draw(stale));
        var expected = switch (stale) {
            case BEFORE_CAPTURE -> List.<String>of();
            case BEFORE_PASS -> List.of("capture", "global", "outer");
            case BEFORE_SUBMIT -> List.of("capture", "global", "setup", "shader", "uniform", "sampler", "outer");
            default -> throw new AssertionError();
        };
        assertEquals(expected, draw.actions);
        assertEquals(timing.started, timing.ended);
        assertTrue(timing.live.isEmpty());
        assertFalse(draw.insideGuard);
    }

    @Test void staleReturnWithTimingFailureStillRunsEveryRequiredCleanup() {
        var timing = new Timing();
        timing.failBegin = Stage.CLEAR;
        var draw = new Draw(timing);
        assertSame(timing.beginFailure, assertThrows(RuntimeException.class, () -> draw.draw(Stale.BEFORE_SUBMIT)));
        assertEquals(List.of("capture", "global", "setup", "shader", "uniform", "sampler", "outer"), draw.actions);
        assertSame(draw.snapshot, draw.restoredSnapshot);
    }

    @Test void reusedExceptionCannotSuppressItself() {
        var error = new AssertionError("shared");
        assertSame(error, assertThrows(Error.class, () -> SceneDrawPhases.restoring(
                () -> { throw error; }, () -> { throw error; })));
        assertEquals(0, error.getSuppressed().length);
    }

    @Test void measuredConvenienceKeepsWorkErrorPrimaryWhenTimingEndThrows() {
        var timing = new Timing();
        timing.failEnd = Stage.SETUP;
        var work = new IllegalStateException("owner initialization");
        assertSame(work, assertThrows(RuntimeException.class, () -> SceneDrawPhases.measured(
                timing, Stage.SETUP, "initialization", () -> { throw work; })));
        assertArrayEquals(new Throwable[] { timing.endFailure }, work.getSuppressed());
        assertEquals(timing.started, timing.ended);
        assertTrue(timing.live.isEmpty());
    }

    @Test void measuredConvenienceRunsWorkWhenTimingBeginFails() {
        var timing = new Timing();
        timing.failBegin = Stage.SETUP;
        var actions = new ArrayList<String>();
        assertSame(timing.beginFailure, assertThrows(RuntimeException.class, () -> SceneDrawPhases.measured(
                timing, Stage.SETUP, "initialization", () -> { actions.add("work"); return 7; })));
        assertEquals(List.of("work"), actions);
        assertTrue(timing.started.isEmpty());
        assertTrue(timing.ended.isEmpty());
    }

    @Test void measuredConvenienceReturnsWorkResultWithOptionalTiming() {
        var expected = new Object();
        assertSame(expected, SceneDrawPhases.measured(new Timing(), Stage.SETUP, "initialization", () -> expected));
        assertSame(expected, SceneDrawPhases.measured(null, Stage.SETUP, "initialization", () -> expected));
    }
}
