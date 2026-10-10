package io.earthplatform.minecraft;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.WorldManifest;
import io.netty.buffer.Unpooled;
import java.util.HashSet;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Accepted DSM only; all 65,536 columns and 16,777,216 native voxel positions in the safe footprint. */
class CopernicusWindowTest {
    private static Registry<Biome> biomes;
    private static EarthWindowGenerator generator;
    @BeforeAll static void setup() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        biomes = registry.freeze();
        generator = new EarthWindowGenerator(new FixedBiomeSource(biomes.getHolderOrThrow(Biomes.PLAINS)),
                7328, 7499, true, ProofTerrain.COPERNICUS, ProofTerrain.GLOBAL_PROJECTION);
    }

    @Test void acceptedBytesAndActualPeakSampleArePreserved() throws Exception {
        var source = generator.terrain().source();
        assertEquals(ProofTerrain.COPERNICUS_FINGERPRINT, source.manifest().fingerprint());
        assertEquals("copernicus-dem-glo30-2021", source.manifest().provenance().id());
        assertEquals(1.0, source.manifest().provenance().resolutionArcSeconds());
        try (var input = ProofTerrain.class.getResourceAsStream("/earthplatform/copernicus_proof_data/everest.egp")) {
            assertNotNull(input);
            assertEquals("51a7756632cc1655aa2a1f65bd1a08ff6dfa68e89dfaa19336218c71f54a8d87", WorldManifest.sha256(input.readAllBytes()));
        }
        for (String resource : new String[]{"provenance/License-COPDEM-30.pdf", "provenance/source-eula.pdf", "provenance/source-metadata.xml", "source-lock.json"}) {
            try (var input = ProofTerrain.class.getResourceAsStream("/earthplatform/copernicus_proof_data/" + resource)) { assertNotNull(input, resource); }
        }
        double landmark = source.elevationMetres(new GeoPoint(27.9881, 86.925));
        assertEquals(8720.444140624944, landmark, 1e-7);
        assertNotEquals(8849, (long) StrictMath.floor(landmark));
    }

    @Test void runtimeProjectionIdentityIsExplicitAndDistinctFromLocalPackIdentity() {
        var terrain = generator.terrain();
        assertEquals(new GeoPoint(0, 0), terrain.projection().origin());
        assertEquals(0.0, terrain.projection().standardParallel());
        assertEquals(7499.750282978113, terrain.elevationMetres(9_674_928, -3_112_480), 1e-7);
        assertEquals(7499, generator.earthSurfaceY(9_674_928, -3_112_480));
        assertNotEquals(terrain.source().manifest().fingerprint(), terrain.runtimeFingerprint());
        var local = new WindowTerrain(ProofTerrain.COPERNICUS, ProofTerrain.MANIFEST_PROJECTION);
        assertNotEquals(local.runtimeFingerprint(), terrain.runtimeFingerprint());
        assertEquals(terrain.runtimeFingerprint(), new WindowTerrain(ProofTerrain.COPERNICUS, ProofTerrain.GLOBAL_PROJECTION).runtimeFingerprint());
        var geo = terrain.projection().blockCentre(9_674_928, -3_112_480);
        assertEquals(terrain.source().elevationMetres(geo), terrain.elevationMetres(9_674_928, -3_112_480));
    }

    @Test void allNativeColumnsRetainVerifiedReliefWithoutAddedMicrodetail() {
        double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
        long voxelCount = 0;
        var distinct = new HashSet<Long>();
        var height = LevelHeightAccessor.create(0, 256);
        var cell = new BlockPos.MutableBlockPos();
        for (int cz = 0; cz < 16; cz++) for (int cx = 0; cx < 16; cx++) {
            int startX = 9_674_800 + cx * 16, startZ = -3_112_608 + cz * 16;
            var chunk = new ProtoChunk(new ChunkPos(Math.floorDiv(startX, 16), Math.floorDiv(startZ, 16)), UpgradeData.EMPTY, height, biomes, null);
            generator.fillFromNoise(null, null, null, chunk).join();
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int worldX = startX + x, worldZ = startZ + z;
                var geo = generator.terrain().projection().blockCentre(worldX, worldZ);
                double exact = generator.terrain().source().elevationMetres(geo);
                minimum = Math.min(minimum, exact); maximum = Math.max(maximum, exact);
                long ground = (long) StrictMath.floor(exact); distinct.add(ground);
                assertEquals(ground, generator.earthSurfaceY(worldX, worldZ));
                assertTrue(ground >= 7345 && ground <= 7556);
                for (int y = 0; y < 256; y++) {
                    var actual = chunk.getBlockState(cell.set(worldX, y, worldZ));
                    long canonical = 7328L + y;
                    assertTrue(actual.is(canonical > ground ? Blocks.AIR : canonical == ground ? Blocks.SNOW_BLOCK : Blocks.STONE));
                    voxelCount++;
                }
            }
        }
        assertEquals(7345.986004719974, minimum, 1e-7);
        assertEquals(7556.914606812992, maximum, 1e-7);
        assertEquals(210.92860209301853, maximum - minimum, 1e-7);
        assertEquals(212, distinct.size());
        assertEquals(16_777_216, voxelCount);
        System.out.println("COPERNICUS_NATIVE_FOOTPRINT columns=65536 voxels=" + voxelCount + " min=" + minimum
                + " max=" + maximum + " relief=" + (maximum - minimum) + " runtimeIdentity=" + generator.terrain().runtimeFingerprint());
    }

    @Test void absentCoverageAndUnknownDatasetNeverFallBackToSyntheticOrNoaa() {
        assertThrows(MissingTerrainException.class, () -> generator.earthSurfaceY(0, 0));
        assertThrows(MissingTerrainException.class, () -> generator.terrain().cube(new CubePos(0, 458, 0)));
        assertThrows(IllegalArgumentException.class, () -> new WindowTerrain("unknown", ProofTerrain.GLOBAL_PROJECTION));
        assertThrows(IllegalArgumentException.class, () -> new WindowTerrain(ProofTerrain.COPERNICUS, "guessed_projection"));
    }

    @Test void oldGeneratorCodecRetainsNoaaAndManifestProjectionDefaults() {
        var input = JsonParser.parseString("""
                {"biome_source":{"type":"minecraft:fixed","biome":"minecraft:plains"},
                 "earth_origin_y":8352,"calibration_surface_y":8491,"real_terrain":true}
                """);
        // A concrete native registry supplies stable holder ownership for serialization;
        // VanillaRegistries' bootstrap lookup is a construction context, not a save registry.
        var lookup = HolderLookup.Provider.create(java.util.stream.Stream.of(biomes.asLookup()));
        var ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        var decoded = EarthWindowGenerator.CODEC.codec().parse(ops, input).getOrThrow();
        assertEquals(ProofTerrain.NOAA, decoded.terrainDataset());
        assertEquals(ProofTerrain.MANIFEST_PROJECTION, decoded.horizontalProjection());
        assertTrue(decoded.realTerrain());
        var copernicusJson = input.deepCopy().getAsJsonObject();
        copernicusJson.addProperty("earth_origin_y", 7328);
        copernicusJson.addProperty("calibration_surface_y", 7499);
        copernicusJson.addProperty("terrain_dataset", ProofTerrain.COPERNICUS);
        copernicusJson.addProperty("horizontal_projection", ProofTerrain.GLOBAL_PROJECTION);
        // Decode through these RegistryOps so the holder has their serialization owner.
        var registryOwned = EarthWindowGenerator.CODEC.codec().parse(ops, copernicusJson).getOrThrow();
        var encoded = EarthWindowGenerator.CODEC.codec().encodeStart(ops, registryOwned).getOrThrow();
        var roundTrip = EarthWindowGenerator.CODEC.codec().parse(ops, encoded).getOrThrow();
        assertEquals(ProofTerrain.COPERNICUS, roundTrip.terrainDataset());
        assertEquals(ProofTerrain.GLOBAL_PROJECTION, roundTrip.horizontalProjection());
        assertEquals(generator.terrain().runtimeFingerprint(), roundTrip.terrain().runtimeFingerprint());
    }

    @Test void payloadAndHudCarryCopernicusIdentityRatherThanNoaaLabel() {
        var expected = new WindowStatePayload(ResourceLocation.fromNamespaceAndPath("earthplatform", "copernicus_ridge"),
                7328, 7499, true, ProofTerrain.COPERNICUS, ProofTerrain.GLOBAL_PROJECTION);
        var bytes = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            WindowStatePayload.CODEC.encode(bytes, expected);
            assertEquals(expected, WindowStatePayload.CODEC.decode(bytes));
            assertFalse(bytes.isReadable());
        } finally { bytes.release(); }
        var hud = EarthWindowHud.layout(393, 272, 9, text -> text.length() * 6, 173, 7328, true, ProofTerrain.COPERNICUS);
        assertTrue(hud.source().contains("Copernicus 1\" DSM"));
        assertFalse(hud.source().contains("NOAA"));
        assertTrue(hud.source().contains("heuristic"));
        assertTrue(hud.source().contains("256 m"));
        assertTrue(hud.x() + hud.width() <= 388);
    }
}
