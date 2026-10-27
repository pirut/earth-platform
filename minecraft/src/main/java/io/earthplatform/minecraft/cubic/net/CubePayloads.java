package io.earthplatform.minecraft.cubic.net;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.far.Far;
import io.earthplatform.minecraft.platform.Loader;
import io.earthplatform.minecraft.platform.Payloads;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Cube traffic. Positions travel as separate ints, never BlockPos.asLong, whose 12-bit Y would
 * wrap above Y 2047.
 */
public final class CubePayloads {
    private CubePayloads() {}

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, path); }

    /** One full cube: vanilla section bytes plus block entity update tags. */
    public record CubeData(int x, int y, int z, byte[] section, List<CompoundTag> blockEntities) implements CustomPacketPayload {
        public static final Type<CubeData> TYPE = new Type<>(id("cube_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CubeData> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.x); buf.writeVarInt(p.y); buf.writeVarInt(p.z);
            buf.writeByteArray(p.section);
            buf.writeVarInt(p.blockEntities.size());
            for (CompoundTag tag : p.blockEntities) buf.writeNbt(tag);
        }, buf -> {
            int x = buf.readVarInt(), y = buf.readVarInt(), z = buf.readVarInt();
            byte[] section = buf.readByteArray(1 << 20);
            int count = buf.readVarInt();
            List<CompoundTag> tags = new ArrayList<>(count);
            for (int i = 0; i < count; i++) tags.add(buf.readNbt());
            return new CubeData(x, y, z, section, tags);
        });
        /** Rough size on the wire, for pacing. */
        public int size() { return 16 + section.length + 256 * blockEntities.size(); }
        @Override public Type<CubeData> type() { return TYPE; }
    }

    public record CubeUnload(long[] keys) implements CustomPacketPayload {
        public static final Type<CubeUnload> TYPE = new Type<>(id("cube_unload"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CubeUnload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeLongArray(p.keys), buf -> new CubeUnload(buf.readLongArray()));
        @Override public Type<CubeUnload> type() { return TYPE; }
    }

    public record Change(int x, int y, int z, int state, @Nullable CompoundTag blockEntity) {}

    /** Block changes inside cubes the receiver already holds. */
    public record BlockChanges(List<Change> changes) implements CustomPacketPayload {
        public static final Type<BlockChanges> TYPE = new Type<>(id("cube_changes"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BlockChanges> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.changes.size());
            for (Change c : p.changes) {
                buf.writeVarInt(c.x); buf.writeVarInt(c.y); buf.writeVarInt(c.z); buf.writeVarInt(c.state);
                buf.writeBoolean(c.blockEntity != null);
                if (c.blockEntity != null) buf.writeNbt(c.blockEntity);
            }
        }, buf -> {
            int count = buf.readVarInt();
            List<Change> changes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int x = buf.readVarInt(), y = buf.readVarInt(), z = buf.readVarInt(), state = buf.readVarInt();
                changes.add(new Change(x, y, z, state, buf.readBoolean() ? buf.readNbt() : null));
            }
            return new BlockChanges(changes);
        });
        @Override public Type<BlockChanges> type() { return TYPE; }
    }

    /** Sky tops for one 16x16 cube column. */
    public record ColumnTops(int cubeX, int cubeZ, int[] tops) implements CustomPacketPayload {
        public static final Type<ColumnTops> TYPE = new Type<>(id("cube_column_tops"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ColumnTops> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.cubeX); buf.writeVarInt(p.cubeZ); buf.writeVarIntArray(p.tops);
        }, buf -> new ColumnTops(buf.readVarInt(), buf.readVarInt(), buf.readVarIntArray(256)));
        @Override public Type<ColumnTops> type() { return TYPE; }
    }

    /** Far-terrain columns the client is missing, by {@link Far} key. */
    public record FarRequest(long[] keys) implements CustomPacketPayload {
        public static final int MAX_KEYS = 512;
        public static final Type<FarRequest> TYPE = new Type<>(id("far_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FarRequest> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeLongArray(p.keys), buf -> new FarRequest(buf.readLongArray(null, MAX_KEYS)));
        @Override public Type<FarRequest> type() { return TYPE; }
    }

    /**
     * One far-terrain column: its lowest section's Y in level units, then each section's vanilla
     * section bytes (voxels as blocks, plus biomes) from the bottom up, empty for an all-air
     * section, then each voxel column's drop (see {@link io.earthplatform.minecraft.cubic.gen.FarColumn#drops}).
     */
    public record FarColumnData(long key, int bottom, List<byte[]> sections, short[] drops) implements CustomPacketPayload {
        public static final Type<FarColumnData> TYPE = new Type<>(id("far_column"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FarColumnData> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeLong(p.key);
            buf.writeInt(p.bottom);
            buf.writeVarInt(p.sections.size());
            for (byte[] section : p.sections) buf.writeByteArray(section);
            for (short drop : p.drops) buf.writeShort(drop);
        }, buf -> {
            long key = buf.readLong();
            int bottom = buf.readInt(), count = Math.min(buf.readVarInt(), Far.MAX_SECTIONS);
            List<byte[]> sections = new ArrayList<>(count);
            for (int i = 0; i < count; i++) sections.add(buf.readByteArray(1 << 20));
            short[] drops = new short[256];
            for (int i = 0; i < 256; i++) drops[i] = buf.readShort();
            return new FarColumnData(key, bottom, sections, drops);
        });
        /** Rough size on the wire, for pacing. */
        public int size() {
            int size = 528;
            for (byte[] section : sections) size += 4 + section.length;
            return size;
        }
        @Override public Type<FarColumnData> type() { return TYPE; }
    }

    /**
     * An Earth world's map scale, sent when a player's far terrain starts: how many metres a block
     * spans at the equator. Map stretch grows toward the poles, so the client measures its haze in
     * ground metres rather than blocks.
     */
    public record EarthScale(double metresPerBlock) implements CustomPacketPayload {
        public static final Type<EarthScale> TYPE = new Type<>(id("earth_scale"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EarthScale> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeDouble(p.metresPerBlock), buf -> new EarthScale(buf.readDouble()));
        @Override public Type<EarthScale> type() { return TYPE; }
    }

    /** Version of this group of payloads; NeoForge refuses a connection whose two sides differ. */
    public static final String VERSION = "cubic-2";

    public static void register(Payloads registrar) {
        registrar.toClient(CubeData.TYPE, CubeData.CODEC, p -> { if (Loader.INSTANCE.isClient()) CubicClientReceiver.cube(p); });
        registrar.toClient(CubeUnload.TYPE, CubeUnload.CODEC, p -> { if (Loader.INSTANCE.isClient()) CubicClientReceiver.unload(p); });
        registrar.toClient(BlockChanges.TYPE, BlockChanges.CODEC, p -> { if (Loader.INSTANCE.isClient()) CubicClientReceiver.changes(p); });
        registrar.toClient(ColumnTops.TYPE, ColumnTops.CODEC, p -> { if (Loader.INSTANCE.isClient()) CubicClientReceiver.tops(p); });
        registrar.toClient(FarColumnData.TYPE, FarColumnData.CODEC, p -> { if (Loader.INSTANCE.isClient()) CubicClientReceiver.far(p); });
        registrar.toClient(EarthScale.TYPE, EarthScale.CODEC, p -> { if (Loader.INSTANCE.isClient()) CubicClientReceiver.scale(p); });
        registrar.toServer(FarRequest.TYPE, FarRequest.CODEC, (p, player) -> {
            if (CubicLevel.of(player.serverLevel()) instanceof ServerCubeWorld world) world.far().request(player, p.keys());
        });
    }
}
