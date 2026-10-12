package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.*;
import java.util.*;

/** Heightfield minus exact arena union, then six-direction arena faces minus actual XYZ readiness. */
public final class DhArenaComposition {
    private DhArenaComposition(){}
    public static DhReplacementComposition.Result build(long centerX,long centerZ,DhLodLayout layout,List<CubePos> ready,
            DhArenaInput.Data input,long maxBytes,Runnable check){
        Objects.requireNonNull(input);Objects.requireNonNull(check).run();
        if(layout!=DhLodLayout.ARENA_4096||maxBytes<0||maxBytes>DhReplacementComposition.MAX_RESULT_BYTES)
            throw new DhReplacementComposition.Declined("Exact arena layout and existing result budget required");
        var capture=input.capture();var d=capture.definition();var source=input.source();
        ready=List.copyOf(ready);var readySet=new HashSet<>(ready);
        if(ready.size()!=27||readySet.size()!=27)throw new DhReplacementComposition.Declined("Arena requires exact moving27 native mask");
        int minX=ready.stream().mapToInt(CubePos::x).min().orElseThrow(),minY=ready.stream().mapToInt(CubePos::y).min().orElseThrow(),minZ=ready.stream().mapToInt(CubePos::z).min().orElseThrow();
        var center=new CubePos(minX+1,minY+1,minZ+1);d.validateCenter(center);
        for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)if(!readySet.contains(center.offset(x,y,z)))throw new DhReplacementComposition.Declined("Noncanonical arena native mask");
        if(centerX!=256*Math.floorDiv(16L*center.x()+136,256)||centerZ!=256*Math.floorDiv(16L*center.z()+136,256))throw new DhReplacementComposition.Declined("Far center differs from proved native template");
        var census=new DhArenaAdmission(source).inspect(capture,check);
        if(!census.admitted()||census.resultBytes()>maxBytes)throw new DhReplacementComposition.Declined("Arena pooled prospective census exceeds existing caps");
        var refinement=new ArrayList<>(ready);for(var p:d.positions())if(!refinement.contains(p))refinement.add(p);
        var base=new DhAdaptiveTerrainData(new DhArenaSurfaceSampler(source),centerX,centerZ,layout).clip(ready,refinement,List.of(DhArenaGeometry.volume(d)),check);
        if(base.sampledCells()>DhArenaAdmission.MAX_BASE_LEAVES||base.faces()>DhArenaAdmission.MAX_BASE_FACES||base.tiles().size()>DhArenaAdmission.MAX_BASE_OWNERS)
            throw new IllegalStateException("Fixed arena base escaped its exhaustive reservation");
        var tiles=new LinkedHashMap<DhGeographicLodData.TileKey,List<DhTerrainGeometry.PageFace>>(base.tiles());
        var counts=new HashMap<DhArenaGeometry.Bucket,Integer>();int[] visible={0},owners={0};
        int seen=DhArenaGeometry.visit(capture,(x,y,z,direction,color)->{
            if(readySet.contains(CubePos.fromBlock(x,y,z)))return;
            var b=DhArenaGeometry.bucket(x,y,z);int n=counts.getOrDefault(b,0);if(n%DhAdaptiveTerrainData.MAX_PAGE_FACES==0)owners[0]++;
            counts.put(b,n+1);visible[0]++;
            if(base.faces()+visible[0]>DhAdaptiveTerrainData.MAX_FACES||base.tiles().size()+owners[0]>DhAdaptiveTerrainData.MAX_TILES)
                throw new IllegalStateException("Visible arena escaped conservative source census");
            var pages=DhTerrainGeometry.splitPages(DhArenaGeometry.face(x,y,z,direction,color),b.x(),b.z());
            if(pages.size()!=1)throw new AssertionError("Unit arena face crosses signed page");
            var key=new DhGeographicLodData.TileKey(b.x(),b.z(),b.page(),input.identity(),n/DhAdaptiveTerrainData.MAX_PAGE_FACES);
            tiles.computeIfAbsent(key,k->new ArrayList<>()).add(pages.getFirst());
        },check);
        if(seen!=census.arena().sourceFaces())throw new AssertionError("Immutable arena census changed");
        var combined=new DhAdaptiveTerrainData.Batch(tiles,base.faces()+visible[0],base.clippedSourceFaces()+seen-visible[0],base.sampledCells(),base.knownCells(),base.unknownCells(),base.refinedCells(),base.sourceFaces()+seen,base.transitionFaces(),base.centerSampledCells(),base.footprintRejectedCells(),base.fullySupportedCells());
        check.run();return new DhReplacementComposition.Result(combined,seen,visible[0],owners[0]);
    }
}
