package io.earthplatform.minecraft.cubic.client;

/**
 * Counts the times the level is drawn. The game usually draws it once a frame, but a VR mod draws
 * it once for each eye (and again for a mirror or a handheld camera), each pass from its own
 * camera; anything worked out from the camera, or drawn only once, goes by the pass, not the frame.
 * Work that only has to happen once a frame, such as uploads, still goes by the frame.
 */
public final class LevelPasses {
    /** How many passes in a frame keep their own history (see {@link #inFrame}); more share the last. */
    public static final int TRACKED = 8;

    private static int pass, inFrame = -1;

    private LevelPasses() {}

    /** A new frame: its first pass will be number 0 in it. */
    public static void beginFrame() { inFrame = -1; }

    /** The level is about to be drawn again. */
    public static void begin() {
        pass++;
        inFrame++;
        FarLod.beginPass();
    }

    /** Counts up by one for every pass, so a value saved during one pass tells whether it is still under way. */
    public static int current() { return pass; }

    /** Which pass of this frame is under way, from 0, capped at {@link #TRACKED} - 1. */
    public static int inFrame() { return Math.clamp(inFrame, 0, TRACKED - 1); }
}
