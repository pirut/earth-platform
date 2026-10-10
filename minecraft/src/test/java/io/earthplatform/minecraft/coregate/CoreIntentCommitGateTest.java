package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.enginebridge.NativeRayEditPlanner;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreIntentCommitGateTest {
    private static final CubePos TARGET = new CubePos(0, 8, 0), HALO = new CubePos(0, 7, 0);
    private static final Vec3 EYE = new Vec3(5.5, 133.5, 5.5), DOWN = new Vec3(0, -1, 0);
    private static final long EPOCH = 23, LEASE = 91, REVISION = 7;
    private static EnginePaletteContext palette;
    private static WorldIdentity world;
    private static Map<CubePos, EngineCubeSnapshot> snapshots;
    private static NativeRayEditPlanner.Plan place, remove;

    @BeforeAll static void fixtures() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
        world = new WorldIdentity(73, "commit-gate-test", "full-3d-v1", "fixture-v1", NativeCubeCodec.paletteIdentity(palette));
        var owners = new HashMap<CubePos, EngineCubeSnapshot>();
        for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            var position = TARGET.offset(x, y, z);
            var section = new EngineCubeSection(palette);
            if (position.equals(TARGET)) section.set(5, 0, 5, Blocks.STONE.defaultBlockState());
            owners.put(position, EngineCubeSnapshot.capture(position, world, EPOCH, LEASE, REVISION, section));
        }
        snapshots = Map.copyOf(owners);
        place = plan(EYE, NativeRayEditPlanner.Action.PLACE_DIORITE);
        remove = plan(EYE, NativeRayEditPlanner.Action.REMOVE);
    }
    private static NativeRayEditPlanner.Plan plan(Vec3 eye, NativeRayEditPlanner.Action action) {
        return assertInstanceOf(NativeRayEditPlanner.Plan.class, NativeRayEditPlanner.plan(world,
                EPOCH, palette, snapshots, eye, DOWN, 8, action, TARGET, body(eye)));
    }
    private static AABB body(Vec3 eye) {
        return new AABB(eye.x - .3, eye.y - 1.62, eye.z - .3,
                eye.x + .3, eye.y + .18, eye.z + .3);
    }
    private static String evaluate(boolean active, Vec3 eye, Vec3 look, AABB body,
                                   NativeRayEditPlanner.Plan plan, Map<CubePos, EngineCubeSnapshot> owners) {
        return CoreIntentCommitGate.evaluate(active, EYE, DOWN, eye, look, body, plan, owners);
    }
    private static void rejects(String expected, String actual) {
        assertNotNull(actual);
        assertTrue(actual.contains(expected), actual);
    }

    @Test void unchangedOperatorPoseAndDependenciesPassWithoutMutatingInputs() {
        assertNull(evaluate(true, EYE, DOWN, body(EYE), place, snapshots));
        assertNull(evaluate(true, EYE, DOWN, body(EYE), remove, snapshots));
        assertEquals(27, snapshots.size());
        assertEquals(REVISION, snapshots.get(TARGET).revision());
    }
    @Test void revokedPermissionStoppedOrReplacedSessionAlwaysRejects() {
        rejects("Operator session", evaluate(false, EYE, DOWN, body(EYE), place, snapshots));
        // The supplied authority boolean owns all session checks and wins even over malformed input.
        rejects("Operator session", CoreIntentCommitGate.evaluate(false, null, null, null, null, null, null, null));
    }
    @Test void eyeMovementLimitIsInclusiveAndDoesNotPermitLargerMovement() {
        Vec3 boundary = EYE.add(.5, 0, 0), beyond = EYE.add(.500001, 0, 0);
        assertNull(evaluate(true, boundary, DOWN, body(boundary), place, snapshots));
        rejects("Camera moved", evaluate(true, beyond, DOWN, body(beyond), place, snapshots));
    }
    @Test void lookAngleLimitIgnoresMagnitudeButRejectsRotationBeyondThreshold() {
        for (double magnitude : new double[]{Double.MIN_VALUE, 1, Double.MAX_VALUE})
            assertNull(evaluate(true, EYE, new Vec3(0, -magnitude, 0), body(EYE), place, snapshots));
        double allowed = .995001, rejected = .994999;
        assertNull(evaluate(true, EYE, new Vec3(Math.sqrt(1 - allowed * allowed), -allowed, 0), body(EYE), place, snapshots));
        rejects("Camera moved", evaluate(true, EYE, new Vec3(Math.sqrt(1 - rejected * rejected), -rejected, 0), body(EYE), place, snapshots));
    }
    @Test void currentReachRechecksEvenSmallMovementAfterAValidEightMetrePlan() {
        Vec3 captured = new Vec3(5.5, 137, 5.5), farther = captured.add(0, .1, 0);
        var edge = plan(captured, NativeRayEditPlanner.Action.REMOVE);
        assertNull(CoreIntentCommitGate.evaluate(true, captured, DOWN, captured, DOWN, body(captured), edge, snapshots));
        rejects("reach", CoreIntentCommitGate.evaluate(true, captured, DOWN, farther, DOWN, body(farther), edge, snapshots));
    }
    @Test void subThresholdMovementIntoPlacementCellCannotUseCapturedBodyClearance() {
        Vec3 captured = new Vec3(5.5, 131.62, 5.5), moved = captured.add(0, -.1, 0);
        var clearAtCapture = plan(captured, NativeRayEditPlanner.Action.PLACE_DIORITE);
        assertNull(CoreIntentCommitGate.evaluate(true, captured, DOWN, captured, DOWN, body(captured), clearAtCapture, snapshots));
        rejects("body overlaps", CoreIntentCommitGate.evaluate(true, captured, DOWN, moved, DOWN, body(moved), clearAtCapture, snapshots));
    }
    @Test void mereBodyFaceContactAndRemovalInsideBodyAreAllowed() {
        var touching = new AABB(5.2, 130, 5.2, 5.8, 131.8, 5.8);
        assertNull(evaluate(true, EYE, DOWN, touching, place, snapshots));
        var overlapsStone = new AABB(5.2, 128, 5.2, 5.8, 129.8, 5.8);
        assertNull(evaluate(true, EYE, DOWN, overlapsStone, remove, snapshots));
    }
    @Test void missingOrChangedNonTargetDependencyRejectsWithUnchangedTarget() {
        assertTrue(place.dependencies().stamps().stream().anyMatch(s -> s.position().equals(HALO)));
        var changed = new HashMap<>(snapshots);
        changed.put(HALO, EngineCubeSnapshot.capture(HALO, world, EPOCH, LEASE, REVISION + 1, new EngineCubeSection(palette)));
        assertSame(snapshots.get(TARGET), changed.get(TARGET));
        rejects("dependencies", evaluate(true, EYE, DOWN, body(EYE), place, changed));
        changed.remove(HALO);
        rejects("dependencies", evaluate(true, EYE, DOWN, body(EYE), place, changed));
        rejects("dependencies", evaluate(true, EYE, DOWN, body(EYE), place, null));
    }
    @Test void nonFiniteAndDegeneratePoseCannotPassNaNComparisons() {
        for (Vec3 invalid : new Vec3[]{null, new Vec3(Double.NaN, 0, 0), new Vec3(0, Double.POSITIVE_INFINITY, 0)}) {
            rejects("Invalid", evaluate(true, invalid, DOWN, body(EYE), place, snapshots));
            rejects("Invalid", evaluate(true, EYE, invalid, body(EYE), place, snapshots));
            rejects("Invalid", CoreIntentCommitGate.evaluate(true, invalid, DOWN, EYE, DOWN, body(EYE), place, snapshots));
            rejects("Invalid", CoreIntentCommitGate.evaluate(true, EYE, invalid, EYE, DOWN, body(EYE), place, snapshots));
        }
        rejects("direction", evaluate(true, EYE, Vec3.ZERO, body(EYE), place, snapshots));
        rejects("plan", evaluate(true, EYE, DOWN, body(EYE), null, snapshots));
    }
    @Test void missingNonFiniteAndOversizedCurrentBodyFailClosed() {
        for (AABB invalid : new AABB[]{null, new AABB(0, Double.NaN, 0, 1, 2, 1),
                new AABB(0, 0, 0, 0, 1, 1), new AABB(0, 0, 0, 1.01, 1.8, .6),
                new AABB(0, 0, 0, .6, 2.01, .6), new AABB(0, 0, 0, .6, 1.8, 1.01)})
            rejects("body", evaluate(true, EYE, DOWN, invalid, place, snapshots));
    }
}
