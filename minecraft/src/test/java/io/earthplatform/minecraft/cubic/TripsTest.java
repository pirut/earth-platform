package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.MissingTerrainException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Stale trip steps are dropped; trips are called off on death or another dimension; holds never strand; a cold respawn falls back. */
class TripsTest {
    private final UUID player = UUID.randomUUID();

    @Test void anOlderFetchNeverReplacesANewerTrip() {
        Trips trips = new Trips();
        long first = trips.begin(player), second = trips.begin(player);
        // The first trip's terrain fetch finishes after the second set off: dropped.
        assertFalse(trips.current(player, first));
        assertTrue(trips.current(player, second));
        trips.end(player, first); // ending a stale trip does nothing to the newer one
        assertTrue(trips.pending(player));
        trips.end(player, second);
        assertFalse(trips.pending(player));
    }

    @Test void aCancelledPersonalSpawnsFetchDoesNotRecreateTheTrip() {
        Trips trips = new Trips();
        long personal = trips.begin(player);
        // Past its bound, the personal spawn is given up for the server's: called off.
        trips.cancel(player);
        assertFalse(trips.current(player, personal), "a fetch finishing after the fallback would land them");
        assertFalse(trips.pending(player));
        // A random trip's land search begun before a newer trip is dropped the same way.
        long search = trips.begin(player);
        trips.begin(player);
        assertFalse(trips.current(player, search));
    }

    @Test void aRetryOfTheSamePlaceKeepsItsClockAndAnotherPlaceDoesNot() {
        Trips trips = new Trips();
        ArrivalWait.Wait waited = new ArrivalWait.Wait(4_000, 100);
        trips.remember(player, 35.36, 138.73, waited);
        assertEquals(waited, trips.resume(player, 35.36, 138.73));
        assertEquals(ArrivalWait.Wait.NEW, trips.resume(player, 35.36, 138.73), "carried twice");
        trips.remember(player, 35.36, 138.73, waited);
        assertEquals(ArrivalWait.Wait.NEW, trips.resume(player, -33.92, 18.42));
        trips.remember(player, 35.36, 138.73, waited);
        trips.cancel(player);
        assertEquals(ArrivalWait.Wait.NEW, trips.resume(player, 35.36, 138.73), "a trip called off left its clock");
    }

    @Test void aTripIsCalledOffOnDeathOrAnotherDimension() {
        String overworld = "minecraft:overworld", nether = "minecraft:the_nether";
        assertFalse(Trips.calledOff(false, false, overworld, overworld));
        assertTrue(Trips.calledOff(false, false, overworld, nether), "a Nether portal taken while it waited");
        assertTrue(Trips.calledOff(false, true, overworld, overworld), "on the death screen");
        assertTrue(Trips.calledOff(true, false, overworld, overworld), "left the server");
    }

    @Test void aCalledOffTripTellsThePlayerWhyInOneLine() {
        String overworld = "minecraft:overworld", nether = "minecraft:the_nether";
        assertNull(Trips.whyCalledOff(false, false, overworld, overworld));
        // A random trip's search, or a terrain fetch, finishing after a Nether portal or a death.
        assertEquals("The trip was called off: you went to another dimension.", Trips.whyCalledOff(false, false, overworld, nether));
        assertEquals("The trip was called off: you died.", Trips.whyCalledOff(false, true, overworld, overworld));
        // Called off, the token is gone: the search's result, or a later fetch, cannot land them.
        Trips trips = new Trips();
        long search = trips.begin(player);
        trips.cancel(player);
        assertFalse(trips.current(player, search));
        assertFalse(trips.pending(player));
    }

    @Test void aLandingThatThrowsStillLetsGoOfItsCubes() {
        ResidentCubes resident = new ResidentCubes();
        resident.keep("join:a player", ResidentCubes.around(new net.minecraft.core.BlockPos(0, 64, 0)));
        assertThrows(IllegalStateException.class, () -> resident.landing("join:a player", () -> { throw new IllegalStateException("teleport failed"); }));
        assertEquals(0, resident.holders(), "the hold was stranded");
        resident.keep("trip:a player", ResidentCubes.around(new net.minecraft.core.BlockPos(0, 64, 0)));
        resident.landing("trip:a player", () -> {});
        assertEquals(0, resident.holders());
    }

    @Test void aColdRespawnFallsBackWhenTheTerrainIsUnavailable() {
        // The landing search or scatter reading map data that cannot be had: no arrival point, so vanilla's respawn.
        assertNull(ArrivalWait.orNone(() -> { throw new MissingTerrainException("elevation tile not downloaded"); }));
        assertEquals("here", ArrivalWait.orNone(() -> "here"));
    }
}
