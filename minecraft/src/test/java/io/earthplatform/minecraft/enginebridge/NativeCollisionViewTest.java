package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.enginebridge.NativeCollisionView.BoundaryReason.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCollisionViewTest {
    private static EnginePaletteContext palette;
    private static WorldIdentity world;
    private static final long EPOCH = 21;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
        world = new WorldIdentity(73, "native-collision-test", "full-3d-v1", "fixture-v1", NativeCubeCodec.paletteIdentity(palette));
    }

    private static EngineCubeSnapshot snapshot(CubePos pos, EngineCubeSection section, long revision) {
        return EngineCubeSnapshot.capture(pos, world, EPOCH, 9, revision, section);
    }
    private static EngineCubeSnapshot uniform(CubePos pos, BlockState state) {
        return snapshot(pos, EngineCubeSection.uniform(palette, state), 0);
    }
    private static Map<CubePos, EngineCubeSnapshot> map(EngineCubeSnapshot... snapshots) {
        var result = new HashMap<CubePos, EngineCubeSnapshot>();
        for (var snapshot : snapshots) result.put(snapshot.position(), snapshot);
        return result;
    }
    private static NativeCollisionView view(EngineCubeSnapshot... snapshots) {
        return new NativeCollisionView(world, EPOCH, palette, map(snapshots), () -> true);
    }
    private static EngineCubeSnapshot cell(int x, int y, int z, BlockState state) {
        EngineCubeSection section = new EngineCubeSection(palette);
        section.set(CubePos.local(x), CubePos.local(y), CubePos.local(z), state);
        return snapshot(CubePos.fromBlock(x, y, z), section, 0);
    }

    /** A test-only native CollisionGetter; all geometry comes from the production sparse getter. */
    private static final class NativeAccess implements CollisionGetter {
        private final NativeCollisionView view;
        private final WorldBorder border = new WorldBorder();
        private int chunkRequests;
        private NativeAccess(NativeCollisionView view) { this.view = view; }
        @Override public BlockGetter getChunkForCollisions(int x, int z) { chunkRequests++; return view; }
        @Override public BlockState getBlockState(BlockPos pos) { return view.getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return view.getFluidState(pos); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return view.getBlockEntity(pos); }
        @Override public int getHeight() { return view.getHeight(); }
        @Override public int getMinBuildHeight() { return view.getMinBuildHeight(); }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) { return List.of(); }
    }
    private static List<VoxelShape> shapes(NativeAccess access, AABB box) {
        var result = new ArrayList<VoxelShape>();
        // The actual pinned vanilla cursor, chunk lookup, state/shape reads and overlap decisions.
        new BlockCollisions<>(access, null, box, false, (pos, shape) -> shape).forEachRemaining(result::add);
        assertTrue(access.chunkRequests > 0);
        return result;
    }
    private static double move(NativeAccess access, AABB box, Direction.Axis axis, double distance) {
        AABB swept = box.expandTowards(axis == Direction.Axis.X ? distance : 0,
                axis == Direction.Axis.Y ? distance : 0, axis == Direction.Axis.Z ? distance : 0);
        // Native Entity.collideWithShapes calls this exact solver for each movement axis.
        return Shapes.collide(axis, box, shapes(access, swept), distance);
    }

    @Test void vanillaIteratorAndSolverStandFallJumpAndStopAtWall() {
        var section = new EngineCubeSection(palette);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) section.set(x, 0, z, Blocks.STONE.defaultBlockState());
        for (int y = 1; y < 5; y++) section.set(9, y, 8, Blocks.DIORITE.defaultBlockState());
        var view = view(snapshot(new CubePos(0, 0, 0), section, 0));
        var access = new NativeAccess(view);
        AABB standing = new AABB(8.2, 1, 8.2, 8.8, 2.8, 8.8);
        assertEquals(0, move(access, standing, Direction.Axis.Y, -0.08), 1e-12);
        assertEquals(-3, move(access, standing.move(0, 3, 0), Direction.Axis.Y, -6), 1e-12);
        assertEquals(0.42, move(access, standing, Direction.Axis.Y, 0.42), 1e-12);
        assertEquals(0.2, move(access, standing, Direction.Axis.X, 1), 1e-12);
        assertTrue(access.noCollision(standing));
        assertFalse(access.noCollision(standing.move(0, -0.1, 0)));
        assertTrue(view.counters().knownAir() > 0);
        assertTrue(view.counters().knownSolid() > 0);
        assertTrue(view.isCurrent());
    }

    @Test void actualThreeDimensionalProviderHasTraversableVoidAndNativeOverhangCeiling() {
        var provider = new AnalyticNativeCubeProvider(73, AnalyticNativeCubeProvider.Config.overhang(0), palette);
        var codec = new NativeCubeCodec(provider.identity(), palette);
        var pos = new CubePos(0, 0, 0);
        var snapshot = codec.materialize(pos, provider.load(pos, new CancellationToken()).join(), EPOCH, 9, 0);
        var view = new NativeCollisionView(provider.identity(), EPOCH, palette, Map.of(pos, snapshot), () -> true);
        var access = new NativeAccess(view);
        AABB below = new AABB(5.2, 3, 5.2, 5.8, 4.8, 5.8);
        assertTrue(access.noCollision(below));
        assertEquals(2, move(access, below, Direction.Axis.X, 2), 1e-12);
        assertEquals(1.2, move(access, below, Direction.Axis.Y, 3), 1e-12);
        var roofShapes = shapes(access, below.expandTowards(0, 3, 0));
        assertTrue(roofShapes.stream().anyMatch(shape -> shape.bounds().minY == 6));
        assertEquals(0, view.counters().boundaryReads());
    }

    @Test void knownAirRemainsPassableWhileMissingDataBlocksAtTheOwnerBoundary() {
        var known = uniform(new CubePos(0, 0, 0), Blocks.AIR.defaultBlockState());
        var view = view(known);
        var access = new NativeAccess(view);
        var box = new AABB(14.2, 5, 7.2, 14.8, 6.8, 7.8);
        assertEquals(0.5, move(access, box, Direction.Axis.X, 0.5), 1e-12);
        assertEquals(1.2, move(access, box, Direction.Axis.X, 3), 1e-12);
        var read = view.read(new BlockPos(16, 5, 7));
        assertSame(Blocks.BARRIER.defaultBlockState(), read.state());
        assertFalse(read.known());
        assertEquals(MISSING_CUBE, read.boundary().reason());
        assertEquals(new CubePos(1, 0, 0), read.boundary().position());
        assertTrue(view.counters().reasons().get(MISSING_CUBE) > 0);
        assertEquals(1, view.residentOwners());
        assertTrue(view.isCurrent());
        assertSame(Blocks.AIR.defaultBlockState(), known.materialize(palette).get(15, 5, 7));
    }

    @Test void negativeCubeBoundaryPreservesEveryAxisAndNativeContactPrecision() {
        for (Direction.Axis axis : Direction.Axis.values()) {
            Map<CubePos, EngineCubeSnapshot> snapshots = new HashMap<>();
            for (int x = -1; x <= 0; x++) for (int y = -1; y <= 0; y++) for (int z = -1; z <= 0; z++) {
                var pos = new CubePos(x, y, z);
                snapshots.put(pos, uniform(pos, Blocks.AIR.defaultBlockState()));
            }
            var wall = cell(-1, -1, -1, Blocks.GOLD_BLOCK.defaultBlockState());
            snapshots.put(wall.position(), wall);
            var view = new NativeCollisionView(world, EPOCH, palette, snapshots, () -> true);
            var access = new NativeAccess(view);
            AABB box = switch (axis) {
                case X -> new AABB(0.2, -0.9, -0.9, 0.8, -0.1, -0.1);
                case Y -> new AABB(-0.9, 0.2, -0.9, -0.1, 2, -0.1);
                case Z -> new AABB(-0.9, -0.9, 0.2, -0.1, -0.1, 0.8);
            };
            assertEquals(-0.2, move(access, box, axis, -2), 1e-12);
            assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), view.getBlockState(new BlockPos(-1, -1, -1)));
            assertSame(Blocks.AIR.defaultBlockState(), view.getBlockState(new BlockPos(0, 0, 0)));
        }
    }

    @Test void fullNativeYAndLargeXZUseExactDoubleWorldCoordinatesWithoutPackedBlockPos() {
        for (int y : new int[]{-11008, -11000, 8849, 8863}) {
            int x = 19876543, z = -19876543;
            var target = cell(x, y, z, Blocks.STONE.defaultBlockState());
            var snapshots = map(target);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                var pos = target.position().offset(dx, dy, dz);
                snapshots.putIfAbsent(pos, uniform(pos, Blocks.AIR.defaultBlockState()));
            }
            var view = new NativeCollisionView(world, EPOCH, palette, snapshots, () -> true);
            var access = new NativeAccess(view);
            assertSame(Blocks.STONE.defaultBlockState(), view.getBlockState(new BlockPos(x, y, z)));
            AABB side = new AABB(x - 0.875, y + 0.125, z + 0.125, x - 0.125, y + 0.875, z + 0.875);
            assertEquals(0.125, move(access, side, Direction.Axis.X, 1.5), 1e-12);
            if (y < 8863) {
                AABB above = new AABB(x + 0.125, y + 2.75, z + 0.125, x + 0.875, y + 4.55, z + 0.875);
                assertEquals(-1.75, move(access, above, Direction.Axis.Y, -3), 1e-12);
            }
        }
    }

    @Test void nativeDomainEdgesAreExplicitVirtualWallsEvenIfSnapshotExistsOutside() {
        var snapshots = new HashMap<CubePos, EngineCubeSnapshot>();
        for (BlockPos pos : List.of(new BlockPos(0, -11009, 0), new BlockPos(0, 8864, 0))) {
            CubePos cube = CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ());
            snapshots.put(cube, uniform(cube, Blocks.AIR.defaultBlockState()));
        }
        var view = new NativeCollisionView(world, EPOCH, palette, snapshots, () -> true);
        for (BlockPos pos : List.of(new BlockPos(0, -11009, 0), new BlockPos(0, 8864, 0),
                new BlockPos(-33554433, 0, 0), new BlockPos(33554432, 0, 0),
                new BlockPos(0, 0, -33554433), new BlockPos(0, 0, 33554432),
                new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE))) {
            var read = view.read(pos);
            assertSame(Blocks.BARRIER.defaultBlockState(), read.state());
            assertEquals(OUTSIDE_DOMAIN, read.boundary().reason());
        }
        assertEquals(-11008, view.getMinBuildHeight());
        assertEquals(19872, view.getHeight());
    }

    @Test void unsupportedStatesInvalidateTheirWholeOwnerIncludingFenceProtrusionSpace() {
        for (var state : List.of(Blocks.WATER.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState(), Blocks.CHEST.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState())) {
            var snapshot = cell(4, 4, 4, state);
            var view = view(snapshot);
            var read = view.read(new BlockPos(4, 5, 4));
            assertEquals(UNSUPPORTED_STATE, read.boundary().reason());
            assertSame(Blocks.BARRIER.defaultBlockState(), read.state());
            assertFalse(new NativeAccess(view).noCollision(new AABB(4.3, 5.1, 4.3, 4.7, 6.9, 4.7)));
            assertSame(state, snapshot.materialize(palette).get(4, 4, 4));
            assertNull(view.getBlockEntity(new BlockPos(4, 4, 4)));
            assertTrue(view.getFluidState(new BlockPos(4, 4, 4)).isEmpty());
        }
    }

    @Test void unsupportedFenceHaloAcrossAnOwnerSeamCannotBecomePassableAir() {
        var fence = cell(4, 15, 4, Blocks.OAK_FENCE.defaultBlockState());
        var above = uniform(new CubePos(0, 1, 0), Blocks.AIR.defaultBlockState());
        var view = view(fence, above);
        var read = view.read(new BlockPos(4, 16, 4));
        assertEquals(UNSUPPORTED_STATE, read.boundary().reason());
        assertEquals(fence.position(), read.boundary().position());
        assertFalse(new NativeAccess(view).noCollision(new AABB(4.3, 16.1, 4.3, 4.7, 17.9, 4.7)));
        assertTrue(view.read(new BlockPos(4, 17, 4)).known());
        assertSame(Blocks.AIR.defaultBlockState(), above.materialize(palette).get(4, 0, 4));
    }

    @Test void allAuditedNativeMaterialsAndAirVariantsPassWhileRealBarrierIsUnsupported() {
        var pos = new CubePos(0, 0, 0);
        for (var block : List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.STONE,
                Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.GRAVEL, Blocks.SNOW_BLOCK, Blocks.ANDESITE,
                Blocks.DIORITE, Blocks.TUFF, Blocks.CYAN_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
                Blocks.GOLD_BLOCK, Blocks.BEDROCK)) {
            var view = view(uniform(pos, block.defaultBlockState()));
            var read = view.read(new BlockPos(4, 4, 4));
            assertTrue(read.known(), block.toString());
            assertSame(block.defaultBlockState(), read.state());
        }
        var realBarrier = view(uniform(pos, Blocks.BARRIER.defaultBlockState())).read(new BlockPos(4, 4, 4));
        assertFalse(realBarrier.known());
        assertEquals(UNSUPPORTED_STATE, realBarrier.boundary().reason());
    }

    @Test void missingVerticalAndDepthOwnersStopNativeMovementWithFiniteWork() {
        var view = view(uniform(new CubePos(0, 0, 0), Blocks.AIR.defaultBlockState()));
        var access = new NativeAccess(view);
        assertEquals(0.2, move(access, new AABB(4.2, 14, 4.2, 4.8, 15.8, 4.8), Direction.Axis.Y, 1), 1e-12);
        assertEquals(-0.2, move(access, new AABB(4.2, 0.2, 4.2, 4.8, 2, 4.8), Direction.Axis.Y, -1), 1e-12);
        assertEquals(0.2, move(access, new AABB(4.2, 4, 15.2, 4.8, 5.8, 15.8), Direction.Axis.Z, 1), 1e-12);
        assertTrue(view.counters().reads() < 1000);
        assertEquals(1, view.residentOwners());
    }

    @Test void immutableInputsAndMixedRevisionReplacementFailClosedAcrossAllOwners() {
        var first = uniform(new CubePos(0, 0, 0), Blocks.AIR.defaultBlockState());
        var second = uniform(new CubePos(1, 0, 0), Blocks.STONE.defaultBlockState());
        var inputs = map(first, second);
        var current = new HashMap<CubePos, NativeKnownCubeQueries.CubeStamp>();
        inputs.values().forEach(snapshot -> current.put(snapshot.position(), NativeKnownCubeQueries.CubeStamp.of(snapshot)));
        var view = new NativeCollisionView(world, EPOCH, palette, inputs, stamp -> stamp.equals(current.get(stamp.position())));
        inputs.clear();
        assertEquals(2, view.residentOwners());
        assertTrue(view.read(new BlockPos(4, 4, 4)).known());
        var old = NativeKnownCubeQueries.CubeStamp.of(second);
        current.put(old.position(), new NativeKnownCubeQueries.CubeStamp(world, EPOCH, old.position(), 9, 1));
        // Reading unchanged air must not silently use part of a mixed-revision old view.
        assertEquals(STALE_DEPENDENCY, view.read(new BlockPos(4, 4, 4)).boundary().reason());
        assertFalse(view.isCurrent());
        assertEquals(0, view.residentOwners());
        current.put(old.position(), old);
        assertSame(Blocks.BARRIER.defaultBlockState(), view.getBlockState(new BlockPos(4, 4, 4)));
    }

    @Test void globalGenerationFenceAvoidsPerVoxelOwnerScanAndCanNeverReopen() {
        var snapshots = new HashMap<CubePos, EngineCubeSnapshot>();
        for (int x = 0; x < 125; x++) {
            var pos = new CubePos(x, 0, 0);
            snapshots.put(pos, uniform(pos, Blocks.AIR.defaultBlockState()));
        }
        var current = new AtomicBoolean(true);
        var stampChecks = new AtomicInteger();
        var view = new NativeCollisionView(world, EPOCH, palette, snapshots,
                stamp -> { stampChecks.incrementAndGet(); return true; }, current::get);
        assertTrue(view.read(new BlockPos(4, 4, 4)).known());
        assertEquals(1, stampChecks.get());
        assertTrue(view.isCurrent());
        assertEquals(126, stampChecks.get());
        current.set(false);
        assertEquals(STALE_DEPENDENCY, view.read(new BlockPos(4, 4, 4)).boundary().reason());
        assertEquals(0, view.residentOwners());
        current.set(true);
        assertFalse(view.isCurrent());
        assertEquals(126, stampChecks.get());
    }

    @Test void referencedOwnerFenceAndQueryEndRevalidationCatchExactStampChanges() {
        var snapshot = uniform(new CubePos(0, 0, 0), Blocks.AIR.defaultBlockState());
        var expected = NativeKnownCubeQueries.CubeStamp.of(snapshot);
        for (var changed : List.of(
                new NativeKnownCubeQueries.CubeStamp(world, EPOCH, expected.position(), 9, 1),
                new NativeKnownCubeQueries.CubeStamp(world, EPOCH, expected.position(), 10, 0),
                new NativeKnownCubeQueries.CubeStamp(world, EPOCH + 1, expected.position(), 9, 0),
                new NativeKnownCubeQueries.CubeStamp(new WorldIdentity(74, world.generatorId(), world.generatorVersion(), world.datasetVersion(), world.blockPaletteVersion()), EPOCH, expected.position(), 9, 0))) {
            var view = new NativeCollisionView(world, EPOCH, palette, map(snapshot), stamp -> stamp.equals(changed), () -> true);
            assertEquals(STALE_DEPENDENCY, view.read(new BlockPos(4, 4, 4)).boundary().reason());
        }
        var current = new AtomicBoolean(true);
        var view = new NativeCollisionView(world, EPOCH, palette, map(snapshot), stamp -> current.get(), () -> true);
        assertTrue(new NativeAccess(view).noCollision(new AABB(4.2, 4, 4.2, 4.8, 5.8, 4.8)));
        current.set(false);
        assertFalse(view.isCurrent());
        assertEquals(0, view.residentOwners());
    }

    @Test void closedViewDropsOwnershipAndCallbacksButRetainsZeroOwnerBoundaryDiagnostics() {
        var callbackCount = new AtomicInteger();
        var snapshot = uniform(new CubePos(0, 0, 0), Blocks.AIR.defaultBlockState());
        var view = new NativeCollisionView(world, EPOCH, palette, map(snapshot), () -> { callbackCount.incrementAndGet(); return true; });
        var dependencies = view.dependencies();
        assertEquals(1, dependencies.stamps().size());
        view.invalidate(); view.close();
        assertEquals(0, view.residentOwners());
        assertEquals(0, view.dependencies().stamps().size());
        assertEquals(CLOSED, view.read(new BlockPos(4, 4, 4)).boundary().reason());
        assertEquals(0, callbackCount.get());
        assertFalse(view.isCurrent());
        var counters = view.counters();
        assertEquals(1, counters.reads()); assertEquals(1, counters.boundaryReads());
        assertEquals(1L, counters.reasons().get(CLOSED));
        assertThrows(UnsupportedOperationException.class, () -> counters.reasons().clear());
        assertThrows(UnsupportedOperationException.class, () -> dependencies.stamps().clear());
    }

    @Test void fenceExceptionsProduceExplicitUnavailablePolicyWithoutEscapingToMovement() {
        var snapshot = uniform(new CubePos(0, 0, 0), Blocks.AIR.defaultBlockState());
        var view = new NativeCollisionView(world, EPOCH, palette, map(snapshot), () -> { throw new IllegalStateException("closed owner"); });
        assertEquals(FENCE_FAILED, view.read(new BlockPos(4, 4, 4)).boundary().reason());
        assertEquals(0, view.residentOwners());
        var stampFailure = new NativeCollisionView(world, EPOCH, palette, map(snapshot), stamp -> { throw new IllegalStateException("closed admission"); });
        assertEquals(FENCE_FAILED, stampFailure.read(new BlockPos(4, 4, 4)).boundary().reason());
    }

    @Test void preparationRejectsOverBudgetAndWrongSnapshotIdentities() {
        var snapshots = new HashMap<CubePos, EngineCubeSnapshot>();
        for (int x = 0; x < 126; x++) {
            var pos = new CubePos(x, 0, 0);
            snapshots.put(pos, uniform(pos, Blocks.AIR.defaultBlockState()));
        }
        assertThrows(IllegalArgumentException.class, () -> new NativeCollisionView(world, EPOCH, palette, snapshots, () -> true));
        var snapshot = snapshots.get(new CubePos(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new NativeCollisionView(world, EPOCH + 1, palette, map(snapshot), () -> true));
        assertThrows(IllegalArgumentException.class, () -> new NativeCollisionView(world, EPOCH, palette, Map.of(new CubePos(1, 0, 0), snapshot), () -> true));
        var other = new WorldIdentity(74, world.generatorId(), world.generatorVersion(), world.datasetVersion(), world.blockPaletteVersion());
        assertThrows(IllegalArgumentException.class, () -> new NativeCollisionView(other, EPOCH, palette, map(snapshot), () -> true));
        var forgedPalette = new EnginePaletteContext(palette.biomes(), "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> new NativeCollisionView(world, EPOCH, forgedPalette, map(snapshot), () -> true));
    }
}
