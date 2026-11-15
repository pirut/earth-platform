package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
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
        MemoryPressure.looked((long) (gb * GB), 4 * GB, above, wholeHeap, seconds * SECOND, said::add);
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
        map.putAndTrim(64, 64, 64);
        assertEquals(16, map.size());
        assertTrue(map.containsKey(64), "keeps the newest");
    }
}
