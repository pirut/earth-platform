package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Caves, layers, veins and structures under a generated world: deterministic, and at vanilla's rates. */
class UndergroundTest {
    private static Registry<Biome> biomes;

    @BeforeAll static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(h -> registry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        biomes = registry.freeze();
    }

    private static int[] states(LevelChunkSection section) {
        int[] ids = new int[4096];
        for (int i = 0; i < 4096; i++) ids[i] = Block.getId(section.getBlockState(i & 15, i >> 8, i >> 4 & 15));
        return ids;
    }

    @Test void cubesAreTheSameWhicheverGeneratorAndOrderMakesThem() {
        HighlandsGenerator a = new HighlandsGenerator(42, biomes), b = new HighlandsGenerator(42, biomes);
        int top = a.surfaceY(8, 8) >> 4;
        List<int[]> first = new ArrayList<>();
        for (int cy = top - 12; cy <= top; cy++) first.add(states(a.generate(3, cy, -2)));
        for (int cy = top; cy >= top - 12; cy--) {
            // b sees its neighbours first, so any cached plan must not depend on order.
            b.generate(4, cy, -2); b.generate(3, cy + 1, -1);
            assertArrayEquals(first.get(cy - (top - 12)), states(b.generate(3, cy, -2)), "cube y " + cy);
        }
    }

    @Test void undergroundHasVanillaLayersOresAndStructures() {
        HighlandsGenerator generator = new HighlandsGenerator(7, biomes);
        Map<String, Integer> counts = new TreeMap<>();
        List<CompoundTag> blockEntities = new ArrayList<>(), entities = new ArrayList<>();
        int cubes = 0;
        long start = System.nanoTime();
        for (int cz = 0; cz < 16; cz++) for (int cx = 0; cx < 16; cx++) {
            int ground = generator.surfaceY((cx << 4) + 8, (cz << 4) + 8);
            Underground under = generator.underground();
            int floor = under.floor((cx << 4) + 8, (cz << 4) + 8);
            // From vanilla's bottom up to the ground.
            for (int cy = (floor - 140) >> 4; cy <= ground >> 4; cy++) {
                int before = blockEntities.size();
                LevelChunkSection section = generator.generate(cx, cy, cz, blockEntities, entities);
                cubes++;
                // Every block entity a cube starts with sits on a block that takes it.
                for (CompoundTag tag : blockEntities.subList(before, blockEntities.size())) {
                    var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(net.minecraft.resources.ResourceLocation.parse(tag.getString("id")));
                    assertTrue(type != null && type.isValid(section.getBlockState(tag.getInt("x") & 15, tag.getInt("y") & 15, tag.getInt("z") & 15)), "block under " + tag);
                }
                for (int i = 0; i < 4096; i++) {
                    Block block = section.getBlockState(i & 15, i >> 8, i >> 4 & 15).getBlock();
                    if (block == Blocks.STONE || block == Blocks.AIR || block == Blocks.DEEPSLATE) continue;
                    counts.merge(BuiltInRegistries.BLOCK.getKey(block).getPath(), 1, Integer::sum);
                }
            }
        }
        double ms = (System.nanoTime() - start) / 1e6 / cubes;
        System.out.println("cubes " + cubes + ", " + String.format("%.2f", ms) + " ms each");
        System.out.println(counts);
        System.out.println("block entities " + blockEntities.size() + ", entities " + entities.size());
        for (CompoundTag tag : blockEntities) System.out.println("  " + tag);
        for (String ore : List.of("coal_ore", "iron_ore", "copper_ore", "deepslate_diamond_ore", "deepslate_redstone_ore", "deepslate_gold_ore",
                "deepslate_lapis_ore", "granite", "diorite", "andesite", "tuff", "gravel", "lava", "glow_lichen",
                "sculk", "sculk_sensor", "sculk_shrieker", "moss_block", "pointed_dripstone", "bone_block"))
            assertTrue(counts.getOrDefault(ore, 0) > 0, ore);
        assertTrue(counts.getOrDefault("spawner", 0) > 0, "spawners");
        assertTrue(counts.getOrDefault("budding_amethyst", 0) > 0, "geodes");
        assertEquals(counts.getOrDefault("spawner", 0), (int) blockEntities.stream().filter(t -> t.getString("id").endsWith("mob_spawner")).count());
        assertTrue(ms < 25, "generation time per cube " + ms);
    }
}
