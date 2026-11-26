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
        Long2ObjectOpenHashMap<CubeCodec.PendingTicks> pending = new Long2ObjectOpenHashMap<>();
        pending.put(center, new CubeCodec.PendingTicks(List.of(bTick), List.of(fTick)));

        LongOpenHashSet loaded = new LongOpenHashSet();
        loaded.add(center);
        for (long n : neighbors) loaded.add(n);

        ServerCubeWorld.PendingTickDispatcher dispatcher = new ServerCubeWorld.PendingTickDispatcher();
        dispatcher.onAdded(center);

        List<CubeCodec.PendingTick<Block>> firedBlocks = new ArrayList<>();
        List<CubeCodec.PendingTick<Fluid>> firedFluids = new ArrayList<>();
        List<Long> changedKeys = new ArrayList<>();

        // 1. All 6 neighbors loaded, but carrier chunk is NOT ticking -> held without decrementing delay.
        assertEquals(0, dispatcher.tick(pending, loaded::contains, (x, z) -> false, firedBlocks::add, firedFluids::add, changedKeys::add));
        assertEquals(1, pending.get(center).blocks().get(0).delay());
        assertTrue(firedBlocks.isEmpty());
        assertTrue(firedFluids.isEmpty());

        // 2. Carrier chunk ticking, but each single missing face neighbor holds dispatch.
        for (long missing : neighbors) {
            loaded.remove(missing);
            dispatcher.onUnloaded(missing);
            assertEquals(0, dispatcher.tick(pending, loaded::contains, (x, z) -> true, firedBlocks::add, firedFluids::add, changedKeys::add),
                    "must wait when neighbor " + CubeKey.toString(missing) + " is unloaded");
            assertEquals(1, pending.get(center).blocks().get(0).delay());
            loaded.add(missing);
            dispatcher.noteResidencyChange();
        }

        // 3. First gated tick: delay-0 fluid executes immediately; delay-1 block decrements to 0 and stays cube-owned.
        assertEquals(1, dispatcher.tick(pending, loaded::contains, (x, z) -> true, firedBlocks::add, firedFluids::add, key -> {
            changedKeys.add(key);
            cube.markUnsaved();
        }));
        assertEquals(List.of(fTick), firedFluids);
        assertTrue(firedBlocks.isEmpty(), "delay-1 tick must stay cube-owned until its delay reaches 0");
        assertTrue(pending.containsKey(center));
        assertEquals(0, pending.get(center).blocks().get(0).delay());
        assertTrue(cube.dirty && cube.modified && !cube.edited, "markUnsaved must keep UNTOUCHED intact");

        // 4. Second gated tick: block tick now due -> fires once and clears pending map.
        assertEquals(1, dispatcher.tick(pending, loaded::contains, (x, z) -> true, firedBlocks::add, firedFluids::add, changedKeys::add));
        assertFalse(pending.containsKey(center));
        assertEquals(1, firedBlocks.size());
        assertEquals(bTick.pos(), firedBlocks.get(0).pos());
        assertEquals(bTick.type(), firedBlocks.get(0).type());
        assertEquals(0, firedBlocks.get(0).delay());

        // 5. Subsequent ticks never replay.
        assertEquals(0, dispatcher.tick(pending, loaded::contains, (x, z) -> true, firedBlocks::add, firedFluids::add, changedKeys::add));
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
        BlockPos pos = new BlockPos((cx << 4) + 5, (cy << 4) + 6, (cz << 4) + 7);
        CubeCodec.PendingTick<Block> delayed = new CubeCodec.PendingTick<>(pos, Blocks.SAND, 3, TickPriority.HIGH);
        Long2ObjectOpenHashMap<CubeCodec.PendingTicks> pending = new Long2ObjectOpenHashMap<>();
        pending.put(key, new CubeCodec.PendingTicks(List.of(delayed), List.of()));

        LongOpenHashSet loaded = new LongOpenHashSet();
        loaded.add(key);
        for (long n : allNeighbors) loaded.add(n);

        ServerCubeWorld.PendingTickDispatcher dispatcher = new ServerCubeWorld.PendingTickDispatcher();
        dispatcher.onAdded(key);
        List<CubeCodec.PendingTick<Block>> fired = new ArrayList<>();

        // Tick 1 while resident + neighbor-gated: delay 3 -> 2, does not fire, cube marked unsaved.
        assertEquals(0, dispatcher.tick(pending, loaded::contains, (x, z) -> true, fired::add, f -> {}, k -> cube.markUnsaved()));
        assertTrue(fired.isEmpty());
        assertTrue(cube.dirty && cube.modified && !cube.edited);
        assertEquals(2, pending.get(key).blocks().get(0).delay());

        // Source cube unloads while carrier chunk continues ticking -> save via real CubeCodec.write() + writePendingTicks().
        CompoundTag savedOnUnload = encodeLiveCube(cube, pending.get(key));
        assertTrue(savedOnUnload.getBoolean(CubeCodec.UNTOUCHED));
        loaded.remove(key);
        pending.remove(key);
        dispatcher.onUnloaded(key);

        // Carrier chunk keeps ticking for several ticks while source cube is unloaded -> never executes!
        for (int i = 0; i < 5; i++) {
            assertEquals(0, dispatcher.tick(pending, loaded::contains, (x, z) -> true, fired::add, f -> {}, k -> {}));
        }
        assertTrue(fired.isEmpty(), "unloaded cube must never execute pending ticks");

        // Reload cube from NBT -> remaining delay 2 must have survived unload/reload.
        CubeCodec.Decoded reloaded = codec.read(savedOnUnload, key);
        assertEquals(1, reloaded.pendingTicks().blocks().size());
        assertEquals(2, reloaded.pendingTicks().blocks().get(0).delay(), "remaining delay 2 must persist through unload/reload");

        // Re-adopt cube, but with one face neighbor absent -> countdown stays paused at delay 2.
        loaded.add(key);
        loaded.remove(missingNeighbor);
        pending.put(key, reloaded.pendingTicks());
        dispatcher.onAdded(key);
        assertEquals(0, dispatcher.tick(pending, loaded::contains, (x, z) -> true, fired::add, f -> {}, k -> {}));
        assertEquals(2, pending.get(key).blocks().get(0).delay(), "countdown must pause while a face neighbor is missing");

        // Neighbor loads -> countdown resumes: 2 -> 1 -> 0 -> executes!
        loaded.add(missingNeighbor);
        dispatcher.noteResidencyChange();
        assertEquals(0, dispatcher.tick(pending, loaded::contains, (x, z) -> true, fired::add, f -> {}, k -> {}));
        assertEquals(1, pending.get(key).blocks().get(0).delay());
        assertEquals(0, dispatcher.tick(pending, loaded::contains, (x, z) -> true, fired::add, f -> {}, k -> {}));
        assertEquals(0, pending.get(key).blocks().get(0).delay());
        assertTrue(fired.isEmpty());

        assertEquals(1, dispatcher.tick(pending, loaded::contains, (x, z) -> true, fired::add, f -> {}, k -> {}));
        assertEquals(1, fired.size());
        assertEquals(pos, fired.get(0).pos());
        assertEquals(Blocks.SAND, fired.get(0).type());
        assertEquals(TickPriority.HIGH, fired.get(0).priority());
        assertFalse(pending.containsKey(key));

        // Re-saving via real CubeCodec.write() + writePendingTicks() after drain clears tick tags so reload never replays.
        CompoundTag savedAfterDrain = encodeLiveCube(cube, pending.getOrDefault(key, CubeCodec.PendingTicks.EMPTY));
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

        Long2ObjectOpenHashMap<CubeCodec.PendingTicks> pending = new Long2ObjectOpenHashMap<>();
        pending.put(key, new CubeCodec.PendingTicks(List.of(low, highFirst, extHigh, highSecond), List.of()));

        ServerCubeWorld.PendingTickDispatcher dispatcher = new ServerCubeWorld.PendingTickDispatcher();
        dispatcher.onAdded(key);
        List<CubeCodec.PendingTick<Block>> order = new ArrayList<>();

        // Initial scan finds the edge cube is missing a neighbor.
        assertEquals(0, dispatcher.tick(pending, loaded::contains, (x, z) -> true, order::add, f -> {}, k -> {}));
        assertEquals(1, dispatcher.lastScannedCount());

        // Subsequent ticks with unchanged residency must NOT re-scan unready edge cubes.
        assertEquals(0, dispatcher.tick(pending, loaded::contains, (x, z) -> true, order::add, f -> {}, k -> {}));
        assertEquals(0, dispatcher.lastScannedCount(), "unready edge cube must not be rescanned without residency change");

        // Once the missing neighbor loads, residency change triggers scan and executes in stable ascending priority order.
        loaded.add(missing);
        dispatcher.noteResidencyChange();
        assertEquals(4, dispatcher.tick(pending, loaded::contains, (x, z) -> true, order::add, f -> {}, k -> {}));
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
}
