package io.earthplatform.minecraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.server.level.WorldGenRegion;

/** Materializes independent canonical 16³ cubes into a real, bounded Minecraft chunk. */
public final class EarthWindowGenerator extends ChunkGenerator {
    public static final MapCodec<EarthWindowGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(EarthWindowGenerator::getBiomeSource),
            Codec.INT.fieldOf("earth_origin_y").forGetter(EarthWindowGenerator::originY),
            Codec.INT.fieldOf("calibration_surface_y").forGetter(EarthWindowGenerator::surfaceY),
            Codec.BOOL.optionalFieldOf("real_terrain", false).forGetter(EarthWindowGenerator::realTerrain),
            Codec.STRING.optionalFieldOf("terrain_dataset", ProofTerrain.NOAA).forGetter(EarthWindowGenerator::terrainDataset),
            Codec.STRING.optionalFieldOf("horizontal_projection", ProofTerrain.MANIFEST_PROJECTION).forGetter(EarthWindowGenerator::horizontalProjection)
    ).apply(instance, EarthWindowGenerator::new));
    private final VerticalWindow window;
    private final int surfaceY;
    private final CalibrationCubes cubes;
    private final boolean realTerrain;
    private final String terrainDataset;
    private final String horizontalProjection;
    private final WindowTerrain terrain;

    public EarthWindowGenerator(BiomeSource biomeSource, int originY, int surfaceY) {
        this(biomeSource, originY, surfaceY, false);
    }
    public EarthWindowGenerator(BiomeSource biomeSource, int originY, int surfaceY, boolean realTerrain) {
        this(biomeSource, originY, surfaceY, realTerrain, ProofTerrain.NOAA, ProofTerrain.MANIFEST_PROJECTION);
    }
    public EarthWindowGenerator(BiomeSource biomeSource, int originY, int surfaceY, boolean realTerrain,
            String terrainDataset, String horizontalProjection) {
        super(biomeSource);
        ProofTerrain.validateIdentifiers(terrainDataset, horizontalProjection);
        this.window = new VerticalWindow(originY, 256);
        window.toEngineY(surfaceY);
        this.surfaceY = surfaceY;
        this.cubes = new CalibrationCubes(surfaceY);
        this.realTerrain = realTerrain;
        this.terrainDataset = terrainDataset;
        this.horizontalProjection = horizontalProjection;
        this.terrain = realTerrain ? new WindowTerrain(terrainDataset, horizontalProjection) : null;
    }

    public int originY() { return window.originY(); }
    public int surfaceY() { return surfaceY; }
    public boolean realTerrain() { return realTerrain; }
    public String terrainDataset() { return terrainDataset; }
    public String horizontalProjection() { return horizontalProjection; }
    public WindowTerrain terrain() {
        if (!realTerrain) throw new IllegalStateException("Synthetic calibration has no real terrain source");
        return terrain;
    }
    public long earthSurfaceY(long x, long z) { return realTerrain ? terrain.surfaceBlockY(x, z) : surfaceY; }
    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }
    @Override public int getGenDepth() { return window.height(); }
    @Override public int getMinY() { return 0; }
    @Override public int getSeaLevel() { return Math.max(0, Math.min(window.height(), -originY())); }

    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int chunkX = chunk.getPos().x, chunkZ = chunk.getPos().z;
        // One extra sample per column for material selection; never use the window's nominal
        // calibration height to infer the actual surface of a real-data column.
        long[] ground = new long[256];
        if (realTerrain) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            ground[z * 16 + x] = earthSurfaceY(chunkX * 16L + x, chunkZ * 16L + z);
        }
        var surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        var ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        for (int section = 0; section < window.cubeCount(); section++) {
            CubePos canonical = new CubePos(chunkX, window.minCubeY() + section, chunkZ);
            ImmutableCube cube = realTerrain ? terrain.cube(canonical) : cubes.generate(canonical);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int stateId = cube.get(x, y, z);
                if (stateId == CalibrationCubes.AIR) continue;
                int engineY = section * 16 + y;
                BlockState state = mapState(stateId, ground[z * 16 + x]);
                position.set(chunkX * 16 + x, engineY, chunkZ * 16 + z);
                chunk.setBlockState(position, state, false);
                surface.update(x, engineY, z, state);
                ocean.update(x, engineY, z, state);
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    private BlockState mapState(int id, long groundY) {
        if (realTerrain) return HeuristicSurface.map(id, groundY);
        return switch (id) {
            case CalibrationCubes.AIR -> Blocks.AIR.defaultBlockState();
            case CalibrationCubes.STONE -> Blocks.STONE.defaultBlockState();
            case CalibrationCubes.SURFACE -> Blocks.GRASS_BLOCK.defaultBlockState();
            default -> throw new IllegalArgumentException("Unmapped material: " + id);
        };
    }

    @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        long ground = earthSurfaceY(x, z);
        long top = realTerrain && type != Heightmap.Types.OCEAN_FLOOR && type != Heightmap.Types.OCEAN_FLOOR_WG ? Math.max(ground, -1) : ground;
        return (int) Math.max(0, Math.min(window.height(), top - originY() + 1));
    }
    @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        BlockState[] states = new BlockState[window.height()];
        if (realTerrain) {
            long ground = earthSurfaceY(x, z);
            for (int section = 0; section < window.cubeCount(); section++) {
                CubePos pos = CubePos.fromBlock(x, originY() + section * 16L, z);
                ImmutableCube cube = terrain.cube(pos);
                for (int y = 0; y < 16; y++) states[section * 16 + y] = mapState(cube.get(CubePos.local(x), y, CubePos.local(z)), ground);
            }
            return new NoiseColumn(0, states);
        }
        for (int y = 0; y < states.length; y++) {
            long canonical = window.toEarthY(y);
            states[y] = mapState(canonical > surfaceY ? CalibrationCubes.AIR : canonical == surfaceY ? CalibrationCubes.SURFACE : CalibrationCubes.STONE, surfaceY);
        }
        return new NoiseColumn(0, states);
    }
    @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {}
    @Override public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {}
    @Override public void spawnOriginalMobs(WorldGenRegion region) {}
    // Real windows are an elevation proof, not a vanilla world-generation canvas.
    // Features include trees, ores, lakes, snow layers and structures: all would add
    // invented detail or change the sampled shape. Synthetic calibration keeps its prior behavior.
    @Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
        if (!realTerrain) super.applyBiomeDecoration(level, chunk, structures);
    }
    @Override public void createStructures(RegistryAccess registries, ChunkGeneratorStructureState state,
            StructureManager structures, ChunkAccess chunk, StructureTemplateManager templates) {
        if (!realTerrain) super.createStructures(registries, state, structures, chunk, templates);
    }
    @Override public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) {
        if (!realTerrain) super.createReferences(level, structures, chunk);
    }
    @Override public void addDebugScreenInfo(List<String> text, RandomState random, BlockPos position) {
        // ChunkGenerator's diagnostic hook has no client font/viewport access. Keep each
        // row short enough for the narrow F3 left column instead of adding screen-wide rows.
        text.add("Earth Y " + ((long) originY() + position.getY()) + "m, local " + position.getY());
        text.add("256m window; origin " + originY());
        text.add(realTerrain ? ProofTerrain.debugLabel(terrainDataset) : "Synthetic calibration");
        if (realTerrain) {
            text.add("Heuristic, not land cover");
            text.add("Biome placeholder; no decor");
        }
        text.add("Cubic engine NOT implemented");
    }
}
