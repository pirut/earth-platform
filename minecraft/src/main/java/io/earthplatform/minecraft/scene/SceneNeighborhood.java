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
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.world.AuxiliaryLightManager;
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
    /** Explicit fixture lighting only. Neither policy is a propagated cubic light engine. */
    public enum LightingPolicy { DIAGNOSTIC_FULLBRIGHT, SHADER_OUTDOOR_NON_EMISSIVE }
    private final LightingPolicy lightingPolicy;
    private final boolean directionalShadingDisabled;
    private final Map<CubePos, SceneCube> cubes;
    private final Consumer<SceneCube> onRead;
    private final SceneRenderEnvelope envelope;
    private final SceneReadFootprint.Recorder footprint;

    public SceneNeighborhood(Map<CubePos, SceneCube> cubes) { this(cubes,SceneRenderEnvelope.NATIVE); }

    public SceneNeighborhood(Map<CubePos, SceneCube> cubes, SceneRenderEnvelope envelope) {
        this(cubes, envelope, LightingPolicy.DIAGNOSTIC_FULLBRIGHT);
    }
    public SceneNeighborhood(Map<CubePos, SceneCube> cubes, SceneRenderEnvelope envelope, LightingPolicy lightingPolicy) {
        this(cubes, envelope, lightingPolicy, false);
    }
    public SceneNeighborhood(Map<CubePos, SceneCube> cubes, SceneRenderEnvelope envelope, LightingPolicy lightingPolicy, boolean directionalShadingDisabled) {
        this.directionalShadingDisabled=directionalShadingDisabled;
        this.envelope=Objects.requireNonNull(envelope,"envelope");
        this.lightingPolicy=Objects.requireNonNull(lightingPolicy,"lightingPolicy");
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
        footprint = null;
    }

    private SceneNeighborhood(Map<CubePos, SceneCube> cubes, Consumer<SceneCube> onRead, SceneRenderEnvelope envelope, SceneReadFootprint.Recorder footprint, LightingPolicy lightingPolicy, boolean directionalShadingDisabled) {
        this.cubes = cubes;
        this.lightingPolicy = Objects.requireNonNull(lightingPolicy);
        this.directionalShadingDisabled=directionalShadingDisabled;
        this.envelope=envelope;
        this.onRead = Objects.requireNonNull(onRead);
        this.footprint = footprint;
    }

    /** Compilation gets a private read observer; the source neighborhood remains immutable. */
    SceneNeighborhood tracking(Consumer<SceneCube> onRead) { return new SceneNeighborhood(cubes, onRead, envelope, null, lightingPolicy, directionalShadingDisabled); }
    SceneNeighborhood tracking(Consumer<SceneCube> onRead, SceneReadFootprint.Recorder footprint) {
        return new SceneNeighborhood(cubes, onRead, envelope, Objects.requireNonNull(footprint), lightingPolicy, directionalShadingDisabled);
    }
    SceneRenderEnvelope envelope() { return envelope; }
    public LightingPolicy lightingPolicy() { return lightingPolicy; }
    public boolean directionalShadingDisabled() { return directionalShadingDisabled; }
    /** Temporary diagnostics list of existing immutable payload identities; never copies section bytes. */
    java.util.List<io.earthplatform.minecraft.network.EngineCubeSnapshot> retainedSnapshots() {
        return cubes.values().stream().map(SceneCube::snapshot).toList();
    }
    SceneCube cube(CubePos position) { return cubes.get(position); }
    public int size() {
        if (footprint != null) throw unsupported("neighborhood membership is not a native render getter");
        return cubes.size();
    }
    public boolean contains(CubePos position) {
        if (footprint != null) throw unsupported("neighborhood membership is not a native render getter");
        return cubes.containsKey(position);
    }

    private SparseFacadeException unsupported(String message) {
        if (footprint != null) footprint.invalidate();
        return new SparseFacadeException(message);
    }
    private void record(SceneReadFootprint.Kind kind, BlockPos pos, int argument, SceneReadFootprint.Value value) {
        if (footprint != null) footprint.record(new SceneReadFootprint.Query(kind,
                pos == null ? 0 : pos.getX(), pos == null ? 0 : pos.getY(), pos == null ? 0 : pos.getZ(), argument), value);
    }
    private SceneCube known(BlockPos pos) {
        if (pos == null) throw unsupported("null scene getter position");
        SceneCube cube = cubes.get(CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()));
        if (cube == null) throw unsupported("unknown scene mesh dependency at " + pos + "; defer mesh, never invent air");
        try { onRead.accept(cube); }
        catch (RuntimeException | Error error) { if (footprint != null) footprint.invalidate(); throw error; }
        return cube;
    }
    @Override public BlockState getBlockState(BlockPos pos) {
        SceneCube cube = known(pos);
        BlockState result = cube.state(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        record(SceneReadFootprint.Kind.STATE, pos, 0, SceneReadFootprint.Value.identity(result));
        return result;
    }
    @Override public FluidState getFluidState(BlockPos pos) {
        FluidState result = getBlockState(pos).getFluidState();
        record(SceneReadFootprint.Kind.FLUID, pos, 0, SceneReadFootprint.Value.identity(result));
        return result;
    }
    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        getBlockState(pos);
        record(SceneReadFootprint.Kind.ENTITY, pos, 0, SceneReadFootprint.Value.identity(null));
        return null;
    }
    @Override public ModelData getModelData(BlockPos pos) {
        getBlockState(pos);
        record(SceneReadFootprint.Kind.MODEL_DATA, pos, 0, SceneReadFootprint.Value.identity(ModelData.EMPTY));
        return ModelData.EMPTY;
    }
    @Override public AuxiliaryLightManager getAuxLightManager(ChunkPos pos) {
        throw unsupported("scene has no auxiliary chunk light manager");
    }
    @Override public int getHeight() {
        int result = envelope.height();
        record(SceneReadFootprint.Kind.HEIGHT, null, 0, SceneReadFootprint.Value.scalar(result));
        return result;
    }
    @Override public int getMinBuildHeight() {
        int result = envelope.minY();
        record(SceneReadFootprint.Kind.MIN_HEIGHT, null, 0, SceneReadFootprint.Value.scalar(result));
        return result;
    }
    @Override public int getMaxLightLevel() {
        record(SceneReadFootprint.Kind.MAX_LIGHT, null, 0, SceneReadFootprint.Value.scalar(15));
        return 15;
    }
    @Override public float getShade(Direction direction, boolean shade) {
        if (direction == null) throw unsupported("null scene shade direction");
        float result = !shade || directionalShadingDisabled ? 1 : switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
        record(SceneReadFootprint.Kind.SHADE, null, direction.ordinal() * 2 + (shade ? 1 : 0),
                SceneReadFootprint.Value.scalar(Float.floatToRawIntBits(result)));
        return result;
    }
    @Override public float getShade(float x, float y, float z, boolean shade) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) throw unsupported("non-finite scene shade normal");
        float result = getShade(Direction.getNearest(x, y, z), shade);
        if (footprint != null) footprint.record(new SceneReadFootprint.Query(SceneReadFootprint.Kind.NORMAL_SHADE,
                Float.floatToRawIntBits(x), Float.floatToRawIntBits(y), Float.floatToRawIntBits(z), shade ? 1 : 0),
                SceneReadFootprint.Value.scalar(Float.floatToRawIntBits(result)));
        return result;
    }
    @Override public LevelLightEngine getLightEngine() {
        throw unsupported("scene uses explicit fixture illumination; no propagated native lighting engine");
    }
    @Override public int getBrightness(LightLayer layer, BlockPos pos) {
        if (layer == null) throw unsupported("null scene light layer");
        getBlockState(pos);
        int result = lightingPolicy == LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE && layer == LightLayer.BLOCK ? 0 : 15;
        record(SceneReadFootprint.Kind.LIGHT, pos, layer.ordinal(), SceneReadFootprint.Value.scalar(result));
        return result;
    }
    @Override public int getRawBrightness(BlockPos pos, int ambient) {
        getBlockState(pos);
        int result = 15 - Math.min(15, Math.max(0, ambient));
        record(SceneReadFootprint.Kind.RAW_LIGHT, pos, ambient, SceneReadFootprint.Value.scalar(result));
        return result;
    }
    @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        if (resolver == null) throw unsupported("null scene color resolver");
        getBlockState(pos);
        // The facade deliberately never invokes a resolver: all non-null resolver inputs are equivalent.
        // Do not retain arbitrary callback object graphs merely to replay this fixed cosmetic constant.
        record(SceneReadFootprint.Kind.TINT, pos, 0, SceneReadFootprint.Value.scalar(COSMETIC_GRASS_TINT));
        return COSMETIC_GRASS_TINT;
    }
    /** Replay only this sealed facade's getter contract; never invoke native models or supplied callbacks. */
    SceneReadFootprint.Value replay(SceneReadFootprint.Query query) {
        var pos = query.kind().positional ? new BlockPos(query.x(), query.y(), query.z()) : null;
        return switch (query.kind()) {
            case STATE -> SceneReadFootprint.Value.identity(getBlockState(pos));
            case FLUID -> SceneReadFootprint.Value.identity(getFluidState(pos));
            case ENTITY -> SceneReadFootprint.Value.identity(getBlockEntity(pos));
            case MODEL_DATA -> SceneReadFootprint.Value.identity(getModelData(pos));
            case LIGHT -> SceneReadFootprint.Value.scalar(getBrightness(LightLayer.values()[query.argument()], pos));
            case RAW_LIGHT -> SceneReadFootprint.Value.scalar(getRawBrightness(pos, query.argument()));
            case TINT -> SceneReadFootprint.Value.scalar(getBlockTint(pos, FIXED_TINT_INPUT));
            case SHADE -> SceneReadFootprint.Value.scalar(Float.floatToRawIntBits(
                    getShade(Direction.values()[query.argument() / 2], (query.argument() & 1) != 0)));
            case NORMAL_SHADE -> SceneReadFootprint.Value.scalar(Float.floatToRawIntBits(
                    getShade(Float.intBitsToFloat(query.x()), Float.intBitsToFloat(query.y()), Float.intBitsToFloat(query.z()), query.argument() != 0)));
            case HEIGHT -> SceneReadFootprint.Value.scalar(getHeight());
            case MIN_HEIGHT -> SceneReadFootprint.Value.scalar(getMinBuildHeight());
            case MAX_LIGHT -> SceneReadFootprint.Value.scalar(getMaxLightLevel());
        };
    }
    private static final ColorResolver FIXED_TINT_INPUT = (biome, x, z) -> COSMETIC_GRASS_TINT;
}
