package io.earthplatform.compat.dh;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Receipt logic only. A test marker implementation cannot enter the production installed-class adapter. */
class SurvivalDhConfigurationTest {
    private static final class Entry implements SurvivalDhConfiguration.BaseAccess,SurvivalDhConfiguration.OverrideAccess {
        long base,override;int active;boolean poisoned;
        synchronized void begin(boolean api){active++;if(api)override++;else base++;}
        synchronized void end(){active--;}
        public synchronized long earthplatform$survivalBaseGeneration(){if(active!=0||poisoned)throw new IllegalStateException("Active/poisoned");return base;}
        public synchronized long earthplatform$survivalOverrideGeneration(){if(active!=0||poisoned)throw new IllegalStateException("Active/poisoned");return override;}
    }
    @Test void absentTransformsReturnHistoricalOnlyAndForeignClassRefuses(){
        Object a=new Object(),b=new Object();assertNull(SurvivalDhConfiguration.capture(Object.class,a,b));
        assertThrows(IllegalStateException.class,()->SurvivalDhConfiguration.capture(Entry.class,a,b));
    }
    @Test void exactObjectsBothGenerationsAndSameValueWritesAreRequired(){
        var grass=new Entry();var debug=new Entry();var proof=SurvivalDhConfiguration.capture(Entry.class,grass,debug);
        assertNotNull(proof);assertDoesNotThrow(()->proof.requireCurrent(grass,debug));
        assertThrows(IllegalStateException.class,()->proof.requireCurrent(new Entry(),debug));
        grass.begin(false);grass.end(); // Same-value writes still retire the old private receipt.
        assertThrows(IllegalStateException.class,()->proof.requireCurrent(grass,debug));
        var newer=SurvivalDhConfiguration.capture(Entry.class,grass,debug);
        debug.begin(true);debug.end();debug.begin(true);debug.end(); // A -> B -> A is not the original generation.
        assertThrows(IllegalStateException.class,()->newer.requireCurrent(grass,debug));assertFalse(proof.same(newer));
    }
    @Test void inProgressCrossThreadMutationCannotMintAReceipt()throws Exception{
        var grass=new Entry();var debug=new Entry();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var writer=new Thread(()->{grass.begin(false);entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{grass.end();}});
        writer.start();try{assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->SurvivalDhConfiguration.capture(Entry.class,grass,debug));
        }finally{release.countDown();writer.join(2000);}
        assertFalse(writer.isAlive());assertNotNull(SurvivalDhConfiguration.capture(Entry.class,grass,debug));
    }
    @Test void poisonRemainsIrreversible(){
        var grass=new Entry();var debug=new Entry();var proof=SurvivalDhConfiguration.capture(Entry.class,grass,debug);
        synchronized(grass){grass.poisoned=true;}
        assertThrows(IllegalStateException.class,()->proof.requireCurrent(grass,debug));
        assertThrows(IllegalStateException.class,()->SurvivalDhConfiguration.capture(Entry.class,grass,debug));
    }
}
