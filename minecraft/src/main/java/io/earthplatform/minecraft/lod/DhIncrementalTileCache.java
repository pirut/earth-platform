package io.earthplatform.minecraft.lod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Owner-thread, incremental resource transactions, independent of GL and world identity.
 * A caller supplies immutable keys/faces including any required generation stamps, and
 * owns the cross-thread invalidation and draw fence. It also captures/restores graphics
 * state around resource callbacks. A staged transaction never mutates published resources.
 */
public final class DhIncrementalTileCache<K, F, R> implements AutoCloseable {
    public static final int MAX_TILES = 64;
    public static final int MAX_RESIDENT_OWNERS = 128;

    /**
     * upload transfers one fresh, exclusively owned resource only on successful return.
     * Its factory owns cleanup of allocations made before throwing. bytes must include all
     * owned allocations and remain constant until deletion. close may be retried; deleted
     * confirms complete deletion. Estimates are used before allocating, then checked
     * against actual bytes. Resource identities, never resource equality, define ownership.
     */
    public interface ResourceOps<K, F, R> {
        long estimateBytes(List<F> faces);
        R upload(K key, List<F> faces);
        long bytes(R resource);
        void close(R resource);
        boolean deleted(R resource);
    }

    public enum Blocked {
        NONE, BYTE_BUDGET, OWNER_BUDGET, TIME_BUDGET, RESIDENT_BYTES, RESIDENT_OWNERS
    }

    public record PumpResult(int uploadedOwners, long uploadedBytes, boolean ready, Blocked blocked) {}

    private final Thread ownerThread = Thread.currentThread();
    private final ResourceOps<K, F, R> ops;
    private final long maxResidentBytes;
    private final int maxTiles, maxResidentOwners;
    private final LongSupplier nanoTime;
    private final boolean canonicalCurrentPayload;
    private final IdentityHashMap<R, Owned> owners = new IdentityHashMap<>();
    private final Set<R> retired = Collections.newSetFromMap(new IdentityHashMap<>());
    private Map<K, Tile> current = Map.of();
    private Map<K, R> currentResources = Map.of();
    private Map<K, List<F>> currentPayloads = Map.of();
    private Stage staging;
    private long lastStageId = -1, committedTransactionId = -1;
    private long uploadedOwners, uploadedBytes;
    private int lastStageChangedOwners, lastStageReusedOwners;
    private boolean closed, fenced, busy;

    private final class Owned {
        final R resource;
        long bytes;
        Owned(R resource, long bytes) { this.resource = resource; this.bytes = bytes; }
    }

    private final class Tile {
        final List<F> faces;
        final Owned owner;
        Tile(List<F> faces, Owned owner) { this.faces = faces; this.owner = owner; }
    }

    private final class Stage {
        final long id;
        final Map<K, List<F>> payloads;
        final Map<K, Tile> candidates = new LinkedHashMap<>();
        final ArrayDeque<K> pending = new ArrayDeque<>();
        final List<Owned> privateOwners = new ArrayList<>();
        Stage(long id, Map<K, List<F>> payloads) { this.id = id; this.payloads = payloads; }
    }

    public DhIncrementalTileCache(ResourceOps<K, F, R> ops, long maxResidentBytes) {
        this(ops, maxResidentBytes, MAX_TILES, MAX_RESIDENT_OWNERS, System::nanoTime);
    }

    // Smaller limits and a deterministic clock exercise admission without huge allocations.
    DhIncrementalTileCache(ResourceOps<K, F, R> ops, long maxResidentBytes,
                           int maxTiles, int maxResidentOwners, LongSupplier nanoTime) {
        this(ops,maxResidentBytes,maxTiles,maxResidentOwners,nanoTime,false);
    }
    /** Additive explicitly sized native-distance profile; legacy callers keep original maxima. */
    DhIncrementalTileCache(ResourceOps<K,F,R> ops,long maxResidentBytes,int maxTiles,int maxResidentOwners,
                           LongSupplier nanoTime,boolean distanceProfile) {
        this.canonicalCurrentPayload=distanceProfile;
        this.ops = Objects.requireNonNull(ops, "ops");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (maxResidentBytes < 0 || maxTiles < 1 || maxTiles > (distanceProfile?256:MAX_TILES)
                || maxResidentOwners < 1 || maxResidentOwners > (distanceProfile?512:MAX_RESIDENT_OWNERS)) {
            throw new IllegalArgumentException("Invalid tile cache bounds");
        }
        this.maxResidentBytes = maxResidentBytes;
        this.maxTiles = maxTiles;
        this.maxResidentOwners = maxResidentOwners;
    }

