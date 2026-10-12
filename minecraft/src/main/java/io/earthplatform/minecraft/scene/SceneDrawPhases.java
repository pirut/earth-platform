package io.earthplatform.minecraft.scene;

import java.util.function.Supplier;

/**
 * Synchronous owner-action brackets. Timing failures cannot discard a captured restoration owner
 * or skip work/cleanup. A work failure stays primary; cleanup and timing failures are suppressed.
 * No callbacks escape the current owner call and no samples or actions are retained after it.
 */
public final class SceneDrawPhases<S, T> {
    @FunctionalInterface interface Action { void run(); }
    public interface Timing<S, T> {
        T begin(S stage, String detail);
        void end(T token);
    }

    private final Timing<S, T> timing;
    private Throwable timingFailure;

    SceneDrawPhases(Timing<S, T> timing) { this.timing = timing; }

    /** One owner action with the same primary-error and deferred-timing-failure guarantees. */
    public static <S, T, R> R measured(Timing<S, T> timing, S stage, String detail, Supplier<R> work) {
        var phases = new SceneDrawPhases<>(timing);
        return phases.run(() -> phases.capture(stage, detail, work));
    }

    /** Encloses the whole draw, including its nested finally/restoration actions. */
    <R> R run(Supplier<R> work) {
        return restoring(work, () -> rethrow(timingFailure));
    }

    <R> R capture(S stage, String detail, Supplier<R> work) {
        if (timing == null) return work.get();
        T token = null;
        boolean started = false;
        try { token = timing.begin(stage, detail); started = true; }
        catch (RuntimeException | Error failure) { timingFailure = append(timingFailure, failure); }
        try { return work.get(); }
        finally {
            if (started) {
                try { timing.end(token); }
                catch (RuntimeException | Error failure) { timingFailure = append(timingFailure, failure); }
            }
        }
    }

    void phase(S stage, String detail, Action work) {
        capture(stage, detail, () -> { work.run(); return null; });
    }

    /** Unlike a bare finally, restoration cannot replace the original work failure. */
    static <R> R restoring(Supplier<R> work, Action restore) {
        R result = null;
        Throwable failure = null;
        try { result = work.get(); }
        catch (RuntimeException | Error error) { failure = error; }
        try { restore.run(); }
        catch (RuntimeException | Error error) { failure = append(failure, error); }
        rethrow(failure);
        return result;
    }

    /** Every independent restoration is attempted, even if an earlier one throws. */
    static void cleanupAll(Action... actions) {
        Throwable failure = null;
        for (var action : actions) {
            try { action.run(); }
            catch (RuntimeException | Error error) { failure = append(failure, error); }
        }
        rethrow(failure);
    }

    static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
