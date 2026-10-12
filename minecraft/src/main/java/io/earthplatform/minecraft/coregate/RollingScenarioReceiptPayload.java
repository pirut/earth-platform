package io.earthplatform.minecraft.coregate;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Diagnostic receipt only: never grants gameplay or publication authority. */
public record RollingScenarioReceiptPayload(long epoch,long publication,long resources,long coverage,long reloadSequence,String digest,String checkpoint) implements CustomPacketPayload {
    public RollingScenarioReceiptPayload {
        if(epoch<=0||publication<=0||resources<=0||coverage<=0||reloadSequence<0||digest==null||!digest.matches("[0-9a-f]{64}")
                ||checkpoint==null||!checkpoint.matches("[a-z0-9_-]{0,64}"))throw new IllegalArgumentException("Invalid scenario receipt");
    }
    public static final Type<RollingScenarioReceiptPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("earthplatform","rolling_scenario_receipt"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RollingScenarioReceiptPayload> CODEC=new StreamCodec<>(){
        public RollingScenarioReceiptPayload decode(RegistryFriendlyByteBuf b){return new RollingScenarioReceiptPayload(b.readLong(),b.readLong(),b.readLong(),b.readLong(),b.readLong(),b.readUtf(64),b.readUtf(64));}
        public void encode(RegistryFriendlyByteBuf b,RollingScenarioReceiptPayload p){b.writeLong(p.epoch);b.writeLong(p.publication);b.writeLong(p.resources);b.writeLong(p.coverage);b.writeLong(p.reloadSequence);b.writeUtf(p.digest,64);b.writeUtf(p.checkpoint,64);}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
