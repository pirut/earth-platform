package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent unit-voxel material oracle. These tests do not claim renderer or GUI acceptance. */
class DhAlpineIndependentMaterialReviewTest {
    private static final int TOP=0xfff1f5f6,SUB=0xff999a99,DEEP=0xff777a69;
    private static final SceneSurfaceSampler.SurfaceMaterial SNOW=new SceneSurfaceSampler.SurfaceMaterial(TOP,8);
    private static final SceneSurfaceSampler.SurfaceMaterial STONE=new SceneSurfaceSampler.SurfaceMaterial(SUB,2);
    private static final SceneSurfaceSampler.SurfaceMaterial ROCK=new SceneSurfaceSampler.SurfaceMaterial(DEEP,2);
    private static SceneSurfaceSampler cliff(DhTerrainGeometry.Direction direction,long high,long low,
                                            SceneSurfaceSampler.ColumnMaterials materials) {
        return new SceneSurfaceSampler() {
            private boolean high(long x,long z){return switch(direction){case EAST->x<0;case WEST->x>=0;case SOUTH->z<0;case NORTH->z>=0;default->throw new AssertionError();};}
            public long surfaceY(long x,long z){return high(x,z)?high:low;}
            public int argb(long x,long z,long y){assertEquals(surfaceY(x,z),y);return materials.top().argb();}
            public String identity(){return "independent-material-cliff";}
            public boolean supportsStratifiedSides(){return true;}
            public ColumnMaterials columnMaterials(long x,long z,long y){assertEquals(surfaceY(x,z),y);return materials;}
        };
    }
    private static List<DhTerrainGeometry.PageFace> seam(DhAdaptiveTerrainData.Batch batch,DhTerrainGeometry.Direction direction) {
        var result=new ArrayList<DhTerrainGeometry.PageFace>();
        boolean xAxis=direction==DhTerrainGeometry.Direction.EAST||direction==DhTerrainGeometry.Direction.WEST;
        batch.tiles().forEach((key,faces)->faces.forEach(f->{
            if(f.direction()!=direction)return;
            long x=key.originX()+f.x(),z=key.originZ()+f.z(),plane=xAxis?x:z,start=xAxis?z:x;
            if(plane==0&&start<=0&&0<start+f.width())result.add(f);
        }));
        return result;
    }
    private static int shaded(int color,float factor){return 0xff000000|Math.round((color>>>16&255)*factor)<<16|Math.round((color>>>8&255)*factor)<<8|Math.round((color&255)*factor);}
    @Test void allFourOutwardNormalsMatchNativeBandsForShortAndLongSignedPageStrips() {
        int checked=0;
        for(var direction:List.of(DhTerrainGeometry.Direction.NORTH,DhTerrainGeometry.Direction.SOUTH,DhTerrainGeometry.Direction.WEST,DhTerrainGeometry.Direction.EAST))
            for(long high:new long[]{-2047,2049,7314})for(int gap:new int[]{1,3,4,5,23}) {
                var materials=new SceneSurfaceSampler.ColumnMaterials(SNOW,STONE,ROCK,3);
                var batch=new DhAdaptiveTerrainData(cliff(direction,high,high-gap,materials),0,0).clip(List.of());
                var found=new HashMap<Long,SceneSurfaceSampler.SurfaceMaterial>();
                float factor=direction==DhTerrainGeometry.Direction.EAST||direction==DhTerrainGeometry.Direction.WEST?.72f:.85f;
                for(var face:seam(batch,direction)) {
                    long base=(long)face.pageY2048()*2048+face.y();
                    for(long y=base;y<base+face.height();y++) {
                        var expected=y==high?SNOW:y>=high-3?STONE:ROCK;
                        assertEquals(expected.dhMaterial(),face.material(),direction+" y="+y);
                        assertEquals(shaded(expected.argb(),factor),face.argb());
                        assertNull(found.put(y,expected),"No duplicate exposed side unit");
                    }
                }
                assertEquals(gap,found.size());for(long y=high-gap+1;y<=high;y++)assertTrue(found.containsKey(y));
                assertTrue(batch.tiles().values().stream().flatMap(List::stream).filter(f->f.direction()==DhTerrainGeometry.Direction.UP).allMatch(f->f.material()==8&&f.argb()==TOP));
                checked++;
            }
        assertEquals(60,checked);
    }
    @Test void equalColorsWithDifferentCategoriesCannotBeCompactedAcrossBands() {
        int sameColor=0xff929292;
        var stone=new SceneSurfaceSampler.SurfaceMaterial(sameColor,2);
        var snow=new SceneSurfaceSampler.SurfaceMaterial(sameColor,8);
        for(var bands:List.of(new SceneSurfaceSampler.ColumnMaterials(stone,stone,stone,3),
                new SceneSurfaceSampler.ColumnMaterials(snow,stone,stone,3),
                new SceneSurfaceSampler.ColumnMaterials(stone,snow,stone,3))) {
            var batch=new DhAdaptiveTerrainData(cliff(DhTerrainGeometry.Direction.EAST,100,70,bands),0,0).clip(List.of());
            var pieces=seam(batch,DhTerrainGeometry.Direction.EAST);
            assertEquals(bands.top().equals(bands.subsurface())?1:bands.subsurface().equals(bands.deep())?2:3,pieces.size());
            assertEquals(30,pieces.stream().mapToInt(DhTerrainGeometry.PageFace::height).sum());
            for(var face:pieces)for(int offset=0;offset<face.height();offset++) {
                long y=(long)face.pageY2048()*2048+face.y()+offset;
                var expected=y==100?bands.top():y>=97?bands.subsurface():bands.deep();
                assertEquals(expected.dhMaterial(),face.material());assertEquals(shaded(sameColor,.72f),face.argb());
            }
        }
    }
    @Test void clippedAndPagedBandsKeepCategoryEvenWhenColorAndNeighborsMatch() {
        for(var direction:List.of(DhTerrainGeometry.Direction.NORTH,DhTerrainGeometry.Direction.SOUTH,DhTerrainGeometry.Direction.WEST,DhTerrainGeometry.Direction.EAST))
            for(int material:new int[]{2,8})for(int base:new int[]{-2050,2046}) {
                int x=direction==DhTerrainGeometry.Direction.EAST?1:0,z=direction==DhTerrainGeometry.Direction.SOUTH?1:0;
                var face=new DhTerrainGeometry.Face(direction,x,base,z,1,6,TOP,material);
                var ready=List.of(CubePos.fromBlock(0,base,0));
                var clipped=DhTerrainGeometry.clip(face,ready,64);
                assertTrue(clipped.size()<=1);
                for(var fragment:clipped)for(var page:DhTerrainGeometry.splitPages(fragment,0,0)) {
                    assertEquals(material,page.material());assertEquals(TOP,page.argb());
                    assertEquals(direction,page.direction());assertEquals(1,page.width());
                }
            }
    }
    @Test void missingNeighborCoverageDoesNotInventACliffOrMaterial() {
        var base=cliff(DhTerrainGeometry.Direction.EAST,100,20,new SceneSurfaceSampler.ColumnMaterials(SNOW,STONE,ROCK,3));
        var source=new SceneSurfaceSampler() {
            public long surfaceY(long x,long z){assertTrue(x<0,"Unsupported cells must not sample");return base.surfaceY(x,z);}
            public int argb(long x,long z,long y){return base.argb(x,z,y);}
            public String identity(){return base.identity();}
            public boolean supportsStratifiedSides(){return true;}
            public ColumnMaterials columnMaterials(long x,long z,long y){return base.columnMaterials(x,z,y);}
            public boolean supportsFootprintCoverage(){return true;}
            public boolean knownFootprint(long x,long z,long endX,long endZ){return endX<=0;}
        };
        var batch=new DhAdaptiveTerrainData(source,0,0).clip(List.of());
        assertEquals(2048,batch.knownCells());assertEquals(2048,batch.unknownCells());
        assertEquals(batch.unknownCells(),batch.footprintRejectedCells());
        assertTrue(batch.tiles().values().stream().flatMap(List::stream).allMatch(f->f.direction()==DhTerrainGeometry.Direction.UP&&f.material()==8));
    }
}
