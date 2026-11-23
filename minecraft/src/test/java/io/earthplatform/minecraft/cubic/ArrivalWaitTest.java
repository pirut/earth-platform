package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** When a waiting arrival goes: its deadline across a personal spawn's retries, never without its cubes. */
class ArrivalWaitTest {
    /** A place whose cubes and structures are in from given ticks on (never, for Integer.MAX_VALUE). */
    private static final class Place implements ArrivalWait.Place {
        final IntPredicate cubes, structures;
        int tick, structureAsks;
        Place(IntPredicate cubes, IntPredicate structures) { this.cubes = cubes; this.structures = structures; }
        @Override public boolean cubesReady() { return cubes.test(tick); }
        @Override public boolean structuresReady() { structureAsks++; return structures.test(tick); }
    }

    /** The tick at which an arrival, retried every {@code every} ticks with its wait carried or not, takes {@code until}; -1 if never. */
    private static int run(Place place, int every, boolean carry, ArrivalWait.Step until) {
        ArrivalWait.Wait wait = ArrivalWait.Wait.NEW;
        for (place.tick = 0; place.tick < 100_000; place.tick++) {
            if (place.tick > 0 && place.tick % every == 0) wait = carry ? wait.carried() : ArrivalWait.Wait.NEW; // a new try of the same trip
            ArrivalWait.Next next = ArrivalWait.step(wait, place);
            if (next.step() == until) return place.tick;
            if (next.step() != ArrivalWait.Step.WAIT) return -1;
            wait = next.then();
        }
        return -1;
    }

    @Test void aLayoutThatKeepsFailingCannotHoldAPersonalSpawnForEver() {
        // Cached terrain, structures that never answer (a layout that keeps failing), a try every 30 s.
        int went = run(new Place(t -> true, t -> false), 600, true, ArrivalWait.Step.GO);
        assertEquals(ArrivalWait.LIMIT, went);
        assertEquals(-1, run(new Place(t -> true, t -> false), 600, false, ArrivalWait.Step.GO), "as before: each try reset the wait");
    }

    @Test void theStructuresGetTheirTimeOnceTheCubesAreIn() {
        // At a heavy load the cubes take 981 ticks and the layout 1,500: the structures' wait starts with the cubes.
        Place slow = new Place(t -> t >= 981, t -> t >= 1500);
        assertEquals(1500, run(slow, Integer.MAX_VALUE, true, ArrivalWait.Step.GO));
        assertTrue(slow.structureAsks >= 1500, "the structures were not asked for while the cubes came");
        // A layout that never finishes is given up for only once the cubes have been in for the limit.
        assertEquals(981 + ArrivalWait.LIMIT, run(new Place(t -> t >= 981, t -> false), Integer.MAX_VALUE, true, ArrivalWait.Step.GO));
    }

    @Test void itGoesOnlyWithItsCubesLoaded() {
        // Cubes the workers never deliver: never GO (nothing is made on the spot), given up instead.
        assertEquals(ArrivalWait.GIVE_UP, run(new Place(t -> false, t -> true), Integer.MAX_VALUE, true, ArrivalWait.Step.GIVE_UP));
        // A retry after giving up gets the cubes' full time again.
        assertEquals(0, new ArrivalWait.Wait(ArrivalWait.GIVE_UP, 0).carried().ticks());
    }
}
