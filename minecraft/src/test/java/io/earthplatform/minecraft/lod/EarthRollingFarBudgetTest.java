package io.earthplatform.minecraft.lod;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.geography.MissingTerrainException;
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

    private static void assertReads(Counting source,DhAdaptiveTerrainData.Batch batch){assertEquals(batch.sampledCells(),source.heights);assertEquals(batch.knownCells(),source.colors);assertTrue(source.heights<=12288);}
    private static final class Counting implements SceneSurfaceSampler{
        final SceneSurfaceSampler source;int heights,colors;Counting(SceneSurfaceSampler source){this.source=source;}
        void reset(){heights=colors=0;}public long surfaceY(long x,long z){heights++;return source.surfaceY(x,z);}
        public int argb(long x,long z,long y){colors++;return source.argb(x,z,y);}public String identity(){return source.identity();}
        public boolean supportsConcurrentReads(){return source.supportsConcurrentReads();}
    }
}
