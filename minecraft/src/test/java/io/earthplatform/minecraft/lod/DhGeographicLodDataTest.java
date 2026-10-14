package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DhGeographicLodDataTest {
    private static SceneSurfaceSampler flat(long height){return new SceneSurfaceSampler(){public long surfaceY(long x,long z){return height;}public int argb(long x,long z,long y){return 0xffddeeff;}public String identity(){return "fixed-flat-"+height;}};}
    @Test void boundedFlatSourceHas4096DirectSummariesAndNoInventedBoundaryWalls() {
        AtomicInteger samples=new AtomicInteger();var fixed=flat(1000);
        var sampler=new SceneSurfaceSampler(){public long surfaceY(long x,long z){samples.incrementAndGet();return fixed.surfaceY(x,z);}public int argb(long x,long z,long y){return fixed.argb(x,z,y);}public String identity(){return fixed.identity();}};
        var source=new DhGeographicLodData(sampler,20_037_505,-20_037_505);var batch=source.clip(List.of());
        assertEquals(4096,samples.get());assertEquals(4096,source.knownCells());assertEquals(0,source.unknownCells());
        assertEquals(4096,source.sourceFaces());assertEquals(4096,batch.faces());assertTrue(batch.tiles().size()<=25);
        assertTrue(batch.tiles().values().stream().flatMap(List::stream).allMatch(f->f.direction()==DhTerrainGeometry.Direction.UP));
    }
    @Test void onlyTheExactKnownNativeVolumeClipsFarTopIncludingAtTrench() {
        var source=new DhGeographicLodData(flat(-11000),0,0);
        var wrong=source.clip(List.of(new CubePos(0,553,0)));assertEquals(4096,wrong.faces());assertEquals(0,wrong.clippedSourceFaces());
        var right=source.clip(List.of(new CubePos(0,-688,0)));assertEquals(4095,right.faces());assertEquals(1,right.clippedSourceFaces());
        assertEquals(4095,source.clip(List.of(new CubePos(0,-688,0),new CubePos(0,-688,0))).faces());
    }
    @Test void neighborHeightStepsEmitOneCorrectlyOrientedExposedWallOnly() {
        var sampler=new SceneSurfaceSampler(){public long surfaceY(long x,long z){return x>=0?1001:1000;}public int argb(long x,long z,long y){return 0xffffffff;}public String identity(){return "east-step";}};
        var source=new DhGeographicLodData(sampler,0,0);assertEquals(4160,source.sourceFaces());
        var sides=source.clip(List.of()).tiles().values().stream().flatMap(List::stream).filter(f->f.direction()!=DhTerrainGeometry.Direction.UP).toList();
        assertEquals(64,sides.size());assertTrue(sides.stream().allMatch(f->f.direction()==DhTerrainGeometry.Direction.WEST&&f.height()==1&&f.width()==16));
    }
    @Test void unknownCoverageCreatesNoInventedExteriorCaps() {
        var sampler=new SceneSurfaceSampler(){public long surfaceY(long x,long z){if(x>=0)throw new MissingTerrainException("outside accepted source");return 1000;}public int argb(long x,long z,long y){return 0xffffffff;}public String identity(){return "half-unknown";}};
        var source=new DhGeographicLodData(sampler,0,0);assertEquals(2048,source.knownCells());assertEquals(2048,source.unknownCells());
        assertEquals(2048,source.sourceFaces());assertEquals(2048,source.clip(List.of()).faces());
    }
    @Test void pageCrossingsAndNegativeHorizontalTilesNeverTruncate() {
        var sampler=new SceneSurfaceSampler(){public long surfaceY(long x,long z){return 8191+Math.floorDiv(x,128);}public int argb(long x,long z,long y){return 0xffffffff;}public String identity(){return "page-ridge";}};
        var source=new DhGeographicLodData(sampler,0,0);var batch=source.clip(List.of());
        assertEquals(java.util.Set.of(3,4),batch.tiles().keySet().stream().map(DhGeographicLodData.TileKey::pageY).collect(java.util.stream.Collectors.toSet()));
        assertTrue(batch.tiles().keySet().stream().anyMatch(k->k.originX()<0&&k.originZ()<0));
        for(var tile:batch.tiles().entrySet())for(var f:tile.getValue()){assertEquals(tile.getKey().pageY(),f.pageY2048());assertTrue(f.y()>=0&&f.y()<=2048);}
    }
    @Test void unstableSourceIdentityAndEmptyCoverageFailClosed() {
        AtomicInteger calls=new AtomicInteger();var unstable=new SceneSurfaceSampler(){public long surfaceY(long x,long z){calls.incrementAndGet();return 0;}public int argb(long x,long z,long y){return 0xffffffff;}public String identity(){return calls.get()==0?"before":"after";}};
        assertThrows(IllegalStateException.class,()->new DhGeographicLodData(unstable,0,0));
        var absent=new SceneSurfaceSampler(){public long surfaceY(long x,long z){throw new MissingTerrainException("unknown");}public int argb(long x,long z,long y){throw new AssertionError();}public String identity(){return "none";}};
        assertThrows(IllegalArgumentException.class,()->new DhGeographicLodData(absent,0,0));
        assertThrows(IllegalArgumentException.class,()->new DhGeographicLodData(flat(8864),0,0));
    }
}
