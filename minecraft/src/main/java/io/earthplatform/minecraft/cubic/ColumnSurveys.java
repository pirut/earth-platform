package io.earthplatform.minecraft.cubic;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongPredicate;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * Server thread. What the cube workers worked out about cube columns ({@link ColumnSurvey}): the lowest ground, from
 * which buried cubes are told apart, and with {@link SurfaceFlight} the bounds of the ground or water. Every call
 * that may ask for a survey says how many may be in flight before it asks for none ({@code cap}, {@link #ANY} on
 * foot), so a flying player's surveys stay few on every path that reaches them, not only while it is served.
 */
final class ColumnSurveys {
    /** Lowest ground not worked out yet. */
    static final int UNKNOWN_LOW = Integer.MAX_VALUE;
    /** No limit on surveys in flight, as before {@link SurfaceFlight}. */
    static final int ANY = Integer.MAX_VALUE;
    /**
     * With surface flight on, a failed survey is asked for again up to this many times, the first {@link #RETRY_TICKS}
     * after it failed and each after twice the wait before. Off, a failed column stays as it failed.
     */
    static final int RETRIES = 4, RETRY_TICKS = 100;
    /** Blocks of rock under a cube column's lowest ground (and its four neighbours') before its cubes count as buried. */
    static final int COVER = 16;

    /** Workers: surveys a cube column. */
    @FunctionalInterface interface Survey { long[] of(long column, int x, int z); }

    private final boolean surfaces, allBuried;
    private final Executor workers;
    private final Survey survey;
    private final int savedFrom;
    private final Long2IntOpenHashMap lows = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap surfaceLows = new Long2IntOpenHashMap(), surfaceHighs = new Long2IntOpenHashMap();
    /** With surface flight on, failed columns: the tries so far over the tick the next may go. */
    private final Long2LongOpenHashMap failed = new Long2LongOpenHashMap();
    private final LongOpenHashSet pending = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<long[]> ready = new ConcurrentLinkedQueue<>();
    /** Saved buried cubes near players, such as a mine: they come back from the save, never as a stand-in that would fill them in. */
    private final LongOpenHashSet kept = new LongOpenHashSet();
    /** Buried cubes behind an open face of a cube in the ground band (see {@link #openBehind}): made for real. */
    private final LongOpenHashSet opened = new LongOpenHashSet();
    private int tick;
    private boolean closed;

    /** {@code allBuried} counts no cube as buried, so every cube is made. */
    ColumnSurveys(boolean surfaces, boolean allBuried, Executor workers, Survey survey) {
        this.surfaces = surfaces;
        this.allBuried = allBuried;
        this.workers = workers;
        this.survey = survey;
        this.savedFrom = ColumnSurvey.saved(surfaces);
        lows.defaultReturnValue(UNKNOWN_LOW);
    }

    /** Takes in the surveys the workers finished; {@code tick} is the server's, for retries. */
    void drain(int tick) {
        this.tick = tick;
        for (long[] result; (result = ready.poll()) != null; ) {
            long column = result[0];
            pending.remove(column);
            lows.put(column, (int) result[1]);
            if (surfaces) {
                if (ColumnSurvey.failed(result)) failedAgain(column);
                else {
                    failed.remove(column);
                    surfaceLows.put(column, (int) result[2]);
                    surfaceHighs.put(column, (int) result[3]);
                }
            }
            for (int i = savedFrom; i < result.length; i++) kept.add(result[i]);
        }
    }

    private void failedAgain(long column) {
        surfaceLows.remove(column);
        surfaceHighs.remove(column);
        int tries = failed.containsKey(column) ? tries(failed.get(column)) + 1 : 1;
        int at = tries > RETRIES ? Integer.MAX_VALUE : tick + (RETRY_TICKS << (tries - 1));
        failed.put(column, (long) tries << 32 | at & 0xFFFFFFFFL);
    }

    private static int tries(long failure) { return (int) (failure >> 32); }

    private boolean retryDue(long column) {
        if (!failed.containsKey(column)) return false;
        long failure = failed.get(column);
        return tries(failure) <= RETRIES && tick >= (int) failure;
    }

    /**
     * The lowest ground of a cube column, or {@link #UNKNOWN_LOW}. Asks the workers for it when not known, or failed
     * and due again, while fewer than {@code cap} surveys are in flight.
     */
    int lowest(int x, int z, int cap) {
        long column = CubeKey.column(x, z);
        int low = lows.get(column);
        if ((low == UNKNOWN_LOW || !failed.isEmpty() && retryDue(column)) && !closed && pending.size() < cap && pending.add(column)) {
            workers.execute(() -> ready.add(survey.of(column, x, z)));
        }
        return low;
    }

    /** Lowest ground of a cube column and the four next to it, or {@link #UNKNOWN_LOW} while asked for. */
    int lowAround(int x, int z, int cap) {
        int low = lowest(x, z, cap), known = 1;
        for (int side = 0; side < 4; side++) {
            int each = lowest(x + (side == 0 ? 1 : side == 1 ? -1 : 0), z + (side == 2 ? 1 : side == 3 ? -1 : 0), cap);
            if (each == UNKNOWN_LOW) known = 0;
            else low = Math.min(low, each);
        }
        return known == 0 || low == UNKNOWN_LOW ? UNKNOWN_LOW : low;
    }

    /**
     * 1 when a cube lies under at least {@link #COVER} blocks of rock below the lowest ground of its own and the four
     * next cube columns, 0 when not, -1 while that ground is being worked out. Ground that can't be had (a failed
     * survey, in the cube's column or beside it) counts as nothing buried: the cube is made.
     */
    int buried(int x, int y, int z, int cap) {
        if (allBuried) return 0;
        if (!kept.isEmpty() && kept.contains(CubeKey.pack(x, y, z)) || !opened.isEmpty() && opened.contains(CubeKey.pack(x, y, z))) return 0;
        int low = lowAround(x, z, cap);
        if (low == UNKNOWN_LOW) return -1;
        // Long arithmetic: a failed survey's low is Integer.MIN_VALUE, which must stay far below every cube, not wrap round.
        return (y << 4) + 15 < (long) low - COVER ? 1 : 0;
    }

    /**
     * Opens the buried cubes right behind the open faces ({@code open}, one bit a face) of a cube that is not buried
     * itself, such as the rock under a cave going down from the ground band, so a cave seen from above carries on
     * another 16 blocks before any stand-in's stone. Opened cubes don't open further: caves join up under most ground,
     * and following them would bring back nearly all the rock.
     */
    void openBehind(int cx, int cy, int cz, int open, int cap) {
        if (open == 0 || buried(cx, cy, cz, cap) != 0 || opened.contains(CubeKey.pack(cx, cy, cz))) return;
        for (int face = 0; face < 6; face++) {
            if ((open & 1 << face) == 0) continue;
            int x = cx + (face == 4 ? -1 : face == 5 ? 1 : 0), y = cy + (face == 0 ? -1 : face == 1 ? 1 : 0), z = cz + (face == 2 ? -1 : face == 3 ? 1 : 0);
            if (buried(x, y, z, cap) == 1) opened.add(CubeKey.pack(x, y, z));
        }
    }

    /** A saved buried cube comes back from the save from now on. */
    void keep(long key) { kept.add(key); }

    /** A column's surface bounds, or {@link SurfaceFlight#UNKNOWN} when not known or failed; never asks for them. */
    long surfaceBounds(int x, int z) {
        long column = CubeKey.column(x, z);
        return surfaceHighs.containsKey(column) ? SurfaceFlight.bounds(surfaceLows.get(column), surfaceHighs.get(column)) : SurfaceFlight.UNKNOWN;
    }

    int inFlight() { return pending.size(); }

    int opened() { return opened.size(); }

    /** Forgets the columns {@code keep} turns down, with the kept and opened cubes in them. */
    void prune(LongPredicate keep) {
        lows.long2IntEntrySet().removeIf(entry -> !keep.test(entry.getLongKey()));
        if (surfaces) {
            surfaceLows.long2IntEntrySet().removeIf(entry -> !keep.test(entry.getLongKey()));
            surfaceHighs.long2IntEntrySet().removeIf(entry -> !keep.test(entry.getLongKey()));
            if (!failed.isEmpty()) failed.long2LongEntrySet().removeIf(entry -> !keep.test(entry.getLongKey()));
        }
        kept.removeIf(key -> !lows.containsKey(CubeKey.column(CubeKey.x(key), CubeKey.z(key))) && !pending.contains(CubeKey.column(CubeKey.x(key), CubeKey.z(key))));
        opened.removeIf(key -> !lows.containsKey(CubeKey.column(CubeKey.x(key), CubeKey.z(key))));
    }

    /** Asks for no more surveys; the workers are stopping. */
    void close() { closed = true; }
}
