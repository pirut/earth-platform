package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.minecraft.visual.EarthDistanceSurfacePlan;
import io.earthplatform.minecraft.visual.NativeDistanceLimits;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real snapshot/Source proof tests. Any native completion is the explicitly modeled receipt fixture. */
class RollingDistanceBodyBindingTest {
    @Test void all125AndAll4096CellsAreRequiredBeforeReadyAndCountersUseTheSeparateHardCap(){
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.begin();int liveBefore=f.fullResourceChecks.get();
            var first=f.binding.step();assertEquals(262_144,first.comparisonsThisSlice());assertEquals(262_144,first.completedComparisons());assertFalse(first.complete());
            assertEquals(2,f.fullResourceChecks.get()-liveBefore,"Full capsule/resource check only at slice entry and exit, never per64 cells");
            assertFalse(f.binding.partialAliasHeld());assertThrows(IllegalStateException.class,()->f.binding.join(null));
            var second=f.binding.step();assertEquals(249_856,second.comparisonsThisSlice());assertEquals(512_000,second.completedComparisons());assertTrue(second.complete());
            var noOp=f.binding.step();assertEquals(0,noOp.comparisonsThisSlice());assertEquals(512_000,noOp.completedComparisons());assertTrue(noOp.complete());
            assertEquals(125,f.binding.snapshots().size());assertTrue(f.owner.accounting().metadataBytes()<1_048_576);assertEquals(125,f.owner.accounting().payloadIdentities());
        }
    }
    @Test void oneCellForgeryOutsideRead20WithUnchangedHeaderCannotMintAuthority(){
        for(int cell:new int[]{0,15,255,256,4095})try(var f=new DistanceBodyTestFixture()){
            var outside=DistanceBodyTestFixture.PIN.offset(-2,-2,-2);
            f.stage(DistanceBodyTestFixture.PIN,outside,cell,Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState()));f.begin();
            assertThrows(IllegalStateException.class,()->{while(!f.binding.step().complete()){} });
            assertFalse(f.binding.dependenciesCurrent());assertFalse(f.binding.complete());assertNull(f.binding.joined());
            assertFalse(f.binding.partialAliasHeld());
        }
    }
    @Test void theSixExactDefaultStatesPassAndSeventhSceneSupportedMaterialFails(){
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);var seen=new HashSet<Integer>();
            for(var cube:f.replica.cubes().values())for(int i=0;i<4096;i++)seen.add(RollingEarthCaptureValidation.material(cube.state(i&15,i>>>8,i>>>4&15)));
            assertEquals(Set.of(0,1,2,3,4,5),seen);f.prove();assertTrue(f.binding.complete());
        }
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN,DistanceBodyTestFixture.PIN.offset(2,2,2),4095,Block.BLOCK_STATE_REGISTRY.getId(Blocks.GRAVEL.defaultBlockState()));
            f.begin();assertThrows(IllegalArgumentException.class,()->{while(!f.binding.step().complete()){} });assertFalse(f.binding.complete());
        }
    }
    @Test void headerCostAloneConsumesSoftBudgetBeforeAnyComparison(){
        AtomicLong clock=new AtomicLong();try(var f=new DistanceBodyTestFixture(()->clock.getAndAdd(2_000_000L))){
            f.stage(DistanceBodyTestFixture.PIN);f.begin();var slice=f.binding.step();assertEquals(0,slice.comparisonsThisSlice());assertEquals(0,slice.completedComparisons());
            assertEquals(2_000_000L,slice.preGuardNanos());assertTrue(slice.elapsedNanos()>=6_000_000L);assertFalse(slice.complete());assertFalse(f.binding.partialAliasHeld());
        }
    }
    @Test void sixtyFourCellPollStopsPartialWorkWithoutCreditingItAndCancelDropsTheAlias(){
        AtomicLong calls=new AtomicLong();try(var f=new DistanceBodyTestFixture(()->calls.getAndIncrement()<2?0:2_000_000L)){
            f.stage(DistanceBodyTestFixture.PIN);f.begin();var slice=f.binding.step();assertEquals(64,slice.comparisonsThisSlice());assertEquals(0,slice.completedComparisons());assertTrue(f.binding.partialAliasHeld());
            var prior=f.binding;f.cancel();assertFalse(prior.partialAliasHeld());assertTrue(prior.isClosed());assertTrue(f.aliasesReleased());assertTrue(prior.snapshots().isEmpty());
        }
    }
    @Test void sameHeaderPayloadReplacementInvalidatesExactObjectBindingAndCannotComeBack()throws Exception{
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.prove();var original=f.replica.cubes().get(DistanceBodyTestFixture.PIN);
            var s=original.snapshot();var different=f.snapshot(s.position(),s.leaseGeneration(),s.revision(),4095,Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState()));
            mutableCubes(f).put(s.position(),new SceneCube(different,f.palette));assertFalse(f.binding.dependenciesCurrent());
            mutableCubes(f).put(s.position(),original);assertFalse(f.binding.dependenciesCurrent(),"Exact object ABA cannot resurrect a failed current binding");
        }
    }
    @Test void completedProofsRetainOnlySnapshotSourceAndApplicableCaptureNeverDecodedCube()throws Exception{
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.prove();assertFalse(f.binding.partialAliasHeld());
            Object[] proofs=(Object[])field(f.binding,"proofs");int edited=0;
            for(Object proof:proofs){
                assertEquals(3,proof.getClass().getDeclaredFields().length);
                for(var declared:proof.getClass().getDeclaredFields()){assertNotEquals(SceneCube.class,declared.getType());assertFalse(declared.getType().isArray());}
                var snapshot=(EngineCubeSnapshot)field(proof,"snapshot");assertSame(f.source,field(proof,"source"));
                if(snapshot.position().equals(DistanceBodyTestFixture.PIN)){assertSame(f.capture,field(proof,"capture"));edited++;}else assertNull(field(proof,"capture"));
            }assertEquals(1,edited);
            var saved=f.binding;f.cancel();assertNull(field(saved,"proofs"));assertNull(field(saved,"plan"));assertNull(field(saved,"replacement"));assertNull(field(saved,"ready"));
        }
    }
    @Test void actualJoinIsPrivateUntilInstalledAndExact27CannotBeForged(){
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.join();assertTrue(f.joined.isPrepared());assertFalse(f.joined.isPublishedCurrent());assertEquals(27,f.joined.exactReady().size());
            assertThrows(IllegalStateException.class,()->f.joined.requirePrepared(List.of(),f.visual,f.replacement));
            assertThrows(IllegalStateException.class,f.joined::retainPresentation);
            f.install();assertTrue(f.joined.isPrepared());assertTrue(f.joined.isPublishedCurrent());f.binding.requireCommittedSnapshots();
            var wrong=new LinkedHashMap<CubePos,EngineCubeSnapshot>();for(var cube:f.replica.cubes().values())wrong.put(cube.snapshot().position(),cube.snapshot());wrong.remove(DistanceBodyTestFixture.PIN);
            assertThrows(IllegalStateException.class,()->f.binding.requireCommittedSnapshots(wrong));
        }
    }
    @Test void axisMoveReusesExactly100ImmutableFactsAndMintsFreshRequestAuthority(){
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.join();f.install();var old=f.joined;var previous=f.binding;
            f.stage(DistanceBodyTestFixture.PIN.offset(1,0,0));f.begin();assertEquals(100,f.binding.reusedSnapshots());var slice=f.binding.step();assertTrue(slice.complete());assertEquals(102_400,slice.comparisonsThisSlice());assertEquals(102_400,slice.completedComparisons());
            assertTrue(old.isPublishedCurrent());assertNotSame(previous,f.binding);f.join();var next=f.joined;assertFalse(next.isPublishedCurrent());f.install();assertTrue(next.isPublishedCurrent());assertFalse(old.isPrepared());assertTrue(previous.snapshots().isEmpty());
        }
    }
    @Test void samePublicSourceStringsDoNotReuseAChangedSourceObject(){
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.join();f.install();var prior=f.source;f.source=DistanceBodyTestFixture.newSource();assertEquals(prior.identity(),f.source.identity());assertNotSame(prior,f.source);
            f.stage(DistanceBodyTestFixture.PIN.offset(1,0,0));f.begin();assertEquals(0,f.binding.reusedSnapshots());f.prove();assertEquals(512_000,f.binding.completedComparisons());
        }
    }
    @Test void exactHistoryAllowsOnlyTheOldLoadedLeaseAndNeverCurrentReadiness(){
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.join();f.install();var prior=f.joined;var oldSnapshot=f.replica.cubes().get(DistanceBodyTestFixture.PIN).snapshot();var history=f.retainPresentation();
            f.editOne();assertFalse(prior.isPrepared());assertFalse(prior.isPublishedCurrent());assertTrue(history.isPresentable());assertSame(prior,history.joined());assertTrue(prior.permitsRetainedPresentation());
            f.begin();assertEquals(124,f.binding.reusedSnapshots());f.prove();assertEquals(4096,f.binding.completedComparisons());assertNotSame(oldSnapshot,f.replica.cubes().get(DistanceBodyTestFixture.PIN).snapshot());
            f.join();assertThrows(IllegalStateException.class,f::install);history.close();f.install();assertFalse(prior.permitsRetainedPresentation());assertFalse(history.isPresentable());
        }
    }
    @Test void changedCaptureObjectReprovesOnlyOriginalEvenWhenItsContentIsEqual(){
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.join();f.install();var old=f.capture;f.capture=DistanceBodyTestFixture.capture(f.source);assertNotSame(old,f.capture);assertEquals(old.contentIdentity(),f.capture.contentIdentity());
            f.stage(DistanceBodyTestFixture.PIN.offset(1,0,0));f.begin();assertEquals(99,f.binding.reusedSnapshots());f.prove();assertEquals(26*4096,f.binding.completedComparisons());
        }
    }
    @Test void resourceInvalidationAndOffThreadRevokeFenceEveryCapability()throws Exception{
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.join();f.install();var history=f.retainPresentation();
            Thread thread=new Thread(f.owner::revoke);thread.start();thread.join();assertFalse(f.joined.isPrepared());assertFalse(history.isPresentable());
        }
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.prove();f.live.set(false);assertFalse(f.binding.dependenciesCurrent());f.live.set(true);assertFalse(f.binding.dependenciesCurrent());
        }
    }
    @Test void failedCleanupRetainsAliasesAndBudgetUntilRetryThenClearsEveryHolder()throws Exception{
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.prove();var prior=f.binding;
            prior.failCleanupForTest(()->{throw new IllegalStateException("injected body alias release failure");});
            assertThrows(IllegalStateException.class,f.owner::close);assertFalse(f.aliasesReleased());assertFalse(prior.dependenciesCurrent());assertNotNull(field(prior,"plan"));assertEquals(125,f.accounting().payloadIdentities());
            assertThrows(IllegalStateException.class,()->f.owner.begin(2,f.stamp,f.plan,f.replacement));
            prior.failCleanupForTest(()->{});f.owner.close();assertTrue(f.aliasesReleased());assertNull(field(prior,"plan"));assertNull(field(prior,"proofs"));assertTrue(prior.snapshots().isEmpty());f.owner.close();
        }
    }
    @Test void all26AdjacentDirectionsReuseOnlyExactOverlap(){
        for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            if(dx==0&&dy==0&&dz==0)continue;
            try(var f=new DistanceBodyTestFixture()){
                f.stage(DistanceBodyTestFixture.PIN);f.join();f.install();
                f.stage(DistanceBodyTestFixture.PIN.offset(dx,dy,dz));f.begin();
                int reused=(5-Math.abs(dx))*(5-Math.abs(dy))*(5-Math.abs(dz));
                assertEquals(reused,f.binding.reusedSnapshots());f.prove();
                assertEquals((125-reused)*4096L,f.binding.completedComparisons());
                assertEquals(125-reused,f.owner.accounting().payloadIdentities()-125);
            }
        }
    }
    @Test void leavingAndReenteringEqualCellsRequiresFreshLeaseAndFreshProof(){
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.join();f.install();
            var position=DistanceBodyTestFixture.PIN.offset(-2,0,0);var original=f.replica.cubes().get(position).snapshot();
            f.stage(DistanceBodyTestFixture.PIN.offset(1,0,0));f.join();f.install();assertNull(f.replica.cubes().get(position));
            f.stage(DistanceBodyTestFixture.PIN);f.begin();var returned=f.replica.cubes().get(position).snapshot();
            assertNotSame(original,returned);assertNotEquals(original.leaseGeneration(),returned.leaseGeneration());
            assertEquals(100,f.binding.reusedSnapshots());f.prove();assertEquals(102_400,f.binding.completedComparisons());
        }
    }
    @Test void wrongPreparedYSupportRefusesInsteadOfAssumingUnknownIsAir(){
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);var p=DistanceBodyTestFixture.PIN;
            var narrow=EarthDistanceSurfacePlan.Source.prepareSampled(f.source.envelope(),p.minBlockY()-16,8896,DistanceBodyTestFixture.HASH,DistanceBodyTestFixture.HASH,DistanceBodyTestFixture.HASH,2,0,NativeDistanceLimits.sourceLimits(),
                    (x,z)->new EarthDistanceSurfacePlan.Sample(p.minBlockY(),5,Math.floorMod(x,3)==0?2:Math.floorMod(x,3)==1?3:4,7,0,p.minBlockY(),0));
            f.plan=EarthDistanceSurfacePlan.build(narrow,f.capture,p,2,0,NativeDistanceLimits.sourceLimits());f.begin();
            assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->f.binding.step());assertEquals(0,f.binding.completedComparisons());assertFalse(f.binding.dependenciesCurrent());
        }
    }
    @Test void explicitBudgetIncludesExternalResumeAndRejects214thCoordinateOr215thPayload(){
        try(var f=new DistanceBodyTestFixture()){
            f.stage(DistanceBodyTestFixture.PIN);f.prove();
            var extras=new ArrayList<EngineCubeSnapshot>();
                        for(int i=0;i<88;i++){var position=DistanceBodyTestFixture.PIN.offset(3+i%5,i/25-2,i/5%5-2);extras.add(f.codec.materialize(position,io.earthplatform.core.ImmutableCube.uniform(Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState())),1,1000+i,0));}
            assertEquals(213,f.owner.accounting(extras).coordinates());
            var duplicate=f.replica.cubes().values().iterator().next().snapshot();extras.add(f.codec.materialize(duplicate.position(),io.earthplatform.core.ImmutableCube.uniform(0),1,duplicate.leaseGeneration(),1));assertEquals(214,f.owner.accounting(extras).payloadIdentities());
            extras.add(f.codec.materialize(duplicate.position(),io.earthplatform.core.ImmutableCube.uniform(0),1,duplicate.leaseGeneration(),2));assertThrows(IllegalStateException.class,()->f.owner.accounting(extras));
            extras.remove(extras.size()-1);extras.remove(extras.size()-1);extras.add(f.codec.materialize(DistanceBodyTestFixture.PIN.offset(20,0,0),io.earthplatform.core.ImmutableCube.uniform(0),1,9999,0));assertThrows(IllegalStateException.class,()->f.owner.accounting(extras));
        }
    }
    @SuppressWarnings("unchecked") static Map<CubePos,SceneCube> mutableCubes(DistanceBodyTestFixture f)throws Exception{return (Map<CubePos,SceneCube>)field(f.replica,"cubes");}
    static Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
}
