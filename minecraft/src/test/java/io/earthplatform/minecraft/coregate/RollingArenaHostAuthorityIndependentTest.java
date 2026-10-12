package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.coregate.ArenaHostReviewFixtures.*;

class RollingArenaHostAuthorityIndependentTest {
    private RollingArenaAuthority mirror(){return new RollingArenaAuthority(D,1,1);}
    @Test void completeAuthorityIncludesExactlyEightEditableAndSixtyFourCanonicalDependencies(){
        var c=first();assertEquals(64,c.dependencies().size());assertEquals(8,A.positions().size());assertEquals(A.dependencies(),c.dependencies().stream().map(RollingCubeActor.CubeVersion::position).toList());
        assertEquals(8,c.queryAttestation().resultCount());assertTrue(c.queryAttestation().completeCatalogue());RollingArenaQueryGuard.require(D,c);
        for(var p:A.positions())assertEquals(0,c.revision(p));assertThrows(IllegalArgumentException.class,()->c.revision(A.origin().offset(-1,0,0)));
    }
    @Test void missingDuplicateReorderedAndEditedHaloDependenciesRefuse(){
        var c=first();var deps=new ArrayList<>(c.dependencies());deps.removeLast();var missing=deps;assertThrows(IllegalArgumentException.class,()->replaceDependencies(c,missing));
        deps=new ArrayList<>(c.dependencies());Collections.swap(deps,0,1);var reordered=deps;assertThrows(IllegalArgumentException.class,()->replaceDependencies(c,reordered));
        deps=new ArrayList<>(c.dependencies());var v=deps.get(1);deps.set(1,new RollingCubeActor.CubeVersion(v.position(),deps.getFirst().leaseGeneration(),0));var duplicate=deps;assertThrows(IllegalArgumentException.class,()->replaceDependencies(c,duplicate));
        assertThrows(IllegalArgumentException.class,()->certificate(1,1,1,Map.of(A.dependencies().getFirst(),1L),AIR));
    }
    @Test void nullAndInexactQueryCountCannotAcquireAuthority(){
        var c=first();assertThrows(NullPointerException.class,()->new ArenaCertificate(c.world(),1,1,STORE,INDEX,c.providerIdentity(),PAL,c.materialPolicy(),c.detailPolicy(),1,A,c.dependencies(),c.captureIdentity(),null));
        var q=c.queryAttestation();for(int count:new int[]{0,1,7,9,64})assertThrows(IllegalArgumentException.class,()->new ArenaQueryAttestation(STORE,INDEX,1,q.snapshotRootIdentity(),A.identity(),q.layoutIdentity(),q.policyIdentity(),q.resultDigest(),count));
    }
    @Test void everyCubeCanBeTargetWithoutFocusHandoffAndOnlyOneRevisionAdvances(){
        for(var target:A.positions())try(var m=mirror()){
            var old=first();m.propose(old);m.commit(old.identity());m.beginEdit(target);assertFalse(m.isCurrent(old.identity()));assertTrue(m.canPresent(old.identity()));
            var changed=AIR.withMaterial(target.minBlockX(),target.minBlockY(),target.minBlockZ(),ArenaCapture.DIORITE).compact();var next=certificate(2,2,65,Map.of(target,1L),changed);
            m.propose(next);assertEquals(2,m.retainedSets());assertTrue(m.isCurrent(next.identity()));m.commit(next.identity());assertEquals(1,m.retainedSets());assertFalse(m.canPresent(old.identity()));
            for(var p:A.positions())assertEquals(p.equals(target)?1:0,next.revision(p));
        }
    }
    @Test void nonTargetDurableChangeAndRevisionJumpCannotRideAnEdit(){
        try(var m=mirror()){var old=first();m.propose(old);m.commit(old.identity());var target=A.positions().getLast();m.beginEdit(target);
            assertThrows(IllegalArgumentException.class,()->m.propose(certificate(2,2,65,Map.of(A.positions().getFirst(),1L),AIR)));
            assertThrows(IllegalArgumentException.class,()->m.propose(certificate(2,2,65,Map.of(target,2L),AIR)));
            assertFalse(m.isCurrent(old.identity()));assertTrue(m.canPresent(old.identity()));assertEquals(1,m.retainedSets());}
    }
    @Test void movementMayRefreshPublicationLeasesButNeverDurableContentOrQuery(){
        try(var m=mirror()){var old=first();m.propose(old);m.commit(old.identity());var fresh=certificate(2,1,65,Map.of(),AIR);m.propose(fresh);assertEquals(old.durableIdentity(),fresh.durableIdentity());assertNotEquals(old.identity(),fresh.identity());m.commit(fresh.identity());
            assertThrows(IllegalArgumentException.class,()->m.propose(certificate(3,2,129,Map.of(),AIR)));
            var changed=AIR.withMaterial(1,129,1,ArenaCapture.STONE);assertThrows(IllegalArgumentException.class,()->m.propose(certificate(3,1,129,Map.of(),changed)));
            assertTrue(m.isCurrent(fresh.identity()));}
    }
    @Test void staleGenerationAndSameGenerationDifferentRootRejectWithoutReplacingCurrent(){
        try(var m=mirror()){var old=first();m.propose(old);m.commit(old.identity());
            var stale=certificate(1,1,65,Map.of(),AIR);assertThrows(IllegalArgumentException.class,()->m.propose(stale));
            var aba=certificate(2,1,"f".repeat(64),65,Map.of(),AIR,RollingArenaQueryGuard.policy(D),D.lodLayout().identity(),1,1,STORE,INDEX);assertThrows(IllegalArgumentException.class,()->m.propose(aba));assertTrue(m.isCurrent(old.identity()));}
    }
    @Test void wrongLayoutPolicyEpochResourceStoreAndIndexFailClosed(){
        try(var m=mirror()){var old=first();m.propose(old);m.commit(old.identity());
            for(int i=0;i<6;i++){var c=certificate(2,1,String.format("%064x",1),65,Map.of(),AIR,i==0?"f".repeat(64):RollingArenaQueryGuard.policy(D),i==1?"foreign-layout":D.lodLayout().identity(),i==2?2:1,i==3?2:1,i==4?"22345678-1234-1234-1234-123456789abc":STORE,i==5?"d".repeat(64):INDEX);assertThrows(IllegalArgumentException.class,()->m.propose(c));}
            assertTrue(m.isCurrent(old.identity()));assertEquals(1,m.retainedSets());}
    }
    @Test void refusedEditCanRestoreExactHistoricalIdentityButCanceledProposalCannotReplay(){
        try(var m=mirror()){var old=first();m.propose(old);m.commit(old.identity());m.beginEdit(A.positions().getLast());m.propose(old);m.commit(old.identity());assertTrue(m.isCurrent(old.identity()));
            var next=certificate(2,1,65,Map.of(),AIR);m.propose(next);m.cancelProposal();assertFalse(m.isCurrent(next.identity()));assertThrows(IllegalArgumentException.class,()->m.propose(next));assertTrue(m.isCurrent(old.identity()));}
    }
    @Test void canceledDurableProposalCannotRollBackToHistoricalOrReplayItsGeneration(){
        try(var m=mirror()){var old=first();m.propose(old);m.commit(old.identity());var target=A.positions().getLast();m.beginEdit(target);var changed=AIR.withMaterial(31,159,31,ArenaCapture.STONE).compact();var next=certificate(2,2,65,Map.of(target,1L),changed);m.propose(next);m.cancelProposal();
            assertThrows(IllegalArgumentException.class,()->m.propose(old));assertThrows(IllegalArgumentException.class,()->m.propose(next));assertTrue(m.canPresent(old.identity()));assertFalse(m.isCurrent(old.identity()));var refreshed=certificate(3,2,129,Map.of(target,1L),changed);m.propose(refreshed);m.commit(refreshed.identity());assertFalse(m.canPresent(old.identity()));}
    }
    @Test void closeRevokesCurrentProposedHistoricalAndCannotBeReentered(){
        var m=mirror();var old=first();m.propose(old);m.commit(old.identity());m.beginEdit(A.origin());var next=certificate(2,2,65,Map.of(A.origin(),1L),AIR);m.propose(next);m.close();m.close();assertEquals(0,m.retainedSets());assertFalse(m.isCurrent(next.identity()));assertFalse(m.canPresent(old.identity()));assertThrows(IllegalStateException.class,()->m.commit(next.identity()));assertThrows(IllegalStateException.class,()->m.propose(old));
    }
    @Test void partialCommitWrongIdentityAndOutsideTargetCannotMutateAuthority(){
        try(var m=mirror()){assertThrows(IllegalStateException.class,()->m.commit(null));var old=first();m.propose(old);assertThrows(IllegalStateException.class,()->m.commit("f".repeat(64)));m.commit(old.identity());assertThrows(IllegalStateException.class,()->m.beginEdit(A.origin().offset(2,0,0)));assertTrue(m.isCurrent(old.identity()));}
    }
}
