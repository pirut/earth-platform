package io.earthplatform.streaming;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, synchronized metadata gate for a single world and connection epoch.
 * This class owns neither packet payloads nor Minecraft objects. All mutations are
 * linearized by this object's monitor. See README.md for the external install/unload contract.
 */
public final class CubeStreamAdmission {
    /** Hard limit on the caller-configurable metadata budget, not a recommended render distance. */
    public static final int MAX_LEASES = 65_536;

    public enum State { OPEN, FAILED, CLOSED }
    public enum Lifecycle { REQUESTED, LOADED }
    public enum Rejection {
        NONE, NOT_OPEN, WRONG_WORLD, WRONG_EPOCH, UNSOLICITED_POSITION,
        RETIRED_LEASE, STALE_REVISION, SUPERSEDED_TOKEN, FOREIGN_TOKEN
    }

    /** A positive generation is unique across all new leases within this gate. */
    public record Lease(CubePos position, long generation) {
        public Lease {
            Objects.requireNonNull(position, "position");
            if (generation <= 0) throw new IllegalArgumentException("Positive lease generation required");
        }
    }

    /** Metadata must come from a fully verified, bounded packet. CRC is not authentication. */
    public record Header(WorldIdentity world, long connectionEpoch, CubePos position,
                         long leaseGeneration, long revision) {
        public Header {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(position, "position");
            if (connectionEpoch < 0 || leaseGeneration <= 0 || revision < 0) {
                throw new IllegalArgumentException("Nonnegative epoch/revision and positive lease generation required");
            }
        }
    }

    /** -1 means no revision yet. Views are immutable snapshots, not live handles. */
    public record LeaseView(Lease lease, Lifecycle lifecycle, long appliedRevision, long pendingRevision) { }

    /** Bounded caller-owned output; the gate retains no retired leases or previous changes. */
    public record InterestChange(List<Lease> requested, List<LeaseView> retired) {
        public InterestChange {
            requested = List.copyOf(requested);
            retired = List.copyOf(retired);
        }
    }

    /** Opaque, single-reservation capability. Does not reference the gate or hold any payload. */
    public static final class AdmissionToken {
        private final Object owner;
        private final Header header;
        private AdmissionToken(Object owner, Header header) { this.owner = owner; this.header = header; }
        public Header header() { return header; }
    }

    /** Exactly one of an admitted token and a rejection is present. */
    public record Admission(Rejection rejection, AdmissionToken token) {
        public Admission {
            Objects.requireNonNull(rejection, "rejection");
            if ((rejection == Rejection.NONE) != (token != null)) {
                throw new IllegalArgumentException("Token must be present exactly when admitted");
            }
        }
        public boolean accepted() { return rejection == Rejection.NONE; }
    }

    /** Counters saturate at Long.MAX_VALUE; gauges are bounded by maxLeases. */
    public record Metrics(int activeLeases, int loadedLeases, int pendingAdmissions,
                          long replacements, long leasesCreated, long leasesRetired,
                          long admissions, long supersessions, long commits, long cancellations,
                          long admissionRejections, long commitRejections, long installerFailures) { }

    public record View(State state, List<LeaseView> leases, Metrics metrics) {
        public View { leases = List.copyOf(leases); }
    }

    private static final class Entry {
        final Lease lease;
        long applied = -1;
        AdmissionToken pending;
        Entry(Lease lease) { this.lease = lease; }
        LeaseView view() {
            return new LeaseView(lease, applied < 0 ? Lifecycle.REQUESTED : Lifecycle.LOADED,
                    applied, pending == null ? -1 : pending.header.revision());
        }
    }

    private final WorldIdentity world;
    private final long connectionEpoch;
    private final int maxLeases;
    private final Object owner = new Object();
    private Map<CubePos, Entry> entries = new LinkedHashMap<>();
    private State state = State.OPEN;
    private boolean mutating;
    private long lastGeneration;
    private long replacements, leasesCreated, leasesRetired, admissions, supersessions;
    private long commits, cancellations, admissionRejections, commitRejections, installerFailures;

    public CubeStreamAdmission(WorldIdentity world, long connectionEpoch, int maxLeases) {
        this(world, connectionEpoch, maxLeases, 0);
    }

    /**
     * Constructs a fresh stream after a separately verified bounded bootstrap consumed retired
     * lease identities. This cannot rebase any live gate; the caller must bind the exact floor to
     * its authenticated initial protocol. No snapshots or ownership are imported.
     */
    public static CubeStreamAdmission afterVerifiedBootstrap(WorldIdentity world, long connectionEpoch,
            int maxLeases, long lastBootstrapGeneration) {
        if(lastBootstrapGeneration<=0||lastBootstrapGeneration>MAX_LEASES)
            throw new IllegalArgumentException("Bounded positive bootstrap lease floor required");
        return new CubeStreamAdmission(world,connectionEpoch,maxLeases,lastBootstrapGeneration);
    }

