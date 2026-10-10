package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import com.sun.management.ThreadMXBean;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.residency.*;
import io.earthplatform.minecraft.transform.SparseConstructorTransformer;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.jupiter.api.Assertions.*;

class SparseFacadeTest {
    private static RegistryAccess registries;
    private static Registry<Biome> biomes;
    private static Holder<DimensionType> dimensionType;
    private static EnginePaletteContext palette;
    private String previous;
    @BeforeAll static void prepare() {
        var lookup = VanillaRegistries.createLookup();
        biomes = registry(lookup, Registries.BIOME);
        registries = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes, registry(lookup, Registries.DAMAGE_TYPE))).freeze();
        dimensionType = lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        palette = EnginePaletteContext.create(biomes);
    }
    private static <T> Registry<T> registry(HolderLookup.Provider lookup, ResourceKey<? extends Registry<T>> key) {
        var result = new MappedRegistry<T>(key, Lifecycle.stable());
        lookup.lookupOrThrow(key).listElements().forEach(holder -> result.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        return result.freeze();
    }
    @BeforeEach void enable() { previous=System.getProperty(SparseCubeStorage.OPT_IN); System.setProperty(SparseCubeStorage.OPT_IN,"true"); }
    @AfterEach void restore() { if(previous==null)System.clearProperty(SparseCubeStorage.OPT_IN);else System.setProperty(SparseCubeStorage.OPT_IN,previous); }
    private static final class SparseLevel extends FacadeTestLevel implements SparseFacadeAccess {
        final SparseCubeStorage cubes;
        SparseLevel(int minY,int height,int cap,Set<CubePos> air) {
            super(registries,dimensionType,minY,height);
            cubes = new SparseCubeStorage(palette,minY,height,cap,64,air);
        }
        @Override public SparseCubeStorage earthplatform$sparseCubes() { return cubes; }
    }
    private static int arrayLength(ChunkAccess chunk,String name) throws Exception {
        Field field=ChunkAccess.class.getDeclaredField(name);field.setAccessible(true);
        return java.lang.reflect.Array.getLength(field.get(chunk));
    }
    private static SparseLevel earth(int cap,Set<CubePos> air) { return new SparseLevel(-11008,19872,cap,air); }
    private static LevelChunk chunk(SparseLevel level) { return new LevelChunk(level,new ChunkPos(0,0)); }

    @Test void actualLevelAndEmptyConstructorsAllocateZeroLegacySlots() throws Exception {
        var level=earth(2,Set.of());
        for(LevelChunk chunk:List.of(chunk(level),new EmptyLevelChunk(level,new ChunkPos(0,0),biomes.getHolderOrThrow(Biomes.PLAINS)))) {
            assertEquals(0,arrayLength(chunk,"sections"));assertEquals(0,arrayLength(chunk,"postProcessing"));
            assertEquals(-11008,chunk.getMinBuildHeight());assertEquals(19872,chunk.getHeight());
            assertThrows(SparseFacadeException.class,chunk::getSections);
            assertThrows(SparseFacadeException.class,()->chunk.getSection(0));
            assertThrows(SparseFacadeException.class,chunk::getPostProcessing);
            assertThrows(SparseFacadeException.class,()->chunk.isSectionEmpty(-688));
            assertThrows(SparseFacadeException.class,chunk::getHeightmaps);
            assertThrows(SparseFacadeException.class,chunk::initializeLightSources);
            assertThrows(SparseFacadeException.class,()->chunk.findBlocks(state->true,(pos,state)->fail("Unsupported scan must not silently return empty")));
        }
        assertEquals(0,level.cubes.residentCount());
    }
    @Test void realFacadeReadWritesRetainBothExtremesAndDoNotTouchAliases() {
        var low=new CubePos(0,-688,0);var high=new CubePos(0,553,0);
        var aliases=Set.of(CubePos.fromBlock(8,1288,8),CubePos.fromBlock(8,657,8));
        var level=earth(2,aliases);level.cubes.loadUniform(low,Blocks.AIR.defaultBlockState());level.cubes.loadUniform(high,Blocks.AIR.defaultBlockState());
        LevelChunk chunk=chunk(level);
        var bottom=new BlockPos(8,-11000,8);var top=new BlockPos(8,8849,8);
        assertSame(Blocks.AIR.defaultBlockState(),chunk.setBlockState(bottom,Blocks.GOLD_BLOCK.defaultBlockState(),false));
        chunk.setBlockState(top,Blocks.DIAMOND_BLOCK.defaultBlockState(),false);
        assertSame(Blocks.GOLD_BLOCK.defaultBlockState(),chunk.getBlockState(bottom));
        assertSame(Blocks.DIAMOND_BLOCK.defaultBlockState(),chunk.getBlockState(top));
        assertTrue(chunk.getFluidState(bottom).isEmpty());assertTrue(chunk.getFluidState(8,8849,8).isEmpty());
        assertSame(Blocks.AIR.defaultBlockState(),chunk.getBlockState(new BlockPos(8,1288,8)));
        assertSame(Blocks.AIR.defaultBlockState(),chunk.getBlockState(new BlockPos(8,657,8)));
        assertEquals(1,level.cubes.revision(low));assertEquals(1,level.cubes.revision(high));
        assertNull(chunk.setBlockState(top,Blocks.DIAMOND_BLOCK.defaultBlockState(),false));assertEquals(1,level.cubes.revision(high));
        assertEquals(2,level.cubes.residentCount());
    }
    @Test void emptyFacadeOverridesCannotSubstituteAirOrDiscardWrites() {
        var level=earth(1,Set.of());var position=new BlockPos(1,-11000,1);
        var empty=new EmptyLevelChunk(level,new ChunkPos(0,0),biomes.getHolderOrThrow(Biomes.PLAINS));
        assertThrows(SparseFacadeException.class,()->empty.getBlockState(position));
        assertThrows(SparseFacadeException.class,()->empty.getFluidState(position));
        assertThrows(SparseFacadeException.class,()->empty.setBlockState(position,Blocks.STONE.defaultBlockState(),false));
        level.cubes.loadUniform(CubePos.fromBlock(1,-11000,1),Blocks.AIR.defaultBlockState());
        empty.setBlockState(position,Blocks.GOLD_BLOCK.defaultBlockState(),false);
        assertSame(Blocks.GOLD_BLOCK.defaultBlockState(),empty.getBlockState(position));
        assertThrows(SparseFacadeException.class,empty::isEmpty);
        assertThrows(SparseFacadeException.class,()->empty.getNoiseBiome(0,0,0));
    }
    @Test void retiredEditedCubeNeverFallsBackToItsImplicitAirBaseline() {
        var low=new CubePos(0,-688,0);var high=new CubePos(0,553,0);var level=earth(2,Set.of(low,high));
        var chunk=chunk(level);var p=new BlockPos(1,-11000,1);var q=new BlockPos(1,8849,1);
        assertSame(Blocks.AIR.defaultBlockState(),chunk.getBlockState(p));assertEquals(0,level.cubes.residentCount());
        assertThrows(SparseFacadeException.class,()->chunk.setBlockState(p,Blocks.STONE.defaultBlockState(),false));
        level.cubes.materializeImplicitAir(low);level.cubes.materializeImplicitAir(high);
        chunk.setBlockState(p,Blocks.GOLD_BLOCK.defaultBlockState(),false);chunk.setBlockState(q,Blocks.DIAMOND_BLOCK.defaultBlockState(),false);
        level.cubes.retire(low);
        assertEquals(SparseCubeStorage.Presence.UNLOADED_OWNED,level.cubes.presence(low));
        assertThrows(SparseFacadeException.class,()->chunk.getBlockState(p));
        assertThrows(SparseFacadeException.class,()->level.cubes.materializeImplicitAir(low));
        assertThrows(SparseFacadeException.class,()->level.cubes.loadUniform(low,Blocks.AIR.defaultBlockState()));
        assertSame(Blocks.DIAMOND_BLOCK.defaultBlockState(),chunk.getBlockState(q));assertEquals(1,level.cubes.residentCount());
        level.cubes.reload(low);assertSame(Blocks.GOLD_BLOCK.defaultBlockState(),chunk.getBlockState(p));assertEquals(1,level.cubes.revision(low));
        assertEquals(0,level.cubes.retiredCount());assertEquals(2,level.cubes.residentCount());
    }
    @Test void defaultAndUnmarkedVanillaConstructorsRemainUnchangedEvenWithFlagEnabled() {
        for(int height:new int[]{256,384,19872}) {
            var normal=new FacadeTestLevel(registries,dimensionType,height==19872?-11008:0,height);
            var chunk=new LevelChunk(normal,new ChunkPos(0,0));
            assertEquals(height/16,chunk.getSections().length);assertTrue(Arrays.stream(chunk.getSections()).allMatch(Objects::nonNull));
            var empty=new EmptyLevelChunk(normal,new ChunkPos(0,0),biomes.getHolderOrThrow(Biomes.PLAINS));
            assertEquals(height/16,empty.getSections().length);assertSame(Blocks.VOID_AIR.defaultBlockState(),empty.getBlockState(BlockPos.ZERO));
        }
        System.clearProperty(SparseCubeStorage.OPT_IN);
        assertThrows(SparseFacadeException.class,()->earth(1,Set.of()));
        var normal=new FacadeTestLevel(registries,dimensionType,-64,384);assertEquals(24,new LevelChunk(normal,new ChunkPos(0,0)).getSections().length);
    }
    @Test void unsupportedMutationBoundsAndBudgetFailBeforeAnyStateChange() {
        var level=earth(1,Set.of());var pos=new BlockPos(1,8849,1);var cube=CubePos.fromBlock(1,8849,1);level.cubes.loadUniform(cube,Blocks.STONE.defaultBlockState());
        var chunk=chunk(level);
        assertThrows(SparseFacadeException.class,()->chunk.setBlockState(pos,Blocks.WATER.defaultBlockState(),false));
        assertThrows(SparseFacadeException.class,()->chunk.setBlockState(pos,Blocks.CHEST.defaultBlockState(),false));
        assertThrows(SparseFacadeException.class,()->chunk.setBlockState(pos,Blocks.AIR.defaultBlockState(),true));
        assertThrows(SparseFacadeException.class,()->chunk.getBlockState(new BlockPos(16,8849,0)));
        assertThrows(SparseFacadeException.class,()->chunk.getBlockState(new BlockPos(0,8864,0)));
        assertThrows(SparseFacadeException.class,()->level.cubes.loadUniform(new CubePos(0,-688,0),Blocks.AIR.defaultBlockState()));
        assertThrows(SparseFacadeException.class,chunk::getBlockTicks);
        assertThrows(SparseFacadeException.class,()->chunk.getBlockEntity(pos,LevelChunk.EntityCreationType.IMMEDIATE));
        assertSame(Blocks.STONE.defaultBlockState(),chunk.getBlockState(pos));assertEquals(0,level.cubes.revision(cube));assertEquals(1,level.cubes.residentCount());
    }
    @Test void crossThreadAccessFailsWithoutHandingOutMutableSections() throws Exception {
        var level=earth(1,Set.of());var chunk=chunk(level);level.cubes.loadUniform(new CubePos(0,553,0),Blocks.STONE.defaultBlockState());
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread other=new Thread(()->{try{chunk.setBlockState(new BlockPos(0,8849,0),Blocks.AIR.defaultBlockState(),false);}catch(Throwable expected){failure.set(expected);}});
        other.start();other.join();assertInstanceOf(SparseFacadeException.class,failure.get());
        assertSame(Blocks.STONE.defaultBlockState(),chunk.getBlockState(new BlockPos(0,8849,0)));
    }
    @Test void allLocalCellsAndAdjacentNativeYBoundariesRemainDistinct() {
        for(int[] pair:new int[][]{{2047,2048},{-2049,-2048},{-1,0}}) {
            var level=earth(2,Set.of());var a=CubePos.fromBlock(-1,pair[0],-1);var b=CubePos.fromBlock(-1,pair[1],-1);
            level.cubes.loadUniform(a,Blocks.AIR.defaultBlockState());level.cubes.loadUniform(b,Blocks.AIR.defaultBlockState());
            var chunk=new LevelChunk(level,new ChunkPos(-1,-1));
            var p=new BlockPos(-1,pair[0],-1);var q=new BlockPos(-1,pair[1],-1);
            chunk.setBlockState(p,Blocks.GOLD_BLOCK.defaultBlockState(),false);chunk.setBlockState(q,Blocks.DIAMOND_BLOCK.defaultBlockState(),false);
            assertSame(Blocks.GOLD_BLOCK.defaultBlockState(),chunk.getBlockState(p));assertSame(Blocks.DIAMOND_BLOCK.defaultBlockState(),chunk.getBlockState(q));
        }
        for(int cubeY:new int[]{-688,553}) {
            var level=earth(1,Set.of());var cube=new CubePos(1252344,cubeY,-626172);level.cubes.loadUniform(cube,Blocks.AIR.defaultBlockState());
            var chunk=new LevelChunk(level,new ChunkPos(cube.x(),cube.z()));
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                var p=new BlockPos((int)cube.minBlockX()+x,(int)cube.minBlockY()+y,(int)cube.minBlockZ()+z);
                var state=((x+2*y+3*z)&1)==0?Blocks.GOLD_BLOCK.defaultBlockState():Blocks.DIAMOND_BLOCK.defaultBlockState();
                chunk.setBlockState(p,state,false);
            }
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                var p=new BlockPos((int)cube.minBlockX()+x,(int)cube.minBlockY()+y,(int)cube.minBlockZ()+z);
                assertSame(((x+2*y+3*z)&1)==0?Blocks.GOLD_BLOCK.defaultBlockState():Blocks.DIAMOND_BLOCK.defaultBlockState(),chunk.getBlockState(p));
            }
            assertEquals(4096,level.cubes.revision(cube));
        }
    }
    @Test void constructorRejectsLegacyImportsAndInvalidCapabilities() {
        var level=earth(1,Set.of());
        assertThrows(SparseFacadeException.class,()->new LevelChunk(level,new ChunkPos(0,0),UpgradeData.EMPTY,
                new net.minecraft.world.ticks.LevelChunkTicks<>(),new net.minecraft.world.ticks.LevelChunkTicks<>(),0L,
                new LevelChunkSection[]{new LevelChunkSection(biomes)},null,null));
        class InvalidLevel extends FacadeTestLevel implements SparseFacadeAccess {
            InvalidLevel() { super(registries,dimensionType,-11008,19872); }
            @Override public SparseCubeStorage earthplatform$sparseCubes() { return null; }
        }
        assertThrows(SparseFacadeException.class,()->new EmptyLevelChunk(new InvalidLevel(),new ChunkPos(0,0),biomes.getHolderOrThrow(Biomes.PLAINS)));
        assertThrows(SparseFacadeException.class,()->new ProtoChunk(new ChunkPos(0,0),UpgradeData.EMPTY,level,biomes,null));
        assertThrows(SparseFacadeException.class,()->level.cubes.loadUniform(new CubePos(Integer.MAX_VALUE,0,0),Blocks.AIR.defaultBlockState()));
        assertEquals(0,level.cubes.residentCount());
    }
    @Test void implicitKnowledgeIsDefensivelyCopiedAndRetirementRemainsOwnedOnBudgetFailure() {
        var low=new CubePos(0,-688,0);var high=new CubePos(0,553,0);var mutableKnowledge=new HashSet<CubePos>();mutableKnowledge.add(low);
        var storage=new SparseCubeStorage(palette,-11008,19872,1,2,mutableKnowledge);
        mutableKnowledge.clear();mutableKnowledge.add(high);
        assertEquals(SparseCubeStorage.Presence.KNOWN_IMPLICIT_AIR,storage.presence(low));assertEquals(SparseCubeStorage.Presence.UNKNOWN,storage.presence(high));
        storage.materializeImplicitAir(low);var p=new BlockPos(0,-11000,0);
        storage.set(new ChunkPos(0,0),p,Blocks.GOLD_BLOCK.defaultBlockState(),false);storage.retire(low);
        storage.loadUniform(high,Blocks.DIAMOND_BLOCK.defaultBlockState());
        assertThrows(SparseFacadeException.class,()->storage.reload(low));assertEquals(1,storage.retiredCount());
        storage.retire(high);assertThrows(SparseFacadeException.class,()->storage.loadUniform(new CubePos(0,0,0),Blocks.AIR.defaultBlockState()));
        storage.reload(low);assertSame(Blocks.GOLD_BLOCK.defaultBlockState(),storage.get(new ChunkPos(0,0),p));assertEquals(1,storage.revision(low));
    }
    private static long allocated(SparseLevel level,boolean empty,ThreadMXBean memory) throws Exception {
        var retained=new ArrayList<LevelChunk>();long start=memory.getThreadAllocatedBytes(Thread.currentThread().threadId());
        for(int i=0;i<64;i++)retained.add(empty?new EmptyLevelChunk(level,new ChunkPos(i,0),biomes.getHolderOrThrow(Biomes.PLAINS)):new LevelChunk(level,new ChunkPos(i,0)));
        long bytes=memory.getThreadAllocatedBytes(Thread.currentThread().threadId())-start;
        assertEquals(0,arrayLength(retained.getLast(),"sections"));assertEquals(0,arrayLength(retained.getLast(),"postProcessing"));
        return bytes/64;
    }
    @Test void warmedFacadeAllocationIsBoundedIndependentlyOfDeclaredHeight() throws Exception {
        var normal=new SparseLevel(-64,384,2,Set.of());var tall=earth(2,Set.of());
        var memory=(ThreadMXBean)ManagementFactory.getThreadMXBean();assertTrue(memory.isThreadAllocatedMemorySupported());memory.setThreadAllocatedMemoryEnabled(true);
        for(int i=0;i<3;i++){allocated(normal,false,memory);allocated(tall,false,memory);allocated(normal,true,memory);allocated(tall,true,memory);}
        long normalLevel=allocated(normal,false,memory),tallLevel=allocated(tall,false,memory);
        long normalEmpty=allocated(normal,true,memory),tallEmpty=allocated(tall,true,memory);
        assertTrue(tallLevel<32768);assertTrue(tallEmpty<32768);
        assertTrue(tallLevel<normalLevel*2);assertTrue(tallEmpty<normalEmpty*2);
        String result="{\"scope\":\"warm allocated bytes per actual facade constructor; not retained heap, live-level or gameplay performance\","
                +"\"normalDeclaredSections\":24,\"earthDeclaredSections\":1242,\"actualLegacySectionSlots\":0,\"actualLegacyPostprocessingSlots\":0,"
                +"\"normalLevelChunkBytes\":"+normalLevel+",\"earthLevelChunkBytes\":"+tallLevel+",\"normalEmptyLevelChunkBytes\":"+normalEmpty+",\"earthEmptyLevelChunkBytes\":"+tallEmpty+"}";
        Path report=Path.of("build/reports/earthplatform/sparse-facade-allocation.json");Files.createDirectories(report.getParent());Files.writeString(report,result+"\n");
        System.out.println("SPARSE_FACADE_ALLOCATION "+result);
    }
    private static ClassNode original() throws Exception {
        var node=new ClassNode();try(var zip=new ZipFile(System.getProperty("earthplatform.test.minecraftJar"));var input=zip.getInputStream(zip.getEntry("net/minecraft/world/level/chunk/ChunkAccess.class"))) {
            new ClassReader(input).accept(node,0);
        }return node;
    }
    @Test void pinnedConstructorShapesVerifyAndMalformedTargetsRejectWithoutPartialPatch() throws Exception {
        var node=original();SparseConstructorTransformer.apply(node);int calls=0;
        for(var method:node.methods)if(method.name.equals("<init>")) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name,method);
            for(var insn:method.instructions)if(insn instanceof MethodInsnNode call && call.name.equals("constructorSectionCount"))calls++;
        }
        assertEquals(2,calls);assertThrows(IllegalStateException.class,()->SparseConstructorTransformer.apply(node));
        var bad=original();for(var method:bad.methods)if(method.name.equals("<init>"))for(var insn:method.instructions)if(insn instanceof FieldInsnNode field && field.name.equals("postProcessing"))field.name="unexpectedArray";
        assertThrows(IllegalStateException.class,()->SparseConstructorTransformer.apply(bad));
        for(var method:bad.methods)for(var insn:method.instructions)if(insn instanceof MethodInsnNode call)assertNotEquals("constructorSectionCount",call.name);
    }
}
