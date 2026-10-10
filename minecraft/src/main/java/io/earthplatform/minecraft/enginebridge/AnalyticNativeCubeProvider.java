package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Deterministic synthetic fixtures for the existing full-3D CubeProvider boundary. Owns no
 * executor, queue, cache or native sections. BoundedCubeLoader must invoke load on the caller's
 * generation worker, never the game tick thread. Completion means generation has stopped.
 */
public final class AnalyticNativeCubeProvider implements CubeProvider {
    public static final String GENERATOR_ID = "earthplatform:analytic-native-cube";
    public static final String GENERATOR_VERSION = "analytic-native-cube-v1";
    public static final String CONFIG_VERSION = "analytic-fixtures-v1";
    public enum Fixture { FLAT, SLOPE, VOID, OVERHANG, BOUNDED_COLLISION }

    /**
     * All generation inputs. anchorY is an actual signed block Y, with no vertical compression.
     * Slope height is anchorY + floorDiv(x * riseX + z * riseZ, run), including negative X/Z.
     * OVERHANG repeats every 32 blocks in X/Z: ground at anchorY, a 12x12 roof at +6..+8,
     * and a 2x2 support at +1..+5. The space under most of its roof is genuine air.
     */
    public record Config(Fixture fixture, long anchorY, int riseX, int riseZ, int run,
                         int airState, int solidState) {
        public Config {
            Objects.requireNonNull(fixture, "fixture");
            long minimum = (long) Integer.MIN_VALUE * CubePos.SIZE;
            long maximum = (long) Integer.MAX_VALUE * CubePos.SIZE + CubePos.SIZE - 1;
            if (anchorY < minimum || anchorY > maximum - 8)
                throw new IllegalArgumentException("Anchor must leave room for the fixture in the core coordinate domain");
            // Bounded rises keep arithmetic exact over the entire signed core cube domain.
            if (run < 1 || Math.abs((long) riseX) > 1024 || Math.abs((long) riseZ) > 1024)
                throw new IllegalArgumentException("Positive slope run and rises within +/-1024 required");
            if (fixture != Fixture.SLOPE && (riseX != 0 || riseZ != 0 || run != 1))
                throw new IllegalArgumentException("Only slope fixtures accept slope parameters");
            if (fixture == Fixture.VOID && anchorY != 0)
                throw new IllegalArgumentException("Void fixture has no height anchor");
            if (!NativeCubeCodec.registeredState(airState).isEmpty())
                throw new IllegalArgumentException("Air material must be a registered native air state");
            if (NativeCubeCodec.registeredState(solidState).isEmpty())
                throw new IllegalArgumentException("Solid material must be a registered non-air state");
        }

        public static Config flat(long surfaceY) { return standard(Fixture.FLAT, surfaceY, 0, 0, 1); }
        public static Config slope(long originY, int riseX, int riseZ, int run) {
            return standard(Fixture.SLOPE, originY, riseX, riseZ, run);
        }
        public static Config voidWorld() { return standard(Fixture.VOID, 0, 0, 0, 1); }
        public static Config boundedCollision(long groundY) { return standard(Fixture.BOUNDED_COLLISION, groundY, 0, 0, 1); }
        public static Config overhang(long groundY) { return standard(Fixture.OVERHANG, groundY, 0, 0, 1); }

        private static Config standard(Fixture fixture, long y, int riseX, int riseZ, int run) {
            return new Config(fixture, y, riseX, riseZ, run,
                    Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState()),
                    Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState()));
        }

        /** The versioned, unambiguous configuration hash is persisted in the world's dataset identity. */
        public String fingerprint() {
            String canonical = CONFIG_VERSION + "\nfixture=" + fixture.name() + "\nanchorY=" + anchorY
                    + "\nriseX=" + riseX + "\nriseZ=" + riseZ + "\nrun=" + run
                    + "\nairState=" + airState + "\nsolidState=" + solidState + "\n";
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }

    private final Config config;
    private final WorldIdentity identity;

    /** Seed is part of save identity; these analytic v1 equations intentionally contain no randomness. */
    public AnalyticNativeCubeProvider(long seed, Config config, EnginePaletteContext palette) {
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(palette, "palette");
        if (!palette.fingerprint().equals(EnginePaletteContext.create(palette.biomes()).fingerprint()))
            throw new IllegalArgumentException("Palette fingerprint does not describe current native registries");
        this.identity = new WorldIdentity(seed, GENERATOR_ID, GENERATOR_VERSION,
                CONFIG_VERSION + ":" + config.fingerprint(), NativeCubeCodec.paletteIdentity(palette));
    }

    public WorldIdentity identity() { return identity; }
    public Config config() { return config; }

    @Override public CompletableFuture<ImmutableCube> load(CubePos position, CancellationToken cancellation) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(cancellation, "cancellation");
        try {
            cancellation.throwIfCancelled();
            if (config.fixture() == Fixture.VOID) return CompletableFuture.completedFuture(ImmutableCube.uniform(config.airState()));
            int[] blocks = new int[CubePos.VOLUME];
            for (int y = 0; y < CubePos.SIZE; y++) {
                long blockY = position.minBlockY() + y;
                for (int z = 0; z < CubePos.SIZE; z++) {
                    cancellation.throwIfCancelled();
                    long blockZ = position.minBlockZ() + z;
                    for (int x = 0; x < CubePos.SIZE; x++) {
                        long blockX = position.minBlockX() + x;
                        blocks[CubePos.index(x, y, z)] = solid(blockX, blockY, blockZ) ? config.solidState() : config.airState();
                    }
                }
            }
            ImmutableCube result = ImmutableCube.copyOf(blocks);
            cancellation.throwIfCancelled();
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }

    private boolean solid(long x, long y, long z) {
        return switch (config.fixture()) {
            case VOID -> false;
            case FLAT -> y <= config.anchorY();
            case SLOPE -> y <= config.anchorY() + Math.floorDiv(x * config.riseX() + z * config.riseZ(), config.run());
            case BOUNDED_COLLISION -> {
                if (y <= config.anchorY()) yield true;
                long dy = y - config.anchorY();
                // A finite true-3D fixture, wholly inside native cube X/Z 0, Y anchor..anchor+15.
                // The far capability is exactly the flat base outside that pinned native volume.
                boolean roof = dy == 4 && x >= 2 && x < 7 && z >= 3 && z < 10;
                boolean support = dy >= 1 && dy <= 3 && x == 2 && z >= 3 && z < 5;
                boolean wall = dy >= 1 && dy <= 3 && x == 9 && z >= 1 && z < 9;
                yield roof || support || wall;
            }
            case OVERHANG -> {
                if (y <= config.anchorY()) yield true;
                long relativeY = y - config.anchorY();
                int localX = Math.floorMod(x, 32), localZ = Math.floorMod(z, 32);
                boolean roof = relativeY >= 6 && relativeY <= 8 && localX >= 2 && localX < 14 && localZ >= 2 && localZ < 14;
                boolean support = relativeY >= 1 && relativeY <= 5 && localX >= 2 && localX < 4 && localZ >= 2 && localZ < 4;
                yield roof || support;
            }
        };
    }
}