    // Package-private exhaustion test seam; never exposes a way to rewind a live gate.
    CubeStreamAdmission(WorldIdentity world, long connectionEpoch, int maxLeases, long lastGeneration) {
        this.world = Objects.requireNonNull(world, "world");
        if (connectionEpoch < 0 || lastGeneration < 0) throw new IllegalArgumentException("Negative epoch/generation");
        if (maxLeases < 1 || maxLeases > MAX_LEASES) throw new IllegalArgumentException("Invalid lease budget");
        this.connectionEpoch = connectionEpoch;
        this.maxLeases = maxLeases;
        this.lastGeneration = lastGeneration;
    }

    public WorldIdentity world() { return world; }
    public long connectionEpoch() { return connectionEpoch; }
    public int maxLeases() { return maxLeases; }

    /** Allocation-free currentness check for render dependencies; never exposes the mutable entry. */
    public synchronized boolean isCurrentLoaded(Header header) {
        Objects.requireNonNull(header, "header");
        if (state != State.OPEN || !world.equals(header.world()) || connectionEpoch != header.connectionEpoch()) return false;
        Entry entry = entries.get(header.position());
        return entry != null && entry.applied >= 0 && entry.lease.generation() == header.leaseGeneration()
                && entry.applied == header.revision();
    }

    /**
     * Allocation-free identity/liveness check for an explicitly retained immutable presentation.
     * A newer committed revision is allowed, but never a requested-only, retired, or re-entered lease.
     * This is not a current-revision, render-readiness, collision, or edit admission check.
     */
    public synchronized boolean isCurrentLoadedLease(Header header) {
        Objects.requireNonNull(header, "header");
        if (state != State.OPEN || !world.equals(header.world()) || connectionEpoch != header.connectionEpoch()) return false;
        Entry entry = entries.get(header.position());
        return entry != null && entry.applied >= header.revision()
                && entry.lease.generation() == header.leaseGeneration();
    }

    /**
     * Atomically replace the entire desired set. Retained positions keep their generation,
     * applied revision and pending reservation. Duplicates, nulls, over-budget input and
     * generation exhaustion throw without changing any state or counter.
     * Traversal stops after at most maxLeases elements plus one hasNext check.
     */
    public synchronized InterestChange replaceInterest(Collection<CubePos> positions) {
        beginMutation();
        try {
            requireOpen();
            Objects.requireNonNull(positions, "positions");
            Map<CubePos, Boolean> desired = new LinkedHashMap<>();
            int additions = 0;
            Iterator<CubePos> iterator = positions.iterator();
            while (iterator.hasNext()) {
                if (desired.size() == maxLeases) throw new IllegalArgumentException("Interest exceeds lease budget");
                CubePos position = iterator.next();
                Objects.requireNonNull(position, "position");
                if (desired.put(position, Boolean.TRUE) != null) throw new IllegalArgumentException("Duplicate position");
                if (!entries.containsKey(position)) additions++;
            }
            if (additions > Long.MAX_VALUE - lastGeneration) {
                throw new IllegalStateException("Lease generation exhausted; use a fresh connection epoch");
            }
            Map<CubePos, Entry> next = new LinkedHashMap<>();
            List<Lease> requested = new ArrayList<>(additions);
            List<LeaseView> retired = new ArrayList<>();
            long generation = lastGeneration;
            for (CubePos position : desired.keySet()) {
                Entry entry = entries.get(position);
                if (entry == null) {
                    entry = new Entry(new Lease(position, ++generation));
                    requested.add(entry.lease);
                }
                next.put(position, entry);
            }
            for (Entry entry : entries.values()) {
                if (!desired.containsKey(entry.lease.position())) retired.add(entry.view());
            }
            // All caller code, validation and output construction precede publication.
            InterestChange change = new InterestChange(requested, retired);
            entries = next;
            lastGeneration = generation;
            replacements = saturatedAdd(replacements, 1);
            leasesCreated = saturatedAdd(leasesCreated, requested.size());
            leasesRetired = saturatedAdd(leasesRetired, retired.size());
            return change;
        } finally { endMutation(); }
    }

    /** Reserve one revision; a newer reservation supersedes the previous token for that lease. */
    public synchronized Admission admit(Header header) {
        beginMutation();
        try {
            Objects.requireNonNull(header, "header");
            Rejection rejection = validate(header);
            Entry entry = entries.get(header.position());
            if (rejection == Rejection.NONE &&
                    (header.revision() <= entry.applied ||
                     (entry.pending != null && header.revision() <= entry.pending.header.revision()))) {
                rejection = Rejection.STALE_REVISION;
            }
            if (rejection != Rejection.NONE) {
                admissionRejections = saturatedAdd(admissionRejections, 1);
                return new Admission(rejection, null);
            }
            AdmissionToken token = new AdmissionToken(owner, header);
            Admission result = new Admission(Rejection.NONE, token);
            if (entry.pending != null) supersessions = saturatedAdd(supersessions, 1);
            entry.pending = token;
            admissions = saturatedAdd(admissions, 1);
            return result;
        } finally { endMutation(); }
    }

