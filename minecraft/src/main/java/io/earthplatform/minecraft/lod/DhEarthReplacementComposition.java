package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.*;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;

/** Exact bounded alpine base/read20/owned18 replacement, using the unchanged pooled publication caps. */
public final class DhEarthReplacementComposition {
    private DhEarthReplacementComposition(){}
    public static DhReplacementComposition.Result build(SceneSurfaceSampler sampler,long centerX,long centerZ,DhLodLayout layout,
            List<CubePos> ready,DhReplacementInput.Data replacement,long maxBytes,Runnable check){
        Objects.requireNonNull(check).run();Objects.requireNonNull(sampler);Objects.requireNonNull(ready);
        DhEarthReplacementAdmission.requireLayout();
        if(layout!=DhLodLayout.EARTH_ALPINE_2048||maxBytes<0||maxBytes>DhReplacementComposition.MAX_RESULT_BYTES)
            throw new DhReplacementComposition.Declined("Exact bounded Earth layout and existing5MiB result pool required");
        if(!DhEarthReplacementAdmission.SOURCE_IDENTITY.equals(sampler.identity())||!sampler.supportsFootprintCoverage()||!sampler.supportsStratifiedSides())
            throw new DhReplacementComposition.Declined("Exact source identity and stratified complete-footprint Earth sampler required");
        if(replacement==null||replacement.capture().earthCapture()==null
                ||!EarthReplacementAdmission.MATERIAL_POLICY.equals(replacement.certificate().materialPolicy()))
            throw new DhReplacementComposition.Declined("Earth publication requires its exact six-material replacement authority");
        var capture=replacement.capture();var earth=capture.earthCapture();
        DhEarthReplacementAdmission.requireCapture(earth);
        if(!DhEarthReplacementAdmission.PIN.equals(replacement.certificate().editedCube()))
            throw new DhReplacementComposition.Declined("Earth replacement certificate has the wrong original pin");
        var admission=new DhEarthReplacementAdmission().inspect(earth,check);
        if(!admission.admitted())throw new DhReplacementComposition.Declined("Earth native/replacement/pooled capture admission declined");
        var envelope=EarthAlpineDefinition.ENVELOPE;
        if(Math.floorMod(centerX,128)!=0||Math.floorMod(centerZ,128)!=0
                ||centerX<envelope.minColumnX()+2048||centerX>envelope.maxColumnXExclusive()-2048
                ||centerZ<envelope.minColumnZ()+2048||centerZ>envelope.maxColumnZExclusive()-2048)
            throw new DhReplacementComposition.Declined("Earth far center is outside the exhaustively censused snap set");
        requireReadyProfile(ready,centerX,centerZ);
        ready=List.copyOf(ready);
        var owned=capture.ownedVolume();var read=capture.readBounds();
        var readVolume=new Volume(read.minX(),read.minY(),read.minZ(),read.maxXExclusive(),read.maxYExclusive(),read.maxZExclusive());
        final DhAdaptiveTerrainData.Batch base;
        try{base=new DhAdaptiveTerrainData(sampler,centerX,centerZ,layout)
                .clipWithRefinementVolumes(ready,ready,List.of(owned),List.of(readVolume),check);}
        catch(DhAdaptiveTerrainData.BudgetExceeded budget){throw new DhReplacementComposition.Declined(budget.getMessage(),budget);}
        // Runtime checks bind every publication to the same static reservation, including malformed/partial profiles.
        if(base.unknownCells()!=0||base.knownCells()!=base.sampledCells()||base.sampledCells()>DhEarthReplacementAdmission.MAX_BASE_CELLS
                ||base.sourceFaces()>DhEarthReplacementAdmission.MAX_BASE_SOURCE_FACES||base.faces()>DhEarthReplacementAdmission.MAX_BASE_FACES
                ||base.tiles().size()>DhEarthReplacementAdmission.MAX_BASE_OWNERS
                ||base.tiles().values().stream().anyMatch(f->f.size()>DhEarthReplacementAdmission.MAX_BASE_PAGE_FACES))
            throw new DhReplacementComposition.Declined("Earth base publication exceeds its exact source census reservation");
        requireBytes(base.faces()+admission.replacementFaces(),replacement.declaredBytes(),maxBytes);
        final DhVoxelReplacementGeometry.Mesh mesh;
        try{mesh=DhVoxelReplacementGeometry.build(capture,
                Math.min(DhEarthReplacementAdmission.MAX_REPLACEMENT_FACES,DhAdaptiveTerrainData.MAX_FACES-base.faces()),
                (long)admission.replacementFaces()*DhVoxelReplacementGeometry.BYTES_PER_FACE,check);}
        catch(DhVoxelReplacementGeometry.BudgetExceeded budget){throw new DhReplacementComposition.Declined("Earth exact replacement exceeds pooled reservation",budget);}
        if(mesh.faceCount()!=admission.replacementFaces())throw new IllegalStateException("Immutable Earth face census changed");
        var readySet=new HashSet<>(ready);var visible=new ArrayList<Face>();
        for(var face:mesh.faces()){
            check.run();if(readySet.contains(solidCube(face)))continue;
            if(face.material()!=2&&face.material()!=8)throw new DhReplacementComposition.Declined("Earth replacement lost pinned material category");
            visible.add(face);
        }
        int pieces=(visible.size()+4095)/4096,totalFaces=Math.addExact(base.faces(),visible.size());
        if(pieces>2||base.tiles().size()+pieces>DhAdaptiveTerrainData.MAX_TILES||totalFaces>DhAdaptiveTerrainData.MAX_FACES)
            throw new DhReplacementComposition.Declined("Earth combined face or physical owner cap exceeded");
        requireBytes(totalFaces,replacement.declaredBytes(),maxBytes);
        if(88L*(DhAdaptiveTerrainData.MAX_FACES+(long)totalFaces)>8L*1024*1024||64+base.tiles().size()+pieces>128)
            throw new DhReplacementComposition.Declined("Earth old/new GPU reservation exceeded");
        var tiles=new LinkedHashMap<DhGeographicLodData.TileKey,List<PageFace>>(base.tiles());
        for(int i=0;i<visible.size();i++){
            var pages=splitPages(visible.get(i),owned.minX(),owned.minZ());
            if(pages.size()!=1||pages.getFirst().pageY2048()!=3)throw new DhReplacementComposition.Declined("Earth original pin must occupy one exact signed page");
            var key=new DhGeographicLodData.TileKey(owned.minX(),owned.minZ(),3,replacement.identity(),i/4096);
            tiles.computeIfAbsent(key,k->new ArrayList<>()).add(pages.getFirst());
        }
        if(!DhEarthReplacementAdmission.SOURCE_IDENTITY.equals(sampler.identity())||!sampler.supportsFootprintCoverage()||!sampler.supportsStratifiedSides())
            throw new IllegalStateException("Earth immutable source changed during composition");
        var batch=new DhAdaptiveTerrainData.Batch(tiles,totalFaces,base.clippedSourceFaces()+mesh.faceCount()-visible.size(),
                base.sampledCells(),base.knownCells(),base.unknownCells(),base.refinedCells(),base.sourceFaces()+mesh.faceCount(),base.transitionFaces(),
                base.centerSampledCells(),base.footprintRejectedCells(),base.fullySupportedCells());
        check.run();return new DhReplacementComposition.Result(batch,mesh.faceCount(),visible.size(),pieces);
    }
    /** Binds every candidate to exactly one member of the complete finite moving-window census. */
    public static CubePos requireReadyProfile(List<CubePos> ready,long farX,long farZ){
        Objects.requireNonNull(ready);
        if(ready.size()!=27)throw new DhReplacementComposition.Declined("Earth replacement requires exactly27 moving native targets");
        var distinct=new HashSet<CubePos>();
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(var cube:ready){
            if(cube==null||!distinct.add(cube))throw new DhReplacementComposition.Declined("Earth moving profile contains null or duplicate targets");
            minX=Math.min(minX,cube.x());minY=Math.min(minY,cube.y());minZ=Math.min(minZ,cube.z());
            maxX=Math.max(maxX,cube.x());maxY=Math.max(maxY,cube.y());maxZ=Math.max(maxZ,cube.z());
        }
        if((long)maxX-minX!=2||(long)maxY-minY!=2||(long)maxZ-minZ!=2)
            throw new DhReplacementComposition.Declined("Earth targets must be one complete contiguous3³ moving window");
        var center=new CubePos(minX+1,minY+1,minZ+1);
        if(!EarthAlpineDefinition.ENVELOPE.permitsCenter(center)
                ||EarthAlpineDefinition.snapCubeCenter(center.x())!=farX||EarthAlpineDefinition.snapCubeCenter(center.z())!=farZ)
            throw new DhReplacementComposition.Declined("Earth moving native center/domain does not match the exact far snap");
        //27 distinct integer positions in a3×3×3 bounding box necessarily fill its complete product.
        return center;
    }
    private static CubePos solidCube(Face face){return CubePos.fromBlock(face.x()-(face.direction()==Direction.EAST?1:0),
            face.y()-(face.direction()==Direction.UP?1:0),face.z()-(face.direction()==Direction.SOUTH?1:0));}
    private static void requireBytes(int faces,long inputBytes,long maxBytes){
        long bytes=4096L+inputBytes+128L*faces;
        if(bytes>maxBytes)throw new DhReplacementComposition.Declined("Earth prepared-result reservation exceeds existing5MiB cap");
    }
}
