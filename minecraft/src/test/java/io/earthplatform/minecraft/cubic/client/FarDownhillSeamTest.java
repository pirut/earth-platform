package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.far.Far;
import io.earthplatform.minecraft.cubic.gen.FarSurfacePacket;
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

/**
 * Regression tests for downhill vertical seam closure at {@code y_floor} (#264),
 * neighbour {@code side.drops} parity between {@link FarCoverage} and {@link FarMesher},
 * and defensive compact-packet guards in {@link FarColumnMesh}.
 */
class FarDownhillSeamTest {
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

    /** Builds a solid stone column whose surface top at each cell (x, z) is {@code surfaceTop[z * 16 + x]} world blocks. */
    private static void fillColumn(int level, int bottom, int[] surfaceTop, LevelChunkSection[] sections, char[] drops) {
        int v = 1 << level;
        int baseY = bottom * (16 * v);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int si = 0; si < sections.length; si++) sections[si] = new LevelChunkSection(SurfacePacketFixtures.biomes());
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int cell = z * 16 + x;
            int topWorld = surfaceTop[cell];
            int rel = topWorld - baseY;
            int topRow = Math.ceilDiv(rel, v) - 1;
            int dropBlocks = (topRow + 1) * v - rel;
            drops[cell] = (char) (dropBlocks * Far.DROP_UNIT);
            for (int vy = 0; vy <= topRow; vy++) {
                sections[vy >> 4].setBlockState(x, vy & 15, z, stone, false);
            }
        }
        for (LevelChunkSection section : sections) section.recalcBlockCounts();
    }

    private static FarMesher.Job makeJob(int level, int x0, int z0, int bottom, LevelChunkSection[] sections, char[] drops,
            FarMesher.Side[] sides, FarMesher.Side[] diagonals, LongOpenHashSet drawn, FarFaces faces,
            FarSurfacePacket packet, int smoothFrom) {
        return new FarMesher.Job(1, 1, level, 0, x0, z0, bottom, sections, drops, sides, diagonals, drawn, false,
                new FarLooks(), faces, null,
                new float[] {0.5f, 1, 0.8f, 0.8f, 0.6f, 0.6f},
                new float[] {1, 1, 1, 1, 1, 1},
                packet, smoothFrom);
    }

    private static int maxUpWorldY16At(List<UnpackedQuad> quads, int baseY, int cellX, int cellZ) {
        int max = Integer.MIN_VALUE;
        for (UnpackedQuad q : quads) {
            if (q.dir() != Direction.UP) continue;
            int depth = q.extent() + 1;
            if (cellX >= q.x() && cellX < q.x() + q.width() && cellZ >= q.z() && cellZ < q.z() + depth) {
                max = Math.max(max, baseY * 16 + q.y16());
            }
        }
        return max;
    }

    private static int maxEastSideTopWorldY16At(List<UnpackedQuad> quads, int baseY, int edgeX, int cellZ) {
        int max = Integer.MIN_VALUE;
        for (UnpackedQuad q : quads) {
            if (q.dir() != Direction.EAST || q.x() != edgeX) continue;
            if (cellZ >= q.z() && cellZ < q.z() + q.width()) {
                max = Math.max(max, baseY * 16 + q.y16() + q.extent());
            }
        }
        return max;
    }

    @Test void steepDownhillSlopeClosesVerticalGapStrictlyAtYFloor() throws Exception {
        FarFaces faces = syntheticFaces(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());

        // Case 1: Level 1 (v = 2), y_floor = 64 (cy = 4 drawn, cy = 3 unloaded).
        // Test both a 1-block gap (h_sample + 1 = 63) and a 2-block gap (h_sample + 1 = 62).
        for (int straddleTop : new int[] {63, 62}) {
            int level = 1, bottom = 1, baseY = bottom * (16 << level), yFloor = 64;
            int[] surfaceTop = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                surfaceTop[z * 16 + x] = x <= 5 ? 66 + (5 - x) * 2 : x == 6 ? straddleTop : 59 - (x - 7) * 2;
            }
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, surfaceTop, sections, drops);

            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) {
                drawn.add(CubeKey.pack(cx, 4, cz));
                drawn.add(CubeKey.pack(cx, 5, cz));
            }

            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                int yFloor16 = yFloor * 16;
                assertEquals(yFloor16, maxUpWorldY16At(quads, baseY, 6, 8),
                        "L1 straddling downhill voxel (sampled top " + straddleTop + ") must close right up to y_floor=64");
                assertEquals(yFloor16, maxEastSideTopWorldY16At(quads, baseY, 7, 8),
                        "downhill side wall of extended voxel must reach y_floor=64 without leaving a vertical slit");
                assertEquals(59 * 16, maxUpWorldY16At(quads, baseY, 7, 8),
                        "further downhill voxel must stay at its own sampled top 59");
                for (UnpackedQuad q : quads) {
                    if (q.dir() == Direction.UP && q.x() >= 6) {
                        assertTrue(baseY * 16 + q.y16() <= yFloor16, "UP face must never protrude above y_floor");
                    }
                    if (q.dir().getAxis().isHorizontal() && q.x() >= 7) {
                        assertTrue(baseY * 16 + q.y16() + q.extent() <= yFloor16, "side face must never protrude above y_floor");
                    }
                }
            } finally {
                built.free();
            }
        }

        // Case 2: Level 2 (v = 4), y_floor = 64, 3-block undershoot (sampled top = 61, uphill = 66).
        {
            int level = 2, bottom = 0, baseY = 0, yFloor = 64;
            int[] surfaceTop = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                surfaceTop[z * 16 + x] = x <= 4 ? 66 + (4 - x) * 4 : x == 5 ? 61 : 56;
            }
            LevelChunkSection[] sections = new LevelChunkSection[2];
            char[] drops = new char[256];
            fillColumn(level, bottom, surfaceTop, sections, drops);

            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) {
                drawn.add(CubeKey.pack(cx, 4, cz));
                drawn.add(CubeKey.pack(cx, 5, cz));
            }
            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                assertEquals(yFloor * 16, maxUpWorldY16At(quads, baseY, 5, 8),
                        "L2 straddling downhill voxel (top 61) must close gap strictly up to y_floor=64");
                assertEquals(56 * 16, maxUpWorldY16At(quads, baseY, 6, 8));
            } finally {
                built.free();
            }
        }

        // Case 3: Cross-column boundary at x = 15 with uphill neighbour in east side (sides[3], x = 0) at top = 66.
        {
            int level = 1, bottom = 1, baseY = bottom * (16 << level), yFloor = 64;
            int[] ownTops = new int[256];
            int[] eastTops = new int[256];
            Arrays.fill(ownTops, 58);
            for (int z = 0; z < 16; z++) ownTops[z * 16 + 15] = 63;
            Arrays.fill(eastTops, 66);

            LevelChunkSection[] ownSections = new LevelChunkSection[3];
            char[] ownDrops = new char[256];
            fillColumn(level, bottom, ownTops, ownSections, ownDrops);

            LevelChunkSection[] eastSections = new LevelChunkSection[3];
            char[] eastDrops = new char[256];
            fillColumn(level, bottom, eastTops, eastSections, eastDrops);

            FarMesher.Side[] sides = new FarMesher.Side[4];
            sides[3] = new FarMesher.Side(eastSections, bottom, eastDrops);

            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 2; cx++) for (int cz = 0; cz <= 1; cz++) {
                drawn.add(CubeKey.pack(cx, 4, cz));
                drawn.add(CubeKey.pack(cx, 5, cz));
            }
            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, ownSections, ownDrops, sides, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                assertEquals(yFloor * 16, maxUpWorldY16At(quads, baseY, 15, 8),
                        "edge cell straddling y_floor with uphill neighbour across column seam must close up to y_floor=64");
                assertEquals(58 * 16, maxUpWorldY16At(quads, baseY, 14, 8));
            } finally {
                built.free();
            }
        }

        // Case 4: Negative world Y (y_floor = -64, cy = -4 drawn, cy = -5 unloaded).
        {
            int level = 1, bottom = -3, baseY = bottom * (16 << level), yFloor = -64;
            int[] surfaceTop = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                surfaceTop[z * 16 + x] = x <= 5 ? -62 : x == 6 ? -65 : -68;
            }
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, surfaceTop, sections, drops);

            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) {
                drawn.add(CubeKey.pack(cx, -4, cz));
            }
            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                assertEquals(yFloor * 16, maxUpWorldY16At(quads, baseY, 6, 8),
                        "negative-Y downhill slope must close gap strictly up to y_floor=-64");
                assertEquals(-68 * 16, maxUpWorldY16At(quads, baseY, 7, 8));
            } finally {
                built.free();
            }
        }
    }

    @Test void flatTerrainBelowFloatingNearCubeIsNeverPulledUpward() throws Exception {
        FarFaces faces = syntheticFaces(Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState());
        int level = 1, bottom = 1, baseY = bottom * (16 << level);

        // Case 1: Completely flat terrain at y = 62 (just 2 blocks below y_floor = 64) under aerial/floating near cubes cy = 4.
        {
            int[] flatTops = new int[256];
            Arrays.fill(flatTops, 62);
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, flatTops, sections, drops);

            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) {
                drawn.add(CubeKey.pack(cx, 4, cz));
            }
            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    assertEquals(62 * 16, maxUpWorldY16At(quads, baseY, x, z),
                            "flat terrain under floating near cube must never be pulled upward at (" + x + "," + z + ")");
                }
            } finally {
                built.free();
            }
        }

        // Case 2: Flat valley floor at y = 50 next to a sheer 20-block cliff at y = 70 (y_floor = 64).
        // Must not pull the valley floor up 14 blocks into a floating shelf.
        {
            int[] cliffTops = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                cliffTops[z * 16 + x] = x <= 5 ? 70 : 50;
            }
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, cliffTops, sections, drops);

            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) {
                drawn.add(CubeKey.pack(cx, 4, cz));
            }
            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                assertEquals(50 * 16, maxUpWorldY16At(quads, baseY, 6, 8),
                        "valley floor far below y_floor beside a sheer cliff must not be pulled up into a shelf");
            } finally {
                built.free();
            }
        }

        // Case 3: Ground submerged under water directly below y_floor must not be extended up through water.
        {
            int[] slopeTops = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                slopeTops[z * 16 + x] = x <= 5 ? 66 : 62;
            }
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, slopeTops, sections, drops);
            // Place water right above ground at x = 6 (topRow = (62 - 32)/2 - 1 = 14, so row 15 spans [62, 64]).
            for (int z = 0; z < 16; z++) {
                sections[0].setBlockState(6, 15, z, Blocks.WATER.defaultBlockState(), false);
            }
            sections[0].recalcBlockCounts();

            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) {
                drawn.add(CubeKey.pack(cx, 4, cz));
            }
            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                assertEquals(62 * 16, maxUpWorldY16At(quads, baseY, 6, 8),
                        "underwater seabed must not be pulled upward through water");
            } finally {
                built.free();
            }
        }
    }

    @Test void coverageAndMesherAgreeOnNeighbourSideDropsAcrossColumnBoundary() throws Exception {
        int level = 1; // v = 2
        int bottom = 1; // baseY = 32
        int rows = 32;
        char[] ownDrops = new char[256];
        // Own column has drop = 0 on all cells: row 16 spans world [64, 65] (entirely inside cy = 4).
        Arrays.fill(ownDrops, (char) 0);

        // Each cardinal neighbour has drop = 256 (1 block lower) on its facing edge:
        // row 16 in the neighbour spans world [63, 64] (straddles unloaded cy = 3 and drawn cy = 4!).
        FarMesher.Side[] sides = new FarMesher.Side[4];
        for (int s = 0; s < 4; s++) {
            char[] sideDrops = new char[256];
            Arrays.fill(sideDrops, (char) (Far.DROP_UNIT));
            LevelChunkSection[] sideSections = {new LevelChunkSection(SurfacePacketFixtures.biomes()), new LevelChunkSection(SurfacePacketFixtures.biomes())};
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                for (int vy = 0; vy <= 16; vy++) sideSections[vy >> 4].setBlockState(x, vy & 15, z, Blocks.STONE.defaultBlockState(), false);
            }
            for (LevelChunkSection sec : sideSections) sec.recalcBlockCounts();
            sides[s] = new FarMesher.Side(sideSections, bottom, sideDrops);
        }

        LevelChunkSection[] ownSections = {new LevelChunkSection(SurfacePacketFixtures.biomes()), new LevelChunkSection(SurfacePacketFixtures.biomes())};
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            for (int vy = 0; vy <= 16; vy++) ownSections[vy >> 4].setBlockState(x, vy & 15, z, Blocks.STONE.defaultBlockState(), false);
        }
        for (LevelChunkSection sec : ownSections) sec.recalcBlockCounts();

        // Drawn cubes cover cy = 4 across this column and its 1-voxel halo (cx = -1..2, cz = -1..2), NOT cy = 3!
        LongOpenHashSet drawn = new LongOpenHashSet();
        for (int cx = -1; cx <= 2; cx++) for (int cz = -1; cz <= 2; cz++) drawn.add(CubeKey.pack(cx, 4, cz));

        FarFaces faces = syntheticFaces(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        FarMesher.Job job = makeJob(level, 0, 0, bottom, ownSections, ownDrops, sides, null, drawn, faces, null, Integer.MAX_VALUE);
        FarCoverage coverage = new FarCoverage(level, 0, 0, bottom, rows, ownDrops, sides, drawn);

        // Exhaustive parity check across the entire column + 1-voxel halo and all rows.
        for (int vy = -1; vy <= rows; vy++) {
            for (int z = -1; z <= 16; z++) {
                for (int x = -1; x <= 16; x++) {
                    int fx = x, fy = vy, fz = z;
                    assertEquals(FarMesher.drawn(job, x, vy, z), coverage.drawn(x, vy, z),
                            () -> "FarCoverage and FarMesher.drawn disagree at (" + fx + "," + fy + "," + fz + ")");
                }
            }
        }
        // Inside column at row 16 (drop = 0 -> [64, 65], inside cy = 4): drawn must be TRUE.
        assertTrue(coverage.drawn(0, 16, 8));
        assertTrue(FarMesher.drawn(job, 0, 16, 8));

        // Across all 4 cardinal boundaries at row 16 (side.drop = 256 -> [63, 64], touches unloaded cy = 3):
        // Old clamped logic used ownDrops=0 and wrongly returned TRUE; both must now return FALSE!
        int[][] edgeCoords = {{8, -1}, {8, 16}, {-1, 8}, {16, 8}};
        for (int[] c : edgeCoords) {
            assertFalse(coverage.drawn(c[0], 16, c[1]),
                    "FarCoverage must inspect side.drops at (" + c[0] + ",16," + c[1] + ")");
            assertFalse(FarMesher.drawn(job, c[0], 16, c[1]),
                    "FarMesher.drawn must inspect side.drops at (" + c[0] + ",16," + c[1] + ")");
        }
    }

    @Test void farColumnMeshDirectCompactPacketGuardsDrawnAndSmooth() throws Exception {
        var biome = SurfacePacketFixtures.biomes().getHolderOrThrow(Biomes.PLAINS);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        var run = List.of(new FarSurfacePacket.Run(8, stone), new FarSurfacePacket.Run(16, air));
        short[] packetDrops = new short[256];
        char[] drops = new char[256];
        FarSurfacePacket packet = new FarSurfacePacket(5, 0, 16, packetDrops, biome, Collections.nCopies(16, biome), Collections.nCopies(256, run));
        FarFaces faces = syntheticFaces(stone, air);

        // 1. Direct FarColumnMesh call with compact packet (sections == null) + non-empty drawn near cubes:
        //    must mask out drawn voxels AND emit interior seam faces matching the dense oracle bit-for-bit.
        LongOpenHashSet drawn = new LongOpenHashSet();
        // Level 5 -> v = 32 (2x2 near cubes per voxel). Cover voxel (5, 7, 5) -> bx = 160..191 (cx = 10..11),
        // bz = 160..191 (cz = 10..11), by = 224..255 (cy = 14..15).
        for (int cy = 14; cy <= 15; cy++) for (int cz = 10; cz <= 11; cz++) for (int cx = 10; cx <= 11; cx++) {
            drawn.add(CubeKey.pack(cx, cy, cz));
        }
        FarMesher.Job maskedPacketJob = makeJob(5, 0, 0, 0, null, drops, null, null, drawn, faces, packet, Integer.MAX_VALUE);
        FarMesher.Built directMasked = new FarColumnMesh(maskedPacketJob).build();
        FarMesher.Built oracleMasked = FarMesher.buildDenseOracle(maskedPacketJob);
        try {
            assertArrayEquals(oracleMasked.quads(), directMasked.quads(),
                    "direct FarColumnMesh on compact packet with drawn cubes must match dense oracle");
            assertArrayEquals(oracleMasked.counts(), directMasked.counts());
            // Verify voxel (5, 7, 5) was actually carved out (leaving an interior UP seam face at vy = 6 -> y = 7*32 = 224).
            List<UnpackedQuad> quads = unpack(directMasked);
            boolean hasCarvedFloor = quads.stream().anyMatch(q ->
                    q.dir() == Direction.UP && q.x() == 5 && q.z() == 5 && q.y16() == 7 * 32 * 16);
            assertTrue(hasCarvedFloor, "near cube must mask compact packet voxel (5,7,5) and expose interior seam face below it");
        } finally {
            directMasked.free();
            oracleMasked.free();
        }

        // 2. Direct FarColumnMesh call with compact packet (sections == null) + compact packet sides/diagonals + smooth mode:
        //    must not throw NullPointerException and must match the dense oracle bit-for-bit.
        FarMesher.Side[] packetSides = new FarMesher.Side[4];
        FarMesher.Side[] packetDiagonals = new FarMesher.Side[4];
        for (int i = 0; i < 4; i++) {
            packetSides[i] = new FarMesher.Side(null, 0, drops, packet);
            packetDiagonals[i] = new FarMesher.Side(null, 0, drops, packet);
        }
        FarMesher.Job smoothPacketJob = makeJob(5, 0, 0, 0, null, drops, packetSides, packetDiagonals, null, faces, packet, Far.EDIT_LEVELS + 1);
        assertTrue(FarMesher.smooth(smoothPacketJob));
        FarMesher.Built directSmooth = new FarColumnMesh(smoothPacketJob).build();
        FarMesher.Built oracleSmooth = FarMesher.buildDenseOracle(smoothPacketJob);
        try {
            assertNotNull(directSmooth.grid(), "smooth compact packet must emit smooth ground grid");
            assertArrayEquals(oracleSmooth.grid(), directSmooth.grid());
            assertArrayEquals(oracleSmooth.quads(), directSmooth.quads());
            assertArrayEquals(oracleSmooth.counts(), directSmooth.counts());
        } finally {
            directSmooth.free();
            oracleSmooth.free();
        }
    }
}