    /**
     * Revalidate and install synchronously under the same monitor as lease retirement.
     * The installer must atomically install a lease-tagged value on its correct owner thread.
     * It must be short and must not wait for other threads to call this gate. Reads from an
     * installer are allowed (and see pre-commit metadata); reentrant mutations are rejected.
     * Any thrown exception or error poisons the gate and invalidates every pending token.
     * External side effects are not rolled back. Call close(), then clean up all returned leases.
     */
    public synchronized Rejection commit(AdmissionToken token, Runnable installer) {
        beginMutation();
        try {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(installer, "installer");
            Rejection rejection = validateToken(token);
            if (rejection != Rejection.NONE) {
                commitRejections = saturatedAdd(commitRejections, 1);
                return rejection;
            }
            Entry entry = entries.get(token.header.position());
            try {
                installer.run();
            } catch (Throwable failure) {
                state = State.FAILED;
                for (Entry active : entries.values()) active.pending = null;
                installerFailures = saturatedAdd(installerFailures, 1);
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                // A malicious Runnable can throw a checked exception through a generic cast.
                throw new IllegalStateException("Installer threw an undeclared checked exception", failure);
            }
            entry.applied = token.header.revision();
            entry.pending = null;
            commits = saturatedAdd(commits, 1);
            return Rejection.NONE;
        } finally { endMutation(); }
    }

    /**
     * Withdraw only the exact current token. Allows retry above the last committed revision.
     * An old/superseded token can neither commit nor cancel a newer reservation.
     */
    public synchronized boolean cancel(AdmissionToken token) {
        beginMutation();
        try {
            Objects.requireNonNull(token, "token");
            if (validateToken(token) != Rejection.NONE) return false;
            entries.get(token.header.position()).pending = null;
            cancellations = saturatedAdd(cancellations, 1);
            return true;
        } finally { endMutation(); }
    }

    /** Retire all leases, including potentially partially installed values after failure. Idempotent. */
    public synchronized InterestChange close() {
        beginMutation();
        try {
            List<LeaseView> retired = new ArrayList<>(entries.size());
            for (Entry entry : entries.values()) retired.add(entry.view());
            InterestChange change = new InterestChange(List.of(), retired);
            entries.clear();
            state = State.CLOSED;
            leasesRetired = saturatedAdd(leasesRetired, retired.size());
            return change;
        } finally { endMutation(); }
    }

    public synchronized View view() {
        List<LeaseView> leases = new ArrayList<>(entries.size());
        int loaded = 0, pending = 0;
        for (Entry entry : entries.values()) {
            leases.add(entry.view());
            if (entry.applied >= 0) loaded++;
            if (entry.pending != null) pending++;
        }
        return new View(state, leases, new Metrics(entries.size(), loaded, pending,
                replacements, leasesCreated, leasesRetired, admissions, supersessions, commits,
                cancellations, admissionRejections, commitRejections, installerFailures));
    }

    private Rejection validate(Header header) {
        if (state != State.OPEN) return Rejection.NOT_OPEN;
        if (!world.equals(header.world())) return Rejection.WRONG_WORLD;
        if (connectionEpoch != header.connectionEpoch()) return Rejection.WRONG_EPOCH;
        Entry entry = entries.get(header.position());
        if (entry == null) return Rejection.UNSOLICITED_POSITION;
        if (entry.lease.generation() != header.leaseGeneration()) return Rejection.RETIRED_LEASE;
        return Rejection.NONE;
    }

    private Rejection validateToken(AdmissionToken token) {
        if (token.owner != owner) return Rejection.FOREIGN_TOKEN;
        Rejection rejection = validate(token.header);
        if (rejection != Rejection.NONE) return rejection;
        Entry entry = entries.get(token.header.position());
        if (entry.pending != token) return Rejection.SUPERSEDED_TOKEN;
        if (token.header.revision() <= entry.applied) return Rejection.STALE_REVISION;
        return Rejection.NONE;
    }

    private void beginMutation() {
        if (mutating) throw new IllegalStateException("Reentrant admission mutation is forbidden");
        mutating = true;
    }
    private void endMutation() { mutating = false; }
    private void requireOpen() {
        if (state != State.OPEN) throw new IllegalStateException("Admission gate is " + state);
    }
    static long saturatedAdd(long value, long amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }
}
