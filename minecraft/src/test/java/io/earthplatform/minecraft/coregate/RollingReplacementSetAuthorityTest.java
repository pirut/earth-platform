package io.earthplatform.minecraft.coregate;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RollingReplacementSetAuthorityTest {
 static final WorldIdentity W=new WorldIdentity(9,"test","1","d","p");
 static final CubePos A=new CubePos(0,8,0),B=A.offset(4,0,0);
 static final String STORE="12345678-1234-1234-1234-123456789abc",INDEX="a".repeat(64),PAL="b".repeat(64);
 static ReplacementSetCertificate set(long generation,long ra,long rb,long lease){var regions=new ArrayList<ReplacementSetCertificate.Region>();for(var p:List.of(A,B)){long rev=p.equals(A)?ra:rb;var deps=new ArrayList<RollingCubeActor.CubeVersion>();for(var q:CubeTickets.box(p,1,1,27).stream().sorted().toList())deps.add(new RollingCubeActor.CubeVersion(q,lease++,q.equals(p)?rev:0));regions.add(new ReplacementSetCertificate.Region(p,deps,(p.equals(A)?"c":"d").repeat(63)+(char)('0'+rev)));}return new ReplacementSetCertificate(W,1,1,STORE,INDEX,"provider",PAL,"material","detail",generation,regions);}
 static RollingReplacementSetAuthority mirror(){return new RollingReplacementSetAuthority(W,1,1,ReplacementWorkspaces.two(A),"provider",PAL,"material","detail");}
 @Test void completeSetCommitsThenEditRetainsHistoricalWithoutCurrentAuthority(){var m=mirror();var first=set(1,1,0,1);m.propose(first);m.commit(first.identity());m.beginEdit(A);assertFalse(m.isCurrent(first.identity()));assertTrue(m.canPresent(first.identity()));var next=set(2,2,0,100);m.propose(next);assertTrue(m.isCurrent(next.identity()));assertTrue(m.canPresent(first.identity()));assertEquals(2,m.retainedSets());m.commit(next.identity());assertFalse(m.canPresent(first.identity()));assertTrue(m.isCurrent(next.identity()));assertEquals(1,m.retainedSets());}
 @Test void refusedEditCanRestoreExactOldCompleteSet(){var m=mirror();var old=set(1,1,0,1);m.propose(old);m.commit(old.identity());m.beginEdit(B);m.propose(old);m.commit(old.identity());assertTrue(m.isCurrent(old.identity()));assertEquals(1,m.retainedSets());}
 @Test void unfocusedDurableChangesCannotRideAnotherEdit(){var m=mirror();var old=set(1,1,0,1);m.propose(old);m.commit(old.identity());m.beginEdit(B);assertThrows(IllegalArgumentException.class,()->m.propose(set(2,2,1,100)));assertFalse(m.isCurrent(old.identity()));assertTrue(m.canPresent(old.identity()));}
 @Test void leaseReacquisitionMayRefreshAuthorityButCannotChangeDurableContent(){var m=mirror();var old=set(1,1,0,1);m.propose(old);m.commit(old.identity());var fresh=set(2,1,0,100);m.propose(fresh);m.commit(fresh.identity());assertTrue(m.isCurrent(fresh.identity()));assertThrows(IllegalArgumentException.class,()->m.propose(old));}
 @Test void replacementGenerationsCannotReenterAfterCancel(){var m=mirror();var first=set(1,1,0,1);m.propose(first);m.cancelProposal();assertThrows(IllegalArgumentException.class,()->m.propose(first));}
 @Test void nullCommitAndUnconfiguredFocusFailClosed(){var m=mirror();assertThrows(IllegalStateException.class,()->m.commit(null));assertThrows(IllegalArgumentException.class,()->m.beginEdit(A.offset(2,0,0)));}
 @Test void reloadCloseRevokesAllValueOnlyAuthority(){var m=mirror();var old=set(1,1,0,1);m.propose(old);m.commit(old.identity());m.beginEdit(A);m.propose(set(2,2,0,100));m.close();m.close();assertEquals(0,m.retainedSets());assertFalse(m.canPresent(old.identity()));assertFalse(m.isCurrent(old.identity()));assertThrows(IllegalStateException.class,()->m.propose(old));}
}
