package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.gen.SurfacePacketFixtures;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link CubeCodec} pending-tick NBT persistence (including legacy backward
 * compatibility and unknown registry ID safety) and {@link ServerCubeWorld.PendingTickDispatcher}
 * 6-face neighbor-gated one-shot dispatch (UNDER-01).
 */
class PendingTicksCodecAndDispatchTest {
    private static Registry<Biome> biomes;
    private static CubeCodec codec;

    @BeforeAll static void setup() {
        biomes = SurfacePacketFixtures.biomes();
        codec = new CubeCodec(biomes);
    }

    private static LevelChunkSection emptySection() {
        Holder<Biome> plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        return new LevelChunkSection(
                new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                new PalettedContainer<>(biomes.asHolderIdMap(), plains, PalettedContainer.Strategy.SECTION_BIOMES));
    }

    @Test void cubeCodecRoundTripsBlockAndFluidTicksAtCubicHeights() {
        List<CubeCodec.PendingTick<Block>> blockTicks = List.of(
                new CubeCodec.PendingTick<>(new BlockPos(5, 3205, -12), Blocks.SAND, 2, TickPriority.HIGH),
                new CubeCodec.PendingTick<>(new BlockPos(7, -1020, 4), Blocks.REDSTONE_TORCH, 0, TickPriority.EXTREMELY_HIGH));
        List<CubeCodec.PendingTick<Fluid>> fluidTicks = List.of(
                new CubeCodec.PendingTick<>(new BlockPos(8, 3208, -9), Fluids.WATER, 0, TickPriority.NORMAL),
                new CubeCodec.PendingTick<>(new BlockPos(9, -1018, 3), Fluids.LAVA, 30, TickPriority.LOW));
        CubeCodec.PendingTicks expected = new CubeCodec.PendingTicks(blockTicks, fluidTicks);

        CompoundTag generated = codec.writeGenerated(0, 200, -1, emptySection(), List.of(), expected);
        assertTrue(generated.contains(CubeCodec.BLOCK_TICKS));
        assertTrue(generated.contains(CubeCodec.FLUID_TICKS));

        CubeCodec.Decoded decoded = codec.read(generated);
        assertEquals(expected, decoded.pendingTicks(), "round-tripped pending ticks must match exactly");
    }

    @Test void cubeCodecReadsLegacyNbtWithoutTickTagsAsEmpty() {
        CompoundTag legacy = codec.writeGenerated(1, -4, 2, emptySection(), List.of());
        assertFalse(legacy.contains(CubeCodec.BLOCK_TICKS));
        assertFalse(legacy.contains(CubeCodec.FLUID_TICKS));

        CubeCodec.Decoded decoded = codec.read(legacy);
        assertTrue(decoded.pendingTicks().isEmpty(), "absent tick tags must decode as empty for saved-world compatibility");
    }

    @Test void cubeCodecSkipsUnknownIdsAndEmptyFluidWithoutFailing() {
        CompoundTag tag = codec.writeGenerated(0, 4, 0, emptySection(), List.of());

        ListTag blocks = new ListTag();
        CompoundTag badBlock = new CompoundTag();
        badBlock.putString("id", "nonexistent_mod:deleted_block");
        badBlock.putInt("x", 1); badBlock.putInt("y", 65); badBlock.putInt("z", 2);
        badBlock.putInt("delay", 1); badBlock.putInt("priority", 0);
        blocks.add(badBlock);
        CompoundTag malformedId = new CompoundTag();
        malformedId.putString("id", "NOT A VALID RESOURCE LOCATION!!!");
        malformedId.putInt("x", 2); malformedId.putInt("y", 65); malformedId.putInt("z", 2);
        blocks.add(malformedId);
        CompoundTag goodBlock = new CompoundTag();
        goodBlock.putString("id", "minecraft:sand");
        goodBlock.putInt("x", 3); goodBlock.putInt("y", 66); goodBlock.putInt("z", 4);
        goodBlock.putInt("delay", 3); goodBlock.putInt("priority", TickPriority.HIGH.getValue());
        blocks.add(goodBlock);
        tag.put(CubeCodec.BLOCK_TICKS, blocks);

        ListTag fluids = new ListTag();
        CompoundTag badFluid = new CompoundTag();
        badFluid.putString("id", "nonexistent_mod:deleted_fluid");
        badFluid.putInt("x", 1); badFluid.putInt("y", 65); badFluid.putInt("z", 2);
        fluids.add(badFluid);
        CompoundTag emptyFluid = new CompoundTag();
        emptyFluid.putString("id", "minecraft:empty");
        emptyFluid.putInt("x", 2); emptyFluid.putInt("y", 65); emptyFluid.putInt("z", 2);
        fluids.add(emptyFluid);
        CompoundTag goodFluid = new CompoundTag();
        goodFluid.putString("id", "minecraft:water");
        goodFluid.putInt("x", 5); goodFluid.putInt("y", 67); goodFluid.putInt("z", 6);
        goodFluid.putInt("delay", 0); goodFluid.putInt("priority", TickPriority.NORMAL.getValue());
        fluids.add(goodFluid);
        tag.put(CubeCodec.FLUID_TICKS, fluids);

        CubeCodec.Decoded decoded = codec.read(tag);
        assertEquals(1, decoded.pendingTicks().blocks().size(), "unknown/malformed block IDs must be skipped");
        assertEquals(Blocks.SAND, decoded.pendingTicks().blocks().get(0).type());
        assertEquals(new BlockPos(3, 66, 4), decoded.pendingTicks().blocks().get(0).pos());
        assertEquals(TickPriority.HIGH, decoded.pendingTicks().blocks().get(0).priority());
        assertEquals(1, decoded.pendingTicks().fluids().size(), "unknown and empty fluid IDs must be skipped");
        assertEquals(Fluids.WATER, decoded.pendingTicks().fluids().get(0).type());
        assertEquals(new BlockPos(5, 67, 6), decoded.pendingTicks().fluids().get(0).pos());
    }

