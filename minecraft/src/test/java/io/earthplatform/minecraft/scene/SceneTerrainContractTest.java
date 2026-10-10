package io.earthplatform.minecraft.scene;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.geography.EquirectangularProjection;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.WorldManifest;
import io.earthplatform.geography.variety.AlpineSurfaceRelief;
import io.earthplatform.minecraft.ProofTerrain;
import io.earthplatform.minecraft.lod.DhGeographicLodData;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.streaming.CubeStreamAdmission;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.*;

/** Accepted source bytes and native CPU contracts only; no GL, collision, or gameplay claim. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SceneTerrainContractTest {
    private static final EquirectangularProjection PROJECTION = new EquirectangularProjection(new GeoPoint(0, 0), 0);
    private static EnginePaletteContext palette;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS,
                VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
                RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }

    private static SceneTerrain terrain(boolean relief) {
        String before = System.getProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN);
        try {
            System.setProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN, Boolean.toString(relief));
            return new SceneTerrain();
        } finally {
            if (before == null) System.clearProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN);
            else System.setProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN, before);
        }
    }

    @Test void acceptedSceneStartPreservesSourceHeightAndExplicitReliefIdentity() {
        var source = ProofTerrain.get(ProofTerrain.COPERNICUS);
        assertEquals(ProofTerrain.COPERNICUS_FINGERPRINT, source.manifest().fingerprint());
        assertEquals(7500.738714689942,
                source.elevationMetres(PROJECTION.blockCentre(SceneCommands.START_X, SceneCommands.START_Z)), 1e-7);
        var exact = terrain(false);
        var natural = terrain(true);
        var exactColumn = exact.sample(SceneCommands.START_X, SceneCommands.START_Z);
        var naturalColumn = natural.sample(SceneCommands.START_X, SceneCommands.START_Z);
        assertFalse(exact.naturalRelief());
        assertTrue(natural.naturalRelief());
        assertEquals(7500.738714689942, exactColumn.source().sourceMetres(), 1e-7);
        assertEquals(0, exactColumn.source().proceduralDeltaMetres());
        assertEquals(exactColumn.source().sourceMetres(), exactColumn.source().generatedMetres());
        assertEquals(7500, exactColumn.surfaceY());
        assertEquals(exactColumn.source().sourceMetres(), naturalColumn.source().sourceMetres());
        assertEquals(naturalColumn.source().sourceMetres() + naturalColumn.source().proceduralDeltaMetres(),
                naturalColumn.source().generatedMetres());
        assertTrue(Math.abs(naturalColumn.source().proceduralDeltaMetres()) <= AlpineSurfaceRelief.MAXIMUM_DISPLACEMENT_METRES);
        assertEquals((long) StrictMath.floor(naturalColumn.source().generatedMetres()), naturalColumn.surfaceY());
        assertEquals(exact.identity(), terrain(false).identity());
        assertNotEquals(exact.identity(), natural.identity());
        assertEquals(source.manifest().provenance().source().toString(), exact.sourceUrl());
        assertEquals(source.manifest().provenance().verticalDatum(), exact.datum());
        assertSame(exactColumn, exact.sample(SceneCommands.START_X, SceneCommands.START_Z));
        assertEquals(1, exact.cachedColumns());
        assertEquals(exactColumn.argb(), exact.argb(SceneCommands.START_X, SceneCommands.START_Z, 7500));
        assertThrows(IllegalArgumentException.class, () -> exact.argb(SceneCommands.START_X, SceneCommands.START_Z, 7499));
    }

    @Test void paletteWorldIdentityFitsEveryBoundedSnapshotFieldAndPinsNativePalette() {
        var terrain = terrain(false);
        var world = terrain.world(palette);
        for (String field : List.of(world.generatorId(), world.generatorVersion(), world.datasetVersion(), world.blockPaletteVersion())) {
            assertFalse(field.isBlank());
            assertTrue(field.length() <= 128, field);
        }
        assertEquals(terrain.identity(), world.datasetVersion());
        assertTrue(world.datasetVersion().matches("[0-9a-f]{64}"));
        assertEquals(WorldManifest.sha256((SceneTerrain.PALETTE + "/" + palette.fingerprint()).getBytes(StandardCharsets.UTF_8)),
                world.blockPaletteVersion());
        assertTrue(world.blockPaletteVersion().matches("[0-9a-f]{64}"));
        String otherFingerprint = palette.fingerprint().equals("0".repeat(64)) ? "1".repeat(64) : "0".repeat(64);
        var other = terrain.world(new EnginePaletteContext(palette.biomes(), otherFingerprint));
        assertNotEquals(world.blockPaletteVersion(), other.blockPaletteVersion());
        assertEquals(world.datasetVersion(), other.datasetVersion());
        assertNotEquals(world.datasetVersion(), terrain(true).world(palette).datasetVersion());
    }

    @Test void actualEntryPlanCreatesAndAdmitsEveryNativeOwnerWithinBudgetInBothReliefModes() {
        for (boolean relief : new boolean[]{false, true}) {
            var terrain = terrain(relief);
            long ground = terrain.surfaceY(SceneCommands.START_X, SceneCommands.START_Z);
            var plan = SceneInterestPlan.around(terrain, SceneCommands.START_X + 0.5, ground + 36.0, SceneCommands.START_Z + 0.5);
            assertEquals(CubePos.section(SceneCommands.START_X), plan.centerX());
            assertEquals(CubePos.section(SceneCommands.START_Z), plan.centerZ());
            assertFalse(plan.renderTargets().isEmpty());
            assertTrue(plan.owners().size() <= SceneInterestPlan.MAX_OWNERS);
            assertTrue(plan.owners().containsAll(plan.renderTargets()));
            for (var target : plan.renderTargets()) {
                for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                    var neighbor = target.offset(x, y, z);
                    if (neighbor.minBlockY() >= -11008 && neighbor.minBlockY() + 16 <= 8864)
                        assertTrue(plan.owners().contains(neighbor), "Missing actual source halo " + neighbor);
                }
            }
            var world = terrain.world(palette);
            var admission = new CubeStreamAdmission(world, 7, SceneInterestPlan.MAX_OWNERS);
            var installed = new HashMap<CubePos, SceneCube>();
            var requests = admission.replaceInterest(plan.owners()).requested();
            assertEquals(plan.owners().size(), requests.size());
            for (var lease : requests) {
                var snapshot = terrain.snapshot(lease.position(), palette, world, 7, lease.generation());
                assertEquals(world, snapshot.world());
                assertEquals(lease.position(), snapshot.position());
                assertEquals(lease.generation(), snapshot.leaseGeneration());
                assertTrue(snapshot.sectionBytes() > 0 && snapshot.sectionBytes() <= EngineCubeSnapshot.MAX_SECTION_BYTES);
                var wire = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    snapshot.write(wire);
                    var decoded = EngineCubeSnapshot.readFrame(wire, world, 7, palette);
                    var header = new CubeStreamAdmission.Header(decoded.world(), decoded.epoch(), decoded.position(),
                            decoded.leaseGeneration(), decoded.revision());
                    var accepted = admission.admit(header);
                    assertTrue(accepted.accepted(), accepted.rejection().toString());
                    var cube = new SceneCube(decoded, palette);
                    assertEquals(CubeStreamAdmission.Rejection.NONE,
                            admission.commit(accepted.token(), () -> installed.put(decoded.position(), cube)));
                    assertTrue(admission.isCurrentLoaded(header));
                    assertSnapshotColumns(terrain, cube);
                    assertFalse(wire.isReadable());
                } finally { wire.release(); }
            }
            assertEquals(plan.owners().size(), installed.size());
            assertEquals(installed.size(), new SceneNeighborhood(installed).size());
            assertTrue(terrain.cachedColumns() <= SceneTerrain.MAX_CACHED_COLUMNS);
            System.out.println("SCENE_ENTRY_SOURCE relief=" + relief + " ground=" + ground + " cameraY=" + (ground + 36)
                    + " targets=" + plan.renderTargets().size() + " admittedOwners=" + installed.size());
        }
    }

    private static void assertSnapshotColumns(SceneTerrain terrain, SceneCube cube) {
        var position = cube.snapshot().position();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            var column = terrain.sample(position.minBlockX() + x, position.minBlockZ() + z);
            for (int y = 0; y < 16; y++) {
                long absoluteY = position.minBlockY() + y;
                var expected = absoluteY > column.surfaceY() ? Blocks.AIR.defaultBlockState()
                        : absoluteY == column.surfaceY() ? column.top()
                        : absoluteY >= column.surfaceY() - 3 ? column.subsurface() : Blocks.STONE.defaultBlockState();
                assertSame(expected, cube.state(x, y, z));
                assertTrue(cube.state(x, y, z).getFluidState().isEmpty());
            }
        }
    }

    @Test void all4096FarCentersAndTheirSourceDerivativeHalosAreKnownWithoutClamping() {
        var source = ProofTerrain.get(ProofTerrain.COPERNICUS);
        long originX = Math.floorDiv(SceneCommands.START_X, DhGeographicLodData.CELL_SIZE) * DhGeographicLodData.CELL_SIZE - DhGeographicLodData.RADIUS;
        long originZ = Math.floorDiv(SceneCommands.START_Z, DhGeographicLodData.CELL_SIZE) * DhGeographicLodData.CELL_SIZE - DhGeographicLodData.RADIUS;
        int samples = 0;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int z = 0; z < DhGeographicLodData.CELLS; z++) for (int x = 0; x < DhGeographicLodData.CELLS; x++) {
            long bx = originX + (long) x * DhGeographicLodData.CELL_SIZE + DhGeographicLodData.CELL_SIZE / 2;
            long bz = originZ + (long) z * DhGeographicLodData.CELL_SIZE + DhGeographicLodData.CELL_SIZE / 2;
            for (int[] delta : new int[][]{{0, 0}, {8, 0}, {-8, 0}, {0, 8}, {0, -8}}) {
                double height = source.elevationMetres(PROJECTION.blockCentre(bx + delta[0], bz + delta[1]));
                assertTrue(Double.isFinite(height));
                min = Math.min(min, height); max = Math.max(max, height); samples++;
            }
        }
        assertEquals(20_480, samples);
        assertEquals(6617.305740753116, min, 1e-7);
        assertEquals(7600.841056775903, max, 1e-7);
        for (boolean relief : new boolean[]{false, true}) {
            var terrain = terrain(relief);
            var far = new DhGeographicLodData(terrain, SceneCommands.START_X, SceneCommands.START_Z);
            assertEquals(originX, far.originX()); assertEquals(originZ, far.originZ());
            assertEquals(4096, far.knownCells()); assertEquals(0, far.unknownCells());
            assertEquals(4096, terrain.cachedColumns());
            assertEquals(terrain.identity(), far.identity());
            var unclipped = far.clip(List.of());
            assertTrue(unclipped.faces() >= 4096 && unclipped.faces() <= DhGeographicLodData.MAX_FACES);
            assertTrue(unclipped.tiles().size() <= DhGeographicLodData.MAX_TILES);
            System.out.println("SCENE_FAR_SOURCE relief=" + relief + " known=" + far.knownCells() + " unknown="
                    + far.unknownCells() + " derivativeReads=" + samples + " min=" + min + " max=" + max);
        }
    }

    @Test void missingSourceNeverBecomesAirOrSyntheticTerrainAndInvalidEnvelopeFailsClosed() {
        for (boolean relief : new boolean[]{false, true}) {
            var terrain = terrain(relief);
            var world = terrain.world(palette);
            assertThrows(MissingTerrainException.class, () -> terrain.sample(0, 0));
            assertThrows(MissingTerrainException.class, () -> terrain.surfaceY(0, 0));
            assertThrows(MissingTerrainException.class, () -> terrain.argb(0, 0, 0));
            assertThrows(MissingTerrainException.class, () -> terrain.snapshot(new CubePos(0, 0, 0), palette, world, 1, 1));
            assertThrows(MissingTerrainException.class, () -> SceneInterestPlan.around(terrain, 0, 36, 0));
            assertThrows(IllegalArgumentException.class, () -> new DhGeographicLodData(terrain, 0, 0));
            assertEquals(0, terrain.cachedColumns());
            for (int y : new int[]{-689, 554}) {
                var outside = new CubePos(CubePos.section(SceneCommands.START_X), y, CubePos.section(SceneCommands.START_Z));
                assertThrows(MissingTerrainException.class, () -> terrain.snapshot(outside, palette, world, 1, 1));
            }
            assertThrows(ArithmeticException.class, () -> terrain.sample(Long.MAX_VALUE, SceneCommands.START_Z));
            assertThrows(ArithmeticException.class, () -> terrain.sample(SceneCommands.START_X, Long.MIN_VALUE));
            assertEquals(0, terrain.cachedColumns());
        }
    }
}
