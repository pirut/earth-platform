package io.earthplatform.minecraft.survival.persistence;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Invalid-capability/reentrancy tests never claim a native save took place. */
class NativeOrdinarySaveAuthorityTest {
    NativeOrdinarySaveObserver.Authority incomplete(AtomicInteger reads){
        return new NativeOrdinarySaveObserver.Authority(){
            ServerPlayer player(){reads.incrementAndGet();return null;}
            PlayerDataStorage storage(){return null;}
            NativeSurvivalCheckpoint returning(){return null;}
            Object checkpointProvenance(){return this;}
            void requireHeldAndCurrent(NativeSurvivalCheckpoint checkpoint,Object provenance){fail("An incomplete binding must not reach validation");}
        };
    }
    @Test void failedAuthorityIsConsumedOnceAndCannotBeRetried(){
        AtomicInteger reads=new AtomicInteger();var authority=incomplete(reads);
        var first=NativeOrdinarySaveObserver.save(authority);assertNotNull(first.failure());
        assertThrows(RuntimeException.class,()->first.requireSucceeded(authority));
        var second=NativeOrdinarySaveObserver.save(authority);assertNotNull(second.failure());assertEquals(1,reads.get());
    }
    @Test void foreignConsumerCannotUseReceiptAndScopeIsReleasedOnFailure(){
        var one=incomplete(new AtomicInteger());var first=NativeOrdinarySaveObserver.save(one);
        var other=incomplete(new AtomicInteger());assertThrows(RuntimeException.class,()->first.requireSucceeded(other));
        assertNotNull(NativeOrdinarySaveObserver.save(other).failure());
        assertDoesNotThrow(()->NativeOrdinarySaveHooks.enter(null,null));
    }
    @Test void caughtNestedScopeFromBindingGetterStillPoisonsOuter(){
        var nested=incomplete(new AtomicInteger());
        var authority=new NativeOrdinarySaveObserver.Authority(){
            ServerPlayer player(){assertThrows(RuntimeException.class,()->NativeOrdinarySaveObserver.save(nested));return null;}
            PlayerDataStorage storage(){return null;}
            NativeSurvivalCheckpoint returning(){return null;}
            Object checkpointProvenance(){return this;}
            void requireHeldAndCurrent(NativeSurvivalCheckpoint checkpoint,Object provenance){}
        };
        var result=NativeOrdinarySaveObserver.save(authority);assertNotNull(result.failure());
        assertTrue(result.failure().getMessage().contains("Nested"));assertThrows(RuntimeException.class,()->result.requireSucceeded(authority));
    }
}
