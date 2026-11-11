package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.far.Far;
import io.earthplatform.minecraft.cubic.gen.SurfacePacketFixtures;
import io.earthplatform.minecraft.cubic.gen.FarSurfacePacket;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

class FarSmoothGroundTest {
    @Test void adjacentSlopesMeetAtExactlyTheSameHeightsColoursAndNormals() throws Exception {
        FarFaces faces = faces();
        FarSmoothGround left = new FarSmoothGround(new FarColumnMesh(job(0, 5, faces, null)));
        FarSmoothGround right = new FarSmoothGround(new FarColumnMesh(job(1, 5, faces, null)));
        for (int z = 0; z < FarMesher.POINTS; z++) {
            int a = z * FarMesher.POINTS + FarMesher.POINTS - 1, b = z * FarMesher.POINTS;
            assertEquals(left.pointHeight[a], right.pointHeight[b]);
            assertEquals(left.pointRgb[a], right.pointRgb[b]);
            assertEquals(left.pointNormal[a], right.pointNormal[b]);
        }
        FarMesher.Built mesh = FarMesher.build(job(0, 5, faces, null));
        try { assertNotNull(mesh.grid()); assertTrue(mesh.quads().length > 0); }
        finally { mesh.free(); }
    }

    @Test void finestLevelsAndColumnsContainingNearCubesKeepTheirBlockGeometry() throws Exception {
        FarFaces faces = faces();
        assertFalse(FarMesher.smooth(job(0, 1, faces, null)));
        assertFalse(FarMesher.smooth(job(0, 2, faces, null)));
        assertFalse(FarMesher.smooth(job(0, 3, faces, null)));
        assertFalse(FarMesher.smooth(job(0, 4, faces, null)), "keep levels containing player edits in block form");
        LongOpenHashSet mask = new LongOpenHashSet(); mask.add(CubeKey.pack(0, 0, 0));
        assertFalse(FarMesher.smooth(job(0, 5, faces, mask)));
        FarMesher.Built mesh = FarMesher.build(job(0, 5, faces, mask));
        try { assertNull(mesh.grid()); } finally { mesh.free(); }
    }

    @Test void packetOnlyNeighboursProduceTheSameSmoothBoundariesAsDenseNeighbours() throws Exception {
        FarMesher.Job reference = job(0, 5, faces(), null);
        FarMesher.Side[] sides = packets(reference.sides()), corners = packets(reference.diagonals());
        FarMesher.Job candidate = new FarMesher.Job(reference.key(), reference.version(), reference.level(), reference.slot(),
                reference.x0(), reference.z0(), reference.bottom(), reference.sections(), reference.drops(), sides, corners,
                reference.drawn(), reference.textured(), reference.looks(), reference.faces(), null, reference.shade(), reference.white());
        FarMesher.Built a = FarMesher.buildDenseOracle(reference), b = FarMesher.build(candidate);
        try { assertArrayEquals(a.grid(), b.grid()); assertArrayEquals(a.quads(), b.quads()); assertArrayEquals(a.counts(), b.counts()); }
        finally { a.free(); b.free(); }
    }

    @Test void skyOnlyCubesDoNotMakeAVisibleBlockyRectangleInSmoothGround() throws Exception {
        LongOpenHashSet sky = new LongOpenHashSet(); sky.add(CubeKey.pack(0, 800, 0));
        var job = job(0, 5, faces(), sky);
        assertTrue(FarMesher.smooth(job));
        var mesh = FarMesher.build(job);
        try { assertNotNull(mesh.grid()); } finally { mesh.free(); }
    }

    @Test void coarseWaterIsNearlyOpaqueWhileNearTexturedWaterKeepsItsTextureAlpha() throws Exception {
        FarFaces faces = faces();
        assertTrue((new FarColumnMesh(job(0, 4, faces, null)).colour(Blocks.WATER.defaultBlockState(), null, Direction.UP) >>> 24) >= 250);
        assertEquals(255, new FarColumnMesh(job(0, 2, faces, null)).colour(Blocks.WATER.defaultBlockState(), null, Direction.UP) >>> 24);
    }