    /**
     * Takes immutable snapshots of the map and every face list. Faces and keys themselves
     * must be immutable. A newer stage supersedes the old private transaction. An accepted
     * ID is consumed even if that transaction is later cancelled or its upload fails.
     * False means a stale/repeated ID only; malformed input and operational faults throw.
     */
    public boolean stage(long transactionId, Map<K, List<F>> payloads) {
        beginMutation();
        try {
            if (transactionId < 0) throw new IllegalArgumentException("Negative transaction ID");
            if (transactionId <= lastStageId) return false;
            Objects.requireNonNull(payloads, "payloads");
            var snapshot = new LinkedHashMap<K, List<F>>();
            for (var entry : payloads.entrySet()) {
                if (snapshot.size() == maxTiles) throw new IllegalArgumentException("Too many tile keys");
                K key = Objects.requireNonNull(entry.getKey(), "tile key");
                List<F> faces = List.copyOf(Objects.requireNonNull(entry.getValue(), "faces"));
                if (snapshot.put(key, faces) != null) throw new IllegalArgumentException("Duplicate tile key");
            }
            var next = new Stage(transactionId, immutable(snapshot));
            int reused = 0;
            for (var entry : snapshot.entrySet()) {
                Tile previous = current.get(entry.getKey());
                if (previous != null && previous.faces.equals(entry.getValue())) {
                    // Reuse the physical GPU owner, not a prior generation of equal CPU face objects.
                    next.candidates.put(entry.getKey(), canonicalCurrentPayload?new Tile(entry.getValue(),previous.owner):previous);
                    reused++;
                } else {
                    next.pending.add(entry.getKey());
                }
            }
            cancelStageInternal();
            staging = next;
            lastStageId = transactionId;
            lastStageReusedOwners = reused;
            lastStageChangedOwners = snapshot.size() - reused;
            return true;
        } finally {
            busy = false;
        }
    }

    public boolean ready(long transactionId) {
        checkOwner();
        return !closed && !fenced && staging != null && staging.id == transactionId && staging.pending.isEmpty();
    }

    /**
     * Admits uploads up to all three budgets; an upload is synchronous and cannot be
     * preempted, so maxNanos bounds admission, not the duration of a single callback.
     * Current plus private plus failed-retirement owners all count against resident caps.
     * Any operational failure rolls back the entire private stage and is rethrown.
     */
    public PumpResult pump(long maxBytes, int maxOwners, long maxNanos) {
        beginMutation();
        try {
            if (maxBytes < 0 || maxOwners < 0 || maxNanos < 0) {
                throw new IllegalArgumentException("Negative pump budget");
            }
            if (staging == null) return new PumpResult(0, 0, false, Blocked.NONE);
            int count = 0;
            long bytes = 0;
            long start = nanoTime.getAsLong();
            while (!staging.pending.isEmpty()) {
                if (count == maxOwners) return result(count, bytes, Blocked.OWNER_BUDGET);
                if (nanoTime.getAsLong() - start >= maxNanos) return result(count, bytes, Blocked.TIME_BUDGET);
                if (owners.size() >= maxResidentOwners) return result(count, bytes, Blocked.RESIDENT_OWNERS);
                K key = staging.pending.peek();
                List<F> faces = staging.payloads.get(key);
                long estimate;
                try {
                    estimate = ops.estimateBytes(faces);
                    if (estimate < 0) throw new IllegalStateException("Negative resource estimate");
                } catch (RuntimeException | Error failure) {
                    rollback(failure);
                    throw failure;
                }
                if (estimate > maxBytes - bytes) return result(count, bytes, Blocked.BYTE_BUDGET);
                long resident = residentBytesInternal();
                if (resident > maxResidentBytes || estimate > maxResidentBytes - resident) {
                    return result(count, bytes, Blocked.RESIDENT_BYTES);
                }
                if (nanoTime.getAsLong() - start >= maxNanos) return result(count, bytes, Blocked.TIME_BUDGET);
                try {
                    R resource = Objects.requireNonNull(ops.upload(key, faces), "Uploaded resource");
                    // An aliased return never transfers a second ownership, even if equals differs.
                    if (owners.containsKey(resource)) throw new IllegalStateException("Upload returned an already owned resource");
                    var owned = new Owned(resource, maxResidentBytes);
                    owners.put(resource, owned);
                    staging.privateOwners.add(owned);
                    long actual = ops.bytes(resource);
                    if (actual < 0) throw new IllegalStateException("Negative resource byte count");
                    owned.bytes = actual;
                    if (ops.deleted(resource)) throw new IllegalStateException("Upload returned a deleted resource");
                    if (actual > maxBytes - bytes || actual > maxResidentBytes - resident) {
                        throw new IllegalStateException("Uploaded resource exceeds admitted byte budget");
                    }
                    staging.candidates.put(key, new Tile(faces, owned));
                    staging.pending.remove();
                    count++;
                    bytes += actual;
                    uploadedOwners = saturatedAdd(uploadedOwners, 1);
                    uploadedBytes = saturatedAdd(uploadedBytes, actual);
                } catch (RuntimeException | Error failure) {
                    rollback(failure);
                    throw failure;
                }
            }
            return result(count, bytes, Blocked.NONE);
        } finally {
            busy = false;
        }
    }

