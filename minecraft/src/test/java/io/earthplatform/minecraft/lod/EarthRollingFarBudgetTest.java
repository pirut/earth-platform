package io.earthplatform.minecraft.lod;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.enginebridge.NativeEarthCubeProvider;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.*;
import java.util.concurrent.CompletionException;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Actual accepted Earth source and full rolling masks. CPU contracts, never a GL/gameplay claim. */
@Timeout(60)
class EarthRollingFarBudgetTest {
    static final long X=9_674_936, Z=-3_112_472;
    static final long RESULT_CAP=5L*1024*1024, CACHE_CAP=20L*1024*1024, GPU_CAP=8L*1024*1024;
    static EnginePaletteContext palette;
    @BeforeAll static void registries(){
        var registry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        registry.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(registry.freeze());
    }
    static NativeEarthCubeProvider provider(){return NativeEarthCubeProvider.prepare(palette,true);}
    static CubePos pin(SceneSurfaceSampler source){return CubePos.fromBlock(X,source.surfaceY(X,Z),Z);}
    static CubePos center(SceneSurfaceSampler source,CubePos pin,int dx,int dz){
        long x=pin.minBlockX()+16L*dx+8,z=pin.minBlockZ()+16L*dz+8;
        return CubePos.fromBlock(x,source.surfaceY(x,z)+1,z);
    }
    static List<int[]> offsets(){return List.of(new int[]{0,0},new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1},
            new int[]{1,1},new int[]{-1,-1},new int[]{2,3},new int[]{3,-3},
            new int[]{4,0},new int[]{-4,0},new int[]{0,4},new int[]{0,-4},
            new int[]{8,0},new int[]{-8,0},new int[]{0,8},new int[]{0,-8},new int[]{8,8},new int[]{-8,-8},
            new int[]{16,0},new int[]{-16,0},new int[]{0,16},new int[]{0,-16},new int[]{16,-16},new int[]{-16,16},
            new int[]{30,30},new int[]{-30,-30},new int[]{31,31},new int[]{-31,-31},new int[]{32,32},new int[]{-32,-32});}

    @Test void anchorHasActualSlopeAndEditableInteriorAboveItsGround(){
        var earth=provider();var source=earth.surfaceSampler();var pin=pin(source);long ground=source.surfaceY(X,Z);
        assertEquals(7506,ground);assertEquals(new CubePos(604683,469,-194530),pin);
        assertTrue(RollingCubePlan.isEditableInterior(pin,X,ground,Z));
        assertTrue(RollingCubePlan.isEditableInterior(pin,X,ground+1,Z));
        assertEquals(pin,CubePos.fromBlock(X,ground+3,Z));
        var heights=new TreeSet<Long>();for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)heights.add(source.surfaceY(X+x,Z+z));
        assertTrue(heights.size()>1,"Use an actual slope, not the old flat oracle");
        for(var p:RollingCubePlan.stationary(pin,pin).committedOwners())assertTrue(earth.load(p,new CancellationToken()).isDone());
        // Fully verify the prepared entry's known source/collision window, including air/stone cubes.
        for(var p:RollingCubePlan.stationary(pin,pin).committedOwners())assertNotNull(earth.load(p,new CancellationToken()).join());
        System.out.println("EARTH_ROLLING_ANCHOR x="+X+" z="+Z+" ground="+ground+" localY="+Math.floorMod(ground,16)
                +" neighborHeights="+heights+" pin="+pin+" world="+earth.identity());
    }

    @Test void full27TargetsAndOneDisjointPinFitActualSlopeGeometryAndConservativeMemoryBudgets(){
        var source=provider().surfaceSampler();var pin=pin(source);var counting=new Counting(source);
        var data=new DhAdaptiveTerrainData(counting,X,Z);DhAdaptiveTerrainData.Batch previous=null;
        long maxResidentBytes=0,maxDeclared=0;int maxCells=0,maxFaces=0,maxTiles=0,maxPage=0;
        for(var offset:offsets()){
            var center=offset[0]==2&&offset[1]==3
                    ?CubePos.fromBlock(9_674_962,source.surfaceY(9_674_962,-3_112_430)+1,-3_112_430)
                    :center(source,pin,offset[0],offset[1]);var plan=RollingCubePlan.stationary(pin,center);
            assertEquals(27,plan.currentTargets().size());var mask=plan.committedTargets();
            assertTrue(mask.contains(pin));assertEquals(plan.currentTargets().contains(pin)?27:28,mask.size());
            assertTrue(plan.committedOwners().size()<=152);assertTrue(plan.stagedOwners().size()<=213);
            var adjacent=RollingCubePlan.create(pin,center,center.offset(1,1,1));
            assertTrue(adjacent.stagedOwners().size()<=213);assertTrue(adjacent.stagedTargets().size()<=47);
            assertEquals(55,2*RollingCubePlan.WINDOW_TARGETS+RollingCubePlan.PIN_TARGETS);
            counting.reset();var masked=data.clip(mask);assertReads(counting,masked);counting.reset();var fallback=data.clip(List.of(),mask);assertReads(counting,fallback);
            for(var batch:List.of(masked,fallback)){
                assertEquals(0,batch.unknownCells());assertEquals(batch.sampledCells(),batch.knownCells());
                assertTrue(batch.refinedCells()>0);assertTrue(batch.transitionFaces()>0);
                assertTrue(batch.sampledCells()<=12288);assertTrue(batch.sourceFaces()<=32768);assertTrue(batch.faces()<=32768);assertTrue(batch.tiles().size()<=64);
                long declared=4096L+128L*batch.faces();assertTrue(declared<=RESULT_CAP);assertTrue(4L*declared<=CACHE_CAP);
                assertTrue(88L*batch.faces()<=GPU_CAP);maxDeclared=Math.max(maxDeclared,declared);
                maxCells=Math.max(maxCells,batch.sampledCells());maxFaces=Math.max(maxFaces,batch.faces());maxTiles=Math.max(maxTiles,batch.tiles().size());
                for(var entry:batch.tiles().entrySet()){
                    assertEquals(3,entry.getKey().pageY(),"This bounded Earth route is entirely in signed page 3; negative pages remain separate synthetic coverage");
                    assertTrue(entry.getValue().size()<=4096);maxPage=Math.max(maxPage,entry.getValue().size());
                    for(var f:entry.getValue()){assertTrue(f.x()>=0&&f.x()<=256);assertTrue(f.z()>=0&&f.z()<=256);
                        if(f.direction()==DhTerrainGeometry.Direction.UP)assertTrue(f.y()>0&&f.y()<=2048);
                        else assertTrue(f.y()>=0&&f.y()+f.height()<=2048);}
                }
            }
            long changedFaces=0;int changedTiles=0;
            for(var e:masked.tiles().entrySet())if(previous==null||!e.getValue().equals(previous.tiles().get(e.getKey()))){changedFaces+=e.getValue().size();changedTiles++;}
            long resident=88L*((previous==null?0:previous.faces())+changedFaces);
            int residentOwners=(previous==null?0:previous.tiles().size())+changedTiles;
            assertTrue(resident<=GPU_CAP,"Current plus changed candidates retain physical bytes until commit/retirement");assertTrue(residentOwners<=128);
            maxResidentBytes=Math.max(maxResidentBytes,resident);
            System.out.println("EARTH_ROLLING_FAR dx="+offset[0]+" dz="+offset[1]+" center="+center+" targets="+mask.size()
                    +" cells="+masked.sampledCells()+" unknown="+masked.unknownCells()+" faces="+masked.faces()+" fallbackFaces="+fallback.faces()
                    +" tiles="+masked.tiles().size()+" fallbackTiles="+fallback.tiles().size()+" transitions="+masked.transitionFaces()
                    +" preparedUpper="+(4096L+128L*masked.faces())+" currentAndChangedGpuUpper="+resident+" currentAndChangedOwners="+residentOwners);
            previous=masked;
        }
        System.out.println("EARTH_ROLLING_FAR_MAX cells="+maxCells+" faces="+maxFaces+" tiles="+maxTiles+" pageFaces="+maxPage
                +" preparedUpper="+maxDeclared+" currentAndChangedGpuUpper="+maxResidentBytes);
    }

    @Test void realSourceBoundaryStaysUnknownEvenForAirAndStoneAndFarReportsItsMissingCells(){
        var earth=provider();var source=earth.surfaceSampler();
        // The accepted crop starts near x9674282; the appearance slope needs an additional 8m source halo.
        long first=9_674_290;assertDoesNotThrow(()->source.surfaceY(first,Z));
        assertThrows(MissingTerrainException.class,()->source.surfaceY(first-1,Z));
        for(int y:new int[]{-688,553}){
            var failed=earth.load(CubePos.fromBlock(first-1,(long)y*16,Z),new CancellationToken());assertTrue(failed.isDone());
            assertInstanceOf(MissingTerrainException.class,assertThrows(CompletionException.class,failed::join).getCause());
        }
        var partial=new DhAdaptiveTerrainData(source,first,Z).clip(List.of());
        assertTrue(partial.unknownCells()>0);assertTrue(partial.knownCells()>0);assertEquals(partial.sampledCells(),partial.knownCells()+partial.unknownCells());
        System.out.println("EARTH_ROLLING_SOURCE_EDGE firstKnownX="+first+" sampled="+partial.sampledCells()+" known="+partial.knownCells()+" unknown="+partial.unknownCells());
    }

    private static void assertReads(Counting source,DhAdaptiveTerrainData.Batch batch){assertEquals(batch.sampledCells(),source.heights);assertEquals(batch.knownCells(),source.colors);assertTrue(source.heights<=12288);}
    private static final class Counting implements SceneSurfaceSampler{
        final SceneSurfaceSampler source;int heights,colors;Counting(SceneSurfaceSampler source){this.source=source;}
        void reset(){heights=colors=0;}public long surfaceY(long x,long z){heights++;return source.surfaceY(x,z);}
        public int argb(long x,long z,long y){colors++;return source.argb(x,z,y);}public String identity(){return source.identity();}
        public boolean supportsConcurrentReads(){return source.supportsConcurrentReads();}
    }
}
