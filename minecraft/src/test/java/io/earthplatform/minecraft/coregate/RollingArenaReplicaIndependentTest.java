package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.network.*;
import io.earthplatform.minecraft.lod.DhArenaInput;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RollingArenaReplicaIndependentTest {
    static EnginePaletteContext palette;static NativeCubeCodec codec;static RollingSessionDescriptor descriptor;static int air,diorite;
    static final ArenaDefinition A=ArenaDefinition.FIXED;
    @BeforeAll static void setup(){var r=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());r.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);palette=EnginePaletteContext.create(r.freeze());descriptor=RollingSessionDescriptor.arena(palette);codec=new NativeCubeCodec(descriptor.world(),palette);air=Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());diorite=Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());}
    static ArenaCertificate certificate(long generation,Map<CubePos,Long> revisions,ArenaCapture capture,long floor){
        var deps=ArenaHostReviewFixtures.dependencies(floor,revisions);var policy=RollingArenaQueryGuard.policy(descriptor);var entries=A.positions().stream().map(p->new ArenaReplacementIndex.Entry(p,revisions.getOrDefault(p,0L),policy,capture.contentIdentity())).toList();
        var q=new ArenaQueryAttestation(ArenaHostReviewFixtures.STORE,ArenaHostReviewFixtures.INDEX,generation,String.format("%064x",generation),A.identity(),descriptor.lodLayout().identity(),policy,ArenaReplacementIndex.resultIdentity(entries),8);
        return new ArenaCertificate(descriptor.world(),1,1,q.storeIdentity(),q.indexIdentity(),descriptor.sourceFingerprint(),palette.fingerprint(),DhArenaInput.MATERIAL_POLICY,DhArenaInput.DETAIL_POLICY,generation,A,deps,capture.contentIdentity(),q);
    }
    static final class Driver implements AutoCloseable {
        final RollingGateReplica replica=new RollingGateReplica(descriptor.world(),1,palette,descriptor);
        final Map<CubePos,Long> revisions=new HashMap<>();final Map<CubePos,ImmutableCube> blocks=new HashMap<>();
        List<RollingCubeActor.CubeLease> leases=List.of();CubePos center=descriptor.initialCenter();long generation,nextLease=65,certificateGeneration=1;ArenaCapture capture=ArenaHostReviewFixtures.AIR;
        RollingCubeActor.StageMetadata metadata(CubePos next){boolean initial=generation==0;var plan=initial?RollingCubePlan.stationary(A.origin(),center,false):RollingCubePlan.create(A.origin(),center,next,false,false);var positions=initial?plan.committedOwners():plan.nextCommittedOwners();var previous=new HashMap<CubePos,Long>();leases.forEach(l->previous.put(l.position(),l.leaseGeneration()));var added=new ArrayList<CubePos>();var candidate=new ArrayList<RollingCubeActor.CubeLease>();for(var p:positions){Long lease=previous.get(p);if(lease==null){lease=nextLease++;added.add(p);}candidate.add(new RollingCubeActor.CubeLease(p,lease));}return new RollingCubeActor.StageMetadata(descriptor.world(),1,generation+1,initial,A.origin(),center,next,leases,candidate,added,initial?List.of():plan.retiredOwners(),initial?plan.currentTargets():plan.candidateTargets(),false,false,A.origin());}
        EngineCubeSnapshot snapshot(CubePos p,long lease,long revision){return codec.materialize(p,blocks.getOrDefault(p,ImmutableCube.uniform(air)),1,lease,revision);}
        RollingCubeActor.StageReady ready(long g,CubePos c,List<RollingCubeActor.CubeLease> ls,long floor){var versions=ls.stream().map(l->new RollingCubeActor.CubeVersion(l.position(),l.leaseGeneration(),revisions.getOrDefault(l.position(),0L))).toList();return new RollingCubeActor.StageReady(descriptor.world(),1,g,c,versions,null,false,null,null,List.of(),certificate(certificateGeneration,revisions,capture,floor),capture);}
        void fill(RollingCubeActor.StageMetadata m){for(var l:m.candidateLeases())if(m.newPositions().contains(l.position()))replica.acceptSnapshot(m.generation(),snapshot(l.position(),l.leaseGeneration(),revisions.getOrDefault(l.position(),0L)));}
        void commit(CubePos next){var m=metadata(next);replica.acceptMetadata(m);fill(m);replica.acceptReady(ready(m.generation(),next,m.candidateLeases(),1));replica.commitStage(m.generation());generation=m.generation();leases=m.candidateLeases();center=next;}
        long lease(CubePos p){return leases.stream().filter(l->l.position().equals(p)).findFirst().orElseThrow().leaseGeneration();}
        RollingCubeActor.StageReady current(){return ready(generation,center,leases,1);}
        @Override public void close(){replica.close();}
    }
    @Test void canonicalDescriptorRoundTripOwnStoreExactBoundsAndLegacyUntouched(){
        assertTrue(descriptor.arenaMode());assertFalse(descriptor.twoWorkspaces());assertEquals(8,descriptor.arenaDefinition().positions().size());assertTrue(descriptor.saveDirectory().startsWith("earthplatform-finite-arena-v1/"));
        for(int x:new int[]{-16,15})for(int z:new int[]{-16,15})assertDoesNotThrow(()->descriptor.requireAllowedWindow(new CubePos(x,9,z)));
        for(var p:List.of(new CubePos(-17,9,0),new CubePos(16,9,0),new CubePos(0,5,0),new CubePos(0,16,0)))assertThrows(IllegalArgumentException.class,()->descriptor.requireAllowedWindow(p));
        assertTrue(descriptor.permitsEdit(0,128,0));assertTrue(descriptor.permitsEdit(31,159,31));assertFalse(descriptor.permitsEdit(32,159,31));assertFalse(descriptor.permitsEdit(31,160,31));
        var legacy=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1,true);assertFalse(legacy.arenaMode());assertEquals("earthplatform-two-workspace-player-gate-v1/base",legacy.saveDirectory());
        for(var d:List.of(descriptor,legacy)){var b=new FriendlyByteBuf(Unpooled.buffer());try{d.write(b);assertEquals(d.arenaMode()?7:5,b.getUnsignedByte(0));b.writeLong(55);assertEquals(d,RollingSessionDescriptor.read(b,d.world(),palette));assertEquals(55,b.readLong());}finally{b.release();}}
    }
    @Test void nativeAdmissionStartsAtSixtyFiveAndReadinessMustProveBootstrapOneThroughSixtyFour(){
        try(var d=new Driver()){var m=d.metadata(d.center);var wrong=m.candidateLeases().stream().map(l->new RollingCubeActor.CubeLease(l.position(),l.leaseGeneration()-64)).toList();var forged=new RollingCubeActor.StageMetadata(m.world(),m.epoch(),m.generation(),true,m.pinCenter(),m.currentCenter(),m.candidateCenter(),m.currentLeases(),wrong,m.newPositions(),m.retiredPositions(),m.renderTargets(),false,false,A.origin());assertThrows(IllegalArgumentException.class,()->d.replica.acceptMetadata(forged));assertEquals(0,d.replica.admission().view().metrics().leasesCreated());
            d.replica.acceptMetadata(m);d.fill(m);assertEquals(125,d.replica.cubes().size());assertFalse(d.replica.stageReady());assertThrows(IllegalArgumentException.class,()->d.replica.acceptReady(d.ready(1,d.center,m.candidateLeases(),65)));assertFalse(d.replica.stageReady());d.replica.acceptReady(d.ready(1,d.center,m.candidateLeases(),1));d.replica.commitStage(1);assertFalse(d.replica.currentPinned());assertEquals(125,d.replica.currentPositions125().size());assertEquals(27,d.replica.currentTargets().size());}
    }
    @Test void allEightBoundaryTargetsUseTheirOwnBarrierAndFreshReadyDigest(){
        try(var d=new Driver()){d.commit(d.center);String prior=d.replica.currentManifestDigest();for(var target:A.positions()){
            long lease=d.lease(target);var old=d.current();d.replica.beginEdit(target);assertThrows(IllegalStateException.class,d.replica::currentManifestDigest);var wrong=A.positions().stream().filter(p->!p.equals(target)).findFirst().orElseThrow();assertThrows(IllegalArgumentException.class,()->d.replica.acceptSnapshot(d.generation,d.snapshot(wrong,d.lease(wrong),1)));
            int[] cells=new int[4096];Arrays.fill(cells,air);cells[0]=diorite;d.blocks.put(target,ImmutableCube.copyOf(cells));d.replica.acceptSnapshot(d.generation,d.snapshot(target,lease,1));d.replica.acknowledge("APPLIED",1);assertThrows(IllegalArgumentException.class,()->d.replica.acceptReady(old));
            d.revisions.put(target,1L);d.capture=d.capture.withMaterial(target.minBlockX(),target.minBlockY(),target.minBlockZ(),ArenaCapture.DIORITE).compact();d.certificateGeneration++;d.replica.acceptReady(d.current());assertEquals(1,d.replica.revision(target));assertFalse(d.replica.currentPinned());String next=d.replica.currentManifestDigest();assertNotEquals(prior,next);prior=next;}
            assertEquals(8,d.revisions.size());assertEquals(125,d.replica.cubes().size());}
    }
    @Test void refusedEditRestoresExactCommittedDigestAndSaveFailureCannotBecomeReady(){
        try(var d=new Driver()){d.commit(d.center);String prior=d.replica.currentManifestDigest();d.replica.beginEdit(A.positions().getLast());d.replica.acknowledge("REPLACEMENT_BUDGET",0);d.replica.acceptReady(d.current());assertEquals(prior,d.replica.currentManifestDigest());d.replica.beginEdit(A.positions().getLast());assertThrows(IllegalStateException.class,()->d.replica.acknowledge("SAVE_FAILED",-1));assertTrue(d.replica.paused());assertThrows(IllegalStateException.class,()->d.replica.acceptReady(d.current()));}
    }
    @Test void distantRetirementKeepsNoNativePinsAndArenaCannotEditWithoutWholeReadHalo(){
        try(var d=new Driver()){d.commit(d.center);for(int x=1;x<=10;x++)d.commit(new CubePos(x,d.center.y(),d.center.z()));assertEquals(125,d.replica.cubes().size());assertEquals(27,d.replica.currentTargets().size());assertFalse(d.replica.cubes().containsKey(A.origin()));assertFalse(d.replica.currentPinned());assertEquals(0,d.replica.revision(A.origin()));assertThrows(IllegalArgumentException.class,()->d.replica.beginEdit(A.origin()));for(int x=9;x>=0;x--)d.commit(new CubePos(x,d.center.y(),d.center.z()));assertDoesNotThrow(()->d.replica.beginEdit(A.positions().getLast()));}
    }
    @Test void acknowledgementReconstructsArbitraryTargetAndRefusalRetainsEntireCapture(){
        try(var d=new Driver()){d.commit(d.center);var old=d.current();var target=A.positions().getLast();var request=new RollingCubeActor.EditRequest(descriptor.world(),1,target,d.lease(target),0,target.minBlockX(),target.minBlockY(),target.minBlockZ(),diorite);var refusal=new RollingCubeActor.EditAck(request,RollingCubeActor.EditStatus.REPLACEMENT_BUDGET,0,"budget");var restored=RollingEditAcknowledgement.ready(old,target,old.versions(),refusal);assertSame(old.arenaCertificate(),restored.arenaCertificate());assertSame(old.arenaCapture(),restored.arenaCapture());assertEquals(RollingGateWire.manifestDigest(old),RollingGateWire.manifestDigest(restored));
            var changed=d.capture.withMaterial(target.minBlockX(),target.minBlockY(),target.minBlockZ(),ArenaCapture.DIORITE).compact();var c=certificate(2,Map.of(target,1L),changed,1);var applied=new RollingCubeActor.EditAck(request,RollingCubeActor.EditStatus.APPLIED,1,"",null,null,List.of(),c,changed);var versions=old.versions().stream().map(v->v.position().equals(target)?new RollingCubeActor.CubeVersion(target,v.leaseGeneration(),1):v).toList();var next=RollingEditAcknowledgement.ready(old,target,versions,applied);assertSame(c,next.arenaCertificate());assertNotEquals(RollingGateWire.manifestDigest(old),RollingGateWire.manifestDigest(next));assertThrows(IllegalArgumentException.class,()->RollingEditAcknowledgement.ready(old,A.origin(),versions,applied));
            for(var status:List.of(RollingCubeActor.EditStatus.CLOSING,RollingCubeActor.EditStatus.SAVE_FAILED)){var failed=new RollingCubeActor.EditAck(request,status,-1,"");assertThrows(IllegalStateException.class,()->RollingEditAcknowledgement.ready(old,target,old.versions(),failed));}}
    }
}
