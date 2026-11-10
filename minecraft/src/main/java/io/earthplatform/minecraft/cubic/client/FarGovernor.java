package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.play.EarthConfig;
import net.minecraft.client.Minecraft;

/**
 * Holds a frame rate the player asked for (the {@code targetFrameRate} setting) by stepping far
 * terrain's sharpness down a choice at a time while the game runs slower, and back up toward the
 * choice the player made when there is room again. Off unless a rate is set. It only ever moves
 * the sharpness, between Fast and the player's own choice: the far distance stays as set.
 *
 * <p>It counts frames over {@value #WINDOW} ms at a time. Two slow windows running step down. A
 * step up is a trial, as the frame rate one choice sharper can only be found by trying: it waits
 * for {@value #FIRST_WAIT} ms of fast windows first, and each trial that had to be taken back
 * doubles the wait for the next, up to {@value #LONGEST_WAIT} ms, so a computer that cannot hold
 * the next choice settles rather than flickering between two. With the frame rate uncapped a trial
 * also wants headroom ({@value #HEADROOM} times the rate asked for); capped at about the rate
 * asked for (the game's limit, or vsync) there is none to see, and holding the cap is enough.
 * After any step it looks away for {@value #SETTLE} ms while the columns of the new choice come in.
 */
final class FarGovernor {
    static final int WINDOW = 2_000, SETTLE = 6_000, FIRST_WAIT = 20_000, LONGEST_WAIT = 320_000, TRIAL = 30_000;
    static final double SLOW = 0.9, HELD = 0.97, HEADROOM = 1.15;

    private long windowStart = Long.MIN_VALUE, quietUntil = Long.MIN_VALUE, raisedAt = Long.MIN_VALUE;
    private int frames, slow, fast, wait = FIRST_WAIT;

    /** Forgets what it has seen, as when the setting, the choice or the world changes, or the game was paused. */
    void reset() {
        windowStart = Long.MIN_VALUE;
        slow = fast = 0;
    }

    /**
     * Counts a frame drawn at {@code now} (ms) and, at the end of a window, says how many choices
     * below the player's own the sharpness should be held: {@code steps} as it is, one more or one
     * less, from 0 to {@code most}.
     */
    int frame(long now, int target, boolean capped, int steps, int most) {
        if (windowStart == Long.MIN_VALUE) { windowStart = now; frames = 0; return steps; }
        frames++;
        long elapsed = now - windowStart;
        if (elapsed < WINDOW) return steps;
        double rate = frames * 1000.0 / elapsed;
        windowStart = now;
        frames = 0;
        if (now < quietUntil) return steps;
        // A trial that held: the next one need not wait as long.
        if (raisedAt != Long.MIN_VALUE && now - raisedAt >= TRIAL) { raisedAt = Long.MIN_VALUE; wait = FIRST_WAIT; }
        if (rate < SLOW * target) {
            fast = 0;
            if (++slow < 2 || steps >= most) return steps;
            slow = 0;
            if (raisedAt != Long.MIN_VALUE) { raisedAt = Long.MIN_VALUE; wait = Math.min(2 * wait, LONGEST_WAIT); }
            quietUntil = now + SETTLE;
            return steps + 1;
        }
        slow = 0;
        if (rate >= (capped ? HELD : HEADROOM) * target) {
            if (++fast * WINDOW < wait || steps <= 0) return steps;
            fast = 0;
            raisedAt = now;
            quietUntil = now + SETTLE;
            return steps - 1;
        }
        fast = 0;
        return steps;
    }

    private static final FarGovernor INSTANCE = new FarGovernor();
    private static int lastTarget;
    private static EarthConfig.Sharpness lastChoice;

    /** Called once a frame drawn in a cubic world, on the render thread. */
    static void drawn() {
        int target = EarthConfig.FAR_TARGET_FPS.get();
        EarthConfig.Sharpness choice = EarthConfig.FAR_SHARPNESS.get();
        if (target != lastTarget || choice != lastChoice) {
            lastTarget = target;
            lastChoice = choice;
            INSTANCE.reset();
            INSTANCE.wait = FIRST_WAIT;
            INSTANCE.raisedAt = Long.MIN_VALUE;
            if (target <= 0) EarthConfig.sharpnessSteps(0);
        }
        if (target <= 0) return;
        Minecraft minecraft = Minecraft.getInstance();
        // A paused or background game says nothing of how fast it plays.
        if (minecraft.isPaused() || !minecraft.isWindowActive()) { INSTANCE.reset(); return; }
        int limit = minecraft.getWindow().getFramerateLimit();
        boolean capped = minecraft.options.enableVsync().get() || limit <= HEADROOM * target;
        long now = net.minecraft.Util.getMillis();
        EarthConfig.sharpnessSteps(INSTANCE.frame(now, target, capped, EarthConfig.sharpnessSteps(), EarthConfig.mostSharpnessSteps()));
    }

    /** Lets go of the sharpness when the world is left, so the next one starts from the player's choice. */
    static void left() {
        INSTANCE.reset();
        INSTANCE.wait = FIRST_WAIT;
        INSTANCE.raisedAt = Long.MIN_VALUE;
        EarthConfig.sharpnessSteps(0);
    }
}
