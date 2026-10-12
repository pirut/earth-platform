package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent source occupancy and solid-side ownership oracles; no author census helpers. */
@Timeout(180)
class DhEarthReplacementGeometryIndependentTest {
    private static final CubePos PIN = new CubePos(604879, 457, -194643);
    private static final long X = PIN.minBlockX(), Y = PIN.minBlockY(), Z = PIN.minBlockZ();
    private static final Volume OWNED = new Volume(X-1,Y-1,Z-1,X+17,Y+17,Z+17);
    private static final Volume READ = new Volume(X-2,Y-2,Z-2,X+18,Y+18,Z+18);
    private record Xz(long x,long z) {}
    private record Cell(long x,long y,long z) {}
    private record Unit(Direction direction,long x,long y,long z,int material,int argb) {}
    private record Query(long x,long z,long endX,long endZ) {}
    private static final class SourceHolder {
        static final EarthAlpineDefinition VALUE=load();
        private static EarthAlpineDefinition load(){
            try{return EarthAlpineDefinition.load(Path.of(System.getProperty("earth.alpine.geography",Path.of(System.getProperty("earthplatform.test.projectDirectory","minecraft")).resolve("../geography").normalize().toString())));}
            catch(Exception failure){throw new ExceptionInInitializerError(failure);}
        }
    }
    private static final class FrozenSampler implements SceneSurfaceSampler {
        private final Map<Xz,EarthAlpineDefinition.Column> columns=new HashMap<>();
        EarthAlpineDefinition.Column column(long x,long z){return columns.computeIfAbsent(new Xz(x,z),p->SourceHolder.VALUE.column(p.x(),p.z()));}
        public long surfaceY(long x,long z){return column(x,z).surfaceY();}
        public int argb(long x,long z,long y){return column(x,z).top().argb();}
        public String identity(){return SourceHolder.VALUE.identity();}
        public boolean supportsFootprintCoverage(){return true;}
        public boolean supportsStratifiedSides(){return true;}
        public boolean knownFootprint(long x,long z,long endX,long endZ){return SourceHolder.VALUE.knownFootprint(x,z,endX,endZ);}
        public ColumnMaterials columnMaterials(long x,long z,long y){var c=column(x,z);return new ColumnMaterials(surface(c.top()),surface(c.subsurface()),surface(c.deep()),3);}
    }
    private static SceneSurfaceSampler.SurfaceMaterial surface(EarthAlpineDefinition.Material m){return new SceneSurfaceSampler.SurfaceMaterial(m.argb(),m==EarthAlpineDefinition.Material.SNOW_BLOCK?8:2);}

    @Test void readFootprintRefinesAllFourHundredColumnsIncludingTheUnownedOuterHalo(){
        var queries=new ArrayList<Query>();
        var flat=new SceneSurfaceSampler(){
            public long surfaceY(long x,long z){return Y+2;}
            public int argb(long x,long z,long y){return 0xff8a8a8a;}
            public String identity(){return "independent-exact-read20-flat";}
            public boolean supportsFootprintCoverage(){return true;}
            public boolean knownFootprint(long x,long z,long ex,long ez){queries.add(new Query(x,z,ex,ez));return true;}
        };
        var batch=new DhAdaptiveTerrainData(flat,EarthAlpineDefinition.snapCubeCenter(PIN.x()),EarthAlpineDefinition.snapCubeCenter(PIN.z()),DhLodLayout.EARTH_ALPINE_2048)
                .clipWithRefinementVolumes(List.of(),List.of(),List.of(OWNED),List.of(READ),()->{});
        int exact=0;
        for(long z=Z-2;z<Z+18;z++)for(long x=X-2;x<X+18;x++){
            final long px=x,pz=z;
            var containing=queries.stream().filter(q->q.x()<=px&&px<q.endX()&&q.z()<=pz&&pz<q.endZ()).toList();
            assertEquals(List.of(new Query(x,z,x+1,z+1)),containing,"Missing, duplicate, or coarse read20 column");exact++;
        }
        var visible=new HashSet<Xz>();
        for(var entry:batch.tiles().entrySet())for(var packed:entry.getValue()){
            var f=global(entry.getKey(),packed);
            if(f.x()>=X-2&&f.x()<X+18&&f.z()>=Z-2&&f.z()<Z+18){
                assertEquals(Direction.UP,f.direction());assertEquals(1,f.width());assertEquals(1,f.height());
                assertFalse(OWNED.contains(f.x(),f.y()-1,f.z()));assertTrue(visible.add(new Xz(f.x(),f.z())));
            }
        }
        assertEquals(400,exact);assertEquals(20*20-18*18,visible.size());
        for(long z=Z-2;z<Z+18;z++)for(long x=X-2;x<X+18;x++)
            assertEquals(!OWNED.contains(x,Y+2,z),visible.contains(new Xz(x,z)));
    }

