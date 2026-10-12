package io.earthplatform.geography.earth;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.*;
import io.earthplatform.geography.landcover.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Source/material/anchor/no-fallback tests only. Far tessellation and native cap census live separately. */
public final class EarthAlpineDefinitionTest {
    private static long checks;
    private EarthAlpineDefinitionTest() {}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable op){try{op.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException|MissingTerrainException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),out=root.resolve("earth-alpine-results");Files.createDirectories(out);
        var d=EarthAlpineDefinition.load(root);var second=EarthAlpineDefinition.load(root);
        check(d.identity().equals(EarthAlpineDefinition.DEFINITION_IDENTITY),"definition pin");
        check(d.identity().equals(second.identity()),"repeat identity");
        check(d.sourceIdentity().equals(EarthAlpineDefinition.SOURCE_IDENTITY),"source pin");
        check(d.detailIdentity().equals(EarthAlpineDefinition.DETAIL_IDENTITY),"detail pin");
        check(d.materialPolicyIdentity().equals(EarthAlpineDefinition.MATERIAL_POLICY_IDENTITY),"material pin");
        check(d.anchor().equals(EarthAlpineDefinition.ANCHOR),"pure anchor");
        check(d.envelope().equals(EarthAlpineDefinition.ENVELOPE),"pure envelope");
        d.validatePreparedGround(d.anchor().x(),d.anchor().surfaceY(),d.anchor().z());checks++;
        rejects(()->d.validatePreparedGround(d.anchor().x(),d.anchor().surfaceY()+1,d.anchor().z()));
        var raw=OfflineTerrain.load(root.resolve("data/copernicus-everest-expanded-030/world.properties"));
        var env=d.envelope();double maxDelta=0,min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;int changed=0,points=0;
        var materialCounts=new EnumMap<EarthAlpineDefinition.Material,Integer>(EarthAlpineDefinition.Material.class);
        try(var grid=Files.newBufferedWriter(out.resolve("source-sample-grid.tsv"))){
            grid.write("x\tz\tlatitude\tlongitude\tsource_m\tprocedural_delta_m\tgenerated_m\tsurface_y\tclass_code\ttop\treceipt\n");
            for(long z=env.minColumnZ()+64;z<env.maxColumnZExclusive();z+=128)for(long x=env.minColumnX()+64;x<env.maxColumnXExclusive();x+=128){
                var c=d.column(x,z);var p=d.projection().blockCentre(x,z);points++;
                check(c.equals(second.column(x,z)),"repeat exact column");
                check(Double.doubleToLongBits(c.sourceMetres())==Double.doubleToLongBits(raw.elevationMetres(p)),"source unchanged");
                check(Math.abs(c.proceduralDeltaMetres())<=2,"detail bound");
                check(c.generatedMetres()==c.sourceMetres()+c.proceduralDeltaMetres(),"source/detail separation");
                check(c.surfaceY()==(long)Math.floor(c.generatedMetres()),"height true metres floor");
                check(c.sourceClassification().status()==LandCoverSample.Status.MAPPED,"measured class required");
                check(c.materialAt(c.surfaceY()+1)==EarthAlpineDefinition.Material.AIR,"above air");
                check(c.materialAt(c.surfaceY())==c.top(),"top layer");
                check(c.materialAt(c.surfaceY()-1)==c.subsurface()&&c.materialAt(c.surfaceY()-3)==c.subsurface(),"three subsurface layers");
                check(c.materialAt(c.surfaceY()-4)==EarthAlpineDefinition.Material.STONE,"deep stone");
                check((c.top().argb()>>>24)==255,"opaque palette");
                if(c.top()==EarthAlpineDefinition.Material.SNOW_BLOCK)check(c.sourceClassification().classification()==LandCoverClass.SNOW_AND_ICE,"snow needs source70");
                if(c.materialReceipt()==EarthAlpineDefinition.MaterialReceipt.UNRESOLVED_MAPPED_CLASS_CONSERVATIVE_ROCK){
                    check(c.top()!=EarthAlpineDefinition.Material.SNOW_BLOCK,"unresolved not invented snow");
                    check(c.proceduralDeltaMetres()==0,"no alpine detail on unresolved class");
                }
                maxDelta=Math.max(maxDelta,Math.abs(c.proceduralDeltaMetres()));min=Math.min(min,c.sourceMetres());max=Math.max(max,c.sourceMetres());
                if(c.surfaceY()!=(long)Math.floor(c.sourceMetres()))changed++;
                materialCounts.merge(c.top(),1,Integer::sum);
                grid.write(x+"\t"+z+"\t"+p.latitude()+"\t"+p.longitude()+"\t"+c.sourceMetres()+"\t"+c.proceduralDeltaMetres()+"\t"+c.generatedMetres()+"\t"+c.surfaceY()+"\t"+c.sourceClassification().classification().code()+"\t"+c.top()+"\t"+c.materialReceipt()+"\n");
            }
        }
        check(changed>0,"new region actually has explicit procedural detail");
        var a=d.anchor();
        for(long z=a.z()-1;z<=a.z()+1;z++)for(long x=a.x()-1;x<=a.x()+1;x++){
            var c=d.column(x,z);check(c.surfaceY()==a.surfaceY(),"natural 3x3 level support");
            for(long y=(long)a.feetY();y<=(long)a.feetY()+2;y++)check(c.materialAt(y)==EarthAlpineDefinition.Material.AIR,"clear body");
        }
        for(var b:List.of(a.digTarget(),a.placementTarget()))for(long v:new long[]{b.x(),b.y(),b.z()})check(CubePos.local(v)>=1&&CubePos.local(v)<=14,"interior edit axes");
        check(d.materialAt(a.digTarget().x(),a.digTarget().y(),a.digTarget().z())!=EarthAlpineDefinition.Material.AIR,"dig solid");
        check(d.materialAt(a.placementTarget().x(),a.placementTarget().y(),a.placementTarget().z())==EarthAlpineDefinition.Material.AIR,"place air");
        check(d.materialAt(a.placementTarget().x(),a.placementTarget().y()-1,a.placementTarget().z())!=EarthAlpineDefinition.Material.AIR,"place supported");
        for(long z:new long[]{env.minColumnZ(),env.maxColumnZExclusive()-128})for(long x:new long[]{env.minColumnX(),env.maxColumnXExclusive()-128})check(d.knownFootprint(x,z,128),"boundary halo supported");
        check(!d.knownFootprint(env.minColumnX()-1,env.minColumnZ(),1),"unknown boundary rejects");
        check(!d.knownFootprint(Long.MAX_VALUE,0,2),"addition overflow fails closed");
        check(!d.knownFootprint(Long.MIN_VALUE,0,Long.MAX_VALUE,1),"subtraction overflow fails closed");
        rejects(()->d.knownFootprint(a.x(),a.z(),129));rejects(()->d.knownFootprint(a.x(),a.z(),0));
        rejects(()->d.column(Long.MIN_VALUE,0));rejects(()->d.column(a.x(),Long.MAX_VALUE));
        rejects(()->d.materialAt(a.x(),EarthAlpineDefinition.MIN_BLOCK_Y-1,a.z()));
        rejects(()->d.materialAt(a.x(),EarthAlpineDefinition.MAX_BLOCK_Y_EXCLUSIVE,a.z()));
        check(d.detailProfile().maximumAmplitudeMetres()==2,"new region amplitude");
        var peak=d.projection().toProjected(new GeoPoint(27.9881,86.925));
        check(d.column((long)Math.floor(peak.x()),(long)Math.floor(peak.z())).proceduralDeltaMetres()==0,"summit preserve zone");
        var expected=d.column(a.x(),a.z());
        try(var pool=Executors.newFixedThreadPool(4)){
            var jobs=new ArrayList<Callable<Boolean>>();for(int n=0;n<16;n++)jobs.add(()->{for(int i=0;i<64;i++)if(!d.column(a.x(),a.z()).equals(expected))return false;return true;});
            for(var f:pool.invokeAll(jobs))check(f.get(),"immutable parallel sampling");
        }
        check(d.sourceClassCounts().equals(Map.of(LandCoverClass.BARE_SPARSE_VEGETATION,5528L,LandCoverClass.SNOW_AND_ICE,243444L,LandCoverClass.PERMANENT_WATER,528L)),"exact original source class census");
        // Preparation owns I/O. A definition still samples after all files used to create it are removed.
        Path isolated=Files.createTempDirectory("earth-alpine-no-io-");
        var relativeFiles=List.of("data/copernicus-everest-expanded-030/world.properties","data/copernicus-everest-expanded-030/everest-expanded.egp",
                "data/worldcover-2021-proof/landcover.properties","data/worldcover-2021-proof/everest/landcover.elc","data/worldcover-2021-proof/marin-headlands/landcover.elc");
        try {
            for(String relative:relativeFiles){Path destination=isolated.resolve(relative);Files.createDirectories(destination.getParent());Files.copy(root.resolve(relative),destination);}
            var prepared=EarthAlpineDefinition.load(isolated);
            Path manifest=isolated.resolve(relativeFiles.getFirst());
            Files.writeString(manifest,Files.readString(manifest).replace("world.seed=20221009","world.seed=20221010"));
            try{EarthAlpineDefinition.load(isolated);throw new AssertionError("Changed source seed must fail preparation");}catch(java.io.IOException expectedFailure){checks++;}
            for(String relative:relativeFiles)Files.delete(isolated.resolve(relative));
            check(prepared.column(a.x(),a.z()).equals(expected),"sampling remains available after setup files are removed");
            check(prepared.knownFootprint(a.x(),a.z(),128),"coverage proof has no runtime filesystem dependency");
        } finally {
            try(var paths=Files.walk(isolated)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
        var summit=d.projection().toProjected(new GeoPoint(27.989166666666666,86.92555555555556));
        double distance=StrictMath.hypot(summit.x()-a.centreX(),summit.z()-a.centreZ()),minimumClearance=Double.POSITIVE_INFINITY,blockedRange=0;
        for(int range=1;range<distance-40;range++){
            double t=range/distance;
            long x=(long)StrictMath.floor(a.centreX()+(summit.x()-a.centreX())*t),z=(long)StrictMath.floor(a.centreZ()+(summit.z()-a.centreZ())*t);
            double rayY=a.feetY()+1.62+(8737.7880859375-a.feetY()-1.62)*t;
            double clearance=rayY-(d.column(x,z).surfaceY()+1);
            if(clearance<minimumClearance){minimumClearance=clearance;blockedRange=range;}
        }
        System.out.println("SOURCE_PEAK_SIGHTLINE distance="+distance+" minimumClearance="+minimumClearance+" atRange="+blockedRange+" final40mExcluded=true; real intervening ridge hides summit; no shelf/terrain adjustment");
        System.out.println("EarthAlpineDefinitionTest PASS checks="+checks+" gridPoints="+points+" gridChangedColumns="+changed+" maxAbsProceduralDelta="+maxDelta+" sampledSourceMin="+min+" sampledSourceMax="+max+" materials="+materialCounts);
        System.out.println("ANCHOR "+a);System.out.println("ENVELOPE "+env);System.out.println("IDENTITY "+d.identity());
    }
}
