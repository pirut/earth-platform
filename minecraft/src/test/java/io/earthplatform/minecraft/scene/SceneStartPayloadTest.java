package io.earthplatform.minecraft.scene;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneStartPayloadTest {
    private static final String IDENTITY = "0123456789abcdef".repeat(4);
    private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }

    @Test void boundedHandshakeRoundTripsEveryValidEpochBoundary() {
        for (long epoch : new long[]{1, 7, Long.MAX_VALUE}) {
            var expected = new SceneStartPayload(epoch, IDENTITY);
            assertSame(SceneStartPayload.TYPE, expected.type());
            var wire = buffer();
            try {
                SceneStartPayload.CODEC.encode(wire, expected);
                assertEquals(73, wire.readableBytes());
                assertEquals(expected, SceneStartPayload.CODEC.decode(wire));
                assertFalse(wire.isReadable());
            } finally { wire.release(); }
        }
    }

    @Test void invalidEpochsAndUnboundedOrNoncanonicalIdentitiesAreRejected() {
        for (long epoch : new long[]{0, -1, Long.MIN_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new SceneStartPayload(epoch, IDENTITY));
        for (String identity : new String[]{null, "", "0".repeat(63), "0".repeat(65), "g".repeat(64), "A".repeat(64), "é".repeat(64)})
            assertThrows(IllegalArgumentException.class, () -> new SceneStartPayload(1, identity));
    }

    @Test void decoderEnforcesConstructorAndWireLengthBounds() {
        for (long epoch : new long[]{0, -1, Long.MIN_VALUE}) {
            var wire = buffer();
            try {
                wire.writeLong(epoch); wire.writeUtf(IDENTITY);
                assertThrows(IllegalArgumentException.class, () -> SceneStartPayload.CODEC.decode(wire));
            } finally { wire.release(); }
        }
        for (String identity : new String[]{"", "0".repeat(63), "g".repeat(64)}) {
            var wire = buffer();
            try {
                wire.writeLong(1); wire.writeUtf(identity);
                assertThrows(IllegalArgumentException.class, () -> SceneStartPayload.CODEC.decode(wire));
            } finally { wire.release(); }
        }
        var oversized = buffer();
        try {
            oversized.writeLong(1); oversized.writeUtf("0".repeat(65));
            assertThrows(DecoderException.class, () -> SceneStartPayload.CODEC.decode(oversized));
        } finally { oversized.release(); }
    }

    @Test void everyTruncatedPrefixOfAValidHandshakeIsRejected() {
        var full = buffer();
        try {
            SceneStartPayload.CODEC.encode(full, new SceneStartPayload(1, IDENTITY));
            for (int length = 0; length < full.readableBytes(); length++) {
                var prefix = new RegistryFriendlyByteBuf(full.copy(0, length), RegistryAccess.EMPTY);
                try { assertThrows(RuntimeException.class, () -> SceneStartPayload.CODEC.decode(prefix)); }
                finally { prefix.release(); }
            }
        } finally { full.release(); }
    }
}
