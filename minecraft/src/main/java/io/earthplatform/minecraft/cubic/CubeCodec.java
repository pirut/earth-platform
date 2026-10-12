package io.earthplatform.minecraft.cubic;

import com.mojang.serialization.Codec;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/** Disk (NBT) and network (section bytes) forms of a cube. */
public final class CubeCodec {
    public static final int VERSION = 1;
    private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomes;
    private final Registry<Biome> biomeRegistry;

    public CubeCodec(Registry<Biome> biomeRegistry) {
        this.biomeRegistry = biomeRegistry;
        this.biomes = PalettedContainer.codecRO(biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
    }

    public record Decoded(LevelChunkSection section, List<CompoundTag> blockEntities) {}

    /** Main thread: block entities are saved through their owners. */
    public CompoundTag write(Cube cube, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", VERSION);
        tag.putInt("x", cube.x); tag.putInt("y", cube.y); tag.putInt("z", cube.z);
        tag.put("blocks", STATES.encodeStart(NbtOps.INSTANCE, cube.section.getStates()).getOrThrow());
        tag.put("biomes", biomes.encodeStart(NbtOps.INSTANCE, cube.section.getBiomes()).getOrThrow());
        ListTag entities = new ListTag();
        for (BlockEntity entity : cube.blockEntities.values()) {
            if (!entity.isRemoved()) entities.add(entity.saveWithFullMetadata(registries));
        }
        if (!entities.isEmpty()) tag.put("block_entities", entities);
        return tag;
    }

    /** Any thread. Block entities stay as tags until the owner thread adopts the cube. */
    public Decoded read(CompoundTag tag) {
        PalettedContainer<BlockState> states = STATES.parse(NbtOps.INSTANCE, tag.get("blocks")).getOrThrow();
        PalettedContainerRO<Holder<Biome>> biomeData = biomes.parse(NbtOps.INSTANCE, tag.get("biomes")).getOrThrow();
        LevelChunkSection section = new LevelChunkSection(states, biomeData);
        section.recalcBlockCounts();
        List<CompoundTag> entities = new ArrayList<>();
        for (Tag entry : tag.getList("block_entities", Tag.TAG_COMPOUND)) entities.add((CompoundTag) entry);
        return new Decoded(section, entities);
    }

    public static byte[] sectionBytes(LevelChunkSection section) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(section.getSerializedSize()));
        section.write(buffer);
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    public static LevelChunkSection readSection(byte[] bytes, Registry<Biome> biomes) {
        LevelChunkSection section = new LevelChunkSection(biomes);
        section.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes)));
        section.recalcBlockCounts();
        return section;
    }

    public Registry<Biome> biomeRegistry() { return biomeRegistry; }
}
