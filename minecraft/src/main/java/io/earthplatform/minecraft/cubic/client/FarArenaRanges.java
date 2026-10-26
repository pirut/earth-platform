package io.earthplatform.minecraft.cubic.client;

import java.util.HashMap;
import java.util.Map;
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

    /** The same immutable range is stored in both indexes; no per-size buckets are needed. */
    private record FreeRange(int start, int size) {}

    private int capacity;
    private final TreeMap<Integer, FreeRange> freeByStart = new TreeMap<>();
    /** Null only for the address-first scan used by performance comparisons. */
    private final TreeSet<FreeRange> freeBySize;
    private final Map<Integer, Allocation> live = new HashMap<>();
    private int allocated;

    FarArenaRanges(int capacity) { this(capacity, true); }

    FarArenaRanges(int capacity, boolean indexed) {
        if (capacity <= 0) throw new IllegalArgumentException("Arena capacity must be positive");
        this.capacity = capacity;
        freeBySize = indexed ? new TreeSet<>((left, right) -> {
            int bySize = Integer.compare(left.size, right.size);
            return bySize == 0 ? Integer.compare(left.start, right.start) : bySize;
        }) : null;
        addFree(0, capacity);
    }

    /** Allocates a positive range, or returns null when no contiguous free range is large enough. */
    Allocation allocate(int size) {
        if (size <= 0) throw new IllegalArgumentException("Allocation size must be positive");
        FreeRange fit = null;
        if (freeBySize != null) {
            // Start zero includes every equal-size range; the set then breaks ties by address.
            fit = freeBySize.ceiling(new FreeRange(0, size));
        } else {
            for (FreeRange range : freeByStart.values()) {
                if (range.size >= size) { fit = range; break; }
            }
        }
        if (fit == null) return null;
        int start = fit.start, available = fit.size;
        removeFree(fit);
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
        // Allocation deliberately keeps Object's identity equality. Conditional removal performs
        // one lookup while leaving a foreign/stale token's current owner untouched.
        if (!live.remove(allocation.start, allocation))
            throw new IllegalArgumentException("Allocation does not belong to this arena or was already released");
        allocated -= allocation.size;
        int start = allocation.start, size = allocation.size;
        Map.Entry<Integer, FreeRange> before = freeByStart.lowerEntry(start);
        if (before != null && before.getValue().start + before.getValue().size == start) {
            FreeRange range = before.getValue();
            removeFree(range);
            start = range.start;
            size += range.size;
        }
        Map.Entry<Integer, FreeRange> after = freeByStart.ceilingEntry(start);
        if (after != null && start + size == after.getValue().start) {
            FreeRange range = after.getValue();
            removeFree(range);
            size += range.size;
        }
        addFree(start, size);
    }

    /** Makes the arena larger: the units from its old end to its new one are free, joined to a free range ending there. */
    void grow(int capacity) {
        if (capacity <= this.capacity) throw new IllegalArgumentException("An arena only grows");
        int start = this.capacity, size = capacity - this.capacity;
        Map.Entry<Integer, FreeRange> before = freeByStart.lowerEntry(start);
        if (before != null && before.getValue().start + before.getValue().size == start) {
            FreeRange range = before.getValue();
            removeFree(range);
            start = range.start;
            size += range.size;
        }
        addFree(start, size);
        this.capacity = capacity;
    }

    int allocatedUnits() { return allocated; }
    int freeUnits() { return capacity - allocated; }
    int allocationCount() { return live.size(); }
    int freeRangeCount() { return freeByStart.size(); }

    private void addFree(int start, int size) {
        FreeRange range = new FreeRange(start, size);
        freeByStart.put(start, range);
        if (freeBySize != null) freeBySize.add(range);
    }

    private void removeFree(FreeRange range) {
        freeByStart.remove(range.start);
        if (freeBySize != null) freeBySize.remove(range);
    }
}
