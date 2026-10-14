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
