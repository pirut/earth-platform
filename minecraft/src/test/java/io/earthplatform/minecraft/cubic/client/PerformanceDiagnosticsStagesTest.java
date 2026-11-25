package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.Timings;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Build 79 crashed because a timer was added without a log name; names and timers now live in one list. */
class PerformanceDiagnosticsStagesTest {
    /** Timings deliberately not in the window log. */
    private static final Set<String> UNLOGGED = Set.of("FAR_WALK");

    @Test void everyStageHasAUniqueName() {
        Set<String> names = new HashSet<>();
        for (var stage : PerformanceDiagnostics.STAGES) {
            assertNotNull(stage.timer());
            assertFalse(stage.name() == null || stage.name().isBlank());
            assertTrue(names.add(stage.name()), "duplicate stage name " + stage.name());
        }
    }

    @Test void everyTimingIsLoggedUnlessListedAsUnlogged() throws Exception {
        Set<Timings> logged = new HashSet<>();
        for (var stage : PerformanceDiagnostics.STAGES) assertTrue(logged.add(stage.timer()), "timer listed twice: " + stage.name());
        for (var field : Timings.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != Timings.class) continue;
            Timings timing = (Timings) field.get(null);
            assertTrue(logged.contains(timing) || UNLOGGED.contains(field.getName()), field.getName() + " has no diagnostics stage");
        }
    }
}
