package io.earthplatform.minecraft.scene;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * One admitted carrier resource reload's CPU aliases, independent of carrier disposal.
 * No GL calls, queued work, deleted-name registry, or RenderSystem/Iris callbacks.
 */
public final class NativeShaderTextureAliases {
    /** Exact pinned Iris dual-target initializer domain; not a hardware texture-unit count. */
    public static final int SLOT_COUNT = 128;
    private static Scope current;
    private static long nextGeneration;
    private static Evidence latest = new Evidence(0, false, Outcome.IDLE, 0, 0, 0, 0, 0, 0);

    private NativeShaderTextureAliases() {}

    public enum Outcome { IDLE, ACTIVE, SUCCESS, FAILED, CANCELLED, ABORTED }

    /** Bounded diagnostics only. A remembered name is never consulted to clear an alias. */
    public record Evidence(long generation, boolean active, Outcome outcome, long deletionCalls,
                           long aliasesCleared, int lastDeletedName, int lastClearedName,
                           long lastClearedSlotMaskLo, long lastClearedSlotMaskHi) {}

    /**
     * Reserve the single owner before calling the admission verifier, but enable no
     * deletion hook until it returns successfully. The caller must verify the exact
     * admitted carrier and ClientLevel immediately before the real resource reload.
     */
    public static Scope begin(Object exactClientLevel, Runnable verifyAdmitted) {
        Objects.requireNonNull(exactClientLevel, "Exact carrier level");
        Objects.requireNonNull(verifyAdmitted, "Admitted carrier verifier");
        Scope scope;
        synchronized (NativeShaderTextureAliases.class) {
            if (current != null) throw new IllegalStateException("Prior shader texture alias reload remains owned");
            scope = new Scope(exactClientLevel, Math.incrementExact(nextGeneration));
            nextGeneration = scope.generation;
            current = scope;
        }
        try {
            verifyAdmitted.run();
            synchronized (NativeShaderTextureAliases.class) {
                scope.admitted = true;
            }
            return scope;
        } catch (RuntimeException | Error failure) {
            synchronized (NativeShaderTextureAliases.class) {
                scope.release(Outcome.ABORTED);
            }
            throw failure;
        }
    }

    public static synchronized boolean active() {
        return current != null && current.admitted;
    }

    public static synchronized Evidence evidence() {
        return current != null && current.admitted ? current.evidence(true) : latest;
    }

    /** Invoke synchronously immediately BEFORE GL11.glDeleteTextures(int). */
    public static synchronized void beforeDelete(Object currentLevel, int[] rawSlots, int deletedName) {
        Scope scope = matching(currentLevel);
        if (scope == null) return;
        requireSlots(rawSlots);
        scope.deletionCalls++;
        scope.lastDeletedName = deletedName;
        scope.recordClear(deletedName, clearExactHalf(rawSlots, deletedName, 0),
                    clearExactHalf(rawSlots, deletedName, 64));
    }

    /** Invoke synchronously immediately BEFORE GL11.glDeleteTextures(int[]). */
    public static synchronized void beforeDelete(Object currentLevel, int[] rawSlots, int[] deletedNames) {
        Scope scope = matching(currentLevel);
        if (scope == null) return;
        requireSlots(rawSlots);
        Objects.requireNonNull(deletedNames, "Deleted texture names");
        // Never mutate the actual argument that the following GL deletion will use.
        if (rawSlots == deletedNames) throw new IllegalStateException("Deletion argument aliases shader texture storage");
        scope.deletionCalls++;
        scope.lastDeletedName = 0;
        for (int deletedName : deletedNames) {
            scope.lastDeletedName = deletedName;
            scope.recordClear(deletedName, clearExactHalf(rawSlots, deletedName, 0),
                    clearExactHalf(rawSlots, deletedName, 64));
        }
    }

    private static Scope matching(Object currentLevel) {
        Scope scope = current;
        if (scope == null || !scope.admitted || scope.exactLevel != currentLevel) return null;
        scope.ownerThread();
        return scope;
    }

