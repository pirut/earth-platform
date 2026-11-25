package io.earthplatform.minecraft.cubic.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlywheelCompatTest {
    private static final List<String> LOADED = List.of("shaft", "cogwheel", "chest");

    private static Supplier<Iterable<String>> counting(AtomicInteger walks) {
        return () -> { walks.incrementAndGet(); return LOADED; };
    }

    @Test void firstFrameQueuesEverythingAlreadyLoaded() {
        FlywheelCompat.Sync sync = new FlywheelCompat.Sync();
        List<String> queued = new ArrayList<>();
        Object manager = new Object();
        // A block entity stored before the first frame goes in with everything else on that frame.
        sync.stored(manager, "shaft", queued::add);
        assertEquals(List.of(), queued);
        sync.frame(manager, () -> LOADED, queued::add);
        assertEquals(LOADED, queued);
    }

    @Test void storedBlockEntitiesGoStraightToTheCurrentManager() {
        // Includes one replacing another in the same block with the same state, which nothing else reports.
        FlywheelCompat.Sync sync = new FlywheelCompat.Sync();
        List<String> queued = new ArrayList<>();
        Object manager = new Object();
        sync.frame(manager, () -> List.<String>of(), queued::add);
        sync.stored(manager, "press", queued::add);
        sync.stored(manager, "press replaced", queued::add);
        assertEquals(List.of("press", "press replaced"), queued);
    }

    @Test void sameManagerNeverWalksTheCubesAgain() {
        FlywheelCompat.Sync sync = new FlywheelCompat.Sync();
        AtomicInteger walks = new AtomicInteger();
        List<String> queued = new ArrayList<>();
        Object manager = new Object();
        for (int frame = 0; frame < 100; frame++) sync.frame(manager, counting(walks), queued::add);
        assertEquals(1, walks.get());
        assertEquals(LOADED, queued);
    }

    @Test void flywheelResetQueuesEverythingAgain() {
        // F3+A, /flywheel backend and resource reloads replace Flywheel's manager with an empty one.
        FlywheelCompat.Sync sync = new FlywheelCompat.Sync();
        List<String> queued = new ArrayList<>();
        sync.frame(new Object(), () -> LOADED, queued::add);
        queued.clear();
        Object fresh = new Object();
        sync.frame(fresh, () -> LOADED, queued::add);
        assertEquals(LOADED, queued);
        assertSame(fresh, sync.current());
    }

    @Test void backendOffThenOnQueuesEverythingWhenItComesBack() {
        FlywheelCompat.Sync sync = new FlywheelCompat.Sync();
        AtomicInteger walks = new AtomicInteger();
        List<String> queued = new ArrayList<>();
        Object manager = new Object();
        sync.frame(manager, counting(walks), queued::add);
        sync.frame(null, counting(walks), null);
        assertNull(sync.current());
        // While Flywheel is off, arrivals wait for the frame that finds it on again.
        sync.stored(manager, "press", queued::add);
        queued.clear();
        sync.frame(manager, counting(walks), queued::add);
        assertEquals(LOADED, queued);
        assertEquals(2, walks.get());
    }

    @Test void closingTheLevelForgetsTheManager() {
        FlywheelCompat.Sync sync = new FlywheelCompat.Sync();
        List<String> queued = new ArrayList<>();
        Object manager = new Object();
        sync.frame(manager, () -> LOADED, queued::add);
        sync.clear();
        assertNull(sync.current());
        queued.clear();
        sync.frame(manager, () -> LOADED, queued::add);
        assertEquals(LOADED, queued);
    }

    @Test void theManagerIsHeldWeakly() {
        // Flywheel's manager holds the ClientLevel; once Flywheel lets it go, so does this.
        FlywheelCompat.Sync sync = new FlywheelCompat.Sync();
        sync.frame(new Object(), () -> LOADED, ignored -> {});
        for (int i = 0; i < 50 && sync.current() != null; i++) {
            System.gc();
            byte[][] pressure = new byte[64][];
            for (int j = 0; j < pressure.length; j++) pressure[j] = new byte[1 << 16];
        }
        assertNull(sync.current());
    }

    @Test void lightChangesWaitForTheFlushAndGoOnceEach() {
        // Queued while the terrain draws; sent at the start of the next level pass, before Flywheel's frame plan.
        FlywheelCompat.LightQueue queue = new FlywheelCompat.LightQueue();
        queue.add(7L);
        queue.add(7L);
        queue.add(-3L);
        assertEquals(2, queue.size());
        List<Long> sent = new ArrayList<>();
        queue.flush(sent::add);
        assertEquals(2, sent.size());
        assertTrue(sent.containsAll(List.of(7L, -3L)));
        assertTrue(queue.isEmpty());
        queue.flush(key -> fail("nothing queued"));
    }

    @Test void aFailedSendStillEmptiesTheQueue() {
        FlywheelCompat.LightQueue queue = new FlywheelCompat.LightQueue();
        queue.add(1L);
        queue.add(2L);
        assertThrows(IllegalStateException.class, () -> queue.flush(key -> { throw new IllegalStateException(); }));
        assertTrue(queue.isEmpty());
    }

    @Test void withoutFlywheelNothingIsReplaced() {
        // Flywheel isn't on the test classpath: the class still loads and leaves every renderer alone.
        assertFalse(FlywheelCompat.replacesRenderer(null));
        FlywheelCompat.lightChanged(0L);
        FlywheelCompat.flushLight(null);
        FlywheelCompat.levelClosed();
    }
}
