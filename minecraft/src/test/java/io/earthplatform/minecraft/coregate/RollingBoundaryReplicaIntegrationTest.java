package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real boundary edit -> durable actor -> exact wire/replica -> retired3D capture -> native reload. No GPU claim. */
class RollingBoundaryReplicaIntegrationTest {
    static EnginePaletteContext palette;
    @TempDir Path directory;
    @BeforeAll static void registry(){
        var r=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        r.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(r.freeze());
    }
    @Test void minimumCornerExcavationSurvivesRetirementAndReacquisition() throws Exception { runBoundaryEdit(0,128,0,Blocks.AIR.defaultBlockState(),0); }
    @Test void maximumCornerAdditionSurvivesRetirementAndReacquisition() throws Exception { runBoundaryEdit(15,143,15,Blocks.DIORITE.defaultBlockState(),0xffdedede); }
    @Test void boundaryFloorEdgeRevealsNeighborVoxelAfterRetirement() throws Exception { runBoundaryEdit(15,128,8,Blocks.AIR.defaultBlockState(),0); }
    private void runBoundaryEdit(int editX,int editY,int editZ,net.minecraft.world.level.block.state.BlockState state,int argb) throws Exception {
        var descriptor=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,io.earthplatform.minecraft.enginebridge.RollingEditPolicy.FULL_CUBE_V1);
        var provider=RollingGateFixture.provider(palette,CoreGateProfile.BASE);
        var policy=new ReplacementPolicy(descriptor.sourceFingerprint(),DhReplacementInput.ANALYTIC_MATERIAL_POLICY,RollingGateFixture.REPLACEMENT_DETAIL_POLICY,1,descriptor.editPolicy());
        var actor=RollingCubeActor.open(directory,provider.identity(),provider,palette,71,descriptor.center(),policy);
        var events=new ArrayDeque<Event>();
        var replica=new RollingGateReplica(provider.identity(),71,palette,descriptor);
        var authority=new RollingReplacementAuthority(provider.identity(),71,1,descriptor.center(),policy.providerIdentity(),palette.fingerprint(),policy.materialPolicy(),policy.detailPolicy());
        try{
            var first=receiveStage(actor,events,replica);commit(actor,events,replica,first);
            var old=replica.cubes().get(descriptor.target()).snapshot();
            replica.beginEdit();authority.beginEdit();
            assertEquals(Admission.QUEUED,actor.tryEdit(new EditRequest(provider.identity(),71,descriptor.target(),old.leaseGeneration(),0,editX,editY,editZ,Block.BLOCK_STATE_REGISTRY.getId(state))));
            var snap=assertInstanceOf(Snapshot.class,next(actor,events));replica.acceptSnapshot(snap.generation(),snap.snapshot());
            var ack=assertInstanceOf(EditAck.class,next(actor,events));assertTrue(ack.applied());replica.acknowledge(ack.status().name(),ack.revision());
            var versions=first.versions().stream().map(v->{var s=replica.cubes().get(v.position()).snapshot();return new CubeVersion(v.position(),s.leaseGeneration(),s.revision());}).toList();
            var edited=roundTrip(new StageReady(provider.identity(),71,first.generation(),first.candidateCenter(),versions,ack.replacementCertificate(),true));
            replica.acceptReady(edited);var certificate=edited.replacementCertificate();authority.propose(certificate);
            DhVoxelReplacementGeometry.Capture capture;
            try(var work=new RollingReplacementCapture(certificate)){
                for(int i=0;i<7;i++)assertFalse(work.step(replica.cubes()));
                assertTrue(work.step(replica.cubes()));capture=work.finish(replica.cubes());
            }
            // Simulate only the trusted owner's exact paired proof; renderer ownership has its own suites.
            var permit=new RollingPublicationHandshake(71);long pub=permit.begin();String digest=RollingFarPublication.digest(descriptor,edited);permit.ready(digest);
            assertTrue(permit.prepared(71,pub,1,digest));permit.grant();assertTrue(permit.pairReady(71,pub,1,digest));permit.committed();
            authority.commit(certificate.identity());
            assertEquals(Admission.QUEUED,actor.authorizePinRetirement(first.generation(),certificate.identity()));
            for(int x=1;x<=5;x++){
                assertEquals(Admission.QUEUED,actor.requestCenter(descriptor.center().offset(x,0,0)));
                var ready=receiveStage(actor,events,replica);assertEquals(certificate,ready.replacementCertificate());
                assertEquals(x<=1,ready.pinned());commit(actor,events,replica,ready);
                assertTrue(authority.isCurrent(certificate.identity()));
            }
            assertEquals(125,replica.cubes().size());assertEquals(27,replica.currentTargets().size());
            for(var v:certificate.dependencies())assertFalse(replica.cubes().containsKey(v.position()),"Every original dependency genuinely retired");
            assertEquals(1,replica.revision());assertFalse(replica.currentPinned());
            assertEquals(argb,capture.argbAt(editX,editY,editZ));
            assertTrue(DhVoxelReplacementGeometry.build(capture,32768,5L*1024*1024,()->{}).faceCount()>0);
            for(int x=4;x>=0;x--){
                assertEquals(Admission.QUEUED,actor.requestCenter(descriptor.center().offset(x,0,0)));
                var ready=receiveStage(actor,events,replica);commit(actor,events,replica,ready);
            }
            assertTrue(replica.currentPinned());assertEquals(1,replica.revision());
            assertSame(state,replica.cubes().get(descriptor.target()).state(Math.floorMod(editX,16),Math.floorMod(editY,16),Math.floorMod(editZ,16)));
            assertTrue(replica.cubes().get(descriptor.target()).snapshot().leaseGeneration()>old.leaseGeneration());
            RollingReplacementCapture.verifyBindings(certificate,replica.cubes(),false);
            assertThrows(IllegalStateException.class,()->RollingReplacementCapture.verifyBindings(certificate,replica.cubes(),true));
            assertTrue(authority.isCurrent(certificate.identity()));
        }finally{
            authority.close();replica.close();actor.close();assertTrue(actor.closed().get(10,TimeUnit.SECONDS).successful());
        }
    }
    private static StageReady receiveStage(RollingCubeActor actor,ArrayDeque<Event> queue,RollingGateReplica replica)throws Exception{
        var metadata=assertInstanceOf(StageMetadata.class,next(actor,queue));
        var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeMetadata(b,metadata);replica.acceptMetadata(RollingGateWire.readMetadata(b,metadata.world(),metadata.epoch()));assertFalse(b.isReadable());}finally{b.release();}
        Event e;while((e=next(actor,queue))instanceof Snapshot snapshot)replica.acceptSnapshot(snapshot.generation(),snapshot.snapshot());
        var ready=roundTrip(assertInstanceOf(StageReady.class,e));replica.acceptReady(ready);return ready;
    }
    private static StageReady roundTrip(StageReady ready){
        var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeReady(b,ready);var result=RollingGateWire.readReady(b,ready.world(),ready.epoch());assertEquals(ready,result);assertFalse(b.isReadable());return result;}finally{b.release();}
    }
    private static void commit(RollingCubeActor actor,ArrayDeque<Event> events,RollingGateReplica replica,StageReady ready)throws Exception{
        assertEquals(Admission.QUEUED,actor.commitPrepared(ready.generation(),ready.replacementCertificate()==null?null:ready.replacementCertificate().identity()));
        assertEquals(ready.generation(),assertInstanceOf(Committed.class,next(actor,events)).generation());replica.commitStage(ready.generation());
    }
    private static Event next(RollingCubeActor actor,ArrayDeque<Event> queue)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(queue.isEmpty()){queue.addAll(actor.pollEvents(2));if(queue.isEmpty()){assertTrue(System.nanoTime()<deadline,"Actor event timeout "+actor.diagnostics());Thread.sleep(1);}}
        var e=queue.removeFirst();if(e instanceof Failure f)fail(f.detail());return e;
    }
}
