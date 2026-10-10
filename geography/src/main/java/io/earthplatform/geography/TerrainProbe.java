package io.earthplatform.geography;

import io.earthplatform.core.*;
import java.nio.file.*;
import java.util.Locale;

/** Offline command-line verification of an imported regional pack; no network or Minecraft launch. */
public final class TerrainProbe {
    private TerrainProbe() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Usage: TerrainProbe manifest.properties latitude longitude");
        OfflineTerrain terrain=OfflineTerrain.load(Path.of(args[0]));
        GeoPoint point=new GeoPoint(Double.parseDouble(args[1]),Double.parseDouble(args[2]));
        var location=terrain.manifest().projection().toProjected(point);
        long x=(long)StrictMath.floor(location.x()),z=(long)StrictMath.floor(location.z());
        long y=terrain.surfaceBlockY(x,z);
        CubePos pos=CubePos.fromBlock(x,y,z);
        ImmutableCube cube=terrain.cube(pos,MaterialPalette.DEFAULT);
        System.out.printf(Locale.ROOT,"Dataset: %s%nRelease: %s%nNative grid: %.9f arcsec%nHeight datum: %s%nLicence: %s%nRequested geographic elevation: %.3f m%nBlock-centre elevation: %.3f m%nCanonical surface block: %d %d %d%nCube: %s%nSurface material ID: %d%nManifest fingerprint: %s%n",terrain.manifest().provenance().id(),terrain.manifest().provenance().release(),terrain.manifest().provenance().resolutionArcSeconds(),terrain.manifest().provenance().verticalDatum(),terrain.manifest().provenance().license(),terrain.elevationMetres(point),terrain.elevationMetres(x,z),x,y,z,pos,cube.get(CubePos.local(x),CubePos.local(y),CubePos.local(z)),terrain.manifest().fingerprint());
    }
}
