package io.earthplatform.minecraft.survival.source;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.geography.survival.RegisteredBlockState;
import io.earthplatform.geography.survival.SurvivalMaterials;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.render.SurvivalRenderPalette;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exact source/registry proofs, not a loaded-model, GL, memory/performance, or distance-readiness claim. */
class MarinVisualCubeSourceTest {
    @TempDir static Path temporary;
    private static MarinSurvivalDefinition definition;
    private static SurvivalPalette mutablePalette;
    private static SurvivalRenderPalette renderPalette;
    private static MarinVisualCubeSource visual;

    @BeforeAll static void sourceAndRegistry() throws Exception {
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        mutablePalette = new SurvivalPalette(EnginePaletteContext.create(biomes.freeze()));
        renderPalette = new SurvivalRenderPalette(mutablePalette);
        definition = MarinSurvivalResources.installAndLoad(temporary.resolve("source"));
        visual = new MarinVisualCubeSource(definition, renderPalette);
    }
    private static void assertPointEquality(CubePos position) {
        RegisteredBlockState[] cells = definition.generateVisualCube(position);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            assertEquals(definition.visualStateAt(position.minBlockX() + x, position.minBlockY() + y,
                    position.minBlockZ() + z), cells[CubePos.index(x, y, z)]);
    }

    @Test void everyPlayableHorizontalCubeHasBitExactSharedSurfaceGenerationAndIdentityIsSeparate() {
        var bounds = definition.envelope();
        int tested = 0;
        for (int z = bounds.minCenterZ(); z < bounds.maxCenterZExclusive(); z++)
            for (int x = bounds.minCenterX(); x < bounds.maxCenterXExclusive(); x++) {
                int y = CubePos.section(definition.surfaceY((long) x * 16 + 8, (long) z * 16 + 8));
                CubePos position = new CubePos(x, y, z);
                assertTrue(definition.permitsCenter(position));
                assertTrue(definition.permitsVisualCube(position));
                assertArrayEquals(definition.generateCube(position), definition.generateVisualCube(position));
                tested++;
            }
        assertEquals(1024, tested);
        assertEquals(512, bounds.maxTravelXExclusive() - bounds.minTravelX());
        assertEquals(512, bounds.maxTravelZExclusive() - bounds.minTravelZ());
        assertEquals(MarinSurvivalDefinition.DEFINITION_IDENTITY, definition.identity());
        assertEquals(MarinSurvivalDefinition.VISUAL_DEFINITION_IDENTITY, definition.visualIdentity());
        assertNotEquals(definition.identity(), definition.visualIdentity());
        assertEquals(visual.identity(), new MarinVisualCubeSource(definition, renderPalette).identity());
        assertEquals(mutablePalette.identity(), renderPalette.identity());
    }

