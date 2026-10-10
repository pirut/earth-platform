package io.earthplatform.minecraft;

import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.geography.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Explicit source/projection binding. Raster coordinates never masquerade as global block coordinates. */
public final class WindowTerrain {
    private final OfflineTerrain terrain;
    private final EquirectangularProjection projection;
    private final GeographyCubeProvider cubes;
    private final String runtimeFingerprint;

    public WindowTerrain(String dataset, String projectionId) {
        ProofTerrain.validateIdentifiers(dataset, projectionId);
        terrain = ProofTerrain.get(dataset);
        projection = ProofTerrain.GLOBAL_PROJECTION.equals(projectionId)
                ? new EquirectangularProjection(new GeoPoint(0, 0), 0) : terrain.manifest().projection();
        String identity = "minecraft-window-source-v1\n" + terrain.manifest().fingerprint() + "\n" + projectionId
                + "\n" + Double.toHexString(projection.origin().latitude()) + "\n" + Double.toHexString(projection.origin().longitude())
                + "\n" + Double.toHexString(projection.standardParallel()) + "\nblock-centres-x-east-z-south\n" + HeuristicSurface.VERSION;
        runtimeFingerprint = WorldManifest.sha256(identity.getBytes(StandardCharsets.UTF_8));
        ElevationProvider sampled = (points, cancellation) -> {
            cancellation.throwIfCancelled();
            return CompletableFuture.completedFuture(points.stream().map(point ->
                    new ElevationProvider.ElevationSample(terrain.elevationMetres(point), terrain.manifest().provenance())).toList());
        };
        cubes = new GeographyCubeProvider(projection, sampled, MaterialPalette.DEFAULT);
    }

    public OfflineTerrain source() { return terrain; }
    public EquirectangularProjection projection() { return projection; }
    public String runtimeFingerprint() { return runtimeFingerprint; }
    public double elevationMetres(long x, long z) { return terrain.elevationMetres(projection.blockCentre(x, z)); }
    public long surfaceBlockY(long x, long z) { return (long) StrictMath.floor(elevationMetres(x, z)); }
    public ImmutableCube cube(CubePos position) {
        // Offline sampling completes inline and performs no network or filesystem access.
        try { return cubes.load(position, new CancellationToken()).join(); }
        catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }
}
