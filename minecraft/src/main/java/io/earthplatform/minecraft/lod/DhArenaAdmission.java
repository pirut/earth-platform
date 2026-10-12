package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.enginebridge.*;
import java.util.Objects;

/** Source-free-of-IO prospective admission; exact immutable capture, no renderer/GL/actor callback. */
public final class DhArenaAdmission implements ArenaAdmission {
    // Exhaustive production template reservations for the fixed source and ARENA_4096.
    // The matching proof must remain in the aggregate whenever policy/layout changes.
    public static final int MAX_BASE_LEAVES=11476,MAX_BASE_FACES=15887,MAX_BASE_OWNERS=42;
    public static final long MAX_NATIVE_VERTEX_BYTES=1_048_576;
    private final ArenaTerrainSource source;
    public DhArenaAdmission(ArenaTerrainSource source){this.source=Objects.requireNonNull(source);}
    public record Census(DhArenaGeometry.Census arena,int farFaces,int farOwners,long resultBytes,long oldNewGpuBytes,int oldNewOwners){
        public boolean admitted(){return farFaces<=DhAdaptiveTerrainData.MAX_FACES&&farOwners<=DhAdaptiveTerrainData.MAX_TILES
            &&arena.largestOwnerFaces()<=DhAdaptiveTerrainData.MAX_PAGE_FACES&&arena.maxNativeFaces()<=8192
            &&arena.maxNativeVertexBytes()<=MAX_NATIVE_VERTEX_BYTES&&resultBytes<=DhReplacementComposition.MAX_RESULT_BYTES
            &&oldNewGpuBytes<=8L*1024*1024&&oldNewOwners<=128;}
    }
    public Census inspect(ArenaCapture capture,Runnable check){
        var c=DhArenaGeometry.inspect(source,capture,check);int faces=Math.addExact(MAX_BASE_FACES,c.sourceFaces()),owners=Math.addExact(MAX_BASE_OWNERS,c.pieceOwners());
        long bytes=4096L+capture.declaredBytes()+128L*faces;
        // Reserve against any previously admitted full-cap publication, not just
        // a sampled resident size or the candidate's hidden current faces.
        return new Census(c,faces,owners,bytes,88L*(DhAdaptiveTerrainData.MAX_FACES+(long)faces),DhAdaptiveTerrainData.MAX_TILES+owners);
    }
    public Receipt assess(ArenaCapture capture){
        String reason="";boolean admitted=false;
        try{admitted=inspect(capture,()->{}).admitted();if(!admitted)reason="Combined arena native/far face, owner or byte cap exceeded";}
        catch(DhReplacementComposition.Declined e){reason=e.getMessage();}
        return new Receipt(admitted,reason,capture.definition().identity(),capture.contentIdentity(),source.identity(),DhLodLayout.ARENA_4096.identity());
    }
}
