package io.earthplatform.minecraft.survival.source;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.geography.survival.RegisteredBlockState;
import io.earthplatform.geography.survival.SurvivalMaterials;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarinNativeCubeSourceTest {
    @TempDir Path temporary;
    private static SurvivalPalette palette;
    @BeforeAll static void registry() {
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = new SurvivalPalette(EnginePaletteContext.create(biomes.freeze()));
    }
    @Test void bundledSourceReplaysExactPinnedIdentityAndDoesNotRewriteExistingFiles() throws Exception {
        Path root = temporary.resolve("source");
        var definition = MarinSurvivalResources.installAndLoad(root);
        assertEquals(MarinSurvivalDefinition.DEFINITION_IDENTITY, definition.identity());
        Path manifest = root.resolve("terrain/world-global.properties");
        byte[] before = Files.readAllBytes(manifest); var modified = Files.getLastModifiedTime(manifest);
        assertEquals(definition.identity(), MarinSurvivalResources.installAndLoad(root).identity());
        assertArrayEquals(before, Files.readAllBytes(manifest)); assertEquals(modified, Files.getLastModifiedTime(manifest));
        assertEquals(134, definition.anchor().surfaceY());
    }
    @Test void changedOrSymlinkedSourceIsRefusedWithoutOverwrite() throws Exception {
        Path root = temporary.resolve("source"); MarinSurvivalResources.installAndLoad(root);
        Path manifest = root.resolve("terrain/world-global.properties"); byte[] changed = {1, 2, 3};
        Files.write(manifest, changed);
        assertThrows(java.io.IOException.class, () -> MarinSurvivalResources.installAndLoad(root));
        assertArrayEquals(changed, Files.readAllBytes(manifest));
        Path alias = temporary.resolve("alias"); Files.createSymbolicLink(alias, root);
        assertThrows(java.io.IOException.class, () -> MarinSurvivalResources.installAndLoad(alias));
    }
    @Test void everyCellOfActualTreeAndAdjacentCubesMatchesExactRegistryDescriptor() throws Exception {
        var definition = MarinSurvivalResources.installAndLoad(temporary.resolve("source"));
        var source = new MarinNativeCubeSource(definition, palette);
        for (CubePos position : new CubePos[]{CubePos.fromBlock(-13640061, 134, -4213182),
                CubePos.fromBlock(-13640061, 144, -4213182), CubePos.fromBlock(-13640064, 128, -4213184)}) {
            var expected = definition.generateCube(position);
            var actual = source.generate(position, new CancellationToken());
            for (int index = 0; index < CubePos.VOLUME; index++)
                assertSame(MarinNativeCubeSource.resolve(expected[index]), palette.state(actual.get(index)));
        }
    }
    @Test void registryResolutionNeverDefaultsUnknownPropertiesOrUnknownBlocksToAir() {
        assertSame(Blocks.OAK_LOG.defaultBlockState(), MarinNativeCubeSource.resolve(SurvivalMaterials.OAK_LOG));
        assertThrows(IllegalArgumentException.class, () -> MarinNativeCubeSource.resolve(RegisteredBlockState.of("minecraft:no_such_block", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> MarinNativeCubeSource.resolve(RegisteredBlockState.of("minecraft:oak_log", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> MarinNativeCubeSource.resolve(RegisteredBlockState.of("minecraft:oak_log", Map.of("axis", "wrong"))));
        assertThrows(IllegalArgumentException.class, () -> MarinNativeCubeSource.resolve(RegisteredBlockState.of("minecraft:stone", Map.of("invented", "true"))));
    }
    @Test void mutableSourceRefusesOutsideAuthorizedPatchInsteadOfSupplyingAir() throws Exception {
        var source = new MarinNativeCubeSource(MarinSurvivalResources.installAndLoad(temporary.resolve("source")), palette);
        assertThrows(io.earthplatform.geography.MissingTerrainException.class, () -> source.generate(new CubePos(0, 0, 0), new CancellationToken()));
    }
}
