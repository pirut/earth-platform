package io.earthplatform.minecraft.cubic;

import net.minecraft.core.BlockPos;

/**
 * When a waiting arrival (a trip, or a player's first join) may go: once the cubes round the spot
 * are in (the readers and workers make them; the server thread never does, as making one waited
 * behind a slow structure start's lock, or on map data) and the structures there are known, so the
 * first lookups there (YUNG's temples, mob spawns) answer right. Its bounds:
 * <ul>
 * <li>one clock from the first ask, which a new try of the same trip keeps: the cubes must be in
 *     by {@link #GIVE_UP};</li>
 * <li>the structures, asked for from the first tick alongside the cubes, get {@link #LIMIT} ticks
 *     from when the cubes are first all in, however late that is;</li>
 * <li>so every arrival goes or gives up by {@link #TOTAL}.</li>
 * </ul>
 * A trip given up does not land its player in unloaded space.
 */
public final class ArrivalWait {
    /** Ticks the cubes have, from the first ask; ticks the structures have, from when the cubes are in; the bound on both. */
    public static final int GIVE_UP = 6000, LIMIT = 1200, TOTAL = GIVE_UP + LIMIT;

    private ArrivalWait() {}

    /** What an arrival waits for at its spot. */
    public interface Place {
        /** Whether the cubes round the spot are loaded; asks the workers for those that are not. */
        boolean cubesReady();
        /** Whether the structures there are known; asks for them aside until they are. */
        boolean structuresReady();
    }

    public enum Step { WAIT, GO, GIVE_UP }

    /** How long an arrival has waited, in ticks from its first ask, and the tick its cubes were first all in (-1 not yet). */
    public record Wait(int ticks, int cubesIn) {
        public static final Wait NEW = new Wait(0, -1);

        /**
         * The wait for a new try of the same trip (a personal spawn's, tried again every 30 s): all
         * of it, so a retry never resets the clock, and a layout that keeps failing or a cube that
         * never comes cannot hold the arrival for ever.
         */
        public Wait carried() { return this; }
    }

    /** This tick's step, and {@code then} the wait to pass to the next tick if it is not to go. */
    public record Next(Step step, Wait then) {}

    public static Next step(Wait wait, Place place) {
        boolean cubes = place.cubesReady();
        boolean structures = place.structuresReady(); // asked from the first tick, so the layouts run while the cubes come
        int in = wait.cubesIn >= 0 ? wait.cubesIn : cubes ? wait.ticks : -1;
        Step step;
        if (cubes && (structures || wait.ticks - in >= LIMIT)) step = Step.GO;
        else if (in < 0 ? wait.ticks >= GIVE_UP : wait.ticks >= TOTAL) step = Step.GIVE_UP;
        else step = Step.WAIT;
        return new Next(step, new Wait(wait.ticks + 1, in));
    }

    /** Whether a wait counted in milliseconds from {@code since} is past {@link #TOTAL} ticks of a 20-tick-a-second server. */
    public static boolean overdue(long since, long now) { return now - since > TOTAL * 50L; }

    /**
     * The arrival point {@code find} gives, or null where the terrain it needs is unavailable now (a
     * landing search or scatter reading map data that cannot be had), so the caller falls back.
     */
    @javax.annotation.Nullable public static <T> T orNone(java.util.function.Supplier<T> find) {
        try {
            return find.get();
        } catch (RuntimeException unavailable) {
            io.earthplatform.minecraft.EarthPlatformMod.LOGGER.warn("No arrival point to be had now: {}", unavailable.toString());
            return null;
        }
    }

    /** The place round a block of a cubic world. */
    public static Place around(ServerCubeWorld cubes, BlockPos at) {
        return new Place() {
            @Override public boolean cubesReady() { return cubes.readyAround(at); }
            @Override public boolean structuresReady() { return cubes.structuresReadyAround(at); }
        };
    }
}
