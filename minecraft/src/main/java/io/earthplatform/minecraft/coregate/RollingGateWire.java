package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeLease;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeVersion;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageMetadata;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageReady;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Bounded rolling-stage bodies. World, connection epoch, profile and packet kind belong to the
 * outer envelope. Readers consume precisely one body; the enclosing handler must reject trailing
 * bytes. There are no native buffers or unbounded collections retained by these helpers.
 */
public final class RollingGateWire {
    public static final int WIRE_VERSION = 1;
    public static final int MAX_OWNERS = RollingCubePlan.MAX_COMMITTED_OWNERS;
    public static final int MAX_UNION = RollingCubePlan.MAX_STAGED_OWNERS;
    public static final int DIGEST_LENGTH = 64;
    private RollingGateWire() {}

    public static void writeMetadata(FriendlyByteBuf b, StageMetadata m) {
        validateMetadata(m);
        b.writeVarInt(WIRE_VERSION); b.writeLong(m.generation()); b.writeBoolean(m.initial());
        CoreGatePayload.position(b, m.pinCenter()); CoreGatePayload.position(b, m.currentCenter());
        CoreGatePayload.position(b, m.candidateCenter());
        writeLeases(b, m.currentLeases()); writeLeases(b, m.candidateLeases());
        writePositions(b, m.newPositions()); writePositions(b, m.retiredPositions());
        writePositions(b, m.renderTargets());
    }

    public static StageMetadata readMetadata(FriendlyByteBuf b, WorldIdentity world, long epoch) {
        requireVersion(b);
        long generation = b.readLong();
        int initial = b.readUnsignedByte();
        if (initial > 1) throw new IllegalArgumentException("Noncanonical rolling initial flag");
        CubePos pin = CoreGatePayload.position(b), current = CoreGatePayload.position(b), candidate = CoreGatePayload.position(b);
        StageMetadata result = new StageMetadata(world, epoch, generation, initial == 1, pin, current, candidate,
                readLeases(b), readLeases(b), readPositions(b, MAX_OWNERS), readPositions(b, MAX_OWNERS),
                readPositions(b, RollingCubePlan.WINDOW_TARGETS));
        validateMetadata(result);
        return result;
    }

    public static void writeReady(FriendlyByteBuf b, StageReady ready) {
        validateReady(ready);
        b.writeVarInt(WIRE_VERSION); b.writeLong(ready.generation()); CoreGatePayload.position(b, ready.candidateCenter());
        b.writeVarInt(ready.versions().size());
        for (CubeVersion version : ready.versions()) {
            CoreGatePayload.position(b, version.position()); b.writeLong(version.leaseGeneration()); b.writeLong(version.revision());
        }
    }

