package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Bounded known native states, with diagnostic illumination and no unknown-as-air fallback. */
public final class SceneNeighborhood implements BlockAndTintGetter {
    public static final int MAX_OWNERS = 256;
    public static final int MIN_Y = -11008;
    public static final int HEIGHT = 19872;
    /** Fixed cosmetic grass appearance; not a sampled biome, climate, or landcover measurement. */
    public static final int COSMETIC_GRASS_TINT = 0x91BD59;
    private final Map<CubePos, SceneCube> cubes;
    private final Consumer<SceneCube> onRead;
    private final SceneRenderEnvelope envelope;

    public SceneNeighborhood(Map<CubePos, SceneCube> cubes) { this(cubes,SceneRenderEnvelope.NATIVE); }

    public SceneNeighborhood(Map<CubePos, SceneCube> cubes, SceneRenderEnvelope envelope) {
        this.envelope=Objects.requireNonNull(envelope,"envelope");
        Objects.requireNonNull(cubes, "cubes");
        if (cubes.isEmpty() || cubes.size() > MAX_OWNERS) {
            throw new IllegalArgumentException("Scene neighborhoods require 1.." + MAX_OWNERS + " owners");
        }
        Map<CubePos, SceneCube> owned = new HashMap<>();
        SceneCube first = null;
        for (var entry : cubes.entrySet()) {
            SceneCube cube = Objects.requireNonNull(entry.getValue(), "cube");
            if (!cube.snapshot().position().equals(entry.getKey())) {
                throw new IllegalArgumentException("Neighborhood key does not identify its snapshot");
            }
            if (first == null) first = cube;
            if (!first.snapshot().world().equals(cube.snapshot().world())
                    || first.snapshot().epoch() != cube.snapshot().epoch()) {
                throw new IllegalArgumentException("A scene neighborhood cannot mix worlds or connection epochs");
            }
            if(!envelope.contains(entry.getKey()))throw new IllegalArgumentException("Neighborhood cube outside explicit render envelope");
            owned.put(entry.getKey(), cube);
            if (owned.size() > MAX_OWNERS) throw new IllegalArgumentException("Neighborhood owner budget exceeded");
        }
        this.cubes = Map.copyOf(owned);
        onRead = ignored -> {};
    }

    private SceneNeighborhood(Map<CubePos, SceneCube> cubes, Consumer<SceneCube> onRead, SceneRenderEnvelope envelope) {
        this.cubes = cubes;
        this.envelope=envelope;
        this.onRead = Objects.requireNonNull(onRead);
    }

    /** Compilation gets a private read observer; the source neighborhood remains immutable. */
    SceneNeighborhood tracking(Consumer<SceneCube> onRead) { return new SceneNeighborhood(cubes, onRead, envelope); }
    SceneCube cube(CubePos position) { return cubes.get(position); }
    public int size() { return cubes.size(); }
    public boolean contains(CubePos position) { return cubes.containsKey(position); }

    @Override public BlockState getBlockState(BlockPos pos) {
        SceneCube cube = cubes.get(CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()));
        if (cube == null) {
            throw new SparseFacadeException("unknown scene mesh dependency at " + pos + "; defer mesh, never invent air");
        }
        onRead.accept(cube);
        return cube.state(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { getBlockState(pos); return null; }
    @Override public int getHeight() { return envelope.height(); }
    @Override public int getMinBuildHeight() { return envelope.minY(); }
    @Override public float getShade(Direction direction, boolean shade) {
        if (!shade) return 1;
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }
    @Override public LevelLightEngine getLightEngine() {
        throw new SparseFacadeException("scene uses fixed diagnostic illumination; no native lighting engine");
    }
    @Override public int getBrightness(LightLayer layer, BlockPos pos) { getBlockState(pos); return 15; }
    @Override public int getRawBrightness(BlockPos pos, int ambient) {
        getBlockState(pos);
        return 15 - Math.min(15, Math.max(0, ambient));
    }
    @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        getBlockState(pos);
        // Only the accepted grass model uses a tint. The constant is cosmetic, like the fixed light.
        return COSMETIC_GRASS_TINT;
    }
}