    @Test void pendingTickDispatcherWaitsForAllSixNeighborsAndTickingChunkThenFiresOnce() {
        int cx = 3, cy = -2, cz = 5;
        long center = CubeKey.pack(cx, cy, cz);
        long[] neighbors = {
                CubeKey.pack(cx - 1, cy, cz), CubeKey.pack(cx + 1, cy, cz),
                CubeKey.pack(cx, cy - 1, cz), CubeKey.pack(cx, cy + 1, cz),
                CubeKey.pack(cx, cy, cz - 1), CubeKey.pack(cx, cy, cz + 1)
        };

        Cube cube = new Cube(cx, cy, cz, emptySection());
        CubeCodec.PendingTick<Block> bTick = new CubeCodec.PendingTick<>(new BlockPos((cx << 4) + 2, (cy << 4) + 3, (cz << 4) + 4), Blocks.GRAVEL, 1, TickPriority.NORMAL);
        CubeCodec.PendingTick<Fluid> fTick = new CubeCodec.PendingTick<>(new BlockPos((cx << 4) + 8, (cy << 4) + 8, (cz << 4) + 8), Fluids.WATER, 0, TickPriority.NORMAL);
        Long2ObjectOpenHashMap<CubeCodec.PendingTicks> pending = new Long2ObjectOpenHashMap<>();
        pending.put(center, new CubeCodec.PendingTicks(List.of(bTick), List.of(fTick)));

        LongOpenHashSet loaded = new LongOpenHashSet();
        loaded.add(center);
        for (long n : neighbors) loaded.add(n);

        List<CubeCodec.PendingTick<Block>> firedBlocks = new ArrayList<>();
        List<CubeCodec.PendingTick<Fluid>> firedFluids = new ArrayList<>();
        List<Long> drainedKeys = new ArrayList<>();

        // 1. All 6 neighbors loaded, but carrier chunk is NOT ticking -> held.
        assertEquals(0, ServerCubeWorld.PendingTickDispatcher.dispatchReady(
                pending, loaded::contains, (x, z) -> false, firedBlocks::add, firedFluids::add, drainedKeys::add));
        assertTrue(pending.containsKey(center));
        assertTrue(firedBlocks.isEmpty());
        assertTrue(firedFluids.isEmpty());

        // 2. Carrier chunk ticking, but each single missing face neighbor holds dispatch.
        for (long missing : neighbors) {
            loaded.remove(missing);
            assertEquals(0, ServerCubeWorld.PendingTickDispatcher.dispatchReady(
                    pending, loaded::contains, (x, z) -> true, firedBlocks::add, firedFluids::add, drainedKeys::add),
                    "must wait when neighbor " + CubeKey.toString(missing) + " is unloaded");
            assertTrue(pending.containsKey(center));
            loaded.add(missing);
        }

        // 3. All 6 neighbors loaded AND carrier chunk ticking -> fires once, clears pending, marks unsaved.
        assertEquals(1, ServerCubeWorld.PendingTickDispatcher.dispatchReady(
                pending, loaded::contains, (x, z) -> true, firedBlocks::add, firedFluids::add, key -> {
                    drainedKeys.add(key);
                    cube.markUnsaved();
                }));
        assertFalse(pending.containsKey(center));
        assertEquals(List.of(bTick), firedBlocks);
        assertEquals(List.of(fTick), firedFluids);
        assertEquals(List.of(center), drainedKeys);
        assertTrue(cube.dirty && cube.modified, "drained cube must be marked unsaved so ticks never replay on reload");

        // 4. Subsequent ticks never replay.
        assertEquals(0, ServerCubeWorld.PendingTickDispatcher.dispatchReady(
                pending, loaded::contains, (x, z) -> true, firedBlocks::add, firedFluids::add, drainedKeys::add));
        assertEquals(1, firedBlocks.size());
        assertEquals(1, firedFluids.size());
    }

