package io.earthplatform.core;

import java.util.*;

/** Atomic, strictly bounded unions of player-centered boxes. All methods are thread-safe. */
public final class CubeTickets {
    private final int budget;
    private final int maxTickets;
    private final Map<String, Set<CubePos>> tickets = new HashMap<>();
    private final Map<CubePos, Integer> references = new HashMap<>();
    public CubeTickets(int budget, int maxTickets) {
        if (budget < 1 || maxTickets < 1) throw new IllegalArgumentException("Positive limits required");
        this.budget = budget; this.maxTickets = maxTickets;
    }
    public int budget() { return budget; }
    public synchronized int size() { return references.size(); }
    public synchronized int ticketCount() { return tickets.size(); }
    public synchronized int references(CubePos pos) { return references.getOrDefault(pos, 0); }
    public synchronized Set<CubePos> positions() { return Set.copyOf(references.keySet()); }
    public synchronized Delta update(String id, CubePos center, int horizontalRadius, int verticalRadius) {
        Objects.requireNonNull(id); Objects.requireNonNull(center);
        if (id.isBlank() || id.length() > 256) throw new IllegalArgumentException("Invalid ticket ID");
        if (!tickets.containsKey(id) && tickets.size() >= maxTickets) throw new BudgetExceededException("Ticket limit");
        Set<CubePos> replacement = box(center, horizontalRadius, verticalRadius, budget);
        Set<CubePos> previous = tickets.getOrDefault(id, Set.of());
        Set<CubePos> added = new HashSet<>(), removed = new HashSet<>();
        for (CubePos p : replacement) if (!references.containsKey(p)) added.add(p);
        for (CubePos p : previous) if (!replacement.contains(p) && references.get(p) == 1) removed.add(p);
        if ((long) references.size() + added.size() - removed.size() > budget)
            throw new BudgetExceededException("Resident cube budget " + budget + " exceeded");
        for (CubePos p : previous) decrement(p);
        for (CubePos p : replacement) references.merge(p, 1, Math::addExact);
        tickets.put(id, replacement);
        return new Delta(added, removed);
    }
    public synchronized Delta remove(String id) {
        Set<CubePos> previous = tickets.remove(id);
        if (previous == null) return new Delta(Set.of(), Set.of());
        Set<CubePos> removed = new HashSet<>();
        for (CubePos p : previous) { decrement(p); if (!references.containsKey(p)) removed.add(p); }
        return new Delta(Set.of(), removed);
    }
    private void decrement(CubePos pos) {
        int count = references.get(pos);
        if (count == 1) references.remove(pos); else references.put(pos, count - 1);
    }
    public static Set<CubePos> box(CubePos center, int horizontal, int vertical, int maxSize) {
        Objects.requireNonNull(center);
        if (horizontal < 0 || vertical < 0 || maxSize < 1) throw new IllegalArgumentException("Invalid box bounds");
        long width = 2L * horizontal + 1, height = 2L * vertical + 1;
        if (width > maxSize || height > maxSize || width > maxSize / width || width * width > maxSize / height)
            throw new BudgetExceededException("Ticket box exceeds budget");
        int minX = Math.subtractExact(center.x(), horizontal), maxX = Math.addExact(center.x(), horizontal);
        int minY = Math.subtractExact(center.y(), vertical), maxY = Math.addExact(center.y(), vertical);
        int minZ = Math.subtractExact(center.z(), horizontal), maxZ = Math.addExact(center.z(), horizontal);
        Set<CubePos> result = new HashSet<>();
        // Long loop indices avoid wraparound when a cube lies at Integer.MAX_VALUE.
        for (long x = minX; x <= maxX; x++) for (long y = minY; y <= maxY; y++) for (long z = minZ; z <= maxZ; z++)
            result.add(new CubePos((int) x, (int) y, (int) z));
        return Set.copyOf(result);
    }
    public record Delta(Set<CubePos> added, Set<CubePos> removed) {
        public Delta { added = Set.copyOf(added); removed = Set.copyOf(removed); }
    }
    public static final class BudgetExceededException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public BudgetExceededException(String message) { super(message); }
    }
}
