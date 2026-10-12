package io.earthplatform.minecraft.survival.render;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import java.util.concurrent.atomic.AtomicReference;
import io.earthplatform.minecraft.scene.SceneNeighborhood;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native registry/section/admission tests; does not claim loaded models, GL or whole-distance proof. */
class SurvivalNativeSceneTest {
    private static final CubePos CENTER = SurvivalNativeModelFixture.MEMBER;
    private static final UUID ACTOR = new UUID(3, 9);
    private static SurvivalPalette palette;
    private static HolderLookup.Provider registries;
    private static WorldIdentity world;
    @BeforeAll static void registry() {
        registries = VanillaRegistries.createLookup();
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, registries.lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = new SurvivalPalette(EnginePaletteContext.create(registry.freeze()));
        world = new WorldIdentity(39, "survival-visual-test", "039-internal", "signed-marin", palette.identity());
    }
    private static NativeSurvivalWorld owner() {
        return new NativeSurvivalWorld(world, palette,
                new NativeSurvivalWorld.Bounds(CENTER.offset(-100, -100, -100), CENTER.offset(100, 100, 100)),
                new NativeSurvivalWorld.Limits(256, 256, 4, 64, 4096, 128, 4, 1024, 8, 256));
    }
    private static void admit(NativeSurvivalWorld owner, Set<CubePos> positions) {
        for (CubePos position : positions)
            owner.admitGenerated(position, ImmutableCube.uniform(palette.id(Blocks.AIR.defaultBlockState())));
    }
    private static NativeSurvivalWorld haloOwner() {
        var owner = owner(); admit(owner, SurvivalVisualSnapshot.dependencies(Set.of(CENTER))); return owner;
    }
    /** Tests below use core-only edits. This modeled host refuses every native effect. */
    private static NativeWholeAction.Owner noEffectOwner(NativeSurvivalWorld owner) {
        return new NativeWholeAction.Owner(owner, registries, (identity, effects) -> {
            if (!effects.isEmpty()) throw new SurvivalRefusal("test core-only fixture cannot deliver native effects");
            return receipt -> {};
        });
    }
    private static SurvivalVisualSnapshot.Exporter exporter(NativeSurvivalWorld owner) {
        return new SurvivalVisualSnapshot.Exporter(owner, noEffectOwner(owner));
    }
    private static final NativeWholeAction.InventoryView EMPTY_INVENTORY = new NativeWholeAction.InventoryView() {
        public int size() { return 41; }
        public ItemStack getItem(int slot) { return ItemStack.EMPTY; }
        public int selectedSlot() { return 0; }
    };
    private static void set(NativeSurvivalWorld owner, CubeCell cell, net.minecraft.world.level.block.state.BlockState state) {
        try (var action = owner.begin(owner.actionIdentity(ACTOR, 1), Map.of(cell.cube(), owner.stamp(cell.cube())))) {
            action.set(cell, state); action.commit();
        }
    }

    @Test void signedMarinPageHas64MembersAnd216TotalDependenciesWithoutOriginRemapping() {
        assertEquals(-13640057, MarinSurvivalDefinition.ANCHOR_X);
        assertEquals(-4213188, MarinSurvivalDefinition.ANCHOR_Z);
        assertEquals(134, MarinSurvivalDefinition.GROUND_Y);
        var page = SurvivalVisualPage.containing(CENTER);
        assertEquals(64, page.members().size()); assertEquals(216, page.dependencies().size());
        assertEquals(page.dependencies(), SurvivalVisualSnapshot.dependencies(page.members()));
        assertTrue(page.members().contains(CENTER));
        assertEquals(0, Math.floorMod(page.minimum().x(), 4));
        assertEquals(0, Math.floorMod(page.minimum().z(), 4));
        assertEquals(-64, SurvivalVisualPage.containing(new CubePos(-1, -1, -1)).minimum().minBlockX());
        assertThrows(IllegalArgumentException.class, () -> new SurvivalVisualPage(new CubePos(1, 0, 0)));
        assertThrows(ArithmeticException.class, () -> SurvivalVisualPage.containing(new CubePos(Integer.MIN_VALUE, 0, 0)));
        assertThrows(UnsupportedOperationException.class, () -> page.members().clear());
    }

