package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import java.nio.file.*;
import java.util.*;

/** New read20/owned18 base proof, separate from the native-only baseline checkpoint. */
public final class DhEarthReplacementCensus {
    public record BaseCounts(int profiles,int farCenters,int cells,int sourceFaces,int faces,int owners,int pageFaces,
                             String worstFaces,String worstPage) {}
    public static DhTerrainGeometry.Volume owned(CubePos p){return new DhTerrainGeometry.Volume(p.minBlockX()-1,p.minBlockY()-1,p.minBlockZ()-1,p.minBlockX()+17,p.minBlockY()+17,p.minBlockZ()+17);}
    public static DhTerrainGeometry.Volume read(CubePos p){return new DhTerrainGeometry.Volume(p.minBlockX()-2,p.minBlockY()-2,p.minBlockZ()-2,p.minBlockX()+18,p.minBlockY()+18,p.minBlockZ()+18);}
    public static BaseCounts run(EarthAlpineDefinition source)throws Exception {
        var s=new DhEarthAlpineGeometryCensus.CachedSampler(source);var e=source.envelope();var a=source.anchor().digTarget();
        var pin=CubePos.fromBlock(a.x(),a.y(),a.z());var owned=owned(pin);var read=read(pin);
        var farCenters=new HashSet<String>();
        int profiles=0,cells=0,sourceFaces=0,faces=0,owners=0,pageFaces=0;String worstFaces="",worstPage="";
        var csv=new StringBuilder("centerX,centerZ,farX,farZ,cells,sourceFaces,pageFaces,owners,maxPageFaces\n");
        for(int cz=e.minCenterZ();cz<e.maxCenterZExclusive();cz++)for(int cx=e.minCenterX();cx<e.maxCenterXExclusive();cx++) {
            long fx=EarthAlpineDefinition.snapCubeCenter(cx),fz=EarthAlpineDefinition.snapCubeCenter(cz);farCenters.add(fx+","+fz);
            var refinement=DhEarthAlpineGeometryCensus.refinement(source,cx,cz).subList(0,27);
            var batch=new DhAdaptiveTerrainData(s,fx,fz,DhLodLayout.EARTH_ALPINE_2048)
                    .clipWithRefinementVolumes(List.of(),refinement,List.of(owned),List.of(read),()->{});
            if(batch.unknownCells()!=0||batch.knownCells()!=batch.sampledCells())throw new AssertionError("Unknown replacement source");
            profiles++;cells=Math.max(cells,batch.sampledCells());sourceFaces=Math.max(sourceFaces,batch.sourceFaces());owners=Math.max(owners,batch.tiles().size());
            String profile=cx+","+cz+" far="+fx+","+fz;
            if(batch.faces()>faces){faces=batch.faces();worstFaces=profile;}
            int page=0;for(var entry:batch.tiles().entrySet()) {page=Math.max(page,entry.getValue().size());if(entry.getValue().size()>pageFaces){pageFaces=entry.getValue().size();worstPage=profile+" owner="+entry.getKey();}}
            csv.append(cx).append(',').append(cz).append(',').append(fx).append(',').append(fz).append(',').append(batch.sampledCells()).append(',').append(batch.sourceFaces()).append(',').append(batch.faces()).append(',').append(batch.tiles().size()).append(',').append(page).append('\n');
            if(profiles%32==0)System.out.println("READ20_PROGRESS profiles="+profiles+" cells="+cells+" faces="+faces+" owners="+owners+" page="+pageFaces);
        }
        Path out=Path.of(System.getProperty("earth.alpine.replacement.evidence","review-evidence/alpine-replacement"));Files.createDirectories(out);Files.writeString(out.resolve("base-horizontal-profiles.csv"),csv);
        return new BaseCounts(profiles,farCenters.size(),cells,sourceFaces,faces,owners,pageFaces,worstFaces,worstPage);
    }
    public static void main(String[] args)throws Exception{var s=EarthAlpineDefinition.load(Path.of("geography"));System.out.println("READ20_BINDING source="+s.identity()+" layout="+DhLodLayout.EARTH_ALPINE_2048.identity());System.out.println("READ20_BASE "+run(s));}
}
