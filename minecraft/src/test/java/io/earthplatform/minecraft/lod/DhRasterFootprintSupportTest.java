package io.earthplatform.minecraft.lod;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.RasterTile;
import io.earthplatform.geography.TileSpec;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DhRasterFootprintSupportTest {
    private static RasterTile tile(int width,int height,float[] values){
        return new RasterTile(new TileSpec("coverage","unused.egp","0".repeat(64),0,10,.125,.125,width,height),values);
    }
    private static float[] values(int n){float[] v=new float[n];Arrays.fill(v,100);return v;}
    private static GeoPoint point(double x,double y){return new GeoPoint(10-y*.125,x*.125);}
    @Test void completeFiniteSupportIncludesContinuousInteriorAndExactEdges(){
        var tile=tile(5,5,values(25));
        assertTrue(tile.hasFiniteRectangleSupport(point(0,0),point(4,4)));
        assertTrue(tile.hasFiniteRectangleSupport(point(.1,.2),point(3.7,3.8)));
        assertFalse(tile.hasFiniteRectangleSupport(point(-.001,0),point(4,4)));
        assertFalse(tile.hasFiniteRectangleSupport(point(0,0),point(4.001,4)));
        assertFalse(tile.hasFiniteRectangleSupport(point(0,-.001),point(4,4)));
        assertFalse(tile.hasFiniteRectangleSupport(point(0,0),point(4,4.001)));
    }
    @Test void finiteCornersCannotHideInteriorNoDataOrInfinity(){
        for(float missing:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
            var heights=values(25);heights[2*5+2]=missing;var tile=tile(5,5,heights);
            for(var corner:List.of(point(0,0),point(4,0),point(0,4),point(4,4)))assertTrue(Double.isFinite(tile.sample(corner)));
            assertFalse(tile.hasFiniteRectangleSupport(point(0,0),point(4,4)));
            assertTrue(tile.hasFiniteRectangleSupport(point(0,0),point(.5,.5)));
        }
    }
    @Test void interpolationStencilIncludesContributingOutsideCornerNode(){
        var heights=values(25);heights[2*5+2]=Float.NaN;var tile=tile(5,5,heights);
        assertFalse(tile.hasFiniteRectangleSupport(point(.2,.2),point(1.2,1.2)));
    }
    @Test void reversedOrOversizedSupportFailsClosedWithoutAllocation(){
        var tile=tile(5,5,values(25));
        assertFalse(tile.hasFiniteRectangleSupport(point(2,0),point(1,2)));
        assertFalse(tile.hasFiniteRectangleSupport(point(0,2),point(2,1)));
        var large=tile(257,257,values(257*257));
        assertFalse(large.hasFiniteRectangleSupport(point(0,0),point(256,256)));
    }
    @Test void explicitCoverageOmissionPrecedesEveryHeightAndColorRead(){
        AtomicInteger heights=new AtomicInteger(),colors=new AtomicInteger(),proofs=new AtomicInteger();
        SceneSurfaceSampler source=new SceneSurfaceSampler(){
            public String identity(){return "finite-footprint-with-interior-hole-v1";}
            public boolean supportsFootprintCoverage(){return true;}
            public boolean knownFootprint(long x,long z,long xx,long zz){proofs.incrementAndGet();return !(x<=2&&2<xx&&z<=2&&2<zz);}
            public long surfaceY(long x,long z){heights.incrementAndGet();assertFalse(x==8&&z==8);return 100;}
            public int argb(long x,long z,long y){colors.incrementAndGet();return 0xffaabbcc;}
        };
        var batch=new DhAdaptiveTerrainData(source,0,0).clip(List.of());
        assertEquals(4096,batch.sampledCells());assertEquals(4096,proofs.get());
        assertEquals(1,batch.footprintRejectedCells());assertEquals(1,batch.unknownCells());
        assertEquals(4095,batch.centerSampledCells());assertEquals(4095,heights.get());assertEquals(4095,colors.get());
        assertEquals(4095,batch.fullySupportedCells());assertEquals(batch.knownCells(),batch.fullySupportedCells());
        assertEquals(4095,batch.faces()); // No skirt or fabricated side wall surrounds the missing leaf.
    }
    @Test void legacyCenterSamplingDoesNotClaimCompleteFootprintSupport(){
        SceneSurfaceSampler source=new SceneSurfaceSampler(){
            public String identity(){return "legacy-center-only";}public long surfaceY(long x,long z){return 100;}
            public int argb(long x,long z,long y){return 0xffaabbcc;}
        };
        var batch=new DhAdaptiveTerrainData(source,0,0).clip(List.of());
        assertEquals(4096,batch.knownCells());assertEquals(4096,batch.centerSampledCells());
        assertEquals(0,batch.fullySupportedCells());assertEquals(0,batch.footprintRejectedCells());
    }
    @Test void sourceCapabilityChangesCannotUpgradeAConstructedSummary(){
        var enabled=new AtomicBoolean(false);
        SceneSurfaceSampler source=new SceneSurfaceSampler(){
            public String identity(){return "same-source-different-capability";}public long surfaceY(long x,long z){return 100;}
            public int argb(long x,long z,long y){return 0xffaabbcc;}public boolean supportsFootprintCoverage(){return enabled.get();}
        };
        var summary=new DhAdaptiveTerrainData(source,0,0);enabled.set(true);
        assertThrows(IllegalStateException.class,()->summary.clip(List.of()));
    }
}
