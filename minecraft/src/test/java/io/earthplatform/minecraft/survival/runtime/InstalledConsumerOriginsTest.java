package io.earthplatform.minecraft.survival.runtime;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated refusal/accounting fixtures only. Never claims a transformed origin, native
 * registration, or installed startup cut. No native bus/registrar or callback is invoked. */
class InstalledConsumerOriginsTest {
    private Object priorOwner;private boolean priorInvalid;
    @BeforeEach void saveFixtureState()throws Exception {
        priorOwner=field("retained").get(null);priorInvalid=field("invalid").getBoolean(null);
        field("retained").set(null,null);field("invalid").setBoolean(null,false);
    }
    @AfterEach void restoreFixtureState()throws Exception {
        field("retained").set(null,priorOwner);field("invalid").setBoolean(null,priorInvalid);
    }
    private static Field field(String name)throws Exception {
        Field field=InstalledConsumerOrigins.class.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static InstalledConsumerOrigins.Owner fixture()throws Exception {
        var owner=new InstalledConsumerOrigins.Owner();owner.initialize();owner.published=true;
        field("retained").set(null,owner);return owner;
    }
    @Test void missingPreseedCannotRetainOrQualifyOrigin() {
        assertNull(InstalledConsumerOrigins.before(0,new Object(),new Object(),x->{throw new AssertionError();},true));
        assertTrue(InstalledConsumerOrigins.invalid());assertNull(InstalledConsumerOrigins.observed());
    }
    @Test void operandsAreRetainedBeforeWrongHostRefusalWithoutCallingForeignMethods()throws Exception {
        var owner=fixture();Object host=new Object(),bus=new Object();AtomicInteger calls=new AtomicInteger();
        Consumer<Object> consumer=x->calls.incrementAndGet();
        Object receipt=InstalledConsumerOrigins.before(0,host,bus,consumer,true);
        assertSame(owner.receipts[0],receipt);assertSame(host,owner.receipts[0].host);
        assertSame(bus,owner.receipts[0].bus);assertSame(consumer,owner.receipts[0].consumer);
        assertFalse(owner.receipts[0].beforeQualified);assertTrue(InstalledConsumerOrigins.invalid());assertEquals(0,calls.get());
    }
    @Test void duplicateRetainsFirstTwoOperandsAndFurtherOverflowCannotReplaceThem()throws Exception {
        var owner=fixture();Consumer<Object> a=x->{},b=x->{},c=x->{};
        InstalledConsumerOrigins.before(2,new Object(),new Object(),a,true);
        InstalledConsumerOrigins.before(2,new Object(),new Object(),b,true);
        assertNull(InstalledConsumerOrigins.before(2,new Object(),new Object(),c,true));
        assertSame(a,owner.receipts[2].consumer);assertSame(b,owner.receipts[10].consumer);
        assertTrue(owner.overflow);assertTrue(InstalledConsumerOrigins.invalid());assertEquals(17,owner.receipts.length);
    }
    @Test void nativeFailureClassIsRetainedAndPartialReturnNeverBecomesQualified()throws Exception {
        var owner=fixture();Object receipt=InstalledConsumerOrigins.before(1,new Object(),new Object(),x->{},true);
        InstalledConsumerOrigins.after(receipt,false,IllegalArgumentException.class);
        assertSame(IllegalArgumentException.class,owner.receipts[1].nativeFailure);
        assertEquals(3,owner.states.get(1));assertFalse(owner.complete);assertTrue(InstalledConsumerOrigins.invalid());
    }
    @Test void lateAndUnknownCallsPoisonWithinFixedSlots()throws Exception {
        var owner=fixture();owner.phase.set(1);
        var late=InstalledConsumerOrigins.before(3,new Object(),new Object(),x->{},true);
        assertSame(owner.receipts[3],late);assertEquals("DUPLICATE_UNKNOWN_OR_LATE",owner.receipts[3].stage);
        var unknown=InstalledConsumerOrigins.before(99,new Object(),new Object(),x->{},true);
        assertSame(owner.receipts[16],unknown);assertNull(InstalledConsumerOrigins.before(99,new Object(),new Object(),x->{},true));
        assertTrue(owner.overflow);assertFalse(owner.complete);assertTrue(InstalledConsumerOrigins.invalid());
    }
    @Test void parallelDifferentSitesKeepTheirOwnIdentitiesButCannotForgeOrigin()throws Throwable {
        var owner=fixture();var start=new CountDownLatch(1);var done=new CountDownLatch(8);
        Consumer<?>[] consumers=new Consumer<?>[8];Thread[] threads=new Thread[8];
        Throwable firstFailure=null;boolean interrupted=false;
        try {
            for(int i=0;i<8;i++){
                final int site=i;Consumer<Object> consumer=new Consumer<>(){public void accept(Object value){throw new AssertionError();}};
                consumers[i]=consumer;threads[i]=new Thread(()->{try{start.await();
                    InstalledConsumerOrigins.before(site,new Object(),new Object(),consumer,true);
                }catch(InterruptedException stopped){Thread.currentThread().interrupt();}finally{done.countDown();}});
                threads[i].setDaemon(true);threads[i].start();
            }
            start.countDown();assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));
            for(int i=0;i<8;i++){assertSame(consumers[i],owner.receipts[i].consumer);assertFalse(owner.receipts[i].beforeQualified);}
            assertFalse(owner.complete);assertTrue(InstalledConsumerOrigins.invalid());assertFalse(owner.overflow);
        }catch(Throwable failure){firstFailure=failure;interrupted=failure instanceof InterruptedException;}
        finally {
            start.countDown(); // A failed creation/start/assertion cannot leave workers on the gate.
            for(Thread thread:threads){
                if(thread==null)continue;
                try {thread.interrupt();}
                catch(Throwable cleanup){if(firstFailure==null)firstFailure=cleanup;else firstFailure.addSuppressed(cleanup);}
                try {thread.join(1000);assertFalse(thread.isAlive(),"Bounded fixture worker cleanup failed");}
                catch(Throwable cleanup){
                    if(cleanup instanceof InterruptedException)interrupted=true;
                    if(firstFailure==null)firstFailure=cleanup;else firstFailure.addSuppressed(cleanup);
                }
            }
            if(interrupted)Thread.currentThread().interrupt();
        }
        if(firstFailure!=null)throw firstFailure;
    }
}