    @Test void snapshotPinsCompleteResidentHaloFullEpochAndCommitAndIsImmutable() {
        var owner = haloOwner();
        var cell = CubeCell.of(MarinSurvivalDefinition.ANCHOR_X, 134, MarinSurvivalDefinition.ANCHOR_Z);
        set(owner, cell, Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        var export = exporter(owner).capture(Set.of(CENTER));
        assertEquals(owner.world(), export.world()); assertEquals(owner.ownerEpoch(), export.ownerEpoch());
        assertEquals(1, export.commitSequence()); assertEquals(1, export.exportSequence());
        assertEquals(27, export.cubes().size()); assertEquals(Set.of(CENTER), export.members());
        assertEquals(owner.stamp(CENTER), export.cubes().get(CENTER).stamp());
        assertEquals(26L * 4 + 4096L * 4, export.payloadBytes());
        assertThrows(UnsupportedOperationException.class, () -> export.cubes().clear());
        assertThrows(UnsupportedOperationException.class, () -> export.members().clear());
        int[] copy = export.cubes().get(CENTER).blocks().toArray(); copy[cell.localIndex()] = palette.id(Blocks.STONE.defaultBlockState());
        assertEquals(palette.id(Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X)),
                export.cubes().get(CENTER).blocks().get(cell.localIndex()));
    }

    @Test void unknownAndRetiredDependenciesRefuseBeforeExportWithoutAirFallback() {
        var owner = owner(); var exporter = exporter(owner);
        assertThrows(SurvivalRefusal.class, () -> exporter.capture(Set.of(CENTER)));
        admit(owner, SurvivalVisualSnapshot.dependencies(Set.of(CENTER)));
        CubePos corner = CENTER.offset(-1, -1, -1); owner.retire(corner);
        assertThrows(SurvivalRefusal.class, () -> exporter.capture(Set.of(CENTER)));
        owner.reload(corner);
        assertEquals(1, exporter.capture(Set.of(CENTER)).exportSequence(), "failed capture does not consume successful export sequence");
        owner.releaseClean(corner);
        assertThrows(SurvivalRefusal.class, () -> exporter.capture(Set.of(CENTER)));
        assertEquals(26, owner.ownedCount());
    }

    @Test void activeTransactionAndOffOwnerExportCannotBypassTheSimulationReadSet() {
        var owner = haloOwner(); var exporter = exporter(owner);
        try (var action = owner.begin(owner.actionIdentity(ACTOR, 1), Map.of(CENTER, owner.stamp(CENTER)))) {
            assertThrows(SurvivalRefusal.class, () -> exporter.capture(Set.of(CENTER)));
            assertTrue(action.readSet().isEmpty());
        }
        assertInstanceOf(SurvivalRefusal.class, CompletableFuture.supplyAsync(() -> {
            try { exporter.capture(Set.of(CENTER)); return null; } catch (Throwable error) { return error; }
        }).join());
        assertEquals(1, exporter.capture(Set.of(CENTER)).exportSequence());
    }

    @Test void applyingAndAppliedButUnclosedWholeActionsCannotPublishCommittedCubes() {
        var world = haloOwner(); var exported = new AtomicReference<SurvivalVisualSnapshot.Exporter>();
        var host = new NativeWholeAction.Owner(world, registries, (identity, effects) -> {
            assertThrows(SurvivalRefusal.class, () -> exported.get().capture(Set.of(CENTER)));
            assertEquals(1, effects.size()); assertInstanceOf(NativeEffectCodec.RandomSeed.class, effects.getFirst());
            // This test performs a block-only whole action. RandomSeed is audit metadata, no live effect.
            return receipt -> {
                assertFalse(world.hasActiveTransaction());
                assertEquals(NativeWholeAction.State.APPLYING, NativeWholeAction.current().state());
                assertEquals(1, world.commitSequence());
                assertThrows(SurvivalRefusal.class, () -> exported.get().capture(Set.of(CENTER)));
            };
        });
        exported.set(new SurvivalVisualSnapshot.Exporter(world, host));
        try (var action = host.begin(world.actionIdentity(ACTOR, 1), Map.of(CENTER, world.stamp(CENTER)), EMPTY_INVENTORY)) {
            action.set(new CubeCell(CENTER, 0), Blocks.STONE.defaultBlockState());
            assertThrows(SurvivalRefusal.class, () -> exported.get().capture(Set.of(CENTER)));
            action.commit();
            assertEquals(NativeWholeAction.State.APPLIED, action.state()); assertFalse(host.fenced());
            assertThrows(SurvivalRefusal.class, () -> exported.get().capture(Set.of(CENTER)), "scope must close before publication");
        }
        var safe = exported.get().capture(Set.of(CENTER));
        assertEquals(1, safe.commitSequence()); assertEquals(1, safe.exportSequence());
        assertEquals(palette.id(Blocks.STONE.defaultBlockState()), safe.cubes().get(CENTER).blocks().get(0));
    }

