package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import io.earthplatform.minecraft.lod.DhEarthReplacementAdmission;
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

/** Real Earth source/durable actor -> actual compact wire -> replica/material proof; no GPU claim. */
class RollingEarthReplacementReplicaIntegrationTest {
    static EnginePaletteContext palette;static NativeAlpineCubeProvider source;
    @TempDir Path directory;
    @BeforeAll static void registry()throws Exception{
        var r=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        r.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(r.freeze());
        Path project=Path.of(System.getProperty("earthplatform.test.projectDirectory", ".")).toAbsolutePath();
        source=new NativeAlpineCubeProvider(EarthAlpineDefinition.load(project.resolve("../geography").normalize()),palette);
    }
    @Test void pristineAndEditedEarthRetiresBeyondNativeFarPlaneThenReturnsAndReopens()throws Exception{
        String durableCapture;
        try(var h=new Harness(193)){
            var ready=h.receive();assertEquals(0,ready.replacementCertificate().originalRevision());h.commit(ready);
            var original=ready.earthReplacementCapture();assertEquals(8000,original.declaredBytes());
            // Revision-zero authority also retires before native projection could clip the pin.
            for(int i=1;i<=10;i++){ready=h.move(i);assertEquals(i<=1,ready.pinned());}
            assertTrue(Math.abs(ready.candidateCenter().minBlockX()-h.descriptor.pinCenter().minBlockX())>128);
            assertFalse(h.replica.cubes().containsKey(h.descriptor.target()));assertEquals(27,h.replica.currentTargets().size());
            assertSame(original,ready.earthReplacementCapture());
            for(int i=9;i>=0;i--)ready=h.move(i);
            var refusedTarget=source.definition().anchor().digTarget();
            ready=h.edit(ready,refusedTarget.x(),refusedTarget.y(),refusedTarget.z(),Block.BLOCK_STATE_REGISTRY.getId(Blocks.GLASS.defaultBlockState()));assertEquals(0,ready.replacementCertificate().originalRevision());
            var target=source.definition().anchor().digTarget();int originalState=Block.BLOCK_STATE_REGISTRY.getId(NativeAlpineCubeProvider.nativeState(source.definition().materialAt(target.x(),target.y(),target.z())));
            ready=h.edit(ready,target.x(),target.y(),target.z(),Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState()));
            assertEquals(1,ready.replacementCertificate().originalRevision());
            ready=h.edit(ready,target.x(),target.y(),target.z(),originalState);
            assertEquals(2,ready.replacementCertificate().originalRevision());assertEquals(original.contentIdentity(),ready.earthReplacementCapture().contentIdentity());
            durableCapture=ready.earthReplacementCapture().contentIdentity();
            for(int i=1;i<=10;i++)ready=h.move(i);
            assertFalse(ready.pinned());for(var v:ready.replacementCertificate().dependencies())assertFalse(h.replica.cubes().containsKey(v.position()));
            assertEquals(durableCapture,ready.earthReplacementCapture().contentIdentity());
        }
        try(var restarted=new Harness(194)){
            var ready=restarted.receive();assertEquals(2,ready.replacementCertificate().originalRevision());assertEquals(durableCapture,ready.earthReplacementCapture().contentIdentity());restarted.commit(ready);
        }
    }
    @Test void refusedMaterialEditRepreparesExactHistoricalAuthorityThenNextEditStillWorks()throws Exception{
        try(var h=new Harness(201)){
            var ready=h.receive();h.commit(ready);var before=ready;
            var target=source.definition().anchor().digTarget();
            ready=h.edit(ready,target.x(),target.y(),target.z(),Block.BLOCK_STATE_REGISTRY.getId(Blocks.GLASS.defaultBlockState()));
            assertEquals(before.replacementCertificate().identity(),ready.replacementCertificate().identity());assertSame(before.earthReplacementCapture(),ready.earthReplacementCapture());
            assertEquals(0,ready.replacementCertificate().originalRevision());
            ready=h.edit(ready,target.x(),target.y(),target.z(),Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState()));assertEquals(1,ready.replacementCertificate().originalRevision());
        }
    }
    final class Harness implements AutoCloseable {
        final RollingGateFixture.Provider provider=RollingGateFixture.wrap(source,source.identity());
        final RollingSessionDescriptor descriptor=RollingSessionDescriptor.alpine(provider.identity(),palette,EarthAlpineDefinition.ANCHOR_X,7314,EarthAlpineDefinition.ANCHOR_Z);
        final long epoch;final RollingCubeActor actor;final RollingGateReplica replica;final RollingReplacementAuthority authority;final ArrayDeque<Event> queue=new ArrayDeque<>();
        EarthReplacementCapture capture;ReplacementCertificate certificate;
        Harness(long epoch){this.epoch=epoch;var policy=new ReplacementPolicy(provider.identity().datasetVersion(),EarthReplacementAdmission.MATERIAL_POLICY,EarthReplacementAdmission.DETAIL_POLICY,1,RollingEditPolicy.INTERIOR_V1);
            actor=RollingCubeActor.openEarth(directory,provider.identity(),provider,palette,epoch,descriptor.initialCenter(),policy,provider.identity().datasetVersion(),descriptor.lodLayout().identity(),new DhEarthReplacementAdmission());
            replica=new RollingGateReplica(provider.identity(),epoch,palette,descriptor);authority=new RollingReplacementAuthority(provider.identity(),epoch,1,descriptor.target(),policy.providerIdentity(),palette.fingerprint(),policy.materialPolicy(),policy.detailPolicy());}
        StageReady receive()throws Exception{
            var metadata=assertInstanceOf(StageMetadata.class,next());var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeMetadata(b,metadata);replica.acceptMetadata(RollingGateWire.readMetadata(b,provider.identity(),epoch));assertFalse(b.isReadable());}finally{b.release();}
            Event event;while((event=next()) instanceof Snapshot s)replica.acceptSnapshot(s.generation(),s.snapshot());return accept(assertInstanceOf(StageReady.class,event),false);
        }
        StageReady accept(StageReady value,boolean edit){
            var b=new FriendlyByteBuf(Unpooled.buffer());StageReady decoded;try{RollingGateWire.writeReady(b,value);assertTrue(b.readableBytes()<RollingGatePayload.MAX_BYTES);decoded=RollingGateWire.readReady(b,value.world(),value.epoch());assertFalse(b.isReadable());assertEquals(RollingGateWire.manifestDigest(value),RollingGateWire.manifestDigest(decoded));}finally{b.release();}
            decoded=RollingEarthCaptureOwner.canonicalize(decoded,capture);replica.acceptReady(decoded);
            var next=decoded.replacementCertificate();boolean same=certificate!=null&&certificate.identity().equals(next.identity());
            if(edit&&same)authority.reproposeRefusedEarth(next);else authority.propose(next);
            if(edit||!same)try(var validation=new RollingEarthCaptureValidation(next,decoded.earthReplacementCapture(),!same)){for(int i=0;i<7;i++)assertFalse(validation.step(replica.cubes()));assertTrue(validation.step(replica.cubes()));assertSame(decoded.earthReplacementCapture(),validation.finish(replica.cubes()));}
            capture=decoded.earthReplacementCapture();certificate=next;return decoded;
        }
        void commit(StageReady ready)throws Exception{
            assertEquals(Admission.QUEUED,actor.commitPrepared(ready.generation(),certificate.identity()));assertInstanceOf(Committed.class,next());replica.commitStage(ready.generation());authority.commit(certificate.identity());
            var handshake=new RollingPublicationHandshake(epoch);long publication=handshake.begin();String digest=RollingFarPublication.digest(descriptor,ready);handshake.ready(digest);assertTrue(handshake.prepared(epoch,publication,1,digest));handshake.grant();assertTrue(handshake.pairReady(epoch,publication,1,digest));
            RollingGateCommands.authorizePublication(actor,handshake,ready,true,false);
        }
        StageReady move(int offset)throws Exception{assertEquals(Admission.QUEUED,actor.requestCenter(descriptor.center().offset(offset,0,0)));var ready=receive();commit(ready);return ready;}
        StageReady edit(StageReady prior,long x,long y,long z,int state)throws Exception{
            replica.beginEdit();authority.beginEdit();var snapshot=replica.cubes().get(descriptor.target()).snapshot();
            assertEquals(Admission.QUEUED,actor.tryEdit(new EditRequest(provider.identity(),epoch,descriptor.target(),snapshot.leaseGeneration(),snapshot.revision(),x,y,z,state)));
            Event event=next();if(event instanceof Snapshot s){replica.acceptSnapshot(s.generation(),s.snapshot());event=next();}
            var ack=assertInstanceOf(EditAck.class,event);assertNotEquals(EditStatus.SAVE_FAILED,ack.status());replica.acknowledge(ack.status().name(),ack.revision());
            var versions=prior.versions().stream().map(v->{var s=replica.cubes().get(v.position()).snapshot();return new CubeVersion(v.position(),s.leaseGeneration(),s.revision());}).toList();
            var ready=accept(RollingEditAcknowledgement.ready(prior,descriptor.target(),versions,ack),true);
            var handshake=new RollingPublicationHandshake(epoch);long publication=handshake.begin();String digest=RollingFarPublication.digest(descriptor,ready);handshake.ready(digest);assertTrue(handshake.prepared(epoch,publication,1,digest));handshake.grant();assertTrue(handshake.pairReady(epoch,publication,1,digest));
            RollingGateCommands.authorizePublication(actor,handshake,ready,true,false);authority.commit(certificate.identity());return ready;
        }
        Event next()throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(queue.isEmpty()){queue.addAll(actor.pollEvents(2));if(queue.isEmpty()){assertTrue(System.nanoTime()<deadline,"Actor timeout "+actor.diagnostics());Thread.sleep(1);}}var e=queue.removeFirst();if(e instanceof Failure f)fail(f.detail());return e;}
        public void close()throws Exception{authority.close();replica.close();actor.close();assertTrue(actor.closed().get(20,TimeUnit.SECONDS).successful());}
    }
}
