package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import java.util.List;
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

/** Actual durable actor, exact wire and replica focus/lease protocol; no GPU claim. */
class RollingTwoWorkspaceReplicaIntegrationTest {
 static EnginePaletteContext palette;
 @TempDir Path directory;
 @BeforeAll static void registry(){var r=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());r.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);palette=EnginePaletteContext.create(r.freeze());}
 @Test void serialBootstrapFocusHandoffAndSafeRefusalRoundTripThroughRealReplica()throws Exception{
  var d=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1,true);
  var provider=RollingGateFixture.provider(palette,CoreGateProfile.BASE);
  var policy=new ReplacementPolicy(d.sourceFingerprint(),DhReplacementInput.ANALYTIC_MATERIAL_POLICY,RollingGateFixture.REPLACEMENT_DETAIL_POLICY,1,RollingEditPolicy.FULL_CUBE_V1);
  var actor=RollingCubeActor.open(directory,d.world(),provider,palette,71,d.center(),policy,d.flatPolicy());var events=new ArrayDeque<Event>();var replica=new RollingGateReplica(d.world(),71,palette,d);
  var authority=new RollingReplacementSetAuthority(d.world(),71,1,d.workspaces(),d.sourceFingerprint(),palette.fingerprint(),policy.materialPolicy(),policy.detailPolicy());
  try{
   var first=receive(actor,events,replica);assertEquals(55,first.versions().getFirst().leaseGeneration());assertEquals(2,first.replacementCaptures().size());assertEquals(1,first.replacementCaptures().getFirst().materialAt(3,132,4));
   authority.propose(first.replacementSet());authority.commit(first.replacementSet().identity());commit(actor,events,replica,first);
   StageReady ready=first;
   for(int x=1;x<=3;x++){actor.requestCenter(d.center().offset(x,0,0));ready=receive(actor,events,replica);authority.propose(ready.replacementSet());authority.commit(ready.replacementSet().identity());commit(actor,events,replica,ready);}
   assertFalse(replica.currentPinned());assertEquals(d.workspaces().original(),replica.activeFocus());
   assertEquals(Admission.QUEUED,actor.requestFocus(d.workspaces().second(),ready.generation(),ready.replacementSet().identity()));
   var switched=receive(actor,events,replica);assertEquals(d.workspaces().second(),switched.activeFocus());commit(actor,events,replica,switched);assertEquals(d.workspaces().second(),replica.activeFocus());assertTrue(replica.currentPinned());assertEquals(125,replica.cubes().size());
   var old=replica.cubes().get(replica.activeFocus()).snapshot();replica.beginEdit();authority.propose(switched.replacementSet());authority.commit(switched.replacementSet().identity());authority.beginEdit(replica.activeFocus());
   var request=new EditRequest(d.world(),71,replica.activeFocus(),old.leaseGeneration(),old.revision(),68,129,4,Block.BLOCK_STATE_REGISTRY.getId(Blocks.GLASS.defaultBlockState()));
   assertEquals(Admission.QUEUED,actor.tryEdit(request));var ack=assertInstanceOf(EditAck.class,next(actor,events));assertFalse(ack.applied());assertEquals(EditStatus.UNSUPPORTED_REPLACEMENT,ack.status());replica.acknowledge(ack.status().name(),ack.revision());
   var restored=RollingEditAcknowledgement.ready(switched,replica.activeFocus(),switched.versions(),ack);var wire=roundTrip(restored);replica.acceptReady(wire);authority.propose(wire.replacementSet());authority.commit(wire.replacementSet().identity());assertTrue(actor.replacementCurrent(wire.replacementSet().identity()));assertTrue(authority.isCurrent(wire.replacementSet().identity()));assertEquals(0,replica.revision());
  }finally{authority.close();replica.close();actor.close();assertTrue(actor.closed().get(10,TimeUnit.SECONDS).successful());}
 }
 private static StageReady receive(RollingCubeActor actor,ArrayDeque<Event> events,RollingGateReplica replica)throws Exception{var metadata=assertInstanceOf(StageMetadata.class,next(actor,events));var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeMetadata(b,metadata);replica.acceptMetadata(RollingGateWire.readMetadata(b,metadata.world(),metadata.epoch()));}finally{b.release();}Event e;while((e=next(actor,events))instanceof Snapshot s)replica.acceptSnapshot(s.generation(),s.snapshot());var ready=roundTrip(assertInstanceOf(StageReady.class,e));replica.acceptReady(ready);return ready;}
 private static StageReady roundTrip(StageReady ready){var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeReady(b,ready);var result=RollingGateWire.readReady(b,ready.world(),ready.epoch());assertEquals(ready.replacementSet(),result.replacementSet());assertEquals(ready.activeFocus(),result.activeFocus());assertFalse(b.isReadable());return result;}finally{b.release();}}
 private static void commit(RollingCubeActor actor,ArrayDeque<Event> events,RollingGateReplica replica,StageReady ready)throws Exception{assertEquals(Admission.QUEUED,actor.commitPrepared(ready.generation(),ready.replacementSet().identity()));assertInstanceOf(Committed.class,next(actor,events));replica.commitStage(ready.generation());assertEquals(Admission.QUEUED,actor.authorizePinRetirement(ready.generation(),ready.replacementSet().identity()));}
 private static Event next(RollingCubeActor actor,ArrayDeque<Event> events)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(events.isEmpty()){events.addAll(actor.pollEvents(2));if(events.isEmpty()){assertTrue(System.nanoTime()<until,"Actor timeout: "+actor.diagnostics());Thread.sleep(1);}}var e=events.removeFirst();if(e instanceof Failure f)fail(f.detail());return e;}
}
