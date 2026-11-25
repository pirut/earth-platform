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
        // A cube that arrives before the first frame goes in with everything else on that frame.
        sync.arrived(manager, List.of("shaft"), queued::add);
        assertEquals(List.of(), queued);
        sync.frame(manager, () -> LOADED, queued::add);
        assertEquals(LOADED, queued);
    }

    @Test void arrivalsGoStraightToTheCurrentManager() {
        FlywheelCompat.Sync sync = new FlywheelCompat.Sync();
        List<String> queued = new ArrayList<>();
        Object manager = new Object();
        sync.frame(manager, () -> List.<String>of(), queued::add);
        sync.arrived(manager, List.of("press", "mixer"), queued::add);
        assertEquals(List.of("press", "mixer"), queued);
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
        sync.arrived(manager, List.of("press"), queued::add);
        queued.clear();
        sync.frame(manager, counting(walks), queued::add);
        assertEquals(LOADED, queued);
        assertEquals(2, walks.get());
    }

    @Test void withoutFlywheelNothingIsReplaced() {
        // Flywheel isn't on the test classpath: the class still loads and leaves every renderer alone.
        assertFalse(FlywheelCompat.replacesRenderer(null));
    }
}
