package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.minecraft.cubic.gen.HighlandsGenerator;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The fast light (cubes.fastLight) gives every block of the neighborhood the same sky and block light as going over every block. */
class CubeLightTest {
    private static Registry<Biome> biomes;

    @BeforeAll static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(h -> registry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        biomes = registry.freeze();
    }

    private static void assertSameLight(PalettedContainer<BlockState>[] states, int[][] tops, int cy, String where) {
        CubeLight.Field slow = CubeLight.light(states, tops, cy, false);
        byte[] sky = slow.sky.clone(), block = slow.block.clone();
        BlockState[] filled = slow.states.clone();
        CubeLight.Field fast = CubeLight.light(states, tops, cy, true);
        assertArrayEquals(filled, fast.states, where);
        assertArrayEquals(sky, fast.sky, where);
        assertArrayEquals(block, fast.block, where);
    }

    @SuppressWarnings("unchecked")
    @Test void generatedGroundIsLitTheSame() {
        HighlandsGenerator generator = new HighlandsGenerator(11, biomes);
        java.util.Map<Long, PalettedContainer<BlockState>> made = new java.util.HashMap<>();
        int lit = 0;
        for (int[] at : new int[][] {{0, 0}, {37, -12}, {-140, 66}, {512, 512}}) {
            int cx = at[0], cz = at[1];
            int[][] tops = new int[9][];
            int highest = Integer.MIN_VALUE, lowest = Integer.MAX_VALUE;
            for (int c = 0; c < 9; c++) {
                int[] column = tops[c] = new int[256];
                for (int i = 0; i < 256; i++) {
                    int x = ((cx + c % 3 - 1) << 4) + (i & 15), z = ((cz + c / 3 - 1) << 4) + (i >> 4);
                    column[i] = generator.skyTopY(x, z);
                    highest = Math.max(highest, column[i]);
                    lowest = Math.min(lowest, generator.surfaceY(x, z));
                }
            }
            // From well under the ground, through it, to the open sky over it.
            for (int cy = (lowest >> 4) - 4; cy <= (highest >> 4) + 2; cy++) {
                PalettedContainer<BlockState>[] states = new PalettedContainer[27];
                for (int c = 0; c < 27; c++) {
                    int x = cx + c % 3 - 1, y = cy + c / 9 - 1, z = cz + c / 3 % 3 - 1;
                    states[c] = made.computeIfAbsent(CubeKey.pack(x, y, z), key -> generator.generate(x, y, z).getStates());
                }
                assertSameLight(states, tops, cy, "cube " + cx + "," + cy + "," + cz);
                lit++;
            }
        }
        assertTrue(lit > 20);
    }

    /**
     * Mixed neighborhoods: missing cubes and columns, cubes of one state, water, leaves and glass the light crosses,
     * light sources, overhangs, and tops anywhere in or out of the neighborhood.
     */
    @SuppressWarnings("unchecked")
    @Test void mixedNeighborhoodsAreLitTheSame() {
        BlockState[] palette = {Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.GLOWSTONE.defaultBlockState(),
                Blocks.TORCH.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.ICE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(), Blocks.COBWEB.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState()};
        Random random = new Random(5);
        for (int round = 0; round < 120; round++) {
            PalettedContainer<BlockState>[] states = new PalettedContainer[27];
            for (int c = 0; c < 27; c++) {
                int kind = random.nextInt(6);
                if (kind == 0) continue;
                PalettedContainer<BlockState> container = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, palette[0], PalettedContainer.Strategy.SECTION_STATES);
                states[c] = container;
                if (kind == 1) continue;
                BlockState only = palette[random.nextInt(palette.length)];
                // Mostly one state, with a few others scattered in; or solid below a height, open above.
                int floor = random.nextInt(17), sprinkle = kind == 2 ? 0 : kind == 3 ? 40 : 600;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState state = kind == 4 ? (y < floor ? palette[1] : palette[0]) : only;
                    if (sprinkle > 0 && random.nextInt(4096) < sprinkle) state = palette[random.nextInt(palette.length)];
                    container.getAndSetUnchecked(x, y, z, state);
                }
            }
            int[][] tops = new int[9][];
            int cy = random.nextInt(40) - 20, baseY = (cy - 1) << 4;
            for (int c = 0; c < 9; c++) {
                if (random.nextInt(8) == 0) continue;
                int[] column = tops[c] = new int[256];
                int level = baseY + random.nextInt(80) - 16;
                for (int i = 0; i < 256; i++) column[i] = random.nextInt(5) == 0 ? baseY + random.nextInt(64) - 8 : level + random.nextInt(3);
            }
            assertSameLight(states, tops, cy, "round " + round);
        }
    }
}