    @Test void failedNativeDeliveryPermanentlyRefusesExporterAfterActionCloseAndMismatchedOwnerRefuses() {
        var world = haloOwner(); var expectedFailure = new IllegalStateException("injected apply failure");
        var host = new NativeWholeAction.Owner(world, registries, (identity, effects) -> receipt -> { throw expectedFailure; });
        var exporter = new SurvivalVisualSnapshot.Exporter(world, host);
        var before = exporter.capture(Set.of(CENTER));
        try (var action = host.begin(world.actionIdentity(ACTOR, 1), Map.of(CENTER, world.stamp(CENTER)), EMPTY_INVENTORY)) {
            action.set(new CubeCell(CENTER, 0), Blocks.OAK_PLANKS.defaultBlockState());
            assertSame(expectedFailure, assertThrows(SurvivalRefusal.class, action::commit).getCause());
            assertThrows(SurvivalRefusal.class, () -> exporter.capture(Set.of(CENTER)));
        }
        assertFalse(host.hasCurrentAction()); assertFalse(world.hasActiveTransaction()); assertTrue(host.fenced());
        assertEquals(1, world.commitSequence()); assertEquals(0, before.commitSequence());
        assertThrows(SurvivalRefusal.class, () -> exporter.capture(Set.of(CENTER)));
        assertThrows(SurvivalRefusal.class, () -> new SurvivalVisualSnapshot.Exporter(world, host));
        var anotherWorld = haloOwner();
        assertThrows(SurvivalRefusal.class, () -> new SurvivalVisualSnapshot.Exporter(world, noEffectOwner(anotherWorld)));
    }

    @Test void dependencyUnionIsBoundedBeforePayloadOrResidencyAndFullPageFitsExistingGuards() {
        var owner = owner(); var exporter = exporter(owner);
        var separated = new TreeSet<CubePos>();
        for (int i = 0; i < 9; i++) separated.add(CENTER.offset(i * 4, 0, 0));
        var failure = assertThrows(SurvivalRefusal.class, () -> exporter.capture(separated));
        assertTrue(failure.getMessage().contains("216")); assertEquals(0, owner.ownedCount());
        assertThrows(SurvivalRefusal.class, () -> exporter.capture(Set.of()));
        assertThrows(SurvivalRefusal.class, () -> exporter.capture(Set.of(new CubePos(0, -688, 0))));
        var page = SurvivalVisualPage.containing(CENTER); admit(owner, page.dependencies());
        var snapshot = exporter.capture(page);
        try (var scene = new SurvivalNativeScene(snapshot, palette)) {
            assertEquals(64, snapshot.members().size()); assertEquals(216, scene.current().neighborhood().size());
            assertEquals(27, scene.dependencyJobs(CENTER).size());
            assertEquals(28, io.earthplatform.minecraft.renderprobe.RenderPublicationGuard.MAX_PRESENTED_MESHES);
            assertEquals(256, SceneNeighborhood.MAX_OWNERS);
        }
    }

