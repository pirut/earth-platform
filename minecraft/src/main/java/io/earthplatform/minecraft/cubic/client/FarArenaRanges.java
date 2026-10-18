package io.earthplatform.minecraft.cubic.client;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Face ranges in one fixed-size GPU arena. An address index coalesces adjacent free ranges; a
 * size index finds the smallest sufficient range without scanning fragmented addresses. Equal-size
 * ranges use the lowest address, so a sequence of allocations and releases is reproducible.
 * Everything here, like the arena itself, belongs to the render thread.
 */
final class FarArenaRanges {
    /** A live allocation's identity; retaining an old token cannot release a reused address. */
    static final class Allocation {
        private final int start, size;

        private Allocation(int start, int size) { this.start = start; this.size = size; }

        int start() { return start; }
        int size() { return size; }
    }

    private final int capacity;
    private final TreeMap<Integer, Integer> freeByStart = new TreeMap<>();
    /** Null only for the address-first scan used by performance comparisons. */
    private final TreeMap<Integer, NavigableSet<Integer>> freeBySize;
    private final Map<Integer, Allocation> live = new HashMap<>();
    private int allocated;

    FarArenaRanges(int capacity) { this(capacity, true); }

    FarArenaRanges(int capacity, boolean indexed) {
        if (capacity <= 0) throw new IllegalArgumentException("Arena capacity must be positive");
        this.capacity = capacity;
        freeBySize = indexed ? new TreeMap<>() : null;
        addFree(0, capacity);
    }

    /** Allocates a positive range, or returns null when no contiguous free range is large enough. */
    Allocation allocate(int size) {
        if (size <= 0) throw new IllegalArgumentException("Allocation size must be positive");
        int start, available;
        if (freeBySize != null) {
            Map.Entry<Integer, NavigableSet<Integer>> fit = freeBySize.ceilingEntry(size);
            if (fit == null) return null;
            available = fit.getKey();
            start = fit.getValue().first();
        } else {
            Map.Entry<Integer, Integer> fit = null;
            for (Map.Entry<Integer, Integer> entry : freeByStart.entrySet()) {
                if (entry.getValue() >= size) { fit = entry; break; }
            }
            if (fit == null) return null;
            start = fit.getKey();
            available = fit.getValue();
        }
        removeFree(start, available);
        if (available > size) addFree(start + size, available - size);
        Allocation allocation = new Allocation(start, size);
        live.put(start, allocation);
        allocated += size;
        return allocation;
    }

    /**
     * Releases exactly this live token. Rejects duplicate, foreign and stale releases before
     * changing either free index, including when a newer allocation has reused the same address.
     */
    void release(Allocation allocation) {
        Objects.requireNonNull(allocation, "allocation");
        if (live.get(allocation.start) != allocation)
            throw new IllegalArgumentException("Allocation does not belong to this arena or was already released");
        live.remove(allocation.start);
        allocated -= allocation.size;
        int start = allocation.start, size = allocation.size;
        Map.Entry<Integer, Integer> before = freeByStart.lowerEntry(start);
        if (before != null && before.getKey() + before.getValue() == start) {
            removeFree(before.getKey(), before.getValue());
            start = before.getKey();
            size += before.getValue();
        }
        Map.Entry<Integer, Integer> after = freeByStart.ceilingEntry(start);
        if (after != null && start + size == after.getKey()) {
            removeFree(after.getKey(), after.getValue());
            size += after.getValue();
        }
        addFree(start, size);
    }

    int allocatedUnits() { return allocated; }
    int freeUnits() { return capacity - allocated; }
    int allocationCount() { return live.size(); }
    int freeRangeCount() { return freeByStart.size(); }

    private void addFree(int start, int size) {
        freeByStart.put(start, size);
        if (freeBySize != null) freeBySize.computeIfAbsent(size, ignored -> new TreeSet<>()).add(start);
    }

    private void removeFree(int start, int size) {
        freeByStart.remove(start);
        if (freeBySize != null) {
            NavigableSet<Integer> starts = freeBySize.get(size);
            starts.remove(start);
            if (starts.isEmpty()) freeBySize.remove(size);
        }
    }
}
