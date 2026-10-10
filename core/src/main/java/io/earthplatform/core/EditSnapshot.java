package io.earthplatform.core;

import java.util.*;

/** Immutable sparse player overlay. Explicit air edits remain meaningful after base regeneration. */
public record EditSnapshot(CubePos position, long revision, Map<Integer, Integer> blocks) {
    public EditSnapshot {
        Objects.requireNonNull(position); Objects.requireNonNull(blocks);
        if (revision < 0 || blocks.size() > CubePos.VOLUME) throw new IllegalArgumentException("Invalid edit snapshot");
        TreeMap<Integer, Integer> copy = new TreeMap<>();
        blocks.forEach((index, state) -> { CubePos.checkIndex(index); ImmutableCube.checkState(state); copy.put(index, state); });
        blocks = Collections.unmodifiableMap(copy);
    }
    public static EditSnapshot empty(CubePos position) { return new EditSnapshot(position, 0, Map.of()); }
    public EditSnapshot withBlock(int index, int state) {
        CubePos.checkIndex(index); ImmutableCube.checkState(state);
        TreeMap<Integer, Integer> replacement = new TreeMap<>(blocks); replacement.put(index, state);
        return new EditSnapshot(position, Math.incrementExact(revision), replacement);
    }
    public int read(ImmutableCube base, int index) { CubePos.checkIndex(index); return blocks.getOrDefault(index, base.get(index)); }
    public ImmutableCube applyTo(ImmutableCube base) { return base.withEdits(blocks); }
}
