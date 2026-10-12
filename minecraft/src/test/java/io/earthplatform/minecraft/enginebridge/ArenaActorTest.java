package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
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

class ArenaActorTest {
    static EnginePaletteContext palette;static NativeArenaCubeProvider provider;static int diorite,air;
    static final ArenaDefinition ARENA=ArenaDefinition.FIXED;static final CubePos CENTER=new CubePos(0,9,0);static final String LAYOUT="test-exact-arena-layout-v1";
    @TempDir Path dir;
    final List<RollingCubeActor> actors=new ArrayList<>();final Map<RollingCubeActor,ArrayDeque<Event>> queues=new HashMap<>();
    @BeforeAll static void init(){var registry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());registry.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);palette=EnginePaletteContext.create(registry.freeze());provider=new NativeArenaCubeProvider(ArenaTerrainSource.FIXED,palette);diorite=Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());air=Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());}
    @AfterEach void close()throws Exception{actors.forEach(RollingCubeActor::close);for(var actor:actors)actor.closed().get(15,TimeUnit.SECONDS);}
    RollingCubeActor open(Path path,long epoch,CubePos center,AtomicBoolean admit){
        var policy=new ReplacementPolicy(provider.identity().generatorId(),io.earthplatform.minecraft.lod.DhArenaInput.MATERIAL_POLICY,io.earthplatform.minecraft.lod.DhArenaInput.DETAIL_POLICY,1,RollingEditPolicy.FULL_CUBE_V1);
        ArenaAdmission callback=c->new ArenaAdmission.Receipt(admit.get(),admit.get()?"":"test budget refusal",ARENA.identity(),c.contentIdentity(),provider.source().identity(),LAYOUT);
        var actor=RollingCubeActor.openArena(path,provider.identity(),provider,palette,epoch,center,policy,ARENA,provider.source().identity(),LAYOUT,callback);actors.add(actor);queues.put(actor,new ArrayDeque<>());return actor;
    }
    Event next(RollingCubeActor actor)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);var queue=queues.get(actor);while(queue.isEmpty()){queue.addAll(actor.pollEvents(2));if(queue.isEmpty()){if(System.nanoTime()>end)fail("Arena actor event timed out: "+actor.diagnostics());Thread.sleep(1);}}var event=queue.removeFirst();if(event instanceof Failure f)fail(f.detail());return event;}
    StageReady prepare(RollingCubeActor actor)throws Exception{var metadata=assertInstanceOf(StageMetadata.class,next(actor));assertFalse(metadata.currentPinned());assertFalse(metadata.candidatePinned());assertEquals(ARENA.origin(),metadata.pinCenter());Event e;while((e=next(actor)) instanceof Snapshot){}var ready=assertInstanceOf(StageReady.class,e);assertNotNull(ready.arenaCertificate());ready.arenaCertificate().verifyCapture(ready.arenaCapture());assertEquals(125,ready.versions().size());assertEquals(64,ready.arenaCertificate().dependencies().size());assertEquals(1,actor.arenaDiagnostics().bootstrapPeakOwners());assertEquals(1,actor.arenaDiagnostics().bootstrapPeakSnapshotCaches());assertEquals(11664,actor.arenaDiagnostics().capturePayloadBytes());assertTrue(actor.arenaDiagnostics().peakCaptureArrayBytes()<=23328);assertFalse(ready.pinned());assertNull(ready.activeFocus());assertNull(ready.replacementSet());assertTrue(actor.diagnostics().residentCubes()<=186);return ready;}
    void commit(RollingCubeActor actor,StageReady ready)throws Exception{assertEquals(Admission.QUEUED,actor.commitPrepared(ready.generation(),ready.arenaCertificate().identity()));assertInstanceOf(Committed.class,next(actor));assertTrue(actor.diagnostics().residentCubes()<=186);assertFalse(actor.diagnostics().currentPinned());assertTrue(actor.diagnostics().tickets()<=2);}
    static CubeVersion version(StageReady ready,CubePos p){return ready.versions().stream().filter(v->v.position().equals(p)).findFirst().orElseThrow();}
    EditAck edit(RollingCubeActor actor,long epoch,CubeVersion version,int state)throws Exception{var p=version.position();var request=new EditRequest(provider.identity(),epoch,p,version.leaseGeneration(),version.revision(),p.minBlockX()+8,p.minBlockY()+8,p.minBlockZ()+8,state);assertEquals(Admission.QUEUED,actor.tryEdit(request));Event e;while(!((e=next(actor)) instanceof EditAck))assertInstanceOf(Snapshot.class,e);return (EditAck)e;}
    @Test void allEightDurableRevisionsSingleUnionRetireRemoteAndLiteralRestart()throws Exception{
        var actor=open(dir.resolve("world"),7,CENTER,new AtomicBoolean(true));var ready=prepare(actor);commit(actor,ready);var certificate=ready.arenaCertificate();String clean=ready.arenaCapture().contentIdentity();
        for(var p:ARENA.positions()){
            var ack=edit(actor,7,version(ready,p),diorite);assertEquals(EditStatus.APPLIED,ack.status(),ack.detail());assertNotNull(ack.arenaCertificate());assertEquals(1,ack.arenaCertificate().revision(p));
            for(var q:ARENA.positions())assertEquals(q.compareTo(p)<=0?1:0,ack.arenaCertificate().revision(q));
            assertFalse(actor.replacementCurrent(certificate.identity()));assertTrue(actor.replacementCurrent(ack.arenaCertificate().identity()));certificate=ack.arenaCertificate();assertEquals(Admission.QUEUED,actor.authorizePinRetirement(ready.generation(),certificate.identity()));
        }
        assertNotEquals(clean,certificate.captureIdentity());String durable=certificate.durableIdentity();String content=certificate.captureIdentity();
        assertEquals(Admission.QUEUED,actor.requestCenter(new CubePos(5,9,0)));for(int step=1;step<=5;step++){var moved=prepare(actor);assertEquals(new CubePos(step,9,0),moved.candidateCenter());commit(actor,moved);assertEquals(content,moved.arenaCapture().contentIdentity());}
        assertTrue(actor.diagnostics().residentCubes()<=186);assertEquals(Admission.NOT_READY,actor.tryEdit(new EditRequest(provider.identity(),7,ARENA.origin(),1,1,8,136,8,air)));
        actor.close();assertTrue(actor.closed().get(15,TimeUnit.SECONDS).successful());
        var reopened=open(dir.resolve("world"),9,new CubePos(5,9,0),new AtomicBoolean(true));var restart=prepare(reopened);assertEquals(durable,restart.arenaCertificate().durableIdentity());assertNotEquals(certificate.identity(),restart.arenaCertificate().identity());assertEquals(content,restart.arenaCapture().contentIdentity());for(var p:ARENA.positions()){assertEquals(1,restart.arenaCertificate().revision(p));assertEquals(ArenaCapture.DIORITE,restart.arenaCapture().materialAt(p.minBlockX()+8,p.minBlockY()+8,p.minBlockZ()+8));}commit(reopened,restart);
    }
    @Test void pureBudgetRefusalPreservesRootRevisionCaptureAndPublication()throws Exception{
        AtomicBoolean admit=new AtomicBoolean(true);var actor=open(dir.resolve("world"),7,CENTER,admit);var ready=prepare(actor);commit(actor,ready);String identity=ready.arenaCertificate().identity();byte[] root=Files.readAllBytes(dir.resolve("world").resolve(ArenaReplacementIndex.ROOT_FILE));admit.set(false);
        var ack=edit(actor,7,version(ready,ARENA.origin()),diorite);assertEquals(EditStatus.REPLACEMENT_BUDGET,ack.status());assertEquals(0,ack.revision());assertTrue(actor.replacementCurrent(identity));assertArrayEquals(root,Files.readAllBytes(dir.resolve("world").resolve(ArenaReplacementIndex.ROOT_FILE)));assertFalse(Files.exists(dir.resolve("world").resolve(ArenaReplacementIndex.INTENT_FILE)));assertEquals(0,actor.diagnostics().admittedEdits());
        admit.set(true);ack=edit(actor,7,version(ready,ARENA.origin()),diorite);assertEquals(EditStatus.APPLIED,ack.status());assertEquals(1,ack.revision());
    }
    @Test void nativeHaloBoundaryRefusesNoExtraPinsAndMovementBoundaryIsExplicit()throws Exception{
        var actor=open(dir.resolve("world"),7,new CubePos(-1,9,0),new AtomicBoolean(true));var ready=prepare(actor);commit(actor,ready);assertEquals(Admission.NOT_READY,actor.tryEdit(new EditRequest(provider.identity(),7,ARENA.origin(),version(ready,ARENA.origin()).leaseGeneration(),0,8,136,8,diorite)));assertEquals(Admission.NOT_READY,actor.requestPinRestore());assertThrows(IllegalArgumentException.class,()->actor.requestCenter(new CubePos(16,9,0)));assertEquals(0,actor.diagnostics().admittedEdits());assertEquals(125,actor.diagnostics().residentCubes());
    }

    @Test void noOpWriteAdvancesOnlyTargetRevisionAndIndexWhileKeepingContentDigest()throws Exception{
        var actor=open(dir.resolve("world"),7,CENTER,new AtomicBoolean(true));var ready=prepare(actor);commit(actor,ready);var position=ARENA.origin();
        int material=ready.arenaCapture().materialAt(8,136,8);int state=Block.BLOCK_STATE_REGISTRY.getId((material==0?Blocks.AIR:material==1?Blocks.STONE:Blocks.DIORITE).defaultBlockState());
        var ack=edit(actor,7,version(ready,position),state);assertEquals(EditStatus.APPLIED,ack.status(),ack.detail());assertEquals(1,ack.revision());assertEquals(ready.arenaCapture().contentIdentity(),ack.arenaCapture().contentIdentity());assertNotEquals(ready.arenaCertificate().identity(),ack.arenaCertificate().identity());assertEquals(ready.arenaCertificate().queryAttestation().snapshotGeneration()+1,ack.arenaCertificate().queryAttestation().snapshotGeneration());
        for(var p:ARENA.positions())assertEquals(p.equals(position)?1:0,ack.arenaCertificate().revision(p));
    }
}
