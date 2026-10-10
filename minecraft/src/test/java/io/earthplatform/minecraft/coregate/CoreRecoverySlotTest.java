package io.earthplatform.minecraft.coregate;

import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CoreRecoverySlotTest {
    private final UUID id=UUID.randomUUID();
    private final CoreReturnPoint origin=new CoreReturnPoint(Level.OVERWORLD,1,2,3,4,5,GameType.CREATIVE);
    @Test void repeatedReplacementOfSameUuidReplacesOneSlot(){var q=new CoreRecoverySlot<String>();assertTrue(q.offer(id,"old",origin,p->false));var old=q.current();assertTrue(q.offer(id,"replacement",origin,p->false));assertEquals("replacement",q.current().player());q.clear(old);assertNotNull(q.current());}
    @Test void differentLiveOwnerIsRejectedWithoutDiscardingItsRecovery(){var q=new CoreRecoverySlot<String>();q.offer(id,"one",origin,p->false);assertFalse(q.offer(UUID.randomUUID(),"two",origin,p->false));assertEquals("one",q.current().player());}
    @Test void staleOwnerCanBeReplacedWithoutThrowingFromRespawn(){var q=new CoreRecoverySlot<String>();q.offer(id,"removed",origin,p->false);assertTrue(q.offer(UUID.randomUUID(),"live",origin,p->p.equals("removed")));assertEquals("live",q.current().player());}
    @Test void clearedRecordCancelsQueuedTeleport(){var q=new CoreRecoverySlot<String>();q.offer(id,"player",origin,p->false);assertFalse(q.matches(q.current(),Optional.empty()));}
    @Test void changedRecordNeverUsesQueuedOldOrigin(){var q=new CoreRecoverySlot<String>();q.offer(id,"player",origin,p->false);assertFalse(q.matches(q.current(),Optional.of(new CoreReturnPoint(Level.OVERWORLD,9,8,7,0,0,GameType.SURVIVAL))));}
    @Test void exactCurrentRecordExecutesOnce(){var q=new CoreRecoverySlot<String>();q.offer(id,"player",origin,p->false);var entry=q.current();assertTrue(q.matches(entry,Optional.of(origin)));q.clear(entry);assertNull(q.current());assertFalse(q.matches(entry,Optional.of(origin)));}
    @Test void rejectsNullBeforeChangingCurrent(){var q=new CoreRecoverySlot<String>();q.offer(id,"player",origin,p->false);assertThrows(NullPointerException.class,()->q.offer(id,null,origin,p->false));assertEquals("player",q.current().player());}
}
