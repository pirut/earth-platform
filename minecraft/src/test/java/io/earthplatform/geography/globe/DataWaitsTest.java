package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.far.FarWorkers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DataWaitsTest {
    @Test
    void onlyRealWaitsAreToldOnceEachHoweverNested() throws Exception {
        List<Boolean> told = new ArrayList<>();
        DataWaits.watch(told::add);
        try {
            assertEquals("in", DataWaits.get(CompletableFuture.completedFuture("in")));
            assertEquals("in", DataWaits.join(CompletableFuture.completedFuture("in")));
            assertTrue(told.isEmpty(), "a piece already in is no wait");

            CompletableFuture<String> later = CompletableFuture.supplyAsync(() -> {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "later";
            });
            DataWaits.before();
            assertEquals("later", DataWaits.get(later, 5, TimeUnit.SECONDS));
            DataWaits.after();
            assertEquals(List.of(true, false), told, "a wait inside a wait is the same wait");

            DataWaits.watch(null);
            DataWaits.before();
            DataWaits.after();
            assertEquals(2, told.size(), "nothing is told once no one watches");
        } finally {
            DataWaits.watch(null);
        }
    }

    @Test
    void columnsWaitingOnDataLetOthersStartAndTakeTurnsWhenItComes() throws Exception {
        FarWorkers workers = new FarWorkers("test far", 1, 3);
        CountDownLatch download = new CountDownLatch(1), started = new CountDownLatch(3), done = new CountDownLatch(3);
        AtomicInteger atWork = new AtomicInteger(), mostAtWork = new AtomicInteger();
        try {
            for (int i = 0; i < 3; i++) {
                workers.execute(() -> {
                    started.countDown();
                    // All three wait on the same download, as far columns side by side do.
                    DataWaits.before();
                    try { download.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { DataWaits.after(); }
                    mostAtWork.accumulateAndGet(atWork.incrementAndGet(), Math::max);
                    try { Thread.sleep(20); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    atWork.decrementAndGet();
                    done.countDown();
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "with one at work, the others start while the first waits on data");
            download.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, mostAtWork.get(), "with the data in, they go back to work one at a time");
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void heldWorkersStartNoMoreThanBusyEvenWhileOthersWait() throws Exception {
        FarWorkers workers = new FarWorkers("test far", 1, 3);
        CountDownLatch download = new CountDownLatch(1), first = new CountDownLatch(1), second = new CountDownLatch(1);
        try {
            workers.hold(true);
            workers.execute(() -> {
                first.countDown();
                DataWaits.before();
                try { download.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { DataWaits.after(); }
            });
            workers.execute(second::countDown);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            assertFalse(second.await(200, TimeUnit.MILLISECONDS), "near cubes come first: no second column starts");
            workers.hold(false);
            assertTrue(second.await(5, TimeUnit.SECONDS), "once they are made, a column waiting on data makes room for another");
            download.countDown();
        } finally {
            workers.shutdownNow();
        }
    }
}
