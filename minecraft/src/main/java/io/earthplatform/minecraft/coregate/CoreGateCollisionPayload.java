package io.earthplatform.minecraft.coregate;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client reports paired publication readiness; it grants no terrain or movement authority. */
public record CoreGateCollisionPayload(long epoch,long revision,long generation,boolean ready) implements CustomPacketPayload {
    public CoreGateCollisionPayload {
        if (epoch <= 0 || generation < 0 || revision < -1 || (ready && revision < 0)) throw new IllegalArgumentException("Invalid collision frame");
    }
    public static final Type<CoreGateCollisionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("earthplatform", "core_collision_frame"));
    public static final StreamCodec<RegistryFriendlyByteBuf,CoreGateCollisionPayload> CODEC = new StreamCodec<>() {
        public CoreGateCollisionPayload decode(RegistryFriendlyByteBuf b) { return new CoreGateCollisionPayload(b.readLong(),b.readLong(),b.readLong(),b.readBoolean()); }
        public void encode(RegistryFriendlyByteBuf b, CoreGateCollisionPayload p) { b.writeLong(p.epoch);b.writeLong(p.revision);b.writeLong(p.generation);b.writeBoolean(p.ready); }
    };
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
