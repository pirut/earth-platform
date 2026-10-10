package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.enginebridge.NativeRayEditPlanner.Action.*;
import static io.earthplatform.minecraft.enginebridge.NativeRayEditPlanner.RejectionReason.*;
import static io.earthplatform.minecraft.enginebridge.NativeKnownCubeQueries.UnknownReason.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeRayEditPlannerTest {
    private static EnginePaletteContext palette;
    private static WorldIdentity world;
    private static final long EPOCH = 23, LEASE = 91, REVISION = 7;
    private static final CubePos EDITABLE = new CubePos(0, 8, 0);

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
        world = new WorldIdentity(73, "ray-edit-test", "full-3d-v1", "fixture-v1", NativeCubeCodec.paletteIdentity(palette));
    }
    private static EngineCubeSnapshot snapshot(CubePos pos, EngineCubeSection section) {
        return EngineCubeSnapshot.capture(pos, world, EPOCH, LEASE, REVISION, section);
    }
    private static EngineCubeSnapshot cell(int x, int y, int z, BlockState state) {
        var section = new EngineCubeSection(palette);
        section.set(CubePos.local(x), CubePos.local(y), CubePos.local(z), state);
        return snapshot(CubePos.fromBlock(x, y, z), section);
    }
    private static Map<CubePos, EngineCubeSnapshot> halo(EngineCubeSnapshot... owners) {
        var result = new HashMap<CubePos, EngineCubeSnapshot>();
        for (var owner : owners) {
            for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                CubePos pos = owner.position().offset(x, y, z);
                result.putIfAbsent(pos, snapshot(pos, new EngineCubeSection(palette)));
            }
        }
        for (var owner : owners) result.put(owner.position(), owner);
        return result;
    }
    private static NativeRayEditPlanner.Result plan(Map<CubePos, EngineCubeSnapshot> snapshots,
                                                    Vec3 eye, Vec3 look, NativeRayEditPlanner.Action action, CubePos editable) {
        return NativeRayEditPlanner.plan(world, EPOCH, palette, snapshots, eye, look, 8, action, editable, null);
    }
    private static NativeRayEditPlanner.Plan accepted(NativeRayEditPlanner.Result result) {
        return assertInstanceOf(NativeRayEditPlanner.Plan.class, result);
    }
    private static NativeRayEditPlanner.Rejected rejected(NativeRayEditPlanner.Result result,
                                                         NativeRayEditPlanner.RejectionReason reason) {
        var rejected = assertInstanceOf(NativeRayEditPlanner.Rejected.class, result);
        assertEquals(reason, rejected.reason());
        assertFalse(rejected.detail().isBlank());
        return rejected;
    }
    private static void target(NativeRayEditPlanner.Plan plan, int x, int y, int z, BlockState replacement) {
        var request = plan.request();
        assertEquals(x, request.x()); assertEquals(y, request.y()); assertEquals(z, request.z());
        assertEquals(CubePos.fromBlock(x, y, z), request.position());
        assertEquals(Block.BLOCK_STATE_REGISTRY.getId(replacement), request.stateId());
    }

    @Test void placesDioriteAboveRealFlat128WithExactTargetIdentity() {
        var provider = new AnalyticNativeCubeProvider(73, AnalyticNativeCubeProvider.Config.flat(128), palette);
        var codec = new NativeCubeCodec(provider.identity(), palette);
        var map = new HashMap<CubePos, EngineCubeSnapshot>();
        for (int y = 7; y <= 9; y++) {
            var pos = new CubePos(0, y, 0);
            map.put(pos, codec.materialize(pos, provider.load(pos, new CancellationToken()).join(), EPOCH, y + 20, y + 30));
        }
        var result = accepted(NativeRayEditPlanner.plan(provider.identity(), EPOCH, palette, Map.copyOf(map),
                new Vec3(5.5, 133.5, 5.5), new Vec3(0, -1, 0), 8, PLACE_DIORITE, EDITABLE, null));
        target(result, 5, 129, 5, Blocks.DIORITE.defaultBlockState());
        assertEquals(new BlockPos(5, 128, 5), result.hit().getBlockPos());
        assertEquals(Direction.UP, result.hit().getDirection());
        assertEquals(provider.identity(), result.request().world()); assertEquals(EPOCH, result.request().epoch());
        assertEquals(28, result.request().leaseGeneration()); assertEquals(38, result.request().expectedRevision());
        assertTrue(result.isCurrent(map));
        assertTrue(result.dependencies().stamps().stream().anyMatch(s -> s.position().equals(new CubePos(0, 7, 0))));
    }

    @Test void actualMouseGateEntryCameraReachesGroundWithinEightMetres() {
        var provider = new AnalyticNativeCubeProvider(73, AnalyticNativeCubeProvider.Config.flat(128), palette);
        var codec = new NativeCubeCodec(provider.identity(), palette);
        var map = new HashMap<CubePos, EngineCubeSnapshot>();
        long lease = 1;
        for (int y = 6; y <= 10; y++) for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) {
            var pos = new CubePos(x, y, z);
            map.put(pos, codec.materialize(pos, provider.load(pos, new CancellationToken()).join(), EPOCH, lease++, 0));
        }
        Vec3 eye = new Vec3(4.5, 131.0 + 1.62f, -1.5);
        var result = accepted(NativeRayEditPlanner.plan(provider.identity(), EPOCH, palette, Map.copyOf(map),
                eye, Vec3.directionFromRotation(31, 0), 8, PLACE_DIORITE, EDITABLE,
                new AABB(4.2, 131, -1.8, 4.8, 132.8, -1.2)));
        target(result, 4, 129, 4, Blocks.DIORITE.defaultBlockState());
        assertEquals(new BlockPos(4, 128, 4), result.hit().getBlockPos());
        assertTrue(eye.distanceTo(result.hit().getLocation()) < 7.1);
        assertTrue(result.isCurrent(map));
    }

    @Test void removalUsesNearestHitRatherThanFartherRequestedGeometry() {
        var section = new EngineCubeSection(palette);
        section.set(5, 4, 5, Blocks.DIORITE.defaultBlockState());
        section.set(5, 0, 5, Blocks.STONE.defaultBlockState());
        var map = halo(snapshot(EDITABLE, section));
        var result = accepted(plan(map, new Vec3(5.5, 137.5, 5.5), new Vec3(0, -1, 0), REMOVE, EDITABLE));
        target(result, 5, 132, 5, Blocks.AIR.defaultBlockState());
        assertEquals(LEASE, result.request().leaseGeneration()); assertEquals(REVISION, result.request().expectedRevision());
        assertEquals(world, result.request().world()); assertEquals(EPOCH, result.request().epoch());
    }

    @Test void negativeXYZPlacementAndRemovalKeepIndependentCubeAddresses() {
        var map = halo(cell(-17, -17, -17, Blocks.STONE.defaultBlockState()));
        Vec3 eye = new Vec3(-16.5, -13.5, -16.5), down = new Vec3(0, -1, 0);
        var placed = accepted(plan(map, eye, down, PLACE_DIORITE, new CubePos(-2, -1, -2)));
        target(placed, -17, -16, -17, Blocks.DIORITE.defaultBlockState());
        target(accepted(plan(map, eye, down, REMOVE, new CubePos(-2, -2, -2))), -17, -17, -17, Blocks.AIR.defaultBlockState());
        assertEquals(Direction.UP, placed.hit().getDirection());
        assertTrue(placed.dependencies().stamps().stream().anyMatch(s -> s.position().equals(new CubePos(-2, -2, -2))));
        assertTrue(placed.dependencies().stamps().stream().anyMatch(s -> s.position().equals(new CubePos(-2, -1, -2))));
    }

    @Test void signedNativeHeightExtremesKeepEarthScalePrecision() {
        int x = 19876543, z = -19876543;
        for (int y : new int[]{-11000, 8849}) {
            var map = halo(cell(x, y, z, Blocks.GOLD_BLOCK.defaultBlockState()));
            Vec3 eye = new Vec3(x + 0.125, y + 3.75, z + 0.875);
            var result = accepted(plan(map, eye, new Vec3(0, -1, 0), PLACE_DIORITE, CubePos.fromBlock(x, y + 1, z)));
            target(result, x, y + 1, z, Blocks.DIORITE.defaultBlockState());
            assertEquals(new Vec3(x + 0.125, y + 1.0, z + 0.875), result.hit().getLocation());
            target(accepted(plan(map, eye, new Vec3(0, -1, 0), REMOVE, CubePos.fromBlock(x, y, z))),
                    x, y, z, Blocks.AIR.defaultBlockState());
        }
    }

    @Test void realOverhangPlacesOnUndersideInKnownAirRatherThanHeightSummary() {
        var provider = new AnalyticNativeCubeProvider(73, AnalyticNativeCubeProvider.Config.overhang(128), palette);
        var codec = new NativeCubeCodec(provider.identity(), palette);
        var snapshot = codec.materialize(EDITABLE, provider.load(EDITABLE, new CancellationToken()).join(), EPOCH, LEASE, REVISION);
        var result = accepted(NativeRayEditPlanner.plan(provider.identity(), EPOCH, palette, Map.of(EDITABLE, snapshot),
                new Vec3(5.5, 131.5, 5.5), new Vec3(0, 1, 0), 8, PLACE_DIORITE, EDITABLE, null));
        assertEquals(Direction.DOWN, result.hit().getDirection());
        assertEquals(new BlockPos(5, 134, 5), result.hit().getBlockPos());
        target(result, 5, 133, 5, Blocks.DIORITE.defaultBlockState());
    }

    @Test void placementUsesEveryNativeHitFace() {
        var map = halo(cell(5, 133, 5, Blocks.STONE.defaultBlockState()));
        Vec3 centre = new Vec3(5.5, 133.5, 5.5);
        for (Direction face : Direction.values()) {
            Vec3 offset = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            var result = accepted(plan(map, centre.add(offset.scale(3)), offset.scale(-1), PLACE_DIORITE, EDITABLE));
            assertEquals(face, result.hit().getDirection());
            target(result, 5 + face.getStepX(), 133 + face.getStepY(), 5 + face.getStepZ(), Blocks.DIORITE.defaultBlockState());
        }
    }

    @Test void missingAndUnsupportedHaloNeverBecomeEditableAir() {
        var stone = cell(5, 128, 5, Blocks.STONE.defaultBlockState());
        var missing = rejected(plan(Map.of(EDITABLE, stone), new Vec3(5.5, 133.5, 5.5), new Vec3(0, -1, 0), PLACE_DIORITE, EDITABLE), UNKNOWN_DATA);
        assertEquals(MISSING_CUBE, missing.unknown().reason());
        assertEquals(new CubePos(0, 7, 0), missing.unknown().position());
        for (BlockState unsupported : new BlockState[]{Blocks.OAK_FENCE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.SHORT_GRASS.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState(), Blocks.CHEST.defaultBlockState()}) {
            var section = new EngineCubeSection(palette);
            section.set(5, 0, 5, Blocks.STONE.defaultBlockState()); section.set(6, 1, 5, unsupported);
            var result = rejected(plan(halo(snapshot(EDITABLE, section)), new Vec3(5.5, 133.5, 5.5),
                    new Vec3(0, -1, 0), PLACE_DIORITE, EDITABLE), UNKNOWN_DATA);
            assertEquals(UNSUPPORTED_STATE, result.unknown().reason());
        }
    }

    @Test void unknownBeforeKnownFarSolidStopsTheRay() {
        var stone = cell(0, 133, 5, Blocks.STONE.defaultBlockState());
        var result = rejected(plan(Map.of(EDITABLE, stone), new Vec3(-3.5, 133.5, 5.5), new Vec3(1, 0, 0), REMOVE, EDITABLE), UNKNOWN_DATA);
        assertEquals(MISSING_CUBE, result.unknown().reason());
    }

    @Test void dependencyRevalidationChecksWorldEpochLeaseRevisionKeyAndMissingOwner() {
        var map = halo(cell(5, 128, 5, Blocks.STONE.defaultBlockState()));
        var result = accepted(plan(map, new Vec3(5.5, 133.5, 5.5), new Vec3(0, -1, 0), PLACE_DIORITE, EDITABLE));
        assertTrue(result.isCurrent(map));
        CubePos dependency = new CubePos(0, 7, 0); // Halo owner, NOT the unchanged target.
        assertTrue(result.dependencies().stamps().stream().anyMatch(s -> s.position().equals(dependency)));
        var otherWorld = new WorldIdentity(74, world.generatorId(), world.generatorVersion(), world.datasetVersion(), world.blockPaletteVersion());
        var air = new EngineCubeSection(palette);
        for (var replacement : new EngineCubeSnapshot[]{
                EngineCubeSnapshot.capture(dependency, otherWorld, EPOCH, LEASE, REVISION, air),
                EngineCubeSnapshot.capture(dependency, world, EPOCH + 1, LEASE, REVISION, air),
                EngineCubeSnapshot.capture(dependency, world, EPOCH, LEASE + 1, REVISION, air),
                EngineCubeSnapshot.capture(dependency, world, EPOCH, LEASE, REVISION + 1, air),
                EngineCubeSnapshot.capture(new CubePos(1, 7, 0), world, EPOCH, LEASE, REVISION, air)}) {
            var changed = new HashMap<>(map); changed.put(dependency, replacement);
            assertFalse(result.isCurrent(changed));
            assertSame(map.get(EDITABLE), changed.get(EDITABLE));
        }
        var missing = new HashMap<>(map); missing.remove(dependency);
        assertFalse(result.isCurrent(missing)); assertFalse(result.isCurrent(null));
        assertThrows(UnsupportedOperationException.class, () -> result.dependencies().stamps().clear());
    }

    @Test void destinationHaloAddsDependenciesThatRayAloneDidNotNeed() {
        var section = new EngineCubeSection(palette);
        section.set(14, 5, 5, Blocks.STONE.defaultBlockState());
        var map = halo(snapshot(EDITABLE, section));
        // The eye is inside the solid, so the hit is immediate. East-face destination x=15
        // needs the x=16 halo; the hit's x=13..15 halo did not read that owner.
        var result = accepted(plan(map, new Vec3(14.5, 133.5, 5.5), new Vec3(-1, 0, 0), PLACE_DIORITE, EDITABLE));
        target(result, 15, 133, 5, Blocks.DIORITE.defaultBlockState());
        CubePos destinationOnly = new CubePos(1, 8, 0);
        assertTrue(result.dependencies().stamps().stream().anyMatch(s -> s.position().equals(destinationOnly)));
        map.remove(destinationOnly);
        assertFalse(result.isCurrent(map));
        assertEquals(MISSING_CUBE, rejected(plan(map, new Vec3(14.5, 133.5, 5.5), new Vec3(-1, 0, 0), PLACE_DIORITE, EDITABLE), UNKNOWN_DATA).unknown().reason());
    }

    @Test void staleEpochMalformedKeysAndUnpinnedPaletteAreRejectedBeforePlanning() {
        var owner = cell(5, 132, 5, Blocks.STONE.defaultBlockState());
        Vec3 eye = new Vec3(5.5, 135.5, 5.5), look = new Vec3(0, -1, 0);
        rejected(NativeRayEditPlanner.plan(world, EPOCH + 1, palette, Map.of(EDITABLE, owner), eye, look, 8, REMOVE, EDITABLE, null), INVALID_SNAPSHOTS);
        rejected(plan(Map.of(new CubePos(1, 8, 0), owner), eye, look, REMOVE, EDITABLE), INVALID_SNAPSHOTS);
        var forged = new EnginePaletteContext(palette.biomes(), "0".repeat(64));
        rejected(NativeRayEditPlanner.plan(world, EPOCH, forged, Map.of(EDITABLE, owner), eye, look, 8, REMOVE, EDITABLE, null), INVALID_SNAPSHOTS);
        var wrongWorld = new WorldIdentity(74, world.generatorId(), world.generatorVersion(), world.datasetVersion(), world.blockPaletteVersion());
        rejected(NativeRayEditPlanner.plan(wrongWorld, EPOCH, palette, Map.of(EDITABLE, owner), eye, look, 8, REMOVE, EDITABLE, null), INVALID_SNAPSHOTS);
    }

    @Test void editVolumeAppliesToActualDestinationAndRemovalTarget() {
        var map = halo(cell(15, 133, 5, Blocks.STONE.defaultBlockState()));
        Vec3 eye = new Vec3(18.5, 133.5, 5.5), west = new Vec3(-1, 0, 0);
        rejected(plan(map, eye, west, PLACE_DIORITE, EDITABLE), OUTSIDE_EDITABLE_CUBE);
        target(accepted(plan(map, eye, west, REMOVE, EDITABLE)), 15, 133, 5, Blocks.AIR.defaultBlockState());
        rejected(plan(map, eye, west, REMOVE, new CubePos(1, 8, 0)), OUTSIDE_EDITABLE_CUBE);
        // A hit in a neighbor may legitimately place into the allowed destination cube.
        target(accepted(plan(map, eye, west, PLACE_DIORITE, new CubePos(1, 8, 0))), 16, 133, 5, Blocks.DIORITE.defaultBlockState());
    }

    @Test void strictPlayerBodyOverlapRejectsPlacementButTouchingAndRemovalAreAllowed() {
        var map = halo(cell(5, 128, 5, Blocks.STONE.defaultBlockState()));
        Vec3 eye = new Vec3(5.5, 133.5, 5.5), down = new Vec3(0, -1, 0);
        for (double base : new double[]{129.0, 129.999999}) {
            var body = new AABB(5.2, base, 5.2, 5.8, base + 1.8, 5.8);
            rejected(NativeRayEditPlanner.plan(world, EPOCH, palette, map, eye, down, 8, PLACE_DIORITE, EDITABLE, body), TARGET_BODY_OVERLAP);
            accepted(NativeRayEditPlanner.plan(world, EPOCH, palette, map, eye, down, 8, REMOVE, EDITABLE, body));
        }
        var touching = new AABB(5.2, 130, 5.2, 5.8, 131.8, 5.8);
        accepted(NativeRayEditPlanner.plan(world, EPOCH, palette, map, eye, down, 8, PLACE_DIORITE, EDITABLE, touching));
        var malformed = new AABB(5.2, Double.NaN, 5.2, 5.8, 131.8, 5.8);
        rejected(NativeRayEditPlanner.plan(world, EPOCH, palette, map, eye, down, 8, PLACE_DIORITE, EDITABLE, malformed), INVALID_BODY);
        rejected(NativeRayEditPlanner.plan(world, EPOCH, palette, map, eye, down, 8, PLACE_DIORITE, EDITABLE,
                new AABB(0, 0, 0, 1.1, 1.8, 1)), INVALID_BODY);
    }

    @Test void placementCannotReplaceKnownSolidEvenForInsideHit() {
        var section = new EngineCubeSection(palette);
        section.set(5, 5, 5, Blocks.STONE.defaultBlockState()); section.set(6, 5, 5, Blocks.STONE.defaultBlockState());
        var map = halo(snapshot(EDITABLE, section));
        rejected(plan(map, new Vec3(5.5, 133.5, 5.5), new Vec3(-1, 0, 0), PLACE_DIORITE, EDITABLE), TARGET_NOT_AIR);
    }

    @Test void inclusiveEightMetreReachCannotBeExtendedByLookMagnitude() {
        var map = halo(cell(8, 133, 5, Blocks.STONE.defaultBlockState()));
        Vec3 eye = new Vec3(0, 133.5, 5.5);
        target(accepted(plan(map, eye, new Vec3(10000, 0, 0), REMOVE, EDITABLE)), 8, 133, 5, Blocks.AIR.defaultBlockState());
        rejected(NativeRayEditPlanner.plan(world, EPOCH, palette, map, eye, new Vec3(1, 0, 0), 7.999999, REMOVE, EDITABLE, null), NO_HIT);
        for (double reach : new double[]{0, -1, 8.000001, Double.NaN, Double.POSITIVE_INFINITY})
            rejected(NativeRayEditPlanner.plan(world, EPOCH, palette, map, eye, new Vec3(1, 0, 0), reach, REMOVE, EDITABLE, null), INVALID_REACH);
        for (Vec3 look : new Vec3[]{Vec3.ZERO, new Vec3(Double.NaN, 0, 0), new Vec3(Double.POSITIVE_INFINITY, 0, 0)})
            rejected(plan(map, eye, look, REMOVE, EDITABLE), INVALID_LOOK);
        rejected(plan(map, new Vec3(Double.NaN, 133, 5), new Vec3(1, 0, 0), REMOVE, EDITABLE), INVALID_INPUT);
        for (double magnitude : new double[]{Double.MAX_VALUE, Double.MIN_VALUE})
            accepted(plan(map, eye, new Vec3(magnitude, 0, 0), REMOVE, EDITABLE));
    }

    @Test void diagonalEarthScaleNormalizationStaysInsideBudget() {
        int x = 19876543, z = -19876543;
        var map = halo(cell(x, 133, z, Blocks.STONE.defaultBlockState()));
        Vec3 eye = new Vec3(x - 3.5, 137, z - 3.5);
        var result = accepted(plan(map, eye, new Vec3(3.5, -3.5, 3.5), REMOVE, CubePos.fromBlock(x, 133, z)));
        target(result, x, 133, z, Blocks.AIR.defaultBlockState());
        assertTrue(eye.distanceTo(result.hit().getLocation()) <= 8);
    }

    @Test void snapshotAndPlanRemainImmutableAfterOriginalSectionOrMapChanges() {
        var section = new EngineCubeSection(palette);
        section.set(5, 4, 5, Blocks.STONE.defaultBlockState());
        var snapshot = snapshot(EDITABLE, section);
        var map = halo(snapshot);
        var result = accepted(plan(map, new Vec3(5.5, 137.5, 5.5), new Vec3(0, -1, 0), REMOVE, EDITABLE));
        section.set(5, 4, 5, Blocks.AIR.defaultBlockState()); map.clear();
        target(result, 5, 132, 5, Blocks.AIR.defaultBlockState());
        assertEquals(REVISION, result.request().expectedRevision());
        assertFalse(result.isCurrent(map));
        assertEquals(Blocks.STONE.defaultBlockState(), snapshot.materialize(palette).get(5, 4, 5));
    }

    @Test void ownerBudgetIsBoundedAndKnownAirNoHitIsExplicit() {
        var map = new HashMap<CubePos, EngineCubeSnapshot>();
        for (int x = 0; x < 126; x++) {
            var pos = new CubePos(x, 8, 0); map.put(pos, snapshot(pos, new EngineCubeSection(palette)));
        }
        Vec3 eye = new Vec3(5.5, 133.5, 5.5), look = new Vec3(1, 0, 0);
        rejected(plan(map, eye, look, REMOVE, EDITABLE), TOO_MANY_SNAPSHOTS);
        map.remove(new CubePos(125, 8, 0));
        rejected(plan(map, eye, look, REMOVE, EDITABLE), NO_HIT);
        var outside = rejected(plan(map, new Vec3(5.5, -11009.5, 5.5), new Vec3(0, 1, 0), REMOVE, EDITABLE), UNKNOWN_DATA);
        assertEquals(OUTSIDE_DOMAIN, outside.unknown().reason());
    }
}
