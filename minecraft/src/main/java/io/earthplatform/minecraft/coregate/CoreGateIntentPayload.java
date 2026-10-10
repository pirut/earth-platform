package io.earthplatform.minecraft.coregate;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** An input intention only. Server-owned eye/look and admitted cubes determine its actual target. */
public record CoreGateIntentPayload(long epoch,boolean place) implements CustomPacketPayload {
    public CoreGateIntentPayload{if(epoch<=0)throw new IllegalArgumentException("Positive core epoch required");}
    public static final Type<CoreGateIntentPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("earthplatform","core_edit_intent"));
    public static final StreamCodec<RegistryFriendlyByteBuf,CoreGateIntentPayload> CODEC=new StreamCodec<>(){
        public CoreGateIntentPayload decode(RegistryFriendlyByteBuf b){return new CoreGateIntentPayload(b.readLong(),b.readBoolean());}
        public void encode(RegistryFriendlyByteBuf b,CoreGateIntentPayload p){b.writeLong(p.epoch());b.writeBoolean(p.place());}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