    /**
     * Publishes the complete immutable map in one owner-thread step, then retires replaced
     * owners. A retirement fault occurs AFTER publication: it fences further transactions,
     * retains every undeleted resource for close/retry, and throws rather than reporting false.
     */
    public boolean commit(long transactionId) {
        beginMutation();
        try {
            if (staging == null || staging.id != transactionId || !staging.pending.isEmpty()) return false;
            var nextTiles = new LinkedHashMap<K, Tile>();
            var nextResources = new LinkedHashMap<K, R>();
            Set<R> retained = Collections.newSetFromMap(new IdentityHashMap<>());
            for (K key : staging.payloads.keySet()) {
                Tile tile = staging.candidates.get(key);
                nextTiles.put(key, tile);
                nextResources.put(key, tile.owner.resource);
                retained.add(tile.owner.resource);
            }
            Map<K, Tile> previous = current;
            current = immutable(nextTiles);
            currentResources = immutable(nextResources);
            currentPayloads = staging.payloads;
            committedTransactionId = transactionId;
            staging = null;
            for (Tile old : previous.values()) {
                if (!retained.contains(old.owner.resource)) retired.add(old.owner.resource);
            }
            Throwable failure = cleanRetired();
            if (failure != null) {
                fenced = true;
                rethrow(failure);
            }
            return true;
        } finally {
            busy = false;
        }
    }

    public void cancelStage() {
        beginMutation();
        try { cancelStageInternal(); } finally { busy = false; }
    }

    private void cancelStageInternal() {
        if (staging == null) return;
        for (Owned owned : staging.privateOwners) retired.add(owned.resource);
        staging = null;
        rethrow(cleanRetired());
    }

    private void rollback(Throwable original) {
        if (staging != null) {
            for (Owned owned : staging.privateOwners) retired.add(owned.resource);
            staging = null;
        }
        Throwable cleanup = cleanRetired();
        if (cleanup != null && cleanup != original) original.addSuppressed(cleanup);
    }

    /** Retries all retained retirements; a successful retry does not remove a commit fence. */
    public void retryRetirements() {
        checkOwner();
        if (busy) throw new IllegalStateException("Reentrant resource operation");
        busy = true;
        try { rethrow(cleanRetired()); } finally { busy = false; }
    }

    /** All owners are attempted even when one close/deletion check fails. */
    private Throwable cleanRetired() {
        Throwable failure = null;
        for (R resource : new ArrayList<>(retired)) {
            boolean deleted = false;
            try {
                deleted = ops.deleted(resource);
            } catch (RuntimeException | Error error) {
                failure = append(failure, error);
            }
            if (!deleted) {
                try { ops.close(resource); } catch (RuntimeException | Error error) { failure = append(failure, error); }
                try {
                    deleted = ops.deleted(resource);
                    if (!deleted) failure = append(failure, new IllegalStateException("Resource close did not confirm deletion"));
                } catch (RuntimeException | Error error) {
                    failure = append(failure, error);
                }
            }
            if (deleted) {
                owners.remove(resource);
                retired.remove(resource);
            }
        }
        return failure;
    }

    @Override public void close() {
        checkOwner();
        if (busy) throw new IllegalStateException("Reentrant resource operation");
        busy = true;
        try {
            closed = true;
            fenced = true;
            staging = null;
            current = Map.of();
            currentResources = Map.of();
            currentPayloads = Map.of();
            retired.addAll(owners.keySet());
            rethrow(cleanRetired());
        } finally {
            busy = false;
        }
    }

    /** Immutable published snapshot; callers must respect fenced() before drawing. */
    public Map<K, R> currentResources() { checkOwner(); return currentResources; }
    public Map<K, List<F>> currentPayloads() { checkOwner(); return currentPayloads; }
    public long committedTransactionId() { checkOwner(); return committedTransactionId; }
    public int lastStageChangedOwners() { checkOwner(); return lastStageChangedOwners; }
    public int lastStageReusedOwners() { checkOwner(); return lastStageReusedOwners; }
    public long uploadedOwners() { checkOwner(); return uploadedOwners; }
    public long uploadedBytes() { checkOwner(); return uploadedBytes; }
    public int residentOwners() { checkOwner(); return owners.size(); }
    public int retirementPending() { checkOwner(); return retired.size(); }
    public boolean fenced() { checkOwner(); return fenced; }
    public boolean closed() { checkOwner(); return closed; }
    public boolean deleted() { checkOwner(); return owners.isEmpty(); }
    public long residentBytes() { checkOwner(); return residentBytesInternal(); }
    public long currentBytes() {
        checkOwner();
        long bytes = 0;
        for (Tile tile : current.values()) bytes = saturatedAdd(bytes, tile.owner.bytes);
        return bytes;
    }

    private long residentBytesInternal() {
        long bytes = 0;
        for (Owned owned : owners.values()) bytes = saturatedAdd(bytes, owned.bytes);
        return bytes;
    }

    private PumpResult result(int count, long bytes, Blocked blocked) {
        return new PumpResult(count, bytes, staging != null && staging.pending.isEmpty(), blocked);
    }

    private void beginMutation() {
        checkOwner();
        if (busy) throw new IllegalStateException("Reentrant resource operation");
        if (closed || fenced) throw new IllegalStateException("Resource cache is closed or fenced");
        busy = true;
    }

    private void checkOwner() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Tile cache requires its owner thread");
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }
}