    @Test void exactNativeAxisLeafPropertiesAndAllNineBlockFamiliesSurviveTheAdapter() {
        var source = new SurvivalNativeModelFixture.Source(palette, registries); var snapshot = source.capture();
        try (var scene = new SurvivalNativeScene(snapshot, palette)) {
            var cube = scene.current().member(CENTER); var input = snapshot.cubes().get(CENTER).blocks();
            var families = new java.util.HashSet<net.minecraft.world.level.block.Block>();
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var exact = palette.state(input.get(x, y, z)); assertSame(exact, cube.state(x, y, z));
                families.add(exact.getBlock());
            }
            assertEquals(9, families.size());
            assertThrows(SparseFacadeException.class, () -> new SceneCube(cube.snapshot(), palette.context()),
                    "Explicit survival admission must not widen the historical default whitelist");
            var p = new BlockPos(Math.toIntExact(MarinSurvivalDefinition.ANCHOR_X), 134,
                    Math.toIntExact(MarinSurvivalDefinition.ANCHOR_Z));
            assertSame(Blocks.GRASS_BLOCK.defaultBlockState(), scene.current().neighborhood().getBlockState(p));
            assertThrows(SparseFacadeException.class, () -> scene.current().neighborhood().getBlockState(p.offset(0, 4096, 0)));
        }
    }

    @Test void unsupportedFallingBlocksEntitiesAndWaterloggedLeavesRemainRefused() {
        for (var state : java.util.List.of(Blocks.GRAVEL.defaultBlockState(), Blocks.SAND.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)))
            assertThrows(SurvivalRefusal.class, () -> palette.id(state));
    }

    @Test void crossBoundaryEditInvalidatesOldDependencyJobsButKeepsUnchangedMemberSnapshot() {
        var source = new SurvivalNativeModelFixture.Source(palette, registries);
        var first = source.capture();
        try (var scene = new SurvivalNativeScene(first, palette)) {
            var old = scene.current(); var jobs = scene.dependencyJobs(CENTER);
            var memberJob = scene.guard().prepare(old.member(CENTER).snapshot());
            var guard = scene.guard(); assertTrue(guard.drawIfCurrent(jobs, () -> true));
            var changed = source.removeBoundaryOccluder(); scene.install(changed);
            assertEquals(1, changed.commitSequence());
            assertSame(old.member(CENTER).snapshot(), scene.current().member(CENTER).snapshot());
            assertTrue(guard.isCurrent(memberJob));
            assertFalse(guard.drawIfCurrent(jobs, () -> fail("Stale halo must refuse before draw callback")));
            assertTrue(guard.drawIfCurrent(scene.dependencyJobs(CENTER), () -> true));
            var cell = SurvivalNativeModelFixture.BOUNDARY_OCCLUDER;
            var position = new BlockPos(Math.toIntExact(cell.x()), Math.toIntExact(cell.y()), Math.toIntExact(cell.z()));
            assertSame(Blocks.STONE.defaultBlockState(), old.neighborhood().getBlockState(position));
            assertSame(Blocks.AIR.defaultBlockState(), scene.current().neighborhood().getBlockState(position));
            assertThrows(SurvivalRefusal.class, () -> scene.install(first));
        }
    }

    @Test void reincarnationAtSameCommitAndSimulationRevisionGetsANewLease() {
        var owner = haloOwner(); var exporter = exporter(owner); var first = exporter.capture(Set.of(CENTER));
        try (var scene = new SurvivalNativeScene(first, palette)) {
            var oldCube = scene.current().member(CENTER); var oldJob = scene.guard().prepare(oldCube.snapshot());
            owner.releaseClean(CENTER); owner.admitGenerated(CENTER, ImmutableCube.uniform(palette.id(Blocks.STONE.defaultBlockState())));
            var second = exporter.capture(Set.of(CENTER)); scene.install(second);
            assertEquals(first.commitSequence(), second.commitSequence());
            assertEquals(first.cubes().get(CENTER).simulationRevision(), second.cubes().get(CENTER).simulationRevision());
            assertNotEquals(first.cubes().get(CENTER).incarnation(), second.cubes().get(CENTER).incarnation());
            assertNotEquals(oldCube.snapshot().leaseGeneration(), scene.current().member(CENTER).snapshot().leaseGeneration());
            assertFalse(scene.guard().isCurrent(oldJob));
        }
    }

    @Test void exportOrderingRejectsReplayAndWrongStreamOrOwnerWithoutMutatingCurrentScene() {
        var owner = haloOwner(); var exporter = exporter(owner);
        var first = exporter.capture(Set.of(CENTER)); var second = exporter.capture(Set.of(CENTER)); var third = exporter.capture(Set.of(CENTER));
        try (var scene = new SurvivalNativeScene(first, palette)) {
            var original = scene.current();
            assertThrows(SurvivalRefusal.class, () -> scene.install(first)); assertSame(original, scene.current());
            assertThrows(SurvivalRefusal.class, () -> scene.install(exporter(owner).capture(Set.of(CENTER))));
            assertThrows(SurvivalRefusal.class, () -> scene.install(exporter(haloOwner()).capture(Set.of(CENTER))));
            scene.install(third);
            assertThrows(SurvivalRefusal.class, () -> scene.install(second)); assertSame(third, scene.current().source());
        }
    }

    @Test void rawEngineSnapshotsCannotCrossSceneLifetimesEvenWhenOwnerWorldAndLeaseNumbersRepeat() {
        var owner = haloOwner(); var snapshot = exporter(owner).capture(Set.of(CENTER));
        io.earthplatform.minecraft.network.EngineCubeSnapshot old;
        try (var first = new SurvivalNativeScene(snapshot, palette)) {
            old = first.current().member(CENTER).snapshot();
        }
        try (var replacement = new SurvivalNativeScene(snapshot, palette)) {
            var fresh = replacement.current().member(CENTER).snapshot();
            assertEquals(old.world(), fresh.world()); assertEquals(old.leaseGeneration(), fresh.leaseGeneration());
            assertEquals(old.revision(), fresh.revision()); assertNotEquals(old.epoch(), fresh.epoch());
            assertThrows(IllegalStateException.class, () -> replacement.guard().prepare(old));
            assertTrue(replacement.guard().isCurrent(replacement.guard().prepare(fresh)));
            assertEquals(snapshot.ownerEpoch(), replacement.ownerEpoch());
            assertEquals(snapshot.exportStream(), replacement.exportStream());
        }
    }

    @Test void resourceGenerationAndImmediateReloadRequestBothFenceOldWorkBeforeCallback() {
        var owner = haloOwner(); var snapshot = exporter(owner).capture(Set.of(CENTER));
        try (var scene = new SurvivalNativeScene(snapshot, palette)) {
            var guard = scene.guard(); var jobs = scene.dependencyJobs(CENTER);
            scene.invalidateResources();
            assertFalse(guard.drawIfCurrent(jobs, () -> fail("Resource-stale work must not draw")));
            assertTrue(guard.drawIfCurrent(scene.dependencyJobs(CENTER), () -> true));
            CompletableFuture.runAsync(scene::requestResourceInvalidation).join();
            assertTrue(guard.invalidationRequested());
            assertThrows(IllegalStateException.class, () -> scene.dependencyJobs(CENTER));
            assertFalse(guard.drawIfCurrent(jobs, () -> fail("Reload fence must not draw")));
        }
    }

    @Test void immutableExportIsUsableOnAnotherClientThreadWithoutLiveServerReads() {
        var owner = haloOwner(); var exporter = exporter(owner); var exported = exporter.capture(Set.of(CENTER));
        owner.retire(CENTER);
        assertTrue(CompletableFuture.supplyAsync(() -> {
            try (var scene = new SurvivalNativeScene(exported, palette)) {
                return scene.guard().drawIfCurrent(scene.dependencyJobs(CENTER), () -> true);
            }
        }).join());
        // Cross-thread snapshots are immutable values, not evidence of the server's latest residency.
        assertThrows(SurvivalRefusal.class, () -> exporter.capture(Set.of(CENTER)));
        for (var field : SurvivalNativeScene.class.getDeclaredFields())
            assertNotEquals(NativeSurvivalWorld.class, field.getType());
    }

    @Test void sceneThreadOwnershipCloseAndOutdoorLightingRemainExplicit() {
        var owner = haloOwner(); var snapshot = exporter(owner).capture(Set.of(CENTER));
        var scene = new SurvivalNativeScene(snapshot, palette); var guard = scene.guard(); var jobs = scene.dependencyJobs(CENTER);
        assertInstanceOf(SurvivalRefusal.class, CompletableFuture.supplyAsync(() -> {
            try { scene.current(); return null; } catch (Throwable error) { return error; }
        }).join());
        var pos = new BlockPos(Math.toIntExact(CENTER.minBlockX()), Math.toIntExact(CENTER.minBlockY()), Math.toIntExact(CENTER.minBlockZ()));
        var neighborhood = scene.current().neighborhood();
        assertEquals(SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE, neighborhood.lightingPolicy());
        assertEquals(0, neighborhood.getBrightness(LightLayer.BLOCK, pos));
        assertEquals(15, neighborhood.getBrightness(LightLayer.SKY, pos));
        assertThrows(SparseFacadeException.class, neighborhood::getLightEngine);
        assertThrows(SurvivalRefusal.class, () -> scene.current().member(CENTER.offset(1, 0, 0)));
        scene.close(); scene.close(); assertFalse(guard.drawIfCurrent(jobs, () -> fail("Closed scene must not draw")));
        assertThrows(SurvivalRefusal.class, scene::current);
    }
}
