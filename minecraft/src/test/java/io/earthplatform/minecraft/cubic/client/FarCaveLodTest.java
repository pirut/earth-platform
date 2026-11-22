package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.far.Far;
import io.earthplatform.minecraft.cubic.gen.FarColumn;
import io.earthplatform.minecraft.cubic.gen.FarSurfacePacket;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfacePacketFixtures;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Regression tests for cave openings in the far LOD system (issue #210). */
class FarCaveLodTest {
    private static Map<TagKey<Fluid>, List<Holder<Fluid>>> previousFluidTags;
    private static Map<TagKey<Block>, List<Holder<Block>>> previousBlockTags;

    @BeforeAll static void bindTags() {
        SurfacePacketFixtures.biomes();
        var fluids = BuiltInRegistries.FLUID;
        previousFluidTags = fluids.getTags().collect(Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var fluidTags = new HashMap<>(previousFluidTags);
        fluidTags.put(FluidTags.WATER, List.of(fluids.wrapAsHolder(Fluids.WATER), fluids.wrapAsHolder(Fluids.FLOWING_WATER)));
        fluids.bindTags(fluidTags);

        var blocks = BuiltInRegistries.BLOCK;
        previousBlockTags = blocks.getTags().collect(Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var blockTags = new HashMap<>(previousBlockTags);
        blockTags.put(BlockTags.LEAVES, blocks.stream().filter(b -> b instanceof LeavesBlock).map(blocks::wrapAsHolder).toList());
        blockTags.put(BlockTags.LOGS, List.of(blocks.wrapAsHolder(Blocks.OAK_LOG)));
        blocks.bindTags(blockTags);
    }

    @AfterAll static void restoreTags() {
        BuiltInRegistries.FLUID.bindTags(previousFluidTags);
        BuiltInRegistries.BLOCK.bindTags(previousBlockTags);
    }

    @SuppressWarnings("unchecked")
    private static FarFaces syntheticFaces(BlockState... states) throws Exception {
        FarFaces result = new FarFaces();
        var field = FarFaces.class.getDeclaredField("faces");
        field.setAccessible(true);
        Map<BlockState, FarFaces.Face[]> cached = (Map<BlockState, FarFaces.Face[]>) field.get(result);
        for (BlockState state : states) {
            cached.computeIfAbsent(state, s -> {
                FarFaces.Face[] six = new FarFaces.Face[6];
                Arrays.fill(six, new FarFaces.Face(0, 0, 1, 1, 0xff000000 | Block.getId(s) * 7411 & 0xffffff, -1));
                return six;
            });
        }
        return result;
    }

    private static FarMesher.Job job(int level, int bottom, LevelChunkSection[] sections, char[] drops,
            LongOpenHashSet drawn, FarFaces faces, FarSurfacePacket packet) {
        FarMesher.Side[] sides = new FarMesher.Side[4];
        for (int i = 0; i < 4; i++) sides[i] = new FarMesher.Side(sections, bottom, drops, packet);
        return new FarMesher.Job(1, 1, level, 0, 0, 0, bottom, sections, drops, sides, sides,
                drawn, false, new FarLooks(), faces, null,
                new float[] {0.5f, 1, 0.8f, 0.8f, 0.6f, 0.6f},
                new float[] {1, 1, 1, 1, 1, 1},
                packet, Integer.MAX_VALUE);
    }

    private record UnpackedQuad(int x, int z, Direction dir, int width, int y16, int extent, int light, int normal) {}

    private static List<UnpackedQuad> unpack(FarMesher.Built built) {
        List<UnpackedQuad> list = new ArrayList<>();
        int[] quads = built.quads();
        for (int i = 0; i < quads.length; i += 4) {
            int q0 = quads[i], q1 = quads[i + 1], q2 = quads[i + 2], q3 = quads[i + 3];
            list.add(new UnpackedQuad(
                    q0 & 31,
                    (q0 >>> 5) & 31,
                    Direction.from3DDataValue((q0 >>> 10) & 7),
                    ((q0 >>> 13) & 15) + 1,
                    q1,
                    q2 & 0xFFFFF,
                    (q0 >>> 17) & 255,
                    q3 >>> 20));
        }
        return list;
    }

    @Test void airOpeningAtBottomRowEmitsDarkGroundFloorCapInDenseAndRunPaths() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState caveAir = Blocks.CAVE_AIR.defaultBlockState();
        var biome = SurfacePacketFixtures.biomes().getHolderOrThrow(Biomes.PLAINS);

        // Flat solid column with a cave carved down to vy = 0 at (5, 0, 5) — both dense and packet forms.
        List<List<FarSurfacePacket.Run>> runs = new ArrayList<>(256);
        for (int cell = 0; cell < 256; cell++) {
            if (cell == 5 * 16 + 5) {
                runs.add(List.of(
                        new FarSurfacePacket.Run(2, caveAir),
                        new FarSurfacePacket.Run(4, stone),
                        new FarSurfacePacket.Run(16, air)));
            } else {
                runs.add(List.of(
                        new FarSurfacePacket.Run(4, stone),
                        new FarSurfacePacket.Run(16, air)));
            }
        }
        short[] shortDrops = new short[256];
        char[] drops = new char[256];
        shortDrops[5 * 16 + 5] = 256; // 1.0 block drop
        drops[5 * 16 + 5] = 256;

        FarSurfacePacket packet = new FarSurfacePacket(3, 0, 16, shortDrops, biome, Collections.nCopies(16, biome), runs);
        LevelChunkSection[] sections = packet.materializeForMeshing().toArray(LevelChunkSection[]::new);
        FarFaces faces = syntheticFaces(stone, air, caveAir);

        FarMesher.Job denseJob = job(3, 0, sections, drops, null, faces, null);
        FarMesher.Job packetJob = job(3, 0, new LevelChunkSection[0], drops, null, faces, packet);
        FarMesher.Built dense = FarMesher.buildDenseOracle(denseJob);
        FarMesher.Built direct = FarMesher.build(packetJob);
        try {
            // Dense and run-packet paths must remain bit-for-bit identical.
            assertArrayEquals(dense.quads(), direct.quads());
            assertArrayEquals(dense.counts(), direct.counts());

            // Exactly one UP floor cap at (5, 5), height = -1.0 block (-16/16), dark underground light = 0, flat normal = 0.
            List<UnpackedQuad> caps = unpack(dense).stream()
                    .filter(q -> q.dir() == Direction.UP && q.y16() == -16)
                    .toList();
            assertEquals(1, caps.size(), "expected a single bottom floor cap under the cave opening at vy = 0");
            UnpackedQuad cap = caps.get(0);
            assertEquals(5, cap.x());
            assertEquals(5, cap.z());
            assertEquals(1, cap.width());
            assertEquals(0, cap.extent());
            assertEquals(0, cap.light(), "underground floor cap must use dark cave light");
            assertEquals(0, cap.normal(), "flat floor cap must keep its flat up normal");
        } finally {
            dense.free();
            direct.free();
        }
    }

    @Test void drawnNearCubeAtBottomRowEmitsFloorCapOnlyWhenCubeBelowIsNotDrawn() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        LevelChunkSection section = new LevelChunkSection(SurfacePacketFixtures.biomes());
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = 0; y < 8; y++) {
            section.setBlockState(x, y, z, stone, false);
        }
        section.recalcBlockCounts();
        LevelChunkSection[] sections = {section};
        char[] drops = new char[256];
        FarFaces faces = syntheticFaces(stone, Blocks.AIR.defaultBlockState());

        // Level 1, bottom = 2 -> base world Y = 64 (cube Y = 4). Near cube (0, 4, 0) covers x=0..7, z=0..7, vy=0..7.
        LongOpenHashSet onlyBottomCube = new LongOpenHashSet();
        onlyBottomCube.add(CubeKey.pack(0, 4, 0));
        FarMesher.Built capped = FarMesher.build(job(1, 2, sections, drops, onlyBottomCube, faces, null));
        try {
            List<UnpackedQuad> caps = unpack(capped).stream()
                    .filter(q -> q.dir() == Direction.UP && q.y16() == 0 && q.x() == 0 && q.z() == 0)
                    .toList();
            assertEquals(1, caps.size(), "near-drawn cube at vy = 0 with undrawn vy = -1 must emit a floor cap");
            assertEquals(8, caps.get(0).width());
            assertEquals(7, caps.get(0).extent(), "8x8 voxels merge into one quad (extent = depth - 1)");
            assertEquals(0, caps.get(0).light());
        } finally {
            capped.free();
        }

        // Once cube (0, 3, 0) below vy = 0 is also drawn, vy = -1 is covered by near geometry so no cap is emitted.
        LongOpenHashSet withCubeBelow = new LongOpenHashSet(onlyBottomCube);
        withCubeBelow.add(CubeKey.pack(0, 3, 0));
        FarMesher.Built uncapped = FarMesher.build(job(1, 2, sections, drops, withCubeBelow, faces, null));
        try {
            boolean anyFloorCap = unpack(uncapped).stream()
                    .anyMatch(q -> q.dir() == Direction.UP && q.y16() == 0);
            assertFalse(anyFloorCap, "when vy = -1 is also drawn up close, no bottom cap should be emitted");
        } finally {
            uncapped.free();
        }
    }

    @Test void caveSeamBelowTallTreeStillEmitsClosingFacesAgainstDrawnNearCubes() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();

        // Three Level 1 sections (48 voxels = 96 blocks): ground up to vy = 20, tall tree canopy up to vy = 38.
        // Previously tops[cell] = 38 and SEAM_DEPTH = 16 suppressed every seam face below vy = 22 — even above ground!
        LevelChunkSection[] sections = new LevelChunkSection[3];
        for (int si = 0; si < 3; si++) sections[si] = new LevelChunkSection(SurfacePacketFixtures.biomes());
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            for (int vy = 0; vy <= 20; vy++) sections[vy >> 4].setBlockState(x, vy & 15, z, stone, false);
            sections[21 >> 4].setBlockState(x, 21 & 15, z, log, false);
            for (int vy = 22; vy <= 38; vy++) sections[vy >> 4].setBlockState(x, vy & 15, z, leaves, false);
        }
        for (LevelChunkSection section : sections) section.recalcBlockCounts();
        char[] drops = new char[256];
        FarFaces faces = syntheticFaces(stone, log, leaves, Blocks.AIR.defaultBlockState());

        // Mark near cubes covering x = 0..7, z = 0..7 across vy = 0..23 (cubes cy = 0, 1, 2 at bottom = 0, level = 1).
        // Adjacent undrawn column at x = 8, z = 0..7 must emit WEST seam faces all the way down to vy = 0 (y = 0).
        LongOpenHashSet drawn = new LongOpenHashSet();
        for (int cy = 0; cy <= 2; cy++) drawn.add(CubeKey.pack(0, cy, 0));

        FarMesher.Built built = FarMesher.build(job(1, 0, sections, drops, drawn, faces, null));
        try {
            List<UnpackedQuad> westSeam = unpack(built).stream()
                    .filter(q -> q.dir() == Direction.WEST && q.x() == 8 && q.z() < 8)
                    .toList();
            assertFalse(westSeam.isEmpty(), "expected WEST seam faces along x = 8 facing the drawn cave cubes");
            int lowestY16 = westSeam.stream().mapToInt(UnpackedQuad::y16).min().orElse(Integer.MAX_VALUE);
            assertEquals(0, lowestY16, "seam wall under tall trees must reach all the way down to vy = 0");
        } finally {
            built.free();
        }
    }

    @Test void fineFarColumnsKeepAtLeast32BlocksOfSolidRockBelowLowestSurface() {
        // Surface at y = 66 is the worst alignment for Level 1 (width 32): previously (66 - 2) >> 5 = 2 gave base = 64,
        // leaving only 2 blocks of rock below ground.
        int surfaceY = 66;
        SurfaceGenerator flat = new SurfaceGenerator(42, SurfacePacketFixtures.biomes(), RandomSource.create(42)) {
            @Override protected void ground(int x, int z, int[] out) { out[0] = surfaceY; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int h, int w, int slope) {
                return new Surface(holder(SurfacePacketFixtures.biomes(), Biomes.PLAINS), GRASS, DIRT, 0, 0, 0, 0, 0, false);
            }
            @Override public String id() { return "cave_depth_test"; }
            @Override public int seaLevel() { return 0; }
            @Override protected int shoreLevel() { return 0; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };

        for (int level = Far.MIN_LEVEL; level <= Far.EDIT_LEVELS; level++) {
            FarColumn column = new FarColumn();
            flat.farColumn(level, 0, 0, column);
            int baseY = column.bottom * Far.width(level);
            assertTrue(surfaceY - baseY >= 32,
                    "level " + level + " baseY=" + baseY + " is too close to surfaceY=" + surfaceY);
            assertFalse(column.sections.isEmpty());
            assertTrue(column.sections.size() <= Far.MAX_SECTIONS);
            // Bottom row of the generated column must be solid rock.
            LevelChunkSection lowest = column.sections.get(0);
            assertFalse(lowest == null || lowest.getBlockState(0, 0, 0).isAir(),
                    "level " + level + " vy = 0 should be solid rock beneath the surface");
        }
    }
}
