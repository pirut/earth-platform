package io.earthplatform.minecraft.cubic.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Bounded timestamp pairs. Results are read only after BOTH queries report availability. */
final class GpuQueryRing implements AutoCloseable {
    interface Queries {
        int create();
        void stamp(int query);
        boolean available(int query);
        long result(int query);
        void delete(int query);
        default boolean canBegin() { return true; }
        default void begin(int start, int end) { stamp(start); }
        default void end(int start, int end) { stamp(end); }
        default boolean finish(int start, int end) { end(start, end); return true; }
        default boolean availablePair(int start, int end) { return available(start) && available(end); }
        default long elapsed(int start, int end) { return result(end) - result(start); }
    }

    record Sample(String pass, long frame, int age, long cpuNanos, long gpuNanos, int ranges, int faces) {}
    private static final class Entry {
        int start, end, ranges, faces;
        long token, frame, submitted, cpu;
        boolean ended;
        String pass;
    }
    private final Queries queries;
    private final LongSupplier clock;
    private final Entry[] entries;
    private final int maxAge, timestampBits;
    private final ArrayList<Sample> ready = new ArrayList<>();
    private int cursor;
    private long sequence, dropped;
    private boolean closed;

    GpuQueryRing(Queries queries, LongSupplier clock, int capacity, int maxAge, int timestampBits) {
        if (capacity < 1 || capacity > 255 || maxAge < 1 || timestampBits < 1 || timestampBits > 64)
            throw new IllegalArgumentException("Invalid query ring limits");
        this.queries = queries;
        this.clock = clock;
        this.maxAge = maxAge;
        this.timestampBits = timestampBits;
        entries = new Entry[capacity];
        for (int i = 0; i < capacity; i++) entries[i] = new Entry();
    }

    long begin(String pass, long frame) {
        if (closed) return 0;
        if (!queries.canBegin()) { dropped++; return 0; }
        int index = cursor;
        cursor = (cursor + 1) % entries.length;
        Entry e = entries[index];
        if (e.token != 0) { dropped++; return 0; }
        if (e.start == 0) { e.start = queries.create(); e.end = queries.create(); }
        e.token = (++sequence << 8) | (index + 1);
        e.frame = frame;
        e.pass = pass;
        e.submitted = clock.getAsLong();
        e.ended = false;
        queries.begin(e.start, e.end);
        return e.token;
    }

    void end(long token, int ranges, int faces) {
        if (closed || token == 0) return;
        int index = (int) (token & 255) - 1;
        if (index < 0 || index >= entries.length) return;
        Entry e = entries[index];
        if (e.token != token || e.ended) return;
        if (!queries.finish(e.start, e.end)) { discard(e); return; }
        e.cpu = clock.getAsLong() - e.submitted;
        e.ranges = ranges;
        e.faces = faces;
        e.ended = true;
    }

    void poll(long frame) {
        if (closed) return;
        for (Entry e : entries) {
            if (e.token == 0 || frame <= e.frame) continue;
            long age = frame - e.frame;
            // Do not reuse an overdue in-flight query: delete its names and replace lazily.
            if (age > maxAge || !e.ended) {
                if (age > maxAge) discard(e);
                continue;
            }
            if (!queries.availablePair(e.start, e.end)) continue;
            long wallAge = clock.getAsLong() - e.submitted;
            // A narrow timestamp counter could have wrapped more than once while queued.
            if (timestampBits < 63 && wallAge >= (1L << timestampBits)) { discard(e); continue; }
            long elapsed = queries.elapsed(e.start, e.end);
            if (timestampBits < 64) elapsed &= -1L >>> (64 - timestampBits);
            if (elapsed >= 0) {
                if (ready.size() == entries.length * 8) { ready.remove(0); dropped++; }
                ready.add(new Sample(e.pass, e.frame, (int) age, e.cpu, elapsed, e.ranges, e.faces));
            } else dropped++;
            e.token = 0;
            e.pass = null;
        }
    }

    private void discard(Entry e) {
        queries.delete(e.start);
        queries.delete(e.end);
        e.start = e.end = 0;
        e.token = 0;
        e.pass = null;
        dropped++;
    }

    List<Sample> drain() {
        List<Sample> samples = List.copyOf(ready);
        ready.clear();
        return samples;
    }

    long dropped() { return dropped; }

    @Override public void close() {
        if (closed) return;
        for (Entry e : entries) if (e.start != 0) {
            queries.delete(e.start);
            queries.delete(e.end);
            e.start = e.end = 0;
            e.token = 0;
        }
        ready.clear();
        closed = true;
    }
}
