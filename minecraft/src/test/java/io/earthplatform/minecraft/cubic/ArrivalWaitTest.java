package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** When a waiting arrival goes: one bound a retry can't reset, a structure grace from when the cubes are in, never without its cubes. */
class ArrivalWaitTest {
    /** A place whose cubes and structures are in at given ticks. */
    private static final class Place implements ArrivalWait.Place {
        final IntPredicate cubes, structures;
        int tick, structureAsks;
        Place(IntPredicate cubes, IntPredicate structures) { this.cubes = cubes; this.structures = structures; }
        @Override public boolean cubesReady() { return cubes.test(tick); }
        @Override public boolean structuresReady() { structureAsks++; return structures.test(tick); }
    }

    private static final IntPredicate NEVER = t -> false, ALWAYS = t -> true;

    /** The tick at which an arrival, tried again every {@code every} ticks with its wait carried (or not), takes {@code until}; -1 if it never does. */
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

    @Test void cubesInLateStillGiveTheStructuresTheirWholeGrace() {
        // The cubes come in at tick 5,900, the structures not at all: they get 1,200 ticks from then, not the 100 left before 6,000.
        assertEquals(5_900 + ArrivalWait.LIMIT, run(new Place(t -> t >= 5_900, NEVER), Integer.MAX_VALUE, true, ArrivalWait.Step.GO));
        // And answered at 6,500, it goes then.
        assertEquals(6_500, run(new Place(t -> t >= 5_900, t -> t >= 6_500), Integer.MAX_VALUE, true, ArrivalWait.Step.GO));
        assertTrue(5_900 + ArrivalWait.LIMIT <= ArrivalWait.TOTAL, "the grace fits in the bound");
    }

    @Test void aRetryNeverResetsTheClock() {
        // A personal spawn tried every 30 s, its terrain fetched fine each time, one cube never available: given up at 6,000.
        assertEquals(ArrivalWait.GIVE_UP, run(new Place(NEVER, ALWAYS), 600, true, ArrivalWait.Step.GIVE_UP));
        assertEquals(-1, run(new Place(NEVER, ALWAYS), 600, false, ArrivalWait.Step.GIVE_UP), "as before: each try reset it, held for ever");
        // A layout that keeps failing, cubes cached: goes once the grace has run, across the retries.
        assertEquals(ArrivalWait.LIMIT, run(new Place(ALWAYS, NEVER), 600, true, ArrivalWait.Step.GO));
    }

    @Test void everyArrivalGoesOrGivesUpWithinItsBound() {
        // Cubes in at 100, then one lost for good, structures never: given up at the bound, not held.
        assertEquals(ArrivalWait.TOTAL, run(new Place(t -> t >= 100 && t < 150, NEVER), Integer.MAX_VALUE, true, ArrivalWait.Step.GIVE_UP));
        // Cubes in and out after that, the structures waited for only from when the cubes were first in.
        assertEquals(100 + ArrivalWait.LIMIT, run(new Place(t -> t >= 100 && (t < 150 || t >= 1_000), NEVER), Integer.MAX_VALUE, true, ArrivalWait.Step.GO));
    }

    @Test void itGoesOnlyWithItsCubesLoadedAndAsksForTheStructuresFromTheStart() {
        Place place = new Place(t -> t >= 981, t -> t >= 1_500);
        assertEquals(1_500, run(place, Integer.MAX_VALUE, true, ArrivalWait.Step.GO));
        assertTrue(place.structureAsks > 981, "the structures were asked for only once the cubes were in");
        assertEquals(ArrivalWait.GIVE_UP, run(new Place(NEVER, ALWAYS), Integer.MAX_VALUE, true, ArrivalWait.Step.GIVE_UP));
    }

    @Test void aPersonalSpawnIsOverdueAfterTheWholeBound() {
        assertFalse(ArrivalWait.overdue(0, ArrivalWait.TOTAL * 50L));
        assertTrue(ArrivalWait.overdue(0, ArrivalWait.TOTAL * 50L + 1));
    }
}
