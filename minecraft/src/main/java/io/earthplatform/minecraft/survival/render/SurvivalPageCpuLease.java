package io.earthplatform.minecraft.survival.render;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.util.Objects;

/** Existing shared WORKER_UPLOAD pool only; a failed physical close keeps its exact charge. */
final class SurvivalPageCpuLease {
    private final Thread owner = Thread.currentThread();
    private final NativeDistanceCpuBudget.Reservation reservation;
    SurvivalPageCpuLease(NativeDistanceCpuBudget budget, long bytes) {
        reservation = Objects.requireNonNull(budget).reserve(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD, bytes);
    }
    long bytes() { checkOwner(); return reservation.released() ? 0 : reservation.bytes(); }
    boolean released() { checkOwner(); return reservation.released(); }
    void drain(Runnable physicalClose) {
        checkOwner(); Objects.requireNonNull(physicalClose);
        if (reservation.released()) return;
        physicalClose.run();
        reservation.releaseAfterPhysicalDrain();
    }
    private void checkOwner() {
        if (owner != Thread.currentThread()) throw new IllegalStateException("Native page CPU lease requires its owner");
    }
}
