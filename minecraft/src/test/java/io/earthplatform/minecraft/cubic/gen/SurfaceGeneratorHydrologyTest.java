package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies HYDRO-03 ({@link SurfaceGenerator} {@code Column.maxWater} 4-cardinal 1-block border scan
 * for chunk-boundary waterfalls, excluding diagonal corners) and HYDRO-10 (coarse far-LOD {@code pours}
 * suppression on dry cells when {@code v > 8}).
 */
class SurfaceGeneratorHydrologyTest {
    private static BlockState fallingWater;

    @BeforeAll
    static void bootMinecraftRegistries() {
        SurfacePacketFixtures.biomes();
        fallingWater = Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock();
    }

    private static SurfaceGenerator waterfallScene(BorderKind border) {
        return new SurfaceGenerator(42L, SurfacePacketFixtures.biomes(), RandomSource.create(42L)) {
            @Override protected void ground(int x, int z, int[] out) {
                boolean brink = switch (border) {
                    case WEST -> x == -1 && z >= 0 && z < 16;
                    case EAST -> x == 16 && z >= 0 && z < 16;
                    case NORTH -> z == -1 && x >= 0 && x < 16;
                    case SOUTH -> z == 16 && x >= 0 && x < 16;
                    case DIAGONALS_ONLY -> (x == -1 || x == 16) && (z == -1 || z == 16);
                };
                if (brink) {
                    out[0] = 120;
                    out[1] = 120;
                } else {
                    out[0] = 64;
                    out[1] = DRY;
                }
            }

            @Override protected Surface surface(int x, int z, int h, int w, int slope) {
                return new Surface(holder(SurfacePacketFixtures.biomes(), Biomes.PLAINS), GRASS, DIRT, 0, 0, 0, 0, 0, false);
            }

            @Override public String id() { return "test_waterfall_" + border.name(); }
            @Override public int seaLevel() { return 62; }
            @Override protected int shoreLevel() { return 62; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };
    }

    private enum BorderKind { WEST, EAST, NORTH, SOUTH, DIAGONALS_ONLY }

    @Test
    void cardinalBorderWaterfallsGenerateContinuousFallingWaterInSkyCubes() {
        for (BorderKind border : new BorderKind[] {BorderKind.WEST, BorderKind.EAST, BorderKind.NORTH, BorderKind.SOUTH}) {
            SurfaceGenerator gen = waterfallScene(border);
            assertEquals(120, gen.highestSurface(0, 0), "highestSurface must include cardinal border water for " + border);

            int expectedLx = border == BorderKind.WEST ? 0 : border == BorderKind.EAST ? 15 : 8;
            int expectedLz = border == BorderKind.NORTH ? 0 : border == BorderKind.SOUTH ? 15 : 8;

            // Sky cubes cy = 5 (y=80..95), cy = 6 (y=96..111), cy = 7 (y=112..127) must all contain FALLING_WATER up to y=120!
            for (int cy = 5; cy <= 7; cy++) {
                LevelChunkSection section = gen.generate(0, cy, 0);
                assertNotNull(section, "sky cube cy=" + cy + " must not be null for " + border);
                int y0 = cy << 4;
                for (int ly = 0; ly < 16; ly++) {
                    int y = y0 + ly;
                    BlockState state = section.getBlockState(expectedLx, ly, expectedLz);
                    if (y <= 120) {
                        assertEquals(fallingWater, state,
                                "expected continuous FALLING_WATER at y=" + y + " for " + border + " border");
                    } else {
                        assertTrue(state.isAir(), "expected air above y=120 at y=" + y + " for " + border);
                    }
                }
            }
        }
    }

    @Test
    void diagonalOnlyBorderWaterDoesNotInflateMaxWaterOrGenerateFallingWater() {
        SurfaceGenerator gen = waterfallScene(BorderKind.DIAGONALS_ONLY);
        assertEquals(64, gen.highestSurface(0, 0), "diagonal-only water must NOT inflate highestSurface");
        LevelChunkSection sky = gen.generate(0, 6, 0);
        assertTrue(sky.hasOnlyAir(), "diagonal-only high water must leave sky cube empty air");
    }

    @Test
    void coarseFarLodSuppressesDryCliffWaterCurtainsWhileFineLodAndWetStepsKeepThem() {
        SurfaceGenerator cliff = new SurfaceGenerator(99L, SurfacePacketFixtures.biomes(), RandomSource.create(99L)) {
            @Override protected void ground(int x, int z, int[] out) {
                if (x < 0) {
                    // High water plateau at y = 128 on the west side.
                    out[0] = 120;
                    out[1] = 128;
                } else if (z >= 0) {
                    // Low DRY cliff floor on the east-south quadrant.
                    out[0] = 64;
                    out[1] = DRY;
                } else {
                    // Low WET plunge pool on the east-north quadrant (water step-down).
                    out[0] = 60;
                    out[1] = 64;
                }
            }

            @Override protected Surface surface(int x, int z, int h, int w, int slope) {
                return new Surface(holder(SurfacePacketFixtures.biomes(), Biomes.PLAINS), GRASS, DIRT, 0, 0, 0, 0, 0, false);
            }

            @Override public String id() { return "test_far_pours"; }
            @Override public int seaLevel() { return 62; }
            @Override protected int shoreLevel() { return 62; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };

        // Fine/mid LOD (level = 3, v = 8): dry cell (cx=0, cz=8, x=4, z=68) beside high water at x=-4 STILL pours!
        FarColumn v8 = new FarColumn();
        cliff.farColumn(3, 0, 0, v8, false);
        assertTrue(hasWaterAbove(v8, 3, 0, 8, 80), "v=8 should still pour water over cliff lip onto adjacent dry cell");

        // Coarse far LOD (level = 4, v = 16): dry cell (cx=0, cz=8, x=8, z=136) beside high water at x=-8 suppresses pours!
        FarColumn v16Dry = new FarColumn();
        cliff.farColumn(4, 0, 0, v16Dry, false);
        assertFalse(hasWaterAbove(v16Dry, 4, 0, 8, 80), "v=16 must suppress oversized water curtain on dry cliff cell");

        // Coarse far LOD (level = 4, v = 16): wet cell (cx=0, cz=8 in columnZ=-1, z<0) still bridges water-to-water step!
        FarColumn v16Wet = new FarColumn();
        cliff.farColumn(4, 0, -1, v16Wet, false);
        assertTrue(hasWaterAbove(v16Wet, 4, 0, 8, 80), "v=16 should still bridge water-to-water step-down on wet cells");
    }

    private static boolean hasWaterAbove(FarColumn column, int level, int cx, int cz, int minY) {
        int v = 1 << level, width = 16 * v;
        for (int si = 0; si < column.sections.size(); si++) {
            LevelChunkSection section = column.sections.get(si);
            if (section == null) continue;
            int sectionY = (column.bottom + si) * width;
            for (int vy = 0; vy < 16; vy++) {
                int y = sectionY + vy * v;
                if (y < minY) continue;
                if (section.getBlockState(cx, vy, cz).is(Blocks.WATER)) return true;
            }
        }
        return false;
    }
}
