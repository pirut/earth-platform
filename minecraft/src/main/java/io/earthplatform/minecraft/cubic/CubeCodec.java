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
    /** Saved as generated, never changed by play; far terrain skips these, as they match the generator. */
    public static final String UNTOUCHED = "untouched";
    /** A fresh surface cube still owed its first animals. */
    public static final String UNPOPULATED = "unpopulated";
    /** Entities a fresh cube starts with (chest minecarts), not yet placed in the level. */
    public static final String ENTITIES = "entities";
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
        CompoundTag tag = sectionTag(cube.x, cube.y, cube.z, cube.section);
        ListTag entities = new ListTag();
        for (BlockEntity entity : cube.blockEntities.values()) {
            if (!entity.isRemoved()) entities.add(entity.saveWithFullMetadata(registries));
        }
        if (!entities.isEmpty()) tag.put("block_entities", entities);
        if (!cube.edited) tag.putBoolean(UNTOUCHED, true);
        return tag;
    }

    /** Any thread: a cube just as the generator made it, with its block entities still as tags. */
    public CompoundTag writeGenerated(int x, int y, int z, LevelChunkSection section, List<CompoundTag> blockEntities) {
        CompoundTag tag = sectionTag(x, y, z, section);
        if (!blockEntities.isEmpty()) {
            ListTag entities = new ListTag();
            entities.addAll(blockEntities);
            tag.put("block_entities", entities);
        }
        tag.putBoolean(UNTOUCHED, true);
        return tag;
    }

    private CompoundTag sectionTag(int x, int y, int z, LevelChunkSection section) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", VERSION);
        tag.putInt("x", x); tag.putInt("y", y); tag.putInt("z", z);
        tag.put("blocks", STATES.encodeStart(NbtOps.INSTANCE, section.getStates()).getOrThrow());
        tag.put("biomes", biomes.encodeStart(NbtOps.INSTANCE, section.getBiomes()).getOrThrow());
        return tag;
    }

    /** Any thread: the entity tags saved under a key, or none. */
    public static List<CompoundTag> tags(CompoundTag tag, String key) {
        List<CompoundTag> list = new ArrayList<>();
        for (Tag entry : tag.getList(key, Tag.TAG_COMPOUND)) list.add((CompoundTag) entry);
        return list;
    }

    /**
     * Any thread. Block entities stay as tags until the owner thread adopts the cube. Blocks and biomes
     * from a mod that has since been removed become air and plains, like vanilla chunks, so the rest of
     * the cube (someone's build) survives instead of the whole cube being regenerated.
     */
    public Decoded read(CompoundTag tag) {
        PalettedContainer<BlockState> states = STATES.parse(NbtOps.INSTANCE, tag.get("blocks"))
                .promotePartial(error -> missing(tag, error)).getOrThrow();
        PalettedContainerRO<Holder<Biome>> biomeData = biomes.parse(NbtOps.INSTANCE, tag.get("biomes"))
                .promotePartial(error -> missing(tag, error)).getOrThrow();
        LevelChunkSection section = new LevelChunkSection(states, biomeData);
        section.recalcBlockCounts();
        return new Decoded(section, tags(tag, "block_entities"));
    }

    private static void missing(CompoundTag tag, String error) {
        Cubic.LOGGER.warn("Saved cube [{},{},{}]: {}", tag.getInt("x"), tag.getInt("y"), tag.getInt("z"), error);
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
