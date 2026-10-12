package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

/** Bounded native output data, never an apply capability or a caller-supplied prepared result. */
public final class NativeMiningControlJournal {
    static final int MAX_NOTICES = 8, MAX_WIRE_BYTES = 4096;
    static final int RESERVATION_BYTES = 2 * MAX_WIRE_BYTES + MAX_NOTICES * 256;
    public sealed interface Notice permits Crack, BlockResync, Ack {}
    public record Crack(int breakerId, CubeCell target, int stage) implements Notice {}
    public record BlockResync(CubeCell target, int stateId) implements Notice {}
    public record Ack(int sequence) implements Notice {}
    NativeMiningControlJournal() {}
    int count() { return notices.size(); }
    private final List<Notice> notices = new ArrayList<>();
    private int wireBytes;
    private boolean sealed;
    void crack(int breakerId,CubeCell target,int stage) {
        requireWireCell(target);
        // Retain the raw native int for eventual original destroyBlockProgress replay. The
        // wire codec narrows to one unsigned byte: e.g.256 becomes0, while -1 becomes255.
        add(new Crack(breakerId,target,stage), roundTripCrack(breakerId,target,stage));
    }
    void packet(Object packet) {
        if (packet == null) throw refused("Null mining output packet");
        if (packet.getClass() == ClientboundBlockChangedAckPacket.class) {
            var ack=(ClientboundBlockChangedAckPacket)packet;
            add(new Ack(ack.sequence()), roundTripAck(ack));
        } else if (packet.getClass() == ClientboundBlockUpdatePacket.class) {
            var block=(ClientboundBlockUpdatePacket)packet;
            var target=NativeMiningControlState.cell(block.getPos()); requireWireCell(target);
            // IDs are evidence only; the private preparation retains and checks exact state identity.
            int state=net.minecraft.world.level.block.Block.getId(block.getBlockState());
            add(new BlockResync(target,state),roundTripBlock(block));
        } else throw refused("Native mining produced an unadmitted packet class: "+packet.getClass().getName());
    }
    List<Notice> seal() { if(sealed)throw refused("Mining journal sealed twice"); sealed=true;return List.copyOf(notices); }
    int wireBytes() { return wireBytes; }
    private void add(Notice notice,int size) {
        if (sealed || size < 0 || notices.size() >= MAX_NOTICES || size > MAX_WIRE_BYTES-wireBytes)
            throw refused("Native mining output exceeds its prepaid bounded journal");
        notices.add(notice); wireBytes += size;
    }
    static void requireWireCell(CubeCell target) {
        if (target==null || target.x() < -33554432L || target.x() > 33554431L || target.z() < -33554432L
                || target.z() > 33554431L || target.y() < -2048L || target.y() > 2047L)
            throw refused("First native mining transport requires signed packed-position-safe coordinates");
    }
    static void requirePacket(ServerboundPlayerActionPacket packet) {
        if(packet==null || packet.getClass()!=ServerboundPlayerActionPacket.class || packet.getSequence()<0)
            throw refused("Native mining requires original nonnegative-sequence PlayerAction packet");
        switch(packet.getAction()) {
            case START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {}
            default -> throw refused("Only original START/ABORT/STOP mining actions are admitted");
        }
        requireWireCell(NativeMiningControlState.cell(packet.getPos()));
        var raw=io.netty.buffer.Unpooled.buffer(32,64);
        try {
            var buf=new FriendlyByteBuf(raw); ServerboundPlayerActionPacket.STREAM_CODEC.encode(buf,packet);
            var decoded=ServerboundPlayerActionPacket.STREAM_CODEC.decode(buf);
            if(buf.isReadable() || decoded.getAction()!=packet.getAction() || decoded.getSequence()!=packet.getSequence()
                    || decoded.getDirection()!=packet.getDirection() || !decoded.getPos().equals(packet.getPos()))
                throw refused("Native mining input changed in its actual packet codec");
        } finally {raw.release();}
    }
    static int roundTripCrack(int breakerId,CubeCell target,int stage) {
        requireWireCell(target);
        var packet=new ClientboundBlockDestructionPacket(breakerId,NativeMiningControlState.pos(target),stage);
        var raw=io.netty.buffer.Unpooled.buffer(32,64);
        try {
            var buf=new FriendlyByteBuf(raw);ClientboundBlockDestructionPacket.STREAM_CODEC.encode(buf,packet);int size=buf.readableBytes();
            var decoded=ClientboundBlockDestructionPacket.STREAM_CODEC.decode(buf);
            if(buf.isReadable()||decoded.getId()!=breakerId||!decoded.getPos().equals(packet.getPos())
                    ||decoded.getProgress()!=(stage&255))throw refused("Native crack codec changed its unsigned-byte stage semantics");
            return size;
        } finally {raw.release();}
    }
    private static int roundTripAck(ClientboundBlockChangedAckPacket packet) {
        var raw=io.netty.buffer.Unpooled.buffer(16,64);
        try {
            var buf=new FriendlyByteBuf(raw);ClientboundBlockChangedAckPacket.STREAM_CODEC.encode(buf,packet);int size=buf.readableBytes();
            var decoded=ClientboundBlockChangedAckPacket.STREAM_CODEC.decode(buf);
            if(buf.isReadable()||decoded.sequence()!=packet.sequence())throw refused("Native mining ack codec changed");return size;
        } finally {raw.release();}
    }
    private static int roundTripBlock(ClientboundBlockUpdatePacket packet) {
        var raw=io.netty.buffer.Unpooled.buffer(32,64);
        try {
            // This pinned codec uses the global Block.BLOCK_STATE_REGISTRY ID map, not dynamic registries.
            var buf=new net.minecraft.network.RegistryFriendlyByteBuf(raw,net.minecraft.core.RegistryAccess.EMPTY);ClientboundBlockUpdatePacket.STREAM_CODEC.encode(buf,packet);int size=buf.readableBytes();
            var decoded=ClientboundBlockUpdatePacket.STREAM_CODEC.decode(buf);
            if(buf.isReadable()||!decoded.getPos().equals(packet.getPos())||decoded.getBlockState()!=packet.getBlockState())
                throw refused("Native mining resync codec changed");return size;
        } finally {raw.release();}
    }
    private static SurvivalRefusal refused(String message) {return new SurvivalRefusal(message);}
}