    public static StageReady readReady(FriendlyByteBuf b, WorldIdentity world, long epoch) {
        requireVersion(b);
        long generation = b.readLong(); CubePos center = CoreGatePayload.position(b);
        int count = readCount(b, MAX_OWNERS, 28);
        List<CubeVersion> versions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) versions.add(new CubeVersion(CoreGatePayload.position(b), b.readLong(), b.readLong()));
        StageReady result = new StageReady(world, epoch, generation, center, versions);
        validateReady(result);
        return result;
    }

    /** Validates exact canonical geometry before admission can mutate any live lease. */
    public static void validateMetadata(StageMetadata m) {
        Objects.requireNonNull(m, "metadata");
        validateWorld(m.world());
        if (m.epoch() < 0 || m.generation() <= 0) throw new IllegalArgumentException("Invalid rolling identity");
        RollingCubePlan plan;
        if (m.initial()) {
            if (!m.currentCenter().equals(m.candidateCenter()) || !m.pinCenter().equals(m.currentCenter())
                    || m.generation() != 1) throw new IllegalArgumentException("Initial stage must start at the original pin");
            plan = RollingCubePlan.stationary(m.pinCenter(), m.currentCenter());
        } else {
            if (m.currentCenter().equals(m.candidateCenter())) throw new IllegalArgumentException("Rolling candidate must advance");
            plan = RollingCubePlan.create(m.pinCenter(), m.currentCenter(), m.candidateCenter());
        }
        List<CubePos> current = m.initial() ? List.of() : plan.committedOwners();
        List<CubePos> candidate = m.initial() ? plan.committedOwners() : plan.nextCommittedOwners();
        requireLeasePositions(m.currentLeases(), current);
        requireLeasePositions(m.candidateLeases(), candidate);
        if (!m.newPositions().equals(m.initial() ? candidate : plan.addedOwners())
                || !m.retiredPositions().equals(m.initial() ? List.of() : plan.retiredOwners())
                || !m.renderTargets().equals(m.initial() ? plan.currentTargets() : plan.candidateTargets()))
            throw new IllegalArgumentException("Rolling metadata differs from exact bounded plan");
        TreeSet<CubePos> union = new TreeSet<>(current); union.addAll(candidate);
        if (union.size() > MAX_UNION) throw new IllegalArgumentException("Rolling union exceeds bound");
        var retained = new java.util.HashMap<CubePos, Long>();
        for (CubeLease lease : m.currentLeases()) retained.put(lease.position(), lease.leaseGeneration());
        for (CubeLease lease : m.candidateLeases()) {
            Long previous = retained.get(lease.position());
            if (previous != null && previous.longValue() != lease.leaseGeneration())
                throw new IllegalArgumentException("Retained lease changed across stage");
        }
    }

    /** Structural ready checks; the replica additionally compares the entire exact live manifest. */
    public static void validateReady(StageReady ready) {
        Objects.requireNonNull(ready, "ready");
        validateWorld(ready.world());
        if (ready.epoch() < 0 || ready.generation() <= 0) throw new IllegalArgumentException("Invalid rolling ready identity");
        RollingCubePlan.validateWindowCenter(ready.candidateCenter());
        if (ready.versions().size() < RollingCubePlan.WINDOW_OWNERS || ready.versions().size() > MAX_OWNERS)
            throw new IllegalArgumentException("Rolling manifest owner count");
        CubePos previous = null;
        HashSet<Long> generations = new HashSet<>();
        for (CubeVersion version : ready.versions()) {
            Objects.requireNonNull(version);
            if ((previous != null && previous.compareTo(version.position()) >= 0)
                    || !generations.add(version.leaseGeneration()))
                throw new IllegalArgumentException("Manifest must have ordered unique coordinates and leases");
            previous = version.position();
        }
    }

    /**
     * SHA-256 over a versioned, length-delimited binary manifest. Includes world, epoch, actor
     * generation, XYZ, leases and revisions. Sorted input is required, never silently normalized.
     */
    public static String manifestDigest(StageReady ready) {
        validateReady(ready);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                string(out, "earth-rolling-manifest-v1");
                WorldIdentity world = ready.world(); out.writeLong(world.seed());
                string(out, world.generatorId()); string(out, world.generatorVersion());
                string(out, world.datasetVersion()); string(out, world.blockPaletteVersion());
                out.writeLong(ready.epoch()); out.writeLong(ready.generation()); position(out, ready.candidateCenter());
                out.writeInt(ready.versions().size());
                for (CubeVersion version : ready.versions()) {
                    position(out, version.position()); out.writeLong(version.leaseGeneration()); out.writeLong(version.revision());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void requireLeasePositions(List<CubeLease> leases, List<CubePos> expected) {
        if (leases.size() != expected.size() || leases.size() > MAX_OWNERS)
            throw new IllegalArgumentException("Rolling lease count differs from plan");
        HashSet<Long> generations = new HashSet<>();
        for (int i = 0; i < leases.size(); i++) {
            CubeLease lease = Objects.requireNonNull(leases.get(i));
            if (!lease.position().equals(expected.get(i)) || !generations.add(lease.leaseGeneration()))
                throw new IllegalArgumentException("Rolling leases must exactly follow canonical plan order");
        }
    }

    private static void requireVersion(FriendlyByteBuf b) {
        if (b.readVarInt() != WIRE_VERSION) throw new IllegalArgumentException("Unsupported rolling body version");
    }
    private static void validateWorld(WorldIdentity world) {
        Objects.requireNonNull(world);
        if (world.generatorId().length() > 128 || world.generatorVersion().length() > 128
                || world.datasetVersion().length() > 128 || world.blockPaletteVersion().length() > 128)
            throw new IllegalArgumentException("World identity exceeds bounded rolling schema");
    }
    private static void writeLeases(FriendlyByteBuf b, List<CubeLease> leases) {
        b.writeVarInt(leases.size());
        for (CubeLease lease : leases) { CoreGatePayload.position(b, lease.position()); b.writeLong(lease.leaseGeneration()); }
    }
    private static List<CubeLease> readLeases(FriendlyByteBuf b) {
        int count = readCount(b, MAX_OWNERS, 20); List<CubeLease> leases = new ArrayList<>(count);
        for (int i = 0; i < count; i++) leases.add(new CubeLease(CoreGatePayload.position(b), b.readLong()));
        return leases;
    }
    private static void writePositions(FriendlyByteBuf b, List<CubePos> positions) {
        b.writeVarInt(positions.size()); for (CubePos p : positions) CoreGatePayload.position(b, p);
    }
    private static List<CubePos> readPositions(FriendlyByteBuf b, int maximum) {
        int count = readCount(b, maximum, 12); List<CubePos> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) positions.add(CoreGatePayload.position(b));
        return positions;
    }
    private static int readCount(FriendlyByteBuf b, int maximum, int bytesPerEntry) {
        int count = b.readVarInt();
        if (count < 0 || count > maximum || count > b.readableBytes() / bytesPerEntry)
            throw new IllegalArgumentException("Invalid bounded rolling list length");
        return count;
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8); out.writeInt(encoded.length); out.write(encoded);
    }
    private static void position(DataOutputStream out, CubePos p) throws IOException {
        out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z());
    }
}
