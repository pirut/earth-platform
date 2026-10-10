package io.earthplatform.minecraft.fixture;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Fixed bounded diagnostic sequence, not a general streaming request protocol. */
public record NativeProbePayload(long epoch, byte[] lower, byte[] upper, byte[] renewedLower) implements CustomPacketPayload {
    public static final int MAX_FRAME_BYTES = 48 * 1024;
    public static final Type<NativeProbePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("earthplatform", "native_sparse_probe_v1"));
    public NativeProbePayload {
        if (epoch <= 0) throw new IllegalArgumentException("Positive diagnostic epoch required");
        lower = checkedCopy(lower); upper = checkedCopy(upper); renewedLower = checkedCopy(renewedLower);
    }
    private static byte[] checkedCopy(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) throw new IllegalArgumentException("Bounded diagnostic frame required");
        return bytes.clone();
    }
    @Override public byte[] lower() { return lower.clone(); }
    @Override public byte[] upper() { return upper.clone(); }
    @Override public byte[] renewedLower() { return renewedLower.clone(); }
    public static final StreamCodec<RegistryFriendlyByteBuf, NativeProbePayload> CODEC = new StreamCodec<>() {
        @Override public NativeProbePayload decode(RegistryFriendlyByteBuf buffer) {
            long epoch = buffer.readLong();
            if (epoch <= 0) throw new DecoderException("Invalid diagnostic epoch");
            var payload = new NativeProbePayload(epoch, buffer.readByteArray(MAX_FRAME_BYTES), buffer.readByteArray(MAX_FRAME_BYTES), buffer.readByteArray(MAX_FRAME_BYTES));
            if (buffer.isReadable()) throw new DecoderException("Trailing bytes after fixed diagnostic payload");
            return payload;
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, NativeProbePayload value) {
            buffer.writeLong(value.epoch); buffer.writeByteArray(value.lower); buffer.writeByteArray(value.upper); buffer.writeByteArray(value.renewedLower);
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
