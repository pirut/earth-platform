package io.earthplatform.minecraft.coregate;

/** Server-owned readiness fence. A repeated old ready packet cannot reopen an invalidated frame. */
final class CoreCollisionHandshake {
    private final long epoch;
    private long lastGeneration = -1;
    private boolean ready;
    CoreCollisionHandshake(long epoch) { if (epoch <= 0) throw new IllegalArgumentException(); this.epoch = epoch; }
    void invalidate() { ready = false; }
    boolean accept(CoreGateCollisionPayload p, long authoritativeRevision, boolean editPending) {
        if (p.epoch() != epoch || p.generation() < lastGeneration) return false;
        if (!p.ready()) { lastGeneration = p.generation(); ready = false; return true; }
        if (editPending || p.revision() != authoritativeRevision || p.generation() <= lastGeneration) return false;
        lastGeneration = p.generation(); ready = true; return true;
    }
    boolean ready() { return ready; }
    long generation() { return lastGeneration; }
}
