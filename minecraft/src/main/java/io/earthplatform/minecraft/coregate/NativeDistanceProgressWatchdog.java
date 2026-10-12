package io.earthplatform.minecraft.coregate;

import java.util.Arrays;
import java.util.Objects;

/**
 * Constant-space, pause-aware liveness policy for one native-distance replacement episode.
 * An attempt identity is an attribution fence, never useful work. Credits survive every rebind
 * and are bounded by one semantic high-water value per distance and lane.
 */
public final class NativeDistanceProgressWatchdog {
    public static final long USEFUL_IDLE_NANOS = 120_000_000_000L;
    public static final long PEER_IDLE_NANOS = 120_000_000_000L;
    public static final long TOTAL_ACTIVE_NANOS = 1_800_000_000_000L;
    public static final int MIN_SELECTED = 2, MAX_SELECTED = 32, SETTING_COUNT = 31;

    /** Observation bounds only. These do not change any renderer or resource allocation cap. */
    public enum Lane {
        SERVER_DRAINED(1, true), CLIENT_DRAINED(1, false), PREFLIGHT(1, true),
        SOURCE_READY(1, true), ACTOR_SNAPSHOTS(188, true), CAPSULE_CALLBACKS(86_016, false),
        NATIVE_MESH_CALLBACKS(114_688, false), REPLACEMENT_READS(100_000, false),
        SOURCE_COLUMNS(3_200_000, false), PLAN_TILES(732, false), HALO_ROWS(202_752, false),
        FACE_SCANS(4_831_838_208L, false), PRODUCED_PIECES(3_072, false),
        UPLOADED_PIECES(3_072, false), DH_UPLOADED_BYTES(33_554_432, false),
        PREPARED_PAIR(1, true), BODY_EQUALITY_COMPARISONS(512_000, false);

        private final long cap;
        private final boolean server;
        Lane(long cap, boolean server) { this.cap = cap; this.server = server; }
        public long cap() { return cap; }
        public boolean server() { return server; }
        public boolean serverOwned() { return server; }
    }

    private static final Lane[] LANES = Lane.values();
    public static final int LANE_COUNT = LANES.length;

    public record Limits(long usefulIdleNanos, long peerIdleNanos, long totalActiveNanos) {
        public Limits {
            if (usefulIdleNanos <= 0 || peerIdleNanos <= 0 || totalActiveNanos <= 0)
                throw new IllegalArgumentException("Progress deadlines must be positive");
        }
        public static Limits defaults() { return new Limits(USEFUL_IDLE_NANOS, PEER_IDLE_NANOS, TOTAL_ACTIVE_NANOS); }
    }

    /** Arrays are copied both on construction and access; a receipt cannot mutate the guard. */
    public record Checkpoint(long episode, long requestEpoch, long serial, int selected,
                             int effective, int serverLimit, long actorEpoch, long resourceGeneration,
                             long publication, String digest, String planIdentity, long activeNanos,
                             long usefulIdleNanos, long peerIdleNanos, long lastPeerStamp,
                             long clientObservation, long serverObservation, boolean paused,
                             String failure, long[] raw, long[][] credited) {
        public Checkpoint { raw = raw.clone(); credited = copyTable(credited); }
        @Override public long[] raw() { return raw.clone(); }
        @Override public long[][] credited() { return copyTable(credited); }
        public long[] currentCredited() { return credited[selected - MIN_SELECTED].clone(); }
        public long[] rawVector() { return raw(); }
        public long[][] creditedTable() { return credited(); }
        public boolean failed() { return failure != null; }
    }

    private final long episode;
    private final Limits limits;
    private final boolean checkPeer;
    private final long[] raw = new long[LANE_COUNT];
    private final long[][] credited = new long[SETTING_COUNT][LANE_COUNT];
    private long requestEpoch, serial, actorEpoch, resourceGeneration, publication;
    private int selected, effective, serverLimit;
    private boolean tuplePinned, paused;
    private String digest = "", planIdentity = "", failure;
    private long lastObservedNanos, activeNanos, lastUsefulNanos, lastPeerNanos, lastPeerStamp;
    private long clientObservation = -1, serverObservation = -1;

    public NativeDistanceProgressWatchdog(long episode, int initialSelected, long nowNanos,
                                           boolean paused, boolean checkPeer) {
        this(episode, initialSelected, nowNanos, paused, checkPeer, Limits.defaults());
    }

