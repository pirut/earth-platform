package io.earthplatform.minecraft.fixture;

import com.mojang.serialization.MapCodec;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Constructor metadata only. Any attempt to generate a vanilla column is a fixture failure. */
public final class SparseFixtureGenerator extends ChunkGenerator {
    public SparseFixtureGenerator(Holder<Biome> biome) { super(new FixedBiomeSource(biome)); }
    private static SparseFacadeException unsupported() { return new SparseFacadeException("vanilla column generation is forbidden in the native sparse fixture"); }
    @Override protected MapCodec<? extends ChunkGenerator> codec() { throw unsupported(); }
    @Override public void applyCarvers(WorldGenRegion a,long b,RandomState c,BiomeManager d,StructureManager e,ChunkAccess f,GenerationStep.Carving g) { throw unsupported(); }
    @Override public void buildSurface(WorldGenRegion a,StructureManager b,RandomState c,ChunkAccess d) { throw unsupported(); }
    @Override public void spawnOriginalMobs(WorldGenRegion a) { throw unsupported(); }
    @Override public int getGenDepth() { return SparseFixtureDimensions.HEIGHT; }
    @Override public int getMinY() { return SparseFixtureDimensions.MIN_Y; }
    @Override public int getSeaLevel() { return 0; }
    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender a,RandomState b,StructureManager c,ChunkAccess d) { throw unsupported(); }
    @Override public int getBaseHeight(int x,int z,Heightmap.Types type,LevelHeightAccessor level,RandomState random) { throw unsupported(); }
    @Override public NoiseColumn getBaseColumn(int x,int z,LevelHeightAccessor level,RandomState random) { throw unsupported(); }
    @Override public void addDebugScreenInfo(List<String> text,RandomState random,BlockPos position) { text.add("Ephemeral native sparse fixture: no column generation or gameplay"); }
}
