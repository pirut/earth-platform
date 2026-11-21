package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import javax.annotation.Nullable;
import net.minecraft.core.SectionPos;

/**
 * Sable's Rapier physics runs in 32-bit floats, which hold whole numbers exactly only up to about
 * 8 million. Earth coordinates reach tens of millions of blocks, so a ship there was snapped to coarse
 * positions and shook. This keeps one physics scene near zero: every world position handed to Rapier
 * has a fixed whole-chunk origin subtracted, and every pose read back has it added again.
 *
 * <p>The origin is picked from the first ship or terrain chunk the scene sees and dropped again once
 * the scene holds nothing, so a trip to a distant part of the world starts a fresh one. A scene exists
 * here only for cubic levels, and only after something of the world has been given to it.
 */
public final class SableOrigin {
    /** The id Sable uses for the world itself in a constraint. */
    public static final int WORLD = -1;

    private static final Long2ObjectMap<SableOrigin> SCENES = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    private final LongOpenHashSet chunks = new LongOpenHashSet();
    /** Constraint handle to which of its two frames (bit 0, bit 1) sit in the world. */
    private final Long2IntOpenHashMap worldFrames = new Long2IntOpenHashMap();
    /** Constraint handle to how far its world frame was moved, in that frame's axes; see the constraint mixin. */
    private final Long2ObjectOpenHashMap<double[]> motorShifts = new Long2ObjectOpenHashMap<>();
    private boolean set;
    private int ox, oz;
    private int live;

    /** The scene's origin, created on first use. Only for cubic levels. */
    public static SableOrigin of(long scene) {
        synchronized (SCENES) {
            SableOrigin origin = SCENES.get(scene);
            if (origin == null) SCENES.put(scene, origin = new SableOrigin());
            return origin;
        }
    }

    /** The scene's origin, or null when nothing has been shifted for it. */
    @Nullable public static SableOrigin find(long scene) { return SCENES.get(scene); }

    public static void drop(long scene) { SCENES.remove(scene); }

    private SableOrigin() {}

    /** Picks the origin at a chunk when the scene is empty. */
    private void pick(int chunkX, int chunkZ) {
        if (set && (live > 0 || !chunks.isEmpty())) return;
        set = true;
        ox = chunkX;
        oz = chunkZ;
        worldFrames.clear();
        motorShifts.clear();
        EarthPlatformMod.LOGGER.info("Sable physics origin set to block ({}, {})", chunkX << 4, chunkZ << 4);
    }

    private static int chunkOf(double blocks) { return (int) Math.floor(blocks) >> 4; }

    /** The block offset to subtract from world x and z (zero while there is no origin). */
    public synchronized int blockX() { return set ? ox << 4 : 0; }

    public synchronized int blockZ() { return set ? oz << 4 : 0; }

    public synchronized boolean isSet() { return set; }

    /** A body is about to enter the scene at this world position. */
    public synchronized void bodyAdded(double x, double z) {
        pick(chunkOf(x), chunkOf(z));
        live++;
    }

    public synchronized void bodyRemoved() { live = Math.max(0, live - 1); }

    /** A world terrain section is about to enter the scene; returns its chunk x shift in chunks via {@link #chunkX}. */
    public synchronized void chunkAdded(int x, int y, int z) {
        pick(x, z);
        chunks.add(SectionPos.asLong(x, y, z));
    }

    public synchronized void chunkRemoved(int x, int y, int z) { chunks.remove(SectionPos.asLong(x, y, z)); }

    public synchronized int chunkX(int x) { return set ? x - ox : x; }

    public synchronized int chunkZ(int z) { return set ? z - oz : z; }

    /** Shifts the x and z of a flat array of x, y, z triples, as for a rope's points. */
    public synchronized void toPhysics(double[] triples, int count) {
        for (int i = 0; i < count; i++) {
            triples[i * 3] -= blockX();
            triples[i * 3 + 2] -= blockZ();
        }
    }

    /** The inverse of {@link #toPhysics}. */
    public synchronized void fromPhysics(double[] triples) {
        for (int i = 0; i + 2 < triples.length; i += 3) {
            triples[i] += blockX();
            triples[i + 2] += blockZ();
        }
    }

    /** Records which frames of a new constraint are anchored in the world. */
    public synchronized void constraintAdded(long handle, int bodyA, int bodyB) {
        int mask = (bodyA == WORLD ? 1 : 0) | (bodyB == WORLD ? 2 : 0);
        if (mask != 0) worldFrames.put(handle, mask);
    }

    public synchronized void constraintRemoved(long handle) {
        worldFrames.remove(handle);
        motorShifts.remove(handle);
    }

    /** Remembers that a constraint's world frame was moved, so its linear motor targets must move with it. */
    public synchronized void motorShift(long handle, double[] shift) { motorShifts.put(handle, shift); }

    @Nullable public synchronized double[] motorShift(long handle) { return motorShifts.get(handle); }

    /** Whether frame 0 or 1 of a constraint is anchored in the world. */
    public synchronized boolean worldFrame(long handle, int frame) {
        return set && (worldFrames.get(handle) & (1 << frame)) != 0;
    }
}
