package io.earthplatform.geography.composition;

import io.earthplatform.geography.*;
import java.nio.file.*;
import java.util.*;

/** Compare both actual source surfaces at identical high-resolution footprint edge coordinates. No blending or source modification. */
public final class CompositionBoundaryAudit {
    public static void main(String[] args)throws Exception {
        Path geography=Path.of(args.length>0?args[0]:"geography"),output=Path.of(args.length>1?args[1]:"geography/composition-results");
        Files.createDirectories(output);
        OfflineTerrain noaa=OfflineTerrain.load(geography.resolve("data/noaa-etopo2022-proof/world.properties"));
        OfflineTerrain copernicus=OfflineTerrain.load(geography.resolve("data/copernicus-everest-proof/world.properties"));
        TileSpec tile=copernicus.manifest().tiles().getFirst();
        var points=new LinkedHashSet<GeoPoint>();
        double east=tile.west()+tile.stepLon()*(tile.width()-1),south=tile.north()-tile.stepLat()*(tile.height()-1);
        for(int x=0;x<tile.width();x++){double lon=tile.west()+x*tile.stepLon();points.add(new GeoPoint(tile.north(),lon));points.add(new GeoPoint(south,lon));}
        for(int y=0;y<tile.height();y++){double lat=tile.north()-y*tile.stepLat();points.add(new GeoPoint(lat,tile.west()));points.add(new GeoPoint(lat,east));}
        var rows=new ArrayList<Row>();
        for(GeoPoint p:points){double a=copernicus.elevationMetres(p),b=noaa.elevationMetres(p);rows.add(new Row(p.latitude(),p.longitude(),a,b,a-b));}
        double[] absolute=rows.stream().mapToDouble(r->Math.abs(r.difference)).sorted().toArray();
        Row worst=rows.stream().max(Comparator.comparingDouble(r->Math.abs(r.difference))).orElseThrow();
        try(var csv=Files.newBufferedWriter(output.resolve("actual-edge-differences.csv"))) {
            csv.write("latitude,longitude,copernicus_metres,noaa_metres,signed_difference_metres\n");
            for(Row r:rows)csv.write(String.format(Locale.ROOT,"%.12f,%.12f,%.9f,%.9f,%.9f%n",r.lat,r.lon,r.high,r.low,r.difference));
        }
        String json=String.format(Locale.ROOT,"{\n  \"policy\":\"%s\",\n  \"scope\":\"Actual Copernicus Everest crop perimeter; both surfaces sampled at identical geographic points\",\n  \"samples\":%d,\n  \"nativeEdgeSampleSpacingArcSeconds\":1,\n  \"meanAbsoluteStepMetres\":%.9f,\n  \"medianAbsoluteStepMetres\":%.9f,\n  \"p95AbsoluteStepMetres\":%.9f,\n  \"p99AbsoluteStepMetres\":%.9f,\n  \"maximumAbsoluteStepMetres\":%.9f,\n  \"edgesExceeding10m\":%d,\n  \"edgesExceeding50m\":%d,\n  \"worstPoint\":{\"latitude\":%.12f,\"longitude\":%.12f,\"copernicusMetres\":%.9f,\"noaaMetres\":%.9f,\"signedDifferenceMetres\":%.9f},\n  \"seamlessWorldAccepted\":false,\n  \"note\":\"Hard selection exposes artificial source-boundary steps. These are source surface differences, not a measurement uncertainty estimate. No blending, correction, datum transformation or existing-world regeneration was performed.\"\n}\n",CompositionPlan.POLICY,rows.size(),Arrays.stream(absolute).average().orElseThrow(),quantile(absolute,.5),quantile(absolute,.95),quantile(absolute,.99),absolute[absolute.length-1],Arrays.stream(absolute).filter(x->x>10).count(),Arrays.stream(absolute).filter(x->x>50).count(),worst.lat,worst.lon,worst.high,worst.low,worst.difference);
        Files.writeString(output.resolve("actual-edge-summary.json"),json);System.out.print(json);
    }
    private static double quantile(double[] a,double p){double index=p*(a.length-1);int i=(int)index;return a[i]+(a[Math.min(i+1,a.length-1)]-a[i])*(index-i);}
    private record Row(double lat,double lon,double high,double low,double difference){}
}
