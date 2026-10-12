package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingAdjacentSetMirrorTest {
    static final WorldIdentity W=new WorldIdentity(7,"test","1","d","p");
    static final CubePos A=new CubePos(0,8,0),B=A.offset(1,0,0);
    static final String STORE="12345678-1234-1234-1234-123456789abc",INDEX="a".repeat(64),PAL="b".repeat(64);
    static ReplacementSetCertificate set(long generation,long ar,long br,char ac,char bc) {
        var regions=new ArrayList<ReplacementSetCertificate.Region>();long lease=1;
        for(var p:List.of(A,B)) {
            var ds=new ArrayList<RollingCubeActor.CubeVersion>();
            for(var q:CubeTickets.box(p,1,1,27).stream().sorted().toList())ds.add(new RollingCubeActor.CubeVersion(q,lease++,q.equals(A)?ar:q.equals(B)?br:0));
            regions.add(new ReplacementSetCertificate.Region(p,ds,String.valueOf(p.equals(A)?ac:bc).repeat(64)));
        }
        return new ReplacementSetCertificate(W,1,1,STORE,INDEX,"provider",PAL,"material","detail",generation,regions);
    }
    static RollingReplacementSetAuthority mirror() { return new RollingReplacementSetAuthority(W,1,1,ReplacementWorkspaces.adjacent(A),"provider",PAL,"material","detail"); }
    @Test void seamEditRefreshesBothCaptureIdentitiesButOnlyEditedOwnRevision() {
        for(var focus:List.of(A,B)) {
            var m=mirror();var old=set(1,0,0,'c','d');m.propose(old);m.commit(old.identity());m.beginEdit(focus);
            var next=set(2,focus.equals(A)?1:0,focus.equals(B)?1:0,'e','f');m.propose(next);
            assertTrue(m.isCurrent(next.identity()));assertFalse(m.isCurrent(old.identity()));assertTrue(m.canPresent(old.identity()));
            assertEquals(0,next.region(focus.equals(A)?B:A).revision());m.commit(next.identity());assertFalse(m.canPresent(old.identity()));
        }
    }
    @Test void unrelatedOwnRevisionAdvanceFailsBeforeAnyCurrentSetAppears() {
        var m=mirror();var old=set(1,0,0,'c','d');m.propose(old);m.commit(old.identity());m.beginEdit(A);
        var forged=set(2,1,1,'e','f');assertThrows(IllegalArgumentException.class,()->m.propose(forged));assertFalse(m.isCurrent(forged.identity()));assertTrue(m.canPresent(old.identity()));assertEquals(1,m.retainedSets());
    }
    @Test void captureChangeWithoutDurableDependencyAdvanceFails() {
        var m=mirror();var old=set(1,0,0,'c','d');m.propose(old);m.commit(old.identity());m.beginEdit(A);
        assertThrows(IllegalArgumentException.class,()->m.propose(set(2,0,0,'e','f')));assertFalse(m.isCurrent(old.identity()));assertTrue(m.canPresent(old.identity()));
    }
    @Test void refusalRestoresExactWholeSet() {
        var m=mirror();var old=set(1,0,0,'c','d');m.propose(old);m.commit(old.identity());m.beginEdit(B);m.propose(old);m.commit(old.identity());assertTrue(m.isCurrent(old.identity()));assertEquals(1,m.retainedSets());
    }
    @Test void cancellationCannotRollBackTheAdmittedDurableEdit() {
        var m=mirror();var old=set(1,0,0,'c','d');m.propose(old);m.commit(old.identity());m.beginEdit(A);var next=set(2,1,0,'e','f');m.propose(next);m.cancelProposal();
        assertThrows(IllegalArgumentException.class,()->m.propose(old));assertFalse(m.isCurrent(old.identity()));assertTrue(m.canPresent(old.identity()));assertFalse(m.isCurrent(next.identity()));
    }
    @Test void revisionOnlyNeighborRefreshMayKeepUnchangedReadContent() {
        var m=mirror();var old=set(1,0,0,'c','d');m.propose(old);m.commit(old.identity());m.beginEdit(A);var next=set(2,1,0,'e','d');m.propose(next);m.commit(next.identity());assertTrue(m.isCurrent(next.identity()));
    }
    @Test void noEditCannotChangeEitherCaptureOrDurableRevision() {
        var m=mirror();var old=set(1,0,0,'c','d');m.propose(old);m.commit(old.identity());assertThrows(IllegalArgumentException.class,()->m.propose(set(2,1,0,'e','f')));assertTrue(m.isCurrent(old.identity()));
    }
    @Test void closeRevokesBothProposedAndHistoricalSets() {
        var m=mirror();var old=set(1,0,0,'c','d');m.propose(old);m.commit(old.identity());m.beginEdit(B);var next=set(2,0,1,'e','f');m.propose(next);m.close();assertEquals(0,m.retainedSets());assertFalse(m.canPresent(old.identity()));assertFalse(m.isCurrent(next.identity()));
    }
}
