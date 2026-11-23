package io.earthplatform.minecraft.cubic;

import net.minecraft.core.BlockPos;

/**
 * When a waiting arrival (a trip, or a player's first join) may go: once the cubes round the spot
 * are in (the readers and workers make them; the server thread never does, as making one waited
 * behind a slow structure start's lock, or on map data) and the structures there are known, so the
 * first lookups there (YUNG's temples, mob spawns) answer right. The structures are asked for from
 * the first tick, alongside the cubes, and waited for up to {@link #LIMIT} ticks once the cubes are
 * in. A trip whose cubes cannot be had by {@link #GIVE_UP} is given up rather than landing a player
 * in unloaded space.
 */
public final class ArrivalWait {
    /** Ticks the structures are waited for once the cubes are in, and ticks before a trip is given up. */
    public static final int LIMIT = 1200, GIVE_UP = 6000;

    private ArrivalWait() {}

    /** What an arrival waits for at its spot. */
    public interface Place {
        /** Whether the cubes round the spot are loaded; asks the workers for those that are not. */
        boolean cubesReady();
        /** Whether the structures there are known; asks for them aside until they are. */
        boolean structuresReady();
    }

    public enum Step { WAIT, GO, GIVE_UP }

    /** How long an arrival has waited: in all, and with its cubes in (for the structures). */
    public record Wait(int ticks, int withCubes) {
        public static final Wait NEW = new Wait(0, 0);

        /**
         * The wait carried to a new try of the same trip (a personal spawn's, tried again every
         * 30 s): the structures' deadline is kept, so a layout that keeps failing cannot hold the
         * arrival for ever, while the cubes get their full time again.
         */
        public Wait carried() { return new Wait(0, Math.min(withCubes, LIMIT)); }
    }

    /** This tick's step, and {@code then} the wait to pass to the next tick if it is not to go. */
    public record Next(Step step, Wait then) {}

    public static Next step(Wait wait, Place place) {
        boolean cubes = place.cubesReady();
        boolean structures = place.structuresReady(); // asked from the first tick, so the layouts run while the cubes come
        Step step = cubes && (structures || wait.withCubes >= LIMIT) ? Step.GO : wait.ticks >= GIVE_UP ? Step.GIVE_UP : Step.WAIT;
        return new Next(step, new Wait(wait.ticks + 1, cubes ? wait.withCubes + 1 : wait.withCubes));
    }

    /** The place round a block of a cubic world. */
    public static Place around(ServerCubeWorld cubes, BlockPos at) {
        return new Place() {
            @Override public boolean cubesReady() { return cubes.readyAround(at); }
            @Override public boolean structuresReady() { return cubes.structuresReadyAround(at); }
        };
    }
}
