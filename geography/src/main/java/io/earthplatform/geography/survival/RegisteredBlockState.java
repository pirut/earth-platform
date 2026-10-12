package io.earthplatform.geography.survival;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** Persistent, engine-independent block identity. Runtime registry integers never enter this contract. */
public record RegisteredBlockState(String registryId, SortedMap<String,String> properties)
        implements Comparable<RegisteredBlockState> {
    public RegisteredBlockState {
        Objects.requireNonNull(registryId); Objects.requireNonNull(properties);
        if (!registryId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("A namespaced registry identifier is required");
        var copy = new TreeMap<String,String>();
        properties.forEach((key,value) -> {
            if (key == null || value == null || !key.matches("[a-z0-9_]+") || !value.matches("[a-z0-9_.-]+"))
                throw new IllegalArgumentException("Invalid registered state property");
            copy.put(key,value);
        });
        properties = Collections.unmodifiableSortedMap(copy);
    }
    public static RegisteredBlockState of(String registryId) { return of(registryId,Map.of()); }
    public static RegisteredBlockState of(String registryId, Map<String,String> properties) {
        return new RegisteredBlockState(registryId,new TreeMap<>(properties));
    }
    /** Stable syntax for identity, persistence and strict adapter resolution, never a numeric palette ID. */
    public String canonical() {
        if (properties.isEmpty()) return registryId;
        var entries = new java.util.StringJoiner(",",registryId+"[","]");
        properties.forEach((key,value) -> entries.add(key+"="+value));
        return entries.toString();
    }
    public boolean isAir() { return registryId.equals("minecraft:air") && properties.isEmpty(); }
    @Override public int compareTo(RegisteredBlockState other) { return canonical().compareTo(other.canonical()); }
}
