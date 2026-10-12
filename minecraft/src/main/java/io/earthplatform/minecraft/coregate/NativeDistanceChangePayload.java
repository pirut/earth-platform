package io.earthplatform.minecraft.coregate;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Exact old-session setting transition. A drained reply is a physical ownership receipt, not readiness. */
public record NativeDistanceChangePayload(long epoch,long serial,int kind,int selected,int effective,int serverLimit) implements CustomPacketPayload {
    public static final int REQUEST=0,DRAIN=1,CLIENT_DRAINED=2,FAILED=3;
    public NativeDistanceChangePayload {
        if(epoch<=0||serial<=0||kind<REQUEST||kind>FAILED||selected<2||selected>32||effective<0||effective>32||serverLimit<0||serverLimit>32)
            throw new IllegalArgumentException("Invalid exact native-distance transition");
    }
    public static NativeDistanceChangePayload request(long epoch,long serial,int selected,int effective,int serverLimit){return new NativeDistanceChangePayload(epoch,serial,REQUEST,selected,effective,serverLimit);}
    public static NativeDistanceChangePayload drained(long epoch,long serial,int selected,int effective,int serverLimit){return new NativeDistanceChangePayload(epoch,serial,CLIENT_DRAINED,selected,effective,serverLimit);}
    public NativeDistanceChangePayload drain(){return new NativeDistanceChangePayload(epoch,serial,DRAIN,selected,effective,serverLimit);}
    public NativeDistanceChangePayload failed(){return new NativeDistanceChangePayload(epoch,serial,FAILED,selected,effective,serverLimit);}
    public boolean sameSelection(NativeDistanceChangePayload other){return other!=null&&epoch==other.epoch&&serial==other.serial&&selected==other.selected&&effective==other.effective&&serverLimit==other.serverLimit;}
    public static final Type<NativeDistanceChangePayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("earthplatform","native_distance_change"));
    public static final StreamCodec<RegistryFriendlyByteBuf,NativeDistanceChangePayload> CODEC=new StreamCodec<>(){
        public NativeDistanceChangePayload decode(RegistryFriendlyByteBuf b){return new NativeDistanceChangePayload(b.readLong(),b.readLong(),b.readUnsignedByte(),b.readVarInt(),b.readVarInt(),b.readVarInt());}
        public void encode(RegistryFriendlyByteBuf b,NativeDistanceChangePayload p){b.writeLong(p.epoch);b.writeLong(p.serial);b.writeByte(p.kind);b.writeVarInt(p.selected);b.writeVarInt(p.effective);b.writeVarInt(p.serverLimit);}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
