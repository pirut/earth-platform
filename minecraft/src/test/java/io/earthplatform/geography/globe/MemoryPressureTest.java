package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryPressureTest {
    private static final long GB = 1L << 30, SECOND = 1_000_000_000L;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void freshGame() { MemoryPressure.reset(); }

    private void look(double gb, int above, boolean wholeHeap, long seconds) {
        look(gb, above, wholeHeap, false, seconds);
    }

    private void look(double gb, int above, boolean wholeHeap, boolean sustainedTight, long seconds) {
        MemoryPressure.looked((long) (gb * GB), 4 * GB, above, wholeHeap, sustainedTight, seconds * SECOND, said::add);
    }

    @Test
    void cachesKeepAQuarterWhileMemoryIsShort() {
        assertEquals(1024, MemoryPressure.scaled(1024));
        look(3.6, MemoryPressure.LOOKS_HIGH, false, 1);
        assertEquals(256, MemoryPressure.scaled(1024));
        assertEquals(2, MemoryPressure.scaled(4));
    }

    @Test
    void shortAfterTwoLooksAboveOrOneWholeHeapCollection() {
        look(3.6, 1, false, 1);
        assertFalse(MemoryPressure.high(), "one look above may be a collection running late");
        look(3.6, 2, false, 2);
        assertTrue(MemoryPressure.high());
        MemoryPressure.reset();
        look(2.0, 0, true, 1);
        assertTrue(MemoryPressure.high(), "the collector had to compact the whole heap");
    }

    @Test
    void staysShortUntilWellBelowForHalfAMinute() {
        look(3.6, 2, false, 0);
        look(2.0, 0, false, 10);
        assertTrue(MemoryPressure.high(), "G1's saw-tooth dips do not end it at once");
        look(3.0, 0, false, 20);
        look(2.0, 0, false, 49);
        assertTrue(MemoryPressure.high(), "75% is between the marks, so the half minute counts from there");
        look(2.0, 0, false, 51);
        assertFalse(MemoryPressure.high());
        assertEquals(4096, MemoryPressure.mostMib());
    }

    @Test
    void tellsThePlayerAndTheLogOnlyTheFirstTime() {
        look(3.6, 2, false, 0);
        assertTrue(MemoryPressure.firstShortage());
        assertFalse(MemoryPressure.firstShortage());
        look(2.0, 0, false, 40);
        assertFalse(MemoryPressure.high());
        look(3.6, 2, false, 50);
        assertTrue(MemoryPressure.high());
        assertFalse(MemoryPressure.firstShortage(), "told once a game");
        assertEquals(1, said.size());
    }

    @Test
    void lruMapsTrimToTheSmallerSizeOnTheirNextPut() {
        LruMap<Integer, Integer> map = new LruMap<>(16);
        for (int i = 0; i < 64; i++) map.putAndTrim(i, i, 64);
        assertEquals(64, map.size());
        look(3.6, 2, false, 0);
        assertEquals(16, map.size(), "already full caches drop when memory runs short, not on the next put");
        map.putAndTrim(64, 64, 64);
        assertEquals(16, map.size());
        assertTrue(map.containsKey(64), "keeps the newest");
    }

    @Test
    void aCollectionThatLeavesNoReserveCountsAsShort() {
        assertFalse(MemoryPressure.liveSetShort(2 * GB, 4 * GB));
        assertTrue(MemoryPressure.liveSetShort(4 * GB - (400L << 20), 4 * GB), "under the 512 MB reserve");
        look(3.6, 0, false, true, 0);
        assertTrue(MemoryPressure.high());
        assertEquals(1, said.size());
        assertTrue(said.get(0).contains("little heap free"));
    }

    @Test
    void oneTightLookIsNotYetShortUntilACollectionOrTwoSeconds() {
        MemoryPressure.TightSpan span = new MemoryPressure.TightSpan();
        assertFalse(span.update(true, 3, 0));
        assertFalse(span.update(true, 3, SECOND), "no collection yet, and not two seconds");
        assertTrue(span.update(true, 4, 2 * SECOND), "a collection ran and the heap is still full");
        MemoryPressure.TightSpan waited = new MemoryPressure.TightSpan();
        assertFalse(waited.update(true, 1, 0));
        assertTrue(waited.update(true, 1, MemoryPressure.TIGHT_WITHOUT_GC_NANOS));
        assertFalse(new MemoryPressure.TightSpan().update(false, 9, 0));
    }

    @Test
    void nearAFullHeapOnlyAFewHeavyPiecesRun() throws Exception {
        assertFalse(MemoryPressure.tight(), "tests do not watch the real heap");
        MemoryPressure.simulate(4 * GB - (1 << 20), 4 * GB);
        assertTrue(MemoryPressure.tight());
        AtomicInteger inside = new AtomicInteger();
        int extra = 4;
        CountDownLatch arrived = new CountDownLatch(MemoryPressure.HEAVY_WHEN_TIGHT + extra);
        CountDownLatch started = new CountDownLatch(MemoryPressure.HEAVY_WHEN_TIGHT);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        try {
            for (int i = 0; i < MemoryPressure.HEAVY_WHEN_TIGHT + extra; i++) threads.add(Thread.ofPlatform().start(() -> {
                arrived.countDown();
                MemoryPressure.enterHeavy();
                try {
                    inside.incrementAndGet();
                    started.countDown();
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally { MemoryPressure.exitHeavy(); }
            }));
            assertTrue(arrived.await(2, TimeUnit.SECONDS));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Thread.sleep(300);
            assertEquals(MemoryPressure.HEAVY_WHEN_TIGHT, inside.get());
            MemoryPressure.simulate(GB, 4 * GB);
            assertFalse(MemoryPressure.tight());
            long deadline = System.nanoTime() + 2 * SECOND;
            while (inside.get() < threads.size() && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(threads.size(), inside.get(), "work resumes once the heap has room");
        } finally {
            release.countDown();
            MemoryPressure.simulate(GB, 4 * GB);
            for (Thread thread : threads) thread.join(2_000);
        }
    }
}
