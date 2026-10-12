package io.earthplatform.minecraft.scene;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real native getters/materializations, without a renderer singleton, model callback or GL context. */
class SceneNeighborhoodReadFootprintTest {
    private static final WorldIdentity WORLD = new WorldIdentity(0, "footprint", "1", "real-elevation", "native-palette");
    private static EnginePaletteContext palette;
    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }
    private static EngineCubeSection section() { return EngineCubeSection.uniform(palette, Blocks.STONE.defaultBlockState()); }
    private static SceneCube cube(CubePos pos, long revision, EngineCubeSection section) {
        return new SceneCube(EngineCubeSnapshot.capture(pos, WORLD, 1, 1, revision, section), palette);
    }
    private static SceneNeighborhood neighborhood(SceneCube cube) { return new SceneNeighborhood(Map.of(cube.snapshot().position(), cube)); }

    @Test void unseenInteriorEditAcceptsButSeenBoundaryStateChangeRejects() {
        var position = new CubePos(1, 0, 0);
        var source = neighborhood(cube(position, 1, section()));
        var recorder = new SceneReadFootprint.Recorder();
        var reads = source.tracking(ignored -> {}, recorder);
        assertSame(Blocks.STONE.defaultBlockState(), reads.getBlockState(new BlockPos(16, 8, 8)));
        var footprint = recorder.seal();
        var interior = section(); interior.set(8, 8, 8, Blocks.DIRT.defaultBlockState());
        var next = neighborhood(cube(position, 2, interior));
        var accepted = footprint.validate(Set.of(position), next::replay);
        assertTrue(accepted.matches()); assertEquals(1, accepted.compared());
        var boundary = section(); boundary.set(0, 8, 8, Blocks.DIRT.defaultBlockState());
        assertFalse(footprint.validate(Set.of(position), neighborhood(cube(position, 3, boundary))::replay).matches());
        assertSame(Blocks.STONE.defaultBlockState(), source.getBlockState(new BlockPos(24, 8, 8)));
    }
    @Test void exactNativeStatePropertiesMatter() {
        var position = new CubePos(0, 0, 0);
        var first = section(); first.set(1, 1, 1, Blocks.GRASS_BLOCK.defaultBlockState().setValue(SnowyDirtBlock.SNOWY, false));
        var second = section(); second.set(1, 1, 1, Blocks.GRASS_BLOCK.defaultBlockState().setValue(SnowyDirtBlock.SNOWY, true));
        var recorder = new SceneReadFootprint.Recorder();
        neighborhood(cube(position, 1, first)).tracking(ignored -> {}, recorder).getBlockState(new BlockPos(1, 1, 1));
        assertFalse(recorder.seal().validate(Set.of(position), neighborhood(cube(position, 2, second))::replay).matches());
    }
    @Test void allNativeAndNeoForgeGetterKindsRecordAndReplayWithoutResolverCallbacks() {
        var position = new CubePos(0, 0, 0);
        var source = neighborhood(cube(position, 1, section()));
        var recorder = new SceneReadFootprint.Recorder();
        var dependencies = new AtomicInteger();
        var reads = source.tracking(ignored -> dependencies.incrementAndGet(), recorder);
        var pos = new BlockPos(1, 2, 3);
        reads.getBlockState(pos); reads.getFluidState(pos); reads.getBlockEntity(pos);
        assertSame(ModelData.EMPTY, reads.getModelData(pos));
        reads.getBrightness(LightLayer.SKY, pos); reads.getBrightness(LightLayer.BLOCK, pos);
        reads.getRawBrightness(pos, -100); reads.getRawBrightness(pos, 4); reads.getRawBrightness(pos, 100);
        reads.getBlockTint(pos, (biome, x, z) -> fail("A cosmetic facade never invokes arbitrary color callbacks"));
        reads.getBlockTint(pos, (biome, x, z) -> fail("All non-null resolver inputs are contractually equivalent"));
        reads.getHeight(); reads.getMinBuildHeight(); reads.getMaxBuildHeight(); reads.getMaxLightLevel();
        for (var direction : Direction.values()) { reads.getShade(direction, true); reads.getShade(direction, false); }
        reads.getShade(0.3f, 0.2f, 0.9f, true); reads.getShade(-0.0f, 1, 0, false);
        var footprint = recorder.seal();
        assertEquals(27, footprint.readCount());
        assertEquals(1, footprint.positionCount()); assertEquals(1, footprint.ownerCount());
        assertEquals(11, dependencies.get());
        var pass = footprint.validate(Set.of(position), source::replay);
        assertTrue(pass.matches()); assertEquals(footprint.readCount(), pass.compared());
    }
    @Test void fullSignedPositionsAndMutablePositionsAreCapturedWithoutAliases() {
        var low = new CubePos(-1, -688, -1); var high = new CubePos(-1, -432, -1);
        var source = new SceneNeighborhood(Map.of(low, cube(low, 1, section()), high, cube(high, 1, section())));
        var recorder = new SceneReadFootprint.Recorder(); var reads = source.tracking(ignored -> {}, recorder);
        var pos = new BlockPos.MutableBlockPos(-1, -11000, -1);
        reads.getBlockState(pos); pos.set(-1, -6904, -1); reads.getBlockState(pos); pos.set(200, 200, 200);
        var footprint = recorder.seal(); assertEquals(2, footprint.positionCount()); assertEquals(2, footprint.ownerCount());
        assertTrue(footprint.validate(Set.of(low, high), source::replay).matches());
    }
    @Test void everyPositionalGetterRejectsUnknownAndPoisonCannotBeCaughtAway() {
        var source = neighborhood(cube(new CubePos(0, 0, 0), 1, section()));
        var unknown = new BlockPos(16, 1, 1);
        List<java.util.function.Consumer<SceneNeighborhood>> getters = List.of(
                n -> n.getBlockState(unknown), n -> n.getFluidState(unknown), n -> n.getBlockEntity(unknown),
                n -> n.getModelData(unknown), n -> n.getBrightness(LightLayer.SKY, unknown),
                n -> n.getRawBrightness(unknown, 0), n -> n.getBlockTint(unknown, (biome, x, z) -> 0));
        for (var getter : getters) {
            var recorder = new SceneReadFootprint.Recorder();
            assertThrows(SparseFacadeException.class, () -> getter.accept(source.tracking(ignored -> {}, recorder)));
            assertThrows(IllegalStateException.class, recorder::seal);
        }
    }
    @Test void unsupportedAuxiliaryLightingAndInvalidInputsPoisonCompilation() {
        var source = neighborhood(cube(new CubePos(0, 0, 0), 1, section()));
        var pos = new BlockPos(1, 1, 1);
        List<java.util.function.Consumer<SceneNeighborhood>> getters = List.of(
                SceneNeighborhood::getLightEngine, n -> n.getAuxLightManager(new ChunkPos(0, 0)),
                n -> n.getAuxLightManager(pos), n -> n.getBlockState(null), n -> n.getBrightness(null, pos),
                n -> n.getBlockTint(pos, null), n -> n.getShade(null, false),
                n -> n.getShade(Float.NaN, 0, 0, true), SceneNeighborhood::size,
                n -> n.contains(new CubePos(0, 0, 0)));
        for (var getter : getters) {
            var recorder = new SceneReadFootprint.Recorder();
            assertThrows(SparseFacadeException.class, () -> getter.accept(source.tracking(ignored -> {}, recorder)));
            assertThrows(IllegalStateException.class, recorder::seal);
        }
    }
    @Test void envelopeIsAnObservedGlobalInputEvenWhenNoBlocksAreRead() {
        var cube = cube(new CubePos(0, 0, 0), 1, section());
        var nativeSource = neighborhood(cube);
        var padding = new SceneNeighborhood(Map.of(cube.snapshot().position(), cube), SceneRenderEnvelope.PLAYER_DIAGNOSTIC_PADDING);
        var recorder = new SceneReadFootprint.Recorder();
        var reads = nativeSource.tracking(ignored -> {}, recorder); reads.getHeight(); reads.getMinBuildHeight();
        assertFalse(recorder.seal().validate(Set.of(), padding::replay).matches());
    }
    @Test void knownFarawayReadsAreRejectedBeforeDependencyCaptureAndCaughtErrorsPoisonTheBuild() {
        var rendered = new CubePos(-20, -688, -30);
        var far = rendered.offset(2, 0, 0);
        var source = new SceneNeighborhood(Map.of(rendered, cube(rendered, 1, section()), far, cube(far, 1, section())));
        var recorder = new SceneReadFootprint.Recorder();
        var captures = new AtomicInteger();
        var reads = source.tracking(dependency -> {
            SceneReadFootprint.requireHaloOwner(rendered, dependency.snapshot().position());
            captures.incrementAndGet();
        }, recorder);
        var knownFar = new BlockPos(Math.toIntExact(far.minBlockX()), Math.toIntExact(far.minBlockY()), Math.toIntExact(far.minBlockZ()));
        assertSame(Blocks.STONE.defaultBlockState(), source.getBlockState(knownFar));
        assertThrows(SparseFacadeException.class, () -> reads.getBlockState(knownFar));
        assertEquals(0, captures.get(), "Unsupported sources cannot become retained dependency Jobs");
        assertThrows(IllegalStateException.class, recorder::requireValid, "Catching the facade failure cannot rescue compilation");
        assertThrows(IllegalStateException.class, recorder::seal);
    }
    @Test void laterSliceCannotPrepareAnOldSourceSnapshotAfterARevisionArrives() {
        var primary = new CubePos(0, 0, 0); var neighbor = new CubePos(1, 0, 0);
        var admission = new CubeStreamAdmission(WORLD, 1, 2);
        var leases = admission.replaceInterest(List.of(primary, neighbor)).requested();
        Map<CubePos, SceneCube> cubes = new HashMap<>();
        for (var lease : leases) {
            var snapshot = EngineCubeSnapshot.capture(lease.position(), WORLD, 1, lease.generation(), 1, section());
            commit(admission, snapshot); cubes.put(lease.position(), new SceneCube(snapshot, palette));
        }
        var source = new SceneNeighborhood(cubes);
        try (var guard = new RenderPublicationGuard(admission)) {
            var recorder = new SceneReadFootprint.Recorder();
            var captured = new HashMap<CubePos, RenderPublicationGuard.Job>();
            var reads = source.tracking(cube -> {
                var job = captured.computeIfAbsent(cube.snapshot().position(), ignored -> guard.prepare(cube.snapshot()));
                if (!guard.isCurrent(job)) throw new IllegalStateException("Stale actual read");
            }, recorder);
            reads.getBlockState(new BlockPos(0, 0, 0));
            assertEquals(Set.of(primary), captured.keySet(), "Unqueried halo owners must not be captured eagerly");
            var oldNeighbor = cubes.get(neighbor).snapshot();
            var replacement = EngineCubeSnapshot.capture(neighbor, WORLD, 1, oldNeighbor.leaseGeneration(), 2, section());
            commit(admission, replacement);
            assertThrows(IllegalStateException.class, () -> reads.getBlockState(new BlockPos(16, 0, 0)));
            assertThrows(IllegalStateException.class, recorder::seal);
            assertFalse(captured.containsKey(neighbor));
        }
    }
    private static void commit(CubeStreamAdmission gate, EngineCubeSnapshot snapshot) {
        var ticket = gate.admit(new CubeStreamAdmission.Header(snapshot.world(), snapshot.epoch(), snapshot.position(),
                snapshot.leaseGeneration(), snapshot.revision()));
        assertTrue(ticket.accepted());
        assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(ticket.token(), () -> {}));
    }
}
