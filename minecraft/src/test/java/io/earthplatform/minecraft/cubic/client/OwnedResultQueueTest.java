package io.earthplatform.minecraft.cubic.client;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedResultQueueTest {
    private static final class Result {
        final AtomicInteger releases = new AtomicInteger();
        void free() { assertEquals(1, releases.incrementAndGet(), "result released more than once"); }
    }

    @Test void pollTransfersOwnershipAndCloseReleasesOnlyStillQueuedResults() {
        var queue = new OwnedResultQueue<Result>(Result::free);
        Result consumed = new Result(), pending = new Result();
        queue.add(consumed); queue.add(pending);
        assertSame(consumed, queue.peek());
        assertSame(consumed, queue.poll());
        queue.close(); queue.close();
        assertEquals(0, consumed.releases.get());
        assertEquals(1, pending.releases.get());
        consumed.free();
        assertTrue(queue.isClosed());
        assertTrue(queue.isEmpty());
    }

    @Test void completionAfterCloseIsReleasedWithoutAnotherRenderTick() throws Exception {
        var queue = new OwnedResultQueue<Result>(Result::free);
        Result result = new Result();
        var ready = new CountDownLatch(1); var finish = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            Future<?> task = worker.submit(() -> {
                ready.countDown();
                try { assertTrue(finish.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                queue.add(result);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            queue.close();
            finish.countDown();
            task.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, result.releases.get());
        assertTrue(queue.isEmpty());
        assertNull(queue.poll());
    }

    @Test void concurrentPublicationAndClosureReleaseEveryResultExactlyOnce() throws Exception {
        var queue = new OwnedResultQueue<Result>(Result::free);
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < 2000; i++) results.add(new Result());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(3)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) {
                int offset = worker * 1000;
                tasks.add(workers.submit(() -> {
                    try { assertTrue(start.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { throw new AssertionError(ex); }
                    for (int i = offset; i < offset + 1000; i++) queue.add(results.get(i));
                }));
            }
            tasks.add(workers.submit(() -> {
                try { assertTrue(start.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                queue.close();
            }));
            start.countDown();
            for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        queue.close();
        assertTrue(queue.isEmpty());
        assertEquals(2000, results.stream().filter(r -> r.releases.get() == 1).count());
    }

    @Test void closedQueueConsumesFutureResultsAndRejectsNullWithoutDisposal() {
        var queue = new OwnedResultQueue<Result>(Result::free);
        queue.close();
        Result result = new Result();
        assertTrue(queue.offer(result));
        assertEquals(1, result.releases.get());
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertEquals(0, queue.size());
    }
}