    @Test void exactNativeDescriptorsIncludeGravelWhileSimulationStillRefusesIt() {
        for (RegisteredBlockState descriptor : SurvivalMaterials.supportedStates()) {
            var state = MarinNativeCubeSource.resolve(descriptor);
            assertSame(state, renderPalette.state(renderPalette.id(state)));
            if (descriptor.equals(SurvivalMaterials.GRAVEL))
                assertThrows(SurvivalRefusal.class, () -> mutablePalette.id(state));
            else assertEquals(mutablePalette.id(state), renderPalette.id(state));
        }
        var gravel = CubePos.fromBlock(-13642240, 59, -4212752);
        assertEquals(SurvivalMaterials.GRAVEL, definition.visualStateAt(-13642240, 59, -4212752));
        assertFalse(definition.permitsCenter(gravel));
        var blocks = visual.generate(gravel, new CancellationToken());
        assertSame(Blocks.GRAVEL.defaultBlockState(), renderPalette.state(blocks.get(CubePos.indexOfBlock(-13642240, 59, -4212752))));
        renderPalette.validate(blocks);
        assertThrows(SurvivalRefusal.class, () -> mutablePalette.validate(blocks));
        assertTrue(renderPalette.policyIdentity().contains("gravel"));
        assertThrows(SurvivalRefusal.class, () -> renderPalette.id(Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true)));
        assertThrows(SurvivalRefusal.class, () -> renderPalette.id(Blocks.WATER.defaultBlockState()));
        assertThrows(SurvivalRefusal.class, () -> renderPalette.id(Blocks.CHEST.defaultBlockState()));
        assertThrows(SurvivalRefusal.class, () -> renderPalette.id(Blocks.SAND.defaultBlockState()));
        assertThrows(SurvivalRefusal.class, () -> renderPalette.state(-1));
        assertThrows(SurvivalRefusal.class, () -> renderPalette.state(Integer.MAX_VALUE));
    }

    @Test void visualAvailabilityDoesNotExpandMutableSourceOrOwnerAdmission() {
        var bounds = definition.envelope();
        var outside = new CubePos(bounds.minCenterX() - 1, 500, definition.anchor().center().z());
        assertTrue(visual.permits(outside));
        assertFalse(definition.permitsCenter(outside));
        assertThrows(MissingTerrainException.class, () -> definition.generateCube(outside));
        assertThrows(MissingTerrainException.class, () -> definition.stateAt(outside.minBlockX(), outside.minBlockY(), outside.minBlockZ()));
        assertThrows(MissingTerrainException.class, () -> definition.treesIntersecting(outside.minBlockX(), outside.minBlockZ(),
                outside.minBlockX() + 16, outside.minBlockZ() + 16));
        var mutable = new MarinNativeCubeSource(definition, mutablePalette);
        assertThrows(MissingTerrainException.class, () -> mutable.generate(outside, new CancellationToken()));
        var world = new WorldIdentity(39, "marin-visual-no-authority", "039", "signed-marin", mutablePalette.identity());
        var owner = new NativeSurvivalWorld(world, mutablePalette,
                new NativeSurvivalWorld.Bounds(new CubePos(bounds.minCenterX(), bounds.minCenterY(), bounds.minCenterZ()),
                        new CubePos(bounds.maxCenterXExclusive() - 1, bounds.maxCenterYExclusive() - 1, bounds.maxCenterZExclusive() - 1)),
                new NativeSurvivalWorld.Limits(4, 4, 1, 4, 4096, 128, 4, 1024, 8, 4));
        assertThrows(SurvivalRefusal.class, () -> owner.admitGenerated(outside, visual.generate(outside, new CancellationToken())));
        assertThrows(SurvivalRefusal.class, () -> owner.admitGenerated(definition.anchor().center(),
                ImmutableCube.uniform(renderPalette.id(Blocks.GRAVEL.defaultBlockState()))));
        assertEquals(0, owner.usage().ownedCubes());
        assertEquals(0, owner.usage().residentCubes());
    }

    @Test void sourceBoundaryRequiresEveryRootHaloAndLargeQueriesFailBeforeAllocation() {
        var b = definition.envelope();
        long minX = b.minColumnX() + MarinSurvivalDefinition.TREE_HALO;
        long minZ = b.minColumnZ() + MarinSurvivalDefinition.TREE_HALO;
        long maxX = b.maxColumnXExclusive() - MarinSurvivalDefinition.TREE_HALO;
        long maxZ = b.maxColumnZExclusive() - MarinSurvivalDefinition.TREE_HALO;
        for (long x : new long[]{minX, maxX - 1}) for (long z : new long[]{minZ, maxZ - 1}) {
            assertNotNull(definition.visualStateAt(x, 0, z));
            assertNotNull(definition.visualTreesIntersecting(x, z, x + 1, z + 1));
        }
        assertThrows(MissingTerrainException.class, () -> definition.visualStateAt(minX - 1, 0, minZ));
        assertThrows(MissingTerrainException.class, () -> definition.visualStateAt(maxX, 0, minZ));
        assertThrows(MissingTerrainException.class, () -> definition.visualStateAt(minX, 0, minZ - 1));
        assertThrows(MissingTerrainException.class, () -> definition.visualStateAt(minX, 0, maxZ));
        assertTrue(definition.knownFootprint(b.minColumnX(), b.minColumnZ(), 16), "Terrain support alone is weaker than complete visual tree-root support");
        assertFalse(visual.permits(CubePos.fromBlock(b.minColumnX(), 0, b.minColumnZ())));
        assertPointEquality(CubePos.fromBlock(b.minColumnX() + 16, 0, b.minColumnZ() + 16));
        assertPointEquality(CubePos.fromBlock(b.maxColumnXExclusive() - 32, 0, b.maxColumnZExclusive() - 32));
        assertEquals(128, MarinSurvivalDefinition.MAX_VISUAL_TREE_QUERY_SPAN);
        var trees = definition.visualTreesIntersecting(minX, minZ, minX + 128, minZ + 128);
        assertTrue(trees.size() <= MarinSurvivalDefinition.MAX_VISUAL_TREE_CANDIDATES);
        assertThrows(MissingTerrainException.class, () -> definition.visualTreesIntersecting(minX, minZ, minX + 129, minZ + 1));
        assertThrows(MissingTerrainException.class, () -> definition.visualTreesIntersecting(minX, minZ, minX + 1, minZ + 129));
        assertThrows(MissingTerrainException.class, () -> definition.visualTreesIntersecting(minX, minZ, minX, minZ + 1));
        assertThrows(MissingTerrainException.class, () -> definition.visualTreesIntersecting(Long.MIN_VALUE, 0, Long.MAX_VALUE, 1));
        assertThrows(MissingTerrainException.class, () -> definition.visualStateAt(Long.MIN_VALUE, 0, minZ));
        assertThrows(MissingTerrainException.class, () -> definition.visualStateAt(Long.MAX_VALUE, 0, minZ));
        assertFalse(visual.permits(new CubePos(Integer.MIN_VALUE, 0, Integer.MAX_VALUE)));
        assertFalse(visual.permits(null));
        assertThrows(MissingTerrainException.class, () -> visual.generate(new CubePos(0, 0, 0), new CancellationToken()));
    }

    @Test void signedAbsoluteYAndNativeRegistryIdsMatchEveryCellWithoutRemapping() {
        var a = definition.anchor();
        for (long y : new long[]{MarinSurvivalDefinition.MIN_BLOCK_Y, -9600, -16, 0, 128, 8000,
                MarinSurvivalDefinition.MAX_BLOCK_Y_EXCLUSIVE - 16}) {
            var position = CubePos.fromBlock(a.x() - 512, y, a.z() - 512);
            assertTrue(position.x() < 0 && position.z() < 0);
            assertPointEquality(position);
            var named = definition.generateVisualCube(position);
            var actual = visual.generate(position, new CancellationToken());
            for (int index = 0; index < CubePos.VOLUME; index++)
                assertSame(MarinNativeCubeSource.resolve(named[index]), renderPalette.state(actual.get(index)));
            var overlap = CubePos.fromBlock(a.x(), y, a.z());
            assertArrayEquals(definition.generateCube(overlap), definition.generateVisualCube(overlap));
        }
        assertThrows(MissingTerrainException.class, () -> definition.visualStateAt(a.x(), MarinSurvivalDefinition.MIN_BLOCK_Y - 1, a.z()));
        assertThrows(MissingTerrainException.class, () -> definition.visualStateAt(a.x(), MarinSurvivalDefinition.MAX_BLOCK_Y_EXCLUSIVE, a.z()));
        assertFalse(visual.permits(new CubePos(a.center().x(), definition.envelope().minCenterY() - 1, a.center().z())));
        assertFalse(visual.permits(new CubePos(a.center().x(), definition.envelope().maxCenterYExclusive(), a.center().z())));
    }

    @Test void proceduralFeaturesOutsideMutablePatchHaveExactCrossXyzSeamsAndOrderIndependentFragments() {
        // Exact deterministic anchors from the pinned source, including X, Z, and Y cube seams.
        List<MarinSurvivalDefinition.Tree> features = List.of(
                new MarinSurvivalDefinition.Tree(-13642327, 266, -4215520, 5),
                new MarinSurvivalDefinition.Tree(-13642288, 262, -4215521, 5));
        Map<CubePos, RegisteredBlockState[]> captured = new HashMap<>();
        Set<CubePos> positions = new LinkedHashSet<>();
        boolean xSeam = false, ySeam = false, zSeam = false;
        for (var tree : features) {
            assertFalse(definition.envelope().containsPlayableColumn(tree.x(), tree.z()));
            assertTrue(definition.visualTreesIntersecting(tree.x() - 2, tree.z() - 2, tree.x() + 3, tree.z() + 3).contains(tree));
            var first = CubePos.fromBlock(tree.x() - 2, tree.groundY() + 1, tree.z() - 2);
            var last = CubePos.fromBlock(tree.x() + 2, tree.topY() + 1, tree.z() + 2);
            xSeam |= first.x() != last.x(); ySeam |= first.y() != last.y(); zSeam |= first.z() != last.z();
            for (long y = tree.groundY() + 1; y <= tree.topY() + 1; y++)
                for (long z = tree.z() - 2; z <= tree.z() + 2; z++)
                    for (long x = tree.x() - 2; x <= tree.x() + 2; x++) {
                        var position = CubePos.fromBlock(x, y, z); positions.add(position);
                        var cells = captured.computeIfAbsent(position, definition::generateVisualCube);
                        var expected = tree.stateAt(x, y, z);
                        if (!expected.isAir() && y > definition.surfaceY(x, z))
                            assertEquals(expected, cells[CubePos.indexOfBlock(x, y, z)]);
                    }
        }
        assertTrue(xSeam && ySeam && zSeam);
        for (CubePos position : positions) assertPointEquality(position);
        var reversed = new ArrayList<>(positions); Collections.reverse(reversed);
        for (CubePos position : reversed) assertArrayEquals(captured.get(position), definition.generateVisualCube(position));
        var position = positions.iterator().next();
        var fresh = definition.generateVisualCube(position); fresh[0] = SurvivalMaterials.CRAFTING_TABLE;
        assertArrayEquals(captured.get(position), definition.generateVisualCube(position));
        assertThrows(UnsupportedOperationException.class, () -> definition.visualTreesIntersecting(
                features.getFirst().x() - 2, features.getFirst().z() - 2,
                features.getFirst().x() + 3, features.getFirst().z() + 3).clear());
    }
}
