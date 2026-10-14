package io.earthplatform.minecraft.lod;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.scene.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
final class DhArenaTestFixtures {
 static final ArenaTerrainSource SOURCE=ArenaTerrainSource.FIXED;
 static final ArenaDefinition DEF=ArenaDefinition.FIXED;
 static final WorldIdentity WORLD=new WorldIdentity(0,NativeArenaCubeProvider.GENERATOR_ID,NativeArenaCubeProvider.GENERATOR_VERSION,SOURCE.identity(),"test-palette");
 static final long EPOCH=4,RESOURCE=2;
 @FunctionalInterface interface Material {int at(long x,long y,long z);}
 static ArenaCapture capture(){return capture(SOURCE::materialAt);}
 static ArenaCapture capture(Material m){var b=ArenaCapture.builder(DEF);for(long y=DEF.readMinY();y<DEF.readMaxYExclusive();y++)for(long z=DEF.readMinZ();z<DEF.readMaxZExclusive();z++)for(long x=DEF.readMinX();x<DEF.readMaxXExclusive();x++)b.set(x,y,z,m.at(x,y,z));return b.seal();}
 static List<CubePos> mask(CubePos c){var r=new ArrayList<CubePos>();for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)r.add(c.offset(x,y,z));return List.copyOf(r);}
 static long center(int c){return 256L*Math.floorDiv(16L*c+136,256);}
}
