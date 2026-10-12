package io.earthplatform.minecraft.scene;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Cleanup-only strong ownership. Parent precharges this fixed-capacity vector before construction.
 * No value, count or successful close callback from this class grants physical or frame completion.
 */
final class SurvivalSceneOwnerRoster<T> {
    @FunctionalInterface interface Close<T> { boolean closeAndConfirm(T owner); }
    private final Thread thread = Thread.currentThread();
    private final int capacity;
    private final ArrayList<T> owners;
    private boolean draining;
    SurvivalSceneOwnerRoster(int capacity) {
        if (capacity < 1 || capacity > 1536) throw new IllegalArgumentException("Bounded owner roster required");
        this.capacity = capacity; owners = new ArrayList<>(capacity);
    }
    void add(T owner) {
        check();
        if (draining || owners.size() == capacity) throw new IllegalStateException("Physical owner roster is full or draining");
        owners.add(Objects.requireNonNull(owner));
    }
    T get(int index) { check(); return owners.get(index); }
    int size() { check(); return owners.size(); }
    int retained() { check(); int result = 0; for (T owner : owners) if (owner != null) result++; return result; }
    void drain(Close<T> closer) {
        check(); Objects.requireNonNull(closer);
        if (draining) throw new IllegalStateException("Reentrant physical-owner cleanup");
        draining = true; Throwable failure = null;
        try {
            for (int i = 0; i < owners.size(); i++) {
                T owner = owners.get(i); if (owner == null) continue;
                try {
                    if (!closer.closeAndConfirm(owner)) throw new IllegalStateException("Physical owner did not confirm drain");
                    owners.set(i, null);
                } catch (RuntimeException | Error error) {
                    if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
                }
            }
        } finally { draining = false; }
        if (failure instanceof Error fatal) throw fatal;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }
    void clearAfterDrain() {
        check(); if (draining || retained() != 0) throw new IllegalStateException("Physical owners remain strongly retained");
        owners.clear();
    }
    private void check() { if (Thread.currentThread() != thread) throw new IllegalStateException("Exact cleanup roster owner thread required"); }
}
