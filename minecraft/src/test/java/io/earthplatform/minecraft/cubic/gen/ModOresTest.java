package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * An ore feature added to one biome, the way a biome modifier adds Create's zinc: it shows up under
 * that biome, under the sea as well as on land, and nowhere in the air, the water, or a biome the
 * modifier left alone.
 */
class ModOresTest {
    private static Registry<Biome> biomes;
    private static final BlockState EMERALD = Blocks.EMERALD_BLOCK.defaultBlockState();

    @BeforeAll static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        var vanilla = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        PlacedFeature ore = ore();
        vanilla.listElements().forEach(holder -> {
            Biome biome = holder.is(Biomes.PLAINS) ? withOre(holder.value(), ore) : holder.value();
            registry.register(holder.key(), biome, RegistrationInfo.BUILT_IN);
        });
        biomes = registry.freeze();
    }

    /** A fat vein so a few chunks are enough to see it, only where the biome lists this feature. */
    private static PlacedFeature ore() {
        List<OreConfiguration.TargetBlockState> targets = new ArrayList<>();
        for (Block block : List.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF))
            targets.add(OreConfiguration.target(new BlockMatchTest(block), EMERALD));
        ConfiguredFeature<?, ?> configured = new ConfiguredFeature<>(Feature.ORE, new OreConfiguration(targets, 20));
        return new PlacedFeature(Holder.direct(configured), List.of(
                CountPlacement.of(24),
                InSquarePlacement.spread(),
                HeightRangePlacement.uniform(VerticalAnchor.absolute(-40), VerticalAnchor.absolute(40)),
                BiomeFilter.biome()));
    }

    /** Plains, plus this one feature at the ore step. Every other biome is left as vanilla wrote it. */
    private static Biome withOre(Biome source, PlacedFeature ore) {
        BiomeGenerationSettings.PlainBuilder settings = new BiomeGenerationSettings.PlainBuilder();
        var steps = source.getGenerationSettings().features();
        for (int i = 0; i < steps.size(); i++)
            for (Holder<PlacedFeature> feature : steps.get(i))
                settings.addFeature(i, feature);
        settings.addFeature(GenerationStep.Decoration.UNDERGROUND_ORES, Holder.direct(ore));
        Biome.ClimateSettings climate = source.getModifiedClimateSettings();
        return new Biome.BiomeBuilder()
                .hasPrecipitation(climate.hasPrecipitation())
                .temperature(climate.temperature())
                .downfall(climate.downfall())
                .temperatureAdjustment(climate.temperatureModifier())
                .specialEffects(source.getSpecialEffects())
                .mobSpawnSettings(source.getMobSettings())
                .generationSettings(settings.build())
                .build();
    }

    @Test void modOreBuriesItselfUnderTheBiomeItWasAddedToAndNotInTheSeaOrTheAir() {
        Flat plains = new Flat(3, biomes, biomes.getHolderOrThrow(Biomes.PLAINS), 80, 64);
        Flat sea = new Flat(3, biomes, biomes.getHolderOrThrow(Biomes.PLAINS), 40, 64);
        Flat desert = new Flat(3, biomes, biomes.getHolderOrThrow(Biomes.DESERT), 80, 64);
        Count land = count(plains, 0, 0, 3, 3);
        Count ocean = count(sea, 0, 0, 3, 3);
        Count dry = count(desert, 0, 0, 3, 3);
        assertTrue(land.ore > 40, "plains " + land);
        assertTrue(land.span > 24, "veins spread through the stone, not one squashed layer: " + land);
        assertEquals(0, land.exposed, "above ground or in air " + land);
        assertTrue(ocean.ore > 20, "under the seabed " + ocean);
        assertEquals(0, ocean.exposed, "in the water or on the seabed " + ocean);
        assertEquals(0, dry.ore, "desert was not given this ore");
        // The same cubes, asked for again in another order, keep the same ore.
        assertEquals(land.ore, count(new Flat(3, biomes, biomes.getHolderOrThrow(Biomes.PLAINS), 80, 64), 3, 3, 0, 0).ore);
    }

    private static Count count(Flat world, int x0, int z0, int x1, int z1) {
        int ore = 0, exposed = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int cx0 = Math.min(x0, x1), cx1 = Math.max(x0, x1), cz0 = Math.min(z0, z1), cz1 = Math.max(z0, z1);
        for (int cz = cz0; cz <= cz1; cz++) for (int cx = cx0; cx <= cx1; cx++) {
            int floor = world.underground().floor((cx << 4) + 8, (cz << 4) + 8);
            int ground = world.surfaceY((cx << 4) + 8, (cz << 4) + 8);
            for (int cy = (floor - 80) >> 4; cy <= (ground >> 4) + 2; cy++) {
                LevelChunkSection section = world.generate(cx, cy, cz);
                int y0 = cy << 4;
                for (int i = 0; i < 4096; i++) {
                    if (section.getBlockState(i & 15, i >> 8, i >> 4 & 15).getBlock() != Blocks.EMERALD_BLOCK) continue;
                    int x = (cx << 4) + (i & 15), y = y0 + (i >> 8), z = (cz << 4) + (i >> 4 & 15);
                    int top = world.surfaceY(x, z);
                    ore++;
                    min = Math.min(min, y);
                    max = Math.max(max, y);
                    if (y >= top || y > world.waterY(x, z) && world.waterY(x, z) != SurfaceGenerator.DRY) exposed++;
                    if (section.getBlockState(i & 15, i >> 8, i >> 4 & 15).getFluidState().isSource()) exposed++;
                }
            }
        }
        return new Count(ore, exposed, max == Integer.MIN_VALUE ? 0 : max - min);
    }

    private record Count(int ore, int exposed, int span) {}

    /** One biome, one height, so a test can say which biome an ore was allowed. */
    private static final class Flat extends SurfaceGenerator {
        private final Holder<Biome> biome;
        private final int ground, sea;

        Flat(long seed, Registry<Biome> biomes, Holder<Biome> biome, int ground, int sea) {
            super(seed, biomes, RandomSource.create(seed));
            this.biome = biome;
            this.ground = ground;
            this.sea = sea;
            legacyCaves();
        }

        @Override public String id() { return "flat-ore-test"; }
        @Override public int seaLevel() { return sea; }
        @Override protected int shoreLevel() { return sea; }
        @Override protected boolean emeralds(int maxGround) { return false; }
        @Override protected void ground(int x, int z, int[] out) {
            out[0] = ground;
            out[1] = ground < sea ? sea : DRY;
        }
        @Override protected Surface surface(int x, int z, int y, int water, int slope) {
            BlockState top = y < sea ? SAND : GRASS;
            BlockState under = y < sea ? SAND : DIRT;
            return new Surface(biome, top, under, 0, 0, 0, 0, 0, false);
        }
    }
}
