package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeLease;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeVersion;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageMetadata;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageReady;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingGateWireTest {
    static final WorldIdentity WORLD = new WorldIdentity(7, "rolling", "v1", "data", "palette");
    static final CubePos CENTER = new CubePos(0, 8, 0);
    static StageMetadata metadata() {
        var plan = RollingCubePlan.stationary(CENTER, CENTER); var leases = new ArrayList<CubeLease>();
        long next = 1; for (var p : plan.committedOwners()) leases.add(new CubeLease(p, next++));
        return new StageMetadata(WORLD, 4, 1, true, CENTER, CENTER, CENTER, List.of(), leases, plan.committedOwners(), List.of(), plan.currentTargets());
    }
    static StageReady ready() {
        return new StageReady(WORLD, 4, 1, CENTER, metadata().candidateLeases().stream().map(l -> new CubeVersion(l.position(), l.leaseGeneration(), 0)).toList());
    }
    static FriendlyByteBuf buffer() { return new FriendlyByteBuf(Unpooled.buffer()); }

    @Test void metadataRoundTripsCanonicalBoundedFieldsAndLeavesEnclosingSentinel() {
        var b = buffer(); try {
            RollingGateWire.writeMetadata(b, metadata()); int end = b.writerIndex(); b.writeByte(0x55);
            assertEquals(metadata(), RollingGateWire.readMetadata(b, WORLD, 4));
            assertEquals(end, b.readerIndex()); assertEquals(0x55, b.readUnsignedByte());
        } finally { b.release(); }
    }

    @Test void readyRoundTripsAndCanonicalDigestIsStableAndIdentitySensitive() {
        var b = buffer(); try {
            var original = ready(); RollingGateWire.writeReady(b, original);
            var decoded = RollingGateWire.readReady(b, WORLD, 4); assertEquals(original, decoded); assertFalse(b.isReadable());
            String digest = RollingGateWire.manifestDigest(original); assertEquals(64, digest.length());
            assertEquals(digest, RollingGateWire.manifestDigest(decoded));
            assertNotEquals(digest, RollingGateWire.manifestDigest(new StageReady(WORLD, 5, 1, CENTER, original.versions())));
            assertNotEquals(digest, RollingGateWire.manifestDigest(new StageReady(WORLD, 4, 2, CENTER, original.versions())));
            assertNotEquals(digest, RollingGateWire.manifestDigest(new StageReady(WORLD, 4, 1, CENTER.offset(0, 1, 0), original.versions())));
            var changed = new ArrayList<>(original.versions()); var first = changed.getFirst();
            changed.set(0, new CubeVersion(first.position(), first.leaseGeneration(), 1));
            assertNotEquals(digest, RollingGateWire.manifestDigest(new StageReady(WORLD, 4, 1, CENTER, changed)));
            changed.set(0, new CubeVersion(first.position(), 9999, 0));
            assertNotEquals(digest, RollingGateWire.manifestDigest(new StageReady(WORLD, 4, 1, CENTER, changed)));
            var other = new WorldIdentity(8, "rolling", "v1", "data", "palette");
            assertNotEquals(digest, RollingGateWire.manifestDigest(new StageReady(other, 4, 1, CENTER, original.versions())));
        } finally { b.release(); }
    }

    @Test void oversizedNegativeAndTruncatedCountsRejectBeforeListPopulation() {
        for (int count : new int[]{-1, 153, Integer.MAX_VALUE, 125}) {
            var b = buffer(); try {
                b.writeVarInt(RollingGateWire.WIRE_VERSION); b.writeLong(1); CoreGatePayload.position(b, CENTER); b.writeVarInt(count);
                var input = b;
                assertThrows(IllegalArgumentException.class, () -> RollingGateWire.readReady(input, WORLD, 4));
            } finally { b.release(); }
            b = buffer(); try {
                b.writeVarInt(RollingGateWire.WIRE_VERSION); b.writeLong(1); b.writeBoolean(true); b.writeBoolean(true); b.writeBoolean(true);
                for (int i = 0; i < 4; i++) CoreGatePayload.position(b, CENTER);
                b.writeVarInt(count); var input = b;
                assertThrows(IllegalArgumentException.class, () -> RollingGateWire.readMetadata(input, WORLD, 4));
            } finally { b.release(); }
        }
    }

    @Test void invalidVersionsAndNoncanonicalBooleanFailClosed() {
        var b = buffer(); try {
            b.writeVarInt(99); assertThrows(IllegalArgumentException.class, () -> RollingGateWire.readMetadata(b, WORLD, 4));
            b.clear(); b.writeVarInt(RollingGateWire.WIRE_VERSION); b.writeLong(1); b.writeByte(2);
            assertThrows(IllegalArgumentException.class, () -> RollingGateWire.readMetadata(b, WORLD, 4));
        } finally { b.release(); }
    }

    @Test void canonicalManifestRejectsDuplicateCoordinatesDuplicateLeasesMissingAndReorderedValues() {
        var original = ready(); var versions = new ArrayList<>(original.versions());
        Collections.swap(versions, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> RollingGateWire.manifestDigest(new StageReady(WORLD, 4, 1, CENTER, versions)));
        versions.clear(); versions.addAll(original.versions()); versions.set(1, versions.getFirst());
        assertThrows(IllegalArgumentException.class, () -> RollingGateWire.manifestDigest(new StageReady(WORLD, 4, 1, CENTER, versions)));
        versions.clear(); versions.addAll(original.versions()); var second = versions.get(1);
        versions.set(1, new CubeVersion(second.position(), versions.getFirst().leaseGeneration(), 0));
        assertThrows(IllegalArgumentException.class, () -> RollingGateWire.manifestDigest(new StageReady(WORLD, 4, 1, CENTER, versions)));
        assertThrows(IllegalArgumentException.class, () -> RollingGateWire.manifestDigest(new StageReady(WORLD, 4, 1, CENTER, original.versions().subList(0, 124))));
    }

    @Test void metadataRejectsAlteredGeometryMissingOwnersAndLeaseAliases() {
        var m = metadata();
        assertThrows(IllegalArgumentException.class, () -> RollingGateWire.validateMetadata(new StageMetadata(WORLD, 4, 1, true,
                CENTER, CENTER, CENTER.offset(1, 0, 0), m.currentLeases(), m.candidateLeases(), m.newPositions(), m.retiredPositions(), m.renderTargets())));
        assertThrows(IllegalArgumentException.class, () -> RollingGateWire.validateMetadata(new StageMetadata(WORLD, 4, 1, true,
                CENTER, CENTER, CENTER, m.currentLeases(), m.candidateLeases().subList(0, 124), m.newPositions(), m.retiredPositions(), m.renderTargets())));
        var leases = new ArrayList<>(m.candidateLeases()); leases.set(1, new CubeLease(leases.get(1).position(), 1));
        assertThrows(IllegalArgumentException.class, () -> RollingGateWire.validateMetadata(new StageMetadata(WORLD, 4, 1, true,
                CENTER, CENTER, CENTER, m.currentLeases(), leases, m.newPositions(), m.retiredPositions(), m.renderTargets())));
        assertThrows(IllegalArgumentException.class, () -> RollingGateWire.validateMetadata(new StageMetadata(WORLD, 4, 1, true,
                CENTER, CENTER, CENTER, m.currentLeases(), m.candidateLeases(), m.newPositions(), List.of(CENTER), m.renderTargets())));
    }

    @Test void allTruncationsOfReadyAndMetadataRejectWhileFullBodiesStayBelowEnvelopeLimit() {
        for (boolean ready : new boolean[]{false, true}) {
            var encoded = buffer(); try {
                if (ready) RollingGateWire.writeReady(encoded, ready()); else RollingGateWire.writeMetadata(encoded, metadata());
                assertTrue(encoded.readableBytes() < CoreGatePayload.MAX_BYTES);
                for (int size = 0; size < encoded.readableBytes(); size++) {
                    var slice = new FriendlyByteBuf(encoded.slice(0, size));
                    assertThrows(RuntimeException.class, () -> {
                        if (ready) RollingGateWire.readReady(slice, WORLD, 4); else RollingGateWire.readMetadata(slice, WORLD, 4);
                    });
                }
            } finally { encoded.release(); }
        }
    }
}
