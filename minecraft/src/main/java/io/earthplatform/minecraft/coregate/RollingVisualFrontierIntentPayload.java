package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded prefetch intent only. No camera rectangle, collision receipt or position authority crosses this wire. */
public record RollingVisualFrontierIntentPayload(long epoch,long committedPublication,long resourceGeneration,long intentId,CubePos desiredCenter) implements CustomPacketPayload {
    public RollingVisualFrontierIntentPayload {
        Objects.requireNonNull(desiredCenter);
        if(epoch<=0||committedPublication<=0||resourceGeneration<=0||intentId<=0)throw new IllegalArgumentException("Positive visual frontier intent identities required");
    }
    public static final Type<RollingVisualFrontierIntentPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("earthplatform","rolling_visual_frontier_intent"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RollingVisualFrontierIntentPayload> CODEC=new StreamCodec<>(){
        public RollingVisualFrontierIntentPayload decode(RegistryFriendlyByteBuf b){return new RollingVisualFrontierIntentPayload(b.readLong(),b.readLong(),b.readLong(),b.readLong(),new CubePos(b.readInt(),b.readInt(),b.readInt()));}
        public void encode(RegistryFriendlyByteBuf b,RollingVisualFrontierIntentPayload p){b.writeLong(p.epoch);b.writeLong(p.committedPublication);b.writeLong(p.resourceGeneration);b.writeLong(p.intentId);b.writeInt(p.desiredCenter.x());b.writeInt(p.desiredCenter.y());b.writeInt(p.desiredCenter.z());}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
