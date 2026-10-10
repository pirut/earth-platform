package io.earthplatform.minecraft.scene;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actual native block/snapshot tests. These tests do not claim GL publication or world lighting. */
class SceneNeighborhoodTest {
    private static final WorldIdentity WORLD = new WorldIdentity(0, "scene", "1", "real-elevation", "native-palette");
    private static EnginePaletteContext palette;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS,
                VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
                RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }

    private static SceneCube cube(CubePos position, BlockState state) {
        return cube(position, WORLD, 1, EngineCubeSection.uniform(palette, state));
    }

    private static SceneCube cube(CubePos position, WorldIdentity world, long epoch, EngineCubeSection section) {
        return new SceneCube(EngineCubeSnapshot.capture(position, world, epoch, 1, 1, section), palette);
    }

    private static SceneNeighborhood neighborhood(SceneCube cube) {
        return new SceneNeighborhood(Map.of(cube.snapshot().position(), cube));
    }

    @Test void allSupportedNativeStatesRemainExactIncludingSnowyGrassAndDiagnosticMarkers() {
        var states = List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                Blocks.ANDESITE.defaultBlockState(), Blocks.DIORITE.defaultBlockState(), Blocks.TUFF.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.GRASS_BLOCK.defaultBlockState().setValue(SnowyDirtBlock.SNOWY, true),
                Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.PACKED_ICE.defaultBlockState(),
                Blocks.MAGENTA_TERRACOTTA.defaultBlockState(), Blocks.CYAN_TERRACOTTA.defaultBlockState());
        var section = new EngineCubeSection(palette);
        for (int i = 0; i < states.size(); i++) section.set(i, 8, 9, states.get(i));
        SceneCube cube = cube(new CubePos(-1, 553, -1), WORLD, 1, section);
        var neighborhood = neighborhood(cube);
        for (int i = 0; i < states.size(); i++) {
            var pos = new BlockPos(-16 + i, 8856, -7);
            assertSame(states.get(i), cube.state(i, 8, 9));
            assertSame(states.get(i), neighborhood.getBlockState(pos));
            assertTrue(neighborhood.getFluidState(pos).isEmpty());
            assertNull(neighborhood.getBlockEntity(pos));
        }
    }

    @Test void immutableMaterializationDoesNotExposeOrRetainAMutableSection() {
        var position = new CubePos(0, 0, 0);
        var section = new EngineCubeSection(palette);
        section.set(7, 8, 9, Blocks.STONE.defaultBlockState());
        var snapshot = EngineCubeSnapshot.capture(position, WORLD, 1, 1, 1, section);
        SceneCube cube = new SceneCube(snapshot, palette);
        section.set(7, 8, 9, Blocks.WATER.defaultBlockState());
        snapshot.materialize(palette).set(7, 8, 9, Blocks.DIRT.defaultBlockState());
        assertSame(snapshot, cube.snapshot());
        assertSame(Blocks.STONE.defaultBlockState(), cube.state(7, 8, 9));
        assertSame(Blocks.STONE.defaultBlockState(), CompletableFuture.supplyAsync(() -> cube.state(7, 8, 9)).join());
        byte[] copiedWire = snapshot.copySectionBytes();
        java.util.Arrays.fill(copiedWire, (byte) 0);
        assertSame(Blocks.STONE.defaultBlockState(), new SceneCube(snapshot, palette).state(7, 8, 9));
    }

    @Test void localAccessRejectsOutOfBoundsOnEveryAxis() {
        SceneCube cube = cube(new CubePos(0, 0, 0), Blocks.AIR.defaultBlockState());
        for (int outside : new int[]{-1, 16, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThrows(IndexOutOfBoundsException.class, () -> cube.state(outside, 8, 8));
            assertThrows(IndexOutOfBoundsException.class, () -> cube.state(8, outside, 8));
            assertThrows(IndexOutOfBoundsException.class, () -> cube.state(8, 8, outside));
        }
    }

    @Test void nativeMaterializationRejectsAMismatchedPaletteFingerprint() {
        var snapshot = EngineCubeSnapshot.capture(new CubePos(0, 0, 0), WORLD, 1, 1, 1, new EngineCubeSection(palette));
        String otherFingerprint = palette.fingerprint().equals("0".repeat(64)) ? "1".repeat(64) : "0".repeat(64);
        var otherPalette = new EnginePaletteContext(palette.biomes(), otherFingerprint);
        assertThrows(io.netty.handler.codec.DecoderException.class, () -> new SceneCube(snapshot, otherPalette));
    }

    @Test void unknownUnloadedAndPackedNativeYAliasesNeverBecomeAirOrLight() {
        for (int x : new int[]{-20_037_508, 20_037_508}) for (int z : new int[]{-10_018_754, 10_018_754}) {
            var low = cube(CubePos.fromBlock(x, -11000, z), Blocks.STONE.defaultBlockState());
            var high = cube(CubePos.fromBlock(x, 8849, z), Blocks.PACKED_ICE.defaultBlockState());
            var neighborhood = new SceneNeighborhood(Map.of(low.snapshot().position(), low, high.snapshot().position(), high));
            assertEquals(-11008, neighborhood.getMinBuildHeight());
            assertEquals(19872, neighborhood.getHeight());
            assertEquals(8864, neighborhood.getMaxBuildHeight());
            assertSame(Blocks.STONE.defaultBlockState(), neighborhood.getBlockState(new BlockPos(x, -11008, z)));
            assertSame(Blocks.STONE.defaultBlockState(), neighborhood.getBlockState(new BlockPos(x, -11000, z)));
            assertSame(Blocks.PACKED_ICE.defaultBlockState(), neighborhood.getBlockState(new BlockPos(x, 8849, z)));
            assertSame(Blocks.PACKED_ICE.defaultBlockState(), neighborhood.getBlockState(new BlockPos(x, 8863, z)));
            assertUnknown(neighborhood, new BlockPos(x, -11009, z));
            assertUnknown(neighborhood, new BlockPos(x, 8864, z));
            for (int y : new int[]{-11000, 8849}) {
                var known = new BlockPos(x, y, z);
                for (int shift : new int[]{-4096, 4096}) {
                    var alias = known.offset(0, shift, 0);
                    assertEquals(known.asLong(), alias.asLong());
                    assertUnknown(neighborhood, alias);
                }
                var position = CubePos.fromBlock(x, y, z);
                int ox = Math.toIntExact(position.minBlockX()), oy = Math.toIntExact(position.minBlockY()), oz = Math.toIntExact(position.minBlockZ());
                for (var unknown : List.of(new BlockPos(ox - 1, oy + 8, oz + 8), new BlockPos(ox + 16, oy + 8, oz + 8),
                        new BlockPos(ox + 8, oy - 1, oz + 8), new BlockPos(ox + 8, oy + 16, oz + 8),
                        new BlockPos(ox + 8, oy + 8, oz - 1), new BlockPos(ox + 8, oy + 8, oz + 16),
                        new BlockPos(ox - 1, oy - 1, oz - 1), new BlockPos(ox + 16, oy + 16, oz + 16))) {
                    assertUnknown(neighborhood, unknown);
                }
            }
        }
    }

    private static void assertUnknown(SceneNeighborhood neighborhood, BlockPos pos) {
        assertAll(
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getBlockState(pos)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getFluidState(pos)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getBlockEntity(pos)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getBrightness(LightLayer.SKY, pos)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getBrightness(LightLayer.BLOCK, pos)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getRawBrightness(pos, 0)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getBlockTint(pos, (biome, x, z) -> 0)));
    }

    @Test void knownFaceAndDiagonalHaloReadsUseTheirNativeOwners() {
        Map<CubePos, SceneCube> input = new HashMap<>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            var position = new CubePos(x, 10 + y, z);
            input.put(position, cube(position, y == 1 ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState()));
        }
        var neighborhood = new SceneNeighborhood(input);
        assertEquals(27, neighborhood.size());
        assertSame(Blocks.STONE.defaultBlockState(), neighborhood.getBlockState(new BlockPos(-1, 159, -1)));
        assertSame(Blocks.AIR.defaultBlockState(), neighborhood.getBlockState(new BlockPos(16, 176, 16)));
        assertUnknown(neighborhood, new BlockPos(32, 176, 16));
    }

    @Test void neighborhoodsOwnTheirMapAndEnforceThe256OwnerLimit() {
        Map<CubePos, SceneCube> input = new HashMap<>();
        for (int i = 0; i < 256; i++) {
            var position = new CubePos(i, 0, 0);
            input.put(position, cube(position, Blocks.AIR.defaultBlockState()));
        }
        var neighborhood = new SceneNeighborhood(input);
        assertEquals(256, neighborhood.size());
        assertTrue(neighborhood.contains(new CubePos(255, 0, 0)));
        var extra = new CubePos(256, 0, 0);
        input.put(extra, cube(extra, Blocks.AIR.defaultBlockState()));
        assertThrows(IllegalArgumentException.class, () -> new SceneNeighborhood(input));
        input.clear();
        assertEquals(256, neighborhood.size());
        assertSame(Blocks.AIR.defaultBlockState(), neighborhood.getBlockState(new BlockPos(4080, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new SceneNeighborhood(Map.of()));
    }

    @Test void neighborhoodsRejectMismatchedKeysWorldsEpochsAndNullEntries() {
        var origin = new CubePos(0, 0, 0);
        var next = new CubePos(1, 0, 0);
        var first = cube(origin, Blocks.AIR.defaultBlockState());
        assertThrows(IllegalArgumentException.class, () -> new SceneNeighborhood(Map.of(next, first)));
        var otherWorld = new WorldIdentity(1, "scene", "1", "real-elevation", "native-palette");
        var alien = cube(next, otherWorld, 1, new EngineCubeSection(palette));
        var otherEpoch = cube(next, WORLD, 2, new EngineCubeSection(palette));
        assertThrows(IllegalArgumentException.class, () -> new SceneNeighborhood(Map.of(origin, first, next, alien)));
        assertThrows(IllegalArgumentException.class, () -> new SceneNeighborhood(Map.of(origin, first, next, otherEpoch)));
        Map<CubePos, SceneCube> nullable = new HashMap<>();
        nullable.put(origin, null);
        assertThrows(NullPointerException.class, () -> new SceneNeighborhood(nullable));
    }

    @Test void unsupportedFluidsBlockEntitiesAndUnapprovedModelsFailClosed() {
        for (var state : List.of(Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.VOID_AIR.defaultBlockState(),
                Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.SAND.defaultBlockState(),
                Blocks.SNOW.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState())) {
            assertThrows(SparseFacadeException.class, () -> cube(new CubePos(0, 0, 0), state));
        }
        for (int y : new int[]{-689, 554}) {
            assertThrows(IllegalArgumentException.class, () -> cube(new CubePos(0, y, 0), Blocks.AIR.defaultBlockState()));
        }
    }

    @Test void fixedDiagnosticIlluminationDoesNotClaimANativeLightingEngine() {
        var neighborhood = neighborhood(cube(new CubePos(0, 0, 0), Blocks.STONE.defaultBlockState()));
        var known = new BlockPos(8, 8, 8);
        assertEquals(15, neighborhood.getBrightness(LightLayer.BLOCK, known));
        assertEquals(15, neighborhood.getBrightness(LightLayer.SKY, known));
        assertEquals(12, neighborhood.getRawBrightness(known, 3));
        assertEquals(0, neighborhood.getRawBrightness(known, 16));
        assertEquals(0, neighborhood.getRawBrightness(known, Integer.MAX_VALUE));
        assertEquals(15, neighborhood.getRawBrightness(known, Integer.MIN_VALUE));
        assertEquals(0x91BD59, SceneNeighborhood.COSMETIC_GRASS_TINT);
        assertEquals(SceneNeighborhood.COSMETIC_GRASS_TINT, neighborhood.getBlockTint(known, (biome, x, z) -> 0));
        var grass = neighborhood(cube(new CubePos(0, 0, 0), Blocks.GRASS_BLOCK.defaultBlockState()));
        assertEquals(0x91BD59, grass.getBlockTint(known, (biome, x, z) -> 0x123456),
                "Grass tint is explicitly cosmetic and does not claim a sampled biome color");
        for (var direction : Direction.values()) assertEquals(1.0f, neighborhood.getShade(direction, false));
        assertEquals(0.5f, neighborhood.getShade(Direction.DOWN, true));
        assertEquals(1.0f, neighborhood.getShade(Direction.UP, true));
        assertEquals(0.8f, neighborhood.getShade(Direction.NORTH, true));
        assertEquals(0.8f, neighborhood.getShade(Direction.SOUTH, true));
        assertEquals(0.6f, neighborhood.getShade(Direction.EAST, true));
        assertEquals(0.6f, neighborhood.getShade(Direction.WEST, true));
        assertThrows(SparseFacadeException.class, neighborhood::getLightEngine);
    }

    @Test void compilationReadTrackingIsPrivateAndRecordsActualNativeDependencies() {
        var cube = cube(new CubePos(0, 0, 0), Blocks.STONE.defaultBlockState());
        var neighborhood = neighborhood(cube);
        List<SceneCube> reads = new ArrayList<>();
        var tracking = neighborhood.tracking(reads::add);
        neighborhood.getBlockState(new BlockPos(1, 1, 1));
        assertTrue(reads.isEmpty());
        tracking.getBlockState(new BlockPos(1, 1, 1));
        tracking.getBrightness(LightLayer.SKY, new BlockPos(2, 2, 2));
        assertEquals(List.of(cube, cube), reads);
        assertUnknown(tracking, new BlockPos(16, 1, 1));
        assertEquals(2, reads.size());
    }
}
