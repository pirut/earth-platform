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
import net.minecraft.core.RegistryAccess;
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
 * Regression tests for {@link CubeCodec} pending-tick NBT persistence (including coordinate validation,
 * legacy backward compatibility, and unknown registry ID safety) and
 * {@link ServerCubeWorld.PendingTickDispatcher} cube-owned countdown, unload/reload persistence,
 * priority ordering, and 6-face neighbor-gated execution (UNDER-01).
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

    private static CompoundTag encodeLiveCube(Cube cube, CubeCodec.PendingTicks ticks) {
        CompoundTag tag = codec.write(cube, RegistryAccess.EMPTY);
        CubeCodec.writePendingTicks(tag, ticks);
        return tag;
    }

    @Test void cubeCodecRoundTripsBlockAndFluidTicksAtCubicHeights() {
        // High positive cubic height: cube (0, 200, -1) -> x in [0,15], y in [3200,3215], z in [-16,-1].
        List<CubeCodec.PendingTick<Block>> highBlocks = List.of(
                new CubeCodec.PendingTick<>(new BlockPos(5, 3205, -12), Blocks.SAND, 2, TickPriority.HIGH),
                new CubeCodec.PendingTick<>(new BlockPos(7, 3214, -4), Blocks.REDSTONE_TORCH, 0, TickPriority.EXTREMELY_HIGH));
        List<CubeCodec.PendingTick<Fluid>> highFluids = List.of(
                new CubeCodec.PendingTick<>(new BlockPos(8, 3208, -9), Fluids.WATER, 0, TickPriority.NORMAL),
                new CubeCodec.PendingTick<>(new BlockPos(9, 3201, -3), Fluids.LAVA, 30, TickPriority.LOW));
        CubeCodec.PendingTicks highExpected = new CubeCodec.PendingTicks(highBlocks, highFluids);

        CompoundTag highTag = codec.writeGenerated(0, 200, -1, emptySection(), List.of(), highExpected);
        assertTrue(highTag.contains(CubeCodec.BLOCK_TICKS));
        assertTrue(highTag.contains(CubeCodec.FLUID_TICKS));
        assertEquals(highExpected, codec.read(highTag).pendingTicks(), "round-tripped high cubic pending ticks must match");

        // Deep negative cubic height: cube (0, -64, 0) -> x in [0,15], y in [-1024,-1009], z in [0,15].
        CubeCodec.PendingTicks deepExpected = new CubeCodec.PendingTicks(
                List.of(new CubeCodec.PendingTick<>(new BlockPos(7, -1020, 4), Blocks.REDSTONE_TORCH, 0, TickPriority.EXTREMELY_HIGH)),
                List.of(new CubeCodec.PendingTick<>(new BlockPos(9, -1018, 3), Fluids.LAVA, 30, TickPriority.LOW)));
        CompoundTag deepTag = codec.writeGenerated(0, -64, 0, emptySection(), List.of(), deepExpected);
        assertEquals(deepExpected, codec.read(deepTag, CubeKey.pack(0, -64, 0)).pendingTicks(),
                "round-tripped deep negative cubic pending ticks must match");
    }

    @Test void cubeCodecDropsOutOfCubeAndOutOfWorldRangeTicks() {
        int cx = 2, cy = 4, cz = -1; // x in [32,47], y in [64,79], z in [-16,-1]
        long key = CubeKey.pack(cx, cy, cz);
        CubeCodec.PendingTick<Block> insideBlock = new CubeCodec.PendingTick<>(new BlockPos(35, 70, -5), Blocks.SAND, 2, TickPriority.NORMAL);
        CubeCodec.PendingTick<Block> neighborBlock = new CubeCodec.PendingTick<>(new BlockPos(48, 70, -5), Blocks.GRAVEL, 1, TickPriority.NORMAL);
        CubeCodec.PendingTick<Block> outOfRangeBlock = new CubeCodec.PendingTick<>(new BlockPos(35, CubeWorld.MAX_Y + 1, -5), Blocks.SAND, 0, TickPriority.NORMAL);
        CubeCodec.PendingTick<Fluid> insideFluid = new CubeCodec.PendingTick<>(new BlockPos(40, 75, -1), Fluids.WATER, 0, TickPriority.HIGH);
        CubeCodec.PendingTick<Fluid> neighborFluid = new CubeCodec.PendingTick<>(new BlockPos(40, 80, -1), Fluids.LAVA, 5, TickPriority.LOW);

        CompoundTag tag = codec.writeGenerated(cx, cy, cz, emptySection(), List.of(),
                new CubeCodec.PendingTicks(
                        List.of(insideBlock, neighborBlock, outOfRangeBlock),
                        List.of(insideFluid, neighborFluid)));

        CubeCodec.Decoded decoded = codec.read(tag, key);
        assertEquals(List.of(insideBlock), decoded.pendingTicks().blocks(), "out-of-cube and out-of-range block ticks must be dropped on decode");
        assertEquals(List.of(insideFluid), decoded.pendingTicks().fluids(), "out-of-cube fluid ticks must be dropped on decode");

        // Passing a mismatched expectedCubeKey drops all ticks from the wrong cubes.
        CubeCodec.Decoded wrongCube = codec.read(tag, CubeKey.pack(cx + 2, cy, cz));
        assertTrue(wrongCube.pendingTicks().blocks().isEmpty());
        assertTrue(wrongCube.pendingTicks().fluids().isEmpty());
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

    @Test void pendingTickDispatcherWaitsForAllSixNeighborsAndTickingChunkThenFiresDueTicksOnce() {
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
        LongOpenHashSet loaded = new LongOpenHashSet();
        loaded.add(center);
        for (long n : neighbors) loaded.add(n);

        ServerCubeWorld.PendingTickDispatcher dispatcher = new ServerCubeWorld.PendingTickDispatcher();
        dispatcher.put(center, new CubeCodec.PendingTicks(List.of(bTick), List.of(fTick)));

        List<CubeCodec.PendingTick<Block>> firedBlocks = new ArrayList<>();
        List<CubeCodec.PendingTick<Fluid>> firedFluids = new ArrayList<>();
        List<Long> changedKeys = new ArrayList<>();

        // 1. All 6 neighbors loaded, but carrier chunk is NOT ticking -> held without decrementing delay.
        assertEquals(0, dispatcher.tick(loaded::contains, (x, z) -> false, firedBlocks::add, firedFluids::add, changedKeys::add));
        assertEquals(1, dispatcher.get(center).blocks().get(0).delay());
        assertTrue(firedBlocks.isEmpty());
        assertTrue(firedFluids.isEmpty());

        // 2. Carrier chunk ticking, but each single missing face neighbor holds dispatch.
        for (long missing : neighbors) {
            loaded.remove(missing);
            dispatcher.remove(missing);
            assertEquals(0, dispatcher.tick(loaded::contains, (x, z) -> true, firedBlocks::add, firedFluids::add, changedKeys::add),
                    "must wait when neighbor " + CubeKey.toString(missing) + " is unloaded");
            assertEquals(1, dispatcher.get(center).blocks().get(0).delay());
            loaded.add(missing);
            dispatcher.noteResidencyChange();
        }

        // 3. First gated tick: delay-0 fluid executes immediately; delay-1 block decrements to 0 and stays cube-owned.
        assertEquals(1, dispatcher.tick(loaded::contains, (x, z) -> true, firedBlocks::add, firedFluids::add, key -> {
            changedKeys.add(key);
            cube.markUnsaved();
        }));
        assertEquals(List.of(fTick), firedFluids);
        assertTrue(firedBlocks.isEmpty(), "delay-1 tick must stay cube-owned until its delay reaches 0");
        assertTrue(dispatcher.containsKey(center));
        assertEquals(0, dispatcher.get(center).blocks().get(0).delay());
        assertTrue(cube.dirty && cube.modified && !cube.edited, "markUnsaved must keep UNTOUCHED intact");

        // 4. Second gated tick: block tick now due -> fires once and clears pending queue.
        assertEquals(1, dispatcher.tick(loaded::contains, (x, z) -> true, firedBlocks::add, firedFluids::add, changedKeys::add));
        assertFalse(dispatcher.containsKey(center));
        assertEquals(1, firedBlocks.size());
        assertEquals(bTick.pos(), firedBlocks.get(0).pos());
        assertEquals(bTick.type(), firedBlocks.get(0).type());
        assertEquals(0, firedBlocks.get(0).delay());

        // 5. Subsequent ticks never replay.
        assertEquals(0, dispatcher.tick(loaded::contains, (x, z) -> true, firedBlocks::add, firedFluids::add, changedKeys::add));
        assertEquals(1, firedBlocks.size());
        assertEquals(1, firedFluids.size());
    }

    @Test void delayedTickPersistsRemainingDelayAcrossUnloadReloadAndWaitsForNeighbors() {
        int cx = 1, cy = -10, cz = 2;
        long key = CubeKey.pack(cx, cy, cz);
        long missingNeighbor = CubeKey.pack(cx + 1, cy, cz);
        long[] allNeighbors = {
                CubeKey.pack(cx - 1, cy, cz), missingNeighbor,
                CubeKey.pack(cx, cy - 1, cz), CubeKey.pack(cx, cy + 1, cz),
                CubeKey.pack(cx, cy, cz - 1), CubeKey.pack(cx, cy, cz + 1)
        };

        Cube cube = new Cube(cx, cy, cz, emptySection());
        Long2ObjectOpenHashMap<Cube> liveCubes = new Long2ObjectOpenHashMap<>();
        liveCubes.put(key, cube);
        BlockPos pos = new BlockPos((cx << 4) + 5, (cy << 4) + 6, (cz << 4) + 7);
        CubeCodec.PendingTick<Block> delayed = new CubeCodec.PendingTick<>(pos, Blocks.SAND, 3, TickPriority.HIGH);

        LongOpenHashSet loaded = new LongOpenHashSet();
        loaded.add(key);
        for (long n : allNeighbors) loaded.add(n);

        ServerCubeWorld.PendingTickDispatcher dispatcher = new ServerCubeWorld.PendingTickDispatcher();
        dispatcher.put(key, new CubeCodec.PendingTicks(List.of(delayed), List.of()));
        List<CubeCodec.PendingTick<Block>> fired = new ArrayList<>();
        java.util.function.LongConsumer markUnsaved = k -> ServerCubeWorld.PendingTickDispatcher.markCubeUnsaved(k, liveCubes::get);

        // Tick 1 while resident + neighbor-gated: delay 3 -> 2, does not fire, production onChanged marks cube unsaved.
        assertEquals(0, dispatcher.tick(loaded::contains, (x, z) -> true, fired::add, f -> {}, markUnsaved));
        assertTrue(fired.isEmpty());
        assertTrue(cube.dirty && cube.modified && !cube.edited);
        assertEquals(2, dispatcher.get(key).blocks().get(0).delay());

        // Source cube unloads while carrier chunk continues ticking -> save via real CubeCodec.write() + writePendingTicks().
        CompoundTag savedOnUnload = encodeLiveCube(cube, dispatcher.get(key));
        assertTrue(savedOnUnload.getBoolean(CubeCodec.UNTOUCHED));
        loaded.remove(key);
        liveCubes.remove(key);
        dispatcher.remove(key);

        // Carrier chunk keeps ticking for several ticks while source cube is unloaded -> never executes!
        for (int i = 0; i < 5; i++) {
            assertEquals(0, dispatcher.tick(loaded::contains, (x, z) -> true, fired::add, f -> {}, markUnsaved));
        }
        assertTrue(fired.isEmpty(), "unloaded cube must never execute pending ticks");

        // Reload cube from NBT -> remaining delay 2 must have survived unload/reload.
        CubeCodec.Decoded reloaded = codec.read(savedOnUnload, key);
        assertEquals(1, reloaded.pendingTicks().blocks().size());
        assertEquals(2, reloaded.pendingTicks().blocks().get(0).delay(), "remaining delay 2 must persist through unload/reload");
        Cube reloadedCube = new Cube(cx, cy, cz, reloaded.section());
        assertFalse(reloadedCube.dirty || reloadedCube.modified || reloadedCube.edited);

        // Re-adopt cube, but with one face neighbor absent -> countdown stays paused at delay 2 and cube stays clean.
        loaded.add(key);
        liveCubes.put(key, reloadedCube);
        loaded.remove(missingNeighbor);
        dispatcher.put(key, reloaded.pendingTicks());
        assertEquals(0, dispatcher.tick(loaded::contains, (x, z) -> true, fired::add, f -> {}, markUnsaved));
        assertEquals(2, dispatcher.get(key).blocks().get(0).delay(), "countdown must pause while a face neighbor is missing");
        assertFalse(reloadedCube.dirty, "paused cube must not be marked unsaved");

        // Neighbor loads -> countdown resumes: 2 -> 1 -> 0 -> executes, marking reloadedCube unsaved via production onChanged!
        loaded.add(missingNeighbor);
        dispatcher.noteResidencyChange();
        assertEquals(0, dispatcher.tick(loaded::contains, (x, z) -> true, fired::add, f -> {}, markUnsaved));
        assertEquals(1, dispatcher.get(key).blocks().get(0).delay());
        assertTrue(reloadedCube.dirty && reloadedCube.modified && !reloadedCube.edited,
                "production onChanged callback must mark reloaded cube unsaved without setting edited");
        assertEquals(0, dispatcher.tick(loaded::contains, (x, z) -> true, fired::add, f -> {}, markUnsaved));
        assertEquals(0, dispatcher.get(key).blocks().get(0).delay());
        assertTrue(fired.isEmpty());

        reloadedCube.dirty = false;
        assertEquals(1, dispatcher.tick(loaded::contains, (x, z) -> true, fired::add, f -> {}, markUnsaved));
        assertTrue(reloadedCube.dirty && reloadedCube.modified && !reloadedCube.edited,
                "draining final due tick must mark reloaded cube unsaved via production onChanged");
        assertEquals(1, fired.size());
        assertEquals(pos, fired.get(0).pos());
        assertEquals(Blocks.SAND, fired.get(0).type());
        assertEquals(TickPriority.HIGH, fired.get(0).priority());
        assertFalse(dispatcher.containsKey(key));

        // Re-saving via real CubeCodec.write() + writePendingTicks() after drain clears tick tags so reload never replays.
        CompoundTag savedAfterDrain = encodeLiveCube(reloadedCube, dispatcher.getOrDefault(key, CubeCodec.PendingTicks.EMPTY));
        assertFalse(savedAfterDrain.contains(CubeCodec.BLOCK_TICKS));
        assertFalse(savedAfterDrain.contains(CubeCodec.FLUID_TICKS));
        assertTrue(codec.read(savedAfterDrain, key).pendingTicks().isEmpty());
    }

    @Test void pendingTickDispatcherSortsByPriorityAndSkipsRescanningUnreadyEdgeCubes() {
        int cx = 0, cy = 4, cz = 0;
        long key = CubeKey.pack(cx, cy, cz);
        long missing = CubeKey.pack(cx + 1, cy, cz);
        LongOpenHashSet loaded = new LongOpenHashSet(new long[]{
                key, CubeKey.pack(cx - 1, cy, cz),
                CubeKey.pack(cx, cy - 1, cz), CubeKey.pack(cx, cy + 1, cz),
                CubeKey.pack(cx, cy, cz - 1), CubeKey.pack(cx, cy, cz + 1)
        });

        CubeCodec.PendingTick<Block> low = new CubeCodec.PendingTick<>(new BlockPos(1, 65, 1), Blocks.SAND, 0, TickPriority.LOW);
        CubeCodec.PendingTick<Block> highFirst = new CubeCodec.PendingTick<>(new BlockPos(2, 65, 1), Blocks.GRAVEL, 0, TickPriority.HIGH);
        CubeCodec.PendingTick<Block> extHigh = new CubeCodec.PendingTick<>(new BlockPos(3, 65, 1), Blocks.REDSTONE_TORCH, 0, TickPriority.EXTREMELY_HIGH);
        CubeCodec.PendingTick<Block> highSecond = new CubeCodec.PendingTick<>(new BlockPos(4, 65, 1), Blocks.SAND, 0, TickPriority.HIGH);

        ServerCubeWorld.PendingTickDispatcher dispatcher = new ServerCubeWorld.PendingTickDispatcher();
        dispatcher.put(key, new CubeCodec.PendingTicks(List.of(low, highFirst, extHigh, highSecond), List.of()));
        List<CubeCodec.PendingTick<Block>> order = new ArrayList<>();

        // Initial scan finds the edge cube is missing a neighbor.
        assertEquals(0, dispatcher.tick(loaded::contains, (x, z) -> true, order::add, f -> {}, k -> {}));
        assertEquals(1, dispatcher.lastScannedCount());

        // Subsequent ticks with unchanged residency must NOT re-scan unready edge cubes.
        assertEquals(0, dispatcher.tick(loaded::contains, (x, z) -> true, order::add, f -> {}, k -> {}));
        assertEquals(0, dispatcher.lastScannedCount(), "unready edge cube must not be rescanned without residency change");

        // Once the missing neighbor loads, residency change triggers scan and executes in stable ascending priority order.
        loaded.add(missing);
        dispatcher.noteResidencyChange();
        assertEquals(4, dispatcher.tick(loaded::contains, (x, z) -> true, order::add, f -> {}, k -> {}));
        assertEquals(List.of(extHigh, highFirst, highSecond, low), order,
                "due ticks must execute in ascending TickPriority order with stable tie-breaking");
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

    @Test void freshDeepCubeWithPendingTicksIsMarkedUnsavedPreservingUntouchedAndSurvivesCodecWrite() {
        // Deep cube far outside the surface save band (cy = -200 -> y = -3200..-3185).
        Cube deep = new Cube(0, -200, 0, emptySection());
        CubeCodec.PendingTicks ticks = new CubeCodec.PendingTicks(
                List.of(),
                List.of(new CubeCodec.PendingTick<>(new BlockPos(8, -3192, 8), Fluids.WATER, 0, TickPriority.NORMAL)));

        ServerCubeWorld.initializeAdoptedCubeFlags(deep, false, false, false, ticks);
        assertTrue(deep.dirty && deep.modified, "fresh deep cube with pending ticks must be marked unsaved so store() persists it on unload");
        assertTrue(!deep.edited, "fresh deep cube with pending ticks must remain UNTOUCHED");

        // Verify the real CubeCodec.write() + writePendingTicks() path persists the ticks cleanly with UNTOUCHED intact.
        CompoundTag saved = encodeLiveCube(deep, ticks);
        assertTrue(saved.getBoolean(CubeCodec.UNTOUCHED));
        assertTrue(saved.contains(CubeCodec.FLUID_TICKS));
        assertEquals(ticks, codec.read(saved, deep.key).pendingTicks());

        // Contrast: fresh cube with no pending ticks stays clean (dirty == false so store() skips it).
        Cube cleanDeep = new Cube(1, -200, 0, emptySection());
        ServerCubeWorld.initializeAdoptedCubeFlags(cleanDeep, false, false, false, CubeCodec.PendingTicks.EMPTY);
        assertFalse(cleanDeep.dirty, "fresh cube without pending ticks must remain clean (dirty == false)");
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

    @Test void cubeCodecDropsTwentyTwoBitWrapAliasedCoordinatesInReadAndFilter() {
        int cx = 2, cy = 4, cz = -1; // x in [32,47], y in [64,79], z in [-16,-1]
        long key = CubeKey.pack(cx, cy, cz);
        int wrapBlocks = 1 << 26; // 1 << 22 cubes == CubeKey XZ wrap period

        BlockPos validBlockPos = new BlockPos(35, 70, -5);
        BlockPos xPlusWrap = new BlockPos(35 + wrapBlocks, 70, -5);
        BlockPos xMinusWrap = new BlockPos(35 - wrapBlocks, 70, -5);
        BlockPos zPlusWrap = new BlockPos(35, 70, -5 + wrapBlocks);

        BlockPos validFluidPos = new BlockPos(40, 75, -1);
        BlockPos fluidXWrap = new BlockPos(40 + wrapBlocks, 75, -1);
        BlockPos fluidZWrap = new BlockPos(40, 75, -1 - wrapBlocks);

        // Prove these corrupt coordinates actually alias to the same packed CubeKey.
        assertEquals(key, CubeKey.ofBlock(xPlusWrap));
        assertEquals(key, CubeKey.ofBlock(xMinusWrap));
        assertEquals(key, CubeKey.ofBlock(zPlusWrap));
        assertEquals(key, CubeKey.ofBlock(fluidXWrap));
        assertEquals(key, CubeKey.ofBlock(fluidZWrap));

        CubeCodec.PendingTick<Block> validBlock = new CubeCodec.PendingTick<>(validBlockPos, Blocks.SAND, 2, TickPriority.NORMAL);
        CubeCodec.PendingTick<Block> wrappedBlock1 = new CubeCodec.PendingTick<>(xPlusWrap, Blocks.GRAVEL, 1, TickPriority.NORMAL);
        CubeCodec.PendingTick<Block> wrappedBlock2 = new CubeCodec.PendingTick<>(xMinusWrap, Blocks.SAND, 0, TickPriority.HIGH);
        CubeCodec.PendingTick<Block> wrappedBlock3 = new CubeCodec.PendingTick<>(zPlusWrap, Blocks.REDSTONE_TORCH, 0, TickPriority.LOW);

        CubeCodec.PendingTick<Fluid> validFluid = new CubeCodec.PendingTick<>(validFluidPos, Fluids.WATER, 0, TickPriority.HIGH);
        CubeCodec.PendingTick<Fluid> wrappedFluid1 = new CubeCodec.PendingTick<>(fluidXWrap, Fluids.LAVA, 4, TickPriority.NORMAL);
        CubeCodec.PendingTick<Fluid> wrappedFluid2 = new CubeCodec.PendingTick<>(fluidZWrap, Fluids.WATER, 1, TickPriority.LOW);

        CubeCodec.PendingTicks mixed = new CubeCodec.PendingTicks(
                List.of(validBlock, wrappedBlock1, wrappedBlock2, wrappedBlock3),
                List.of(validFluid, wrappedFluid1, wrappedFluid2));

        // 1. CubeCodec.filterForCube must drop all 22-bit wrap aliases.
        CubeCodec.PendingTicks filtered = CubeCodec.filterForCube(key, mixed);
        assertEquals(List.of(validBlock), filtered.blocks(), "filterForCube must reject 22-bit wrap-aliased block ticks");
        assertEquals(List.of(validFluid), filtered.fluids(), "filterForCube must reject 22-bit wrap-aliased fluid ticks");

        // 2. CubeCodec.readPendingTicks / codec.read must also drop all 22-bit wrap aliases.
        CompoundTag tag = codec.writeGenerated(cx, cy, cz, emptySection(), List.of(), mixed);
        CubeCodec.PendingTicks readDirect = CubeCodec.readPendingTicks(tag, key);
        assertEquals(List.of(validBlock), readDirect.blocks(), "readPendingTicks must reject 22-bit wrap-aliased block ticks");
        assertEquals(List.of(validFluid), readDirect.fluids(), "readPendingTicks must reject 22-bit wrap-aliased fluid ticks");

        CubeCodec.Decoded decoded = codec.read(tag, key);
        assertEquals(List.of(validBlock), decoded.pendingTicks().blocks(), "codec.read must reject 22-bit wrap-aliased block ticks");
        assertEquals(List.of(validFluid), decoded.pendingTicks().fluids(), "codec.read must reject 22-bit wrap-aliased fluid ticks");
    }

    private static void addCubeWithSixNeighbors(LongOpenHashSet loaded, int cx, int cy, int cz) {
        loaded.add(CubeKey.pack(cx, cy, cz));
        loaded.add(CubeKey.pack(cx - 1, cy, cz));
        loaded.add(CubeKey.pack(cx + 1, cy, cz));
        loaded.add(CubeKey.pack(cx, cy - 1, cz));
        loaded.add(CubeKey.pack(cx, cy + 1, cz));
        loaded.add(CubeKey.pack(cx, cy, cz - 1));
        loaded.add(CubeKey.pack(cx, cy, cz + 1));
    }

    private static void assertCrossCubePriorityOrder(int ax, int ay, int az, int bx, int by, int bz) {
        long keyA = CubeKey.pack(ax, ay, az);
        long keyB = CubeKey.pack(bx, by, bz);

        LongOpenHashSet loaded = new LongOpenHashSet();
        addCubeWithSixNeighbors(loaded, ax, ay, az);
        addCubeWithSixNeighbors(loaded, bx, by, bz);

        CubeCodec.PendingTick<Block> lowBlockA = new CubeCodec.PendingTick<>(
                new BlockPos((ax << 4) + 2, (ay << 4) + 2, (az << 4) + 2), Blocks.SAND, 0, TickPriority.LOW);
        CubeCodec.PendingTick<Block> highBlockB = new CubeCodec.PendingTick<>(
                new BlockPos((bx << 4) + 3, (by << 4) + 3, (bz << 4) + 3), Blocks.REDSTONE_TORCH, 0, TickPriority.EXTREMELY_HIGH);
        CubeCodec.PendingTick<Fluid> lowFluidA = new CubeCodec.PendingTick<>(
                new BlockPos((ax << 4) + 4, (ay << 4) + 4, (az << 4) + 4), Fluids.LAVA, 0, TickPriority.LOW);
        CubeCodec.PendingTick<Fluid> highFluidB = new CubeCodec.PendingTick<>(
                new BlockPos((bx << 4) + 5, (by << 4) + 5, (bz << 4) + 5), Fluids.WATER, 0, TickPriority.EXTREMELY_HIGH);

        ServerCubeWorld.PendingTickDispatcher dispatcher = new ServerCubeWorld.PendingTickDispatcher();
        dispatcher.put(keyA, new CubeCodec.PendingTicks(List.of(lowBlockA), List.of(lowFluidA)));
        dispatcher.put(keyB, new CubeCodec.PendingTicks(List.of(highBlockB), List.of(highFluidB)));

        List<CubeCodec.PendingTick<Block>> blockOrder = new ArrayList<>();
        List<CubeCodec.PendingTick<Fluid>> fluidOrder = new ArrayList<>();
        assertEquals(4, dispatcher.tick(loaded::contains, (x, z) -> true, blockOrder::add, fluidOrder::add, k -> {}));
        assertEquals(List.of(highBlockB, lowBlockA), blockOrder,
                "EXTREMELY_HIGH block tick in cube B must execute before LOW block tick in cube A");
        assertEquals(List.of(highFluidB, lowFluidA), fluidOrder,
                "EXTREMELY_HIGH fluid tick in cube B must execute before LOW fluid tick in cube A");
    }

    @Test void pendingTickDispatcherOrdersDueTicksAcrossMultipleCubesByPriority() {
        // Test both keyA < keyB and keyA > keyB so candidate sort order cannot mask missing cross-cube priority sorting.
        assertTrue(CubeKey.pack(1, 4, 1) < CubeKey.pack(5, 4, 5));
        assertCrossCubePriorityOrder(1, 4, 1, 5, 4, 5);
        assertCrossCubePriorityOrder(5, 4, 5, 1, 4, 1);
    }

    @Test void productionRecheckHelpersVerifyLiveStateAndMidPassUnloads() {
        // Three non-adjacent cubes so unloading one cube or one neighbor can be tested independently within one pass.
        int ax = 0, ay = 4, az = 0;
        int bx = 10, by = 4, bz = 10;
        int cx = 20, cy = 4, cz = 20;
        long keyA = CubeKey.pack(ax, ay, az);
        long keyB = CubeKey.pack(bx, by, bz);
        long keyC = CubeKey.pack(cx, cy, cz);
        long neighborOfC = CubeKey.pack(cx + 1, cy, cz);

        Cube cubeA = new Cube(ax, ay, az, emptySection());
        Cube cubeB = new Cube(bx, by, bz, emptySection());
        Cube cubeC = new Cube(cx, cy, cz, emptySection());
        cubeA.set(1, 1, 1, Blocks.SAND.defaultBlockState(), false);
        cubeA.set(2, 1, 1, Blocks.STONE.defaultBlockState(), false); // mismatches GRAVEL tick
        cubeA.set(3, 1, 1, Blocks.WATER.defaultBlockState(), false);
        cubeA.set(4, 1, 1, Blocks.AIR.defaultBlockState(), false);   // mismatches LAVA tick
        cubeB.set(1, 1, 1, Blocks.SAND.defaultBlockState(), false);
        cubeB.set(3, 1, 1, Blocks.WATER.defaultBlockState(), false);
        cubeC.set(1, 1, 1, Blocks.SAND.defaultBlockState(), false);
        cubeC.set(3, 1, 1, Blocks.WATER.defaultBlockState(), false);

        Long2ObjectOpenHashMap<Cube> liveCubes = new Long2ObjectOpenHashMap<>();
        liveCubes.put(keyA, cubeA);
        liveCubes.put(keyB, cubeB);
        liveCubes.put(keyC, cubeC);

        LongOpenHashSet loaded = new LongOpenHashSet();
        addCubeWithSixNeighbors(loaded, ax, ay, az);
        addCubeWithSixNeighbors(loaded, bx, by, bz);
        addCubeWithSixNeighbors(loaded, cx, cy, cz);

        BlockPos aMatchBlock = new BlockPos((ax << 4) + 1, (ay << 4) + 1, (az << 4) + 1);
        BlockPos aMismatchBlock = new BlockPos((ax << 4) + 2, (ay << 4) + 1, (az << 4) + 1);
        BlockPos aMatchFluid = new BlockPos((ax << 4) + 3, (ay << 4) + 1, (az << 4) + 1);
        BlockPos aMismatchFluid = new BlockPos((ax << 4) + 4, (ay << 4) + 1, (az << 4) + 1);
        BlockPos bBlockPos = new BlockPos((bx << 4) + 1, (by << 4) + 1, (bz << 4) + 1);
        BlockPos bFluidPos = new BlockPos((bx << 4) + 3, (by << 4) + 1, (bz << 4) + 1);
        BlockPos cBlockPos = new BlockPos((cx << 4) + 1, (cy << 4) + 1, (cz << 4) + 1);
        BlockPos cFluidPos = new BlockPos((cx << 4) + 3, (cy << 4) + 1, (cz << 4) + 1);

        ServerCubeWorld.PendingTickDispatcher dispatcher = new ServerCubeWorld.PendingTickDispatcher();
        dispatcher.put(keyA, new CubeCodec.PendingTicks(
                List.of(
                        new CubeCodec.PendingTick<>(aMatchBlock, Blocks.SAND, 0, TickPriority.EXTREMELY_HIGH),
                        new CubeCodec.PendingTick<>(aMismatchBlock, Blocks.GRAVEL, 0, TickPriority.HIGH)),
                List.of(
                        new CubeCodec.PendingTick<>(aMatchFluid, Fluids.WATER, 0, TickPriority.EXTREMELY_HIGH),
                        new CubeCodec.PendingTick<>(aMismatchFluid, Fluids.LAVA, 0, TickPriority.HIGH))));
        dispatcher.put(keyB, new CubeCodec.PendingTicks(
                List.of(new CubeCodec.PendingTick<>(bBlockPos, Blocks.SAND, 0, TickPriority.LOW)),
                List.of(new CubeCodec.PendingTick<>(bFluidPos, Fluids.WATER, 0, TickPriority.LOW))));
        dispatcher.put(keyC, new CubeCodec.PendingTicks(
                List.of(new CubeCodec.PendingTick<>(cBlockPos, Blocks.SAND, 0, TickPriority.LOW)),
                List.of(new CubeCodec.PendingTick<>(cFluidPos, Fluids.WATER, 0, TickPriority.LOW))));

        List<BlockPos> ranBlocks = new ArrayList<>();
        List<BlockPos> ranFluids = new ArrayList<>();

        dispatcher.tick(
                loaded::contains,
                (x, z) -> true,
                tick -> ServerCubeWorld.PendingTickDispatcher.runIfLiveBlockTick(
                        tick, liveCubes::get, loaded::contains,
                        (state, pos) -> {
                            ranBlocks.add(pos);
                            if (pos.equals(aMatchBlock)) {
                                // Mid-pass unloads: unload cube B itself (matching forget() clearing pendingTicks)
                                // and unload one face neighbor of cube C.
                                loaded.remove(keyB);
                                liveCubes.remove(keyB);
                                dispatcher.remove(keyB);
                                loaded.remove(neighborOfC);
                                dispatcher.remove(neighborOfC);
                            }
                        }),
                tick -> ServerCubeWorld.PendingTickDispatcher.runIfLiveFluidTick(
                        tick, liveCubes::get, loaded::contains,
                        (fluid, pos) -> ranFluids.add(pos)),
                key -> ServerCubeWorld.PendingTickDispatcher.markCubeUnsaved(key, liveCubes::get));

        assertEquals(List.of(aMatchBlock), ranBlocks,
                "only matching live block tick with resident cube and 6 neighbors must execute");
        assertEquals(List.of(aMatchFluid), ranFluids,
                "only matching live fluid tick with resident cube and 6 neighbors must execute");
        assertTrue(cubeA.dirty && cubeA.modified && !cubeA.edited, "cubeA must be marked unsaved without edited");
        assertFalse(dispatcher.containsKey(keyA), "cubeA due ticks must be drained after execution");
        assertFalse(dispatcher.containsKey(keyB), "forgotten cubeB must have no pending ticks");

        // Cube C lost a neighbor mid-pass: its due block and fluid ticks MUST remain in pendingTicks (not lost)
        // and cube C must be dropped from readyNeighbors until residency changes.
        assertTrue(dispatcher.containsKey(keyC), "cubeC due ticks must survive a mid-pass neighbor unload");
        assertEquals(1, dispatcher.get(keyC).blocks().size(), "cubeC due block tick must remain queued at delay 0");
        assertEquals(0, dispatcher.get(keyC).blocks().get(0).delay());
        assertEquals(1, dispatcher.get(keyC).fluids().size(), "cubeC due fluid tick must remain queued at delay 0");
        assertEquals(0, dispatcher.get(keyC).fluids().get(0).delay());
        assertFalse(cubeC.dirty, "skipped cubeC must not be marked unsaved before its ticks execute");

        // While neighborOfC remains unloaded with no residency change, cubeC is not rescanned and does not execute.
        assertEquals(0, dispatcher.tick(
                loaded::contains, (x, z) -> true,
                tick -> ServerCubeWorld.PendingTickDispatcher.runIfLiveBlockTick(
                        tick, liveCubes::get, loaded::contains, (state, pos) -> ranBlocks.add(pos)),
                tick -> ServerCubeWorld.PendingTickDispatcher.runIfLiveFluidTick(
                        tick, liveCubes::get, loaded::contains, (fluid, pos) -> ranFluids.add(pos)),
                key -> ServerCubeWorld.PendingTickDispatcher.markCubeUnsaved(key, liveCubes::get)));
        assertEquals(0, dispatcher.lastScannedCount(), "skipped cubeC must leave readyNeighbors until residency changes");

        // Reload cube C's missing neighbor and notify residency change: cube C's preserved due ticks resume and execute!
        loaded.add(neighborOfC);
        dispatcher.noteResidencyChange();
        assertEquals(2, dispatcher.tick(
                loaded::contains, (x, z) -> true,
                tick -> ServerCubeWorld.PendingTickDispatcher.runIfLiveBlockTick(
                        tick, liveCubes::get, loaded::contains, (state, pos) -> ranBlocks.add(pos)),
                tick -> ServerCubeWorld.PendingTickDispatcher.runIfLiveFluidTick(
                        tick, liveCubes::get, loaded::contains, (fluid, pos) -> ranFluids.add(pos)),
                key -> ServerCubeWorld.PendingTickDispatcher.markCubeUnsaved(key, liveCubes::get)));
        assertEquals(List.of(aMatchBlock, cBlockPos), ranBlocks,
                "cubeC due block tick must resume and execute after its neighbor reloads");
        assertEquals(List.of(aMatchFluid, cFluidPos), ranFluids,
                "cubeC due fluid tick must resume and execute after its neighbor reloads");
        assertFalse(dispatcher.containsKey(keyC), "cubeC pending ticks must be drained once resumed and executed");
        assertTrue(cubeC.dirty && cubeC.modified && !cubeC.edited, "cubeC must be marked unsaved without edited");

        // Remove neighborOfC again for the direct helper checks below.
        loaded.remove(neighborOfC);

        // Direct unit verification of the production recheck helpers on unloaded cube, missing neighbor, and state mismatch.
        assertFalse(ServerCubeWorld.PendingTickDispatcher.runIfLiveBlockTick(
                new CubeCodec.PendingTick<>(bBlockPos, Blocks.SAND, 0, TickPriority.NORMAL),
                liveCubes::get, loaded::contains, (s, p) -> fail("unloaded cube must not run")));
        assertFalse(ServerCubeWorld.PendingTickDispatcher.runIfLiveBlockTick(
                new CubeCodec.PendingTick<>(cBlockPos, Blocks.SAND, 0, TickPriority.NORMAL),
                liveCubes::get, loaded::contains, (s, p) -> fail("missing neighbor must not run")));
        assertFalse(ServerCubeWorld.PendingTickDispatcher.runIfLiveFluidTick(
                new CubeCodec.PendingTick<>(cFluidPos, Fluids.WATER, 0, TickPriority.NORMAL),
                liveCubes::get, loaded::contains, (f, p) -> fail("missing neighbor fluid must not run")));
        assertFalse(ServerCubeWorld.PendingTickDispatcher.runIfLiveBlockTick(
                new CubeCodec.PendingTick<>(aMismatchBlock, Blocks.GRAVEL, 0, TickPriority.NORMAL),
                liveCubes::get, loaded::contains, (s, p) -> fail("mismatched block state must not run")));
        assertFalse(ServerCubeWorld.PendingTickDispatcher.runIfLiveFluidTick(
                new CubeCodec.PendingTick<>(aMismatchFluid, Fluids.LAVA, 0, TickPriority.NORMAL),
                liveCubes::get, loaded::contains, (f, p) -> fail("mismatched fluid state must not run")));
    }
    @Test void midPassForgetAndReadoptWithValueEqualReplacementTickDoesNotConsumeReplacement() {
        int ax = 0, ay = 4, az = 0;
        int bx = 10, by = 4, bz = 10;
        long keyA = CubeKey.pack(ax, ay, az);
        long keyB = CubeKey.pack(bx, by, bz);

        Cube cubeA = new Cube(ax, ay, az, emptySection());
        Cube cubeB = new Cube(bx, by, bz, emptySection());
        Long2ObjectOpenHashMap<Cube> liveCubes = new Long2ObjectOpenHashMap<>();
        liveCubes.put(keyA, cubeA);
        liveCubes.put(keyB, cubeB);

        LongOpenHashSet loaded = new LongOpenHashSet();
        addCubeWithSixNeighbors(loaded, ax, ay, az);
        addCubeWithSixNeighbors(loaded, bx, by, bz);

        BlockPos aBlockPos = new BlockPos((ax << 4) + 1, (ay << 4) + 1, (az << 4) + 1);
        BlockPos aFluidPos = new BlockPos((ax << 4) + 2, (ay << 4) + 1, (az << 4) + 1);
        BlockPos bBlockPos = new BlockPos((bx << 4) + 1, (by << 4) + 1, (bz << 4) + 1);
        BlockPos bFluidPos = new BlockPos((bx << 4) + 2, (by << 4) + 1, (bz << 4) + 1);

        CubeCodec.PendingTick<Block> aBlock = new CubeCodec.PendingTick<>(aBlockPos, Blocks.SAND, 0, TickPriority.EXTREMELY_HIGH);
        CubeCodec.PendingTick<Fluid> aFluid = new CubeCodec.PendingTick<>(aFluidPos, Fluids.WATER, 0, TickPriority.EXTREMELY_HIGH);
        CubeCodec.PendingTick<Block> bBlockOriginal = new CubeCodec.PendingTick<>(bBlockPos, Blocks.SAND, 0, TickPriority.LOW);
        CubeCodec.PendingTick<Fluid> bFluidOriginal = new CubeCodec.PendingTick<>(bFluidPos, Fluids.WATER, 0, TickPriority.LOW);

        CubeCodec.PendingTick<Block> bBlockReplacement = new CubeCodec.PendingTick<>(bBlockPos, Blocks.SAND, 0, TickPriority.LOW);
        CubeCodec.PendingTick<Fluid> bFluidReplacement = new CubeCodec.PendingTick<>(bFluidPos, Fluids.WATER, 0, TickPriority.LOW);
        assertEquals(bBlockOriginal, bBlockReplacement);
        assertEquals(bFluidOriginal, bFluidReplacement);

        ServerCubeWorld.PendingTickDispatcher dispatcher = new ServerCubeWorld.PendingTickDispatcher();
        dispatcher.put(keyA, new CubeCodec.PendingTicks(List.of(aBlock), List.of(aFluid)));
        dispatcher.put(keyB, new CubeCodec.PendingTicks(List.of(bBlockOriginal), List.of(bFluidOriginal)));

        List<CubeCodec.PendingTick<Block>> ranBlocks = new ArrayList<>();
        List<CubeCodec.PendingTick<Fluid>> ranFluids = new ArrayList<>();

        // Pass 1: cube A's callback forgets cube B and immediately re-adopts cube B with fresh, value-equal ticks.
        int pass1 = dispatcher.tick(
                loaded::contains,
                (x, z) -> true,
                tick -> {
                    ranBlocks.add(tick);
                    if (tick == aBlock) {
                        dispatcher.remove(keyB);
                        Cube reAdoptedB = new Cube(bx, by, bz, emptySection());
                        liveCubes.put(keyB, reAdoptedB);
                        dispatcher.put(keyB, new CubeCodec.PendingTicks(List.of(bBlockReplacement), List.of(bFluidReplacement)));
                    }
                },
                ranFluids::add,
                key -> ServerCubeWorld.PendingTickDispatcher.markCubeUnsaved(key, liveCubes::get));

        assertEquals(2, pass1, "only cube A's block and fluid ticks may execute during pass 1");
        assertEquals(List.of(aBlock), ranBlocks, "stale pass entry for cube B must not consume replacement block tick");
        assertEquals(List.of(aFluid), ranFluids, "stale pass entry for cube B must not consume replacement fluid tick");
        assertFalse(dispatcher.containsKey(keyA));
        assertTrue(dispatcher.containsKey(keyB), "replacement queue for re-adopted cube B must remain intact");
        assertEquals(List.of(bBlockReplacement), dispatcher.get(keyB).blocks());
        assertEquals(List.of(bFluidReplacement), dispatcher.get(keyB).fluids());
        assertFalse(liveCubes.get(keyB).dirty, "re-adopted cube B must not be marked unsaved before its replacement ticks run");

        // Pass 2: the replacement ticks in the newly adopted queue execute cleanly.
        int pass2 = dispatcher.tick(
                loaded::contains,
                (x, z) -> true,
                ranBlocks::add,
                ranFluids::add,
                key -> ServerCubeWorld.PendingTickDispatcher.markCubeUnsaved(key, liveCubes::get));

        assertEquals(2, pass2, "replacement block and fluid ticks must execute on the next pass");
        assertEquals(List.of(aBlock, bBlockReplacement), ranBlocks);
        assertEquals(List.of(aFluid, bFluidReplacement), ranFluids);
        assertFalse(dispatcher.containsKey(keyB));
        assertTrue(liveCubes.get(keyB).dirty && liveCubes.get(keyB).modified && !liveCubes.get(keyB).edited);
    }

    @Test void drainingFullCube4096DueTicksPreservesPriorityOrderAndDrainsCleanly() {
        int cx = 2, cy = 3, cz = -1;
        long key = CubeKey.pack(cx, cy, cz);
        LongOpenHashSet loaded = new LongOpenHashSet();
        addCubeWithSixNeighbors(loaded, cx, cy, cz);

        TickPriority[] priorities = TickPriority.values();
        List<CubeCodec.PendingTick<Block>> input = new ArrayList<>(4096);
        int idx = 0;
        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    // Cycle priorities in reverse so sorting must reorder across the full cube while preserving tie order.
                    TickPriority priority = priorities[priorities.length - 1 - (idx % priorities.length)];
                    BlockPos pos = new BlockPos((cx << 4) + lx, (cy << 4) + ly, (cz << 4) + lz);
                    input.add(new CubeCodec.PendingTick<>(pos, (idx & 1) == 0 ? Blocks.SAND : Blocks.GRAVEL, 0, priority));
                    idx++;
                }
            }
        }
        assertEquals(4096, input.size());

        List<CubeCodec.PendingTick<Block>> expectedOrder = new ArrayList<>(input);
        expectedOrder.sort(java.util.Comparator.comparingInt(t -> t.priority().getValue()));

        ServerCubeWorld.PendingTickDispatcher dispatcher = new ServerCubeWorld.PendingTickDispatcher();
        dispatcher.put(key, new CubeCodec.PendingTicks(input, List.of()));

        List<CubeCodec.PendingTick<Block>> executed = new ArrayList<>(4096);
        int[] firstMidPassRemaining = {-1};
        int count = dispatcher.tick(
                loaded::contains,
                (x, z) -> true,
                tick -> {
                    if (executed.isEmpty()) {
                        firstMidPassRemaining[0] = dispatcher.get(key).blocks().size();
                    }
                    executed.add(tick);
                },
                f -> fail("no fluid ticks were queued"),
                k -> {});

        assertEquals(4096, count, "all 4,096 due ticks must execute in a single pass");
        assertEquals(4095, firstMidPassRemaining[0], "mid-pass snapshot must immediately exclude the consumed tick");
        assertEquals(expectedOrder, executed, "all 4,096 ticks must execute in stable ascending TickPriority order");
        assertFalse(dispatcher.containsKey(key), "cube queue must be removed once all 4,096 ticks drain");
        assertTrue(dispatcher.isEmpty());
    }
}
