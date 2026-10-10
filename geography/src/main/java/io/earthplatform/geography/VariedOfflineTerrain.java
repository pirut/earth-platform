package io.earthplatform.geography;

import io.earthplatform.core.*;
import io.earthplatform.geography.variety.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Independent opt-in fixture. Setup uses an accepted offline pack; queries and cubes do no I/O and spawn no threads. */
public final class VariedOfflineTerrain implements CubeProvider {
    private final OfflineTerrain terrain;
    private final MicrodetailProfile profile;
    private final MaterialPalette palette;
    public VariedOfflineTerrain(OfflineTerrain terrain,MicrodetailProfile profile,MaterialPalette palette) {
        this.terrain=Objects.requireNonNull(terrain); this.profile=Objects.requireNonNull(profile); this.palette=Objects.requireNonNull(palette);
    }
    public WorldManifest sourceManifest() { return terrain.manifest(); }
    public MicrodetailProfile profile() { return profile; }
    /** The caller must pin the actual runtime numeric block palette, as with baseline WorldManifest. */
    public WorldIdentity worldIdentity(String actualPaletteVersion) {
        var base=terrain.manifest().worldIdentity(actualPaletteVersion);
        if (!profile.enabled()) return base;
        String data=WorldManifest.sha256((base.datasetVersion()+"\n"+profile.fingerprint()).getBytes(StandardCharsets.UTF_8));
        return new WorldIdentity(base.seed(),base.generatorId()+"/"+MicrodetailProfile.VERSION,base.generatorVersion(),data,base.blockPaletteVersion());
    }
    public SurfaceSample sample(GeoPoint point) {
        double base=terrain.elevationMetres(point);
        double delta=SurfaceMicrodetail.displacement(point,base,terrain.manifest().provenance().resolutionArcSeconds(),profile);
        return new SurfaceSample(base,delta,delta==0?base:base+delta,terrain.manifest().provenance());
    }
    public SurfaceSample sample(long x,long z) { return sample(terrain.manifest().projection().blockCentre(x,z)); }
    public double elevationMetres(long x,long z) { return sample(x,z).generatedMetres(); }
    public long surfaceBlockY(long x,long z) { return GeographyCubeProvider.surfaceY(elevationMetres(x,z)); }
    public ImmutableCube cube(CubePos position,CancellationToken token) {
        Objects.requireNonNull(position); Objects.requireNonNull(token); token.throwIfCancelled();
        long[] heights=new long[256];
        for(int z=0;z<16;z++) {
            token.throwIfCancelled();
            for(int x=0;x<16;x++) heights[z*16+x]=surfaceBlockY(position.minBlockX()+x,position.minBlockZ()+z);
        }
        return GeographyCubeProvider.fromHeights(position,heights,palette,token);
    }
    /** Bounded synchronous computation on the caller's worker. Use BoundedCubeLoader to bound parallelism/queueing. */
    @Override public CompletableFuture<ImmutableCube> load(CubePos position,CancellationToken token) {
        try { return CompletableFuture.completedFuture(cube(position,token)); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    /** Source provenance is retained for the BASE only. proceduralDeltaMetres is always explicitly synthetic. */
    public record SurfaceSample(double sourceMetres,double proceduralDeltaMetres,double generatedMetres,DatasetProvenance sourceProvenance) {}
}
