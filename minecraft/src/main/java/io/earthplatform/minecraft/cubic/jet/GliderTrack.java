package io.earthplatform.minecraft.cubic.jet;

/**
 * Server: where a glider was over its last few game ticks. A player's client moves it, and its moves
 * reach the server in uneven bursts, so the server's own velocity for it says nothing about how fast
 * it went. Speeds averaged over a few ticks of real positions tell a crash from flight.
 */
public final class GliderTrack {
    private static final int SIZE = 8;
    private final double[] xs = new double[SIZE], zs = new double[SIZE];
    private long tick;
    private int count;

    public void reset() { count = 0; }

    /** Notes where the glider is at a game tick; within a tick the last call wins. */
    public void at(long now, double x, double z) {
        if (count > 0 && now == tick) { set(now, x, z); return; }
        long gap = now - tick;
        // A first sight, a long gap, or a jump no glider makes (a teleport) starts over.
        if (count == 0 || gap < 0 || gap > SIZE || Math.hypot(x - xs[slot(tick)], z - zs[slot(tick)]) > JetStream.MAX_STEP * gap) {
            count = 0;
            gap = 1;
        }
        // Ticks with no move heard count as already here, so a late packet never looks like a stop.
        for (long t = now - gap + 1; t <= now; t++) set(t, x, z);
        count = (int) Math.min(SIZE, count + gap);
        tick = now;
    }

    /** Damage for flying into a wall now: by how much the speed of a few ticks ago has dropped since. 0 or less when it hasn't. */
    public float crash() {
        if (count < 7) return 0.0F;
        // Two-tick spans, as a client's moves come one tick late and two the next as often as one a tick.
        return JetStream.crash(distance(tick - 6, tick - 4) / 2, distance(tick - 2, tick) / 2);
    }

    private double distance(long from, long to) { return Math.hypot(xs[slot(to)] - xs[slot(from)], zs[slot(to)] - zs[slot(from)]); }

    private void set(long t, double x, double z) { xs[slot(t)] = x; zs[slot(t)] = z; }

    private static int slot(long t) { return (int) Math.floorMod(t, (long) SIZE); }
}
