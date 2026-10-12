package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual actor/store -> resume metadata/27 bootstrap wire -> replica/material verification. No GPU claim. */
@Timeout(90)
class RollingEarthResumeTest {
    private static EnginePaletteContext palette;private static WorldIdentity world;private static RollingSessionDescriptor original;
    private static int air,diorite;private static CubeProvider provider;
    @TempDir Path directory;
    @BeforeAll static void setup(){
        var r=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        r.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(r.freeze());
        world=new WorldIdentity(EarthAlpineDefinition.SEED,NativeAlpineCubeProvider.GENERATOR_ID,NativeAlpineCubeProvider.GENERATOR_VERSION+"/"+RollingGateFixture.VERSION,EarthAlpineDefinition.DEFINITION_IDENTITY,NativeCubeCodec.paletteIdentity(palette));
        original=RollingSessionDescriptor.alpine(world,palette,EarthAlpineDefinition.ANCHOR_X,Math.toIntExact(EarthAlpineDefinition.GROUND_Y),EarthAlpineDefinition.ANCHOR_Z);
        air=Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());diorite=Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
        provider=(p,c)->CompletableFuture.completedFuture(ImmutableCube.uniform(air));
    }
    private RollingCubeActor open(RollingSessionDescriptor descriptor,long epoch,CubeProvider source){
        var policy=new ReplacementPolicy(world.datasetVersion(),EarthReplacementAdmission.MATERIAL_POLICY,EarthReplacementAdmission.DETAIL_POLICY,1);
        return RollingCubeActor.resumeEarth(directory,world,source,palette,epoch,descriptor.pinCenter(),descriptor.initialCenter(),policy,
                world.datasetVersion(),descriptor.lodLayout().identity(),c->new EarthReplacementAdmission.Receipt(true,"exact bounded test source",c.contentIdentity(),world.datasetVersion(),descriptor.lodLayout().identity()));
    }
    @Test void heldPoseAndFixedStorePinRoundTripIndependently(){
        for(Vec3 position:new Vec3[]{original.entry(),original.entry().add(16,8,0),original.entry().add(160,80,-64)}){
            var resumed=original.resumeAt(position);assertEquals(position,resumed.entry());assertEquals(original.pinCenter(),resumed.pinCenter());assertEquals(CubePos.fromBlock((long)Math.floor(position.x),(long)Math.floor(position.y),(long)Math.floor(position.z)),resumed.initialCenter());
            assertEquals(original.saveDirectory(),resumed.saveDirectory());assertEquals(original.sourceFingerprint(),resumed.sourceFingerprint());assertTrue(resumed.samePreparedSource(original));
            assertFalse(resumed.permitsEdit(resumed.initialCenter().minBlockX()+4,resumed.initialCenter().minBlockY()+4,resumed.initialCenter().minBlockZ()+4)&&!resumed.initialCenter().equals(original.pinCenter()));
            var b=new FriendlyByteBuf(Unpooled.buffer());try{resumed.write(b);assertEquals(resumed,RollingSessionDescriptor.read(b,world,palette));assertFalse(b.isReadable());}finally{b.release();}
        }
        assertThrows(IllegalArgumentException.class,()->original.resumeAt(original.entry().add(300,0,0)));
        assertThrows(IllegalArgumentException.class,()->original.resumeAt(new Vec3(Double.NaN,7314,0)));
    }
    @Test void distantResumePreservesSavedRevisionAndExactCaptureThenRestoresOriginalPin()throws Exception{
        try(var store=new FileEditStore(directory,world)){store.save(new EditSnapshot(original.pinCenter(),1,Map.of(CubePos.index(4,4,4),diorite)));}
        String identity;byte[] before=Files.readAllBytes(directory.resolve("cubes/"+original.pinCenter().x()+"_"+original.pinCenter().y()+"_"+original.pinCenter().z()+".cube"));
        try(var first=new Harness(original,801)){var ready=first.receive();first.commit(ready);identity=ready.earthReplacementCapture().contentIdentity();assertEquals(1,ready.replacementCertificate().originalRevision());}
        var resumed=original.resumeAt(original.entry().add(160,32,-64));
        try(var h=new Harness(resumed,802)){
            var ready=h.receive();assertEquals(27,h.bootstrapCount);assertEquals(125,ready.versions().size());assertFalse(ready.pinned());assertEquals(resumed.initialCenter(),ready.candidateCenter());
            assertEquals(1,ready.replacementCertificate().originalRevision());assertEquals(identity,ready.earthReplacementCapture().contentIdentity());assertFalse(h.replica.cubes().containsKey(original.pinCenter()));
            h.commit(ready);assertEquals(Admission.NOT_READY,h.actor.requestPinRestore());
            var center=resumed.initialCenter();while(!center.equals(original.pinCenter())){center=RollingCubePlan.nextStep(center,original.pinCenter());assertEquals(Admission.QUEUED,h.actor.requestCenter(center));ready=h.receive();h.commit(ready);}
            assertTrue(ready.pinned());assertEquals(1,ready.replacementCertificate().originalRevision());assertEquals(identity,ready.earthReplacementCapture().contentIdentity());
        }
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("cubes/"+original.pinCenter().x()+"_"+original.pinCenter().y()+"_"+original.pinCenter().z()+".cube")));
    }
    @Test void missingOriginalBootstrapFailsBeforeAnyReadyAndNeverClaimsSuccessfulClose()throws Exception{
        var resumed=original.resumeAt(original.entry().add(128,0,0));var target=original.pinCenter().offset(1,1,1);
        CubeProvider missing=(p,c)->p.equals(target)?CompletableFuture.failedFuture(new IllegalStateException("deliberate missing original dependency")):provider.load(p,c);
        try(var actor=open(resumed,803,missing)){
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!actor.closed().isDone()){for(var e:actor.pollEvents(2))assertFalse(e instanceof StageReady);if(System.nanoTime()>end)fail("missing bootstrap did not settle");Thread.sleep(1);}
            assertFalse(actor.closed().get().successful());
        }
    }
    private final class Harness implements AutoCloseable {
        final RollingSessionDescriptor descriptor;final RollingCubeActor actor;final RollingGateReplica replica;final RollingEarthResumeBootstrap bootstrap;
        final ArrayDeque<Event> events=new ArrayDeque<>();int bootstrapCount;boolean initial=true;
        Harness(RollingSessionDescriptor descriptor,long epoch){this.descriptor=descriptor;actor=open(descriptor,epoch,provider);replica=new RollingGateReplica(world,epoch,palette,descriptor);bootstrap=descriptor.earthResumeBootstrap()?new RollingEarthResumeBootstrap(descriptor,epoch,palette):null;}
        Event next()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(events.isEmpty()){events.addAll(actor.pollEvents(2));if(events.isEmpty()){if(System.nanoTime()>end)fail("actor wait "+actor.diagnostics());Thread.sleep(1);}}var e=events.removeFirst();if(e instanceof Failure f)fail(f.detail());return e;}
        StageMetadata metadataWire(StageMetadata value){var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeMetadata(b,value);var result=RollingGateWire.readMetadata(b,world,value.epoch());assertFalse(b.isReadable());return result;}finally{b.release();}}
        io.earthplatform.minecraft.network.EngineCubeSnapshot snapshotWire(io.earthplatform.minecraft.network.EngineCubeSnapshot value){var b=new FriendlyByteBuf(Unpooled.buffer());try{value.write(b);var result=io.earthplatform.minecraft.network.EngineCubeSnapshot.read(b,world,value.epoch(),palette);assertFalse(b.isReadable());return result;}finally{b.release();}}
        StageReady readyWire(StageReady value){var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeReady(b,value);var result=RollingGateWire.readReady(b,world,value.epoch());assertFalse(b.isReadable());return result;}finally{b.release();}}
        StageReady receive()throws Exception{for(;;){var e=next();if(e instanceof StageMetadata metadata){replica.acceptMetadata(metadataWire(metadata));}else if(e instanceof EarthBootstrapSnapshot v){assertNotNull(bootstrap);bootstrap.accept(v.generation(),snapshotWire(v.snapshot()));bootstrapCount++;}else if(e instanceof Snapshot v)replica.acceptSnapshot(v.generation(),snapshotWire(v.snapshot()));else if(e instanceof StageReady raw){var ready=readyWire(raw);replica.acceptReady(ready);if(initial){var cubes=bootstrap==null?replica.cubes():bootstrap.cubes();try(var validation=new RollingEarthCaptureValidation(ready.replacementCertificate(),ready.earthReplacementCapture())){while(!validation.step(cubes)){}assertEquals(ready.earthReplacementCapture(),validation.finish(cubes));}if(bootstrap!=null)bootstrap.close();initial=false;}return ready;}}}
        void commit(StageReady r)throws Exception{assertEquals(Admission.QUEUED,actor.commitPrepared(r.generation(),r.replacementCertificate().identity()));assertInstanceOf(Committed.class,next());replica.commitStage(r.generation());assertEquals(Admission.QUEUED,actor.authorizePinRetirement(r.generation(),r.replacementCertificate().identity()));}
        public void close()throws Exception{actor.close();assertTrue(actor.closed().get(20,TimeUnit.SECONDS).successful());if(bootstrap!=null)bootstrap.close();replica.close();}
    }
}