    private static void requireSlots(int[] rawSlots) {
        if (rawSlots == null || rawSlots.length != SLOT_COUNT)
            throw new IllegalStateException("Expected exactly 128 pinned Iris raw RenderSystem shader texture slots; actual="
                    + (rawSlots == null ? "null" : rawSlots.length));
    }

    private static long clearExactHalf(int[] rawSlots, int deletedName, int offset) {
        // Java int preserves the GLuint bit pattern; only zero is inert. Two
        // exact 64-slot scans keep masks scalar and avoid Java's wrapping shift.
        if (deletedName == 0) return 0;
        long mask = 0;
        for (int bit = 0; bit < 64; bit++) {
            int slot = offset + bit;
            if (rawSlots[slot] == deletedName) {
                rawSlots[slot] = 0;
                mask |= 1L << bit;
            }
        }
        return mask;
    }

    public static final class Scope implements AutoCloseable {
        private final Object exactLevel;
        private final Thread owner = Thread.currentThread();
        private final long generation;
        private boolean admitted, closed;
        private CompletableFuture<?> reloadCompletion;
        private long deletionCalls, aliasesCleared;
        private int lastDeletedName, lastClearedName;
        private long lastClearedSlotMaskLo, lastClearedSlotMaskHi;
        private Outcome outcome = Outcome.ACTIVE;

        private Scope(Object exactLevel, long generation) {
            this.exactLevel = exactLevel;
            this.generation = generation;
        }

        /**
         * Bind once to the exact ReloadInstance.done() future. Its callback only
         * releases pure Java ownership and is safe on any completion thread. This
         * does not replace, recover, cancel, or mask the original future's outcome.
         */
        public void retainUntil(CompletableFuture<?> exactReloadCompletion) {
            Objects.requireNonNull(exactReloadCompletion, "Exact resource reload completion");
            synchronized (NativeShaderTextureAliases.class) {
                ownerThread();
                if (closed || current != this || !admitted || reloadCompletion != null)
                    throw new IllegalStateException("Shader texture alias reload completion already bound or closed");
                reloadCompletion = exactReloadCompletion;
            }
            try {
                exactReloadCompletion.whenComplete((ignored, failure) -> {
                    synchronized (NativeShaderTextureAliases.class) {
                        release(failure == null ? Outcome.SUCCESS
                                : exactReloadCompletion.isCancelled() ? Outcome.CANCELLED : Outcome.FAILED);
                    }
                });
            } catch (RuntimeException | Error failure) {
                synchronized (NativeShaderTextureAliases.class) {
                    // A failed callback registration cannot leave an immortal scope.
                    release(Outcome.ABORTED);
                }
                throw failure;
            }
        }

        public boolean closed() {
            synchronized (NativeShaderTextureAliases.class) { return closed; }
        }

        /** Abort only before a real reload future has been bound. Disposal is not completion. */
        @Override public void close() {
            synchronized (NativeShaderTextureAliases.class) {
                ownerThread();
                if (closed) return;
                if (reloadCompletion != null)
                    throw new IllegalStateException("Shader texture aliases remain owned until exact reload completion");
                release(Outcome.ABORTED);
            }
        }

        private void ownerThread() {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("Shader texture alias mutation requires the reload owner thread");
        }

        /** Caller holds the class monitor. Old completions cannot release a later scope. */
        private void release(Outcome outcome) {
            if (closed) return;
            if (current != this) throw new IllegalStateException("Shader texture alias reload ownership changed");
            closed = true;
            this.outcome = outcome;
            latest = evidence(false);
            current = null;
        }

        private void recordClear(int deletedName, long maskLo, long maskHi) {
            if ((maskLo | maskHi) == 0) return;
            aliasesCleared += Long.bitCount(maskLo) + Long.bitCount(maskHi);
            lastClearedName = deletedName;
            lastClearedSlotMaskLo = maskLo;
            lastClearedSlotMaskHi = maskHi;
        }

        private Evidence evidence(boolean active) {
            return new Evidence(generation, active, outcome, deletionCalls, aliasesCleared,
                    lastDeletedName, lastClearedName, lastClearedSlotMaskLo, lastClearedSlotMaskHi);
        }
    }
}
