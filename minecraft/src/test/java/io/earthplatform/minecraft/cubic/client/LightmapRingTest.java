package io.earthplatform.minecraft.cubic.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightmapRingTest {
    @Test void activityHoldsOriginalWithoutAllocatingOrDeletingAcrossTransitions() {
        LightmapRing ring = new LightmapRing();
        AtomicInteger next = new AtomicInteger(11);
        List<Integer> freed = new ArrayList<>();
        int current = ring.select(10, false, next::getAndIncrement, freed::add);
        assertEquals(10, current);
        assertTrue(ring.active());
        assertEquals(13, next.get());
        for (int i = 0; i < 100; i++) {
            current = ring.select(current, true, next::getAndIncrement, freed::add);
            current = ring.select(current, false, next::getAndIncrement, freed::add);
            assertEquals(10, current);
        }
        assertEquals(13, next.get());
        assertTrue(freed.isEmpty());
        assertEquals(11, ring.select(current, true, next::getAndIncrement, freed::add));
        ring.release(freed::add);
        assertEquals(List.of(11, 12), freed);
    }

    @Test void holdingOriginalRejectsExternalOwnersAndRollsBackPartialPreparation() {
        LightmapRing ring = new LightmapRing();
        List<Integer> freed = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ring.select(10, false, () -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("second allocation");
            return 11;
        }, freed::add));
        assertEquals(List.of(11), freed);
        assertFalse(ring.active());
        AtomicInteger next = new AtomicInteger(21);
        assertEquals(20, ring.select(20, false, next::getAndIncrement, freed::add));
        assertThrows(IllegalStateException.class, () -> ring.select(99, false, next::getAndIncrement, freed::add));
        assertEquals(99, ring.original(99));
    }

    @Test void rotatesThreeNamesWithoutAllocatingAgainAndDoesNotExposeOwnedArray() {
        LightmapRing ring = new LightmapRing();
        AtomicInteger next = new AtomicInteger(11);
        List<Integer> freed = new ArrayList<>();
        int current = 10;
        for (int expected : new int[] {11, 12, 10, 11, 12, 10})
            assertEquals(expected, current = ring.advance(current, next::getAndIncrement, freed::add));
        assertEquals(13, next.get());
        assertTrue(ring.owns(10)); assertTrue(ring.owns(11)); assertTrue(ring.owns(12));
        assertFalse(ring.owns(0)); assertFalse(ring.owns(99));
        int[] copy = ring.snapshot(); copy[0] = 99;
        assertEquals(10, ring.original(current));
        ring.release(freed::add);
        ring.release(freed::add);
        assertEquals(List.of(11, 12), freed);
        assertFalse(ring.active());
    }

    @Test void everyRotationRestoresTheOriginalBeforeDeletingOnlyExtras() {
        for (int rotations = 1; rotations <= 3; rotations++) {
            LightmapRing ring = new LightmapRing();
            AtomicInteger next = new AtomicInteger(21);
            List<Integer> freed = new ArrayList<>();
            int current = 20;
            for (int i = 0; i < rotations; i++) current = ring.advance(current, next::getAndIncrement, freed::add);
            assertEquals(20, ring.original(current));
            ring.release(freed::add);
            assertEquals(List.of(21, 22), freed);
            AtomicInteger replacement = new AtomicInteger(31);
            assertEquals(31, ring.advance(30, replacement::getAndIncrement, freed::add));
        }
    }

    @Test void failedFirstOrSecondAllocationRollsBackAndCanRetry() {
        for (int failAt = 1; failAt <= 2; failAt++) {
            LightmapRing ring = new LightmapRing();
            List<Integer> freed = new ArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            int failureIndex = failAt;
            assertThrows(IllegalStateException.class, () -> ring.advance(10, () -> {
                if (calls.incrementAndGet() == failureIndex) throw new IllegalStateException("synthetic allocation failure");
                return 11;
            }, freed::add));
            assertEquals(failAt == 1 ? List.of() : List.of(11), freed);
            assertFalse(ring.active());
            assertEquals(10, ring.original(10));
            AtomicInteger retry = new AtomicInteger(30);
            assertEquals(30, ring.advance(10, retry::getAndIncrement, freed::add));
        }
    }

    @Test void invalidAllocatorResultsNeverDeleteOriginalOrDeleteAnExtraTwice() {
        for (int[] answers : new int[][] {{0}, {-1}, {10}, {11, -1}, {11, 0}, {11, 10}, {11, 11}}) {
            LightmapRing ring = new LightmapRing();
            AtomicInteger at = new AtomicInteger();
            List<Integer> freed = new ArrayList<>();
            assertThrows(IllegalStateException.class, () -> ring.advance(10, () -> answers[at.getAndIncrement()], freed::add));
            assertEquals(answers[0] == 11 ? List.of(11) : List.of(), freed);
            assertFalse(ring.active());
        }
    }

    @Test void releaseAttemptsBothExtrasEvenIfOneDisposerThrowsAndCannotRepeatThem() {
        LightmapRing ring = new LightmapRing();
        AtomicInteger next = new AtomicInteger(11);
        ring.advance(10, next::getAndIncrement, id -> fail());
        List<Integer> freed = new ArrayList<>();
        assertEquals(10, ring.original(11));
        assertThrows(IllegalStateException.class, () -> ring.release(id -> {
            freed.add(id); throw new IllegalStateException("synthetic cleanup failure");
        }));
        ring.release(freed::add);
        assertEquals(List.of(11, 12), freed);
        assertFalse(ring.active());
    }

    @Test void unexpectedExternalNameIsPreservedAndCannotEnterThePool() {
        LightmapRing ring = new LightmapRing();
        AtomicInteger next = new AtomicInteger(11);
        ring.advance(10, next::getAndIncrement, id -> fail());
        assertEquals(99, ring.original(99));
        assertThrows(IllegalStateException.class, () -> ring.advance(99, next::getAndIncrement, id -> fail()));
        List<Integer> freed = new ArrayList<>(); ring.release(freed::add);
        assertEquals(List.of(11, 12), freed);
    }
    @Test void unsignedGlNamesWithTheHighBitSetRemainValidJavaInts() {
        LightmapRing ring = new LightmapRing();
        AtomicInteger next = new AtomicInteger(-2);
        List<Integer> freed = new ArrayList<>();
        int original = Integer.MIN_VALUE;
        int current = ring.advance(original, next::getAndDecrement, freed::add);
        assertEquals(-2, current);
        assertEquals(-3, current = ring.advance(current, next::getAndDecrement, freed::add));
        assertEquals(original, ring.advance(current, next::getAndDecrement, freed::add));
        ring.release(freed::add);
        assertEquals(List.of(-2, -3), freed);
    }
}
