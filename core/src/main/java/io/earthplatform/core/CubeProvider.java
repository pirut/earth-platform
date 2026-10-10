package io.earthplatform.core;

import java.util.concurrent.CompletableFuture;

/**
 * Plugin boundary for deterministic geography or other generation layers.
 * Implementations must be thread-safe and return identical blocks for a pinned world identity.
 * A provider owns its I/O, retries and native-resolution tile cache; never make a request per block.
 * Complete the future only when work has finished, including after cooperative cancellation.
 */
@FunctionalInterface
public interface CubeProvider {
    CompletableFuture<ImmutableCube> load(CubePos position, CancellationToken cancellation);
}
