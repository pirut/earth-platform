package io.earthplatform.minecraft.scene;

/** Shared near/far source. Unknown coverage throws MissingTerrainException; it is never ocean or air. */
public interface SceneSurfaceSampler {
    long surfaceY(long x,long z);
    int argb(long x,long z,long surfaceY);
    String identity();
    /** Optional immutable top/three-block-subsurface/deep material capability. */
    default boolean supportsStratifiedSides(){return false;}
    default ColumnMaterials columnMaterials(long x,long z,long surfaceY){
        throw new UnsupportedOperationException("Stratified column materials unavailable");
    }
    record SurfaceMaterial(int argb,int dhMaterial) {
        public SurfaceMaterial {
            if((argb>>>24)!=255||(dhMaterial!=2&&dhMaterial!=8))
                throw new IllegalArgumentException("Opaque STONE/SNOW column material required");
        }
    }
    record ColumnMaterials(SurfaceMaterial top,SurfaceMaterial subsurface,SurfaceMaterial deep,int subsurfaceDepth) {
        public ColumnMaterials {
            java.util.Objects.requireNonNull(top);java.util.Objects.requireNonNull(subsurface);java.util.Objects.requireNonNull(deep);
            if(subsurfaceDepth!=3)throw new IllegalArgumentException("Exact native three-block subsurface required");
        }
    }
    /** Optional complete continuous source-support proof, distinct from a known center sample. */
    default boolean supportsFootprintCoverage(){return false;}
    /**
     * Called only when the explicit capability is present. Half-open native metre bounds include
     * the full rendered cell footprint; implementations must include every source-policy halo.
     * False means omit the whole summary cell, without inventing heights or boundary skirts.
     */
    default boolean knownFootprint(long minX,long minZ,long maxXExclusive,long maxZExclusive){
        throw new UnsupportedOperationException("Complete footprint coverage is unavailable");
    }
    /** Explicit capability for immutable column reads from bounded background summary jobs. */
    default boolean supportsConcurrentReads(){return false;}
}
