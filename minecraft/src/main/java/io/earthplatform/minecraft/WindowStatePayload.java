package io.earthplatform.minecraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Versioned adapter handshake, separate from vanilla packed-position messages. */
public record WindowStatePayload(ResourceLocation dimension, int originY, int surfaceY, boolean realTerrain,
        String terrainDataset, String horizontalProjection) implements CustomPacketPayload {
    public WindowStatePayload { ProofTerrain.validateIdentifiers(terrainDataset, horizontalProjection); }
    public WindowStatePayload(ResourceLocation dimension, int originY, int surfaceY, boolean realTerrain) {
        this(dimension, originY, surfaceY, realTerrain, ProofTerrain.NOAA, ProofTerrain.MANIFEST_PROJECTION);
    }
    public static final Type<WindowStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "window_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WindowStatePayload> CODEC = new StreamCodec<>() {
        @Override public WindowStatePayload decode(RegistryFriendlyByteBuf buffer) {
            return new WindowStatePayload(buffer.readResourceLocation(), buffer.readInt(), buffer.readInt(), buffer.readBoolean(),
                    buffer.readUtf(64), buffer.readUtf(64));
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, WindowStatePayload value) {
            buffer.writeResourceLocation(value.dimension()); buffer.writeInt(value.originY()); buffer.writeInt(value.surfaceY()); buffer.writeBoolean(value.realTerrain());
            buffer.writeUtf(value.terrainDataset(), 64); buffer.writeUtf(value.horizontalProjection(), 64);
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static volatile WindowStatePayload latest;
}
