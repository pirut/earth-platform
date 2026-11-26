package io.earthplatform.minecraft.cubic;

import com.mojang.serialization.Codec;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.TickPriority;

/** Disk (NBT) and network (section bytes) forms of a cube. */
public final class CubeCodec {
    public static final int VERSION = 1;
    /** Saved as generated, never changed by play; far terrain skips these, as they match the generator. */
    public static final String UNTOUCHED = "untouched";
    /** A fresh surface cube still owed its first animals. */
    public static final String UNPOPULATED = "unpopulated";
    /** Entities a fresh cube starts with (chest minecarts), not yet placed in the level. */
    public static final String ENTITIES = "entities";
    /** Optional scheduled block ticks captured during structure generation, awaiting neighbor-gated dispatch. */
    public static final String BLOCK_TICKS = "block_ticks";
    /** Optional scheduled fluid ticks captured during structure generation, awaiting neighbor-gated dispatch. */
    public static final String FLUID_TICKS = "fluid_ticks";
    private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomes;
    private final Registry<Biome> biomeRegistry;

    public CubeCodec(Registry<Biome> biomeRegistry) {
        this.biomeRegistry = biomeRegistry;
        this.biomes = PalettedContainer.codecRO(biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
    }

    /** One scheduled block or fluid tick captured during structure generation, at an absolute real block position. */
    public record PendingTick<T>(BlockPos pos, T type, int delay, TickPriority priority) {
        public PendingTick {
            pos = pos.immutable();
            delay = Math.max(0, delay);
            priority = priority == null ? TickPriority.NORMAL : priority;
        }

        public int x() { return pos.getX(); }
        public int y() { return pos.getY(); }
        public int z() { return pos.getZ(); }
    }

    /** Undrained structure block and fluid ticks belonging to one cube. */
    public record PendingTicks(List<PendingTick<Block>> blocks, List<PendingTick<Fluid>> fluids) {
        public static final PendingTicks EMPTY = new PendingTicks(List.of(), List.of());

        public PendingTicks {
            blocks = blocks == null || blocks.isEmpty() ? List.of() : List.copyOf(blocks);
            fluids = fluids == null || fluids.isEmpty() ? List.of() : List.copyOf(fluids);
        }

        public boolean isEmpty() { return blocks.isEmpty() && fluids.isEmpty(); }
    }

    public record Decoded(LevelChunkSection section, List<CompoundTag> blockEntities, PendingTicks pendingTicks) {
        public Decoded(LevelChunkSection section, List<CompoundTag> blockEntities) {
            this(section, blockEntities, PendingTicks.EMPTY);
        }
    }

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
        return writeGenerated(x, y, z, section, blockEntities, PendingTicks.EMPTY);
    }

    /** Any thread: a generated cube with optional undrained structure ticks. */
    public CompoundTag writeGenerated(int x, int y, int z, LevelChunkSection section, List<CompoundTag> blockEntities, PendingTicks pendingTicks) {
        CompoundTag tag = sectionTag(x, y, z, section);
        if (!blockEntities.isEmpty()) {
            ListTag entities = new ListTag();
            entities.addAll(blockEntities);
            tag.put("block_entities", entities);
        }
        writePendingTicks(tag, pendingTicks);
        tag.putBoolean(UNTOUCHED, true);
        return tag;
    }

    /** Any thread: writes optional pending block/fluid tick lists to a cube tag; omits empty lists for backward compatibility. */
    public static void writePendingTicks(CompoundTag tag, PendingTicks pendingTicks) {
        if (pendingTicks == null || pendingTicks.isEmpty()) return;
        if (!pendingTicks.blocks().isEmpty()) {
            ListTag list = new ListTag();
            for (PendingTick<Block> tick : pendingTicks.blocks()) {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(tick.type());
                if (id == null) continue;
                list.add(tickTag(id, tick.pos(), tick.delay(), tick.priority()));
            }
            if (!list.isEmpty()) tag.put(BLOCK_TICKS, list);
        }
        if (!pendingTicks.fluids().isEmpty()) {
            ListTag list = new ListTag();
            for (PendingTick<Fluid> tick : pendingTicks.fluids()) {
                if (tick.type() == Fluids.EMPTY) continue;
                ResourceLocation id = BuiltInRegistries.FLUID.getKey(tick.type());
                if (id == null) continue;
                list.add(tickTag(id, tick.pos(), tick.delay(), tick.priority()));
            }
            if (!list.isEmpty()) tag.put(FLUID_TICKS, list);
        }
    }

    private static CompoundTag tickTag(ResourceLocation id, BlockPos pos, int delay, TickPriority priority) {
        CompoundTag entry = new CompoundTag();
        entry.putString("id", id.toString());
        entry.putInt("x", pos.getX());
        entry.putInt("y", pos.getY());
        entry.putInt("z", pos.getZ());
        entry.putInt("delay", Math.max(0, delay));
        entry.putInt("priority", priority.getValue());
        return entry;
    }

