package io.earthplatform.geography.landcover;

import io.earthplatform.geography.GeoPoint;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Source-backed native-cell, edge, identity, immutability and failure regression tests. */
public final class LandCoverTest {
    private LandCoverTest() {}
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(Checked action, String message) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path pack = Path.of(args.length == 0 ? "geography/data/worldcover-2021-proof" : args[0]);
        synthetic();
        sourcePack(pack);
        failureAndIdentity(pack);
        System.out.println("LandCoverTest passed: 2,097,152 native source cells, 12 controls, explicit unknown/no-data, boundaries, hashes, offline preload and identity.");
    }
    private static void synthetic() throws Exception {
        byte[] values = {10,30,0,70};
        var raster = new LandCoverRaster("fixture",2,2,0,2,1,1,values);
        values[0]=80;
        check(raster.sample(new GeoPoint(1.75,0.25)).classification()==LandCoverClass.TREE_COVER,"Defensive copy");
        check(raster.sample(new GeoPoint(1.75,0.99)).classification()==LandCoverClass.TREE_COVER,"No class interpolation");
        check(raster.sample(new GeoPoint(1.75,1)).classification()==LandCoverClass.GRASSLAND,"Internal cell edge");
        check(raster.sample(new GeoPoint(2,0)).classification()==LandCoverClass.TREE_COVER,"NW edge inclusive");
        check(raster.sample(new GeoPoint(0,1)).status()==LandCoverSample.Status.OUTSIDE_COVERAGE,"South edge exclusive");
        check(raster.sample(new GeoPoint(1,2)).status()==LandCoverSample.Status.OUTSIDE_COVERAGE,"East edge exclusive");
        check(raster.sample(new GeoPoint(0.5,0.5)).status()==LandCoverSample.Status.SOURCE_NO_DATA,"No-data distinct");
        check(raster.sample(new GeoPoint(0.5,0.5)).classification()==LandCoverClass.UNKNOWN,"No grass fallback");
        var crossing = new LandCoverRaster("dateline",2,1,179,1,1,1,new byte[]{10,80});
        check(crossing.sample(new GeoPoint(0.5,-179)).classification()==LandCoverClass.UNKNOWN,"Dateline far edge");
        check(crossing.sample(new GeoPoint(0.5,-179.9)).classification()==LandCoverClass.PERMANENT_WATER,"Dateline sample");
        check(crossing.sample(new GeoPoint(0.5,180)).equals(crossing.sample(new GeoPoint(0.5,-180))),"Longitude aliases");
        byte[] encoded=raster.encode();
        check(Arrays.equals(encoded,LandCoverRaster.decode("fixture",encoded).encode()),"ELC1 round trip");
        rejects(()->LandCoverRaster.decode("bad",Arrays.copyOf(encoded,encoded.length-1)),"Truncated bytes");
        rejects(()->LandCoverRaster.decode("bad",Arrays.copyOf(encoded,encoded.length+1)),"Trailing bytes");
        encoded[44]=(byte)255;
        rejects(()->LandCoverRaster.decode("bad",encoded),"Invalid class must fail, not unknown");
        rejects(()->new LandCoverRaster("bad",2,2,0,2,0,1,new byte[4]),"Zero cell size");
        rejects(()->new LandCoverRaster("bad",Integer.MAX_VALUE,Integer.MAX_VALUE,0,2,1,1,new byte[0]),"Huge geometry");
    }
    private static void sourcePack(Path pack) throws Exception {
        var provider = OfflineLandCover.load(pack.resolve("landcover.properties"));
        check(provider.sample(new GeoPoint(0,0)).equals(LandCoverSample.outsideCoverage()),"Missing coverage");
        check(provider.sample(new GeoPoint(27.9881,86.925)).classification()==LandCoverClass.SNOW_AND_ICE,"Everest source class");
        for (String line:Files.readAllLines(pack.resolve("control-points.tsv")).subList(1,13)) {
            String[] fields=line.split("\t");
            var sample=provider.sample(new GeoPoint(Double.parseDouble(fields[1]),Double.parseDouble(fields[2])));
            check(sample.regionId().equals(fields[0])&&sample.classification().code()==Integer.parseInt(fields[3]),"Source control "+line);
        }
        for (String id:List.of("marin-headlands","everest")) {
            byte[] bytes=Files.readAllBytes(pack.resolve(id+"/landcover.elc"));
            var raster=LandCoverRaster.decode(id,bytes);
            check(!raster.covers(new GeoPoint(raster.north(),raster.west()+raster.width()*raster.stepLon())),"Native east exclusive");
            check(!raster.covers(new GeoPoint(raster.north()-raster.height()*raster.stepLat(),raster.west())),"Native south exclusive");
            check(raster.covers(new GeoPoint(raster.north(),raster.west())),"Native NW inclusive");
            for(int y=0;y<raster.height();y++) for(int x=0;x<raster.width();x++) {
                var point=new GeoPoint(raster.north()-(y+0.5)*raster.stepLat(),raster.west()+(x+0.5)*raster.stepLon());
                int expected=Byte.toUnsignedInt(bytes[44+y*raster.width()+x]);
                check(provider.sample(point).classification().code()==expected,"Every native cell center");
            }
        }
        System.out.println("Verified pack identity: "+provider.identity());
    }
    private static void failureAndIdentity(Path source) throws Exception {
        Path temporary=Files.createTempDirectory("landcover-test-");
        try {
            Path pack=temporary.resolve("pack"); Files.createDirectories(pack);
            for(String id:List.of("marin-headlands","everest")) {
                Files.createDirectory(pack.resolve(id));
                Files.copy(source.resolve(id+"/landcover.elc"),pack.resolve(id+"/landcover.elc"));
            }
            Path manifest=pack.resolve("landcover.properties");
            String original=Files.readString(source.resolve("landcover.properties"));
            Files.writeString(manifest,original);
            var provider=OfflineLandCover.load(manifest);
            List<String> lines=new ArrayList<>(original.lines().toList()); Collections.reverse(lines);
            Files.writeString(manifest,String.join("\n",lines)+"\n");
            check(provider.identity().equals(OfflineLandCover.load(manifest).identity()),"Manifest order canonicalized");
            Files.writeString(manifest,original.replace("dataset.attribution=","dataset.attribution=changed "));
            check(!provider.identity().equals(OfflineLandCover.load(manifest).identity()),"Attribution/provenance affects identity");
            Files.writeString(manifest,original.replace("dataset.epoch=2021","dataset.epoch=2025"));
            rejects(()->OfflineLandCover.load(manifest),"Unsupported epoch");
            Files.writeString(manifest,original.replace("tile.count=2","tile.count=1000"));
            rejects(()->OfflineLandCover.load(manifest),"Unbounded manifest");
            Files.copy(pack.resolve("everest/landcover.elc"),temporary.resolve("outside.elc"));
            Files.writeString(manifest,original.replace("everest/landcover.elc","../outside.elc"));
            rejects(()->OfflineLandCover.load(manifest),"Path escape");
            Files.writeString(manifest,original);
            Path tile=pack.resolve("everest/landcover.elc"); byte[] raw=Files.readAllBytes(tile); raw[100]^=1; Files.write(tile,raw);
            rejects(()->OfflineLandCover.load(manifest),"Corruption must fail");
            Files.delete(tile);
            rejects(()->OfflineLandCover.load(manifest),"Absent file must fail");
            // Successfully preloaded instance continues after source deletion. Query has no runtime file/network dependency.
            check(provider.sample(new GeoPoint(27.9881,86.925)).classification()==LandCoverClass.SNOW_AND_ICE,"Offline preload");
        } finally {
            try(var paths=Files.walk(temporary)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
}
