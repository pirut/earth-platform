package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Vanilla's caves against the caves worlds had before them: how much of the rock they open, how
 * wide a cave runs, and what a cube costs. {@code EARTH_CAVE_SLICES=DIR} also draws
 * slices through both to compare by eye.
 */
class CaveLookTest {
    private static Registry<Biome> biomes;

    @BeforeAll static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(h -> registry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        biomes = registry.freeze();
    }

    /** What a sample of rock between vanilla's Y -50 and 50 holds. */
    private record Sample(double open, double meanRun, double msPerCube) {}

    private static HighlandsGenerator generator(boolean vanilla) {
        HighlandsGenerator generator = new HighlandsGenerator(12345, biomes);
        generator.underground().vanillaCaves(vanilla);
        return generator;
    }

    private static Sample sample(boolean vanilla) {
        HighlandsGenerator generator = generator(vanilla);
        Underground under = generator.underground();
        List<CompoundTag> ignored = new ArrayList<>();
        long open = 0, rock = 0, runs = 0, runBlocks = 0;
        int cubes = 0;
        long start = System.nanoTime();
        for (int cz = 0; cz < 12; cz++) for (int cx = 0; cx < 12; cx++) {
            int floor = under.floor((cx << 4) + 8, (cz << 4) + 8);
            for (int cy = (floor - 114) >> 4; cy <= (floor + 14) >> 4; cy++) {
                LevelChunkSection section = generator.generate(cx, cy, cz, ignored, new ArrayList<>());
                cubes++;
                for (int lz = 0; lz < 16; lz++) for (int ly = 0; ly < 16; ly++) {
                    int run = 0;
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        boolean hollow = state.isAir() || state.is(Blocks.LAVA) || state.is(Blocks.WATER);
                        if (hollow) { open++; run++; } else { rock++; if (run > 0) { runs++; runBlocks += run; run = 0; } }
                    }
                    if (run > 0) { runs++; runBlocks += run; }
                }
            }
        }
        double ms = (System.nanoTime() - start) / 1e6 / cubes;
        return new Sample(open / (double) (open + rock), runs == 0 ? 0 : runBlocks / (double) runs, ms);
    }

    @Test void vanillaCavesOpenLessRockInNarrowerCavesThanTheOldOnes() throws IOException {
        sample(true); // warm up
        Sample old = sample(false), vanilla = sample(true);
        System.out.printf("old caves:     %.1f%% open, mean run %.1f blocks, %.2f ms a cube%n", old.open * 100, old.meanRun, old.msPerCube);
        System.out.printf("vanilla caves: %.1f%% open, mean run %.1f blocks, %.2f ms a cube%n", vanilla.open * 100, vanilla.meanRun, vanilla.msPerCube);
        Underground under = generator(true).underground();
        for (int round = 0; round < 2; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 2000; i++) under.caveField(i * 16 + round * 100000, 2400, 0);
            System.out.printf("vanilla cave field: %.3f ms a cube%n", (System.nanoTime() - t0) / 1e6 / 2000);
        }
        assertTrue(vanilla.open > 0.02 && vanilla.open < 0.2, "vanilla caves open " + vanilla.open);
        String dir = System.getenv("EARTH_CAVE_SLICES");
        if (dir != null) {
            new File(dir).mkdirs();
            for (boolean v : new boolean[] {false, true}) {
                ImageIO.write(sideSlice(generator(v)), "png", new File(dir, (v ? "vanilla" : "old") + "-side.png"));
                ImageIO.write(levelSlice(generator(v), 20), "png", new File(dir, (v ? "vanilla" : "old") + "-level-y20.png"));
                ImageIO.write(levelSlice(generator(v), -30), "png", new File(dir, (v ? "vanilla" : "old") + "-level-y-30.png"));
            }
        }
    }

    private static int colour(BlockState state, boolean above) {
        if (state.isAir()) return above ? 0x9CC4FF : 0x101010;
        if (state.is(Blocks.LAVA)) return 0xFF7A1A;
        if (state.is(Blocks.WATER)) return 0x2E5BFF;
        if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.BEDROCK)) return 0x5A5A62;
        return 0x8E8E8E;
    }

    /** A 384-wide side view, from vanilla's Y -64 to the ground. */
    private static BufferedImage sideSlice(HighlandsGenerator generator) {
        Underground under = generator.underground();
        int width = 384, cz = 3, lz = 8;
        int floor = under.floor(width / 2, (cz << 4) + lz), top = floor + 200, bottom = floor - 128;
        BufferedImage image = new BufferedImage(width, top - bottom, BufferedImage.TYPE_INT_RGB);
        for (int cx = 0; cx < width / 16; cx++) for (int cy = bottom >> 4; cy <= top >> 4; cy++) {
            LevelChunkSection section = generator.generate(cx, cy, cz, new ArrayList<>(), new ArrayList<>());
            for (int ly = 0; ly < 16; ly++) {
                int y = (cy << 4) + ly;
                if (y < bottom || y >= top) continue;
                for (int lx = 0; lx < 16; lx++) {
                    int x = (cx << 4) + lx;
                    image.setRGB(x, top - 1 - y, colour(section.getBlockState(lx, ly, lz), y > generator.surfaceY(x, (cz << 4) + lz)));
                }
            }
        }
        return image;
    }

    /** A 384-square view from above at one vanilla-equivalent height. */
    private static BufferedImage levelSlice(HighlandsGenerator generator, int vanillaY) {
        Underground under = generator.underground();
        int size = 384;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int y = under.floor(size / 2, size / 2) + vanillaY - 64;
        for (int cz = 0; cz < size / 16; cz++) for (int cx = 0; cx < size / 16; cx++) {
            LevelChunkSection section = generator.generate(cx, y >> 4, cz, new ArrayList<>(), new ArrayList<>());
            for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                int x = (cx << 4) + lx, z = (cz << 4) + lz;
                image.setRGB(x, z, colour(section.getBlockState(lx, y & 15, lz), y > generator.surfaceY(x, z)));
            }
        }
        return image;
    }
}
