package io.earthplatform.geography.variety;

import io.earthplatform.core.*;
import io.earthplatform.geography.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic cosmetic-surface tests plus accepted, locally cached real Copernicus and NOAA proof packs. */
public final class TerrainVarietyTest {
    private static int checks;
    private TerrainVarietyTest() {}
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args.length==0?"geography":args[0]);
        profiles(); syntheticSurface(); continuity(); realPacks(root); cancellationAndBudgets(root);
        System.out.println("TerrainVarietyTest: "+checks+" checks passed (synthetic microdetail + real accepted packs; no network)");
    }
    private static MicrodetailProfile region(GeoPoint centre,long seed) {
        return new MicrodetailProfile(seed,1.25,List.of(new MicrodetailProfile.Region(centre,10_000,1000)),List.of());
    }
    private static void profiles() {
        var a=new MicrodetailProfile.Region(new GeoPoint(20,30),1000,100);
        var b=new MicrodetailProfile.Region(new GeoPoint(-20,170),2000,300);
        var p=new MicrodetailProfile.PreserveZone(new GeoPoint(20,30),100,50);
        var x=new MicrodetailProfile(1,1,List.of(a,b),List.of(p));
        var y=new MicrodetailProfile(1,1,List.of(b,a,a),List.of(p,p));
        equal(x.fingerprint(),y.fingerprint(),"canonical region order/deduplication");
        check(!x.fingerprint().equals(new MicrodetailProfile(2,1,List.of(a,b),List.of(p)).fingerprint()),"seed changes fingerprint");
        check(!x.fingerprint().equals(new MicrodetailProfile(1,.5,List.of(a,b),List.of(p)).fingerprint()),"amplitude changes fingerprint");
        check(!x.fingerprint().equals(new MicrodetailProfile(1,1,List.of(a),List.of(p)).fingerprint()),"coverage changes fingerprint");
        check(!x.fingerprint().equals(new MicrodetailProfile(1,1,List.of(a,b),List.of()).fingerprint()),"protection changes fingerprint");
        fail(()->new MicrodetailProfile(1,2.01,List.of(a),List.of()),IllegalArgumentException.class,"hard amplitude cap");
        fail(()->new MicrodetailProfile(1,Double.NaN,List.of(a),List.of()),IllegalArgumentException.class,"nonfinite amplitude");
        fail(()->new MicrodetailProfile(1,1,List.of(),List.of()),IllegalArgumentException.class,"explicit region required");
        fail(()->new MicrodetailProfile(1,1,Collections.nCopies(17,a),List.of()),IllegalArgumentException.class,"bounded region count");
        fail(()->new MicrodetailProfile.Region(new GeoPoint(0,0),10,11),IllegalArgumentException.class,"fade bounds");
        fail(()->new MicrodetailProfile.PreserveZone(new GeoPoint(0,0),-1,10),IllegalArgumentException.class,"positive protection");
        var mutable=new ArrayList<>(List.of(a)); var copied=new MicrodetailProfile(1,1,mutable,List.of()); mutable.clear();
        equal(1,copied.regions().size(),"defensive list snapshot");
        fail(()->copied.regions().clear(),UnsupportedOperationException.class,"immutable regions");
    }
    private static void syntheticSurface() {
        Random random=new Random(8641192);
        var origin=new GeoPoint(0,0); var p=region(origin,73);
        double sum=0,max=0; int nonzero=0;
        for(int i=0;i<10_000;i++) {
            var point=new GeoPoint((random.nextDouble()-.5)*.1,(random.nextDouble()-.5)*.1);
            double delta=SurfaceMicrodetail.displacement(point,100,15,p);
            check(Math.abs(delta)<=1.25,"bounded macroshape displacement");
            near(delta,SurfaceMicrodetail.displacement(point,100,15,p),0,"bit-stable repeated sample");
            near(0,SurfaceMicrodetail.displacement(point,100,15,MicrodetailProfile.disabled()),0,"disabled exact zero");
            near(0,SurfaceMicrodetail.displacement(point,-11000,15,p),0,"bathymetry preserved");
            near(0,SurfaceMicrodetail.displacement(point,32,15,p),0,"shore neighbourhood preserved");
            sum+=delta; max=Math.max(max,Math.abs(delta)); if(delta!=0)nonzero++;
        }
        check(nonzero==10_000,"active region has detail"); check(max>.25,"nontrivial bounded detail");
        check(Math.abs(sum/10_000)<.08,"sampled synthetic mean has no large systematic rise");
        double coarse=SurfaceMicrodetail.displacement(origin,100,15,p);
        double fine=SurfaceMicrodetail.displacement(origin,100,1,p);
        check(coarse!=fine,"source resolution selects detail scale");
        check(fine!=SurfaceMicrodetail.displacement(origin,100,1,region(origin,74)),"seed selects distinct detail");
        near(0,SurfaceMicrodetail.displacement(new GeoPoint(10,0),100,15,p),0,"outside region untouched");
        var protectedProfile=new MicrodetailProfile(1,1,List.of(new MicrodetailProfile.Region(origin,1000,100)),
            List.of(new MicrodetailProfile.PreserveZone(origin,256,128)));
        near(0,SurfaceMicrodetail.displacement(origin,8849,1,protectedProfile),0,"exact landmark unchanged");
        near(0,SurfaceMicrodetail.displacement(new GeoPoint(.001,0),8849,1,protectedProfile),0,"landmark neighbourhood unchanged");
        fail(()->SurfaceMicrodetail.displacement(origin,Double.NaN,1,p),IllegalArgumentException.class,"nonfinite elevation rejected");
        fail(()->SurfaceMicrodetail.displacement(origin,10,0,p),IllegalArgumentException.class,"missing source resolution rejected");
    }
    private static void continuity() {
        var crossing=region(new GeoPoint(0,180),99);
        near(SurfaceMicrodetail.displacement(new GeoPoint(0,180),100,1,crossing),
             SurfaceMicrodetail.displacement(new GeoPoint(0,-180),100,1,crossing),0,"canonical antimeridian exact");
        near(SurfaceMicrodetail.displacement(new GeoPoint(0,180-1e-9),100,1,crossing),
             SurfaceMicrodetail.displacement(new GeoPoint(0,-180+1e-9),100,1,crossing),.0002,"two-sided antimeridian continuity");
        var north=region(new GeoPoint(90,0),13);
        near(SurfaceMicrodetail.displacement(new GeoPoint(90,0),100,1,north),
             SurfaceMicrodetail.displacement(new GeoPoint(90,167),100,1,north),0,"pole longitude independent");
        var p=region(new GeoPoint(0,0),92);
        var projection=new EquirectangularProjection(new GeoPoint(0,0),0);
        double worst=0;
        for(int x=-128;x<128;x++) for(int z=-128;z<128;z+=16) {
            double a=SurfaceMicrodetail.displacement(projection.blockCentre(x,z),100,1,p);
            double b=SurfaceMicrodetail.displacement(projection.blockCentre(x+1,z),100,1,p);
            worst=Math.max(worst,Math.abs(a-b));
            near(a,SurfaceMicrodetail.displacement(projection.toGeographic(x+.5,z+.5),100,1,p),0,"coordinate access independent of cube route");
        }
        check(worst<.8,"adjacent voxels do not receive independent noise spikes");
        var narrow=new MicrodetailProfile(1,1,List.of(new MicrodetailProfile.Region(new GeoPoint(0,0),1000,200)),List.of());
        double edge=StrictMath.toDegrees(1000/EquirectangularProjection.RADIUS_METRES);
        near(0,SurfaceMicrodetail.displacement(new GeoPoint(0,edge),100,1,narrow),1e-30,"support edge zero");
        check(Math.abs(SurfaceMicrodetail.displacement(new GeoPoint(0,edge-1e-8),100,1,narrow))<1e-12,"smooth approach to support edge");
        near(0,SurfaceMicrodetail.displacement(new GeoPoint(0,edge+1e-8),100,1,narrow),0,"outside support remains zero");
        double below=SurfaceMicrodetail.displacement(new GeoPoint(0,0),32,1,narrow);
        double above=SurfaceMicrodetail.displacement(new GeoPoint(0,0),32+1e-5,1,narrow);
        near(below,above,1e-16,"water-protection transition continuous");
    }
    private static void realPacks(Path root) throws Exception {
        var base=OfflineTerrain.load(root.resolve("data/copernicus-everest-proof/world.properties"));
        var off=new VariedOfflineTerrain(base,MicrodetailProfile.disabled(),MaterialPalette.DEFAULT);
        var on=new VariedOfflineTerrain(base,MicrodetailProfile.everestExample(81),MaterialPalette.DEFAULT);
        equal(base.manifest().worldIdentity(),off.worldIdentity(base.manifest().paletteVersion()),"disabled fixture preserves saved identity");
        check(!on.worldIdentity("runtime-palette-v2").datasetVersion().equals(base.manifest().fingerprint()),"enabled detail changes saved data identity");
        equal("runtime-palette-v2",on.worldIdentity("runtime-palette-v2").blockPaletteVersion(),"actual palette remains pinned");
        check(on.worldIdentity("p").generatorId().contains(MicrodetailProfile.VERSION),"procedural version explicit in identity");
        var summit=new GeoPoint(27.9881,86.925); var sample=on.sample(summit);
        near(base.elevationMetres(summit),sample.generatedMetres(),0,"accepted summit scalar preserved");
        near(0,sample.proceduralDeltaMetres(),0,"no synthetic summit height");
        equal(base.manifest().provenance(),sample.sourceProvenance(),"base source provenance retained separately");
        var overlapping=new VariedOfflineTerrain(base,new MicrodetailProfile(19,.75,
            List.of(new MicrodetailProfile.Region(summit,1000,200)),
            List.of(new MicrodetailProfile.PreserveZone(summit,256,128))),MaterialPalette.DEFAULT);
        for(int x=-128;x<=128;x+=16) for(int z=-128;z<=128;z+=16) {
            var protectedSample=overlapping.sample(x,z);
            near(0,protectedSample.proceduralDeltaMetres(),0,"real source overlapping summit protection exact");
            near(base.elevationMetres(x,z),protectedSample.generatedMetres(),0,"real protected neighbourhood unchanged");
        }
        check(overlapping.sample(500,0).proceduralDeltaMetres()!=0,"real detail resumes beyond landmark fade");
        int modified=0; double maxDelta=0;
        for(int x=600;x<856;x++) for(int z=1100;z<1356;z++) {
            var s=on.sample(x,z); var unchanged=off.sample(x,z);
            near(base.elevationMetres(x,z),s.sourceMetres(),0,"source scalar not replaced");
            near(unchanged.sourceMetres(),unchanged.generatedMetres(),0,"disabled scalar exact");
            check(Math.abs(s.proceduralDeltaMetres())<=.75,"real fixture strict displacement cap");
            maxDelta=Math.max(maxDelta,Math.abs(s.proceduralDeltaMetres()));
            if(on.surfaceBlockY(x,z)!=off.surfaceBlockY(x,z)) modified++;
        }
        check(modified>0 && modified<65536,"optional detail changes some 1 m voxels only");
        check(maxDelta>.1,"real regional detail has measurable but bounded effect");
        for(var pos:List.of(CubePos.fromBlock(0,8700,0),CubePos.fromBlock(640,7900,1152),new CubePos(-1,0,-1),new CubePos(0,10000,0))) {
            equal(Arrays.toString(base.cube(pos,MaterialPalette.DEFAULT).toArray()),Arrays.toString(off.cube(pos,new CancellationToken()).toArray()),"disabled cube byte-identical to baseline");
            equal(Arrays.toString(on.cube(pos,new CancellationToken()).toArray()),Arrays.toString(on.load(pos,new CancellationToken()).join().toArray()),"synchronous and provider paths agree");
        }
        var noaa=OfflineTerrain.load(root.resolve("data/noaa-etopo2022-proof/world.properties"));
        var challenger=new GeoPoint(11.3733,142.5917);
        var deep=new VariedOfflineTerrain(noaa,region(challenger,7),MaterialPalette.DEFAULT);
        near(noaa.elevationMetres(challenger),deep.sample(challenger).generatedMetres(),0,"actual Challenger depth untouched");
        fail(()->on.sample(new GeoPoint(0,0)),MissingTerrainException.class,"missing data never filled with random terrain");
    }
    private static void cancellationAndBudgets(Path root) throws Exception {
        var base=OfflineTerrain.load(root.resolve("data/copernicus-everest-proof/world.properties"));
        var provider=new VariedOfflineTerrain(base,MicrodetailProfile.everestExample(1),MaterialPalette.DEFAULT);
        AtomicReference<CancellationToken> captured=new AtomicReference<>();
        var unfinished=new CompletableFuture<ImmutableCube>();
        try(var capture=new BoundedCubeLoader((p,t)->{captured.set(t);return unfinished;},Runnable::run,1,0,1)) {
            var lease=capture.request(new CubePos(0,0,0)); lease.close();
            check(captured.get().isCancelled(),"lease owns cooperative cancellation");
            fail(()->provider.load(new CubePos(0,0,0),captured.get()).join(),CancellationException.class,"cancelled fixture stops before work");
            unfinished.complete(ImmutableCube.uniform(0));
        }
        var tasks=new ArrayList<Runnable>();
        try(var loader=new BoundedCubeLoader(provider,tasks::add,2,3,2)) {
            var leases=new ArrayList<BoundedCubeLoader.Lease>();
            for(int i=0;i<5;i++)leases.add(loader.request(new CubePos(i,540,0)));
            equal(2,loader.stats().running(),"bounded active work");equal(3,loader.stats().queued(),"bounded queue");
            fail(()->loader.request(new CubePos(5,540,0)),RejectedExecutionException.class,"backpressure rejects overflow");
            leases.get(0).close();leases.get(4).close();
            while(!tasks.isEmpty())tasks.removeFirst().run();
            check(leases.get(0).future().isCancelled(),"cancelled active lease never publishes");
            check(leases.get(4).future().isCancelled(),"cancelled queued lease never runs");
            for(int i=1;i<4;i++)check(leases.get(i).future().isDone()&&!leases.get(i).future().isCompletedExceptionally(),"remaining work completes");
            equal(0,loader.stats().running(),"all work fully settled");equal(0,loader.stats().queued(),"queue drained");
        }
    }
    private static void check(boolean value,String label) { checks++;if(!value)throw new AssertionError(label); }
    private static void equal(Object a,Object b,String label) { check(Objects.equals(a,b),label+": "+a+" != "+b); }
    private static void near(double a,double b,double tolerance,String label) { check(Math.abs(a-b)<=tolerance,label+": "+a+" != "+b); }
    private static void fail(Runnable r,Class<? extends Throwable> type,String label) {
        try {r.run();} catch(Throwable t) {while(t.getCause()!=null)t=t.getCause();check(type.isInstance(t),label+": "+t);return;}
        throw new AssertionError(label+": did not fail");
    }
}
