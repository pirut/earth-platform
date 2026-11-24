package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** An arrival's destination cubes stay loaded while nobody is near, across the unload passes, until it lets go. */
class ResidentCubesTest {
    private static final BlockPos LANDING = new BlockPos(-10_533_883, 1272, -3_591_483);

    /** One 40-tick unload pass, as ServerCubeWorld's: nobody is near, so every cube not held goes. */
    private static int pass(LongSet loaded, ResidentCubes resident) {
        var drop = ResidentCubes.unloadable(loaded, key -> false, resident);
        drop.forEach(loaded::remove);
        return drop.size();
    }

    @Test void cubesComingInAcrossSeveralUnloadPassesAreAllStillThereWhenTheLastArrives() {
        long[] destination = ResidentCubes.around(LANDING);
        ResidentCubes resident = new ResidentCubes();
        resident.keep("trip:a player", destination);
        LongSet loaded = new LongLinkedOpenHashSet();
        // A cold remote trip: the workers deliver the destination's cubes one at a time, a few passes apart.
        for (int i = 0; i < destination.length; i++) {
            loaded.add(destination[i]);
            for (int cycle = 0; cycle < 3; cycle++) assertEquals(0, pass(loaded, resident), "a destination cube was dropped");
        }
        assertEquals(destination.length, loaded.size());
        // A slow structure lookup after all are in: still held through many more passes.
        for (int cycle = 0; cycle < 100; cycle++) assertEquals(0, pass(loaded, resident));
        // Arrived (or gave up, or left): let go, and the next pass drops them as nobody is near.
        resident.release("trip:a player");
        assertEquals(destination.length, pass(loaded, resident));
        assertEquals(0, resident.holders());
    }

    @Test void withoutAHoldTheFirstCubesGoBeforeTheLastArrive() {
        long[] destination = ResidentCubes.around(LANDING);
        ResidentCubes resident = new ResidentCubes();
        LongSet loaded = new LongLinkedOpenHashSet();
        int dropped = 0;
        for (long key : destination) {
            loaded.add(key);
            dropped += pass(loaded, resident);
        }
        assertEquals(destination.length, dropped, "as before: each cube went in the pass after it came");
    }

    @Test void holdersShareCubesAndOnlyTheLastReleaseLetsGo() {
        ResidentCubes resident = new ResidentCubes();
        long[] a = ResidentCubes.around(LANDING), b = ResidentCubes.around(LANDING.offset(16, 0, 0));
        resident.keep("trip:a", a);
        resident.keep("join:b", b); // overlapping columns
        long shared = CubeKey.pack((LANDING.getX() >> 4) + 1, LANDING.getY() >> 4, LANDING.getZ() >> 4);
        assertTrue(resident.holds(shared));
        resident.release("trip:a");
        assertTrue(resident.holds(shared), "released with another holder still keeping it");
        assertTrue(resident.holdsColumn(CubeKey.column(CubeKey.x(shared), CubeKey.z(shared))));
        resident.release("join:b");
        assertFalse(resident.holds(shared));
        assertFalse(resident.holdsColumn(CubeKey.column(CubeKey.x(shared), CubeKey.z(shared))));
        // Keeping again for the same holder replaces what it kept: nothing left over from the old spot.
        resident.keep("trip:a", a);
        resident.keep("trip:a", b);
        assertFalse(resident.holds(a[0]));
        assertTrue(resident.holds(b[0]));
        resident.clear(); // the world closes
        assertEquals(0, resident.holders());
        assertFalse(resident.holds(b[0]));
    }

    @Test void theDestinationCoversWhereAnArrivalMayStand() {
        long[] keys = ResidentCubes.around(LANDING);
        assertEquals(9 * (ResidentCubes.BELOW + ResidentCubes.ABOVE + 1), keys.length);
        int cx = LANDING.getX() >> 4, cy = LANDING.getY() >> 4, cz = LANDING.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 3; dy++) {
            long key = CubeKey.pack(cx + dx, cy + dy, cz + dz);
            boolean found = false;
            for (long k : keys) found |= k == key;
            assertTrue(found, "cube " + dx + "," + dy + "," + dz + " not kept");
        }
    }
}
