package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.ReplacementCertificate;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeVersion;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeLease;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageMetadata;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageReady;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent exact-set and authority adversaries for the single original-cube replacement gate. */
class RollingReplacementIndependentReviewTest {
    private static final CubePos PIN = new CubePos(-7, 8, -11);
    private static final WorldIdentity WORLD = new WorldIdentity(81, "review-provider", "v1", "review-source", "review-palette");
    private static final String PALETTE = "a".repeat(64);
    private static EnginePaletteContext nativePalette;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        nativePalette = EnginePaletteContext.create(registry.freeze());
    }

    private static ReplacementCertificate captureCertificate() {
        return new ReplacementCertificate(WORLD, 11, 7, PIN, "store", "provider", nativePalette.fingerprint(),
                DhReplacementInput.ANALYTIC_MATERIAL_POLICY, "unit-faces-v1", 1, vector(PIN));
    }

    @Test void replacementCertificateBindsEveryAuthorityDimensionAndExactNeighborRevision() {
        var vector = vector(PIN);
        var original = certificate(vector);
        String baseline = original.identity();
        assertTrue(baseline.matches("[0-9a-f]{64}"));
        assertEquals(3, original.originalRevision());
        var changedNeighbor = new ArrayList<>(vector);
        var before = changedNeighbor.getFirst();
        changedNeighbor.set(0, new CubeVersion(before.position(), before.leaseGeneration(), before.revision() + 1));
        assertEquals(vector.stream().mapToLong(CubeVersion::revision).max().orElseThrow(),
                changedNeighbor.stream().mapToLong(CubeVersion::revision).max().orElseThrow());
        assertNotEquals(baseline, certificate(changedNeighbor).identity(), "A maximum revision cannot attest a halo");
        var changedLease = new ArrayList<>(vector);
        changedLease.set(0, new CubeVersion(before.position(), 9999, before.revision()));
        assertNotEquals(baseline, certificate(changedLease).identity());
        assertEquals(original.dependencyIdentity(), certificate(changedLease).dependencyIdentity(),
                "Durable summary dependencies survive intentional capture-lease retirement");
        assertNotEquals(original.dependencyIdentity(), certificate(changedNeighbor).dependencyIdentity());
        for (var changed : List.of(
                new ReplacementCertificate(new WorldIdentity(82, "review-provider", "v1", "review-source", "review-palette"), 11, 7, PIN, "store", "provider", PALETTE, "opaque-v1", "unit-faces-v1", 1, vector),
                new ReplacementCertificate(WORLD, 12, 7, PIN, "store", "provider", PALETTE, "opaque-v1", "unit-faces-v1", 1, vector),
                new ReplacementCertificate(WORLD, 11, 8, PIN, "store", "provider", PALETTE, "opaque-v1", "unit-faces-v1", 1, vector),
                new ReplacementCertificate(WORLD, 11, 7, PIN, "other-store", "provider", PALETTE, "opaque-v1", "unit-faces-v1", 1, vector),
                new ReplacementCertificate(WORLD, 11, 7, PIN, "store", "other-provider", PALETTE, "opaque-v1", "unit-faces-v1", 1, vector),
                new ReplacementCertificate(WORLD, 11, 7, PIN, "store", "provider", "b".repeat(64), "opaque-v1", "unit-faces-v1", 1, vector),
                new ReplacementCertificate(WORLD, 11, 7, PIN, "store", "provider", PALETTE, "opaque-v2", "unit-faces-v1", 1, vector),
                new ReplacementCertificate(WORLD, 11, 7, PIN, "store", "provider", PALETTE, "opaque-v1", "unit-faces-v2", 1, vector),
                new ReplacementCertificate(WORLD, 11, 7, PIN, "store", "provider", PALETTE, "opaque-v1", "unit-faces-v1", 2, vector),
                new ReplacementCertificate(WORLD, 11, 7, PIN.offset(1, 0, 0), "store", "provider", PALETTE, "opaque-v1", "unit-faces-v1", 1, vector(PIN.offset(1, 0, 0))))) {
            assertNotEquals(baseline, changed.identity());
        }
    }

    @Test void replacementCertificateRejectsIncompleteReorderedDuplicateAndUneditedHalos() {
        var vector = vector(PIN);
        assertThrows(IllegalArgumentException.class, () -> certificate(vector.subList(0, 26)));
        var reordered = new ArrayList<>(vector); Collections.swap(reordered, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> certificate(reordered));
        var duplicate = new ArrayList<>(vector); duplicate.set(0, duplicate.get(1));
        assertThrows(IllegalArgumentException.class, () -> certificate(duplicate));
        var duplicateLease = new ArrayList<>(vector);
        duplicateLease.set(0, new CubeVersion(duplicateLease.getFirst().position(), duplicateLease.get(1).leaseGeneration(), 0));
        assertThrows(IllegalArgumentException.class, () -> certificate(duplicateLease));
        var unchanged = vector.stream().map(v -> new CubeVersion(v.position(), v.leaseGeneration(), 0)).toList();
        assertThrows(IllegalArgumentException.class, () -> certificate(unchanged));
        var wrongHalo = new ArrayList<>(vector);
        wrongHalo.set(0, new CubeVersion(PIN.offset(-2, -1, -1), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> certificate(wrongHalo));
        var mutable = new ArrayList<>(vector); var captured = certificate(mutable); String identity = captured.identity();
        mutable.clear();
        assertEquals(27, captured.dependencies().size());
        assertEquals(identity, captured.identity());
        assertThrows(UnsupportedOperationException.class, () -> captured.dependencies().clear());
    }

    private static List<CubeVersion> vector(CubePos original) {
        var result = new ArrayList<CubeVersion>();
        long lease = 1;
        for (var position : box(original, 1)) result.add(new CubeVersion(position, lease++, position.equals(original) ? 3 : 0));
        return List.copyOf(result);
    }
    private static ReplacementCertificate certificate(List<CubeVersion> dependencies) {
        return new ReplacementCertificate(WORLD, 11, 7, PIN, "store", "provider", PALETTE,
                "opaque-v1", "unit-faces-v1", 1, dependencies);
    }

    private static List<CubeVersion> nativeManifest(CubePos center) {
        var versions = new ArrayList<CubeVersion>(); long lease = 1000;
        for (var p : box(center, 2)) versions.add(new CubeVersion(p, lease++, p.equals(PIN) ? 3 : 0));
        return List.copyOf(versions);
    }

    @Test void everyPinModeAndAdjacentDirectionMatchesIndependentCoordinateOracle() {
        for (CubePos current : List.of(PIN, PIN.offset(1, 0, 0), PIN.offset(3, -3, 3),
                PIN.offset(-8, 2, -8), PIN.offset(8, -2, 8))) {
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                CubePos candidate = current.offset(dx, dy, dz);
                for (boolean before : new boolean[]{false, true}) for (boolean after : new boolean[]{false, true}) {
                    var plan = RollingCubePlan.create(PIN, current, candidate, before, after);
                    Set<CubePos> oldOwners = box(current, 2), newOwners = box(candidate, 2);
                    Set<CubePos> oldTargets = box(current, 1), newTargets = box(candidate, 1);
                    if (before) { oldOwners.addAll(box(PIN, 1)); oldTargets.add(PIN); }
                    if (after) { newOwners.addAll(box(PIN, 1)); newTargets.add(PIN); }
                    assertEquals(List.copyOf(oldOwners), plan.committedOwners());
                    assertEquals(List.copyOf(newOwners), plan.nextCommittedOwners());
                    assertEquals(List.copyOf(union(oldOwners, newOwners)), plan.stagedOwners());
                    assertEquals(List.copyOf(difference(newOwners, oldOwners)), plan.addedOwners());
                    assertEquals(List.copyOf(difference(oldOwners, newOwners)), plan.retiredOwners());
                    assertEquals(List.copyOf(oldTargets), plan.committedTargets());
                    assertEquals(List.copyOf(union(oldTargets, newTargets)), plan.stagedTargets());
                    assertTrue(plan.stagedOwners().size() <= 213);
                    assertTrue(plan.committedOwners().size() <= 152);
                    assertTrue(plan.nextCommittedOwners().size() <= 152);
                    assertTrue(oldTargets.size() + newTargets.size() - (before && after ? 1 : 0) <= 55);
                    plan.validate();
                }
            }
        }
    }

    @Test void disjointDiagonalPinTransitionsHaveEightyEightDeltasWithoutExpandingPhysicalPools() {
        CubePos current = PIN.offset(20, 20, 20), next = current.offset(1, 1, 1);
        var retiring = RollingCubePlan.create(PIN, current, next, true, false);
        assertEquals(88, retiring.retiredOwners().size());
        assertEquals(61, retiring.addedOwners().size());
        assertEquals(213, retiring.stagedOwners().size());
        assertEquals(125, retiring.nextCommittedOwners().size());
        var restoring = RollingCubePlan.create(PIN, current, next, false, true);
        assertEquals(88, restoring.addedOwners().size());
        assertEquals(61, restoring.retiredOwners().size());
        assertEquals(213, restoring.stagedOwners().size());
        assertEquals(152, restoring.nextCommittedOwners().size());
        assertEquals(213, RollingCubePlan.MAX_STAGED_OWNERS);
        assertEquals(55, RollingCubePlan.MAX_STAGED_MESH_GENERATIONS);
        assertEquals(61, RollingCubePlan.MAX_ADDED_OR_RETIRED_OWNERS);
    }

    @Test void sameCenterRestoreHasExactPinDependenciesAndNoImplicitStationaryModeChange() {
        CubePos distant = PIN.offset(-12, 0, -12);
        var restore = RollingCubePlan.create(PIN, distant, distant, false, true);
        assertEquals(List.copyOf(box(PIN, 1)), restore.addedOwners());
        assertTrue(restore.retiredOwners().isEmpty());
        assertEquals(152, restore.stagedOwners().size());
        assertFalse(restore.committedTargets().contains(PIN));
        assertTrue(restore.stagedTargets().contains(PIN));
        assertThrows(IllegalArgumentException.class,
                () -> RollingCubePlan.create(PIN, distant, null, false, true));
        assertThrows(IllegalArgumentException.class,
                () -> RollingCubePlan.create(PIN, distant, null, true, false));
        var unpinned = RollingCubePlan.stationary(PIN, distant, false);
        assertEquals(125, unpinned.committedOwners().size());
        assertEquals(27, unpinned.committedTargets().size());
        assertTrue(unpinned.pinnedOwners().isEmpty());
        assertTrue(unpinned.pinnedTargets().isEmpty());
    }

    @Test void pinRetirementDoesNotExpandOriginalEditableInteriorOrNativeEnvelopes() {
        var unpinned = RollingCubePlan.stationary(PIN, PIN.offset(12, 0, 12), false);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            assertEquals(x > 0 && x < 15 && y > 0 && y < 15 && z > 0 && z < 15,
                    unpinned.isEditableInterior(PIN.minBlockX() + x, PIN.minBlockY() + y, PIN.minBlockZ() + z));
        }
        assertFalse(unpinned.isEditableInterior(PIN.offset(12, 0, 12).minBlockX() + 4,
                PIN.minBlockY() + 4, PIN.offset(12, 0, 12).minBlockZ() + 4));
        for (int y : new int[]{RollingCubePlan.MIN_WINDOW_CENTER_Y, RollingCubePlan.MAX_WINDOW_CENTER_Y}) {
            CubePos edge = new CubePos(RollingCubePlan.NATIVE_XZ_MIN + 2, y, RollingCubePlan.NATIVE_XZ_MAX - 2);
            assertEquals(125, RollingCubePlan.stationary(PIN, edge, false).committedOwners().size());
            assertThrows(IllegalArgumentException.class,
                    () -> RollingCubePlan.stationary(PIN, edge.offset(-1, 0, 0), false));
            assertThrows(IllegalArgumentException.class,
                    () -> RollingCubePlan.stationary(PIN, edge.offset(0, 0, 1), false));
            int outside = y == RollingCubePlan.MIN_WINDOW_CENTER_Y ? -1 : 1;
            assertThrows(IllegalArgumentException.class,
                    () -> RollingCubePlan.stationary(PIN, edge.offset(0, outside, 0), false));
        }
    }

    private static Set<CubePos> box(CubePos center, int radius) {
        var result = new TreeSet<CubePos>();
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++)
            for (int z = -radius; z <= radius; z++) result.add(center.offset(x, y, z));
        return result;
    }
    private static Set<CubePos> union(Set<CubePos> first, Set<CubePos> second) {
        var result = new TreeSet<>(first); result.addAll(second); return result;
    }
    private static Set<CubePos> difference(Set<CubePos> first, Set<CubePos> second) {
        var result = new TreeSet<>(first); result.removeAll(second); return result;
    }
}
