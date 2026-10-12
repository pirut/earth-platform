package io.earthplatform.minecraft.lod;

import io.earthplatform.core.*;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.scene.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Earth source/composition with fake GL, testing the unchanged atomic single-capability view. */
@Timeout(90)
class DhPreparedEarthReplacementTest {
    static final CubePos PIN=DhEarthReplacementAdmission.PIN;
    static final WorldIdentity WORLD=DhEarthReplacementInputTest.WORLD;
    static EarthAlpineDefinition source()throws Exception{return EarthAlpineDefinition.load(Path.of(System.getProperty("earth.alpine.geography",Path.of(System.getProperty("earthplatform.test.projectDirectory","minecraft")).resolve("../geography").normalize().toString())));}
    static int material(EarthAlpineDefinition.Material m){return switch(m){case AIR->0;case STONE->1;case ANDESITE->3;case TUFF->4;case SNOW_BLOCK->5;};}
    static EarthReplacementCapture capture(EarthAlpineDefinition d){var b=new EarthReplacementCapture.Builder(PIN);for(long y=PIN.minBlockY()-2;y<PIN.minBlockY()+18;y++)for(long z=PIN.minBlockZ()-2;z<PIN.minBlockZ()+18;z++)for(long x=PIN.minBlockX()-2;x<PIN.minBlockX()+18;x++)b.setNext(material(d.materialAt(x,y,z)));return b.finish();}
    static List<SceneReadyCube> ready(CubePos center){var result=new ArrayList<SceneReadyCube>();for(int z=-1;z<=1;z++)for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++)result.add(new SceneReadyCube(center.offset(x,y,z),WORLD,4,1,0,2));return List.copyOf(result);}
    static DhReplacementInput input(EarthReplacementCapture c,long revision,DhEarthReplacementInputTest.Guard guard){return new DhReplacementInput(DhEarthReplacementInputTest.certificate(EarthReplacementAdmission.MATERIAL_POLICY,revision),DhVoxelReplacementGeometry.captureEarth(c),guard);}
    static final class Harness implements AutoCloseable{
        final DhPreparedReplacementTest.Driver driver=new DhPreparedReplacementTest.Driver();final ExecutorService worker=Executors.newSingleThreadExecutor();final DhPreparedLodView view;
        Harness(EarthAlpineDefinition d){
            var sampler=new SceneSurfaceSampler(){
                final Map<String,EarthAlpineDefinition.Column> columns=new HashMap<>();
                synchronized EarthAlpineDefinition.Column c(long x,long z){return columns.computeIfAbsent(x+","+z,k->d.column(x,z));}
                public long surfaceY(long x,long z){return c(x,z).surfaceY();}public int argb(long x,long z,long y){return c(x,z).top().argb();}
                public String identity(){return d.identity();}public boolean supportsConcurrentReads(){return true;}public boolean supportsFootprintCoverage(){return true;}public boolean knownFootprint(long x,long z,long xx,long zz){return d.knownFootprint(x,z,xx,zz);}public boolean supportsStratifiedSides(){return true;}
                public ColumnMaterials columnMaterials(long x,long z,long y){var c=c(x,z);return new ColumnMaterials(m(c.top()),m(c.subsurface()),m(c.deep()),3);}SurfaceMaterial m(EarthAlpineDefinition.Material m){return new SurfaceMaterial(m.argb(),m==EarthAlpineDefinition.Material.SNOW_BLOCK?8:2);}
            };
            view=new DhPreparedLodView(sampler,WORLD,4,2,EarthAlpineDefinition.snapCubeCenter(PIN.x()),EarthAlpineDefinition.snapCubeCenter(PIN.z()),DhLodLayout.EARTH_ALPINE_2048,driver,worker);view.initialize();
        }
        DhPreparedLodView.CoverageTicket prepare(long generation,CubePos center,DhReplacementInput in){var t=view.prepareCoverage(generation,ready(center),EarthAlpineDefinition.snapCubeCenter(center.x()),EarthAlpineDefinition.snapCubeCenter(center.z()),in);DhPreparedReplacementTest.until(()->view.isPrepared(t),view::pump);return t;}
        void commit(long generation,CubePos center,DhReplacementInput in){assertTrue(view.commitPrepared(prepare(generation,center,in)));}
        long n(String key){return view.diagnostics().get(key).getAsLong();}
        public void close(){try{view.close();assertEquals(0,n("residentPageTileOwners"));}finally{worker.shutdownNow();}}
    }
    @Test void pristineRetirementRepeatedEditsAndReturnUseOneAtomicFarAuthority()throws Exception{
        var d=source();var original=capture(d);var firstGuard=new DhEarthReplacementInputTest.Guard();var first=input(original,0,firstGuard);
        try(var h=new Harness(d)){
            h.commit(1,PIN,first);assertEquals(0,h.n("replacementVisibleFaces"));assertEquals(0,h.n("replacementDurableRevision"));
            var away=PIN.offset(0,6,0);h.commit(2,away,first);assertTrue(h.n("replacementVisibleFaces")>0);assertTrue(h.n("replacementOwners")>0);
            var old=h.view.retainPresentation();firstGuard.current=false;
            var target=d.anchor().digTarget();var dug=original.withMaterial(target.x(),target.y(),target.z(),0).compact();var secondGuard=new DhEarthReplacementInputTest.Guard();var second=input(dug,1,secondGuard);
            var ticket=h.prepare(3,away,second);assertEquals(0,h.n("replacementDurableRevision"));assertTrue(h.view.isPresentationCurrent(old));
            assertTrue(h.view.commitPrepared(ticket));assertEquals(1,h.n("replacementDurableRevision"));assertFalse(h.view.isPresentationCurrent(old));
            secondGuard.current=false;var refilled=dug.withMaterial(target.x(),target.y(),target.z(),2).compact();var third=input(refilled,2,new DhEarthReplacementInputTest.Guard());h.commit(4,away,third);
            assertEquals(2,h.n("replacementDurableRevision"));assertTrue(h.n("replacementVisibleFaces")>0);assertTrue(h.n("pageTileOwners")<=64);assertTrue(h.n("residentPageTileOwners")<=128);
            h.commit(5,PIN,third);assertEquals(0,h.n("replacementVisibleFaces"));assertTrue(h.view.replacementReady(third.certificate().identity()));
            assertThrows(IllegalStateException.class,()->h.view.prepareCoverage(6,ready(PIN),EarthAlpineDefinition.snapCubeCenter(PIN.x()),EarthAlpineDefinition.snapCubeCenter(PIN.z()),null));
        }
    }
    @Test void forgedCollarCannotDeclineIntoNativeOnlyEvenWithFullReadyCover()throws Exception{
        var d=source();var original=capture(d);var builder=new EarthReplacementCapture.Builder(PIN);
        for(long y=original.minY();y<original.maxYExclusive();y++)for(long z=original.minZ();z<original.maxZExclusive();z++)for(long x=original.minX();x<original.maxXExclusive();x++){
            int m=original.materialAt(x,y,z);builder.setNext(x==original.minX()&&y==original.minY()&&z==original.minZ()?(m==0?1:0):m);
        }
        try(var h=new Harness(d)){
            var invalid=input(builder.finish(),0,new DhEarthReplacementInputTest.Guard());
            var ticket=h.view.prepareCoverage(1,ready(PIN),EarthAlpineDefinition.snapCubeCenter(PIN.x()),EarthAlpineDefinition.snapCubeCenter(PIN.z()),invalid);
            assertThrows(DhReplacementComposition.Declined.class,()->DhPreparedReplacementTest.until(()->h.view.isPrepared(ticket),h.view::pump));
            assertFalse(h.view.isReplacementDeclined(ticket));assertTrue(h.view.diagnostics().get("invalidated").getAsBoolean());assertEquals(0,h.driver.uploads);
        }
    }
}
