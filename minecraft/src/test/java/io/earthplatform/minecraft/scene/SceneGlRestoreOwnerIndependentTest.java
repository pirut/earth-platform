package io.earthplatform.minecraft.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the production native-upload restoration owner without a GL context. */
class SceneGlRestoreOwnerIndependentTest {
    private static void throwExact(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
    }

    @Test void everyFailureCombinationAttemptsAllThreePhasesAndRetainsTheExactPrimaryForRetry() {
        for (boolean useErrors : List.of(false, true)) for (int mask = 1; mask < 8; mask++) {
            var order = new ArrayList<String>();
            Throwable[] failures = new Throwable[3];
            for (int i = 0; i < 3; i++) if ((mask & (1 << i)) != 0)
                failures[i] = useErrors ? new AssertionError("phase " + i) : new IllegalStateException("phase " + i);
            var owner = new SceneGlRestoreOwner(
                    () -> { order.add("restore"); throwExact(failures[1]); },
                    () -> { order.add("before"); throwExact(failures[0]); },
                    () -> { order.add("after"); throwExact(failures[2]); });
            Throwable first = java.util.Arrays.stream(failures).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            var thrown = assertThrows(Throwable.class, owner::restore);
            assertSame(first, thrown, "mask=" + mask + ", Error=" + useErrors);
            assertEquals(List.of("before", "restore", "after"), order);
            assertArrayEquals(java.util.Arrays.stream(failures).filter(java.util.Objects::nonNull).skip(1).toArray(Throwable[]::new), first.getSuppressed());
            assertTrue(owner.pending());
            java.util.Arrays.fill(failures, null); order.clear(); owner.restore();
            assertFalse(owner.pending()); assertEquals(List.of("before", "restore", "after"), order);
            owner.restore(); assertEquals(3, order.size(), "Successful restoration is idempotent");
        }
    }

    @Test void repeatedFailureCannotLosePendingOwnershipOrChangeTheOriginalCallbacks() {
        var counts = new int[3]; var fault = new AtomicReference<Throwable>(new IllegalStateException("entry"));
        var owner = new SceneGlRestoreOwner(() -> counts[1]++, () -> { counts[0]++; throwExact(fault.get()); }, () -> counts[2]++);
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertSame(fault.get(), assertThrows(IllegalStateException.class, owner::restore));
            assertTrue(owner.pending()); assertArrayEquals(new int[]{attempt, attempt, attempt}, counts);
        }
        fault.set(null); owner.restore(); assertArrayEquals(new int[]{5, 5, 5}, counts); assertFalse(owner.pending());
    }

    @Test void identicalFaultAcrossAllPhasesNeverBecomesIllegalSelfSuppression() {
        var same = new AssertionError("same driver failure"); var calls = new int[1];
        Runnable fail = () -> { calls[0]++; throw same; };
        var owner = new SceneGlRestoreOwner(fail, fail, fail);
        assertSame(same, assertThrows(AssertionError.class, owner::restore));
        assertEquals(3, calls[0]); assertEquals(0, same.getSuppressed().length); assertTrue(owner.pending());
    }

    @Test void wrongThreadDoesNotRunAnyCheckpointOrReleasePendingState() throws Exception {
        var calls = new int[1]; Runnable call = () -> calls[0]++;
        var owner = new SceneGlRestoreOwner(call, call, call); var fault = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> { try { owner.restore(); } catch (Throwable error) { fault.set(error); } });
        worker.start(); worker.join();
        assertInstanceOf(IllegalStateException.class, fault.get()); assertEquals(0, calls[0]); assertTrue(owner.pending());
        owner.restore(); assertEquals(3, calls[0]); assertFalse(owner.pending());
    }

    @Test void constructorValidatesEveryCallbackWithoutExecutingAnyOfThem() {
        var calls = new int[1]; Runnable callback = () -> calls[0]++;
        assertThrows(NullPointerException.class, () -> new SceneGlRestoreOwner(null, callback, callback));
        assertThrows(NullPointerException.class, () -> new SceneGlRestoreOwner(callback, null, callback));
        assertThrows(NullPointerException.class, () -> new SceneGlRestoreOwner(callback, callback, null));
        assertEquals(0, calls[0]);
    }

    @Test void reentrantRestorationIsFencedAndDoesNotPreventALaterOwnerThreadRetry() {
        var reference = new AtomicReference<SceneGlRestoreOwner>(); var reenter = new boolean[]{true}; var order = new ArrayList<String>();
        var owner = new SceneGlRestoreOwner(() -> order.add("restore"), () -> {
            order.add("before"); if (reenter[0]) reference.get().restore();
        }, () -> order.add("after")); reference.set(owner);
        assertThrows(IllegalStateException.class, owner::restore); assertTrue(owner.pending());
        assertEquals(List.of("before", "restore", "after"), order);
        reenter[0] = false; order.clear(); owner.restore();
        assertEquals(List.of("before", "restore", "after"), order); assertFalse(owner.pending());
    }
}