    /** Filters pending ticks to only those whose absolute block coordinates lie inside {@code cubeKey}. */
    public static PendingTicks filterForCube(long cubeKey, @Nullable PendingTicks ticks) {
        if (ticks == null || ticks.isEmpty()) return PendingTicks.EMPTY;
        List<PendingTick<Block>> blocks = ticks.blocks();
        List<PendingTick<Block>> keptBlocks = null;
        for (int i = 0; i < blocks.size(); i++) {
            PendingTick<Block> tick = blocks.get(i);
            boolean inside = CubeWorld.inRange(tick.y())
                    && CubeKey.pack(tick.x() >> 4, tick.y() >> 4, tick.z() >> 4) == cubeKey;
            if (!inside) {
                if (keptBlocks == null) keptBlocks = new ArrayList<>(blocks.subList(0, i));
            } else if (keptBlocks != null) {
                keptBlocks.add(tick);
            }
        }
        List<PendingTick<Fluid>> fluids = ticks.fluids();
        List<PendingTick<Fluid>> keptFluids = null;
        for (int i = 0; i < fluids.size(); i++) {
            PendingTick<Fluid> tick = fluids.get(i);
            boolean inside = CubeWorld.inRange(tick.y())
                    && CubeKey.pack(tick.x() >> 4, tick.y() >> 4, tick.z() >> 4) == cubeKey;
            if (!inside) {
                if (keptFluids == null) keptFluids = new ArrayList<>(fluids.subList(0, i));
            } else if (keptFluids != null) {
                keptFluids.add(tick);
            }
        }
        if (keptBlocks == null && keptFluids == null) return ticks;
        List<PendingTick<Block>> finalBlocks = keptBlocks != null ? keptBlocks : blocks;
        List<PendingTick<Fluid>> finalFluids = keptFluids != null ? keptFluids : fluids;
        return finalBlocks.isEmpty() && finalFluids.isEmpty()
                ? PendingTicks.EMPTY
                : new PendingTicks(finalBlocks, finalFluids);
    }

    /** Any thread: reads optional pending block/fluid ticks validated against the cube coordinates stored in {@code tag}. */
    public static PendingTicks readPendingTicks(CompoundTag tag) {
        return readPendingTicks(tag, CubeKey.pack(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
    }

    /**
     * Any thread: reads optional pending block/fluid ticks belonging to {@code cubeKey}; absent lists
     * yield empty, unknown registry IDs and coordinates outside {@code cubeKey} are dropped safely.
     */
    public static PendingTicks readPendingTicks(CompoundTag tag, long cubeKey) {
        boolean hasBlocks = tag.contains(BLOCK_TICKS, Tag.TAG_LIST);
        boolean hasFluids = tag.contains(FLUID_TICKS, Tag.TAG_LIST);
        if (!hasBlocks && !hasFluids) return PendingTicks.EMPTY;
        List<PendingTick<Block>> blocks = List.of();
        if (hasBlocks) {
            ListTag list = tag.getList(BLOCK_TICKS, Tag.TAG_COMPOUND);
            if (!list.isEmpty()) {
                List<PendingTick<Block>> parsed = new ArrayList<>(list.size());
                for (Tag raw : list) {
                    CompoundTag entry = (CompoundTag) raw;
                    ResourceLocation id = ResourceLocation.tryParse(entry.getString("id"));
                    if (id == null) continue;
                    var block = BuiltInRegistries.BLOCK.getOptional(id);
                    if (block.isEmpty()) continue;
                    int x = entry.getInt("x"), y = entry.getInt("y"), z = entry.getInt("z");
                    if (!CubeWorld.inRange(y) || CubeKey.pack(x >> 4, y >> 4, z >> 4) != cubeKey) continue;
                    parsed.add(new PendingTick<>(
                            new BlockPos(x, y, z),
                            block.get(),
                            Math.max(0, entry.getInt("delay")),
                            TickPriority.byValue(entry.getInt("priority"))));
                }
                blocks = parsed;
            }
        }
        List<PendingTick<Fluid>> fluids = List.of();
        if (hasFluids) {
            ListTag list = tag.getList(FLUID_TICKS, Tag.TAG_COMPOUND);
            if (!list.isEmpty()) {
                List<PendingTick<Fluid>> parsed = new ArrayList<>(list.size());
                for (Tag raw : list) {
                    CompoundTag entry = (CompoundTag) raw;
                    ResourceLocation id = ResourceLocation.tryParse(entry.getString("id"));
                    if (id == null) continue;
                    var fluid = BuiltInRegistries.FLUID.getOptional(id);
                    if (fluid.isEmpty() || fluid.get() == Fluids.EMPTY) continue;
                    int x = entry.getInt("x"), y = entry.getInt("y"), z = entry.getInt("z");
                    if (!CubeWorld.inRange(y) || CubeKey.pack(x >> 4, y >> 4, z >> 4) != cubeKey) continue;
                    parsed.add(new PendingTick<>(
                            new BlockPos(x, y, z),
                            fluid.get(),
                            Math.max(0, entry.getInt("delay")),
                            TickPriority.byValue(entry.getInt("priority"))));
                }
                fluids = parsed;
            }
        }
        return blocks.isEmpty() && fluids.isEmpty() ? PendingTicks.EMPTY : new PendingTicks(blocks, fluids);
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
        return read(tag, CubeKey.pack(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
    }

    /** Any thread: decodes a saved cube tag, validating pending ticks against {@code expectedCubeKey}. */
    public Decoded read(CompoundTag tag, long expectedCubeKey) {
        PalettedContainer<BlockState> states = STATES.parse(NbtOps.INSTANCE, tag.get("blocks"))
                .promotePartial(error -> missing(tag, error)).getOrThrow();
        PalettedContainerRO<Holder<Biome>> biomeData = biomes.parse(NbtOps.INSTANCE, tag.get("biomes"))
                .promotePartial(error -> missing(tag, error)).getOrThrow();
        LevelChunkSection section = new LevelChunkSection(states, biomeData);
        section.recalcBlockCounts();
        return new Decoded(section, tags(tag, "block_entities"), readPendingTicks(tag, expectedCubeKey));
    }

    private static void missing(CompoundTag tag, String error) {
        EarthPlatformMod.LOGGER.warn("Saved cube [{},{},{}]: {}", tag.getInt("x"), tag.getInt("y"), tag.getInt("z"), error);
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
