package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real actor, durable store, replica, edit reconstruction and the actual host authorization. */
class RollingArenaPublicationCommitTest {
    private static final long EPOCH=7, RESOURCES=11;
    private static final ArenaDefinition ARENA=ArenaDefinition.FIXED;
    private static EnginePaletteContext palette;
    private static NativeArenaCubeProvider provider;
    private static RollingSessionDescriptor descriptor;
    private static int air,diorite;
    @TempDir Path directory;
    private final List<Driver> drivers=new ArrayList<>();

    @BeforeAll static void initialize() {
        var registry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        registry.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(registry.freeze());
        descriptor=RollingSessionDescriptor.arena(palette);
        provider=new NativeArenaCubeProvider(descriptor.arenaSource(),palette);
        air=Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        diorite=Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
    }

    @AfterEach void close() throws Exception {
        for(var d:drivers) { d.replica.close();d.actor.close(); }
        for(var d:drivers) assertTrue(d.actor.closed().get(15,TimeUnit.SECONDS).successful());
    }

    private Driver open() throws Exception {
        var d=new Driver(directory.resolve("actor-"+drivers.size()));drivers.add(d);d.stage();return d;
    }

    private static final class Driver {
        final AtomicBoolean admitted=new AtomicBoolean(true);
        final RollingCubeActor actor;
        final RollingGateReplica replica=new RollingGateReplica(descriptor.world(),EPOCH,palette,descriptor);
        final RollingPublicationHandshake handshake=new RollingPublicationHandshake(EPOCH);
        final ArrayDeque<Event> events=new ArrayDeque<>();
        final Map<CubePos,EngineCubeSnapshot> snapshots=new HashMap<>();
        StageReady committed,pending;

        Driver(Path path) {
            ArenaAdmission census=capture->new ArenaAdmission.Receipt(admitted.get(),
                    admitted.get()?"":"test budget refusal",ARENA.identity(),capture.contentIdentity(),
                    descriptor.arenaSource().identity(),descriptor.lodLayout().identity());
            actor=RollingCubeActor.openArena(path,provider.identity(),provider,palette,EPOCH,
                    descriptor.initialCenter(),RollingArenaQueryGuard.replacementPolicy(descriptor),ARENA,
                    descriptor.arenaSource().identity(),descriptor.lodLayout().identity(),census);
        }

        Event next() throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(events.isEmpty()) {
                events.addAll(actor.pollEvents(2));
                if(events.isEmpty()) {
                    if(System.nanoTime()>deadline)fail("Actor event timed out: "+actor.diagnostics());
                    Thread.sleep(1);
                }
            }
            var event=events.removeFirst();
            if(event instanceof Failure failure)fail(failure.detail());
            return event;
        }

        void snapshot(Snapshot event) {
            snapshots.put(event.snapshot().position(),event.snapshot());
            replica.acceptSnapshot(event.generation(),event.snapshot());
        }

        void ready(StageReady ready) {
            pending=ready;replica.acceptReady(ready);
            handshake.ready(RollingFarPublication.digest(descriptor,ready));
            assertTrue(actor.replacementCurrent(ready.arenaCertificate().identity()));
        }

        void pair() {
            assertTrue(handshake.prepared(EPOCH,handshake.publication(),RESOURCES,handshake.digest()));
            handshake.grant();
            assertTrue(handshake.pairReady(EPOCH,handshake.publication(),RESOURCES,handshake.digest()));
        }

        void finish(boolean authorize) {
            if(authorize)RollingGateCommands.authorizePublication(actor,handshake,pending,true,true);
            // The host performs this only after authorization and collision publication.
            handshake.committed();committed=pending;pending=null;
            var keep=new HashSet<CubePos>();committed.versions().forEach(v->keep.add(v.position()));
            snapshots.keySet().retainAll(keep);
            assertEquals(RollingPublicationHandshake.State.IDLE,handshake.state());
            assertTrue(handshake.acceptsMouse(EPOCH,handshake.publication(),RESOURCES));
            bounds();
        }

        void stage() throws Exception {
            handshake.begin();var metadata=assertInstanceOf(StageMetadata.class,next());
            assertEquals(Admission.QUEUED,actor.requestCenter(metadata.candidateCenter()));
            replica.acceptMetadata(metadata);Event event;
            while((event=next()) instanceof Snapshot s)snapshot(s);
            ready(assertInstanceOf(StageReady.class,event));pair();
            replica.commitStage(pending.generation());
            // Initial and movement publications retain their original actor commit path.
            assertEquals(Admission.QUEUED,actor.commitPrepared(pending.generation(),pending.arenaCertificate().identity()));
            var committedEvent=assertInstanceOf(Committed.class,next());
            assertEquals(pending.generation(),committedEvent.generation());
            assertEquals(pending.candidateCenter(),committedEvent.center());
            finish(true);
        }