    @Test void pendingTickDispatcherRespectsWorldTopAndBottomBoundaries() {
        int minCy = CubeWorld.MIN_Y >> 4;
        int maxCy = CubeWorld.MAX_Y >> 4;

        LongOpenHashSet bottomLoaded = new LongOpenHashSet();
        bottomLoaded.add(CubeKey.pack(0, minCy, 0));
        bottomLoaded.add(CubeKey.pack(-1, minCy, 0));
        bottomLoaded.add(CubeKey.pack(1, minCy, 0));
        bottomLoaded.add(CubeKey.pack(0, minCy, -1));
        bottomLoaded.add(CubeKey.pack(0, minCy, 1));
        bottomLoaded.add(CubeKey.pack(0, minCy + 1, 0));
        assertTrue(ServerCubeWorld.PendingTickDispatcher.neighborsLoaded(0, minCy, 0, bottomLoaded::contains),
                "world bottom cube only requires the 5 in-range neighbors");

        LongOpenHashSet topLoaded = new LongOpenHashSet();
        topLoaded.add(CubeKey.pack(0, maxCy, 0));
        topLoaded.add(CubeKey.pack(-1, maxCy, 0));
        topLoaded.add(CubeKey.pack(1, maxCy, 0));
        topLoaded.add(CubeKey.pack(0, maxCy, -1));
        topLoaded.add(CubeKey.pack(0, maxCy, 1));
        topLoaded.add(CubeKey.pack(0, maxCy - 1, 0));
        assertTrue(ServerCubeWorld.PendingTickDispatcher.neighborsLoaded(0, maxCy, 0, topLoaded::contains),
                "world top cube only requires the 5 in-range neighbors");
    }

    @Test void undrainedTicksSurviveUnloadReloadAndDoNotReplayAfterDispatchAndResave() {
        int cx = 0, cy = 4, cz = 0;
        long key = CubeKey.pack(cx, cy, cz);
        CubeCodec.PendingTick<Fluid> fountain = new CubeCodec.PendingTick<>(new BlockPos(8, 68, 8), Fluids.WATER, 0, TickPriority.NORMAL);
        CubeCodec.PendingTicks initial = new CubeCodec.PendingTicks(List.of(), List.of(fountain));

        // Saved before all 6 neighbors load -> ticks preserved on disk.
        CompoundTag savedBeforeDispatch = codec.writeGenerated(cx, cy, cz, emptySection(), List.of(), initial);
        CubeCodec.Decoded reloaded = codec.read(savedBeforeDispatch);
        assertEquals(initial, reloaded.pendingTicks());

        // After reload, neighbors load -> dispatch drains pendingTicks and re-save clears NBT.
        Long2ObjectOpenHashMap<CubeCodec.PendingTicks> pending = new Long2ObjectOpenHashMap<>();
        pending.put(key, reloaded.pendingTicks());
        LongOpenHashSet loaded = new LongOpenHashSet(new long[]{
                key, CubeKey.pack(-1, cy, 0), CubeKey.pack(1, cy, 0),
                CubeKey.pack(0, cy - 1, 0), CubeKey.pack(0, cy + 1, 0),
                CubeKey.pack(0, cy, -1), CubeKey.pack(0, cy, 1)
        });
        List<CubeCodec.PendingTick<Fluid>> scheduled = new ArrayList<>();
        assertEquals(1, ServerCubeWorld.PendingTickDispatcher.dispatchReady(
                pending, loaded::contains, (x, z) -> true, b -> {}, scheduled::add, k -> {}));
        assertEquals(List.of(fountain), scheduled);

        CompoundTag savedAfterDispatch = codec.writeGenerated(cx, cy, cz, emptySection(), List.of(),
                pending.getOrDefault(key, CubeCodec.PendingTicks.EMPTY));
        CubeCodec.Decoded reloadedAfterDrain = codec.read(savedAfterDispatch);
        assertTrue(reloadedAfterDrain.pendingTicks().isEmpty(), "reloading a drained cube must not replay ticks");
    }

    @Test void untouchedStoreQueueTracksPendingTicksMetadata() {
        Cube cube = new Cube(2, 3, 4, emptySection());
        cube.modified = true;
        cube.dirty = true;
        CubeCodec.PendingTicks initial = new CubeCodec.PendingTicks(
                List.of(),
                List.of(new CubeCodec.PendingTick<>(new BlockPos(35, 50, 67), Fluids.WATER, 0, TickPriority.NORMAL)));
        Long2ObjectOpenHashMap<CubeCodec.PendingTicks> livePending = new Long2ObjectOpenHashMap<>();
        livePending.put(cube.key, initial);

        ServerCubeWorld.UntouchedStoreQueue<String> queue = new ServerCubeWorld.UntouchedStoreQueue<>(
                () -> false,
                k -> k == cube.key ? cube : null,
                (k, tag) -> {},
                entry -> Objects.equals(entry.pendingTicks(), livePending.getOrDefault(entry.key(), CubeCodec.PendingTicks.EMPTY)),
                (k, err) -> fail(err));

        // Simulate ticks draining while the untouched store encodes asynchronously.
        queue.enqueue(cube, false, List.of(), initial, () -> {
            livePending.remove(cube.key);
            return "encoded-with-ticks";
        }, Runnable::run, Runnable::run);

        assertTrue(cube.dirty, "cube must remain dirty when pendingTicks changed while untouched store encoded");
    }
}
