package io.earthplatform.minecraft.lod;

import io.earthplatform.core.*;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Zero-copy content/category and owner-side mode checks; actual pooled census is a separate suite. */
class DhEarthReplacementInputTest {
    static final CubePos PIN=CubePos.fromBlock(EarthAlpineDefinition.ANCHOR_X,EarthAlpineDefinition.GROUND_Y,EarthAlpineDefinition.ANCHOR_Z);
    static final WorldIdentity WORLD=new WorldIdentity(EarthAlpineDefinition.SEED,"test-earth","1",EarthAlpineDefinition.DEFINITION_IDENTITY,"palette");
    static EarthReplacementCapture isolated(int material){
        var b=new EarthReplacementCapture.Builder(PIN);
        for(int y=-2;y<18;y++)for(int z=-2;z<18;z++)for(int x=-2;x<18;x++)b.setNext(x==5&&y==5&&z==5?material:0);
        return b.finish();
    }
    static DhReplacementInput.Certificate certificate(String policy,long revision){return new DhReplacementInput.Certificate(WORLD,4,2,revision+1,PIN,revision,String.format("%064x",revision+1),"b".repeat(64),policy);}
    static final class Guard implements DhReplacementInput.AuthorityGuard{
        boolean current=true,historical=true;int calls;
        public boolean isCurrent(DhReplacementInput.Certificate c){calls++;return current;}
        public boolean permitsRetainedPresentation(DhReplacementInput.Certificate c){calls++;return historical;}
    }
    @Test void wrapperSharesExactImmutableByteOwnerAndKeepsAllSixValues(){
        for(int material=0;material<=5;material++){
            var owner=isolated(material);var wrapped=DhVoxelReplacementGeometry.captureEarth(owner);
            assertSame(owner,wrapped.earthCapture());assertEquals(8000,wrapped.declaredBytes());assertEquals(8000,wrapped.cellCount());
            assertEquals(owner.argbAt(PIN.minBlockX()+5,PIN.minBlockY()+5,PIN.minBlockZ()+5),wrapped.argbAt(PIN.minBlockX()+5,PIN.minBlockY()+5,PIN.minBlockZ()+5));
            assertEquals(18,wrapped.ownedVolume().maxXExclusive()-wrapped.ownedVolume().minX());
            assertEquals(20,wrapped.readBounds().sizeX());
            assertThrows(IndexOutOfBoundsException.class,()->wrapped.argbAt(owner.minX()-1,owner.minY(),owner.minZ()));
        }
    }
    @Test void everyEarthUnitFaceCarriesExactStoneOrSnowCategoryAcrossSignedPage(){
        for(int material=1;material<=5;material++){
            var owner=isolated(material);var capture=DhVoxelReplacementGeometry.captureEarth(owner);
            var mesh=DhVoxelReplacementGeometry.build(capture,8192,8192L*64,()->{});
            assertEquals(6,mesh.faceCount());var directions=EnumSet.noneOf(DhTerrainGeometry.Direction.class);
            for(var face:mesh.faces()){
                directions.add(face.direction());assertEquals(material==5?8:2,face.material());
                assertEquals(DhTerrainGeometry.stratifiedFaceArgb(face.direction(),owner.argbAt(PIN.minBlockX()+5,PIN.minBlockY()+5,PIN.minBlockZ()+5)),face.argb());
                var page=DhTerrainGeometry.splitPages(face,capture.ownedVolume().minX(),capture.ownedVolume().minZ()).getFirst();
                assertEquals(3,page.pageY2048());assertEquals(face.material(),page.material());
                assertEquals(face.x(),page.x()+capture.ownedVolume().minX());assertEquals(face.z(),page.z()+capture.ownedVolume().minZ());
                assertEquals(face.y(),page.y()+3L*2048);
            }
            assertEquals(EnumSet.allOf(DhTerrainGeometry.Direction.class),directions);
        }
    }
    @Test void contentFactoryRejectsBothCrossPaletteDirectionsBeforeConsultingAuthority(){
        var earth=DhVoxelReplacementGeometry.captureEarth(isolated(5));
        var legacy=DhVoxelReplacementGeometry.capture(PIN,(x,y,z)->0,()->{});var guard=new Guard();
        assertThrows(IllegalArgumentException.class,()->new DhReplacementInput(certificate(DhReplacementInput.ANALYTIC_MATERIAL_POLICY,0),earth,guard));
        assertThrows(IllegalArgumentException.class,()->new DhReplacementInput(certificate(EarthReplacementAdmission.MATERIAL_POLICY,0),legacy,guard));
        assertEquals(0,guard.calls);
    }
    @Test void pristineRevisionZeroBindsCaptureAndProspectiveChangesCannotAlias(){
        var initial=isolated(5);var changed=initial.withMaterial(PIN.minBlockX()+5,PIN.minBlockY()+5,PIN.minBlockZ()+5,2);
        var c=certificate(EarthReplacementAdmission.MATERIAL_POLICY,0);var guard=new Guard();
        var a=new DhReplacementInput(c,DhVoxelReplacementGeometry.captureEarth(initial),guard);
        var b=new DhReplacementInput(c,DhVoxelReplacementGeometry.captureEarth(changed),guard);
        assertEquals(0,a.certificate().durableRevision());assertNotEquals(a.identity(),b.identity());
        assertSame(initial,a.data().capture().earthCapture());assertSame(changed,b.data().capture().earthCapture());
        assertEquals(8000,a.data().declaredBytes());assertEquals(0,guard.calls);
        assertEquals(5,initial.materialAt(PIN.minBlockX()+5,PIN.minBlockY()+5,PIN.minBlockZ()+5));
    }
    @Test void earthRevocationIsStickyForCurrentAndHistoricalAuthority(){
        var guard=new Guard();var in=new DhReplacementInput(certificate(EarthReplacementAdmission.MATERIAL_POLICY,0),DhVoxelReplacementGeometry.captureEarth(isolated(5)),guard);
        assertTrue(in.isCurrent());assertTrue(in.permitsRetainedPresentation());
        guard.current=false;assertFalse(in.isCurrent());guard.current=true;assertFalse(in.isCurrent());
        guard.historical=false;assertFalse(in.permitsRetainedPresentation());guard.historical=true;assertFalse(in.permitsRetainedPresentation());
    }
    @Test void boundedPrecountAndCancellationNeverReturnTruncatedEarthMesh(){
        var c=DhVoxelReplacementGeometry.captureEarth(isolated(5));
        assertThrows(DhVoxelReplacementGeometry.BudgetExceeded.class,()->DhVoxelReplacementGeometry.build(c,5,320,()->{}));
        var cause=new IllegalStateException("cancel");var calls=new AtomicInteger();
        assertSame(cause,assertThrows(IllegalStateException.class,()->DhVoxelReplacementGeometry.build(c,8192,8192L*64,()->{if(calls.incrementAndGet()==9)throw cause;})));
    }
    @Test void exactEarthViewRequiresAuthorityEvenBeforeTheFirstPublication(){
        try(var h=new Harness(DhLodLayout.EARTH_ALPINE_2048)){
            assertThrows(IllegalStateException.class,()->h.view.prepareCoverage(1,List.of(),EarthAlpineDefinition.ANCHOR_X,EarthAlpineDefinition.ANCHOR_Z));
            assertEquals(-1,h.view.diagnostics().get("highestRequestedCoverageGeneration").getAsLong());assertEquals(0,h.driver.uploads);
        }
    }
    @Test void earthInputCannotEnterLegacyLayout(){
        try(var h=new Harness(DhLodLayout.FIXED_512)){
            var in=new DhReplacementInput(certificate(EarthReplacementAdmission.MATERIAL_POLICY,0),DhVoxelReplacementGeometry.captureEarth(isolated(5)),new Guard());
            assertThrows(IllegalStateException.class,()->h.view.prepareCoverage(1,List.of(),EarthAlpineDefinition.ANCHOR_X,EarthAlpineDefinition.ANCHOR_Z,in));
            assertEquals(0,h.driver.uploads);
        }
    }
    static final class Harness implements AutoCloseable{
        final DhPreparedReplacementTest.Driver driver=new DhPreparedReplacementTest.Driver();final ExecutorService worker=Executors.newSingleThreadExecutor();final DhPreparedLodView view;
        Harness(DhLodLayout layout){
            var sampler=new SceneSurfaceSampler(){public long surfaceY(long x,long z){return 7314;}public int argb(long x,long z,long y){return 0xfff1f5f6;}public String identity(){return EarthAlpineDefinition.DEFINITION_IDENTITY;}public boolean supportsConcurrentReads(){return true;}public boolean supportsFootprintCoverage(){return true;}public boolean supportsStratifiedSides(){return true;}};
            view=new DhPreparedLodView(sampler,WORLD,4,2,EarthAlpineDefinition.ANCHOR_X,EarthAlpineDefinition.ANCHOR_Z,layout,driver,worker);view.initialize();
        }
        public void close(){try{view.close();}finally{worker.shutdownNow();}}
    }
}
