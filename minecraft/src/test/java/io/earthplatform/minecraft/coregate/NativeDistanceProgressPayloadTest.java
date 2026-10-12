package io.earthplatform.minecraft.coregate;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDistanceProgressPayloadTest {
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }
    private static long[] vector() { return new long[NativeDistanceProgressWatchdog.LANE_COUNT]; }
    private static NativeDistanceProgressPayload payload(boolean server, long[] completed) {
        return new NativeDistanceProgressPayload(1, 2, 3, 12, 12, 0,
                0, 0, 0, "", "", 0, 0, server, completed);
    }
    private static NativeDistanceProgressPayload identity(long episode, long epoch, long serial,
            int selected, int effective, int serverLimit, long actor, long resources, long publication,
            String digest, String plan, long observation, long tick) {
        return new NativeDistanceProgressPayload(episode, epoch, serial, selected, effective,
                serverLimit, actor, resources, publication, digest, plan, observation, tick, false, vector());
    }

    @Test void everySettingAndBothOriginsRoundTripWithLongCapsAndExactIdentity() {
        for (int selected = 2; selected <= 32; selected++) for (boolean server : new boolean[]{false, true}) {
            long[] completed = vector();
            for (var lane : NativeDistanceProgressWatchdog.Lane.values())
                if (lane.server() == server) completed[lane.ordinal()] = lane.cap();
            var original = new NativeDistanceProgressPayload(Long.MAX_VALUE, Long.MAX_VALUE,
                    Long.MAX_VALUE, selected, selected, 32, Long.MAX_VALUE, Long.MAX_VALUE,
                    Long.MAX_VALUE, "a".repeat(64), "b".repeat(64), Long.MAX_VALUE,
                    Long.MAX_VALUE, server, completed);
            var buffer = buffer();
            try {
                NativeDistanceProgressPayload.CODEC.encode(buffer, original);
                assertEquals(334, buffer.readableBytes());
                var decoded = NativeDistanceProgressPayload.CODEC.decode(buffer);
                assertEquals(original, decoded);
                assertEquals(original.hashCode(), decoded.hashCode());
                assertArrayEquals(completed, decoded.completed());
                assertFalse(buffer.isReadable());
            } finally { buffer.release(); }
        }
    }

    @Test void zeroBootstrapIdentityAndUnknownEffectiveAndLimitArePermitted() {
        var original = identity(1, 2, 3, 12, 0, 0, 0, 0, 0, "", "", 0, 0);
        var buffer = buffer();
        try {
            NativeDistanceProgressPayload.CODEC.encode(buffer, original);
            assertEquals(206, buffer.readableBytes());
            assertEquals(original, NativeDistanceProgressPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
        assertEquals(12, identity(1, 2, 3, 24, 12, 12, 1, 1, 1, "", "", 0, 0).effective());
    }

    @Test void vectorsAreDefensivelyCopiedOnInputAndOutput() {
        long[] completed = vector();
        int index = NativeDistanceProgressWatchdog.Lane.FACE_SCANS.ordinal();
        completed[index] = 4_831_838_208L;
        var original = payload(false, completed);
        completed[index] = 0;
        original.completed()[index] = 1;
        assertEquals(4_831_838_208L, original.completed()[index]);
        assertNotSame(original.completed(), original.completed());
    }

    @Test void requiresExactVectorLengthAndRejectsEveryNegativeOrOverCapLane() {
        assertThrows(IllegalArgumentException.class, () -> payload(false, null));
        assertThrows(IllegalArgumentException.class, () -> payload(false, new long[0]));
        assertThrows(IllegalArgumentException.class, () -> payload(false, new long[NativeDistanceProgressWatchdog.LANE_COUNT - 1]));
        assertThrows(IllegalArgumentException.class, () -> payload(false, new long[NativeDistanceProgressWatchdog.LANE_COUNT + 1]));
        for (var lane : NativeDistanceProgressWatchdog.Lane.values()) {
            long[] negative = vector(); negative[lane.ordinal()] = -1;
            assertThrows(IllegalArgumentException.class, () -> payload(lane.server(), negative));
            long[] overflow = vector(); overflow[lane.ordinal()] = lane.cap() + 1;
            assertThrows(IllegalArgumentException.class, () -> payload(lane.server(), overflow));
        }
    }

    @Test void originMayReportOnlyItsExplicitProducerLanes() {
        int serverLanes = 0;
        for (var lane : NativeDistanceProgressWatchdog.Lane.values()) {
            if (lane.server()) serverLanes++;
            long[] completed = vector(); completed[lane.ordinal()] = 1;
            assertDoesNotThrow(() -> payload(lane.server(), completed));
            assertThrows(IllegalArgumentException.class, () -> payload(!lane.server(), completed));
        }
        assertEquals(5, serverLanes);
        assertTrue(NativeDistanceProgressWatchdog.Lane.SERVER_DRAINED.server());
        assertTrue(NativeDistanceProgressWatchdog.Lane.PREFLIGHT.server());
        assertTrue(NativeDistanceProgressWatchdog.Lane.SOURCE_READY.server());
        assertTrue(NativeDistanceProgressWatchdog.Lane.ACTOR_SNAPSHOTS.server());
        assertTrue(NativeDistanceProgressWatchdog.Lane.PREPARED_PAIR.server());
    }

    @Test void invalidIdentityTuplesDigestsAndObservationCountersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> identity(0, 2, 3, 12, 12, 0, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 0, 3, 12, 12, 0, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 0, 12, 12, 0, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 1, 1, 0, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 33, 12, 0, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, -1, 0, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 13, 0, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, -1, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 33, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 10, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 24, 12, 0, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 24, 10, 12, 0, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 0, -1, 0, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 0, 0, -1, 0, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 0, 0, 0, -1, "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 0, 0, 0, 0, null, "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 0, 0, 0, 0, "a".repeat(63), "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 0, 0, 0, 0, "g".repeat(64), "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 0, 0, 0, 0, "A".repeat(64), "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 0, 0, 0, 0, "", "a".repeat(65), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 0, 0, 0, 0, "", "", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> identity(1, 2, 3, 12, 12, 0, 0, 0, 0, "", "", 0, -1));
    }

    @Test void everyTruncatedWirePrefixFails() {
        var encoded = buffer();
        byte[] bytes;
        try {
            NativeDistanceProgressPayload.CODEC.encode(encoded, payload(false, vector()));
            bytes = new byte[encoded.readableBytes()]; encoded.readBytes(bytes);
        } finally { encoded.release(); }
        for (int length = 0; length < bytes.length; length++) {
            var truncated = buffer();
            try {
                truncated.writeBytes(bytes, 0, length);
                assertThrows(RuntimeException.class, () -> NativeDistanceProgressPayload.CODEC.decode(truncated));
            } finally { truncated.release(); }
        }
    }

    @Test void decoderRejectsInvalidOriginOverlongDigestAndOutOfRangeCompletedCounter() {
        var origin = buffer();
        try {
            NativeDistanceProgressPayload.CODEC.encode(origin, payload(false, vector()));
            origin.setByte(69, 2);
            assertThrows(RuntimeException.class, () -> NativeDistanceProgressPayload.CODEC.decode(origin));
        } finally { origin.release(); }
        var digest = buffer();
        try {
            digest.writeLong(1); digest.writeLong(2); digest.writeLong(3);
            digest.writeVarInt(12); digest.writeVarInt(12); digest.writeVarInt(0);
            digest.writeLong(0); digest.writeLong(0); digest.writeLong(0);
            digest.writeUtf("a".repeat(65));
            assertThrows(RuntimeException.class, () -> NativeDistanceProgressPayload.CODEC.decode(digest));
        } finally { digest.release(); }
        var overflow = buffer();
        try {
            NativeDistanceProgressPayload.CODEC.encode(overflow, payload(false, vector()));
            var lane = NativeDistanceProgressWatchdog.Lane.FACE_SCANS;
            overflow.setLong(70 + 8 * lane.ordinal(), lane.cap() + 1);
            assertThrows(RuntimeException.class, () -> NativeDistanceProgressPayload.CODEC.decode(overflow));
        } finally { overflow.release(); }
    }
}
