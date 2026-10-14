package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;

/** Production geometry census and independent whole-Y/native full-cube arguments for bounded 030. */
@Timeout(300)
class DhEarthAlpineGeometryCapsTest {

    /** Independent voxel occupancy oracle; every nonair source material is a full opaque cube. */
    private static int bruteNativeOwner(DhEarthAlpineGeometryCensus.CachedSampler sampler,CubePos owner) {
        int faces=0;
        int[][] directions={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
        for(long y=owner.minBlockY();y<owner.minBlockY()+16;y++)
            for(long z=owner.minBlockZ();z<owner.minBlockZ()+16;z++)
                for(long x=owner.minBlockX();x<owner.minBlockX()+16;x++) {
                    if(y>sampler.surfaceY(x,z))continue;
                    for(var d:directions)if(y+d[1]>sampler.surfaceY(x+d[0],z+d[2]))faces++;
                }
        return faces;
    }

    @Test void everyLatticePhaseMaterialBandAndReadinessSubsetCannotIncreasePageOrFaceCounts() {
        int cases=0,shortened=0;
        for(Direction direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST))
            for(int base:new int[]{-2080,-32,0,128,2032,4080,6128,8176})
                for(int phase=0;phase<16;phase++)for(int height=1;height<=16;height++)
                    for(int coordinate:new int[]{-17,-16,-1,0,15,16})for(int material:new int[]{2,8}) {
                        int x=coordinate,z=coordinate,y=base+phase;
                        var face=new Face(direction,x+(direction==Direction.EAST?1:0),y,
                                z+(direction==Direction.SOUTH?1:0),1,height,0xffb3bdc7,material);
                        var first=CubePos.fromBlock(x,y,z);var last=CubePos.fromBlock(x,y+height-1,z);
                        var relevant=first.equals(last)?List.of(first):List.of(first,last);
                        long tx=Math.floorDiv(x,32)*32,tz=Math.floorDiv(z,32)*32;
                        int originalPages=splitPages(face,tx,tz).size();
                        for(int mask=0;mask<(1<<relevant.size());mask++) {
                            var ready=new ArrayList<CubePos>();
                            for(int i=0;i<relevant.size();i++)if((mask&(1<<i))!=0)ready.add(relevant.get(i));
                            var actual=clip(face,ready,16);assertTrue(actual.size()<=1);
                            int units=0;for(int dy=0;dy<height;dy++)if(!ready.contains(CubePos.fromBlock(x,y+dy,z)))units++;
                            assertEquals(units,actual.stream().mapToInt(Face::height).sum());
                            for(var fragment:actual){
                                assertEquals(face.direction(),fragment.direction());assertEquals(face.x(),fragment.x());assertEquals(face.z(),fragment.z());
                                assertEquals(1,fragment.width());assertEquals(face.argb(),fragment.argb());assertEquals(face.material(),fragment.material());
                                assertTrue(splitPages(fragment,tx,tz).size()<=originalPages);
                                if(!fragment.equals(face))shortened++;
                            }
                            cases++;
                        }
                    }
        assertEquals(288768,cases);assertTrue(shortened>0);
        System.out.println("ALPINE_ALL_Y_CLIP cases="+cases+" maxBandHeight=16 maxFragments=1 allSignedPageBoundaries=true arbitraryReadySubsets=true");
    }

    @Test void nativeByteCapAndSignedPageOwnershipRemainExact() {
        assertEquals(1_048_528L,5041L*4*52);
        assertTrue(5041L*4*52<=1_048_576);assertTrue(5042L*4*52>1_048_576);
        for(long y:new long[]{-8192,-6144,-4096,-2048,0,2048,4096,6144,8192}) {
            for(int material:new int[]{2,8}) {
                var top=new Face(Direction.UP,0,y,0,1,1,0xffaaaaaa,material);
                var page=splitPages(top,0,0).getFirst();
                assertEquals(Math.floorDiv(y-1,2048),page.pageY2048());assertEquals(2048,page.y());
                var wall=new Face(Direction.NORTH,0,y-1,0,1,2,0xffaaaaaa,material);
                var pages=splitPages(wall,0,0);assertEquals(2,pages.size());
                assertEquals(Math.floorDiv(y-1,2048),pages.getFirst().pageY2048());
                assertEquals(Math.floorDiv(y,2048),pages.getLast().pageY2048());
                assertEquals(material,pages.getFirst().material());assertEquals(material,pages.getLast().material());
            }
        }
    }
}
