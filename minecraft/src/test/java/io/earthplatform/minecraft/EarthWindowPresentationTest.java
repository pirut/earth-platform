package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.GeoPoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real Minecraft chunk objects with the verified NOAA pack; no live client/server or save mutation. */
class EarthWindowPresentationTest {
    private static Registry<Biome> biomes;
    @TempDir static Path verifiedPack;
    private static String previousManifest;

    @BeforeAll static void setup() throws Exception {
        // Tests may run with a separate working directory. Copy exact bundled bytes into
        // an owned temporary fixture, then let OfflineTerrain validate its checksums.
        copyResource("world.properties");
        var properties = new Properties();
        try (var input = Files.newInputStream(verifiedPack.resolve("world.properties"))) { properties.load(input); }
        for (int i = 0; i < Integer.parseInt(properties.getProperty("tile.count")); i++) {
            copyResource(properties.getProperty("tile." + i + ".path"));
        }
        previousManifest = System.getProperty("earthplatform.terrainManifest");
        System.setProperty("earthplatform.terrainManifest", verifiedPack.resolve("world.properties").toString());
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        biomes = registry.freeze();
    }

    private static void copyResource(String name) throws Exception {
        Path target = verifiedPack.resolve(name).normalize();
        assertTrue(target.startsWith(verifiedPack));
        Files.createDirectories(target.getParent());
        try (var input = ProofTerrain.class.getResourceAsStream("/earthplatform/proof_data/" + name)) {
            assertNotNull(input, name);
            Files.copy(input, target);
        }
    }

    @AfterAll static void restoreProperty() {
        if (previousManifest == null) System.clearProperty("earthplatform.terrainManifest");
        else System.setProperty("earthplatform.terrainManifest", previousManifest);
    }

    private static EarthWindowGenerator generator(int origin, int nominal, boolean real) {
        return new EarthWindowGenerator(new FixedBiomeSource(biomes.getHolderOrThrow(Biomes.PLAINS)), origin, nominal, real);
    }

    @Test void actualNoaaChunksKeepEverySampledSolidWaterAndAirBoundary() {
        verifyLandmark("everest", new GeoPoint(27.9881, 86.925), 8352, 8491);
        verifyLandmark("trench", new GeoPoint(11.3733, 142.5917), -10656, -10512);
        verifyLandmark("coast", new GeoPoint(37.7749, -122.4194), -64, 0);
    }

    private static void verifyLandmark(String name, GeoPoint landmark, int origin, int nominal) {
        var terrain = ProofTerrain.get();
        var projected = terrain.manifest().projection().toProjected(landmark);
        int blockX = (int) Math.floor(projected.x()), blockZ = (int) Math.floor(projected.z());
        var position = new ChunkPos(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
        var height = LevelHeightAccessor.create(0, 256);
        var chunk = new ProtoChunk(position, UpgradeData.EMPTY, height, biomes, null);
        var generator = generator(origin, nominal, true);
        assertSame(chunk, generator.fillFromNoise(null, null, null, chunk).join());
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int worldX = position.getMinBlockX() + x, worldZ = position.getMinBlockZ() + z;
            long ground = terrain.surfaceBlockY(worldX, worldZ);
            min = Math.min(min, ground); max = Math.max(max, ground);
            assertEquals(ground, generator.earthSurfaceY(worldX, worldZ));
            for (int y = 0; y < 256; y++) {
                long earthY = (long) origin + y;
                var actual = chunk.getBlockState(cell.set(worldX, y, worldZ));
                if (earthY > ground) assertTrue(earthY < 0 ? actual.is(Blocks.WATER) : actual.isAir());
                else {
                    assertFalse(actual.isAir());
                    assertTrue(actual.getFluidState().isEmpty());
                    if (ground >= 4800) assertTrue(actual.is(earthY == ground ? Blocks.SNOW_BLOCK : Blocks.STONE));
                    if (ground < -32) assertTrue(actual.is(earthY < ground - 3 ? Blocks.STONE : Blocks.GRAVEL));
                }
            }
            assertEquals(Math.max(0, Math.min(256, ground - origin + 1)),
                    generator.getBaseHeight(worldX, worldZ, Heightmap.Types.OCEAN_FLOOR_WG, height, null));
        }
        var column = generator.getBaseColumn(blockX, blockZ, height, null);
        for (int y = 0; y < 256; y++) assertEquals(chunk.getBlockState(cell.set(blockX, y, blockZ)), column.getBlock(y));
        System.out.println("REAL_PRESENTATION " + name + " groundRange=" + min + ".." + max
                + " landmarkGround=" + terrain.surfaceBlockY(blockX, blockZ) + " checkedVoxels=65536");
    }

    @Test void realDecorationAndStructuresAreNoOpsWhileSyntheticBehaviorIsDelegated() {
        var real = generator(8352, 8491, true);
        assertDoesNotThrow(() -> real.applyBiomeDecoration(null, null, null));
        assertDoesNotThrow(() -> real.createStructures(null, null, null, null, null));
        assertDoesNotThrow(() -> real.createReferences(null, null, null));
        var synthetic = generator(8704, 8849, false);
        assertThrows(NullPointerException.class, () -> synthetic.applyBiomeDecoration(null, null, null));
        assertThrows(NullPointerException.class, () -> synthetic.createStructures(null, null, null, null, null));
        assertThrows(NullPointerException.class, () -> synthetic.createReferences(null, null, null));
    }

    @Test void calibrationStillHasGrassAtItsExactSyntheticHeight() {
        var generator = generator(8704, 8849, false);
        var column = generator.getBaseColumn(0, 0, LevelHeightAccessor.create(0, 256), null);
        assertTrue(column.getBlock(144).is(Blocks.STONE));
        assertTrue(column.getBlock(145).is(Blocks.GRASS_BLOCK));
        assertTrue(column.getBlock(146).isAir());
    }

    @Test void missingRealCoverageStillFailsAndDebugOutputDisclosesHeuristic() {
        var generator = generator(8352, 8491, true);
        var outside = ProofTerrain.get().manifest().projection().toProjected(new GeoPoint(0, 0));
        assertThrows(RuntimeException.class,
                () -> generator.earthSurfaceY((long) Math.floor(outside.x()), (long) Math.floor(outside.z())));
        var lines = new ArrayList<String>();
        generator.addDebugScreenInfo(lines, null, new BlockPos(0, 141, 0));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Earth Y 8493m")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("not land cover")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("NOT implemented")));
    }

    @Test void f3DiagnosticsUseCompactRowsForTheNarrowLeftColumn() {
        for (int[] window : new int[][]{{8352, 8491}, {-10656, -10512}, {-64, 0}}) {
            var generator = generator(window[0], window[1], true);
            for (int y : new int[]{0, 140, 255}) {
                var lines = new ArrayList<String>();
                generator.addDebugScreenInfo(lines, null, new BlockPos(0, y, 0));
                assertEquals(6, lines.size());
                for (String line : lines) assertTrue(line.length() <= 28,
                        "Keep diagnostics within 168 pixels at a conservative six pixels per character: " + line);
                assertTrue(lines.stream().anyMatch(line -> line.contains("NOAA ETOPO 2022")));
                assertTrue(lines.stream().anyMatch(line -> line.contains("Biome placeholder")));
            }
        }
    }
}
