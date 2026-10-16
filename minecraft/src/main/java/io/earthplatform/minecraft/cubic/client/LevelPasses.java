package io.earthplatform.minecraft.cubic.client;

/**
 * Counts the times the level is drawn. The game usually draws it once a frame, but a VR mod draws
 * it once for each eye (and again for a mirror or a handheld camera), each pass from its own
 * camera; anything worked out from the camera, or drawn only once, goes by the pass, not the frame.
 * Work that only has to happen once a frame, such as uploads, still goes by the frame.
 * <p>
 * A frame's first pass starts with the frame itself, before anything draws, as every frame did
 * before passes were counted; only a second or later pass in the same frame starts where the level
 * is drawn again. Starting the first pass there too depended on running before other mods' hooks
 * on the same method, and on a Mac under a shader pack it didn't, leaving near terrain's culled
 * list and far terrain's depth a pass behind: stretched and missing blocks and a dark wall.
 */
public final class LevelPasses {
    /** How many passes in a frame keep their own history (see {@link #inFrame}); more share the last. */
    public static final int TRACKED = 8;

    private static int pass, inFrame;
    /** Whether the level was drawn yet this frame; the first time it is, the frame's pass is already under way. */
    private static boolean drawn;

    private LevelPasses() {}

    /** A new frame, and its first pass. */
    public static void beginFrame() {
        pass++;
        inFrame = 0;
        drawn = false;
        FarLod.beginPass();
    }

    /** The level is about to be drawn: the frame's first pass goes on, a later one starts afresh. */
    public static void begin() {
        if (!drawn) { drawn = true; return; }
        pass++;
        inFrame++;
        FarLod.beginPass();
    }

    /** Counts up by one for every pass, so a value saved during one pass tells whether it is still under way. */
    public static int current() { return pass; }

    /** Which pass of this frame is under way, from 0, capped at {@link #TRACKED} - 1. */
    public static int inFrame() { return Math.clamp(inFrame, 0, TRACKED - 1); }
}