    public NativeDistanceProgressWatchdog(long episode, int initialSelected, long nowNanos,
                                           boolean paused, boolean checkPeer, Limits limits) {
        if (episode <= 0) throw new IllegalArgumentException("Positive episode required");
        validateSelection(initialSelected);
        this.episode = episode;
        this.selected = initialSelected;
        this.lastObservedNanos = nowNanos;
        this.paused = paused;
        this.checkPeer = checkPeer;
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Pins the producer identity but leaves the valid effective/server-limit tuple unpinned. */
    public boolean bindAttempt(long requestEpoch, long serial, int selected, long actorEpoch,
                               long resources, long publication, String digest, String planIdentity) {
        return bind(requestEpoch, serial, selected, 0, 0, false, actorEpoch, resources,
                publication, digest, planIdentity);
    }

    /** Pins the full selection tuple in addition to the producer identity. */
    public boolean bindAttempt(long requestEpoch, long serial, int selected, int effective,
                               int serverLimit, long actorEpoch, long resources, long publication,
                               String digest, String planIdentity) {
        return bind(requestEpoch, serial, selected, effective, serverLimit, true, actorEpoch,
                resources, publication, digest, planIdentity);
    }

    private boolean bind(long epoch, long nextSerial, int nextSelected, int nextEffective,
                         int nextServerLimit, boolean pinTuple, long nextActor, long nextResources,
                         long nextPublication, String nextDigest, String nextPlan) {
        if (expired()) return false;
        validateIdentity(epoch, nextSerial, nextSelected, nextEffective, nextServerLimit,
                nextActor, nextResources, nextPublication, nextDigest, nextPlan);
        if (requestEpoch != 0 && (requestEpoch != epoch || nextSerial < serial)) return false;
        if (requestEpoch != 0 && nextSerial == serial) {
            if (nextSelected != selected || nextActor < actorEpoch) return false;
            if (nextActor == actorEpoch && (nextResources < resourceGeneration || nextPublication < publication)) return false;
            if (selected == nextSelected && effective == nextEffective && serverLimit == nextServerLimit
                    && tuplePinned == pinTuple && actorEpoch == nextActor && resourceGeneration == nextResources
                    && publication == nextPublication && digest.equals(nextDigest) && planIdentity.equals(nextPlan)) return true;
        }
        requestEpoch = epoch;
        serial = nextSerial;
        selected = nextSelected;
        effective = nextEffective;
        serverLimit = nextServerLimit;
        tuplePinned = pinTuple;
        actorEpoch = nextActor;
        resourceGeneration = nextResources;
        publication = nextPublication;
        digest = nextDigest;
        planIdentity = nextPlan;
        Arrays.fill(raw, 0);
        clientObservation = serverObservation = -1;
        return true;
    }

    /**
     * Charges the interval using the previously observed pause state. Entering pause conservatively
     * charges the preceding interval; resuming does not charge a gap already known to be paused.
     * nanoTime may have a signed origin. Negative elapsed time or overflowing subtraction is invalid.
     */
    public void observe(long nowNanos, boolean actuallyPaused) {
        if (failed()) return;
        final long elapsed;
        try { elapsed = Math.subtractExact(nowNanos, lastObservedNanos); }
        catch (ArithmeticException overflow) { fail("clock-overflow"); return; }
        if (elapsed < 0) { fail("clock-regression"); return; }
        boolean wasPaused = paused;
        lastObservedNanos = nowNanos;
        paused = actuallyPaused;
        if (!wasPaused) advanceActiveNanos(elapsed);
        else expired();
    }

    /** Server-tick mode: the owner supplies only actual active-tick duration, never a wall-clock gap. */
    public void advanceActiveNanos(long delta) {
        if (failed()) return;
        if (delta < 0) { fail("negative-active-delta"); return; }
        try { activeNanos = Math.addExact(activeNanos, delta); }
        catch (ArithmeticException overflow) { fail("clock-overflow"); return; }
        expired();
    }

    /** Returns true for an accepted fresh report, including a heartbeat with no useful-work credit. */
    public boolean accept(NativeDistanceProgressPayload payload, long nowNanos, boolean actuallyPaused) {
        observe(nowNanos, actuallyPaused);
        return accept(payload);
    }

    /** Server-tick mode: accepts evidence after the owner advances the explicit active clock. */
    public boolean accept(NativeDistanceProgressPayload payload) {
        if (expired() || payload == null || !matches(payload)) return false;
        long previousObservation = payload.server() ? serverObservation : clientObservation;
        if (payload.observation() <= previousObservation) return false;
        long[] completed = payload.completed();
        // Validate the entire report before granting any progress or peer credit.
        for (Lane lane : LANES) {
            int index = lane.ordinal();
            if (lane.server() != payload.server()) {
                if (completed[index] != 0) { fail("wrong-progress-origin:" + lane); return false; }
            } else if (completed[index] < raw[index]) {
                fail("progress-rollback:" + lane);
                return false;
            }
        }
        if (payload.server()) serverObservation = payload.observation();
        else clientObservation = payload.observation();
        for (Lane lane : LANES) if (lane.server() == payload.server()) creditLane(lane, completed[lane.ordinal()]);
        if (payload.server()) observePeer(payload.peerTick());
        return !failed();
    }

    private boolean matches(NativeDistanceProgressPayload p) {
        return requestEpoch != 0 && p.episode() == episode && p.requestEpoch() == requestEpoch
                && p.serial() == serial && p.selected() == selected
                && (!tuplePinned || p.effective() == effective && p.serverLimit() == serverLimit)
                && p.actorEpoch() == actorEpoch && p.resourceGeneration() == resourceGeneration
                && p.publication() == publication && p.digest().equals(digest) && p.planIdentity().equals(planIdentity);
    }

    /** Returns true only when actual completed work exceeds this distance/lane's semantic high-water. */
    public boolean creditLane(Lane lane, long completed) {
        if (expired()) return false;
        Objects.requireNonNull(lane, "lane");
        int index = lane.ordinal();
        if (completed < 0 || completed > lane.cap()) { fail("progress-out-of-range:" + lane); return false; }
        if (completed < raw[index]) { fail("progress-rollback:" + lane); return false; }
        raw[index] = completed;
        if (completed <= credited[selected - MIN_SELECTED][index]) return false;
        credited[selected - MIN_SELECTED][index] = completed;
        lastUsefulNanos = activeNanos;
        return true;
    }

    /** Increasing authoritative server ticks renew only the independent peer deadline. */
    public void observePeer(long peerTick) {
        if (expired()) return;
        if (peerTick < 0) { fail("negative-peer-tick"); return; }
        if (peerTick > lastPeerStamp) {
            lastPeerStamp = peerTick;
            lastPeerNanos = activeNanos;
        }
    }

    public void fail(String reason) {
        if (failure == null) failure = reason == null || reason.isBlank() ? "failed" : reason;
    }
    public boolean failed() { return failure != null; }
    public String failure() { return failure; }
    public long episode() { return episode; }
    public long requestEpoch() { return requestEpoch; }
    public long serial() { return serial; }
    public int selected() { return selected; }
    public long activeNanos() { return activeNanos; }
    public long usefulIdleNanos() { return activeNanos - lastUsefulNanos; }
    public long peerIdleNanos() { return activeNanos - lastPeerNanos; }
    public long lastPeerStamp() { return lastPeerStamp; }
    public long[] rawVector() { return raw.clone(); }
    public long[][] creditedTable() { return copyTable(credited); }
    public long[] currentCredited() { return credited[selected - MIN_SELECTED].clone(); }

    public Checkpoint checkpoint() {
        return new Checkpoint(episode, requestEpoch, serial, selected, effective, serverLimit,
                actorEpoch, resourceGeneration, publication, digest, planIdentity, activeNanos,
                usefulIdleNanos(), peerIdleNanos(), lastPeerStamp, clientObservation,
                serverObservation, paused, failure, raw, credited);
    }

    private boolean expired() {
        if (failed()) return true;
        if (activeNanos >= limits.totalActiveNanos()) fail("episode-active-time-limit");
        else if (usefulIdleNanos() >= limits.usefulIdleNanos()) fail("useful-work-idle");
        else if (checkPeer && peerIdleNanos() >= limits.peerIdleNanos()) fail("peer-liveness-idle");
        return failed();
    }

    static void validateIdentity(long epoch, long serial, int selected, int effective, int serverLimit,
                                 long actorEpoch, long resources, long publication, String digest, String planIdentity) {
        validateSelection(selected);
        if (epoch <= 0 || serial <= 0 || effective < 0 || effective > selected || serverLimit < 0
                || serverLimit > MAX_SELECTED
                || effective != 0 && effective != (serverLimit > 0 ? Math.min(selected, serverLimit) : selected)
                || actorEpoch < 0 || resources < 0 || publication < 0)
            throw new IllegalArgumentException("Invalid exact native-distance progress identity");
        validateDigest(digest);
        validateDigest(planIdentity);
    }
    private static void validateSelection(int selected) {
        if (selected < MIN_SELECTED || selected > MAX_SELECTED)
            throw new IllegalArgumentException("Native distance must be in 2..32");
    }
    private static void validateDigest(String value) {
        if (value == null || !value.isEmpty() && !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Progress digest must be empty or 64 lowercase hexadecimal characters");
    }
    private static long[][] copyTable(long[][] table) {
        long[][] copy = new long[table.length][];
        for (int i = 0; i < table.length; i++) copy[i] = table[i].clone();
        return copy;
    }
}
