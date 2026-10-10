package io.earthplatform.minecraft.scene;

import io.earthplatform.minecraft.EarthPlatformMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded operator-only visual-scene handshake, not terrain streaming or player-world authority. */
public record SceneStartPayload(long epoch,String sourceIdentity) implements CustomPacketPayload {
    public SceneStartPayload {if(epoch<=0||sourceIdentity==null||!sourceIdentity.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid scene handshake");}
    public static final Type<SceneStartPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID,"sparse_scene_start"));
    public static final StreamCodec<RegistryFriendlyByteBuf,SceneStartPayload> CODEC=new StreamCodec<>() {
        @Override public SceneStartPayload decode(RegistryFriendlyByteBuf buffer){return new SceneStartPayload(buffer.readLong(),buffer.readUtf(64));}
        @Override public void encode(RegistryFriendlyByteBuf buffer,SceneStartPayload payload){buffer.writeLong(payload.epoch());buffer.writeUtf(payload.sourceIdentity(),64);}
    };
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
