package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeLease;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeVersion;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageMetadata;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageReady;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wire-authority adversary built without the server actor, native renderer, or replacement mesher. */
class RollingBoundaryReplicaIndependentReviewTest {
    static EnginePaletteContext palette;
    @BeforeAll static void registry() {
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(biomes.freeze());
    }
    private static final class Fixture implements AutoCloseable {
        final RollingSessionDescriptor descriptor;final RollingGateReplica replica;final CubePos pin;
        final List<CubeLease> leases=new ArrayList<>();long revision;final long epoch=7;
        Fixture(CoreGateProfile profile,boolean full,boolean legacy) {
            descriptor=RollingSessionDescriptor.analytic(profile,palette,full?RollingEditPolicy.FULL_CUBE_V1:RollingEditPolicy.INTERIOR_V1);
            pin=descriptor.pinCenter();replica=legacy?new RollingGateReplica(descriptor.world(),epoch,palette,profile):new RollingGateReplica(descriptor.world(),epoch,palette,descriptor);
            var plan=RollingCubePlan.stationary(pin,pin);long next=0;
            for(var p:plan.committedOwners())leases.add(new CubeLease(p,++next));
            var metadata=new StageMetadata(descriptor.world(),epoch,1,true,pin,pin,pin,List.of(),leases,plan.committedOwners(),List.of(),plan.currentTargets());
            replica.acceptMetadata(metadata);
            for(var l:leases)replica.acceptSnapshot(1,snapshot(l.position(),descriptor.world(),epoch,l.leaseGeneration(),0));
            replica.acceptReady(ready(0));replica.commitStage(1);
        }
        long lease(CubePos p){return leases.stream().filter(l->l.position().equals(p)).findFirst().orElseThrow().leaseGeneration();}
        EngineCubeSnapshot snapshot(CubePos position,WorldIdentity world,long epoch,long lease,long revision,int... indices) {
            var section=EngineCubeSection.uniform(palette,Blocks.AIR.defaultBlockState());
            for(int index:indices)section.set(index&15,(index>>>8)&15,(index>>>4)&15,Blocks.STONE.defaultBlockState());
            return EngineCubeSnapshot.capture(position,world,epoch,lease,revision,section);
        }
        EngineCubeSnapshot next(int... indices){return snapshot(pin,descriptor.world(),epoch,lease(pin),revision+1,indices);}
        StageReady ready(long pinRevision){return new StageReady(descriptor.world(),epoch,1,pin,leases.stream().map(l->new CubeVersion(l.position(),l.leaseGeneration(),l.position().equals(pin)?pinRevision:0)).toList());}
        void apply(int... indices){replica.beginEdit();replica.acceptSnapshot(1,next(indices));revision++;replica.acknowledge("APPLIED",revision);replica.acceptReady(ready(revision));}
        public void close(){replica.close();}
    }
    @Test void all26BoundaryCategoriesPermitExactlyOneAdditionAndRemovalAcrossAllProfiles() {
        for(var profile:CoreGateProfile.values())try(var f=new Fixture(profile,true,false)) {
            int count=0;
            for(int x:new int[]{0,7,15})for(int y:new int[]{0,7,15})for(int z:new int[]{0,7,15}) {
                if(x==7&&y==7&&z==7)continue;
                var prior=f.replica.cubes().get(f.pin).snapshot();var historical=f.replica.cubes().get(f.pin);
                f.apply(CubePos.index(x,y,z));assertTrue(f.replica.cubes().get(f.pin).state(x,y,z).is(Blocks.STONE));
                assertTrue(historical.state(x,y,z).isAir(),"Old immutable snapshot stays historical");
                assertFalse(f.replica.admission().isCurrentLoaded(header(prior)),"Historical revision must lose current authority");
                f.apply();assertTrue(f.replica.cubes().get(f.pin).state(x,y,z).isAir());count++;
            }
            assertEquals(26,count);assertEquals(52,f.replica.revision());assertEquals(125,f.replica.cubes().size());
            assertEquals(125,f.replica.admission().view().metrics().activeLeases());assertEquals(0,f.replica.admission().view().metrics().pendingAdmissions());
        }
    }
    @Test void bothLegacyReplicaConstructorAndInteriorDescriptorKeepEveryBoundaryImmutable() {
        for(boolean legacy:new boolean[]{true,false})try(var f=new Fixture(CoreGateProfile.BASE,false,legacy)) {
            f.replica.beginEdit();int rejected=0;
            for(int x:new int[]{0,7,15})for(int y:new int[]{0,7,15})for(int z:new int[]{0,7,15}) {
                if(x==7&&y==7&&z==7)continue;
                var next=f.next(CubePos.index(x,y,z));assertThrows(IllegalArgumentException.class,()->f.replica.acceptSnapshot(1,next));rejected++;
            }
            assertEquals(26,rejected);assertEquals(0,f.replica.revision());assertEquals(0,f.replica.admission().view().metrics().pendingAdmissions());
            f.replica.acceptSnapshot(1,f.next(CubePos.index(7,7,7)));f.replica.acknowledge("APPLIED",1);f.replica.acceptReady(f.ready(1));
        }
    }
    @Test void fullBoundaryPolicyStillRejectsMultipleEditsAdjacentCubeAndEveryIdentityFence() {
        try(var f=new Fixture(CoreGateProfile.BASE,true,false)) {
            int corner=CubePos.index(0,0,0);f.replica.beginEdit();var original=f.replica.cubes().get(f.pin);
            assertThrows(IllegalArgumentException.class,()->f.replica.acceptSnapshot(1,f.next(corner,CubePos.index(15,15,15))));
            assertThrows(IllegalArgumentException.class,()->f.replica.acceptSnapshot(2,f.next(corner)));
            for(int[] v:new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                var neighbor=f.pin.offset(v[0],v[1],v[2]);var next=f.snapshot(neighbor,f.descriptor.world(),f.epoch,f.lease(neighbor),1,corner);
                assertThrows(IllegalArgumentException.class,()->f.replica.acceptSnapshot(1,next));
            }
            for(long epoch:new long[]{0,6,8}) {
                var next=f.snapshot(f.pin,f.descriptor.world(),epoch,f.lease(f.pin),1,corner);
                assertThrows(IllegalArgumentException.class,()->f.replica.acceptSnapshot(1,next));
            }
            for(long lease:new long[]{f.lease(f.pin)-1,f.lease(f.pin)+1,Long.MAX_VALUE}) {
                var next=f.snapshot(f.pin,f.descriptor.world(),f.epoch,lease,1,corner);
                assertThrows(IllegalArgumentException.class,()->f.replica.acceptSnapshot(1,next));
            }
            for(long rev:new long[]{0,2,Long.MAX_VALUE}) {
                var next=f.snapshot(f.pin,f.descriptor.world(),f.epoch,f.lease(f.pin),rev,corner);
                assertThrows(IllegalArgumentException.class,()->f.replica.acceptSnapshot(1,next));
            }
            var w=f.descriptor.world();var wrong=new WorldIdentity(w.seed()+1,w.generatorId(),w.generatorVersion(),w.datasetVersion(),w.blockPaletteVersion());
            assertThrows(IllegalArgumentException.class,()->f.replica.acceptSnapshot(1,f.snapshot(f.pin,wrong,f.epoch,f.lease(f.pin),1,corner)));
            assertSame(original,f.replica.cubes().get(f.pin));assertEquals(0,f.replica.admission().view().metrics().pendingAdmissions());
            assertThrows(IllegalArgumentException.class,()->f.replica.acknowledge("UNKNOWN_STATUS",1));assertTrue(f.replica.paused());
            f.replica.acceptSnapshot(1,f.next(corner));assertThrows(IllegalArgumentException.class,()->f.replica.acceptSnapshot(1,f.next(corner)));
            assertThrows(IllegalStateException.class,()->f.replica.acknowledge("APPLIED",2));
            f.replica.acknowledge("APPLIED",1);assertThrows(IllegalArgumentException.class,()->f.replica.acceptReady(f.ready(0)));
            f.replica.acceptReady(f.ready(1));assertEquals(1,f.replica.revision());
        }
    }
    @Test void failedSaveAfterBoundarySnapshotNeverRestoresReadyOrAcceptsDelayedSuccess() {
        try(var f=new Fixture(CoreGateProfile.BASE,true,false)) {
            f.replica.beginEdit();f.replica.acceptSnapshot(1,f.next(CubePos.index(15,15,15)));
            assertThrows(IllegalStateException.class,()->f.replica.acknowledge("SAVE_FAILED",-1));
            assertTrue(f.replica.paused());assertThrows(IllegalStateException.class,f.replica::currentManifestDigest);
            assertThrows(IllegalStateException.class,()->f.replica.acknowledge("APPLIED",1));
            assertThrows(IllegalStateException.class,()->f.replica.acceptReady(f.ready(1)));
            assertThrows(IllegalStateException.class,()->f.replica.acceptSnapshot(1,f.next()));
            assertThrows(IllegalStateException.class,f.replica::beginEdit);
        }
    }
    @Test void closingDuringBoundaryBarrierRevokesCapturedAdmissionAndAllResidentOwners() {
        var f=new Fixture(CoreGateProfile.BASE,true,false);var gate=f.replica.admission();var old=f.replica.cubes().get(f.pin).snapshot();
        f.replica.beginEdit();f.replica.acceptSnapshot(1,f.next(CubePos.index(0,15,0)));f.close();
        assertFalse(gate.isCurrentLoaded(header(old)));assertEquals(0,gate.view().metrics().activeLeases());
        assertThrows(IllegalStateException.class,()->f.replica.acknowledge("APPLIED",1));
    }
    private static CubeStreamAdmission.Header header(EngineCubeSnapshot s){return new CubeStreamAdmission.Header(s.world(),s.epoch(),s.position(),s.leaseGeneration(),s.revision());}
}
