package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.netty.buffer.Unpooled;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Small bounded envelope; nested snapshot CRC/schema/palette are verified before admission. */
public final class CoreGatePayload implements CustomPacketPayload {
    public static final int MAX_BYTES=40_000;
    public static final int START=1,SNAPSHOT=2,EDIT_BEGIN=3,EDIT_ACK=4,STOP=5,FAILURE=6;
    public static final Type<CoreGatePayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("earthplatform","core_gate"));
    public static final StreamCodec<RegistryFriendlyByteBuf,CoreGatePayload> CODEC=new StreamCodec<>() {
        public CoreGatePayload decode(RegistryFriendlyByteBuf b){return new CoreGatePayload(b.readByteArray(MAX_BYTES));}
        public void encode(RegistryFriendlyByteBuf b,CoreGatePayload p){b.writeByteArray(p.bytes);}
    };
    private final byte[] bytes;
    public CoreGatePayload(byte[] bytes){Objects.requireNonNull(bytes);if(bytes.length<1||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Core packet size");this.bytes=bytes.clone();}
    public static CoreGatePayload create(Consumer<FriendlyByteBuf> writer){var b=new FriendlyByteBuf(Unpooled.buffer(256,MAX_BYTES));try{writer.accept(b);byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);return new CoreGatePayload(bytes);}finally{b.release();}}
    public FriendlyByteBuf open(){return new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes).asReadOnly());}
    public static void world(FriendlyByteBuf b,WorldIdentity w){b.writeLong(w.seed());b.writeUtf(w.generatorId(),128);b.writeUtf(w.generatorVersion(),128);b.writeUtf(w.datasetVersion(),128);b.writeUtf(w.blockPaletteVersion(),128);}
    public static WorldIdentity world(FriendlyByteBuf b){return new WorldIdentity(b.readLong(),b.readUtf(128),b.readUtf(128),b.readUtf(128),b.readUtf(128));}
    public static void position(FriendlyByteBuf b,CubePos p){b.writeInt(p.x());b.writeInt(p.y());b.writeInt(p.z());}
    public static CubePos position(FriendlyByteBuf b){return new CubePos(b.readInt(),b.readInt(),b.readInt());}
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
