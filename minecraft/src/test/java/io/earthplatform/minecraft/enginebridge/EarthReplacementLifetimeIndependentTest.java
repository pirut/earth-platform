package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independently exercises issuer lease lifetime at signed high-XZ coordinates. */
@Timeout(60)
class EarthReplacementLifetimeIndependentTest {
    static final CubePos P=new CubePos(604879,457,-194643);
    static final String SOURCE="independent-earth-lifetime",LAYOUT="independent-earth-layout";
    static EnginePaletteContext palette;static WorldIdentity world;static int air,stone;
    @TempDir Path dir;
    @BeforeAll static void setup(){var r=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());r.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);palette=EnginePaletteContext.create(r.freeze());world=new WorldIdentity(717,"independent:earth-lifetime","v1",SOURCE,NativeCubeCodec.paletteIdentity(palette));air=Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());stone=Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());}
    final ArrayDeque<Event> pending=new ArrayDeque<>();
    Event event(RollingCubeActor a)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(pending.isEmpty()){pending.addAll(a.pollEvents(2));if(pending.isEmpty()){if(System.nanoTime()>deadline)fail("No actor event: "+a.diagnostics());Thread.sleep(1);}}var next=pending.remove();if(next instanceof Failure f)fail(f.detail());return next;}
    StageReady ready(RollingCubeActor a)throws Exception{for(;;){var e=event(a);if(e instanceof StageMetadata m)assertFalse(m.candidatePinned()&&!RollingCubePlan.adjacent(P,m.candidateCenter()),"Remote native pin emitted");if(e instanceof StageReady r)return r;}}
    void commit(RollingCubeActor a,StageReady r)throws Exception{assertEquals(Admission.QUEUED,a.commitPrepared(r.generation(),r.replacementCertificate().identity()));assertInstanceOf(Committed.class,event(a));}
    StageReady move(RollingCubeActor a,int offset)throws Exception{assertEquals(Admission.QUEUED,a.requestCenter(P.offset(offset,0,0)));var r=ready(a);commit(a,r);return r;}
    CubeVersion original(StageReady r){return r.versions().stream().filter(v->v.position().equals(P)).findFirst().orElseThrow();}
    EditAck edit(RollingCubeActor a,CubeVersion v)throws Exception{assertEquals(Admission.QUEUED,a.tryEdit(new EditRequest(world,717,P,v.leaseGeneration(),v.revision(),P.minBlockX()+4,P.minBlockY()+4,P.minBlockZ()+4,stone)));for(;;){var e=event(a);if(e instanceof EditAck ack)return ack;}}
    @Test void noRemotePinBeforeRetirementAndOldLeaseCannotEditAfterHighCoordinateReacquisition()throws Exception{
        var policy=new ReplacementPolicy(SOURCE,EarthReplacementAdmission.MATERIAL_POLICY,EarthReplacementAdmission.DETAIL_POLICY,1);
        var a=RollingCubeActor.openEarth(dir,world,(p,c)->CompletableFuture.completedFuture(ImmutableCube.uniform(air)),palette,717,P,policy,SOURCE,LAYOUT,c->new EarthReplacementAdmission.Receipt(true,"bounded test layout",c.contentIdentity(),SOURCE,LAYOUT));
        try{
            var initial=ready(a);assertEquals(0,initial.replacementCertificate().originalRevision());commit(a,initial);var first=move(a,1);
            assertEquals(Admission.QUEUED,a.requestCenter(P.offset(2,0,0)));Thread.sleep(150);assertTrue(a.pollEvents(2).isEmpty(),"Must wait for exact paired pin-retirement authority");assertEquals(P.offset(1,0,0),a.diagnostics().currentCenter());
            assertEquals(Admission.QUEUED,a.authorizePinRetirement(first.generation(),first.replacementCertificate().identity()));var second=ready(a);assertFalse(second.pinned());commit(a,second);assertEquals(Admission.NOT_READY,a.requestPinRestore());
            StageReady far=second;for(int x=3;x<=8;x++)far=move(a,x);assertFalse(far.versions().stream().anyMatch(v->v.position().equals(P)));assertSame(initial.earthReplacementCapture(),far.earthReplacementCapture());
            StageReady back=far;for(int x=7;x>=0;x--)back=move(a,x);assertTrue(back.pinned());assertTrue(original(back).leaseGeneration()>original(initial).leaseGeneration());assertSame(initial.replacementCertificate(),back.replacementCertificate());
            var refused=edit(a,original(initial));assertEquals(EditStatus.STALE_LEASE,refused.status());assertTrue(a.replacementCurrent(back.replacementCertificate().identity()));
            var applied=edit(a,original(back));assertTrue(applied.applied(),applied.detail());assertEquals(1,applied.revision());assertTrue(applied.replacementCertificate().generation()>back.replacementCertificate().generation());assertFalse(a.replacementCurrent(back.replacementCertificate().identity()));assertEquals(1,applied.earthReplacementCapture().materialAt(P.minBlockX()+4,P.minBlockY()+4,P.minBlockZ()+4));
        }finally{a.close();assertTrue(a.closed().get(15,TimeUnit.SECONDS).successful());}
    }
}
