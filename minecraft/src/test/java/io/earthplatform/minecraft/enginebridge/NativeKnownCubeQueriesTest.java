package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubicWorld;
import io.earthplatform.core.FileEditStore;
import io.earthplatform.core.EditSnapshot;
import io.earthplatform.core.ImmutableCube;
import java.nio.file.Path;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.io.TempDir;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.enginebridge.NativeKnownCubeQueries.Status.*;
import static io.earthplatform.minecraft.enginebridge.NativeKnownCubeQueries.UnknownReason.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeKnownCubeQueriesTest {
    private static EnginePaletteContext palette;
    private static WorldIdentity world;
    private static final long EPOCH = 17;
    private static final NativeKnownCubeQueries.CurrentStamp CURRENT = ignored -> true;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
        world = new WorldIdentity(73, "known-query-test", "full-3d-v1", "fixture-v1", NativeCubeCodec.paletteIdentity(palette));
    }

    private static EngineCubeSnapshot snapshot(CubePos pos, EngineCubeSection section, long lease, long revision) {
        return EngineCubeSnapshot.capture(pos, world, EPOCH, lease, revision, section);
    }
    private static EngineCubeSnapshot uniform(CubePos pos, BlockState state) {
        return snapshot(pos, EngineCubeSection.uniform(palette, state), 9, 0);
    }
    private static NativeKnownCubeQueries view(EngineCubeSnapshot... snapshots) {
        Map<CubePos, EngineCubeSnapshot> map = new HashMap<>();
        for (var snapshot : snapshots) map.put(snapshot.position(), snapshot);
        return new NativeKnownCubeQueries(world, EPOCH, palette, map);
    }
    private static NativeKnownCubeQueries withAirHalo(EngineCubeSnapshot... snapshots) {
        Map<CubePos, EngineCubeSnapshot> map = new HashMap<>();
        for (var snapshot : snapshots) {
            CubePos centre = snapshot.position();
            for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                CubePos pos = centre.offset(x, y, z);
                map.putIfAbsent(pos, uniform(pos, Blocks.AIR.defaultBlockState()));
            }
        }
        for (var snapshot : snapshots) map.put(snapshot.position(), snapshot);
        return new NativeKnownCubeQueries(world, EPOCH, palette, map);
    }
    private static EngineCubeSnapshot cell(int x, int y, int z, BlockState state) {
        EngineCubeSection section = new EngineCubeSection(palette);
        section.set(CubePos.local(x), CubePos.local(y), CubePos.local(z), state);
        return snapshot(CubePos.fromBlock(x, y, z), section, 9, 0);
    }

    @Test void trueThreeDimensionalOverhangHasKnownAirBelowNativeRoof() {
        var provider = new AnalyticNativeCubeProvider(73, AnalyticNativeCubeProvider.Config.overhang(0), palette);
        var codec = new NativeCubeCodec(provider.identity(), palette);
        var pos = new CubePos(0, 0, 0);
        var snapshot = codec.materialize(pos, provider.load(pos, new CancellationToken()).join(), EPOCH, 2, 0);
        var query = new NativeKnownCubeQueries(provider.identity(), EPOCH, palette, Map.of(pos, snapshot));
        var air = query.raycast(new Vec3(5.5, 3.5, 5.5), new Vec3(12.5, 3.5, 5.5), CURRENT);
        assertEquals(CLEAR, air.status());
        var roof = query.raycast(new Vec3(5.5, 3.5, 5.5), new Vec3(5.5, 8.5, 5.5), CURRENT);
        assertEquals(HIT, roof.status());
        assertEquals(new BlockPos(5, 6, 5), roof.hit().getBlockPos());
        assertEquals(Direction.DOWN, roof.hit().getDirection());
        assertEquals(6.0, roof.hit().getLocation().y);
        assertEquals(CLEAR, query.overlaps(new AABB(5.2, 2, 5.2, 5.8, 3.8, 5.8), CURRENT).status());
        assertEquals(HIT, query.overlaps(new AABB(5.2, 5, 5.2, 5.8, 6.8, 5.8), CURRENT).status());
    }

    @Test void knownAirIsDifferentFromMissingAndFromUnknownBeforeFarSolid() {
        CubePos known = new CubePos(0, 0, 0);
        var empty = view();
        var missing = empty.raycast(new Vec3(1.5, 1.5, 1.5), new Vec3(2.5, 1.5, 1.5), CURRENT);
        assertEquals(UNKNOWN, missing.status());
        assertEquals(MISSING_CUBE, missing.unknown().reason());
        var query = view(uniform(known, Blocks.AIR.defaultBlockState()));
        assertEquals(CLEAR, query.raycast(new Vec3(8.5, 1.5, 1.5), new Vec3(14.5, 1.5, 1.5), CURRENT).status());
        var boundary = query.raycast(new Vec3(12.5, 1.5, 1.5), new Vec3(17.5, 1.5, 1.5), CURRENT);
        assertEquals(UNKNOWN, boundary.status());
        assertEquals(new CubePos(1, 0, 0), boundary.unknown().position());
        assertEquals(1, boundary.dependencies().stamps().size());
        var farSolidOnly = view(cell(16, 1, 1, Blocks.STONE.defaultBlockState()));
        assertEquals(UNKNOWN, farSolidOnly.raycast(new Vec3(12.5, 1.5, 1.5), new Vec3(17.5, 1.5, 1.5), CURRENT).status());
    }

    @Test void hitDoesNotRequireUnknownSpaceBehindOccluder() {
        var query = view(cell(14, 1, 1, Blocks.STONE.defaultBlockState()));
        var hit = query.raycast(new Vec3(12.5, 1.5, 1.5), new Vec3(17.5, 1.5, 1.5), CURRENT);
        assertEquals(HIT, hit.status());
        assertEquals(new BlockPos(14, 1, 1), hit.hit().getBlockPos());
        assertEquals(1, hit.dependencies().stamps().size());
    }

    @Test void negativeCubeSeamsKeepEveryAxisAndIndependentDependencyStamps() {
        var negative = cell(-1, -1, -1, Blocks.GOLD_BLOCK.defaultBlockState());
        var positiveX = uniform(new CubePos(0, -1, -1), Blocks.AIR.defaultBlockState());
        var query = withAirHalo(negative, positiveX);
        var hit = query.raycast(new Vec3(2.5, -0.5, -0.5), new Vec3(-2.5, -0.5, -0.5), CURRENT);
        assertEquals(HIT, hit.status());
        assertEquals(new BlockPos(-1, -1, -1), hit.hit().getBlockPos());
        assertEquals(Direction.EAST, hit.hit().getDirection());
        assertEquals(0.0, hit.hit().getLocation().x);
        assertTrue(hit.dependencies().stamps().contains(NativeKnownCubeQueries.CubeStamp.of(negative)));
        assertTrue(hit.dependencies().stamps().contains(NativeKnownCubeQueries.CubeStamp.of(positiveX)));
        var positiveY = uniform(new CubePos(-1, 0, -1), Blocks.AIR.defaultBlockState());
        var vertical = withAirHalo(negative, positiveY).raycast(new Vec3(-0.5, 2.5, -0.5), new Vec3(-0.5, -2.5, -0.5), CURRENT);
        assertEquals(Direction.UP, vertical.hit().getDirection());
        assertEquals(new BlockPos(-1, -1, -1), vertical.hit().getBlockPos());
        var positiveZ = uniform(new CubePos(-1, -1, 0), Blocks.AIR.defaultBlockState());
        var depth = withAirHalo(negative, positiveZ).raycast(new Vec3(-0.5, -0.5, 2.5), new Vec3(-0.5, -0.5, -2.5), CURRENT);
        assertEquals(Direction.SOUTH, depth.hit().getDirection());
    }

    @Test void allHitFacesReachInsideAndExactBoundaryConventions() {
        var query = withAirHalo(cell(8, 8, 8, Blocks.STONE.defaultBlockState()));
        Vec3 centre = new Vec3(8.5, 8.5, 8.5);
        for (Direction direction : Direction.values()) {
            Vec3 start = centre.add(direction.getStepX() * 3, direction.getStepY() * 3, direction.getStepZ() * 3);
            var hit = query.raycast(start, centre, CURRENT);
            assertEquals(HIT, hit.status());
            assertEquals(direction, hit.hit().getDirection());
            assertEquals(2.5, start.distanceTo(hit.hit().getLocation()), 1e-12);
        }
        var atReach = query.raycast(new Vec3(0, 8.5, 8.5), new Vec3(8, 8.5, 8.5), CURRENT);
        assertEquals(HIT, atReach.status());
        assertEquals(8.0, atReach.hit().getLocation().x);
        assertEquals(CLEAR, query.raycast(new Vec3(0, 8.5, 8.5), new Vec3(7.999999, 8.5, 8.5), CURRENT).status());
        var inside = query.raycast(centre, centre.add(0, 0.1, 0), CURRENT);
        assertEquals(HIT, inside.status()); assertTrue(inside.hit().isInside());
        assertEquals(centre, inside.hit().getLocation());
        assertEquals(CLEAR, query.raycast(new Vec3(8, 8.5, 8.5), new Vec3(7, 8.5, 8.5), CURRENT).status());
        assertEquals(HIT, query.raycast(new Vec3(9, 8.5, 8.5), new Vec3(8.5, 8.5, 8.5), CURRENT).status());
        assertEquals(HIT, query.raycast(centre, centre.add(1e-9, 0, 0), CURRENT).status());
    }

    @Test void signedExtremeNativeHeightsAndEarthScaleCoordinatesRetainSubBlockPrecision() {
        for (int y : new int[]{-11000, 8849}) {
            int x = 19876543, z = -19876543;
            var query = withAirHalo(cell(x, y, z, Blocks.STONE.defaultBlockState()));
            var hit = query.raycast(new Vec3(x + 0.125, y + 2.75, z + 0.875), new Vec3(x + 0.125, y - 0.25, z + 0.875), CURRENT);
            assertEquals(HIT, hit.status());
            assertEquals(new BlockPos(x, y, z), hit.hit().getBlockPos());
            assertEquals(new Vec3(x + 0.125, y + 1.0, z + 0.875), hit.hit().getLocation());
            assertEquals(Direction.UP, hit.hit().getDirection());
            assertEquals(HIT, query.overlaps(new AABB(x + 0.1, y + 0.9, z + 0.2, x + 0.7, y + 2.7, z + 0.8), CURRENT).status());
            assertEquals(CLEAR, query.overlaps(new AABB(x + 0.1, y + 1.0, z + 0.2, x + 0.7, y + 2.8, z + 0.8), CURRENT).status());
        }
    }

    @Test void bodyOverlapIsStrictAndUnknownDominatesKnownCollisionAcrossSeam() {
        var snapshot = cell(15, 1, 1, Blocks.STONE.defaultBlockState());
        var query = withAirHalo(snapshot);
        assertEquals(HIT, query.overlaps(new AABB(15.2, 1.8, 1.2, 15.8, 3.6, 1.8), CURRENT).status());
        assertEquals(CLEAR, query.overlaps(new AABB(15.2, 2, 1.2, 15.8, 3.8, 1.8), CURRENT).status());
        var unknown = view(snapshot).overlaps(new AABB(15.7, 1.2, 1.2, 16.3, 3, 1.8), CURRENT);
        assertEquals(UNKNOWN, unknown.status()); assertNull(unknown.block());
        assertEquals(new CubePos(1, 0, 0), unknown.unknown().position());
        // Face contact is not overlap, but the separate collision halo must still be known.
        assertEquals(HIT, query.overlaps(new AABB(15.4, 1.2, 1.2, 16, 3, 1.8), CURRENT).status());
    }

    @Test void unsupportedRegisteredStatesAreUnknownEvenWhenNativeCollisionCouldBeEmpty() {
        for (BlockState state : new BlockState[]{Blocks.WATER.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState(), Blocks.CHEST.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState()}) {
            var query = view(cell(2, 2, 2, state));
            var ray = query.raycast(new Vec3(1.5, 2.5, 2.5), new Vec3(3.5, 2.5, 2.5), CURRENT);
            assertEquals(UNKNOWN, ray.status()); assertEquals(UNSUPPORTED_STATE, ray.unknown().reason());
            assertEquals(UNKNOWN, query.overlaps(new AABB(2.2, 2.2, 2.2, 2.8, 4, 2.8), CURRENT).status());
        }
        for (BlockState state : new BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.VOID_AIR.defaultBlockState()})
            assertEquals(CLEAR, view(cell(2, 2, 2, state)).raycast(new Vec3(1.5, 2.5, 2.5), new Vec3(3.5, 2.5, 2.5), CURRENT).status());
    }

    @Test void protrudingFenceAndMissingCollisionHaloNeverBecomeClearAir() {
        // Native fence collision reaches 1.5 blocks high, into the known-air voxel above it.
        var fence = cell(4, 4, 4, Blocks.OAK_FENCE.defaultBlockState());
        var query = view(fence);
        var body = query.overlaps(new AABB(4.3, 5.1, 4.3, 4.7, 6.9, 4.7), CURRENT);
        assertEquals(UNKNOWN, body.status()); assertEquals(UNSUPPORTED_STATE, body.unknown().reason());
        var ray = query.raycast(new Vec3(4.5, 5.2, 3.5), new Vec3(4.5, 5.2, 5.5), CURRENT);
        assertEquals(UNKNOWN, ray.status()); assertEquals(UNSUPPORTED_STATE, ray.unknown().reason());
        var below = cell(4, 15, 4, Blocks.OAK_FENCE.defaultBlockState());
        var above = uniform(new CubePos(0, 1, 0), Blocks.AIR.defaultBlockState());
        var missing = view(above).overlaps(new AABB(4.3, 16.1, 4.3, 4.7, 17.9, 4.7), CURRENT);
        assertEquals(UNKNOWN, missing.status()); assertEquals(MISSING_CUBE, missing.unknown().reason());
        var admitted = view(below, above).overlaps(new AABB(4.3, 16.1, 4.3, 4.7, 17.9, 4.7), CURRENT);
        assertEquals(UNKNOWN, admitted.status()); assertEquals(UNSUPPORTED_STATE, admitted.unknown().reason());
    }

    @Test void admissionChangesInvalidateQueriesAndReturnedDependencyProofs() {
        var snapshot = cell(4, 4, 4, Blocks.STONE.defaultBlockState());
        var stamp = NativeKnownCubeQueries.CubeStamp.of(snapshot);
        var current = new HashMap<CubePos, NativeKnownCubeQueries.CubeStamp>();
        current.put(stamp.position(), stamp);
        NativeKnownCubeQueries.CurrentStamp check = expected -> expected.equals(current.get(expected.position()));
        var query = view(snapshot);
        var before = query.raycast(new Vec3(2.5, 4.5, 4.5), new Vec3(5.5, 4.5, 4.5), check);
        assertEquals(HIT, before.status()); assertTrue(before.dependencies().isCurrent(check));
        for (var replacement : new NativeKnownCubeQueries.CubeStamp[]{
                new NativeKnownCubeQueries.CubeStamp(world, EPOCH, stamp.position(), stamp.leaseGeneration(), 1),
                new NativeKnownCubeQueries.CubeStamp(world, EPOCH, stamp.position(), 10, 0),
                new NativeKnownCubeQueries.CubeStamp(world, EPOCH + 1, stamp.position(), 9, 0),
                new NativeKnownCubeQueries.CubeStamp(new WorldIdentity(74, world.generatorId(), world.generatorVersion(), world.datasetVersion(), world.blockPaletteVersion()), EPOCH, stamp.position(), 9, 0)}) {
            current.put(stamp.position(), replacement);
            assertFalse(before.dependencies().isCurrent(check));
            var stale = query.raycast(new Vec3(2.5, 4.5, 4.5), new Vec3(5.5, 4.5, 4.5), check);
            assertEquals(UNKNOWN, stale.status()); assertEquals(STALE_DEPENDENCY, stale.unknown().reason());
        }
        current.clear(); assertFalse(before.dependencies().isCurrent(check));
        assertEquals(UNKNOWN, query.overlaps(new AABB(4.2, 4.2, 4.2, 4.8, 6, 4.8), check).status());
    }

    @Test void rechecksEvenAfterLastShapeReadAndResultCollectionIsImmutable() {
        var query = view(cell(4, 4, 4, Blocks.STONE.defaultBlockState()));
        AtomicInteger reads = new AtomicInteger();
        var result = query.raycast(new Vec3(4.5, 4.5, 4.5), new Vec3(5.5, 4.5, 4.5), stamp -> reads.incrementAndGet() <= 27);
        assertEquals(UNKNOWN, result.status()); assertEquals(STALE_DEPENDENCY, result.unknown().reason());
        assertEquals(28, reads.get());
        assertThrows(UnsupportedOperationException.class, () -> result.dependencies().stamps().clear());
    }

    @Test void immutableCapturedAirEditRequiresReplacementAndLeavesOldQueryStale() {
        var pos = new CubePos(0, 0, 0);
        var section = new EngineCubeSection(palette);
        section.set(4, 4, 4, Blocks.STONE.defaultBlockState());
        var old = snapshot(pos, section, 9, 0);
        var inputs = new HashMap<>(Map.of(pos, old));
        var original = new NativeKnownCubeQueries(world, EPOCH, palette, inputs);
        inputs.clear(); section.set(4, 4, 4, Blocks.AIR.defaultBlockState());
        var edited = snapshot(pos, section, 9, 1);
        NativeKnownCubeQueries.CurrentStamp current = stamp -> stamp.equals(NativeKnownCubeQueries.CubeStamp.of(edited));
        Vec3 start = new Vec3(2.5, 4.5, 4.5), end = new Vec3(5.5, 4.5, 4.5);
        assertEquals(HIT, original.raycast(start, end, CURRENT).status());
        assertEquals(UNKNOWN, original.raycast(start, end, current).status());
        var result = view(edited).raycast(start, end, current);
        assertEquals(CLEAR, result.status()); assertEquals(1, result.dependencies().stamps().getFirst().revision());
    }

    @Test void durableDioriteReplacementQueriesExactlyAfterStoreReopen(@TempDir Path directory) throws Exception {
        CubePos pos = CubePos.fromBlock(-1, -11000, -1);
        int index = CubePos.indexOfBlock(-1, -11000, -1);
        int air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        int diorite = Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
        try (var store = new FileEditStore(directory, world)) {
            store.save(new EditSnapshot(pos, 1, Map.of(index, diorite)));
        }
        try (var reopened = new FileEditStore(directory, world)) {
            var edits = reopened.load(pos).snapshot();
            var cube = new CubicWorld.CubeView(pos, ImmutableCube.uniform(air), edits);
            var snapshot = new NativeCubeCodec(world, palette).materialize(cube, EPOCH, 9);
            var query = withAirHalo(snapshot);
            var ray = query.raycast(new Vec3(-0.5, -10997.5, -0.5), new Vec3(-0.5, -11000.5, -0.5), CURRENT);
            assertEquals(HIT, ray.status());
            assertEquals(new BlockPos(-1, -11000, -1), ray.hit().getBlockPos());
            assertEquals(1, ray.dependencies().stamps().stream().filter(stamp -> stamp.position().equals(pos)).findFirst().orElseThrow().revision());
            assertEquals(HIT, query.overlaps(new AABB(-0.8, -10999.5, -0.8, -0.2, -10997.7, -0.2), CURRENT).status());
        }
    }

    @Test void boundedOwnershipInputsAndMixedSnapshotIdentitiesAreRejected() {
        var map = new HashMap<CubePos, EngineCubeSnapshot>();
        for (int x = 0; x < 126; x++) {
            var pos = new CubePos(x, 0, 0); map.put(pos, uniform(pos, Blocks.AIR.defaultBlockState()));
        }
        assertThrows(IllegalArgumentException.class, () -> new NativeKnownCubeQueries(world, EPOCH, palette, map));
        map.remove(new CubePos(125, 0, 0));
        assertEquals(125, new NativeKnownCubeQueries(world, EPOCH, palette, map).ownerCount());
        var s = uniform(new CubePos(0, 0, 0), Blocks.AIR.defaultBlockState());
        assertThrows(IllegalArgumentException.class, () -> new NativeKnownCubeQueries(world, EPOCH + 1, palette, Map.of(s.position(), s)));
        assertThrows(IllegalArgumentException.class, () -> new NativeKnownCubeQueries(world, EPOCH, palette, Map.of(new CubePos(1, 0, 0), s)));
        var query = view(s);
        assertThrows(IllegalArgumentException.class, () -> query.raycast(Vec3.ZERO, new Vec3(8.0001, 0, 0), CURRENT));
        assertThrows(IllegalArgumentException.class, () -> query.raycast(Vec3.ZERO, Vec3.ZERO, CURRENT));
        assertThrows(IllegalArgumentException.class, () -> query.raycast(Vec3.ZERO, new Vec3(Double.NaN, 0, 0), CURRENT));
        assertThrows(IllegalArgumentException.class, () -> query.overlaps(new AABB(0, 0, 0, 2, 2, 1), CURRENT));
        assertThrows(IllegalArgumentException.class, () -> query.overlaps(new AABB(0, 0, 0, 1, 2.1, 1), CURRENT));
        assertThrows(IllegalArgumentException.class, () -> query.overlaps(new AABB(0, 0, 0, 0, 1, 1), CURRENT));
        var outside = query.raycast(new Vec3(0.5, -11009.5, 0.5), new Vec3(0.5, -11008.5, 0.5), CURRENT);
        assertEquals(UNKNOWN, outside.status()); assertEquals(OUTSIDE_DOMAIN, outside.unknown().reason());
    }
}
