package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.network.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreGateReplicaTest {
    static EnginePaletteContext palette;static WorldIdentity world;static NativeCubeCodec codec;static AnalyticNativeCubeProvider provider;
    static List<CubePos> owners,targets;static List<Long> leases;
    @BeforeAll static void setup(){var registry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());registry.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);palette=EnginePaletteContext.create(registry.freeze());provider=new AnalyticNativeCubeProvider(0,AnalyticNativeCubeProvider.Config.flat(128),palette);world=provider.identity();codec=new NativeCubeCodec(world,palette);owners=CubeTickets.box(CoreGateCommands.CENTER,2,2,125).stream().sorted().toList();targets=CubeTickets.box(CoreGateCommands.CENTER,1,1,27).stream().sorted().toList();leases=java.util.stream.LongStream.rangeClosed(1,125).boxed().toList();}
    CoreGateReplica empty(){return new CoreGateReplica(world,1,palette,owners,leases,targets);}
    EngineCubeSnapshot snapshot(CubePos p,long epoch,long lease,long revision,boolean edited){var source=provider.load(p,new CancellationToken()).join();if(edited){int[] blocks=new int[4096];for(int i=0;i<4096;i++)blocks[i]=source.get(i);blocks[CubePos.index(4,1,4)]=Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());source=ImmutableCube.copyOf(blocks);}return codec.materialize(p,source,epoch,lease,revision);}
    EngineCubeSnapshot original(CubePos p){return snapshot(p,1,owners.indexOf(p)+1,0,false);}
    CoreGateReplica full(){var replica=empty();for(var p:owners)replica.accept(original(p));return replica;}
    @Test void actualBoundedNetworkEnvelopeAndNativeSnapshotsRoundTripEveryCube(){try(var replica=empty()){for(var p:owners){var original=original(p);var frame=CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.SNAPSHOT);b.writeLong(1);original.write(b);});var b=frame.open();try{assertEquals(CoreGatePayload.SNAPSHOT,b.readUnsignedByte());assertEquals(1,b.readLong());replica.accept(EngineCubeSnapshot.readFrame(b,world,1,palette));}finally{b.release();}}assertEquals(125,replica.cubes().size());assertEquals(0,replica.revision());assertThrows(UnsupportedOperationException.class,()->replica.cubes().clear());}}
    @Test void metadataRejectsUnboundedDuplicateChangedInterestAndLeases(){assertThrows(IllegalArgumentException.class,()->new CoreGateReplica(world,1,palette,owners.subList(0,124),leases,targets));var bad=new ArrayList<>(leases);bad.set(0,99L);assertThrows(IllegalArgumentException.class,()->new CoreGateReplica(world,1,palette,owners,bad,targets));assertThrows(IllegalArgumentException.class,()->new CoreGateReplica(world,0,palette,owners,leases,targets));}
    @Test void unknownIsNotAirAndIncompleteBoxCannotEdit(){try(var replica=empty()){assertEquals(-1,replica.revision());assertFalse(replica.cubes().containsKey(CoreGateCommands.TARGET));assertThrows(IllegalStateException.class,replica::beginEdit);}}
    @Test void staleEpochLeaseAndUnsolicitedPositionCannotPublish(){try(var replica=empty()){var p=owners.getFirst();assertThrows(IllegalArgumentException.class,()->replica.accept(snapshot(p,2,1,0,false)));assertThrows(IllegalArgumentException.class,()->replica.accept(snapshot(p,1,2,0,false)));assertThrows(IllegalArgumentException.class,()->replica.accept(snapshot(new CubePos(9,8,9),1,1,0,false)));assertEquals(0,replica.cubes().size());}}
    @Test void changedWorldNeverPublishes(){try(var replica=empty()){var other=new WorldIdentity(8,world.generatorId(),world.generatorVersion(),world.datasetVersion(),world.blockPaletteVersion());var different=new NativeCubeCodec(other,palette).materialize(owners.getFirst(),ImmutableCube.uniform(0),1,1,0);assertThrows(IllegalArgumentException.class,()->replica.accept(different));}}
    @Test void editAckRequiresBarrierAndExactChangedSnapshot(){try(var replica=full()){long lease=owners.indexOf(CoreGateCommands.TARGET)+1;var edit=snapshot(CoreGateCommands.TARGET,1,lease,1,true);assertThrows(IllegalStateException.class,()->replica.accept(edit));assertThrows(IllegalStateException.class,()->replica.acknowledge("APPLIED",1));replica.beginEdit();assertThrows(IllegalStateException.class,()->replica.acknowledge("APPLIED",1));assertTrue(replica.paused());replica.accept(edit);replica.acknowledge("APPLIED",1);assertFalse(replica.paused());assertEquals(1,replica.acknowledgedRevision());assertTrue(replica.cubes().get(CoreGateCommands.TARGET).state(4,1,4).is(Blocks.DIORITE));}}
    @Test void replayedAckCannotReleaseANewBarrierWithoutItsReplacement(){try(var replica=full()){replica.beginEdit();assertThrows(IllegalStateException.class,()->replica.acknowledge("APPLIED",0));assertTrue(replica.paused());assertEquals(0,replica.revision());}}
    @Test void failedDurabilityRetainsFenceAndNoOptimisticRevision(){try(var replica=full()){replica.beginEdit();assertThrows(IllegalStateException.class,()->replica.acknowledge("SAVE_FAILED",-1));assertTrue(replica.paused());assertEquals(0,replica.revision());}}
    @Test void staleReplacementCannotSupersedeAdmittedRevision(){try(var replica=full()){replica.beginEdit();assertThrows(IllegalArgumentException.class,()->replica.accept(original(CoreGateCommands.TARGET)));assertTrue(replica.paused());}}
    @Test void envelopeOwnsBytesAndEnforcesMaximum(){byte[] bytes={5,1};var frame=new CoreGatePayload(bytes);bytes[0]=9;var b=frame.open();try{assertEquals(5,b.readUnsignedByte());assertTrue(b.isReadOnly());}finally{b.release();}assertThrows(IllegalArgumentException.class,()->new CoreGatePayload(new byte[0]));assertThrows(IllegalArgumentException.class,()->new CoreGatePayload(new byte[CoreGatePayload.MAX_BYTES+1]));}
    @Test void closedReplicaCannotBeReopenedByLatePackets(){var replica=full();replica.close();assertThrows(IllegalStateException.class,()->replica.accept(original(owners.getFirst())));assertThrows(IllegalStateException.class,replica::beginEdit);}
}
