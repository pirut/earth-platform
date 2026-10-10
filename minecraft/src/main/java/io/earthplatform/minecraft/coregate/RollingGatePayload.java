package io.earthplatform.minecraft.coregate;

import io.netty.buffer.Unpooled;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Integrated diagnostic stream: one bounded envelope and one frame per event. */
public final class RollingGatePayload implements CustomPacketPayload {
    public static final int MAX_BYTES=40_000;
    public static final int METADATA=1,SNAPSHOT=2,READY=3,ALLOW=4,COMMITTED=5,EDIT_BEGIN=6,EDIT_ACK=7,STOP=8,REJECT=9;
    public static final Type<RollingGatePayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("earthplatform","rolling_gate"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RollingGatePayload> CODEC=new StreamCodec<>() {
        public RollingGatePayload decode(RegistryFriendlyByteBuf b){return new RollingGatePayload(b.readByteArray(MAX_BYTES));}
        public void encode(RegistryFriendlyByteBuf b,RollingGatePayload p){b.writeByteArray(p.bytes);}
    };
    private final byte[] bytes;
    public RollingGatePayload(byte[] bytes){Objects.requireNonNull(bytes);if(bytes.length<9||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Rolling packet size");this.bytes=bytes.clone();}
    public static RollingGatePayload create(Consumer<FriendlyByteBuf> writer){var b=new FriendlyByteBuf(Unpooled.buffer(256,MAX_BYTES));try{writer.accept(b);byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);return new RollingGatePayload(bytes);}finally{b.release();}}
    public FriendlyByteBuf open(){return new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes).asReadOnly());}
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