    @Test void baseAndReplacementUnionMatchesIndependentUnitOccupancyAcrossEveryOwnedBoundary(){
        var sampler=new FrozenSampler();var capture=capture(sampler);var wrapped=DhVoxelReplacementGeometry.captureEarth(capture);
        var base=new DhAdaptiveTerrainData(sampler,EarthAlpineDefinition.snapCubeCenter(PIN.x()),EarthAlpineDefinition.snapCubeCenter(PIN.z()),DhLodLayout.EARTH_ALPINE_2048)
                .clipWithRefinementVolumes(List.of(),List.of(),List.of(OWNED),List.of(READ),()->{});
        var baseUnits=new HashSet<Unit>();
        for(var entry:base.tiles().entrySet())for(var p:entry.getValue())addUnitsInProbe(baseUnits,global(entry.getKey(),p));
        var baseline=new HashSet<>(baseUnits);
        var mesh=DhVoxelReplacementGeometry.build(wrapped,8192,8192L*64,()->{});
        for(var face:mesh.faces())addUnitsInProbe(baseline,face);
        assertEquals(oracle(sampler,Map.of()),baseline);
        assertTrue(baseUnits.stream().anyMatch(f->!OWNED.contains(solid(f).x(),solid(f).y(),solid(f).z())),"Probe must include exact unowned halo surfaces beside its replacement");

        // An exact cube-edge excavation exposes a neighbor's solid collar face.
        Cell dig=null;
        search:for(long z=Z;z<Z+16;z++)for(long y=Y;y<Y+16;y++)
            if(material(sampler,X,y,z,Map.of())!=0&&material(sampler,X-1,y,z,Map.of())!=0){dig=new Cell(X,y,z);break search;}
        assertNotNull(dig,"Frozen source must supply a shared solid cube boundary");
        var edited=capture.withMaterial(dig.x(),dig.y(),dig.z(),EarthReplacementCapture.AIR);
        var changed=new HashSet<>(baseUnits);
        var changedMesh=DhVoxelReplacementGeometry.build(DhVoxelReplacementGeometry.captureEarth(edited),8192,8192L*64,()->{});
        for(var face:changedMesh.faces())addUnitsInProbe(changed,face);
        var override=Map.of(dig,EarthReplacementCapture.AIR);
        assertEquals(oracle(sampler,override),changed);
        int exposedMaterial=material(sampler,dig.x()-1,dig.y(),dig.z(),override);
        var exposed=new Unit(Direction.EAST,dig.x(),dig.y(),dig.z(),dh(exposedMaterial),color(Direction.EAST,exposedMaterial));
        assertTrue(changed.contains(exposed));assertFalse(baseline.contains(exposed));
        assertEquals(new CubePos(PIN.x()-1,PIN.y(),PIN.z()),CubePos.fromBlock(solid(exposed).x(),solid(exposed).y(),solid(exposed).z()));
    }

