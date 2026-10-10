package io.earthplatform.minecraft.coregate;

/**
 * One bounded server-owned paired-publication permit. Actor plans and publications are different:
 * a durable edit uses the same actor plan with a fresh publication. A resource reload kills the
 * session instead of allowing an old native/DH pair to reopen it. No collision owner lives here.
 */
final class RollingPublicationHandshake {
    enum State { IDLE, BUILDING, READY, PREPARED, GRANTED, PAIR_READY, CLOSED }
    private final long epoch;
    private long publication, committedPublication, resourceGeneration;
    private String digest = "", committedDigest = "";
    private State state = State.IDLE;

    RollingPublicationHandshake(long epoch) {
        if (epoch <= 0) throw new IllegalArgumentException("Positive epoch required");
        this.epoch = epoch;
    }

    long begin() {
        if (state != State.IDLE) throw new IllegalStateException("One publication at a time");
        publication = Math.incrementExact(publication);
        digest = "";
        state = State.BUILDING;
        return publication;
    }

    void ready(String manifestDigest) {
        if (state != State.BUILDING || manifestDigest == null || !manifestDigest.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Exact ready manifest required");
        digest = manifestDigest;
        state = State.READY;
    }

    boolean prepared(long epoch, long publication, long resources, String digest) {
        if (state != State.READY || !matches(epoch, publication, digest) || resources <= 0
                || resourceGeneration != 0 && resources != resourceGeneration) return false;
        resourceGeneration = resources;
        state = State.PREPARED;
        return true;
    }

    /** Caller must prove body containment and fence server collision before this transition. */
    void grant() {
        if (state != State.PREPARED) throw new IllegalStateException("Prepared pair required");
        state = State.GRANTED;
    }

    boolean pairReady(long epoch, long publication, long resources, String digest) {
        if (state != State.GRANTED || resources != resourceGeneration || !matches(epoch, publication, digest))
            return false;
        state = State.PAIR_READY;
        return true;
    }

    /** Caller has installed authoritative candidate collision after the actor commit (or edit). */
    void committed() {
        if (state != State.PAIR_READY) throw new IllegalStateException("Exact granted pair required");
        committedPublication = publication;
        committedDigest = digest;
        state = State.IDLE;
    }

    boolean acceptsMouse(long epoch, long publication, long resources) {
        return state == State.IDLE && committedPublication > 0 && this.epoch == epoch
                && publication == committedPublication && resources == resourceGeneration;
    }

    private boolean matches(long epoch, long publication, String digest) {
        return this.epoch == epoch && this.publication == publication && this.digest.equals(digest);
    }

    void close() { state = State.CLOSED; }
    State state() { return state; }
    boolean pending() { return state != State.IDLE && state != State.CLOSED; }
    long publication() { return publication; }
    long committedPublication() { return committedPublication; }
    long resourceGeneration() { return resourceGeneration; }
    String digest() { return digest; }
    String committedDigest() { return committedDigest; }
}
