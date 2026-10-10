package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import io.earthplatform.minecraft.scene.SceneTerrain;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Adapts the accepted, bounded SceneTerrain fixture to the existing full-3D CubeProvider API.
 * This is the same source/appearance and heightfield fill, not additional Earth detail. Unknown
 * source coverage is a failure even for a cube that would otherwise be entirely air or stone.
 *
 * Prepare after registries freeze, on a bootstrap worker: construction may load/verify/extract
 * the offline packs. Thereafter load performs no I/O and owns no executor or work queue. The
 * caller's bounded generation workers invoke load, never the game/render thread. A returned
 * future is already settled, including after cooperative cancellation; no work escapes it.
 */
public final class NativeEarthCubeProvider implements CubeProvider {
    public static final String GENERATOR_ID = "earthplatform:native-earth-cube";
    public static final String GENERATOR_VERSION = "scene-native-cube-fill-v1";

    // SceneTerrain itself is deliberately not shared or exposed: its access-ordered LRU is not
    // thread-safe. Both generation and the optional summary view use this same short lock.
    private final SceneTerrain source;
    private final Object sourceLock = new Object();
    private final WorldIdentity identity;
    private final int airState;
    private final int stoneState;
    private final int registrySize;
    private final SceneSurfaceSampler surfaceSampler;

    /**
     * Blocking preparation only, suitable for the host's bounded asynchronous bootstrap stage.
     * SceneTerrain captures its existing natural-relief opt-in here, never during cube loading.
     * No caller-owned SceneTerrain is accepted, so its mutable cache cannot escape the guard.
     */
    public static NativeEarthCubeProvider prepare(EnginePaletteContext palette) {
        Objects.requireNonNull(palette, "palette");
        if (!palette.fingerprint().equals(EnginePaletteContext.create(palette.biomes()).fingerprint()))
            throw new IllegalArgumentException("Palette fingerprint does not describe current native registries");
        return new NativeEarthCubeProvider(new SceneTerrain(), palette);
    }

    private NativeEarthCubeProvider(SceneTerrain source, EnginePaletteContext palette) {
        this.source = source;
        // Retain the accepted source's seed, but explicitly use the generic native codec palette.
        // SceneTerrain.world's older scene-specific palette hash is not this provider's palette.
        identity = new WorldIdentity(source.world(palette).seed(), GENERATOR_ID, GENERATOR_VERSION,
                source.identity(), NativeCubeCodec.paletteIdentity(palette));
        airState = stateId(Blocks.AIR.defaultBlockState());
        stoneState = stateId(Blocks.STONE.defaultBlockState());
        registrySize = Block.BLOCK_STATE_REGISTRY.size();
        surfaceSampler = new SceneSurfaceSampler() {
            @Override public long surfaceY(long x, long z) { return sampleColumn(x, z).surfaceY(); }
            @Override public int argb(long x, long z, long expectedY) {
                SceneTerrain.Column column = sampleColumn(x, z);
                if (column.surfaceY() != expectedY)
                    throw new IllegalArgumentException("Far sampler changed its pinned surface");
                return column.argb();
            }
            @Override public String identity() { return identity.datasetVersion(); }
        };
    }

    public WorldIdentity identity() { return identity; }

    /**
     * Optional, separate capability for the current heightfield DH summaries. This view reports
     * the pinned, unedited source only; it is neither the engine's full-3D API nor edit-aware LOD.
     * Consumers needing caves, overhangs or edited geometry must instead use authoritative cubes.
     */
    public SceneSurfaceSampler surfaceSampler() { return surfaceSampler; }

    @Override public CompletableFuture<ImmutableCube> load(CubePos position, CancellationToken cancellation) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(cancellation, "cancellation");
        try {
            cancellation.throwIfCancelled();
            if (position.minBlockY() < -11008 || position.minBlockY() + CubePos.SIZE > 8864)
                throw new MissingTerrainException("Cube outside bounded native Y envelope");
            checkRegistry();
            int[] blocks = new int[CubePos.VOLUME];
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    cancellation.throwIfCancelled();
                    SceneTerrain.Column column;
                    synchronized (sourceLock) {
                        // A cancelled worker waiting behind one column must not sample another.
                        cancellation.throwIfCancelled();
                        column = sampleColumn(position.minBlockX() + x, position.minBlockZ() + z);
                    }
                    cancellation.throwIfCancelled();
                    int top = stateId(column.top()), subsurface = stateId(column.subsurface());
                    // Column is immutable. Release the source/cache lock BEFORE filling states;
                    // independent loader workers can sample while this worker builds its cube.
                    for (int y = 0; y < CubePos.SIZE; y++) {
                        long absoluteY = position.minBlockY() + y;
                        blocks[CubePos.index(x, y, z)] = absoluteY > column.surfaceY() ? airState
                                : absoluteY == column.surfaceY() ? top
                                : absoluteY >= column.surfaceY() - 3 ? subsurface : stoneState;
                    }
                }
            }
            ImmutableCube result = ImmutableCube.copyOf(blocks);
            cancellation.throwIfCancelled();
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private SceneTerrain.Column sampleColumn(long x, long z) {
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE)
            throw new MissingTerrainException("Column outside bounded scene coordinate domain");
        synchronized (sourceLock) { return source.sample(x, z); }
    }

    private void checkRegistry() {
        if (Block.BLOCK_STATE_REGISTRY.size() != registrySize
                || stateId(Blocks.AIR.defaultBlockState()) != airState
                || stateId(Blocks.STONE.defaultBlockState()) != stoneState)
            throw new IllegalStateException("Native block registry changed after palette pinning");
    }

    /** Never use a native registry fallback that silently converts an unknown state to air. */
    private static int stateId(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (NativeCubeCodec.registeredState(id) != state)
            throw new IllegalStateException("Native block state mapping changed after palette pinning");
        return id;
    }
}
