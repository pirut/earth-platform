package io.earthplatform.minecraft.survival.render;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalPageCpuLeaseTest {
    @Test void nativePageUsesExistingSixteenMibWorkerPoolInsideUnchanged384MibEnvelope() {
        var budget=new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES);
        assertEquals(384L<<20,NativeDistanceCpuBudget.MAX_BYTES);
        assertEquals(16L<<20,NativeDistanceCpuBudget.Pool.WORKER_UPLOAD.limit());
        var held=new SurvivalPageCpuLease(budget,16L<<20);
        assertThrows(IllegalStateException.class,()->new SurvivalPageCpuLease(budget,1));
        assertEquals(16L<<20,budget.evidence().pools().get(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD));
        held.drain(()->{});assertEquals(0,budget.evidence().bytes());
    }
    @Test void oldGenerationAndNewPageCannotEachSpendTheSameWorkerCapacity() {
        var budget=new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES);
        var old=budget.reserve(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD,8L<<20);
        var page=new SurvivalPageCpuLease(budget,8L<<20);
        assertThrows(IllegalStateException.class,()->new SurvivalPageCpuLease(budget,1L<<20));
        page.drain(()->{});assertEquals(8L<<20,budget.evidence().bytes());
        old.releaseAfterPhysicalDrain();assertEquals(0,budget.evidence().reservations());
    }
    @Test void failedPhysicalCleanupRetainsChargeUntilSuccessfulRetryAndThenIsIdempotent() {
        var budget=new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES);
        var page=new SurvivalPageCpuLease(budget,(1L<<20)+8192);
        var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->page.drain(()->{calls.incrementAndGet();throw new IllegalStateException("physical close failed");}));
        assertFalse(page.released());assertEquals((1L<<20)+8192,page.bytes());assertEquals(1,budget.evidence().reservations());
        page.drain(calls::incrementAndGet);assertTrue(page.released());assertEquals(0,page.bytes());
        page.drain(calls::incrementAndGet);assertEquals(2,calls.get());assertEquals(0,budget.evidence().bytes());
    }
    @Test void reserveRefusesBeforeCallerCanAllocateAndGlobalPressureStillApplies() {
        var budget=new NativeDistanceCpuBudget(1024);
        var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->{new SurvivalPageCpuLease(budget,1025);calls.incrementAndGet();});
        assertEquals(0,calls.get());assertEquals(0,budget.evidence().bytes());
        assertThrows(IllegalArgumentException.class,()->new SurvivalPageCpuLease(budget,0));
    }
    @Test void differentThreadCannotDrainOrSampleAnOwnersNativeReservation() throws Exception {
        var budget=new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES);
        var page=new SurvivalPageCpuLease(budget,1024);
        var result=new AtomicReference<Throwable>();
        Thread thread=new Thread(()->{try{page.drain(()->{});}catch(Throwable failure){result.set(failure);}});
        thread.start();thread.join();assertInstanceOf(IllegalStateException.class,result.get());
        assertEquals(1024,budget.evidence().bytes());page.drain(()->{});
    }
}
