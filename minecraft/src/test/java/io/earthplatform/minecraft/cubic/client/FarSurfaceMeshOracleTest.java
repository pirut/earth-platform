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
    private static java.util.Map<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>, java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.block.Block>>> previousBlockTags;
    @org.junit.jupiter.api.BeforeAll static void fluidTags() {
        SurfacePacketFixtures.biomes();
        var registry = net.minecraft.core.registries.BuiltInRegistries.FLUID;
        previousFluidTags = registry.getTags().collect(java.util.stream.Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var tags = new java.util.HashMap<>(previousFluidTags);
        tags.put(net.minecraft.tags.FluidTags.WATER, java.util.List.of(registry.wrapAsHolder(net.minecraft.world.level.material.Fluids.WATER), registry.wrapAsHolder(net.minecraft.world.level.material.Fluids.FLOWING_WATER)));
        registry.bindTags(tags);
        var blocks=net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        previousBlockTags=blocks.getTags().collect(java.util.stream.Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var blockTags=new java.util.HashMap<>(previousBlockTags);
        blockTags.put(net.minecraft.tags.BlockTags.LEAVES,blocks.stream().filter(b -> b instanceof net.minecraft.world.level.block.LeavesBlock).map(blocks::wrapAsHolder).toList());
        blockTags.put(net.minecraft.tags.BlockTags.LOGS,java.util.List.of(blocks.wrapAsHolder(net.minecraft.world.level.block.Blocks.OAK_LOG)));
        blocks.bindTags(blockTags);
    }
    @org.junit.jupiter.api.AfterAll static void restoreTags() {
        net.minecraft.core.registries.BuiltInRegistries.FLUID.bindTags(previousFluidTags);
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.bindTags(previousBlockTags);
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
    private static FarMesher.Job direct(FarColumn column, int level, boolean masked, FarFaces faces) {
        var old=job(column,level,masked,faces);
        FarMesher.Side[] sides=new FarMesher.Side[4];
        for(int i=0;i<4;i++)sides[i]=new FarMesher.Side(null,column.bottom,old.drops(),column.packet);
        return new FarMesher.Job(old.key(),old.version(),old.level(),old.slot(),old.x0(),old.z0(),old.bottom(),new LevelChunkSection[0],old.drops(),
                sides,sides,old.drawn(),old.textured(),old.looks(),old.faces(),old.shaderIds(),old.shade(),old.white(),column.packet);
    }
    private static void equal(FarMesher.Built a,FarMesher.Built b) {
        assertArrayEquals(a.quads(),b.quads());assertArrayEquals(a.counts(),b.counts());assertArrayEquals(a.grid(),b.grid());
        part(a.water(),b.water());for(int wall=0;wall<4;wall++)part(a.waterWalls()[wall],b.waterWalls()[wall]);
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
            var a = FarMesher.buildDenseOracle(job(dense, level, masked, faces));
            var b = FarMesher.buildDenseOracle(job(packet, level, masked, faces));
            var c = FarMesher.build(direct(packet, level, masked, faces));
            var converted=FarMesher.buildRunPrototype(job(dense,level,masked,faces));
            try {
                equal(a,c);equal(a,converted);
                assertArrayEquals(a.quads(), b.quads()); assertArrayEquals(a.counts(), b.counts()); assertArrayEquals(a.grid(), b.grid());
                part(a.water(), b.water()); if (a.water() != null) waterParts++;
                for (int wall = 0; wall < 4; wall++) part(a.waterWalls()[wall], b.waterWalls()[wall]);
                quads += a.quads().length / 4; jobs++;
            } finally { a.free(); b.free(); c.free(); converted.free(); }
        }
        assertTrue(quads > 0); assertTrue(waterParts > 0); System.out.println("SURFACE_MESH_ORACLE jobs=" + jobs + " quads=" + quads + " waterParts=" + waterParts + " directRunAndLegacy ordering/attributes exact");
    }
    @Test void differentNeighbourDropsBottomsAndBiomesKeepDirectFacesAndLookTableExact() throws Exception {
        var g=new SurfacePacketFixtures.Country(4);
        for(int level:new int[]{3,5,8}) {
            var own=g.column(level,-2,3,true);var faces=faces(own.sections.toArray(LevelChunkSection[]::new));
            var old=job(own,level,false,faces);var prototype=direct(own,level,false,faces);
            FarMesher.Side[] denseSides=new FarMesher.Side[4],runSides=new FarMesher.Side[4];
            int[][] positions={{-2,2},{-2,4},{-3,3},{-1,3}};
            for(int i=0;i<4;i++) {
                var next=g.column(level,positions[i][0],positions[i][1],true);char[] drops=new char[256];
                for(int cell=0;cell<256;cell++)drops[cell]=(char)(next.drops[cell]&65535);
                denseSides[i]=new FarMesher.Side(next.sections.toArray(LevelChunkSection[]::new),next.bottom,drops);
                runSides[i]=new FarMesher.Side(null,next.bottom,drops,next.packet);
            }
            var aJob=new FarMesher.Job(old.key(),old.version(),level,old.slot(),old.x0(),old.z0(),old.bottom(),old.sections(),old.drops(),denseSides,denseSides,old.drawn(),false,old.looks(),faces,null,old.shade(),old.white());
            var bJob=new FarMesher.Job(prototype.key(),prototype.version(),level,prototype.slot(),prototype.x0(),prototype.z0(),prototype.bottom(),prototype.sections(),prototype.drops(),runSides,runSides,prototype.drawn(),false,prototype.looks(),faces,null,prototype.shade(),prototype.white(),own.packet);
            var a=FarMesher.buildDenseOracle(aJob);var b=FarMesher.build(bJob);
            try {equal(a,b);assertArrayEquals(aJob.looks().copy(0,aJob.looks().count()),bJob.looks().copy(0,bJob.looks().count()));}
            finally{a.free();b.free();}
        }
    }
    @Test void runCandidateOmissionOnlySkipsFullyHiddenOpaqueInteriorVoxels() {
        var packet=new SurfacePacketFixtures.Country(0).column(4,0,0,true).packet;
        var candidates=FarRunCandidates.make(packet);int omitted=0;
        for(int y=0;y<packet.height();y++)for(int z=1;z<15;z++)for(int x=1;x<15;x++) {
            var state=packet.block(x,y,z);
            if(state.isAir()||candidates.get(y*256+z*16+x))continue;
            omitted++;assertFalse(FarMesher.seeThrough(state));
            assertTrue(y<packet.height()-1);
            for(int row=Math.max(0,y-1);row<=y+1;row++)for(int[] at:new int[][]{{x-1,z},{x+1,z},{x,z-1},{x,z+1}}) {
                var neighbour=packet.block(at[0],row,at[1]);assertFalse(neighbour.isAir());assertFalse(FarMesher.seeThrough(neighbour));
            }
            if(y>0)assertFalse(packet.block(x,y-1,z).isAir());assertFalse(packet.block(x,y+1,z).isAir());
        }
        assertTrue(omitted>0);System.out.println("RUN_CANDIDATE_ORACLE omittedHiddenVoxels="+omitted);
    }
    @Test void editedPacketDirectConsumerUsesExactDenseFallback() throws Exception {
        var g=new SurfacePacketFixtures.Country(0);var column=g.column(4,0,0,true);
        column.packet=column.packet.withEdits(0,1,List.of(new FarSurfacePacket.Edit(5,10,5,net.minecraft.world.level.block.Blocks.CAVE_AIR.defaultBlockState()),new FarSurfacePacket.Edit(5,11,5,net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState())));
        column.sections.clear();column.sections.addAll(column.packet.materialize(SurfacePacketFixtures.biomes().asHolderIdMap()));
        var faces=faces(column.sections.toArray(LevelChunkSection[]::new));
        var a=FarMesher.buildDenseOracle(job(column,4,false,faces));var b=FarMesher.build(direct(column,4,false,faces));
        try{equal(a,b);}finally{a.free();b.free();}
    }
    private static FarColumn flatColumn() {
        var biome=SurfacePacketFixtures.biomes().getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        var run=List.of(new FarSurfacePacket.Run(4,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()),
                new FarSurfacePacket.Run(16,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
        FarColumn column=new FarColumn();
        column.packet=new FarSurfacePacket(4,0,16,column.drops,biome,Collections.nCopies(16,biome),Collections.nCopies(256,run));
        column.sections.addAll(column.packet.materializeForMeshing());return column;
    }
    @Test void editedNeighbourPacketsKeepPackedNormalsAndSkirtsForPreparedAndConvertedOwnColumns() throws Exception {
        var own=flatColumn();var neighbour=flatColumn();
        var stone=net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        List<FarSurfacePacket.Edit> edits=new ArrayList<>();
        for(int i=0;i<16;i++) {edits.add(new FarSurfacePacket.Edit(i,10,15,stone));edits.add(new FarSurfacePacket.Edit(0,11,i,stone));}
        neighbour.packet=neighbour.packet.withEdits(0,1,edits);
        var denseNeighbour=neighbour.packet.materializeForMeshing().toArray(LevelChunkSection[]::new);
        assertEquals(10,FarMesher.groundRow(denseNeighbour,5,15));
        assertEquals(10,neighbour.packet.top(15*16+5,state->!state.isAir()));
        var faces=faces(own.sections.toArray(LevelChunkSection[]::new));
        for(boolean prepared:new boolean[]{false,true})for(boolean diagonalOnly:new boolean[]{false,true}) {
            var baseline=job(own,4,false,faces);var prototype=prepared?direct(own,4,false,faces):job(own,4,false,faces);
            FarMesher.Side[] denseSides=new FarMesher.Side[4],runSides=new FarMesher.Side[4];
            FarMesher.Side[] denseDiagonals=new FarMesher.Side[4],runDiagonals=new FarMesher.Side[4];
            for(int i=0;i<4;i++) {
                denseSides[i]=new FarMesher.Side(diagonalOnly?baseline.sections():denseNeighbour,0,baseline.drops());
                runSides[i]=diagonalOnly?denseSides[i]:new FarMesher.Side(null,0,baseline.drops(),neighbour.packet);
                denseDiagonals[i]=new FarMesher.Side(denseNeighbour,0,baseline.drops());
                runDiagonals[i]=new FarMesher.Side(null,0,baseline.drops(),neighbour.packet);
            }
            var referenceJob=new FarMesher.Job(1,1,4,0,0,0,0,baseline.sections(),baseline.drops(),denseSides,denseDiagonals,baseline.drawn(),false,baseline.looks(),faces,null,baseline.shade(),baseline.white());
            var candidateJob=new FarMesher.Job(1,1,4,0,0,0,0,prototype.sections(),prototype.drops(),runSides,runDiagonals,prototype.drawn(),false,prototype.looks(),faces,null,prototype.shade(),prototype.white(),prepared?own.packet:null);
            var reference=FarMesher.buildDenseOracle(referenceJob);var candidate=FarMesher.buildRunPrototype(candidateJob);
            try {equal(reference,candidate);assertArrayEquals(referenceJob.looks().copy(0,referenceJob.looks().count()),candidateJob.looks().copy(0,candidateJob.looks().count()));}
            finally{reference.free();candidate.free();}
        }
    }
    @Test void convertedDenseCavesOverhangsPartialWaterAndStatePropertiesKeepPackedOutputExact() throws Exception {
        var column=flatColumn();
        List<FarSurfacePacket.Edit> edits=new ArrayList<>();
        edits.add(new FarSurfacePacket.Edit(5,1,5,net.minecraft.world.level.block.Blocks.CAVE_AIR.defaultBlockState()));
        for(int i=0;i<8;i++) {
            edits.add(new FarSurfacePacket.Edit(5+i,8,5,net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS,net.minecraft.core.Direction.Axis.X)));
            edits.add(new FarSurfacePacket.Edit(5+i,9,5,net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT,true)));
            edits.add(new FarSurfacePacket.Edit(5+i,4,6,net.minecraft.world.level.block.Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL,i)));
        }
        column.packet=column.packet.withEdits(0,1,edits);column.sections.clear();column.sections.addAll(column.packet.materializeForMeshing());
        for(int i=0;i<256;i++)column.drops[i]=(short)((i*677)&4095);
        var faces=faces(column.sections.toArray(LevelChunkSection[]::new));var baseline=job(column,4,false,faces);var prototype=job(column,4,false,faces);
        var before=FarMesher.runStats();var a=FarMesher.buildDenseOracle(baseline);var b=FarMesher.buildRunPrototype(prototype);
        try {assertTrue(FarMesher.runStats().used()>before.used());equal(a,b);assertArrayEquals(baseline.looks().copy(0,baseline.looks().count()),prototype.looks().copy(0,prototype.looks().count()));}
        finally{a.free();b.free();}
    }
    @Test void directRunMesherComponentBench() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("EARTH_SURFACE_BENCH")));
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        if(bean.isThreadAllocatedMemorySupported())bean.setThreadAllocatedMemoryEnabled(true);
        var g=new SurfacePacketFixtures.Country(0);long tid=Thread.currentThread().threadId();
        for(int level:new int[]{3,5,8}) {
            var column=g.column(level,0,0,true);var faces=faces(column.sections.toArray(LevelChunkSection[]::new));
            for(int i=0;i<30;i++){var a=FarMesher.buildDenseOracle(job(column,level,false,faces));a.free();var b=FarMesher.build(direct(column,level,false,faces));b.free();}
            for(int round=0;round<4;round++)for(boolean direct:round%2==0?new boolean[]{false,true}:new boolean[]{true,false}) {
                long allocated=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();int n=50;
                for(int i=0;i<n;i++){var input=job(column,level,false,faces);
                    var result=direct?FarMesher.buildRunPrototype(input):FarMesher.buildDenseOracle(input);result.free();}
                long elapsed=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(tid)-allocated;
                System.out.printf(java.util.Locale.ROOT,"SURFACE_MESH_COMPONENT level=%d round=%d directRuns=%s usPerJob=%.3f javaAllocatedBytesPerJob=%.1f%n",level,round,direct,elapsed/1e3/n,bytes/(double)n);
            }
        }
    }

}
