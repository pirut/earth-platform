package io.earthplatform.minecraft.coregate;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Exact pair acknowledgement. Generation is the publication id, not the actor plan generation. */
public record RollingGateControlPayload(long epoch,long generation,long resourceGeneration,int kind,String digest,boolean place) implements CustomPacketPayload {
    public static final int PREPARED=1,PAIR_READY=2,FENCED=3,MOUSE=4;
    public RollingGateControlPayload {
        Objects.requireNonNull(digest);
        if(epoch<=0||generation<0||resourceGeneration<=0||kind<PREPARED||kind>MOUSE)throw new IllegalArgumentException("Invalid rolling control identity");
        if(kind==PREPARED||kind==PAIR_READY){if(generation<=0||!digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Exact rolling manifest digest required");}
        else if(!digest.isEmpty())throw new IllegalArgumentException("Unexpected rolling control digest");
        if(kind!=MOUSE&&place)throw new IllegalArgumentException("Unexpected edit action");
    }
    public static final Type<RollingGateControlPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("earthplatform","rolling_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RollingGateControlPayload> CODEC=new StreamCodec<>(){
        public RollingGateControlPayload decode(RegistryFriendlyByteBuf b){return new RollingGateControlPayload(b.readLong(),b.readLong(),b.readLong(),b.readUnsignedByte(),b.readUtf(64),b.readBoolean());}
        public void encode(RegistryFriendlyByteBuf b,RollingGateControlPayload p){b.writeLong(p.epoch);b.writeLong(p.generation);b.writeLong(p.resourceGeneration);b.writeByte(p.kind);b.writeUtf(p.digest,64);b.writeBoolean(p.place);}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
