package io.earthplatform.minecraft.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Regression for the real west-edge overhang: inspect emitted polygons and actual official CPU vertex positions. No GL. */
@Timeout(60)
class EarthMovingFootprintCoverageTest {
    @BeforeAll static void registries(){EarthRollingFarBudgetTest.registries();}

    @Test void allTwentyFiveCentersEmitOnlySupportedFootprintsAndOfficialVertices() throws Exception {
        String jar=System.getProperty("earthplatform.lodProbeJar",System.getenv("EARTH_DH_PROBE_JAR"));
        assumeTrue(jar!=null&&!jar.isBlank(),"Explicit pinned official artifact required");
        var runtime=DhOfficialRuntime.open(Path.of(jar));
        var source=EarthRollingFarBudgetTest.provider().surfaceSampler();var pin=EarthRollingFarBudgetTest.pin(source);assertTrue(source.supportsFootprintCoverage());
        int omittedCenters=0,totalOmittedCells=0;
        for(var offset:EarthMovingFarCenterBudgetTest.route().subList(0,25)){
            var visit=EarthMovingFarCenterBudgetTest.visit(source,pin,offset[0],offset[1]);var c=visit.center();
            var data=new DhAdaptiveTerrainData(source,c.x(),c.z());var batch=data.clip(visit.mask());
            EarthMovingFarCenterBudgetTest.budgets(batch);
            long requestedX0=data.originX(),z0=data.originZ(),x1=requestedX0+1024,z1=z0+1024;
            long expectedX0=requestedX0<9_674_290?requestedX0+16:requestedX0;
            int expectedUnknown=requestedX0<9_674_290?64:0;
            assertEquals(expectedUnknown,batch.unknownCells(),"Whole unsupported coarse leaves are explicitly omitted");
            if(expectedUnknown>0)omittedCenters++;totalOmittedCells+=batch.unknownCells();
            long minX=Long.MAX_VALUE,maxX=Long.MIN_VALUE,minZ=Long.MAX_VALUE,maxZ=Long.MIN_VALUE;
            int topFaces=0,checkedCorners=0,packedBoundaryVertices=0;
            for(var entry:batch.tiles().entrySet()){
                var boundary=new ArrayList<DhTerrainGeometry.PageFace>();
                for(var f:entry.getValue())if(f.direction()==DhTerrainGeometry.Direction.UP){
                    topFaces++;long x=entry.getKey().originX()+f.x(),z=entry.getKey().originZ()+f.z();
                    assertTrue(source.knownFootprint(x,z,x+f.width(),z+f.height()),"Complete emitted top rectangle is supported, including interpolation interior");
                    assertTrue(x>=9_674_290,"An emitted rectangle must not extend into the known missing west strip");
                    assertTrue(x>=expectedX0&&x+f.width()<=x1&&z>=z0&&z+f.height()<=z1);
                    if(x!=expectedX0&&z!=z0&&x+f.width()!=x1&&z+f.height()!=z1)continue;
                    for(long xx:new long[]{x,x+f.width()-1})for(long zz:new long[]{z,z+f.height()-1}){
                        assertDoesNotThrow(()->source.surfaceY(xx,zz),"Every rendered boundary rectangle solid-side corner is source-supported");checkedCorners++;
                    }
                    boundary.add(f);
                }
                if(boundary.isEmpty())continue;
                byte[] raw=runtime.buildRectangles(boundary);var bytes=ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());
                // Exactly the position layout consumed by the unchanged DH shader: ushort4, 16-byte stride.
                for(int at=0;at<raw.length;at+=16){
                    long x=entry.getKey().originX()+Short.toUnsignedInt(bytes.getShort(at));
                    long z=entry.getKey().originZ()+Short.toUnsignedInt(bytes.getShort(at+4));
                    assertTrue(x>=9_674_290,"Official output may not resurrect the omitted unsupported strip");
                    assertTrue(x>=expectedX0&&x<=x1&&z>=z0&&z<=z1);
                    minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);packedBoundaryVertices++;
                }
            }
            assertTrue(topFaces>0);assertTrue(checkedCorners>0);assertTrue(packedBoundaryVertices>0);
            assertEquals(expectedX0,minX);assertEquals(x1,maxX);assertEquals(z0,minZ);assertEquals(z1,maxZ);
            System.out.println("EARTH_MOVING_SUPPORTED_FOOTPRINT center="+c+" requestedMinX="+requestedX0+" knownColumnMinX=9674290 emittedMinX="+minX
                    +" emittedMaxX="+maxX+" emittedMinZ="+minZ+" emittedMaxZ="+maxZ+" omittedUnknownLeaves="+batch.unknownCells()
                    +" checkedSolidCorners="+checkedCorners+" officialBoundaryVertices="+packedBoundaryVertices+" unknownRenderedCorners=0 unknownRenderedVertices=0");
        }
        assertEquals(5,omittedCenters);assertEquals(320,totalOmittedCells);
        System.out.println("EARTH_MOVING_SUPPORTED_FOOTPRINT_SUMMARY centers=25 conservativelyOmittedCenters="+omittedCenters+" omittedUnknownLeaves="+totalOmittedCells
                +" unsupportedRenderedRectangleArea=0 officialVerticesOutsideKnownDomain=0; CPU vertex inspection only, no GL claim");
    }
    @Test void providerFootprintProofRejectsOutsideTheActualSignedIntColumnDomain(){
        var source=EarthRollingFarBudgetTest.provider().surfaceSampler();assertTrue(source.supportsFootprintCoverage());
        long z=EarthMovingFarCenterBudgetTest.Z;
        assertFalse(source.knownFootprint((long)Integer.MIN_VALUE-1,z,(long)Integer.MIN_VALUE+15,z+16));
        assertFalse(source.knownFootprint((long)Integer.MAX_VALUE,z,(long)Integer.MAX_VALUE+16,z+16));
    }

    @Test void finiteCenterAndCornersCannotHideAnInterpolationInteriorNodataHole(){
        // Synthetic raster probe solely for conservative consumer semantics, not a different Earth source.
        var spec=new io.earthplatform.geography.TileSpec("interior-hole","unused.egp","0".repeat(64),0,0,.001,.001,17,17);
        float[] heights=new float[17*17];java.util.Arrays.fill(heights,100);heights[5*17+3]=Float.NaN;
        var raster=new io.earthplatform.geography.RasterTile(spec,heights);
        for(var p:java.util.List.of(new io.earthplatform.geography.GeoPoint(0,0),new io.earthplatform.geography.GeoPoint(0,.016),
                new io.earthplatform.geography.GeoPoint(-.016,0),new io.earthplatform.geography.GeoPoint(-.016,.016),
                new io.earthplatform.geography.GeoPoint(-.008,.008)))assertEquals(100,raster.sample(p));
        assertThrows(io.earthplatform.geography.MissingTerrainException.class,()->raster.sample(new io.earthplatform.geography.GeoPoint(-.005,.003)));
        assertFalse(raster.hasFiniteRectangleSupport(new io.earthplatform.geography.GeoPoint(0,0),new io.earthplatform.geography.GeoPoint(-.016,.016)));
        var heightCalls=new java.util.concurrent.atomic.AtomicInteger();var rejectedCenterCalls=new java.util.concurrent.atomic.AtomicInteger();
        var source=new io.earthplatform.minecraft.scene.SceneSurfaceSampler(){
            public long surfaceY(long x,long z){heightCalls.incrementAndGet();if(x==8&&z==8)rejectedCenterCalls.incrementAndGet();return 100+Math.floorDiv(x,16)+Math.floorDiv(z,16);}
            public int argb(long x,long z,long y){return 0xff708090;}
            public String identity(){return "synthetic-interior-raster-nodata-consumer-test";}
            public boolean supportsFootprintCoverage(){return true;}
            public boolean knownFootprint(long x,long z,long x1,long z1){
                if(x!=0||z!=0)return true;
                return raster.hasFiniteRectangleSupport(new io.earthplatform.geography.GeoPoint(-z*.001,x*.001),
                        new io.earthplatform.geography.GeoPoint(-z1*.001,x1*.001));
            }
        };
        var batch=new DhAdaptiveTerrainData(source,0,0).clip(java.util.List.of());
        assertEquals(4096,batch.sampledCells());assertEquals(4095,batch.knownCells());assertEquals(1,batch.unknownCells());
        assertEquals(1,batch.footprintRejectedCells());assertEquals(4095,batch.centerSampledCells());assertEquals(4095,batch.fullySupportedCells());
        assertEquals(4095,heightCalls.get());assertEquals(0,rejectedCenterCalls.get(),"Reject the full footprint before its otherwise finite center is sampled");
        int topFaces=0;
        for(var entry:batch.tiles().entrySet())for(var f:entry.getValue()){
            long x=entry.getKey().originX()+f.x(),z=entry.getKey().originZ()+f.z();
            switch(f.direction()){
                case UP -> {topFaces++;assertFalse(x<16&&x+f.width()>0&&z<16&&z+f.height()>0,"No invented top inside rejected full leaf");}
                case EAST,WEST -> assertFalse((x==0||x==16)&&z<16&&z+f.width()>0,"No X skirt against the omitted leaf");
                case NORTH,SOUTH -> assertFalse((z==0||z==16)&&x<16&&x+f.width()>0,"No Z skirt against the omitted leaf");
                case DOWN -> fail("No invented bottoms");
            }
        }
        assertEquals(4095,topFaces);
        System.out.println("EARTH_MOVING_INTERIOR_NODATA finiteCorners=4 finiteCenter=true interiorNodeMissing=true rejectedLeaves=1 rejectedCenterQueries=0 inventedTops=0 inventedSkirts=0");
    }

}
