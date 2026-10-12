package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.enginebridge.ArenaTerrainSource;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.Objects;

/** Immutable shared heightfield only; exact arena geometry is supplied separately. */
public final class DhArenaSurfaceSampler implements SceneSurfaceSampler {
    private final ArenaTerrainSource source;
    public DhArenaSurfaceSampler(ArenaTerrainSource source){this.source=Objects.requireNonNull(source);}
    public DhArenaSurfaceSampler(ArenaTerrainSource source,String identity){this(source);if(!identity().equals(identity))throw new IllegalArgumentException("Exact arena source identity required");}
    public long surfaceY(long x,long z){return source.baseSurfaceY(x,z);}
    public int argb(long x,long z,long y){if(y!=surfaceY(x,z))throw new IllegalArgumentException("Exact source surface required");return source.baseArgb(x,z);}
    public String identity(){return source.identity();}
    public boolean supportsFootprintCoverage(){return true;}
    public boolean knownFootprint(long x0,long z0,long x1,long z1){return source.knownFootprint(x0,z0,x1,z1);}
    public boolean supportsConcurrentReads(){return true;}
}
