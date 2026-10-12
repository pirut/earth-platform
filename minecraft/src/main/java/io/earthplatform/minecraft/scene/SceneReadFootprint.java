package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Exact, bounded getter observations. Contains no cube payloads, GL owners, or model callbacks. */
final class SceneReadFootprint {
    static final int MAX_READS = 32_768;
    static final int MAX_OWNERS = 256;
    // Explicit accounting units, not a claim about VM-specific object layout or retained native storage.
    static final int ACCOUNTED_BYTES_PER_READ = 128;
    static final int ACCOUNTED_BYTES_PER_OWNER = 64;
    /** The supported renderer input envelope is exactly the owner's 27-cube halo, including diagonals. */
    static void requireHaloOwner(CubePos rendered, CubePos queried) {
        Objects.requireNonNull(rendered); Objects.requireNonNull(queried);
        // Widen before subtraction so extreme signed coordinates cannot wrap into the accepted halo.
        if (Math.abs((long) queried.x() - rendered.x()) > 1
                || Math.abs((long) queried.y() - rendered.y()) > 1
                || Math.abs((long) queried.z() - rendered.z()) > 1) {
            throw new SparseFacadeException("native model read outside the supported 27-cube halo: "
                    + queried + " for rendered owner " + rendered);
        }
    }
    enum Kind {
        STATE(true), FLUID(true), ENTITY(true), MODEL_DATA(true), LIGHT(true), RAW_LIGHT(true), TINT(true),
        SHADE(false), NORMAL_SHADE(false), HEIGHT(false), MIN_HEIGHT(false), MAX_LIGHT(false);
        final boolean positional;
        Kind(boolean positional) { this.positional = positional; }
    }
    /** Full signed XYZ, never the 12-bit native packed Y. For normal shade XYZ are raw float bits. */
    record Query(Kind kind, int x, int y, int z, int argument) {
        Query { Objects.requireNonNull(kind); }
        CubePos owner() { return kind.positional ? CubePos.fromBlock(x, y, z) : null; }
    }
    /** Identity values cover immutable native states; scalars compare all bits, including float shade. */
    record Value(Object identity, long scalar) {
        static Value identity(Object value) { return new Value(value, 0); }
        static Value scalar(long value) { return new Value(null, value); }
        boolean same(Value other) { return other != null && identity == other.identity && scalar == other.scalar; }
    }
    private record Sample(Query query, Value value) {}
    record Validation(boolean matches, int compared) {}

    static final class Recorder {
        private final Thread owner = Thread.currentThread();
        private final int maxReads;
        private final Map<Query, Value> values = new LinkedHashMap<>();
        private boolean invalid, sealed, overflow;
        Recorder() { this(MAX_READS); }
        Recorder(int maxReads) {
            if (maxReads < 1 || maxReads > MAX_READS) throw new IllegalArgumentException("Invalid footprint budget");
            this.maxReads = maxReads;
        }
        void record(Query query, Value value) {
            checkOpen();
            Objects.requireNonNull(query); Objects.requireNonNull(value);
            if (overflow) return;
            Value old = values.get(query);
            if (old != null) {
                if (!old.same(value)) { invalid = true; throw new IllegalStateException("Immutable getter changed during compilation"); }
                return;
            }
            if (values.size() == maxReads) { overflow = true; values.clear(); return; }
            values.put(query, value);
        }
        void invalidate() { checkOwner(); invalid = true; }
        void requireValid() {
            checkOwner();
            if (invalid) throw new IllegalStateException("Scene getter footprint contains an unknown or unsupported read");
        }
        SceneReadFootprint seal() {
            checkOpen();
            var result = new SceneReadFootprint(values, !overflow);
            sealed = true; values.clear();
            return result;
        }
        void cancel() { checkOwner(); invalid = true; values.clear(); }
        int size() { checkOwner(); return values.size(); }
        long accountingBytes() { return (long) size() * ACCOUNTED_BYTES_PER_READ; }
        private void checkOpen() {
            requireValid();
            if (sealed) { invalid = true; throw new IllegalStateException("Scene getter footprint already sealed"); }
        }
        private void checkOwner() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Scene reads require their render owner thread");
        }
    }

    private final Map<CubePos, List<Sample>> byOwner;
    private final List<Sample> globals;
    private final int reads, positions;
    private final boolean reusable;
    private SceneReadFootprint(Map<Query, Value> values, boolean reusable) {
        this.reusable = reusable;
        Map<CubePos, List<Sample>> grouped = new LinkedHashMap<>();
        List<Sample> global = new ArrayList<>();
        var positions = new java.util.HashSet<Position>();
        for (var entry : values.entrySet()) {
            var query = entry.getKey();
            var sample = new Sample(query, entry.getValue());
            if (query.kind.positional) {
                grouped.computeIfAbsent(query.owner(), ignored -> new ArrayList<>()).add(sample);
                positions.add(new Position(query.x, query.y, query.z));
            } else global.add(sample);
        }
        if (grouped.size() > MAX_OWNERS) throw new IllegalStateException("Scene footprint owner budget exceeded");
        grouped.replaceAll((ignored, samples) -> List.copyOf(samples));
        byOwner = Map.copyOf(grouped); globals = List.copyOf(global);
        reads = values.size(); this.positions = positions.size();
    }
    private record Position(int x, int y, int z) {}
    /** Only changed owners are replayed. Global facade observations are cheap and always rechecked. */
    Validation validate(Set<CubePos> changedOwners, Function<Query, Value> read) {
        Objects.requireNonNull(changedOwners); Objects.requireNonNull(read);
        if (!reusable) return new Validation(false, 0);
        int compared = 0;
        for (var sample : globals) {
            compared++;
            if (!sample.value.same(read.apply(sample.query))) return new Validation(false, compared);
        }
        for (var owner : changedOwners) {
            for (var sample : byOwner.getOrDefault(owner, List.of())) {
                compared++;
                if (!sample.value.same(read.apply(sample.query))) return new Validation(false, compared);
            }
        }
        return new Validation(true, compared);
    }
    boolean reusable() { return reusable; }
    int readCount() { return reads; }
    int positionCount() { return positions; }
    int ownerCount() { return byOwner.size(); }
    Set<CubePos> owners() { return byOwner.keySet(); }
    long accountingBytes() { return (long) reads * ACCOUNTED_BYTES_PER_READ + (long) byOwner.size() * ACCOUNTED_BYTES_PER_OWNER; }
}
