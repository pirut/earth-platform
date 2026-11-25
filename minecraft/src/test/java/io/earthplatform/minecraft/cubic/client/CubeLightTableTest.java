package io.earthplatform.minecraft.cubic.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CubeLightTableTest {
    private static final int KEYS = 64;

    private static byte[] lightFor(long key, int round) {
        byte[] light = new byte[4096];
        Arrays.fill(light, (byte) ((key * 31 + round) & 0x7f));
        return light;
    }

    @Test void otherThreadsSeeWhatTheOwnerPut() throws Exception {
        CubeLightTable table = new CubeLightTable(Thread.currentThread());
        byte[] light = lightFor(5, 0);
        table.put(5, light);
        AtomicReference<byte[]> seen = new AtomicReference<>();
        Thread reader = new Thread(() -> seen.set(table.get(5)));
        reader.start();
        reader.join();
        assertSame(light, seen.get());
        table.remove(5);
        reader = new Thread(() -> seen.set(table.get(5)));
        reader.start();
        reader.join();
        assertNull(seen.get());
        assertNull(table.get(5));
    }

    /** Flywheel's workers relight machines while the render thread uploads and drops cubes' light. */
    @Test void readersDuringWritesSeeWholeArraysAndNeverFail() throws Exception {
        CubeLightTable table = new CubeLightTable(Thread.currentThread());
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger(), torn = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(4);
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            Thread reader = new Thread(() -> {
                started.countDown();
                try {
                    long key = 0;
                    while (!done.get()) {
                        byte[] light = table.get(key);
                        if (light != null) {
                            byte first = light[0];
                            if (light.length != 4096 || light[4095] != first || light[2048] != first) torn.incrementAndGet();
                        }
                        reads.incrementAndGet();
                        key = (key + 7) % KEYS;
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            reader.start();
            readers.add(reader);
        }
        started.await();
        // The owner: puts, replaces and removes, and clears now and then, which rehashes its own map.
        for (int round = 0; round < 20_000; round++) {
            long key = (round * 13L) % KEYS;
            if (round % 5 == 4) table.remove(key);
            else table.put(key, lightFor(key, round));
            if (round % 4_000 == 3_999) table.clear();
        }
        done.set(true);
        for (Thread reader : readers) reader.join();
        if (failure.get() != null) fail(failure.get());
        assertEquals(0, torn.get());
        assertTrue(reads.get() > 0);
    }
}
