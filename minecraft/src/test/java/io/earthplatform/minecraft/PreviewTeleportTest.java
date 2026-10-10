package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.MissingTerrainException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual vanilla Entity support-cache path that crashed native preview 002. */
class PreviewTeleportTest {
    private static RegistryAccess registries;
    private static Registry<Biome> biomes;
    private static Holder<DimensionType> dimensionType;
    private static EarthWindowGenerator generator;
    private static final BlockPos OLD_SPAWN_SUPPORT = new BlockPos(8, 68, -3);
    private static final Vec3 DESTINATION = new Vec3(9_674_928.5, 173, -3_112_479.5);

    @BeforeAll static void setup() {
        var lookup = VanillaRegistries.createLookup();
        biomes = registry(lookup, Registries.BIOME);
        registries = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes,
                registry(lookup, Registries.DAMAGE_TYPE))).freeze();
        dimensionType = lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        generator = new EarthWindowGenerator(new FixedBiomeSource(biomes.getHolderOrThrow(Biomes.PLAINS)),
                7328, 7499, true, ProofTerrain.COPERNICUS, ProofTerrain.GLOBAL_PROJECTION);
    }
    private static <T> Registry<T> registry(HolderLookup.Provider lookup, ResourceKey<? extends Registry<T>> key) {
        var result = new MappedRegistry<T>(key, Lifecycle.stable());
        lookup.lookupOrThrow(key).listElements().forEach(holder -> result.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        return result.freeze();
    }
    private static final class CoverageLevel extends FacadeTestLevel {
        final List<BlockPos> reads = new ArrayList<>();
        CoverageLevel() { super(registries, dimensionType, 0, 256); }
        @Override public BlockState getBlockState(BlockPos pos) {
            reads.add(pos.immutable());
            long ground = generator.earthSurfaceY(pos.getX(), pos.getZ());
            long earthY = (long) generator.originY() + pos.getY();
            return (earthY > ground ? Blocks.AIR : earthY == ground ? Blocks.SNOW_BLOCK : Blocks.STONE).defaultBlockState();
        }
    }
    private static final class ProbeEntity extends Entity {
        ProbeEntity(Level level) { super(EntityType.MARKER, level); }
        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(CompoundTag tag) {}
        @Override protected void addAdditionalSaveData(CompoundTag tag) {}
    }
    private static ProbeEntity staleArrival(CoverageLevel level) {
        var entity = new ProbeEntity(level);
        entity.setPos(DESTINATION);
        entity.mainSupportingBlockPos = Optional.of(OLD_SPAWN_SUPPORT);
        entity.fallDistance = 19;
        entity.setDeltaMovement(new Vec3(1, -2, 3));
        return entity;
    }

    @Test void unresetNativeEntityReproducesOldSpawnReadInTheNewDimension() {
        var level = new CoverageLevel();
        var entity = staleArrival(level);
        assertThrows(MissingTerrainException.class, entity::getBlockPosBelowThatAffectsMyMovement);
        assertEquals(List.of(OLD_SPAWN_SUPPORT), level.reads);
    }

    @Test void nativePhysicsResetPreventsOriginQueriesAndRetainsTheExactGlobalPosition() {
        var level = new CoverageLevel();
        var entity = staleArrival(level);
        PreviewTeleport.resetSourcePhysics(entity);
        assertTrue(entity.mainSupportingBlockPos.isEmpty());
        assertFalse(entity.onGround());
        assertEquals(0, entity.fallDistance);
        assertEquals(Vec3.ZERO, entity.getDeltaMovement());
        assertEquals(DESTINATION, entity.position());
        var support = entity.getBlockPosBelowThatAffectsMyMovement();
        assertEquals(new BlockPos(9_674_928, 172, -3_112_480), support);
        assertTrue(level.reads.isEmpty(), "Support lookup must not read the old spawn at all");
        assertTrue(level.getBlockState(new BlockPos(support.getX(), 171, support.getZ())).is(Blocks.SNOW_BLOCK));
        assertEquals(7499, generator.earthSurfaceY(support.getX(), support.getZ()));
    }

    @Test void repeatedTeleportsAlsoClearSupportFromAPreviousEarthWindow() {
        var level = new CoverageLevel();
        var entity = staleArrival(level);
        for (BlockPos previous : List.of(OLD_SPAWN_SUPPORT, BlockPos.ZERO, new BlockPos(-30, 20, -40))) {
            entity.mainSupportingBlockPos = Optional.of(previous);
            PreviewTeleport.resetSourcePhysics(entity);
            assertDoesNotThrow(entity::getBlockPosBelowThatAffectsMyMovement);
        }
        assertTrue(level.reads.isEmpty());
    }

    @Test void unexpectedOriginAndCrashChunkRequestsRemainExplicitFailuresWithoutAnyBlocks() {
        var height = LevelHeightAccessor.create(0, 256);
        for (ChunkPos pos : List.of(new ChunkPos(0, 0), new ChunkPos(-2, -3))) {
            var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, height, biomes, null);
            assertThrows(MissingTerrainException.class, () -> generator.fillFromNoise(null, null, null, chunk));
            assertThrows(MissingTerrainException.class, () -> generator.getBaseColumn(pos.getMinBlockX(), pos.getMinBlockZ(), height, null));
            assertThrows(MissingTerrainException.class, () -> generator.getBaseHeight(pos.getMinBlockX(), pos.getMinBlockZ(), Heightmap.Types.WORLD_SURFACE_WG, height, null));
            for (var section : chunk.getSections()) assertTrue(section.hasOnlyAir(), "No fallback or partial terrain may be emitted");
        }
    }
}