    @Test void everyDirectionalOwnedBoundaryUsesTheSolidSideAtNegativeZAndHighY(){
        int cases=0;
        long[] xs={X-2,X-1,X,X+15,X+16,X+17};
        long[] ys={Y-2,Y-1,Y,Y+15,Y+16,Y+17};
        long[] zs={Z-2,Z-1,Z,Z+15,Z+16,Z+17};
        for(long x:xs)for(long y:ys)for(long z:zs)for(Direction d:Direction.values()){
            var f=face(d,x,y,z,8);boolean expected=OWNED.contains(x,y,z);
            assertEquals(expected?List.of():List.of(f),clip(f,List.of(),List.of(OWNED),8));
            // Same X/Z at a different elevation must not own this face.
            var wrongY=CubePos.fromBlock(x,y+48,z);
            assertEquals(List.of(f),clip(f,List.of(wrongY),8));
            var correct=CubePos.fromBlock(x,y,z);
            assertEquals(List.of(),clip(f,List.of(correct),8));
            var pages=splitPages(f,X-32,Z-32);assertEquals(1,pages.size());assertEquals(3,pages.getFirst().pageY2048());
            assertEquals(8,pages.getFirst().material());cases++;
        }
        assertEquals(1296,cases);
    }

    @Test void recoloringAnImmutableCollarCellCannotPassJustBecauseAllFaceCountsAreUnchanged(){
        var sampler=new FrozenSampler();var original=capture(sampler);byte[] bytes=raw(original);
        int altered=0;
        for(long y=Y-1;y<Y+17&&altered==0;y++)for(long z=Z-1;z<Z+17&&altered==0;z++){
            long x=X-1;int old=original.materialAt(x,y,z);if(old==0)continue;
            int index=Math.toIntExact(((y-original.minY())*20+z-original.minZ())*20+x-original.minX());
            bytes[index]=(byte)(old==EarthReplacementCapture.SNOW_BLOCK?EarthReplacementCapture.STONE:EarthReplacementCapture.SNOW_BLOCK);altered++;
        }
        assertEquals(1,altered);var forged=new EarthReplacementCapture(PIN,bytes);
        assertEquals(io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission.faces(original),io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission.faces(forged));
        assertEquals(DhEarthReplacementAdmission.nativePinFaces(original,()->{}),DhEarthReplacementAdmission.nativePinFaces(forged,()->{}));
        assertNotEquals(DhEarthReplacementAdmission.outsidePinIdentity(original),DhEarthReplacementAdmission.outsidePinIdentity(forged));
        var result=new DhEarthReplacementAdmission().assess(forged);
        assertFalse(result.admitted());assertTrue(result.reason().contains("collar/halo"));
        assertEquals(SourceHolder.VALUE.identity(),result.sourceIdentity());
    }

