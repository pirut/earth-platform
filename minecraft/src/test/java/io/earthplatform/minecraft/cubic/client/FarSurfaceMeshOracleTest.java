package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.gen.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

/** Actual packed mesher, synthetic stable looks: no model manager, display or GL context required. */
class FarSurfaceMeshOracleTest {
    private static java.util.Map<net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid>, java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.material.Fluid>>> previousFluidTags;
    @org.junit.jupiter.api.BeforeAll static void fluidTags() {
        SurfacePacketFixtures.biomes();
        var registry = net.minecraft.core.registries.BuiltInRegistries.FLUID;
        previousFluidTags = registry.getTags().collect(java.util.stream.Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var tags = new java.util.HashMap<>(previousFluidTags);
        tags.put(net.minecraft.tags.FluidTags.WATER, java.util.List.of(registry.wrapAsHolder(net.minecraft.world.level.material.Fluids.WATER), registry.wrapAsHolder(net.minecraft.world.level.material.Fluids.FLOWING_WATER)));
        registry.bindTags(tags);
    }
    @org.junit.jupiter.api.AfterAll static void restoreTags() {
        net.minecraft.core.registries.BuiltInRegistries.FLUID.bindTags(previousFluidTags);
    }
    @SuppressWarnings("unchecked") private static FarFaces faces(LevelChunkSection[] sections) throws Exception {
        FarFaces result = new FarFaces();
        var field = FarFaces.class.getDeclaredField("faces"); field.setAccessible(true);
        Map<BlockState, FarFaces.Face[]> cached = (Map<BlockState, FarFaces.Face[]>) field.get(result);
        for (LevelChunkSection section : sections) if (section != null)
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = section.getBlockState(x, y, z);
                cached.computeIfAbsent(state, s -> {
                    FarFaces.Face[] six = new FarFaces.Face[6];
                    Arrays.fill(six, new FarFaces.Face(0, 0, 1, 1, 0xff000000 | Block.getId(s) * 7411 & 0xffffff, -1));
                    return six;
                });
            }
        return result;
    }
    private static FarMesher.Job job(FarColumn column, int level, boolean masked, FarFaces faces) {
        var sections = column.sections.toArray(LevelChunkSection[]::new);
        char[] drops = new char[256]; for (int i = 0; i < 256; i++) drops[i] = (char) (column.drops[i] & 65535);
        FarMesher.Side[] sides = new FarMesher.Side[4];
        for (int i = 0; i < 4; i++) sides[i] = new FarMesher.Side(sections, column.bottom, drops);
        LongOpenHashSet drawn = new LongOpenHashSet();
        if (masked) {
            int cubes = Math.max(1, (1 << level) / 16), base = column.bottom * (1 << level);
            for (int y = base - 1; y <= base + 4 * cubes; y++) for (int z = 0; z < cubes; z++) for (int x = 0; x < cubes; x++)
                drawn.add(CubeKey.pack(x, y, z));
        }
        return new FarMesher.Job(1, 1, level, 0, 0, 0, column.bottom, sections, drops, sides, sides,
                drawn, false, new FarLooks(), faces, null, new float[] {0.5f, 1, 0.8f, 0.8f, 0.6f, 0.6f}, new float[] {1, 1, 1, 1, 1, 1});
    }
    private static void part(FarMesher.Part a, FarMesher.Part b) {
        assertEquals(a == null, b == null); if (a == null) return;
        var da = a.mesh().drawState(); var db = b.mesh().drawState();
        assertEquals(da, db);
        ByteBuffer av = a.mesh().vertexBuffer(), bv = b.mesh().vertexBuffer();
        assertEquals(av.remaining(), bv.remaining());
        int stride = da.format().getVertexSize();
        // Declared BLOCK attributes occupy bytes 0..30; byte 31 is uninitialized alignment padding.
        assertEquals(32, stride);
        for (int i = 0; i < av.remaining(); i++) if (i % stride != 31) assertEquals(av.get(av.position() + i), bv.get(bv.position() + i));
        assertEquals(a.mesh().indexBuffer(), b.mesh().indexBuffer());
    }
    @Test void legacyAdapterKeepsPackedFacesOrderingNormalsCoverageHaloAndWaterAttributes() throws Exception {
        var generator = new SurfacePacketFixtures.Country(0); int jobs = 0, waterParts = 0; long quads = 0;
        for (int level = 3; level <= 8; level++) for (boolean masked : new boolean[] {false, true}) {
            FarColumn dense = generator.column(level, 0, 0, false), packet = generator.column(level, 0, 0, true);
            FarFaces faces = faces(dense.sections.toArray(LevelChunkSection[]::new));
            var a = FarMesher.build(job(dense, level, masked, faces));
            var b = FarMesher.build(job(packet, level, masked, faces));
            try {
                assertArrayEquals(a.quads(), b.quads()); assertArrayEquals(a.counts(), b.counts()); assertArrayEquals(a.grid(), b.grid());
                part(a.water(), b.water()); if (a.water() != null) waterParts++;
                for (int wall = 0; wall < 4; wall++) part(a.waterWalls()[wall], b.waterWalls()[wall]);
                quads += a.quads().length / 4; jobs++;
            } finally { a.free(); b.free(); }
        }
        assertTrue(quads > 0); assertTrue(waterParts > 0); System.out.println("SURFACE_MESH_ORACLE jobs=" + jobs + " quads=" + quads + " waterParts=" + waterParts + " packed ordering/attributes exact");
    }
}
