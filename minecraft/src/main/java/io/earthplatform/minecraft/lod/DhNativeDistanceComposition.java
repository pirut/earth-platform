package io.earthplatform.minecraft.lod;

import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import io.earthplatform.minecraft.visual.SourceSurfaceCoverage;
import java.util.*;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;

/** Pure exact Earth replacement + selected-distance native complement. No draw authority is minted here. */
public final class DhNativeDistanceComposition {
    public static final String POLICY=DhNativeDistanceTerrainData.POLICY+":earth-owned18-read20:replacement8192:initial-revision0";
    private DhNativeDistanceComposition(){}
    public static DhReplacementComposition.Result build(SceneSurfaceSampler sampler,long centerX,long centerZ,
            DhLodLayout layout,SourceSurfaceCoverage coverage,DhReplacementInput.Data replacement,
            DhNativeDistanceTerrainData.Limits limits,Runnable check){
        Objects.requireNonNull(check).run();Objects.requireNonNull(coverage);Objects.requireNonNull(limits);
        if(layout!=DhLodLayout.EARTH_ALPINE_2048
                ||!EarthAlpineDefinition.DEFINITION_IDENTITY.equals(sampler.identity())
                ||!sampler.supportsFootprintCoverage()||!sampler.supportsStratifiedSides())
            throw new IllegalArgumentException("Exact immutable stratified Earth source/layout required");
        if(replacement==null||replacement.capture().earthCapture()==null
                ||!EarthReplacementAdmission.MATERIAL_POLICY.equals(replacement.certificate().materialPolicy()))
            throw new IllegalArgumentException("Earth distance complement requires its exact replacement content");
        var capture=replacement.capture();var earth=capture.earthCapture();
        var admitted=new DhNativeDistanceReplacementAdmission().inspect(earth,check);
        if(!admitted.admitted())throw new IllegalArgumentException("Earth capture exceeds native/replacement031 reserve");
        if(!coverage.plan().captureIdentity().equals(earth.contentIdentity()))
            throw new IllegalArgumentException("Native visual plan and far replacement capture identities differ");
        var envelope=EarthAlpineDefinition.ENVELOPE;
        if(Math.floorMod(centerX,128)!=0||Math.floorMod(centerZ,128)!=0
                ||centerX<envelope.minColumnX()+2048||centerX>envelope.maxColumnXExclusive()-2048
                ||centerZ<envelope.minColumnZ()+2048||centerZ>envelope.maxColumnZExclusive()-2048)
            throw new IllegalArgumentException("Far center exceeds the exact immutable Earth source support");
        if(EarthAlpineDefinition.snapCubeCenter(coverage.plan().center().x())!=centerX
                ||EarthAlpineDefinition.snapCubeCenter(coverage.plan().center().z())!=centerZ)
            throw new IllegalArgumentException("Native plan and far center belong to different requests");
        var owned=capture.ownedVolume();var read=capture.readBounds();
        if(limits.maxPageFaces()!=4096||limits.maxFaces()<=8192||limits.maxOwners()<=2)
            throw new IllegalArgumentException("Exact replacement reserve requires4096-face pieces and available base capacity");
        var baseLimits=new DhNativeDistanceTerrainData.Limits(limits.maxCells(),
                Math.min(limits.maxFaces()-8192,DhNativeDistanceReplacementAdmission.MAX_BASE_FACES),
                Math.min(limits.maxOwners()-2,DhNativeDistanceReplacementAdmission.MAX_BASE_OWNERS),4096,limits.maxResultBytes());
        var composer=new DhNativeDistanceTerrainData(sampler,centerX,centerZ,layout,coverage,baseLimits);
        var base=composer.compose(owned,new Volume(read.minX(),read.minY(),read.minZ(),read.maxXExclusive(),read.maxYExclusive(),read.maxZExclusive()),check);
        var mesh=DhVoxelReplacementGeometry.build(capture,8192,8192L*DhVoxelReplacementGeometry.BYTES_PER_FACE,check);
        if(mesh.faceCount()!=admitted.replacementFaces())throw new IllegalStateException("Immutable replacement face census changed");
        var visible=new ArrayList<Face>();
        for(var face:mesh.faces()){
            check.run();visible.addAll(composer.clipCoverage(face));
            if((long)base.faces()+visible.size()>limits.maxFaces())throw new DhNativeDistanceTerrainData.BudgetExceeded("Pooled Earth distance-complement face limit exceeded");
        }
        int total=Math.addExact(base.faces(),visible.size());
        long resultBytes=Math.addExact(4096L+replacement.declaredBytes(),Math.multiplyExact(128L,total));
        if(resultBytes>limits.maxResultBytes())throw new DhNativeDistanceTerrainData.BudgetExceeded("Pooled Earth distance-complement result byte limit exceeded");
        var tiles=new LinkedHashMap<DhGeographicLodData.TileKey,List<PageFace>>(base.tiles());
        int pieceFaces=limits.maxPageFaces(),pieces=(visible.size()+pieceFaces-1)/pieceFaces;
        if(pieces>8||(long)tiles.size()+pieces>limits.maxOwners())throw new DhNativeDistanceTerrainData.BudgetExceeded("Pooled Earth distance-complement owner limit exceeded");
        for(int i=0;i<visible.size();i++){
            var pages=splitPages(visible.get(i),owned.minX(),owned.minZ());
            if(pages.size()!=1)throw new IllegalStateException("Exact Earth replacement crossed its signed packing page");
            var page=pages.getFirst();var key=new DhGeographicLodData.TileKey(owned.minX(),owned.minZ(),page.pageY2048(),replacement.identity(),i/pieceFaces);
            tiles.computeIfAbsent(key,k->new ArrayList<>()).add(page);
        }
        check.run();
        var batch=new DhAdaptiveTerrainData.Batch(tiles,total,base.clippedSourceFaces()+mesh.faceCount()-visible.size(),
                base.sampledCells(),base.knownCells(),base.unknownCells(),base.refinedCells(),base.sourceFaces()+mesh.faceCount(),base.transitionFaces(),
                base.centerSampledCells(),base.footprintRejectedCells(),base.fullySupportedCells());
        return new DhReplacementComposition.Result(batch,mesh.faceCount(),visible.size(),pieces);
    }
}
