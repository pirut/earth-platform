package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import io.earthplatform.minecraft.lod.*;
import io.earthplatform.minecraft.network.*;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.scene.BodyNativeCompletionFixture;
import io.earthplatform.minecraft.visual.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;

/** Real replica/admission/snapshots/source/equality. Native GPU receipts are explicitly modeled in tests. */
public final class DistanceBodyTestFixture implements AutoCloseable {
    public static final String HASH="0123456789abcdef".repeat(4);
    public static final CubePos PIN=CubePos.fromBlock(EarthAlpineDefinition.ANCHOR_X,EarthAlpineDefinition.GROUND_Y,EarthAlpineDefinition.ANCHOR_Z);
    static final UUID STORE=new UUID(1,2);
    private final CubePos pin;
    private final io.earthplatform.minecraft.scene.SceneSurfaceSampler sampler;
    public final EnginePaletteContext palette;
    public final WorldIdentity world;
    public final NativeCubeCodec codec;
    public final RollingGateReplica replica;
    public final RenderPublicationGuard guard;
    public final AtomicBoolean live=new AtomicBoolean(true);
    public final java.util.concurrent.atomic.AtomicInteger fullResourceChecks=new java.util.concurrent.atomic.AtomicInteger();
    final RollingDistanceBodyBinding.Owner owner;
    RollingDistanceBodyBinding.Binding binding;
    public EarthDistanceSurfacePlan.Source source;
    public EarthReplacementCapture capture;
    public EarthDistanceSurfacePlan plan;
    public NativeVisualStamp stamp;
    public DhReplacementInput replacement;
    public NativeVisualPublication visual;
    public RollingDistanceBodyBinding.NativeJoined joined;
    StageMetadata metadata;
    List<CubeLease> leases=List.of();
    CubePos center;
    long actorGeneration,nextLease=1,publication;
    public DistanceBodyTestFixture(){this(()->0L,null);}
    DistanceBodyTestFixture(LongSupplier clock){this(clock,null);}
    public static DistanceBodyTestFixture alpine(){return new DistanceBodyTestFixture(()->0L,Alpine.DEFINITION);}
    public CubePos pin(){return pin;}
    public io.earthplatform.minecraft.scene.SceneSurfaceSampler sampler(){return sampler;}
    private static final class Alpine {
        static final EarthAlpineDefinition DEFINITION=load();
        static final EarthDistanceSurfacePlan.Source SOURCE=EarthDistanceSurfacePlan.Source.prepare(DEFINITION,2,0,NativeDistanceLimits.sourceLimits());
        static EarthAlpineDefinition load(){try{return EarthAlpineDefinition.load(java.nio.file.Path.of(System.getProperty("earth.alpine.geography",java.nio.file.Path.of(System.getProperty("earthplatform.test.projectDirectory","minecraft")).resolve("../geography").normalize().toString())));}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    }
    private DistanceBodyTestFixture(LongSupplier clock,EarthAlpineDefinition definition){
        var registry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        registry.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(registry.freeze());
        if(definition==null){world=new WorldIdentity(EarthAlpineDefinition.SEED,NativeAlpineCubeProvider.GENERATOR_ID,NativeAlpineCubeProvider.GENERATOR_VERSION+"/"+RollingGateFixture.VERSION,EarthAlpineDefinition.DEFINITION_IDENTITY,NativeCubeCodec.paletteIdentity(palette));pin=PIN;source=newSource();sampler=null;
            var profile=RollingSessionDescriptor.alpine(world,palette,EarthAlpineDefinition.ANCHOR_X,Math.toIntExact(EarthAlpineDefinition.GROUND_Y),EarthAlpineDefinition.ANCHOR_Z);replica=new RollingGateReplica(world,1,palette,profile);}
        else {var provider=new NativeAlpineCubeProvider(definition,palette);var base=provider.identity();world=new WorldIdentity(base.seed(),base.generatorId(),base.generatorVersion()+"/"+RollingGateFixture.VERSION,base.datasetVersion(),base.blockPaletteVersion());
            var profile=RollingSessionDescriptor.alpine(world,palette,EarthAlpineDefinition.ANCHOR_X,Math.toIntExact(EarthAlpineDefinition.GROUND_Y),EarthAlpineDefinition.ANCHOR_Z);pin=profile.center();source=Alpine.SOURCE;sampler=provider.surfaceSampler();replica=new RollingGateReplica(world,1,palette,profile);}
        center=pin;codec=new NativeCubeCodec(world,palette);guard=new RenderPublicationGuard(replica.admission(),214);
        owner=new RollingDistanceBodyBinding.Owner(replica,guard,()->{fullResourceChecks.incrementAndGet();return live.get();},clock);
        capture=capture(source,pin);
    }
    public static EarthDistanceSurfacePlan.Source newSource(){
        var envelope=new EarthAlpineDefinition.Envelope(PIN.x()-3,PIN.x()+4,PIN.z()-3,PIN.z()+4,-688,554,PIN.minBlockX()-4096,PIN.minBlockX()+4096,PIN.minBlockZ()-4096,PIN.minBlockZ()+4096);
        return EarthDistanceSurfacePlan.Source.prepareSampled(envelope,-11040,8896,HASH,HASH,HASH,2,0,NativeDistanceLimits.sourceLimits(),
                (x,z)->new EarthDistanceSurfacePlan.Sample(PIN.minBlockY(),5,Math.floorMod(x,3)==0?2:Math.floorMod(x,3)==1?3:4,7,0,PIN.minBlockY(),0));
    }
    static EarthReplacementCapture capture(EarthDistanceSurfacePlan.Source source){return capture(source,PIN);}
    static EarthReplacementCapture capture(EarthDistanceSurfacePlan.Source source,CubePos pin){
        try(var builder=new EarthReplacementCapture.Builder(pin)){
            for(long y=pin.minBlockY()-2;y<pin.minBlockY()+18;y++)for(long z=pin.minBlockZ()-2;z<pin.minBlockZ()+18;z++)for(long x=pin.minBlockX()-2;x<pin.minBlockX()+18;x++)builder.setNext(source.materialAt(x,y,z));
            return builder.finish();
        }
    }
    public void stage(CubePos target){stage(target,null,-1,-1);}
    void stage(CubePos target,CubePos corrupt,int cell,int replacementState){
        binding=null;
        boolean initial=actorGeneration==0;var cubePlan=initial?RollingCubePlan.stationary(pin,target):RollingCubePlan.create(pin,center,target);
        var positions=initial?cubePlan.currentOwners():cubePlan.nextCommittedOwners();var old=new HashMap<CubePos,Long>();for(var lease:leases)old.put(lease.position(),lease.leaseGeneration());
        var next=new ArrayList<CubeLease>();var added=new ArrayList<CubePos>();
        for(var position:positions){Long lease=old.get(position);if(lease==null){lease=nextLease++;added.add(position);}next.add(new CubeLease(position,lease));}
        metadata=new StageMetadata(world,1,actorGeneration+1,initial,pin,center,target,leases,next,added,initial?List.of():cubePlan.retiredOwners(),initial?cubePlan.currentTargets():cubePlan.candidateTargets());
        replica.acceptMetadata(metadata);
        plan=EarthDistanceSurfacePlan.build(source,capture,target,2,0,NativeDistanceLimits.sourceLimits());
        for(var lease:next)if(added.contains(lease.position()))replica.acceptSnapshot(metadata.generation(),snapshot(lease.position(),lease.leaseGeneration(),0,lease.position().equals(corrupt)?cell:-1,replacementState));
        acceptReady(metadata.generation(),target,next);
    }
    EngineCubeSnapshot snapshot(CubePos p,long lease,long revision,int changedCell,int stateId){
        int[] cells=new int[4096];Block[] blocks={Blocks.AIR,Blocks.STONE,Blocks.DIORITE,Blocks.ANDESITE,Blocks.TUFF,Blocks.SNOW_BLOCK};
        for(int i=0;i<4096;i++)cells[i]=Block.BLOCK_STATE_REGISTRY.getId(blocks[plan.materialAt(p.minBlockX()+(i&15),p.minBlockY()+(i>>>8),p.minBlockZ()+(i>>>4&15))].defaultBlockState());
        if(changedCell>=0)cells[changedCell]=stateId;
        return codec.materialize(p,ImmutableCube.copyOf(cells),1,lease,revision);
    }
    void acceptReady(long generation,CubePos target,List<CubeLease> next){
        var versions=next.stream().map(lease->new CubeVersion(lease.position(),lease.leaseGeneration(),replica.cubes().get(lease.position()).snapshot().revision())).toList();
        var dependencies=CubeTickets.box(pin,1,1,27).stream().sorted().map(p->{var s=replica.cubes().get(p).snapshot();return new CubeVersion(p,s.leaseGeneration(),s.revision());}).toList();
        var certificate=new ReplacementCertificate(world,1,guard.resourceGeneration(),pin,STORE.toString(),world.datasetVersion(),palette.fingerprint(),EarthReplacementAdmission.MATERIAL_POLICY,EarthReplacementAdmission.DETAIL_POLICY,generation+publication+1,dependencies);
        var ready=new StageReady(world,1,generation,target,versions,certificate,true,null,null,List.of(),null,null,capture);replica.acceptReady(ready);
        publication++;
        var c=new DhReplacementInput.Certificate(world,1,guard.resourceGeneration(),publication,pin,certificate.originalRevision(),certificate.identity(),certificate.dependencyIdentity(),EarthReplacementAdmission.MATERIAL_POLICY);
        replacement=new DhReplacementInput(c,DhVoxelReplacementGeometry.captureEarth(capture),new DhReplacementInput.AuthorityGuard(){public boolean isCurrent(DhReplacementInput.Certificate v){return live.get();}public boolean permitsRetainedPresentation(DhReplacementInput.Certificate v){return live.get();}});
        stamp=new NativeVisualStamp(world,STORE,1,guard.resourceGeneration(),publication,certificate.originalRevision(),2,2,0,source.sourceIdentity(),source.detailIdentity(),source.materialPolicyIdentity(),BodyNativeCompletionFixture.capsuleIdentity(),capture.contentIdentity());
    }
    public void begin(){binding=owner.begin(publication,stamp,plan,replacement);}
    public void prove(){if(binding==null)begin();while(!binding.step().complete()){} }
    public void join(){prove();visual=BodyNativeCompletionFixture.publication(plan,stamp,live::get);joined=binding.join(visual);}
    public void refreshBindingSameContent(){
        publication++;var prior=stamp;stamp=new NativeVisualStamp(prior.world(),prior.storeIdentity(),prior.sessionEpoch(),prior.resourceGeneration(),publication,prior.durableRevision(),prior.selectedDistanceChunks(),prior.effectiveDistanceChunks(),prior.serverDistanceLimit(),prior.sourceIdentity(),prior.detailIdentity(),prior.materialPolicyIdentity(),prior.capsuleIdentity(),prior.captureIdentity());
        binding=null;join();
    }
    public void install(){owner.install(binding);if(metadata!=null){replica.commitStage(metadata.generation());actorGeneration=metadata.generation();center=metadata.candidateCenter();leases=metadata.candidateLeases();metadata=null;}binding.requireCommittedSnapshots();}
    public RollingDistanceBodyBinding.History retainPresentation(){return joined.retainPresentation();}
    public void editOne(){
        replica.beginEdit();var old=replica.cubes().get(pin).snapshot();
        capture=capture.withMaterial(pin.minBlockX()+4,pin.minBlockY()+4,pin.minBlockZ()+4,2).compact();
        plan=EarthDistanceSurfacePlan.build(source,capture,center,2,0,NativeDistanceLimits.sourceLimits());
        replica.acceptSnapshot(actorGeneration,snapshot(pin,old.leaseGeneration(),old.revision()+1,-1,-1));replica.acknowledge("APPLIED",old.revision()+1);acceptReady(actorGeneration,center,leases);binding=null;
    }
    public void invalidateResources(){live.set(false);}
    public void cancel(){owner.cancelCandidate();binding=null;}
    public RollingDistanceBodyBinding.Accounting accounting(){return owner.accounting();}
    public boolean aliasesReleased(){return owner.aliasesReleased();}
    @Override public void close(){owner.close();if(visual!=null)visual.close();guard.close();replica.close();}
}
