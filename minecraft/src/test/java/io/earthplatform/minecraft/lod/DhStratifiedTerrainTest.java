package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent unit-height material oracle for the optional opaque Earth column contract. */
class DhStratifiedTerrainTest {
    private static final int SNOW=0xfff1f5f6,ROCK=0xff999a99,DEEP=0xff8a8a8a;
    private static SceneSurfaceSampler source(long high,long low){return new SceneSurfaceSampler(){
        public long surfaceY(long x,long z){return x<0?high:low;}
        public int argb(long x,long z,long y){assertEquals(surfaceY(x,z),y);return SNOW;}
        public String identity(){return "stratified-test-v1";}
        public boolean supportsStratifiedSides(){return true;}
        public ColumnMaterials columnMaterials(long x,long z,long y){assertEquals(surfaceY(x,z),y);return new ColumnMaterials(new SurfaceMaterial(SNOW,8),new SurfaceMaterial(ROCK,2),new SurfaceMaterial(DEEP,2),3);}
    };}
    private static int shade(int argb){return 0xff000000|Math.round((argb>>>16&255)*.72f)<<16|Math.round((argb>>>8&255)*.72f)<<8|Math.round((argb&255)*.72f);}
    @Test void wallPaletteMatchesEveryUnitHeightAcrossPositiveAndNegativePages(){
        for(long high:new long[]{2050,-2046,7314}){
            long low=high-23;var batch=new DhAdaptiveTerrainData(source(high,low),0,0).clip(List.of());
            var seen=new HashMap<Long,Integer>();
            batch.tiles().forEach((key,faces)->faces.forEach(f->{
                if(f.direction()!=DhTerrainGeometry.Direction.EAST||key.originX()+f.x()!=0||!(key.originZ()+f.z()<=0&&0<key.originZ()+f.z()+f.width()))return;
                for(long y=(long)f.pageY2048()*2048+f.y();y<(long)f.pageY2048()*2048+f.y()+f.height();y++){
                    assertNull(seen.put(y,f.argb()),"duplicate unit wall");
                    assertEquals(y==high?8:2,f.material());
                    assertEquals(shade(y==high?SNOW:y>=high-3?ROCK:DEEP),f.argb());
                }
            }));
            assertEquals(23,seen.size());for(long y=low+1;y<=high;y++)assertTrue(seen.containsKey(y));
            assertTrue(batch.tiles().values().stream().flatMap(List::stream).filter(f->f.direction()==DhTerrainGeometry.Direction.UP).allMatch(f->f.material()==8&&f.argb()==SNOW));
        }
    }
    @Test void clippingAndSignedPagingPreserveExplicitMaterialAndLegacyConstructors(){
        var face=new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.EAST,0,-2060,0,16,40,SNOW,8);
        var pieces=DhTerrainGeometry.clip(face,List.of(new CubePos(-1,-129,0)),List.of(),64);
        assertFalse(pieces.isEmpty());assertTrue(pieces.stream().allMatch(f->f.material()==8));
        var pages=pieces.stream().flatMap(f->DhTerrainGeometry.splitPages(f,-16,0).stream()).toList();
        assertTrue(pages.stream().allMatch(f->f.material()==8));
        assertFalse(new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,0,1,0,1,1,SNOW).toString().contains("material="));
        assertFalse(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,0,0,1,0,1,1,SNOW).toString().contains("material="));
        assertTrue(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,0,0,1,0,1,1,SNOW,8).toString().endsWith(", material=8]"));
        assertEquals(0,new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,0,1,0,1,1,SNOW).material());
        assertEquals(0,new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,0,0,1,0,1,1,SNOW).material());
        assertNotEquals(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,0,0,1,0,1,1,SNOW,2),new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,0,0,1,0,1,1,SNOW,8));
    }
    @Test void invalidMaterialsTransparencyAndDepthRefuse(){
        for(int material:new int[]{-1,1,3,14,15,255}){
            assertThrows(IllegalArgumentException.class,()->new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,0,1,0,1,1,SNOW,material));
            assertThrows(IllegalArgumentException.class,()->new SceneSurfaceSampler.SurfaceMaterial(SNOW,material));
        }
        assertThrows(IllegalArgumentException.class,()->new SceneSurfaceSampler.SurfaceMaterial(0x80ffffff,8));
        var stone=new SceneSurfaceSampler.SurfaceMaterial(DEEP,2);
        assertThrows(IllegalArgumentException.class,()->new SceneSurfaceSampler.ColumnMaterials(stone,stone,stone,4));
    }
    @Test void topColourAndCapabilityMutationFailClosed(){
        var good=source(64,60);
        var bad=new SceneSurfaceSampler(){
            public long surfaceY(long x,long z){return good.surfaceY(x,z);}public int argb(long x,long z,long y){return SNOW;}
            public String identity(){return good.identity();}public boolean supportsStratifiedSides(){return true;}
            public ColumnMaterials columnMaterials(long x,long z,long y){var stone=new SurfaceMaterial(DEEP,2);return new ColumnMaterials(stone,stone,stone,3);}
        };
        assertThrows(IllegalStateException.class,()->new DhAdaptiveTerrainData(bad,0,0).clip(List.of()));
        var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        var mutable=new SceneSurfaceSampler(){
            public long surfaceY(long x,long z){active.set(false);return 64;}public int argb(long x,long z,long y){return SNOW;}
            public String identity(){return "fixed";}public boolean supportsStratifiedSides(){return active.get();}
            public ColumnMaterials columnMaterials(long x,long z,long y){return good.columnMaterials(x,z,good.surfaceY(x,z));}
        };
        assertThrows(IllegalStateException.class,()->new DhAdaptiveTerrainData(mutable,0,0).clip(List.of()));
    }
}