    private static EarthReplacementCapture capture(FrozenSampler s){
        try(var b=new EarthReplacementCapture.Builder(PIN)){
            for(long y=Y-2;y<Y+18;y++)for(long z=Z-2;z<Z+18;z++)for(long x=X-2;x<X+18;x++)b.setNext(material(s,x,y,z,Map.of()));
            return b.finish();
        }
    }
    private static int material(FrozenSampler s,long x,long y,long z,Map<Cell,Integer> overrides){
        var override=overrides.get(new Cell(x,y,z));if(override!=null)return override;
        return switch(s.column(x,z).materialAt(y)){case AIR->0;case STONE->1;case ANDESITE->3;case TUFF->4;case SNOW_BLOCK->5;};
    }
    private static int dh(int m){return m==5?8:2;}
    private static int color(Direction d,int m){
        int raw=switch(m){case 1->0xff8a8a8a;case 2->0xffdedede;case 3->0xff999a99;case 4->0xff777a69;case 5->0xfff1f5f6;default->throw new AssertionError("Air has no face");};
        float shade=switch(d){case EAST,WEST->0.72f;case NORTH,SOUTH->0.85f;case UP,DOWN->1f;};
        return 0xff000000|Math.round(((raw>>>16)&255)*shade)<<16|Math.round(((raw>>>8)&255)*shade)<<8|Math.round((raw&255)*shade);
    }
    private static boolean ownedHorizontal(long x,long z){return x>=X-1&&x<X+17&&z>=Z-1&&z<Z+17;}
    private static Set<Unit> oracle(FrozenSampler s,Map<Cell,Integer> overrides){
        var expected=new HashSet<Unit>();
        for(long y=Y-4;y<Y+20;y++)for(long z=Z-2;z<Z+18;z++)for(long x=X-2;x<X+18;x++){
            int m=material(s,x,y,z,overrides);if(m==0)continue;
            for(Direction d:Direction.values()){
                if(!ownedHorizontal(x,z)&&d!=Direction.UP)continue;
                int dx=d==Direction.WEST?-1:d==Direction.EAST?1:0;
                int dy=d==Direction.DOWN?-1:d==Direction.UP?1:0;
                int dz=d==Direction.NORTH?-1:d==Direction.SOUTH?1:0;
                if(material(s,x+dx,y+dy,z+dz,overrides)==0){var f=face(d,x,y,z,dh(m));assertTrue(expected.add(new Unit(d,f.x(),f.y(),f.z(),f.material(),color(d,m))));}
            }
        }return expected;
    }
    private static Face face(Direction d,long x,long y,long z,int material){return new Face(d,x+(d==Direction.EAST?1:0),y+(d==Direction.UP?1:0),z+(d==Direction.SOUTH?1:0),1,1,0xfff1f5f6,material);}
    private static Cell solid(Unit u){return new Cell(u.x()-(u.direction()==Direction.EAST?1:0),u.y()-(u.direction()==Direction.UP?1:0),u.z()-(u.direction()==Direction.SOUTH?1:0));}
    private static boolean probe(Cell c){return c.x()>=X-2&&c.x()<X+18&&c.z()>=Z-2&&c.z()<Z+18&&c.y()>=Y-4&&c.y()<Y+20;}
    private static void addUnitsInProbe(Set<Unit> result,Face f){
        // Intersect in independent solid coordinates before expansion, avoiding far-area enumeration.
        long sx=f.x()-(f.direction()==Direction.EAST?1:0),sy=f.y()-(f.direction()==Direction.UP?1:0),sz=f.z()-(f.direction()==Direction.SOUTH?1:0);
        boolean horizontal=f.direction()==Direction.UP||f.direction()==Direction.DOWN;
        boolean zWidth=f.direction()==Direction.WEST||f.direction()==Direction.EAST;
        if(horizontal&&(sy<Y-4||sy>=Y+20)||!horizontal&&((zWidth&&(sx<X-2||sx>=X+18))||(!zWidth&&(sz<Z-2||sz>=Z+18))))return;
        long u0=Math.max(zWidth?Z-2:X-2,zWidth?sz:sx),u1=Math.min(zWidth?Z+18:X+18,(zWidth?sz:sx)+f.width());
        long v0=Math.max(horizontal?Z-2:Y-4,horizontal?sz:sy),v1=Math.min(horizontal?Z+18:Y+20,(horizontal?sz:sy)+f.height());
        for(long u=u0;u<u1;u++)for(long v=v0;v<v1;v++){
            long x=zWidth?sx:u,y=horizontal?sy:v,z=horizontal?v:zWidth?u:sz;
            if(!ownedHorizontal(x,z)&&f.direction()!=Direction.UP)continue;
            var unit=face(f.direction(),x,y,z,f.material());var key=new Unit(unit.direction(),unit.x(),unit.y(),unit.z(),unit.material(),f.argb());
            assertTrue(probe(solid(key)));assertTrue(result.add(key),"Duplicate base/replacement surface: "+key);
        }
    }
    private static Face global(DhGeographicLodData.TileKey k,PageFace p){return new Face(p.direction(),k.originX()+p.x(),(long)k.pageY()*2048+p.y(),k.originZ()+p.z(),p.width(),p.height(),p.argb(),p.material());}
    private static byte[] raw(EarthReplacementCapture c){byte[] b=new byte[8000];int i=0;for(long y=c.minY();y<c.maxYExclusive();y++)for(long z=c.minZ();z<c.maxZExclusive();z++)for(long x=c.minX();x<c.maxXExclusive();x++)b[i++]=(byte)c.materialAt(x,y,z);return b;}
}
