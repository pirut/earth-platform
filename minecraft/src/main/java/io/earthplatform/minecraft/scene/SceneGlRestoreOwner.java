package io.earthplatform.minecraft.scene;

import java.util.Objects;

/** One retained native upload restoration owner; failure never releases retry responsibility. */
final class SceneGlRestoreOwner {
    private final Thread owner = Thread.currentThread();
    private final Runnable restore, before, after;
    private boolean pending = true, restoring;
    SceneGlRestoreOwner(Runnable restore, Runnable before, Runnable after) {
        this.restore = Objects.requireNonNull(restore);
        this.before = Objects.requireNonNull(before);
        this.after = Objects.requireNonNull(after);
    }
    void restore() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Native upload restoration requires its owner thread");
        if (!pending) return;
        if (restoring) throw new IllegalStateException("Native upload restoration is already running");
        restoring = true;
        try {
        Throwable failure = null;
        try { before.run(); } catch (RuntimeException | Error error) { failure = error; }
        try { restore.run(); } catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
        try { after.run(); } catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
        SceneBuildStepper.rethrow(failure);
        pending = false;
        } finally { restoring = false; }
    }
    boolean pending() { return pending; }
}
