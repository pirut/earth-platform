package io.earthplatform.geography;

import io.earthplatform.core.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Dependency-free unit/integration harness. Synthetic terrain is labelled and kept separate from actual NOAA imports. */
public final class GeographyTest {
    private static int checks;
    private static final DatasetProvenance FIXTURE = new DatasetProvenance("synthetic-fixture","test-v1","synthetic metres",3600,"CC0-1.0",URI.create("https://example.invalid/synthetic-fixture"),"SYNTHETIC_TEST_ONLY","Generated mathematical test fixture; not Earth measurements");
    public static void main(String[] args) throws Exception {
        Path pack = Path.of(args.length==0?"geography/data/noaa-etopo2022-proof/world.properties":args[0]);
        projection(); raster(); cache(); batch(); cubes(); cancellation(); manifests(pack); realPack(pack);
        System.out.println("GeographyTest: " + checks + " checks passed (synthetic tests + actual NOAA bounded data pack)");
    }
    private static void projection() {
        var p = new EquirectangularProjection(new GeoPoint(37.7749,-122.4194),37.7749);
        for (GeoPoint g : List.of(new GeoPoint(0,0),new GeoPoint(27.9881,86.925),new GeoPoint(-89,179.99),new GeoPoint(89,-179.99))) {
            var xy=p.toProjected(g); var restored=p.toGeographic(xy.x(),xy.z());
            near(g.latitude(),restored.latitude(),1e-10,"projection latitude roundtrip");
            near(0,GeoPoint.wrapLongitude(g.longitude()-restored.longitude()),1e-10,"projection longitude roundtrip");
        }
        equal(new GeoPoint(0,-180),new GeoPoint(0,180),"canonical antimeridian");
        var p0=new EquirectangularProjection(new GeoPoint(0,0),0);
        near(p0.toGeographic(-0.5,-0.5).longitude(),p0.blockCentre(-1,-1).longitude(),0,"negative block centre");
        near(p0.toGeographic(0,0).longitude(),p0.toGeographic(p0.widthMetres(),0).longitude(),1e-10,"world wraps longitude");
        fail(()->p0.toGeographic(0,2e7),MissingTerrainException.class,"outside poles fails");
        fail(()->new EquirectangularProjection(new GeoPoint(0,0),90),IllegalArgumentException.class,"singular parallel fails");
    }
    private static TileSpec spec(String id,double west,double north,int w,int h) { return new TileSpec(id,id+".egp","0".repeat(64),west,north,1,1,w,h); }
    private static RasterTile plane(TileSpec spec) {
        float[] v=new float[spec.width()*spec.height()];
        for(int y=0;y<spec.height();y++) for(int x=0;x<spec.width();x++)v[y*spec.width()+x]=(float)(100*(spec.west()+x)+10*(spec.north()-y));
        return new RasterTile(spec,v);
    }
    private static void raster() throws Exception {
        RasterTile tile=plane(spec("plane",0,2,3,3));
        near(65,tile.sample(new GeoPoint(1.5,0.5)),1e-10,"bilinear plane");
        near(220,tile.sample(new GeoPoint(2,2)),0,"last raster column exact");
        fail(()->tile.sample(new GeoPoint(3,0)),MissingTerrainException.class,"outside tile fails");
        RasterTile east=plane(spec("east",1,2,3,3));
        for(int i=0;i<=100;i++) { double lat=i/50.0; near(tile.sample(new GeoPoint(lat,1.5)),east.sample(new GeoPoint(lat,1.5)),1e-10,"overlap seamless"); }
        RasterTile crossing=new RasterTile(spec("crossing",179,1,3,2),new float[]{0,100,200,10,110,210});
        near(100,crossing.sample(new GeoPoint(1,-180)),0,"antimeridian exact");
        near(150,crossing.sample(new GeoPoint(1,-179.5)),1e-10,"antimeridian interpolation");
        RasterTile missing=new RasterTile(spec("missing",0,1,2,2),new float[]{1,Float.NaN,3,4});
        near(1,missing.sample(new GeoPoint(1,0)),0,"zero-weight no-data ignored");
        fail(()->missing.sample(new GeoPoint(0.5,0.5)),MissingTerrainException.class,"no-data never invented");
        equal(tile.spec(),RasterTile.decode(tile.spec(),tile.encode()).spec(),"binary roundtrip");
        byte[] truncated=Arrays.copyOf(tile.encode(),12);
        fail(()->RasterTile.decode(tile.spec(),truncated),IOException.class,"malformed raster rejected");
    }
    private static TileSpec writeTile(Path root,String id,float offset) throws Exception {
        TileSpec initial=spec(id,0,1,2,2);
        byte[] data=new RasterTile(initial,new float[]{offset,offset+1,offset+2,offset+3}).encode();
        Files.write(root.resolve(id+".egp"),data);
        return new TileSpec(id,id+".egp",WorldManifest.sha256(data),0,1,1,1,2,2);
    }
    private static void cache() throws Exception {
        Path root=Files.createTempDirectory("earth-geo-cache-test-");
        try(var executor=Executors.newFixedThreadPool(2)) {
            TileSpec a=writeTile(root,"a",10), b=writeTile(root,"b",20), c=writeTile(root,"c",30);
            var cache=new CachedTileStore(root,root.resolve("cache"),executor,32,120,2,false);
            var all=new ArrayList<CompletableFuture<RasterTile>>();
            for(int i=0;i<100;i++)all.add(cache.load(a));
            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);
            equal(1L,cache.stats().sourceReads(),"concurrent loads coalesced");
            cache.load(b).join(); cache.load(c).join();
            check(cache.stats().memoryBytes()<=32,"memory budget enforced");
            long disk;try(var paths=Files.list(root.resolve("cache"))) { disk=paths.mapToLong(x->{try{return Files.size(x);}catch(IOException e){throw new UncheckedIOException(e);}}).sum(); }
            check(disk<=120,"disk budget enforced");
            var cold=new CachedTileStore(root,root.resolve("cache"),executor,0,120,2,false);
            cold.load(c).join(); equal(1L,cold.stats().diskHits(),"persistent cache survives instance");
            Path corruptCache=root.resolve("cache").resolve(c.sha256()+".egp");
            byte[] corrupt=Files.readAllBytes(corruptCache);corrupt[corrupt.length-1]^=1;Files.write(corruptCache,corrupt);
            cold.load(c).join();equal(1L,cold.stats().repairedCacheEntries(),"cache corruption reloaded only from pinned source");
            equal(c.sha256(),WorldManifest.sha256(Files.readAllBytes(corruptCache)),"repaired bytes retain original identity");
            TileSpec wrong=new TileSpec("wrong","a.egp","f".repeat(64),0,1,1,1,2,2);
            fail(()->cold.load(wrong).join(),IOException.class,"checksum rejection");
            var queue=new ArrayList<Runnable>();
            var bounded=new CachedTileStore(root,root.resolve("bounded"),queue::add,0,0,1,false);
            var first=bounded.load(a);var duplicate=bounded.load(a);first.cancel(false);
            fail(()->bounded.load(b).join(),RejectedExecutionException.class,"in-flight admission bounded");
            queue.removeFirst().run();near(10,duplicate.join().sample(new GeoPoint(1,0)),0,"cancelled caller cannot poison shared load");
            equal(0,bounded.stats().inFlight(),"finished I/O releases permit");
            TileSpec escaped=new TileSpec("escape","../not-in-pack.egp",a.sha256(),0,1,1,1,2,2);
            fail(()->cold.load(escaped).join(),IOException.class,"path traversal rejected");
            TileSpec remote=new TileSpec("remote","https://example.invalid/fixture.egp",a.sha256(),0,1,1,1,2,2);
            var isolated=new CachedTileStore(root,root.resolve("offline"),executor,0,0,1,false);
            fail(()->isolated.load(remote).join(),IOException.class,"offline pack cannot initiate network");
        } finally { deleteTree(root); }
    }
    private static WorldManifest fixtureManifest(List<TileSpec> tiles) { return new WorldManifest("synthetic-world",1,"surface-fill-v1",new EquirectangularProjection(new GeoPoint(0.5,0.5),0),FIXTURE,tiles,"test-fingerprint","geography-palette-v1"); }
    private static void batch() {
        RasterTile tile=plane(spec("plane",0,2,3,3));
        AtomicInteger loads=new AtomicInteger();
        var provider=new RasterElevationProvider(fixtureManifest(List.of(tile.spec())),s->{loads.incrementAndGet();return CompletableFuture.completedFuture(tile);});
        var result=provider.sample(List.of(new GeoPoint(1,1),new GeoPoint(2,2)),new CancellationToken()).join();
        near(110,result.getFirst().metres(),0,"batch order");equal(1,loads.get(),"batch loads unique tile once");
        fail(()->provider.sample(List.of(new GeoPoint(1,1),new GeoPoint(-20,0)),new CancellationToken()).join(),MissingTerrainException.class,"missing batch coverage explicit");
        equal(1,loads.get(),"missing coverage checked before I/O");
    }
    private static void cubes() {
        for(double height:new double[]{8849,-11000,-0.1,0,16,-16,15.9}) {
            ElevationProvider fixed=(points,token)->CompletableFuture.completedFuture(points.stream().map(p->new ElevationProvider.ElevationSample(height,FIXTURE)).toList());
            var provider=new GeographyCubeProvider(new EquirectangularProjection(new GeoPoint(0,0),0),fixed,MaterialPalette.DEFAULT);
            long surface=(long)Math.floor(height);
            for(long base:new long[]{surface-32,surface-16,surface,surface+16,surface+32}) {
                CubePos pos=CubePos.fromBlock(0,base,0);
                ImmutableCube cube=provider.load(pos,new CancellationToken()).join();
                for(int y=0;y<16;y++) {
                    long worldY=pos.minBlockY()+y;
                    int expected=worldY>surface?(worldY<0?4:0):(worldY<surface-3?1:(surface<0?5:worldY==surface?3:2));
                    equal(expected,cube.get(0,y,0),"full-height cube material");
                }
            }
            check(provider.load(CubePos.fromBlock(0,surface-100,0),new CancellationToken()).join().isUniform(),"deep rock uniform");
        }
    }
    private static void cancellation() {
        var batch = new CompletableFuture<List<ElevationProvider.ElevationSample>>();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<CancellationToken> sharedToken = new AtomicReference<>();
        ElevationProvider delayed = (points,token) -> { calls.incrementAndGet();sharedToken.set(token);return batch; };
        var provider = new GeographyCubeProvider(new EquirectangularProjection(new GeoPoint(0,0),0),delayed,MaterialPalette.DEFAULT,1);
        try(var loader = new BoundedCubeLoader(provider,Runnable::run,2,0,2)) {
            var first = loader.request(new CubePos(0,0,0));
            var second = loader.request(new CubePos(0,1,0));
            equal(1,calls.get(),"vertical cube loads share one elevation request");
            first.close();
            equal(2,loader.stats().running(),"canceled geography request retains slot until shared work settles");
            check(!sharedToken.get().isCancelled(),"one cube cancellation leaves shared elevation live");
            batch.complete(Collections.nCopies(256,new ElevationProvider.ElevationSample(10,FIXTURE)));
            check(first.future().isCancelled(),"canceled cube cannot publish terrain");
            equal(0,second.future().join().get(0,0,0),"other vertical cube completes correctly");
            equal(0,loader.stats().running(),"all geography work settles");
            equal(0,provider.columnStats().inFlight(),"column permit released");
            equal(1L,provider.columnStats().elevationBatches(),"height cache batch count");
        }
    }
    private static void manifests(Path manifest) throws Exception {
        Properties values=new Properties();try(var input=Files.newBufferedReader(manifest)){values.load(input);}
        Path test=Files.createTempFile("earth-manifest-test-",".properties");
        try {
            String original=WorldManifest.load(manifest).fingerprint();
            try(var out=Files.newBufferedWriter(test)){values.store(out,"Different comment and property order");}
            equal(original,WorldManifest.load(test).fingerprint(),"manifest identity ignores comments and order");
            values.setProperty("projection.originLat","37.8");
            try(var out=Files.newBufferedWriter(test)){values.store(out,null);}
            check(!original.equals(WorldManifest.load(test).fingerprint()),"projection edit changes world identity");
            values.setProperty("projection.id","unversioned-new-projection");
            try(var out=Files.newBufferedWriter(test)){values.store(out,null);}
            fail(()->WorldManifest.load(test),IOException.class,"unknown projection cannot silently reinterpret world");
            values.setProperty("projection.id","equirectangular-v1");values.setProperty("interpolation","cubic-unreviewed");
            try(var out=Files.newBufferedWriter(test)){values.store(out,null);}
            fail(()->WorldManifest.load(test),IOException.class,"unknown interpolation rejected");
            var normal=WorldManifest.load(manifest);
            check(!normal.worldIdentity().equals(normal.worldIdentity("different-registry-palette")),"adapter palette is part of save identity");
        } finally { Files.delete(test); }
    }
    private static void realPack(Path path) throws Exception {
        OfflineTerrain real=OfflineTerrain.load(path);
        equal("CC0-1.0",real.manifest().provenance().license(),"NOAA license pinned");
        equal(4,real.manifest().tiles().size(),"bounded real tiles");
        double sf=real.elevationMetres(new GeoPoint(37.7749,-122.4194));
        double everest=real.elevationMetres(new GeoPoint(27.9881,86.925));
        double mariana=real.elevationMetres(new GeoPoint(11.373,142.591));
        check(sf>0&&sf<200,"San Francisco real positive terrain");
        check(everest>8000&&everest<8900,"Everest coarse high elevation");
        check(mariana< -9500&&mariana> -12000,"Challenger real deep ocean");
        fail(()->real.elevationMetres(new GeoPoint(0,0)),MissingTerrainException.class,"real pack is not fictitious global coverage");
        long ground=real.surfaceBlockY(0,0);
        ImmutableCube cube=real.cube(CubePos.fromBlock(0,ground,0),MaterialPalette.DEFAULT);
        equal(3,cube.get(0,CubePos.local(ground),0),"actual SF terrain materialized into cubic storage");
        for(GeoPoint p:List.of(new GeoPoint(27.9881,86.925),new GeoPoint(11.373,142.591))) {
            var projected=real.manifest().projection().toProjected(p);long x=(long)Math.floor(projected.x()),z=(long)Math.floor(projected.z());long y=real.surfaceBlockY(x,z);
            ImmutableCube far=real.cube(CubePos.fromBlock(x,y,z),MaterialPalette.DEFAULT);
            equal(y<0?5:3,far.get(CubePos.local(x),CubePos.local(y),CubePos.local(z)),"actual extreme-region surface cube");
        }
        // Adjacent real tiles duplicate a halo, so the interpolation function must agree within float registration error.
        TileSpec west=real.manifest().tiles().get(0),east=real.manifest().tiles().get(1);
        Path root=path.toAbsolutePath().getParent();
        RasterTile a=RasterTile.decode(west,Files.readAllBytes(root.resolve(west.path()))),b=RasterTile.decode(east,Files.readAllBytes(root.resolve(east.path())));
        double lon=east.west()+east.stepLon()/2;
        for(int row=1;row<east.height()-1;row++) { GeoPoint p=new GeoPoint(east.north()-east.stepLat()*(row+0.25),lon);near(a.sample(p),b.sample(p),1e-7,"actual NOAA overlap seam"); }
        String hash=WorldManifest.load(path).fingerprint();equal(hash,real.manifest().fingerprint(),"stable world manifest fingerprint");
        System.out.printf(Locale.ROOT,"Actual NOAA samples: SF %.3f m; Everest %.3f m; Challenger Deep %.3f m; manifest %s%n",sf,everest,mariana,hash);
    }
    private static void check(boolean condition,String label) { checks++;if(!condition)throw new AssertionError(label); }
    private static void equal(Object expected,Object actual,String label) { check(Objects.equals(expected,actual),label+": expected "+expected+" actual "+actual); }
    private static void near(double expected,double actual,double tolerance,String label) { check(Math.abs(expected-actual)<=tolerance,label+": expected "+expected+" actual "+actual); }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void fail(Throwing action,Class<? extends Throwable> expected,String label) {
        checks++;try { action.run(); } catch(Throwable t) { while(t.getCause()!=null&&(t instanceof CompletionException||t instanceof ExecutionException))t=t.getCause();if(expected.isInstance(t))return;throw new AssertionError(label+": wrong failure",t); }throw new AssertionError(label+": no failure");
    }
    private static void deleteTree(Path root) throws IOException { try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);} }
}
