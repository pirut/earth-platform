package io.earthplatform.geography.composition;

import io.earthplatform.core.*;
import io.earthplatform.geography.*;
import io.earthplatform.geography.ElevationProvider.ElevationSample;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synthetic contracts first, then optional separately-pinned actual NOAA/Copernicus selection. No survey research data is used. */
public final class SourceCompositionTest {
    static int checks;
    static final GeoPoint HIGH=new GeoPoint(1,1),LOW=new GeoPoint(1,-5);
    static final SafeCoverage WORLD=new SafeCoverage(List.of(new SafeCoverage.Rectangle(-180,-89,89,360)));
    static final SafeCoverage PATCH=new SafeCoverage(List.of(new SafeCoverage.Rectangle(0,0,2,2)));
    static String hash(String text){return WorldManifest.sha256(text.getBytes(StandardCharsets.UTF_8));}
    static DatasetProvenance provenance(String id,double resolution){return new DatasetProvenance(id,"fixture-v1","SYNTHETIC EGM2008 metres",resolution,"CC0-1.0",URI.create("urn:fixture:"+id),"SYNTHETIC_TEST_ONLY","Synthetic mathematical fixture, not Earth measurements");}
    static DatumEvidence evidence(String id){return new DatumEvidence(VerticalDatum.EGM2008_METRES,URI.create("urn:fixture-evidence:"+id),hash("evidence-"+id));}
    static SourceBinding source(String id,double resolution,int priority,SafeCoverage coverage,double value,AtomicInteger calls){
        var p=provenance(id,resolution);return new SourceBinding(id,hash(id),p,evidence(id),coverage,priority,(points,token)->{calls.incrementAndGet();return CompletableFuture.completedFuture(points.stream().map(x->new ElevationSample(value,p)).toList());});
    }
    static SourceBinding withProvider(SourceBinding s,ElevationProvider provider){return new SourceBinding(s.id(),s.sourceFingerprint(),s.provenance(),s.datumEvidence(),s.coverage(),s.priority(),provider);}
    static CompositionPlan plan(SourceBinding...sources){return new CompositionPlan(VerticalDatum.EGM2008_METRES,List.of(sources));}
    public static void main(String[] args)throws Exception {
        selection();coverage();fingerprints();failures();orderAndAdmission();cancellation();boundedShape();concurrency();
        if(args.length==1)realPacks(Path.of(args[0]));
        System.out.println("SourceCompositionTest: "+checks+" checks passed; hard edges, no blending, no fallback, no datum conversion");
    }
    static void selection(){
        AtomicInteger lowCalls=new AtomicInteger(),highCalls=new AtomicInteger();
        var low=source("low",15,999,WORLD,1000,lowCalls);var high=source("high",1,-999,PATCH,10,highCalls);
        var p=plan(low,high);equal("high",p.select(HIGH).id(),"native resolution outranks numeric priority");equal("low",p.select(LOW).id(),"lower-resolution coverage fallback chosen before sampling");
        var provider=new CompositeElevationProvider(p);
        var samples=provider.sample(List.of(HIGH,LOW,new GeoPoint(1,2),new GeoPoint(1,2.000001)),new CancellationToken()).join();
        equal(List.of(10.0,1000.0,10.0,1000.0),samples.stream().map(ElevationSample::metres).toList(),"closed border and abrupt hard-edge values");
        equal(high.provenance(),samples.getFirst().provenance(),"exact selected provenance retained");equal(1,highCalls.get(),"one grouped high source query");equal(1,lowCalls.get(),"one grouped low source query");
        var a=source("a",1,5,PATCH,20,new AtomicInteger());var b=source("b",1,6,PATCH,30,new AtomicInteger());
        equal("b",plan(a,b).select(HIGH).id(),"priority breaks native-resolution tie");
        var c=source("c",1,5,PATCH,20,new AtomicInteger());equal("a",plan(c,a).select(HIGH).id(),"source ID breaks final tie");
        equal(plan(a,b,low).fingerprint(),plan(low,b,a).fingerprint(),"registration order does not affect fingerprint");
    }
    static void coverage(){
        var crossing=new SafeCoverage.Rectangle(179,-1,1,2);
        check(crossing.covers(new GeoPoint(0,180)),"+180 crossing coverage");check(crossing.covers(new GeoPoint(0,-180)),"-180 crossing coverage");
        check(crossing.covers(new GeoPoint(0,-179)),"closed eastern antimeridian endpoint");check(!crossing.covers(new GeoPoint(0,-178.999)) ,"outside crossing footprint");
        check(!crossing.covers(new GeoPoint(0,178.999)),"outside western footprint");
        for(int lon=-180;lon<180;lon++)check(WORLD.covers(new GeoPoint(0,lon)),"explicit full longitude span");
        var disjoint=new SafeCoverage(List.of(new SafeCoverage.Rectangle(-10,0,1,1),new SafeCoverage.Rectangle(10,0,1,1)));
        check(!disjoint.covers(new GeoPoint(.5,0)),"disjoint footprint does not claim intervening geography");
        fail(()->new SafeCoverage.Rectangle(0,0,1,0),IllegalArgumentException.class,"zero span rejected");
        var t=new TileSpec("sample-centres","t.egp",hash("t"),10,20,.01,.01,3,3);
        var safe=SafeCoverage.fromTiles(List.of(t));check(safe.covers(new GeoPoint(20,10)),"northwest centre accepted");
        fail(()->SafeCoverage.fromTiles(Collections.nCopies(1025,t)),IllegalArgumentException.class,"tile factory enforces budget before materialization");
        check(!safe.covers(new GeoPoint(20.004,10)),"GeoTIFF pixel-edge area is not safe sample-centre footprint");
        for(double west:new double[]{359.9,-360.1,179.9}) {
            var wrappedTile=new TileSpec("wrapped","wrapped.egp",hash("wrapped"),west,1,.1,.1,2,2);
            GeoPoint endpoint=new GeoPoint(1,west+.1);
            check(wrappedTile.covers(endpoint),"native tile recognizes noncanonical closed endpoint");
            check(SafeCoverage.fromTiles(List.of(wrappedTile)).covers(endpoint),"coverage preserves noncanonical closed endpoint");
        }
        fail(()->new SafeCoverage.Rectangle(10,0,1,1e-20),IllegalArgumentException.class,"unrepresentably small coverage rejected");
        for(double[] example:List.of(new double[]{179.6,.4},new double[]{180.1,359.9},new double[]{359.9,.1})) {
            var r=new SafeCoverage.Rectangle(example[0],-1,1,example[1]);
            check(r.covers(new GeoPoint(0,example[0]+example[1])),"explicit canonical eastern endpoint is retained");
            check(!r.covers(new GeoPoint(0,Math.nextUp(r.east()))),"one representable step past canonical east is excluded");
        }
        Random random=new Random(20261009);
        for(int i=0;i<1000;i++) {
            double west=random.nextDouble()*1440-720,span=random.nextDouble()*359.9+.0001;
            var r=new SafeCoverage.Rectangle(west,-1,1,span);
            check(r.covers(new GeoPoint(0,west)),"random original west endpoint retained");
            check(r.covers(new GeoPoint(0,west+span)),"random original east endpoint retained");
        }
        var fourEdges=new SafeCoverage.Rectangle(10,20,21,1);
        for(GeoPoint point:List.of(new GeoPoint(20,10),new GeoPoint(21,10),new GeoPoint(20,11),new GeoPoint(21,11)))check(fourEdges.covers(point),"all four exact footprint corners closed");
        for(GeoPoint point:List.of(new GeoPoint(Math.nextDown(20.0),10.5),new GeoPoint(Math.nextUp(21.0),10.5),new GeoPoint(20.5,Math.nextDown(10.0)),new GeoPoint(20.5,Math.nextUp(11.0))))check(!fourEdges.covers(point),"immediately adjacent outside coordinate excluded");
    }
    static void fingerprints(){
        var original=source("high",1,3,PATCH,10,new AtomicInteger());var p=plan(original);
        var q=original.provenance();
        List<SourceBinding> variations=List.of(
            new SourceBinding("different",original.sourceFingerprint(),q,original.datumEvidence(),PATCH,3,original.provider()),
            new SourceBinding("high",hash("new tiles"),q,original.datumEvidence(),PATCH,3,original.provider()),
            new SourceBinding("high",original.sourceFingerprint(),q,original.datumEvidence(),PATCH,4,original.provider()),
            new SourceBinding("high",original.sourceFingerprint(),q,new DatumEvidence(VerticalDatum.EGM2008_METRES,URI.create("urn:changed-evidence"),original.datumEvidence().sha256()),PATCH,3,original.provider()),
            new SourceBinding("high",original.sourceFingerprint(),q,new DatumEvidence(VerticalDatum.EGM2008_METRES,original.datumEvidence().source(),hash("changed evidence bytes")),PATCH,3,original.provider()),
            new SourceBinding("high",original.sourceFingerprint(),q,original.datumEvidence(),WORLD,3,original.provider()),
            new SourceBinding("high",original.sourceFingerprint(),provenance("high",2),original.datumEvidence(),PATCH,3,original.provider()),
            new SourceBinding("high",original.sourceFingerprint(),new DatasetProvenance(q.id(),"fixture-v2",q.verticalDatum(),q.resolutionArcSeconds(),q.license(),q.source(),q.quality(),q.attribution()),original.datumEvidence(),PATCH,3,original.provider()));
        var base=new WorldIdentity(7,"earth-geography","surface-fill-v1","base-data-v1","actual-palette-v1");
        for(var v:variations){check(!p.fingerprint().equals(plan(v).fingerprint()),"generation-affecting field changes fingerprint");check(!p.deriveWorldIdentity(base).equals(plan(v).deriveWorldIdentity(base)),"changed composition changes saved identity");}
        check(!base.equals(p.deriveWorldIdentity(base)),"old save identity never reused");equal(7L,p.deriveWorldIdentity(base).seed(),"world seed retained");equal(base.blockPaletteVersion(),p.deriveWorldIdentity(base).blockPaletteVersion(),"actual palette retained");
        var alternateBase=new WorldIdentity(7,"earth-geography","surface-fill-v1","other-base-data","actual-palette-v1");check(!p.deriveWorldIdentity(base).equals(p.deriveWorldIdentity(alternateBase)),"base dataset identity contributes");
        var wrong=new SourceBinding("wrong",hash("wrong"),q,new DatumEvidence(VerticalDatum.EGM96_METRES,URI.create("urn:fixture:egm96"),hash("egm96")),PATCH,0,original.provider());
        fail(()->plan(original,wrong),IllegalArgumentException.class,"mixed datums rejected");
        fail(()->VerticalDatum.fromAuthorityCode("sea level"),IllegalArgumentException.class,"ambiguous datum rejected");
        fail(()->VerticalDatum.fromAuthorityCode("EPSG:5829"),IllegalArgumentException.class,"instantaneous water level unsupported");
        fail(()->new SourceBinding("bad",hash("bad"),q,null,PATCH,0,original.provider()),NullPointerException.class,"missing evidence rejected");
        fail(()->plan(original,original),IllegalArgumentException.class,"duplicate source IDs rejected");
    }
    static void failures(){
        AtomicInteger lowCalls=new AtomicInteger();var low=source("low",15,0,WORLD,1000,lowCalls);var high=source("high",1,0,PATCH,10,new AtomicInteger());
        var noData=withProvider(high,(points,token)->CompletableFuture.failedFuture(new MissingTerrainException("Synthetic no-data")));
        fail(()->new CompositeElevationProvider(plan(low,noData)).sample(List.of(HIGH),new CancellationToken()).join(),MissingTerrainException.class,"selected no-data does not fall back");equal(0,lowCalls.get(),"valid lower resolution was not queried after no-data");
        var wrongCount=withProvider(high,(points,token)->CompletableFuture.completedFuture(List.of()));
        fail(()->new CompositeElevationProvider(plan(wrongCount)).sample(List.of(HIGH),new CancellationToken()).join(),IllegalStateException.class,"wrong source count rejected");
        var wrongProvenance=withProvider(high,(points,token)->CompletableFuture.completedFuture(List.of(new ElevationSample(10,low.provenance()))));
        fail(()->new CompositeElevationProvider(plan(wrongProvenance)).sample(List.of(HIGH),new CancellationToken()).join(),IllegalStateException.class,"provenance mismatch rejected");
        var nullFuture=withProvider(high,(points,token)->null);fail(()->new CompositeElevationProvider(plan(nullFuture)).sample(List.of(HIGH),new CancellationToken()).join(),NullPointerException.class,"null source future rejected");
        var nullList=withProvider(high,(points,token)->CompletableFuture.completedFuture(null));fail(()->new CompositeElevationProvider(plan(nullList)).sample(List.of(HIGH),new CancellationToken()).join(),NullPointerException.class,"null source result list rejected");
        var nullSample=withProvider(high,(points,token)->CompletableFuture.completedFuture(Arrays.asList((ElevationSample)null)));fail(()->new CompositeElevationProvider(plan(nullSample)).sample(List.of(HIGH),new CancellationToken()).join(),NullPointerException.class,"null source sample rejected");
        var singleError=withProvider(high,(points,token)->{throw new AssertionError("Synthetic single source Error");});
        var errorProvider=new CompositeElevationProvider(plan(singleError));
        fail(()->errorProvider.sample(List.of(HIGH),new CancellationToken()).join(),AssertionError.class,"single source Error settles");equal(0,errorProvider.stats().activeBatches(),"single source Error releases permit");
        AtomicInteger calls=new AtomicInteger();var bounded=source("bounded",1,0,PATCH,10,calls);
        fail(()->new CompositeElevationProvider(plan(bounded)).sample(List.of(HIGH,LOW),new CancellationToken()).join(),MissingTerrainException.class,"uncovered batch fails");equal(0,calls.get(),"coverage planning precedes every source call");
    }
    static void orderAndAdmission(){
        var a=source("high",1,0,PATCH,10,new AtomicInteger());var b=source("low",15,0,WORLD,1000,new AtomicInteger());
        var ah=new CompletableFuture<List<ElevationSample>>();var bh=new CompletableFuture<List<ElevationSample>>();
        var provider=new CompositeElevationProvider(plan(withProvider(a,(points,token)->ah),withProvider(b,(points,token)->bh)),4,1);
        var result=provider.sample(List.of(HIGH,LOW,HIGH),new CancellationToken());
        bh.complete(List.of(new ElevationSample(1000,b.provenance())));check(!result.isDone(),"batch waits for every source");
        ah.complete(List.of(new ElevationSample(10,a.provenance()),new ElevationSample(10,a.provenance())));
        equal(List.of(10.0,1000.0,10.0),result.join().stream().map(ElevationSample::metres).toList(),"reverse completion preserves input order");
        equal(0,provider.stats().activeBatches(),"permit released after complete");
        fail(()->provider.sample(Collections.nCopies(5,HIGH),new CancellationToken()).join(),RejectedExecutionException.class,"oversized batch rejected");
        equal(List.of(),provider.sample(List.of(),new CancellationToken()).join(),"empty batch uses no source");
        var pending=new CompletableFuture<List<ElevationSample>>();
        var failing=withProvider(b,(points,token)->{throw new IllegalArgumentException("Synthetic dispatch failure");});
        var held=new CompositeElevationProvider(plan(withProvider(a,(points,token)->pending),failing),4,1);
        var failed=held.sample(List.of(HIGH,LOW),new CancellationToken());
        check(!failed.isDone(),"later synchronous failure waits for earlier live work");failed.cancel(false);
        equal(1,held.stats().activeBatches(),"caller cancellation retains admission");
        fail(()->held.sample(List.of(HIGH),new CancellationToken()).join(),RejectedExecutionException.class,"in-flight work bounded after cancellation");
        pending.complete(List.of(new ElevationSample(10,a.provenance())));equal(0,held.stats().activeBatches(),"barrier completion releases canceled view's admission");
        var beforeError=new CompletableFuture<List<ElevationSample>>();
        var errorProvider=withProvider(b,(points,token)->{throw new AssertionError("Synthetic provider Error");});
        var errors=new CompositeElevationProvider(plan(withProvider(a,(points,token)->beforeError),errorProvider),4,1);
        var errorResult=errors.sample(List.of(HIGH,LOW),new CancellationToken());
        check(!errorResult.isDone(),"provider Error still waits for earlier live source");
        equal(1,errors.stats().activeBatches(),"provider Error does not orphan accounting");
        beforeError.complete(List.of(new ElevationSample(10,a.provenance())));
        fail(()->errorResult.join(),AssertionError.class,"provider Error surfaced through settled future");
        equal(0,errors.stats().activeBatches(),"provider Error releases admission after full barrier");
    }
    static void cancellation(){
        var s=source("high",1,0,PATCH,10,new AtomicInteger());var pending=new CompletableFuture<List<ElevationSample>>();
        var provider=new CompositeElevationProvider(plan(withProvider(s,(points,token)->pending)),4,1);
        CubeProvider wrapper=(position,token)->provider.sample(List.of(HIGH),token).thenApply(samples->ImmutableCube.uniform(1));
        try(var loader=new BoundedCubeLoader(wrapper,Runnable::run,1,0,1)) {
            var lease=loader.request(new CubePos(0,0,0));lease.close();equal(1,provider.stats().activeBatches(),"token cancellation retains running-source admission");
            pending.complete(List.of(new ElevationSample(10,s.provenance())));
            equal(0,provider.stats().activeBatches(),"canceled source batch settles");equal(1L,provider.stats().failedBatches(),"canceled output not published");check(lease.future().isCancelled(),"canceled cube result stays canceled");
        }
    }
    static void boundedShape(){
        var s=source("high",1,0,PATCH,10,new AtomicInteger());
        fail(()->new CompositionPlan(VerticalDatum.EGM2008_METRES,Collections.nCopies(33,s)),IllegalArgumentException.class,"source limit enforced");
        var exactSources=new ArrayList<SourceBinding>();for(int i=0;i<32;i++)exactSources.add(source("s"+i,1,0,PATCH,10,new AtomicInteger()));
        equal(32,new CompositionPlan(VerticalDatum.EGM2008_METRES,exactSources).sources().size(),"exact source limit accepted");
        var rectangles=new ArrayList<SafeCoverage.Rectangle>();for(int i=0;i<1024;i++)rectangles.add(new SafeCoverage.Rectangle(i%32-16,i/32-16,i/32-15,1));
        equal(1024,new SafeCoverage(rectangles).rectangles().size(),"exact per-source rectangle limit accepted");
        var tooMany=new ArrayList<>(rectangles);tooMany.add(new SafeCoverage.Rectangle(80,50,51,1));fail(()->new SafeCoverage(tooMany),IllegalArgumentException.class,"per-source rectangle limit enforced");
        var firstCoverage=new SafeCoverage(rectangles.subList(0,512));var secondCoverage=new SafeCoverage(rectangles.subList(512,1024));
        var first=source("first",1,0,firstCoverage,10,new AtomicInteger());var second=source("second",1,0,secondCoverage,10,new AtomicInteger());
        equal(2,plan(first,second).sources().size(),"exact aggregate rectangle limit accepted");
        var all=source("all",1,0,new SafeCoverage(rectangles),10,new AtomicInteger());fail(()->plan(all,first),IllegalArgumentException.class,"aggregate rectangle budget enforced");
        var pending=new CompletableFuture<List<ElevationSample>>();var provider=new CompositeElevationProvider(plan(withProvider(s,(points,token)->pending)),256,4);
        var futures=new ArrayList<CompletableFuture<List<ElevationSample>>>();
        for(int i=0;i<4;i++)futures.add(provider.sample(Collections.nCopies(256,HIGH),new CancellationToken()));
        for(int i=0;i<36;i++)fail(()->provider.sample(List.of(HIGH),new CancellationToken()).join(),RejectedExecutionException.class,"burst admission rejected");
        equal(4,provider.stats().activeBatches(),"burst active memory bounded");equal(1024L,provider.stats().peakPlannedPointReferences(),"logical input slots measured");equal(4L,provider.stats().sourceCalls(),"grouped query count bounded");
        pending.complete(Collections.nCopies(256,new ElevationSample(10,s.provenance())));CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();equal(0,provider.stats().activeBatches(),"burst fully drains");
    }
    static void concurrency()throws Exception {
        var fixture=source("high",1,0,PATCH,10,new AtomicInteger());
        var pending=new ConcurrentLinkedQueue<CompletableFuture<List<ElevationSample>>>();
        var binding=withProvider(fixture,(points,token)->{var future=new CompletableFuture<List<ElevationSample>>();pending.add(future);return future;});
        var provider=new CompositeElevationProvider(plan(binding),256,4);
        try(var executor=Executors.newFixedThreadPool(16)) {
            CountDownLatch start=new CountDownLatch(1);var submitted=new ArrayList<Future<CompletableFuture<List<ElevationSample>>>>();
            for(int i=0;i<64;i++)submitted.add(executor.submit(()->{start.await();return provider.sample(List.of(HIGH),new CancellationToken());}));
            start.countDown();var results=new ArrayList<CompletableFuture<List<ElevationSample>>>();for(var f:submitted)results.add(f.get(5,TimeUnit.SECONDS));
            equal(4,provider.stats().activeBatches(),"concurrent admission admits exactly four");equal(60L,provider.stats().rejectedBatches(),"concurrent excess rejected");equal(4,pending.size(),"concurrent dispatch bounded");
            var racing=new ArrayList<Future<?>>();CountDownLatch finish=new CountDownLatch(1);
            for(var result:results)if(!result.isDone())racing.add(executor.submit(()->{try{finish.await();result.cancel(false);}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
            for(var future:pending)racing.add(executor.submit(()->{try{finish.await();future.complete(List.of(new ElevationSample(10,fixture.provenance())));}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
            finish.countDown();for(var task:racing)task.get(5,TimeUnit.SECONDS);
            equal(0,provider.stats().activeBatches(),"cancellation/completion races fully drain admission");equal(4L,provider.stats().completedBatches(),"private work settles independently of public cancellation race");
        }
    }
    static void realPacks(Path geography)throws Exception {
        Path noaa=geography.resolve("data/noaa-etopo2022-proof/world.properties"),copernicus=geography.resolve("data/copernicus-everest-proof/world.properties");
        Path noaaEvidence=noaa.getParent().resolve("provenance/service-raster-info.json"),copernicusEvidence=copernicus.getParent().resolve("provenance/source-metadata.xml");
        String noaaSha="67301bd97c80fb3658822c249b0ec8c492932eaecf531bb1aeae3fce3e84a104",copernicusSha="dde5f33704d412bc5a8e0e06e211024c097f9e5dbf3585040a29cf13f6475b2a";
        equal(noaaSha,WorldManifest.sha256(Files.readAllBytes(noaaEvidence)),"reviewed NOAA EPSG3855 evidence remains pinned");
        equal(copernicusSha,WorldManifest.sha256(Files.readAllBytes(copernicusEvidence)),"reviewed Copernicus EPSG3855 evidence remains pinned");
        // These exact evidence hashes were independently validated by the import tests. No free-text datum guessing occurs here.
        OfflineTerrain n=OfflineTerrain.load(noaa),c=OfflineTerrain.load(copernicus);
        SourceBinding ns=realBinding("noaa-etopo",n,new DatumEvidence(VerticalDatum.EGM2008_METRES,URI.create("https://gis.ngdc.noaa.gov/arcgis/rest/services/DEM_mosaics/DEM_all/ImageServer/2527/info"),noaaSha));
        SourceBinding cs=realBinding("copernicus-everest",c,new DatumEvidence(VerticalDatum.EGM2008_METRES,URI.create("https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_N27_00_E086_00_DEM/Copernicus_DSM_10_N27_00_E086_00.xml"),copernicusSha));
        CompositionPlan p=plan(ns,cs);var provider=new CompositeElevationProvider(p);
        var positions=List.of(new GeoPoint(27.9881,86.925),new GeoPoint(27.90,86.90),new GeoPoint(37.7749,-122.4194));
        var results=provider.sample(positions,new CancellationToken()).join();
        near(8720.444140624944,results.get(0).metres(),1e-7,"actual Copernicus selected at Everest");
        equal(c.manifest().provenance(),results.get(0).provenance(),"actual DSM provenance kept");
        near(n.elevationMetres(positions.get(1)),results.get(1).metres(),0,"outside Copernicus safe footprint uses actual NOAA");
        equal(n.manifest().provenance(),results.get(2).provenance(),"disconnected NOAA SF region retained");
        fail(()->provider.sample(List.of(new GeoPoint(0,0)),new CancellationToken()).join(),MissingTerrainException.class,"composition never claims global installed coverage");
        equal(2L,provider.stats().sourceCalls(),"three real points group to two local provider calls");
        System.out.printf(Locale.ROOT,"Actual composed Everest: %.6f m, source=%s; composition=%s%n",results.getFirst().metres(),p.select(positions.getFirst()).id(),p.fingerprint());
    }
    static SourceBinding realBinding(String id,OfflineTerrain terrain,DatumEvidence evidence){
        ElevationProvider provider=(points,token)->{try{var values=new ArrayList<ElevationSample>();for(GeoPoint point:points){token.throwIfCancelled();values.add(new ElevationSample(terrain.elevationMetres(point),terrain.manifest().provenance()));}return CompletableFuture.completedFuture(List.copyOf(values));}catch(RuntimeException e){return CompletableFuture.failedFuture(e);}};
        return new SourceBinding(id,terrain.manifest().fingerprint(),terrain.manifest().provenance(),evidence,SafeCoverage.fromTiles(terrain.manifest().tiles()),0,provider);
    }
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void equal(Object expected,Object actual,String label){check(Objects.equals(expected,actual),label+": expected "+expected+", actual "+actual);}
    static void near(double expected,double actual,double tolerance,String label){check(Math.abs(expected-actual)<=tolerance,label+": expected "+expected+", actual "+actual);}
    @FunctionalInterface interface Attempt{void run()throws Exception;}
    static void fail(Attempt action,Class<? extends Throwable> expected,String label){checks++;try{action.run();}catch(Throwable t){while(t instanceof CompletionException&&t.getCause()!=null)t=t.getCause();if(expected.isInstance(t))return;throw new AssertionError(label,t);}throw new AssertionError(label+": unexpectedly succeeded");}
}
