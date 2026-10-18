package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GpuQueryRingTest {
    static final class Fake implements GpuQueryRing.Queries {
        int next, stamps, reads, deletes;
        long tick;
        final HashSet<Integer> available = new HashSet<>();
        final HashMap<Integer, Long> values = new HashMap<>();
        public int create() { return ++next; }
        public void stamp(int q) { stamps++; values.put(q, tick += 10); }
        public boolean available(int q) { return available.contains(q); }
        public long result(int q) {
            assertTrue(available.contains(q), "A pending query result must never be read");
            reads++;
            return values.get(q);
        }
        public void delete(int q) { deletes++; available.remove(q); values.remove(q); }
        void complete() { for (int q = 1; q <= next; q++) available.add(q); }
    }

    @Test void readsNeitherResultUntilBothAreAvailableInAnOlderFrame() {
        Fake q = new Fake(); AtomicLong clock = new AtomicLong(100);
        try (var r = new GpuQueryRing(q, clock::get, 2, 10, 64)) {
            long token = r.begin("opaque", 4);
            clock.set(150); r.end(token, 7, 99);
            q.complete(); r.poll(4);
            assertEquals(0, q.reads);
            q.available.remove(2); r.poll(5);
            assertEquals(0, q.reads);
            q.available.add(2); r.poll(5);
            var sample = r.drain().getFirst();
            assertEquals("opaque", sample.pass());
            assertEquals(4, sample.frame()); assertEquals(1, sample.age());
            assertEquals(50, sample.cpuNanos()); assertEquals(10, sample.gpuNanos());
            assertEquals(7, sample.ranges()); assertEquals(99, sample.faces());
        }
    }

    @Test void saturationDropsNewWorkWithoutWaitingOrGrowingQueries() {
        Fake q = new Fake();
        try (var r = new GpuQueryRing(q, () -> 0, 2, 10, 64)) {
            r.end(r.begin("a", 1), 0, 0); r.end(r.begin("b", 1), 0, 0);
            assertEquals(0, r.begin("c", 1));
            assertEquals(4, q.next); assertEquals(4, q.stamps); assertEquals(1, r.dropped());
            r.poll(2); assertEquals(0, q.reads);
            q.complete(); r.poll(3); assertEquals(2, r.drain().size());
            assertNotEquals(0, r.begin("d", 3)); assertEquals(4, q.next);
        }
    }

    @Test void overdueAndUnendedWorkIsDeletedAndStaleTokensCannotEndItsReplacement() {
        Fake q = new Fake();
        try (var r = new GpuQueryRing(q, () -> 0, 1, 2, 64)) {
            long stale = r.begin("aborted", 1);
            r.poll(3); assertEquals(0, q.deletes);
            r.poll(4); assertEquals(2, q.deletes); assertEquals(0, q.reads);
            long current = r.begin("fresh", 4);
            r.end(stale, 0, 0); assertEquals(2, q.stamps);
            r.end(current, 0, 0); r.end(current, 0, 0);
            assertEquals(3, q.stamps); assertEquals(4, q.next);
        }
    }

    @Test void timestampPairsCanNestWithoutTimeElapsedQueryConflicts() {
        Fake q = new Fake();
        try (var r = new GpuQueryRing(q, () -> 0, 2, 10, 64)) {
            long outer = r.begin("outer", 1), inner = r.begin("inner", 1);
            r.end(inner, 0, 0); r.end(outer, 0, 0);
            q.complete(); r.poll(2);
            var samples = r.drain();
            assertEquals(30, samples.get(0).gpuNanos());
            assertEquals(10, samples.get(1).gpuNanos());
        }
    }

    @Test void handlesOneNarrowCounterWrapAndDropsAmbiguousMultipleWraps() {
        Fake q = new Fake(); AtomicLong clock = new AtomicLong();
        try (var r = new GpuQueryRing(q, clock::get, 1, 10, 8)) {
            r.end(r.begin("wrap", 1), 0, 0);
            q.values.put(1, 250L); q.values.put(2, 4L);
            q.complete(); clock.set(20); r.poll(2);
            assertEquals(10, r.drain().getFirst().gpuNanos());
            r.end(r.begin("ambiguous", 2), 0, 0);
            q.complete(); clock.set(300); int reads = q.reads; r.poll(3);
            assertEquals(reads, q.reads); assertEquals(1, r.dropped());
        }
    }

    @Test void retainedSamplesAreBoundedEvenWithoutAConsumer() {
        Fake q = new Fake();
        try (var r = new GpuQueryRing(q, () -> 0, 1, 10, 64)) {
            for (int frame = 1; frame <= 20; frame++) {
                r.end(r.begin("a", frame), 0, 0); q.complete(); r.poll(frame + 1);
            }
            assertEquals(2, q.next); assertEquals(8, r.drain().size());
            assertEquals(12, r.dropped()); assertTrue(r.drain().isEmpty());
        }
    }

    @Test void closeDeletesAllQueryNamesOnceAndStopsFurtherWork() {
        Fake q = new Fake(); var r = new GpuQueryRing(q, () -> 0, 1, 10, 64);
        long token = r.begin("a", 1);
        r.close(); r.close(); r.end(token, 0, 0); r.poll(20);
        assertEquals(2, q.deletes); assertEquals(1, q.stamps);
        assertEquals(0, r.begin("b", 2)); assertTrue(r.drain().isEmpty());
    }

    @Test void elapsedBackendSkipsOccupiedTargetsAndNeedsOnlyItsOwnQueryResult() {
        Fake q = new Fake();
        boolean[] occupied = {true};
        GpuQueryRing.Queries elapsed = new GpuQueryRing.Queries() {
            public int create() { return q.create(); }
            public void stamp(int id) { q.stamp(id); }
            public boolean available(int id) { return q.available(id); }
            public long result(int id) { return q.result(id); }
            public void delete(int id) { q.delete(id); }
            public boolean canBegin() { return !occupied[0]; }
            public void begin(int start, int end) { occupied[0] = true; q.stamp(start); }
            public void end(int start, int end) { occupied[0] = false; q.values.put(start, 17L); }
            public boolean availablePair(int start, int end) { return available(start); }
            public long elapsed(int start, int end) { return result(start); }
        };
        try (var r = new GpuQueryRing(elapsed, () -> 0, 2, 10, 32)) {
            assertEquals(0, r.begin("external conflict", 1)); assertEquals(0, q.next);
            occupied[0] = false; long token = r.begin("outer", 1);
            assertEquals(0, r.begin("nested", 1)); r.end(token, 2, 3);
            q.available.add(1); r.poll(2);
            assertEquals(17, r.drain().getFirst().gpuNanos()); assertEquals(1, q.reads);
            assertEquals(2, r.dropped());
        }
    }

    @Test void validatesResourceAndCounterBounds() {
        Fake q = new Fake();
        assertThrows(IllegalArgumentException.class, () -> new GpuQueryRing(q, () -> 0, 256, 10, 64));
        assertThrows(IllegalArgumentException.class, () -> new GpuQueryRing(q, () -> 0, 0, 10, 64));
        assertThrows(IllegalArgumentException.class, () -> new GpuQueryRing(q, () -> 0, 1, 0, 64));
        assertThrows(IllegalArgumentException.class, () -> new GpuQueryRing(q, () -> 0, 1, 10, 0));
    }
}