        EditAck edit(long x,long y,long z,int state) throws Exception {
            var position=CubePos.fromBlock(x,y,z);
            var version=committed.versions().stream().filter(v->v.position().equals(position)).findFirst().orElseThrow();
            var request=new EditRequest(provider.identity(),EPOCH,position,version.leaseGeneration(),version.revision(),x,y,z,state);
            handshake.begin();replica.beginEdit(position);
            assertEquals(Admission.QUEUED,actor.tryEdit(request));Event event;
            while((event=next()) instanceof Snapshot s)snapshot(s);
            var ack=assertInstanceOf(EditAck.class,event);replica.acknowledge(ack.status().name(),ack.revision());
            var versions=committed.versions().stream().map(v->{var s=snapshots.get(v.position());
                return new CubeVersion(v.position(),s.leaseGeneration(),s.revision());}).toList();
            ready(RollingEditAcknowledgement.ready(committed,position,versions,ack));
            return ack;
        }

        EditAck publishEdit(long x,long y,long z,int state) throws Exception {
            var ack=edit(x,y,z,state);pair();finish(true);return ack;
        }

        void bounds() {
            var d=actor.diagnostics();assertTrue(d.residentCubes()<=186,d.toString());
            assertTrue(d.tickets()<=2,d.toString());assertTrue(d.retainedSnapshotReferenceSlots()<=188,d.toString());
            assertFalse(d.currentPinned());assertEquals(125,snapshots.size());
            assertEquals(125,replica.cubes().size());assertEquals(27,replica.currentTargets().size());
            assertEquals(64,committed.arenaCertificate().dependencies().size());
            var a=actor.arenaDiagnostics();assertEquals(1,a.bootstrapPeakOwners());
            assertEquals(1,a.bootstrapPeakSnapshotCaches());assertEquals(11664,a.capturePayloadBytes());
            assertTrue(a.peakCaptureArrayBytes()<=23328,a.toString());
        }
    }

    @Test void observedDigThenRepeatedPlaceCommitsFreshAuthorityAtSameRollingGeneration() throws Exception {
        var d=open();long generation=d.committed.generation();String original=d.committed.arenaCertificate().identity();
        assertEquals(EditStatus.APPLIED,d.publishEdit(8,144,12,air).status());
        assertEquals(1,d.committed.arenaCertificate().revision(new CubePos(0,9,0)));
        String afterDig=d.committed.arenaCertificate().identity();assertNotEquals(original,afterDig);
        assertEquals(EditStatus.APPLIED,d.publishEdit(7,145,14,diorite).status());
        String afterPlace=d.committed.arenaCertificate().identity();String content=d.committed.arenaCapture().contentIdentity();
        assertEquals(EditStatus.APPLIED,d.publishEdit(7,145,14,diorite).status());
        assertEquals(3,d.committed.arenaCertificate().revision(new CubePos(0,9,0)));
        assertEquals(content,d.committed.arenaCapture().contentIdentity(),"No-op keeps content but still needs fresh certificate commit");
        assertNotEquals(afterPlace,d.committed.arenaCertificate().identity());
        assertEquals(generation,d.committed.generation());assertEquals(4,d.handshake.committedPublication());
        assertFalse(d.actor.replacementCurrent(original));assertFalse(d.actor.replacementCurrent(afterDig));
        assertEquals(ArenaCapture.DIORITE,d.committed.arenaCapture().materialAt(7,145,14));
    }

    @Test void allEightCubesSupportRepeatedEditsAndNoOpsWithoutAnyPinOrCapExpansion() throws Exception {
        var d=open();long publication=1;
        for(var p:ARENA.positions())for(int round=1;round<=3;round++) {
            var ack=d.publishEdit(p.minBlockX()+8,p.minBlockY()+8,p.minBlockZ()+8,round==1?air:diorite);
            assertEquals(EditStatus.APPLIED,ack.status(),ack.detail());assertEquals(round,ack.revision());
            assertEquals(++publication,d.handshake.committedPublication());
            assertEquals(round,d.committed.arenaCertificate().revision(p));
            assertEquals(round,d.replica.revision(p));
        }
        for(var p:ARENA.positions())assertEquals(3,d.committed.arenaCertificate().revision(p));
        assertEquals(24,d.actor.diagnostics().admittedEdits());
    }

    @Test void oldSkippedAuthorizationReproducesTwoNotReadyRepliesDespiteFreshReadyThenRecovers() throws Exception {
        var d=open();assertEquals(EditStatus.APPLIED,d.edit(8,144,12,air).status());d.pair();
        d.finish(false); // Negative control: exactly the old arena-only host bypass.
        assertEquals(2,d.handshake.committedPublication());
        for(int i=0;i<2;i++) {
            var ack=d.edit(7,145,14,diorite);assertEquals(EditStatus.NOT_READY,ack.status(),ack.detail());
            assertEquals(1,ack.revision());d.pair();d.finish(false);
        }
        assertEquals(4,d.handshake.committedPublication());assertEquals(1,d.actor.diagnostics().admittedEdits());
        var refused=d.edit(7,145,14,diorite);assertEquals(EditStatus.NOT_READY,refused.status());d.pair();d.finish(true);
        assertEquals(EditStatus.APPLIED,d.publishEdit(7,145,14,diorite).status());
        assertEquals(2,d.committed.arenaCertificate().revision(new CubePos(0,9,0)));
    }

    @Test void preSaveBudgetRefusalKeepsDurabilityAndSupportsNextEditAfterRecoveryPair() throws Exception {
        var d=open();assertEquals(EditStatus.APPLIED,d.publishEdit(8,144,12,air).status());
        var prior=d.committed.arenaCertificate();String content=d.committed.arenaCapture().contentIdentity();
        var root=directory.resolve("actor-0").resolve(ArenaReplacementIndex.ROOT_FILE);byte[] bytes=Files.readAllBytes(root);
        d.admitted.set(false);var ack=d.publishEdit(7,145,14,diorite);
        assertEquals(EditStatus.REPLACEMENT_BUDGET,ack.status());assertEquals(1,ack.revision());
        assertSame(prior,d.committed.arenaCertificate());assertEquals(content,d.committed.arenaCapture().contentIdentity());
        assertArrayEquals(bytes,Files.readAllBytes(root));assertEquals(1,d.actor.diagnostics().admittedEdits());
        d.admitted.set(true);assertEquals(EditStatus.APPLIED,d.publishEdit(7,145,14,diorite).status());
        assertEquals(2,d.committed.arenaCertificate().revision(new CubePos(0,9,0)));
    }

    @Test void postEditMovementRetiresArenaNativeOwnersAndReturnRestoresEditableAuthority() throws Exception {
        var d=open();assertEquals(EditStatus.APPLIED,d.publishEdit(7,145,14,diorite).status());
        String content=d.committed.arenaCapture().contentIdentity();String durable=d.committed.arenaCertificate().durableIdentity();
        var start=d.committed.candidateCenter();var far=new CubePos(5,start.y(),start.z());
        for(int x=start.x()+1;x<=far.x();x++){
            assertEquals(Admission.QUEUED,d.actor.requestCenter(new CubePos(x,start.y(),start.z())));
            d.stage();assertEquals(x,d.committed.candidateCenter().x());
        }
        assertFalse(d.snapshots.containsKey(ARENA.origin()));assertFalse(d.replica.cubes().containsKey(ARENA.origin()));
        assertEquals(content,d.committed.arenaCapture().contentIdentity());
        assertEquals(durable,d.committed.arenaCertificate().durableIdentity());
        for(int x=far.x()-1;x>=start.x();x--){
            assertEquals(Admission.QUEUED,d.actor.requestCenter(new CubePos(x,start.y(),start.z())));
            d.stage();assertEquals(x,d.committed.candidateCenter().x());
        }
        assertEquals(EditStatus.APPLIED,d.publishEdit(7,145,14,air).status());
        assertEquals(2,d.committed.arenaCertificate().revision(new CubePos(0,9,0)));
    }

    @Test void arenaEntryFramesNorthernHillAndLegacyEditAngleIsUnchanged() {
        assertEquals(-150f,RollingGateCommands.entryYaw(descriptor));assertEquals(-8f,RollingGateCommands.entryPitch(descriptor));
        var legacy=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1,true);
        assertEquals(0f,RollingGateCommands.entryYaw(legacy));assertEquals(31f,RollingGateCommands.entryPitch(legacy));
        assertEquals(new net.minecraft.world.phys.Vec3(8.5,145.01,12.5),descriptor.entry(),"Camera change must not move spawn");
    }
}
