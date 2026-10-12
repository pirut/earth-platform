package io.earthplatform.minecraft.survival.interaction;

import io.earthplatform.minecraft.survival.world.CubeCell;
import java.util.Objects;

/**
 * Server-side START/STOP/ABORT timing from the pinned ServerPlayerGameMode protocol. The rate is
 * always supplied by native BlockState.getDestroyProgress, never a hardness/tool lookup table.
 * This class does not break blocks: READY grants one attempt at a separately validated atomic
 * action. A host must validate the target, reach, held tool and session on every packet/tick.
 */
public final class NativeMiningProgress {
    public enum Phase { IDLE, HELD, DELAYED }
    public enum Status { IDLE, PROGRESS, READY, CANCELLED }
    public record Update(Status status, CubeCell target, float fraction, int crackStage) {}
    private Phase phase = Phase.IDLE;
    private CubeCell target;
    private long startTick, lastTick = -1;

    public Phase phase() { return phase; }
    public CubeCell target() { return target; }

    public Update start(CubeCell target, long tick, float nativeRate) {
        Objects.requireNonNull(target); validate(tick, nativeRate);
        this.target = target; this.startTick = tick; this.lastTick = tick; phase = Phase.HELD;
        if (nativeRate >= 1.0F) return ready(nativeRate);
        return progress(nativeRate);
    }

    public Update tick(long tick, float nativeRate, boolean targetStillValid) {
        validate(tick, nativeRate);
        if (phase == Phase.IDLE) return idle();
        if (!targetStillValid) return cancel();
        lastTick = tick;
        float fraction = fraction(tick, nativeRate);
        if (phase == Phase.DELAYED && fraction >= 1.0F) return ready(fraction);
        // Native HELD progress waits for the client's STOP packet, including its 0.7 tolerance.
        return progress(fraction);
    }

    public Update stop(CubeCell stoppedTarget, long tick, float nativeRate, boolean targetStillValid) {
        Objects.requireNonNull(stoppedTarget); validate(tick, nativeRate);
        if (phase == Phase.IDLE) return idle();
        if (!targetStillValid) return cancel();
        if (!target.equals(stoppedTarget)) return idle();
        lastTick = tick;
        float fraction = fraction(tick, nativeRate);
        if (fraction >= 0.7F) return ready(fraction);
        phase = Phase.DELAYED;
        return progress(fraction);
    }

    /** Abort clears delayed work too; no earlier target can finish after release/cancellation. */
    public Update cancel() {
        CubeCell old = target; clear();
        return old == null ? idle() : new Update(Status.CANCELLED, old, 0, -1);
    }

    private Update ready(float fraction) {
        CubeCell old = target; clear();
        return new Update(Status.READY, old, fraction, -1);
    }
    private Update progress(float fraction) {
        return new Update(Status.PROGRESS, target, fraction, Math.min(9, (int) (fraction * 10.0F)));
    }
    private static Update idle() { return new Update(Status.IDLE, null, 0, -1); }
    private float fraction(long tick, float rate) {
        long elapsed = Math.subtractExact(tick, startTick);
        float result = rate * (float) Math.addExact(elapsed, 1);
        return Float.isInfinite(result) ? Float.MAX_VALUE : result;
    }
    private void clear() { phase = Phase.IDLE; target = null; }
    private void validate(long tick, float rate) {
        if (tick < 0 || tick < lastTick || !Float.isFinite(rate) || rate < 0)
            throw new IllegalArgumentException("Monotonic server tick and finite nonnegative native rate required");
    }
}