    @Test void highFlightAndLegacyThresholdCannotSmoothDetailedBlockLevels() throws Exception {
        FarFaces faces = faces();
        for (int level = 1; level <= Far.EDIT_LEVELS; level++) {
            var normal = job(0, level, faces, null);
            var legacy = new FarMesher.Job(normal.key(), normal.version(), normal.level(), normal.slot(), normal.x0(), normal.z0(), normal.bottom(),
                    normal.sections(), normal.drops(), normal.sides(), normal.diagonals(), normal.drawn(), normal.textured(), normal.looks(), normal.faces(),
                    normal.shaderIds(), normal.shade(), normal.white(), null, 1);
            assertFalse(FarMesher.smooth(legacy));
            assertFalse(FarMesher.smooth(legacy.dense()));
            var built = FarMesher.build(legacy);
            try {
                assertNull(built.grid(), "detailed levels retain voxel faces at every height");
                assertTrue(built.quads().length > 0);
                for (int i = 0; i < built.quads().length; i += 4)
                    assertTrue(((built.quads()[i] >>> 10) & 7) < 6, "no smooth surface/wall faces");
            } finally { built.free(); }
        }
        assertTrue(FarMesher.smooth(job(0, 5, faces, null)), "coarse scenery still smooths");
    }

    private static FarMesher.Side[] packets(FarMesher.Side[] dense) {
        FarMesher.Side[] result = new FarMesher.Side[dense.length];
        for (int i = 0; i < dense.length; i++) {
            var source = dense[i];
            var packet = FarSurfacePacket.fromDense(5, source.bottom(), source.sections(), source.drops());
            assertNotNull(packet);
            result[i] = new FarMesher.Side(null, source.bottom(), source.drops(), packet);
        }
        return result;
    }

    private static FarMesher.Job job(int cx, int level, FarFaces faces, LongOpenHashSet mask) {
        FarMesher.Side[] sides = {side(cx, -1), side(cx, 1), side(cx - 1, 0), side(cx + 1, 0)};
        FarMesher.Side[] diagonals = {side(cx - 1, -1), side(cx + 1, -1), side(cx - 1, 1), side(cx + 1, 1)};
        FarMesher.Side own = side(cx, 0);
        return new FarMesher.Job(1, 1, level, 0, cx * Far.width(level), 0, 0, own.sections(), own.drops(), sides, diagonals,
                mask, true, new FarLooks(), faces, null, new float[] {.5f, 1, .8f, .8f, .6f, .6f}, new float[] {1, 1, 1, 1, 1, 1});
    }

    private static FarMesher.Side side(int cx, int cz) {
        LevelChunkSection section = new LevelChunkSection(SurfacePacketFixtures.biomes());
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int top = Math.clamp(8 + Math.floorDiv(cx * 16 + x, 8), 0, 15);
            for (int y = 0; y <= top; y++) section.setBlockState(x, y, z, Blocks.STONE.defaultBlockState(), false);
        }
        section.recalcBlockCounts();
        return new FarMesher.Side(new LevelChunkSection[] {section}, 0, new char[256]);
    }

    @SuppressWarnings("unchecked") private static FarFaces faces() throws Exception {
        SurfacePacketFixtures.biomes();
        FarFaces result = new FarFaces();
        var field = FarFaces.class.getDeclaredField("faces"); field.setAccessible(true);
        Map<BlockState, FarFaces.Face[]> cache = (Map<BlockState, FarFaces.Face[]>) field.get(result);
        FarFaces.Face[] look = new FarFaces.Face[Direction.values().length];
        Arrays.fill(look, new FarFaces.Face(0, 0, 1, 1, 0x808080, -1));
        cache.put(Blocks.STONE.defaultBlockState(), look);
        FarFaces.Face[] water = new FarFaces.Face[Direction.values().length];
        Arrays.fill(water, new FarFaces.Face(0, 0, 1, 1, 0x604070a0, -1));
        cache.put(Blocks.WATER.defaultBlockState(), water);
        return result;
    }
}
