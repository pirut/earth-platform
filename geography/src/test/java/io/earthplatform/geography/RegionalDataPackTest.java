package io.earthplatform.geography;

import io.earthplatform.core.*;
import java.nio.file.*;
import java.util.Locale;

/** Generic real-data pack integration check. Expected ranges are supplied explicitly, never forced into terrain. */
public final class RegionalDataPackTest {
    public static void main(String[] args)throws Exception {
        if(args.length!=6)throw new IllegalArgumentException("Usage: RegionalDataPackTest manifest latitude longitude minimumExpectedMetres maximumExpectedMetres resolutionArcSeconds");
        OfflineTerrain terrain=OfflineTerrain.load(Path.of(args[0]));
        GeoPoint point=new GeoPoint(Double.parseDouble(args[1]),Double.parseDouble(args[2]));
        double low=Double.parseDouble(args[3]),high=Double.parseDouble(args[4]),resolution=Double.parseDouble(args[5]);
        if(!(low<high))throw new IllegalArgumentException("Expected range must be ordered");
        double height=terrain.elevationMetres(point);
        check(height>=low&&height<=high,"Real sample outside independently expected range");
        check(Math.abs(terrain.manifest().provenance().resolutionArcSeconds()-resolution)<1e-12,"Native source spacing changed");
        var projected=terrain.manifest().projection().toProjected(point);long x=(long)Math.floor(projected.x()),z=(long)Math.floor(projected.z());
        long y=terrain.surfaceBlockY(x,z);int material=y<0?5:3;
        CubePos surface=CubePos.fromBlock(x,y,z);
        ImmutableCube a=terrain.cube(surface,MaterialPalette.DEFAULT),b=terrain.cube(surface,MaterialPalette.DEFAULT);
        check(a.get(CubePos.local(x),CubePos.local(y),CubePos.local(z))==material,"Real terrain surface not present at canonical Y");
        check(java.util.Arrays.equals(a.toArray(),b.toArray()),"Repeated generation nondeterministic");
        ImmutableCube deep=terrain.cube(CubePos.fromBlock(x,y-1024,z),MaterialPalette.DEFAULT);
        check(deep.isUniform()&&deep.get(0)==1,"Deep underground lost full-height coordinates");
        ImmutableCube highCube=terrain.cube(CubePos.fromBlock(x,Math.max(y,0)+1024,z),MaterialPalette.DEFAULT);
        check(highCube.isUniform()&&highCube.get(0)==0,"High air lost full-height coordinates");
        System.out.printf(Locale.ROOT,"RegionalDataPackTest: 6 checks passed; dataset=%s sample=%.6fm canonicalY=%d fingerprint=%s%n",terrain.manifest().provenance().id(),height,y,terrain.manifest().fingerprint());
    }
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
