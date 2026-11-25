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

    private record UnpackedQuad(int group, int x, int z, Direction dir, int width, int y16, int extent, int light, int normal) {}

    private static List<UnpackedQuad> unpack(FarMesher.Built built) {
        List<UnpackedQuad> list = new ArrayList<>();
        int[] quads = built.quads(), counts = built.counts();
        int at = 0;
        for (int g : FarMesher.STORED) {
            for (int n = 0; n < counts[g]; n++, at += 4) {
                int q0 = quads[at], q1 = quads[at + 1], q2 = quads[at + 2], q3 = quads[at + 3];
                list.add(new UnpackedQuad(
                        g,
                        q0 & 31,
                        (q0 >>> 5) & 31,
                        Direction.from3DDataValue((q0 >>> 10) & 7),
                        ((q0 >>> 13) & 15) + 1,
                        q1,
                        q2 & 0xFFFFF,
                        (q0 >>> 17) & 255,
                        q3 >>> 20));
            }
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

        // Case 2: Level 2 (v = 4), y_floor = 64, 3-block undershoot (sampled top = 61, uphill = 67 -> midpoint 64).
        {
            int level = 2, bottom = 0, baseY = 0, yFloor = 64;
            int[] surfaceTop = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                surfaceTop[z * 16 + x] = x <= 4 ? 67 + (4 - x) * 4 : x == 5 ? 61 : 56;
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

    @Test void coarseL5PartialCoverageNeverExtendsIntoDrawnNearCube() throws Exception {
        FarFaces faces = syntheticFaces(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        int level = 5, bottom = 0, baseY = 0, yFloor = 64; // v = 32 -> 2x2 near cubes per voxel
        int[] surfaceTop = new int[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            surfaceTop[z * 16 + x] = x <= 4 ? 68 : x == 5 ? 62 : 56;
        }
        LevelChunkSection[] sections = new LevelChunkSection[2];
        char[] drops = new char[256];
        fillColumn(level, bottom, surfaceTop, sections, drops);

        // Cell (5, 8) spans bx = 160..191 (cx = 10..11), bz = 256..287 (cz = 16..17).
        // Uphill cell (4, 8) spans cx = 8..9, cz = 16..17.
        // All 4 of 4 cubes in cy = 4 are drawn, AND 1 of 4 cubes in cy = 3 is drawn inside cell (5, 8).
        LongOpenHashSet drawn = new LongOpenHashSet();
        for (int cx = 8; cx <= 11; cx++) for (int cz = 16; cz <= 17; cz++) {
            drawn.add(CubeKey.pack(cx, 4, cz));
        }
        long partialCube = CubeKey.pack(10, 3, 16);
        drawn.add(partialCube);

        FarMesher.Built partialBuilt = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
        try {
            List<UnpackedQuad> quads = unpack(partialBuilt);
            assertEquals(62 * 16, maxUpWorldY16At(quads, baseY, 5, 8),
                    "L5 cell with 1 of 4 near cubes drawn in topCy must NOT extend into the drawn near cube");
        } finally {
            partialBuilt.free();
        }

        // Removing that 1 near cube leaves 0 of 4 drawn in topCy and 4 of 4 drawn in topCy + 1 -> must extend to y_floor.
        drawn.remove(partialCube);
        FarMesher.Built clearBuilt = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
        try {
            List<UnpackedQuad> quads = unpack(clearBuilt);
            assertEquals(yFloor * 16, maxUpWorldY16At(quads, baseY, 5, 8),
                    "L5 cell with 0 of 4 drawn in topCy and 4 of 4 drawn in topCy + 1 must close up to y_floor=64");
        } finally {
            clearBuilt.free();
        }
    }

    @Test void sheerCliffMidpointAndCoarseGapLimitsAreEnforced() throws Exception {
        FarFaces faces = syntheticFaces(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());

        // 1. L1 sheer cliff within gap limit: valley top = 61, cliff = 82, y_floor = 64 -> must NOT extend.
        {
            int level = 1, bottom = 1, baseY = bottom * (16 << level);
            int[] tops = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) tops[z * 16 + x] = x <= 5 ? 82 : 61;
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, tops, sections, drops);
            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) drawn.add(CubeKey.pack(cx, 4, cz));
            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                assertEquals(61 * 16, maxUpWorldY16At(unpack(built), baseY, 6, 8),
                        "L1 valley edge at 61 beside sheer cliff at 82 must not become a 3-block shelf");
            } finally {
                built.free();
            }
        }

        // 2. L1 midpoint both branches: (top=61, nb=65) stays at 61; (top=61, nb=67) extends to 64.
        for (int nb : new int[] {65, 67}) {
            int level = 1, bottom = 1, baseY = bottom * (16 << level);
            int[] tops = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) tops[z * 16 + x] = x <= 5 ? nb : x == 6 ? 61 : 57;
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, tops, sections, drops);
            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) drawn.add(CubeKey.pack(cx, 4, cz));
            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                int expected = nb == 67 ? 64 * 16 : 61 * 16;
                assertEquals(expected, maxUpWorldY16At(unpack(built), baseY, 6, 8),
                        "L1 midpoint branch for top=61, nb=" + nb);
            } finally {
                built.free();
            }
        }

        // 3. L1 per-neighbour check: sheer cliff on north (82) must not mask a valid slope on west (67).
        {
            int level = 1, bottom = 1, baseY = bottom * (16 << level);
            int[] tops = new int[256];
            Arrays.fill(tops, 57);
            for (int z = 0; z < 16; z++) for (int x = 0; x <= 5; x++) tops[z * 16 + x] = 67;
            tops[7 * 16 + 6] = 82; // sheer cliff north of (6, 8)
            tops[8 * 16 + 6] = 61;
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, tops, sections, drops);
            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) drawn.add(CubeKey.pack(cx, 4, cz));
            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                assertEquals(64 * 16, maxUpWorldY16At(unpack(built), baseY, 6, 8),
                        "valid west slope (67) must still qualify when north neighbour is a sheer cliff (82)");
            } finally {
                built.free();
            }
        }

        // 4. L3, L4, L5 cliff (valley 50, cliff 70) and flat (62) non-trigger cases + L3 6-block gap & L4 sheer cliff.
        for (int level : new int[] {3, 4, 5}) {
            int v = 1 << level, cubesPerCol = Math.max(1, (16 * v) >> 4);
            int bottom = 0, baseY = 0;
            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx < cubesPerCol; cx++) for (int cz = 0; cz < cubesPerCol; cz++) {
                drawn.add(CubeKey.pack(cx, 4, cz));
            }
            // Valley 50 beside cliff 70 (14-block gap) -> must stay at 50 at every coarse LOD.
            {
                int[] tops = new int[256];
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) tops[z * 16 + x] = x <= 5 ? 70 : 50;
                LevelChunkSection[] sections = new LevelChunkSection[2];
                char[] drops = new char[256];
                fillColumn(level, bottom, tops, sections, drops);
                FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
                try {
                    assertEquals(50 * 16, maxUpWorldY16At(unpack(built), baseY, 6, 8),
                            "L" + level + " valley at 50 beside cliff at 70 must never form a shelf");
                } finally {
                    built.free();
                }
            }
            // Flat 62 under drawn cy = 4 -> must stay at 62 at every coarse LOD.
            {
                int[] tops = new int[256];
                Arrays.fill(tops, 62);
                LevelChunkSection[] sections = new LevelChunkSection[2];
                char[] drops = new char[256];
                fillColumn(level, bottom, tops, sections, drops);
                FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
                try {
                    assertEquals(62 * 16, maxUpWorldY16At(unpack(built), baseY, 6, 8),
                            "L" + level + " flat terrain at 62 must stay at 62");
                } finally {
                    built.free();
                }
            }
            // Sheer cliff (top = 61, nb = 82) -> must stay at 61 at L3, L4, L5.
            {
                int[] tops = new int[256];
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) tops[z * 16 + x] = x <= 5 ? 82 : 61;
                LevelChunkSection[] sections = new LevelChunkSection[2];
                char[] drops = new char[256];
                fillColumn(level, bottom, tops, sections, drops);
                FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
                try {
                    assertEquals(61 * 16, maxUpWorldY16At(unpack(built), baseY, 6, 8),
                            "L" + level + " sheer cliff (61 -> 82) must not extend");
                } finally {
                    built.free();
                }
            }
        }

        // L3 specific: 6-block gap (58 -> 70) must be blocked by the 4-block maxGap cap, while 2-block gap (62 -> 70) passes.
        {
            int level = 3, bottom = 0, baseY = 0;
            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx < 8; cx++) for (int cz = 0; cz < 8; cz++) drawn.add(CubeKey.pack(cx, 4, cz));
            for (int cellTop : new int[] {58, 62}) {
                int[] tops = new int[256];
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) tops[z * 16 + x] = x <= 5 ? 70 : x == 6 ? cellTop : 54;
                LevelChunkSection[] sections = new LevelChunkSection[2];
                char[] drops = new char[256];
                fillColumn(level, bottom, tops, sections, drops);
                FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
                try {
                    int expected = cellTop == 62 ? 64 * 16 : 58 * 16;
                    assertEquals(expected, maxUpWorldY16At(unpack(built), baseY, 6, 8),
                            "L3 cellTop=" + cellTop + " beside uphill 70");
                } finally {
                    built.free();
                }
            }
        }
    }

    @Test void extensionNeverExceedsColumnVerticalCeiling() throws Exception {
        FarFaces faces = syntheticFaces(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        // 1-section L4 column (v = 16, bottom = 0, height = 16 rows -> column ceiling = Y = 256).
        int level = 4, bottom = 0, baseY = 0;
        int ceilingY16 = 16 * (1 << level) * 16; // 256 * 16

        // Case 1: Edge cell at exact ceiling Y = 256 beside taller 2-section east column reaching Y = 274 with cy = 17 drawn.
        {
            int[] ownTops = new int[256];
            Arrays.fill(ownTops, 240);
            for (int z = 0; z < 16; z++) ownTops[z * 16 + 15] = 256;
            LevelChunkSection[] ownSections = new LevelChunkSection[1];
            char[] ownDrops = new char[256];
            fillColumn(level, bottom, ownTops, ownSections, ownDrops);

            int[] eastTops = new int[256];
            Arrays.fill(eastTops, 274);
            LevelChunkSection[] eastSections = new LevelChunkSection[2];
            char[] eastDrops = new char[256];
            fillColumn(level, bottom, eastTops, eastSections, eastDrops);

            FarMesher.Side[] sides = new FarMesher.Side[4];
            sides[3] = new FarMesher.Side(eastSections, bottom, eastDrops);

            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 14; cx <= 17; cx++) for (int cz = 0; cz < 16; cz++) {
                drawn.add(CubeKey.pack(cx, 17, cz)); // y_floor = 272 > column ceiling 256
            }
            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, ownSections, ownDrops, sides, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                assertEquals(256 * 16, maxUpWorldY16At(quads, baseY, 15, 8));
                for (UnpackedQuad q : quads) {
                    int topY16 = q.dir().getAxis() == Direction.Axis.Y ? baseY * 16 + q.y16() : baseY * 16 + q.y16() + q.extent();
                    assertTrue(topY16 <= ceilingY16, "emitted quad must never exceed column ceiling 256, got " + topY16);
                }
            } finally {
                built.free();
            }
        }

        // Case 2: Edge cell at Y = 254 extending up to y_floor = 256 (exact column ceiling) stays within bounds.
        {
            int[] ownTops = new int[256];
            Arrays.fill(ownTops, 240);
            for (int z = 0; z < 16; z++) ownTops[z * 16 + 15] = 254;
            LevelChunkSection[] ownSections = new LevelChunkSection[1];
            char[] ownDrops = new char[256];
            fillColumn(level, bottom, ownTops, ownSections, ownDrops);

            int[] eastTops = new int[256];
            Arrays.fill(eastTops, 260);
            LevelChunkSection[] eastSections = new LevelChunkSection[2];
            char[] eastDrops = new char[256];
            fillColumn(level, bottom, eastTops, eastSections, eastDrops);

            FarMesher.Side[] sides = new FarMesher.Side[4];
            sides[3] = new FarMesher.Side(eastSections, bottom, eastDrops);

            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 14; cx <= 17; cx++) for (int cz = 0; cz < 16; cz++) {
                drawn.add(CubeKey.pack(cx, 16, cz)); // y_floor = 256 == column ceiling
            }
            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, ownSections, ownDrops, sides, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                assertEquals(256 * 16, maxUpWorldY16At(quads, baseY, 15, 8));
                for (UnpackedQuad q : quads) {
                    int topY16 = q.dir().getAxis() == Direction.Axis.Y ? baseY * 16 + q.y16() : baseY * 16 + q.y16() + q.extent();
                    assertTrue(topY16 <= ceilingY16, "emitted quad must stay <= column ceiling 256, got " + topY16);
                }
            } finally {
                built.free();
            }
        }
    }

    @Test void nonAirAtVyPlusTwoAndExposedIceCapDoNotExtend() throws Exception {
        FarFaces faces = syntheticFaces(
                Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(), Blocks.ICE.defaultBlockState(),
                Blocks.PACKED_ICE.defaultBlockState(), Blocks.BLUE_ICE.defaultBlockState(),
                Blocks.FROSTED_ICE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        int level = 1, bottom = 1, baseY = bottom * (16 << level); // v = 2, baseY = 32, y_floor = 64

        LongOpenHashSet drawn = new LongOpenHashSet();
        for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) drawn.add(CubeKey.pack(cx, 4, cz));

        // 1. gap = 3 > voxel (top = 61, vy = 14, drop = 256 -> vy+1 = 15 spans [61,63), vy+2 = 16 spans [63,65)).
        //    With AIR at vy+1 and WATER or OAK_LEAVES at vy+2, extension must be blocked.
        for (BlockState blocker : new BlockState[] {Blocks.WATER.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState()}) {
            int[] tops = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) tops[z * 16 + x] = x <= 5 ? 67 : x == 6 ? 61 : 57;
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, tops, sections, drops);
            // Confirm vy + 1 (row 15) is AIR and place blocker at vy + 2 (row 16 = section 1, y = 0).
            assertTrue(sections[0].getBlockState(6, 15, 8).isAir());
            sections[1].setBlockState(6, 0, 8, blocker, false);
            sections[1].recalcBlockCounts();

            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                // Find highest UP quad of the stone ground (exclude any leaves UP quad at y = 65).
                int groundTopY16 = quads.stream()
                        .filter(q -> q.dir() == Direction.UP && 6 >= q.x() && 6 < q.x() + q.width() && 8 >= q.z() && 8 < q.z() + q.extent() + 1)
                        .mapToInt(q -> baseY * 16 + q.y16())
                        .filter(y -> y <= 64 * 16)
                        .max().orElse(Integer.MIN_VALUE);
                assertEquals(61 * 16, groundTopY16,
                        "non-air " + blocker.getBlock() + " at vy + 2 must block downhill extension into vy + 2");
            } finally {
                built.free();
            }
        }

        // 2. Exposed ice cap over water beside a higher bank must never extend upward.
        for (BlockState ice : new BlockState[] {
                Blocks.ICE.defaultBlockState(), Blocks.PACKED_ICE.defaultBlockState(),
                Blocks.BLUE_ICE.defaultBlockState(), Blocks.FROSTED_ICE.defaultBlockState()}) {
            int[] tops = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) tops[z * 16 + x] = x <= 5 ? 66 : 62;
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, tops, sections, drops);
            // At x = 6 (top = 62 -> topRow = 14), put water at row 13 and exposed ice cap at row 14.
            for (int z = 0; z < 16; z++) {
                sections[0].setBlockState(6, 13, z, Blocks.WATER.defaultBlockState(), false);
                sections[0].setBlockState(6, 14, z, ice, false);
            }
            sections[0].recalcBlockCounts();

            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                assertEquals(62 * 16, maxUpWorldY16At(unpack(built), baseY, 6, 8),
                        "exposed " + ice.getBlock() + " cap over water must not rise toward y_floor");
            } finally {
                built.free();
            }
        }
    }

    @Test void adjacentExtendedCellsEmitZeroBuriedCoincidentSideFaces() throws Exception {
        FarFaces faces = syntheticFaces(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        int level = 1, bottom = 1, baseY = bottom * (16 << level); // v = 2, baseY = 32, y_floor = 64

        // 1. Interior adjacent cells with identical original tops (both 62 -> 64).
        {
            int[] tops = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) tops[z * 16 + x] = x <= 5 ? 66 : x == 6 ? 62 : 58;
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, tops, sections, drops);
            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) drawn.add(CubeKey.pack(cx, 4, cz));

            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                assertEquals(64 * 16, maxUpWorldY16At(quads, baseY, 6, 8));
                assertEquals(64 * 16, maxUpWorldY16At(quads, baseY, 6, 9));
                // Shared plane between (6, 8) and (6, 9) is z = 9 (SOUTH of (6,8) and NORTH of (6,9)).
                long buriedOnSharedPlane = quads.stream()
                        .filter(q -> (q.dir() == Direction.NORTH || q.dir() == Direction.SOUTH)
                                && q.z() == 9 && 6 >= q.x() && 6 < q.x() + q.width()
                                && baseY * 16 + q.y16() + q.extent() > 62 * 16
                                && baseY * 16 + q.y16() < 64 * 16)
                        .count();
                assertEquals(0L, buriedOnSharedPlane,
                        "adjacent extended interior cells (62->64) must emit ZERO buried faces on their shared plane");
                // Downhill exposed face at x = 7 must still reach y_floor = 64.
                assertEquals(64 * 16, maxEastSideTopWorldY16At(quads, baseY, 7, 8));
            } finally {
                built.free();
            }
        }

        // 2. Interior adjacent cells with differing original tops (61 -> 64 and 63 -> 64).
        {
            int[] tops = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) tops[z * 16 + x] = x <= 5 ? 67 : 58;
            tops[8 * 16 + 6] = 61;
            tops[9 * 16 + 6] = 63;
            LevelChunkSection[] sections = new LevelChunkSection[3];
            char[] drops = new char[256];
            fillColumn(level, bottom, tops, sections, drops);
            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) drawn.add(CubeKey.pack(cx, 4, cz));

            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, sections, drops, null, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                assertEquals(64 * 16, maxUpWorldY16At(quads, baseY, 6, 8));
                assertEquals(64 * 16, maxUpWorldY16At(quads, baseY, 6, 9));
                long buriedOnSharedPlane = quads.stream()
                        .filter(q -> (q.dir() == Direction.NORTH || q.dir() == Direction.SOUTH)
                                && q.z() == 9 && 6 >= q.x() && 6 < q.x() + q.width()
                                && baseY * 16 + q.y16() + q.extent() > 61 * 16
                                && baseY * 16 + q.y16() < 64 * 16)
                        .count();
                assertEquals(0L, buriedOnSharedPlane,
                        "adjacent extended cells with differing original tops (61->64, 63->64) must emit ZERO faces on their shared plane");
            } finally {
                built.free();
            }
        }

        // 3. Cross-column adjacent extended cells: (15, 8) in own column and (0, 8) in east neighbour both extend 62 -> 64.
        {
            int[] ownTops = new int[256];
            int[] eastTops = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int h = z <= 7 ? 66 : z == 8 ? 62 : 58;
                ownTops[z * 16 + x] = h;
                eastTops[z * 16 + x] = h;
            }
            LevelChunkSection[] ownSections = new LevelChunkSection[3];
            char[] ownDrops = new char[256];
            fillColumn(level, bottom, ownTops, ownSections, ownDrops);
            LevelChunkSection[] eastSections = new LevelChunkSection[3];
            char[] eastDrops = new char[256];
            fillColumn(level, bottom, eastTops, eastSections, eastDrops);

            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int cx = 0; cx <= 2; cx++) for (int cz = 0; cz <= 1; cz++) drawn.add(CubeKey.pack(cx, 4, cz));

            FarMesher.Side[] sides = new FarMesher.Side[4];
            sides[3] = new FarMesher.Side(eastSections, bottom, eastDrops, null, drawn);

            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, ownSections, ownDrops, sides, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                List<UnpackedQuad> quads = unpack(built);
                assertEquals(64 * 16, maxUpWorldY16At(quads, baseY, 15, 8));
                int eastOwnGroup = Direction.EAST.get3DDataValue();
                int eastWallGroup = FarMesher.FIRST_WALL + 3;
                long alwaysDrawnOnSharedPlane = quads.stream()
                        .filter(q -> q.group() == eastOwnGroup && q.dir() == Direction.EAST
                                && q.x() == 16 && 8 >= q.z() && 8 < q.z() + q.width()
                                && baseY * 16 + q.y16() + q.extent() > 62 * 16
                                && baseY * 16 + q.y16() < 64 * 16)
                        .count();
                assertEquals(0L, alwaysDrawnOnSharedPlane,
                        "cross-column adjacent extended cells must emit ZERO always-drawn EAST faces on their shared plane over [62, 64)");
                boolean hasWallForLevelTransition = quads.stream()
                        .anyMatch(q -> q.group() == eastWallGroup && q.dir() == Direction.EAST
                                && q.x() == 16 && 8 >= q.z() && 8 < q.z() + q.width()
                                && baseY * 16 + q.y16() <= 62 * 16
                                && baseY * 16 + q.y16() + q.extent() >= 64 * 16);
                assertTrue(hasWallForLevelTransition,
                        "cross-column extended cell must route hidden [62, 64) face to east wall group for level transitions");
            } finally {
                built.free();
            }
        }
    }

    @Test void farColumnMeshDirectCompactPacketWithWaterEditsMatchesDenseOracle() throws Exception {
        var biome = SurfacePacketFixtures.biomes().getHolderOrThrow(Biomes.PLAINS);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        var wetRun = List.of(new FarSurfacePacket.Run(6, stone), new FarSurfacePacket.Run(9, water), new FarSurfacePacket.Run(16, air));
        var dryRun = List.of(new FarSurfacePacket.Run(6, stone), new FarSurfacePacket.Run(16, air));
        List<List<FarSurfacePacket.Run>> columns = new ArrayList<>(256);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) columns.add(x < 8 ? wetRun : dryRun);
        short[] packetDrops = new short[256];
        char[] drops = new char[256];
        FarSurfacePacket basePacket = new FarSurfacePacket(5, 0, 16, packetDrops, biome, Collections.nCopies(16, biome), columns);
        // Add water above dry stone at (10, 8) and remove water above wet stone at (3, 8).
        FarSurfacePacket editedPacket = basePacket.withEdits(0, 1, List.of(
                new FarSurfacePacket.Edit(10, 6, 8, water),
                new FarSurfacePacket.Edit(10, 7, 8, water),
                new FarSurfacePacket.Edit(3, 6, 8, air),
                new FarSurfacePacket.Edit(3, 7, 8, air),
                new FarSurfacePacket.Edit(3, 8, 8, air)));
        assertEquals(5, editedPacket.editCount());

        FarFaces faces = syntheticFaces(stone, water, air);
        FarMesher.Job editJob = makeJob(5, 0, 0, 0, null, drops, null, null, null, faces, editedPacket, Integer.MAX_VALUE);
        FarMesher.Built direct = new FarColumnMesh(editJob).build();
        FarMesher.Built oracle = FarMesher.buildDenseOracle(editJob);
        try {
            assertArrayEquals(oracle.quads(), direct.quads(),
                    "direct FarColumnMesh with water add/remove edits must match dense oracle geometry and sky light bit-for-bit");
            assertArrayEquals(oracle.counts(), direct.counts());
            List<UnpackedQuad> quads = unpack(direct);
            int lightUnderAddedWater = quads.stream()
                    .filter(q -> q.dir() == Direction.UP && 10 >= q.x() && 10 < q.x() + q.width() && 8 >= q.z() && 8 < q.z() + q.extent() + 1)
                    .mapToInt(UnpackedQuad::light).findFirst().orElseThrow();
            int lightWhereWaterRemoved = quads.stream()
                    .filter(q -> q.dir() == Direction.UP && 3 >= q.x() && 3 < q.x() + q.width() && 8 >= q.z() && 8 < q.z() + q.extent() + 1)
                    .mapToInt(UnpackedQuad::light).findFirst().orElseThrow();
            assertEquals(0, lightUnderAddedWater, "stone beneath 2 voxels (64 blocks) of added water must have sky light 0");
            assertEquals(15, lightWhereWaterRemoved, "stone exposed by removed water edits must regain full sky light 15");
        } finally {
            direct.free();
            oracle.free();
        }
    }

    @Test void mismatchedNeighbourDropsEmitColumnEdgeSeamFaceInPackedMesh() throws Exception {
        FarFaces faces = syntheticFaces(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        int level = 1, bottom = 1, baseY = bottom * (16 << level); // v = 2, baseY = 32
        LongOpenHashSet drawn = new LongOpenHashSet();
        // Only cy = 4 is drawn (across own column and east halo); cy = 3 is unloaded.
        for (int cx = 0; cx <= 2; cx++) for (int cz = 0; cz <= 1; cz++) drawn.add(CubeKey.pack(cx, 4, cz));

        // Case A: ownDrops = 256 (row 16 spans [63, 65), straddles unloaded cy = 3 -> NOT drawn),
        // eastSideDrops = 0 (row 16 spans [64, 66), entirely in drawn cy = 4 -> DRAWN).
        // Old clamped logic thought east row 16 was NOT drawn and culled [64, 65) into wall group;
        // fixed logic must emit an always-drawn Direction.EAST face covering [64, 65).
        {
            char[] ownDrops = new char[256];
            Arrays.fill(ownDrops, (char) Far.DROP_UNIT);
            char[] eastDrops = new char[256];
            Arrays.fill(eastDrops, (char) 0);
            LevelChunkSection[] ownSections = {new LevelChunkSection(SurfacePacketFixtures.biomes()), new LevelChunkSection(SurfacePacketFixtures.biomes())};
            LevelChunkSection[] eastSections = {new LevelChunkSection(SurfacePacketFixtures.biomes()), new LevelChunkSection(SurfacePacketFixtures.biomes())};
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int vy = 0; vy <= 16; vy++) {
                ownSections[vy >> 4].setBlockState(x, vy & 15, z, Blocks.STONE.defaultBlockState(), false);
                eastSections[vy >> 4].setBlockState(x, vy & 15, z, Blocks.STONE.defaultBlockState(), false);
            }
            for (LevelChunkSection s : ownSections) s.recalcBlockCounts();
            for (LevelChunkSection s : eastSections) s.recalcBlockCounts();
            FarMesher.Side[] sides = new FarMesher.Side[4];
            sides[3] = new FarMesher.Side(eastSections, bottom, eastDrops);

            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, ownSections, ownDrops, sides, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                int eastOwnGroup = Direction.EAST.get3DDataValue();
                boolean hasSeamFace = unpack(built).stream().anyMatch(q ->
                        q.group() == eastOwnGroup && q.dir() == Direction.EAST
                                && q.x() == 16 && 8 >= q.z() && 8 < q.z() + q.width()
                                && baseY * 16 + q.y16() == 64 * 16
                                && baseY * 16 + q.y16() + q.extent() == 65 * 16);
                assertTrue(hasSeamFace, "mismatched neighbour drop must emit always-drawn EAST seam face [64, 65) in packed mesh");
            } finally {
                built.free();
            }
        }

        // Case B: ownDrops = 0 (row 15 spans [62, 64)), eastSideDrops = 256 (row 16 spans [63, 65), NOT drawn).
        // Fixed logic must route [63, 64) to east wall group and emit NO always-drawn EAST face there.
        {
            char[] ownDrops = new char[256];
            Arrays.fill(ownDrops, (char) 0);
            char[] eastDrops = new char[256];
            Arrays.fill(eastDrops, (char) Far.DROP_UNIT);
            LevelChunkSection[] ownSections = {new LevelChunkSection(SurfacePacketFixtures.biomes()), new LevelChunkSection(SurfacePacketFixtures.biomes())};
            LevelChunkSection[] eastSections = {new LevelChunkSection(SurfacePacketFixtures.biomes()), new LevelChunkSection(SurfacePacketFixtures.biomes())};
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                for (int vy = 0; vy <= 15; vy++) ownSections[vy >> 4].setBlockState(x, vy & 15, z, Blocks.STONE.defaultBlockState(), false);
                for (int vy = 0; vy <= 16; vy++) eastSections[vy >> 4].setBlockState(x, vy & 15, z, Blocks.STONE.defaultBlockState(), false);
            }
            for (LevelChunkSection s : ownSections) s.recalcBlockCounts();
            for (LevelChunkSection s : eastSections) s.recalcBlockCounts();
            FarMesher.Side[] sides = new FarMesher.Side[4];
            sides[3] = new FarMesher.Side(eastSections, bottom, eastDrops);

            FarMesher.Built built = FarMesher.build(makeJob(level, 0, 0, bottom, ownSections, ownDrops, sides, null, drawn, faces, null, Integer.MAX_VALUE));
            try {
                int eastOwnGroup = Direction.EAST.get3DDataValue();
                boolean hasSpuriousFace = unpack(built).stream().anyMatch(q ->
                        q.group() == eastOwnGroup && q.dir() == Direction.EAST
                                && q.x() == 16 && 8 >= q.z() && 8 < q.z() + q.width()
                                && baseY * 16 + q.y16() + q.extent() > 63 * 16
                                && baseY * 16 + q.y16() < 64 * 16);
                assertFalse(hasSpuriousFace, "east neighbour straddling unloaded cy = 3 must not cause spurious always-drawn EAST face");
            } finally {
                built.free();
            }
        }
    }

    @Test void twoColumnL4RendererStyleCoverageSnapshotsEmitNoBuriedFacesOnSharedPlane() throws Exception {
        FarFaces faces = syntheticFaces(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        // Two adjacent L4 columns (v = 16, width = 256): W covers x=[0, 255] (cx=0..15), E covers x=[256, 511] (cx=16..31).
        // Each border cell (W:(15, z) and E:(0, z)) sits at Y = 62 and qualifies for extension to y_floor = 64
        // solely via an uphill cell 1 voxel deeper inside its OWN column (W:(14, z) at cx = 14, E:(1, z) at cx = 17),
        // which lies OUTSIDE the other column's 1-voxel renderer-style drawnCubes snapshot.
        int level = 4, bottom = 0, baseY = 0;
        int[] westTops = new int[256];
        int[] eastTops = new int[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            westTops[z * 16 + x] = x == 15 ? 62 : 66;
            eastTops[z * 16 + x] = x == 0 ? 62 : 66;
        }
        LevelChunkSection[] westSections = new LevelChunkSection[1];
        char[] westDrops = new char[256];
        fillColumn(level, bottom, westTops, westSections, westDrops);
        LevelChunkSection[] eastSections = new LevelChunkSection[1];
        char[] eastDrops = new char[256];
        fillColumn(level, bottom, eastTops, eastSections, eastDrops);

        // Global compiled cubes at cy = 4 (y_floor = 64): cx = 14..17, cz = 0..15 (cy = 3 unloaded).
        // Partition into exact FarRenderer.cubesByColumn 1-voxel-margin snapshots:
        // W's 1-voxel margin is cx = -1..16 (so cx = 17 is excluded); E's is cx = 15..32 (so cx = 14 is excluded).
        LongOpenHashSet drawnW = new LongOpenHashSet();
        LongOpenHashSet drawnE = new LongOpenHashSet();
        int v = 1 << level;
        for (int cx = 14; cx <= 17; cx++) for (int cz = 0; cz < 16; cz++) {
            long cube = CubeKey.pack(cx, 4, cz);
            int bx = cx << 4, bz = cz << 4;
            int minColX = Far.column(bx - v, level), maxColX = Far.column(bx + 15 + v, level);
            int minColZ = Far.column(bz - v, level), maxColZ = Far.column(bz + 15 + v, level);
            if (0 >= minColX && 0 <= maxColX && 0 >= minColZ && 0 <= maxColZ) drawnW.add(cube);
            if (1 >= minColX && 1 <= maxColX && 0 >= minColZ && 0 <= maxColZ) drawnE.add(cube);
        }
        assertFalse(drawnW.contains(CubeKey.pack(17, 4, 8)), "W's renderer snapshot must exclude cx = 17");
        assertFalse(drawnE.contains(CubeKey.pack(14, 4, 8)), "E's renderer snapshot must exclude cx = 14");

        FarMesher.Side[] westSides = new FarMesher.Side[4];
        westSides[3] = new FarMesher.Side(eastSections, bottom, eastDrops, null, drawnE);
        FarMesher.Side[] eastSides = new FarMesher.Side[4];
        eastSides[2] = new FarMesher.Side(westSections, bottom, westDrops, null, drawnW);

        FarMesher.Built builtW = FarMesher.build(makeJob(level, 0, 0, bottom, westSections, westDrops, westSides, null, drawnW, faces, null, Integer.MAX_VALUE));
        FarMesher.Built builtE = FarMesher.build(makeJob(level, 256, 0, bottom, eastSections, eastDrops, eastSides, null, drawnE, faces, null, Integer.MAX_VALUE));
        try {
            List<UnpackedQuad> quadsW = unpack(builtW);
            List<UnpackedQuad> quadsE = unpack(builtE);
            assertEquals(64 * 16, maxUpWorldY16At(quadsW, baseY, 15, 8), "W border cell (15, 8) must extend 62 -> 64");
            assertEquals(64 * 16, maxUpWorldY16At(quadsE, baseY, 0, 8), "E border cell (0, 8) must extend 62 -> 64");

            int eastOwnGroup = Direction.EAST.get3DDataValue();
            int westOwnGroup = Direction.WEST.get3DDataValue();
            long buriedW = quadsW.stream()
                    .filter(q -> q.group() == eastOwnGroup && q.dir() == Direction.EAST
                            && q.x() == 16 && 8 >= q.z() && 8 < q.z() + q.width()
                            && baseY * 16 + q.y16() + q.extent() > 62 * 16
                            && baseY * 16 + q.y16() < 64 * 16)
                    .count();
            long buriedE = quadsE.stream()
                    .filter(q -> q.group() == westOwnGroup && q.dir() == Direction.WEST
                            && q.x() == 0 && 8 >= q.z() && 8 < q.z() + q.width()
                            && baseY * 16 + q.y16() + q.extent() > 62 * 16
                            && baseY * 16 + q.y16() < 64 * 16)
                    .count();
            assertEquals(0L, buriedW, "W must emit ZERO always-drawn EAST faces on shared boundary over [62, 64)");
            assertEquals(0L, buriedE, "E must emit ZERO always-drawn WEST faces on shared boundary over [62, 64)");

            boolean hasWallW = quadsW.stream().anyMatch(q -> q.group() == FarMesher.FIRST_WALL + 3 && q.dir() == Direction.EAST
                    && q.x() == 16 && 8 >= q.z() && 8 < q.z() + q.width()
                    && baseY * 16 + q.y16() <= 62 * 16 && baseY * 16 + q.y16() + q.extent() >= 64 * 16);
            boolean hasWallE = quadsE.stream().anyMatch(q -> q.group() == FarMesher.FIRST_WALL + 2 && q.dir() == Direction.WEST
                    && q.x() == 0 && 8 >= q.z() && 8 < q.z() + q.width()
                    && baseY * 16 + q.y16() <= 62 * 16 && baseY * 16 + q.y16() + q.extent() >= 64 * 16);
            assertTrue(hasWallW, "W must keep [62, 64) in east wall group for level transitions");
            assertTrue(hasWallE, "E must keep [62, 64) in west wall group for level transitions");
        } finally {
            builtW.free();
            builtE.free();
        }
    }

    @Test void blockyEdgeBesideSmoothL5NeighbourKeepsExposedRiserVisible() throws Exception {
        FarFaces faces = syntheticFaces(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        // L5 (v = 32, smoothFrom = 5):
        // West column W has 2 sections (rows 0..31 -> [0, 1024)) with edge at Y = 512.
        // East column E has 1 section (rows 0..15 -> [0, 512)) with edge at Y = 511.
        // Drawn cubes at cy = 32 (y = [512, 528)) overlap W's [0, 1024) so W meshes BLOCKY,
        // while y = 512 is at/above E's top 512 so E meshes SMOOTH and never runs downhill seam extension.
        int level = 5, bottom = 0, baseY = 0, smoothFrom = 5;
        int[] westTops = new int[256];
        int[] eastTops = new int[256];
        Arrays.fill(westTops, 512);
        Arrays.fill(eastTops, 511);

        LevelChunkSection[] westSections = new LevelChunkSection[2];
        char[] westDrops = new char[256];
        fillColumn(level, bottom, westTops, westSections, westDrops);
        LevelChunkSection[] eastSections = new LevelChunkSection[1];
        char[] eastDrops = new char[256];
        fillColumn(level, bottom, eastTops, eastSections, eastDrops);

        LongOpenHashSet drawn = new LongOpenHashSet();
        // Covers W's east edge (x = 15 -> cx = 30..31) and E's west edge (x = 0 -> cx = 32..33) at cy = 32.
        for (int cx = 30; cx <= 33; cx++) for (int cz = 0; cz < 32; cz++) drawn.add(CubeKey.pack(cx, 32, cz));

        FarMesher.Side[] westSides = new FarMesher.Side[4];
        westSides[3] = new FarMesher.Side(eastSections, bottom, eastDrops, null, drawn);
        FarMesher.Side[] eastSides = new FarMesher.Side[4];
        eastSides[2] = new FarMesher.Side(westSections, bottom, westDrops, null, drawn);

        FarMesher.Job jobW = makeJob(level, 0, 0, bottom, westSections, westDrops, westSides, null, drawn, faces, null, smoothFrom);
        FarMesher.Job jobE = makeJob(level, 512, 0, bottom, eastSections, eastDrops, eastSides, null, drawn, faces, null, smoothFrom);
        assertFalse(FarMesher.smooth(jobW), "W must mesh blocky because cy = 32 overlaps [0, 1024)");
        assertTrue(FarMesher.smooth(jobE), "E must mesh smooth because cy = 32 starts at E's top 512");

        FarMesher.Built builtW = FarMesher.build(jobW);
        FarMesher.Built builtE = FarMesher.build(jobE);
        try {
            assertNull(builtW.grid(), "W must produce blocky geometry");
            assertNotNull(builtE.grid(), "E must produce smooth geometry");
            List<UnpackedQuad> quadsW = unpack(builtW);
            int eastOwnGroup = Direction.EAST.get3DDataValue();
            int eastWallGroup = FarMesher.FIRST_WALL + 3;
            boolean hasVisibleRiser = quadsW.stream().anyMatch(q ->
                    q.group() == eastOwnGroup && q.dir() == Direction.EAST
                            && q.x() == 16 && 8 >= q.z() && 8 < q.z() + q.width()
                            && baseY * 16 + q.y16() == 511 * 16
                            && baseY * 16 + q.y16() + q.extent() == 512 * 16);
            boolean hiddenInWall = quadsW.stream().anyMatch(q ->
                    q.group() == eastWallGroup && q.dir() == Direction.EAST
                            && q.x() == 16 && 8 >= q.z() && 8 < q.z() + q.width()
                            && baseY * 16 + q.y16() + q.extent() > 511 * 16
                            && baseY * 16 + q.y16() < 512 * 16);
            assertTrue(hasVisibleRiser, "blocky W edge at Y=512 beside smooth E edge at Y=511 must keep [511, 512) riser in visible EAST group");
            assertFalse(hiddenInWall, "exposed [511, 512) riser beside smooth neighbour must NOT be hidden in east wall group");
        } finally {
            builtW.free();
            builtE.free();
        }
    }
}
