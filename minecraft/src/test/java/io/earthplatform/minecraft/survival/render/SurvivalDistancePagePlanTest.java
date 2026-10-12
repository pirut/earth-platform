package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.WorldManifest;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.survival.source.MarinSurvivalResources;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Source-backed finite census and membership proofs; no loaded-model, GL, performance or publication claim. */
class SurvivalDistancePagePlanTest {
    @TempDir static Path temporary;
    private static MarinSurvivalDefinition source;
    private static final SurvivalDistancePagePlan.Limits LIMITS = new SurvivalDistancePagePlan.Limits(
            1_100_000, 4_225, 16_384, 1_000_000, 3_600_000, 3_600_000, 4096, 64L << 20);
    private static final String EMPTY_ID = hash("explicit complete empty test overlay");
    private static final int[][] NEIGHBORS = {{0, 0, 0}, {-1, 0, 0}, {1, 0, 0},
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    @BeforeAll static void loadOriginalVerifiedSource() throws Exception {
        source = MarinSurvivalResources.installAndLoad(temporary.resolve("source"));
    }
    private static String hash(String text) {
        return WorldManifest.sha256(text.getBytes(StandardCharsets.UTF_8));
    }
    private static SurvivalDistancePagePlan.CompleteEditSet edits(long revision, Set<CubePos> cubes) {
        return SurvivalDistancePagePlan.CompleteEditSet.copyOf(revision, EMPTY_ID, cubes, LIMITS);
    }
    private static SurvivalDistancePagePlan plan(int distance) {
        return SurvivalDistancePagePlan.build(source, source.anchor().center(), distance, distance, edits(0, Set.of()), LIMITS);
    }
    private static SurvivalDistancePagePlan.Page page(SurvivalDistancePagePlan plan, CubePos cube) {
        return plan.pageIndex().get(SurvivalVisualPage.containing(cube).minimum());
    }
    private static boolean groundMember(SurvivalDistancePagePlan plan, CubePos cube) {
        var p = page(plan, cube); return p != null && p.containsGroundMember(cube);
    }
    private static boolean treeMember(SurvivalDistancePagePlan plan, CubePos cube) {
        var p = page(plan, cube); return p != null && p.containsTreeMember(cube);
    }

    @Test void everyDistanceTwoThroughThirtyTwoKeepsExactSixteenMetreEdgesAndLegitimateCaps() {
        var b = source.envelope();
        for (int d = 2; d <= 32; d++) {
            var choice = new SurvivalDistancePagePlan.DistanceChoice(d, d, 0);
            assertTrue(choice.selectedDistanceEffective());
            for (int x : new int[]{b.minCenterX(), source.anchor().center().x(), b.maxCenterXExclusive() - 1})
                for (int z : new int[]{b.minCenterZ(), source.anchor().center().z(), b.maxCenterZExclusive() - 1}) {
                    var focus = new CubePos(x, source.anchor().center().y(), z);
                    var bounds = SurvivalDistancePagePlan.selectedBounds(focus, choice);
                    assertEquals(((long) x - d) * 16, bounds.minX());
                    assertEquals(((long) z - d) * 16, bounds.minZ());
                    assertEquals(((long) x + d + 1) * 16, bounds.maxXExclusive());
                    assertEquals(((long) z + d + 1) * 16, bounds.maxZExclusive());
                    assertEquals((2L * d + 1) * 16, bounds.width());
                    assertEquals((2L * d + 1) * (2L * d + 1) * 256, bounds.columns());
                    for (int dx : new int[]{-d, d}) for (int dz : new int[]{-d, d})
                        assertTrue(bounds.containsHorizontalCube(focus.offset(dx, 0, dz)));
                    assertFalse(bounds.containsHorizontalCube(focus.offset(-d - 1, 0, 0)));
                    assertFalse(bounds.containsHorizontalCube(focus.offset(0, 0, d + 1)));
                    for (int dx : new int[]{-d - 1, d + 1}) for (int dz : new int[]{-d - 1, d + 1})
                        assertTrue(source.permitsVisualCube(focus.offset(dx, 0, dz)));
                }
        }
        var capped = new SurvivalDistancePagePlan.DistanceChoice(24, 12, 12);
        assertFalse(capped.selectedDistanceEffective());
        var native12 = plan(12);
        var cappedPlan = SurvivalDistancePagePlan.build(source, source.anchor().center(), capped, edits(0, Set.of()), LIMITS);
        assertEquals(native12.bounds(), cappedPlan.bounds());
        assertEquals(native12.pages(), cappedPlan.pages());
        assertNotEquals(native12.identity(), cappedPlan.identity());
        assertThrows(SurvivalDistancePagePlan.Refusal.class, () -> new SurvivalDistancePagePlan.DistanceChoice(24, 12, 0));
        assertThrows(SurvivalDistancePagePlan.Refusal.class, () -> new SurvivalDistancePagePlan.DistanceChoice(24, 12, 16));
        for (int invalid : new int[]{Integer.MIN_VALUE, -1, 0, 1, 33, Integer.MAX_VALUE})
            assertThrows(SurvivalDistancePagePlan.Refusal.class,
                    () -> new SurvivalDistancePagePlan.DistanceChoice(invalid, invalid, 0));
        assertThrows(SurvivalDistancePagePlan.Refusal.class, () -> SurvivalDistancePagePlan.selectedBounds(
                new CubePos(Integer.MAX_VALUE, 0, 0), new SurvivalDistancePagePlan.DistanceChoice(32, 32, 0)));
        assertThrows(SurvivalDistancePagePlan.Refusal.class, () -> new SurvivalDistancePagePlan.Bounds(Long.MIN_VALUE, 0, Long.MAX_VALUE, 1));
    }

    @Test void groundAndCliffMembersAreCompleteAndTreesNeverRemoveGroundMembers() {
        var plan = plan(2); var bounds = plan.bounds();
        for (long z = bounds.minZ(); z < bounds.maxZExclusive(); z++)
            for (long x = bounds.minX(); x < bounds.maxXExclusive(); x++) {
                long top = source.surfaceY(x, z);
                long low = top;
                for (long[] delta : new long[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}})
                    low = Math.min(low, source.surfaceY(x + delta[0], z + delta[1]) + 1);
                assertEquals(top, plan.surfaceY(x, z));
                assertEquals(low, plan.lowestExposedGroundY(x, z));
                for (long cy = Math.floorDiv(low, 16); cy <= Math.floorDiv(top, 16); cy++)
                    assertTrue(groundMember(plan, CubePos.fromBlock(x, cy * 16, z)), "Missing top or cardinal cliff cube");
            }
        int featureCells = 0, groundUnderFeatures = 0;
        for (var tree : source.visualTreesIntersecting(bounds.minX(), bounds.minZ(), bounds.maxXExclusive(), bounds.maxZExclusive()))
            for (long z = Math.max(bounds.minZ(), tree.z() - 2); z < Math.min(bounds.maxZExclusive(), tree.z() + 3); z++)
                for (long x = Math.max(bounds.minX(), tree.x() - 2); x < Math.min(bounds.maxXExclusive(), tree.x() + 3); x++)
                    for (long y = tree.groundY() + 1; y <= tree.topY() + 1; y++) {
                        if (tree.stateAt(x, y, z).isAir() || y <= source.surfaceY(x, z)) continue;
                        assertFalse(source.visualStateAt(x, y, z).isAir());
                        assertTrue(treeMember(plan, CubePos.fromBlock(x, y, z)), "Missing exact cross-cube tree fragment");
                        assertTrue(groundMember(plan, CubePos.fromBlock(x, source.surfaceY(x, z), z)), "Foliage must not erase source ground selection");
                        featureCells++; groundUnderFeatures++;
                    }
        assertTrue(featureCells > 0); assertTrue(groundUnderFeatures > 0);
        assertFalse(plan.containsMember(CubePos.fromBlock(source.anchor().x(), -9600, source.anchor().z())), "No solid-interior extrusion");
        assertFalse(plan.containsMember(CubePos.fromBlock(source.anchor().x(), 8000, source.anchor().z())), "No empty sky extrusion");
    }

    @Test void arbitrarySignedEditCubesAndSixFaceNeighborsArePreservedWithoutExtrusion() {
        CubePos focus = source.anchor().center();
        var below = focus.offset(0, -500, 0); var above = focus.offset(1, 400, 1);
        var justOutside = focus.offset(-3, -200, 0);
        var complete = Set.of(below, above, justOutside);
        var edited = SurvivalDistancePagePlan.build(source, focus, 2, 2, edits(17, complete), LIMITS);
        var plain = plan(2);
        var expected = new TreeSet<CubePos>();
        for (CubePos cube : complete) for (int[] d : NEIGHBORS) {
            CubePos neighbor = cube.offset(d[0], d[1], d[2]);
            if (edited.bounds().containsHorizontalCube(neighbor)) expected.add(neighbor);
        }
        var actual = new TreeSet<CubePos>();
        for (var p : edited.pages()) for (CubePos cube : p.members())
            if (p.containsEditException(cube)) actual.add(cube);
        assertEquals(expected, actual);
        assertEquals(15, actual.size());
        assertFalse(edited.containsMember(focus.offset(0, -100, 0)), "A low edit cannot extrude all cubes up to source terrain");
        for (var p : plain.pages()) {
            var current = edited.pageIndex().get(p.address().minimum());
            assertEquals(p.groundMask(), current.groundMask());
            assertEquals(p.treeMask(), current.treeMask());
        }
        assertEquals(17, edited.edits().contentRevision());
        assertEquals(3, edited.census().completeEditedCubes());
        assertNotEquals(plain.identity(), edited.identity());
    }

    @Test void negativePageBitsAndExactSparseDependencyUnionRemainBounded() {
        var address = new SurvivalVisualPage(new CubePos(-8, -12, -16));
        var all = new SurvivalDistancePagePlan.Page(address, -1L, 0, 0);
        assertEquals(64, all.memberCount());
        assertEquals(address.members(), all.members());
        assertEquals(address.dependencies(), all.dependencies());
        assertEquals(216, all.dependencies().size());
        var exterior = new TreeSet<>(all.dependencies()); exterior.removeAll(all.members());
        assertEquals(152, exterior.size());
        for (int bit = 0; bit < 64; bit++) {
            var one = new SurvivalDistancePagePlan.Page(address, 1L << bit, 0, 0);
            var cube = address.minimum().offset(bit % 4, bit / 16, bit / 4 % 4);
            assertEquals(Set.of(cube), one.members());
            assertEquals(27, one.dependencies().size());
            assertTrue(one.containsGroundMember(cube));
            assertFalse(one.containsTreeMember(cube));
            assertFalse(one.containsEditException(cube));
            assertFalse(one.containsMember(cube.offset(4, 0, 0)));
        }
        assertThrows(SurvivalDistancePagePlan.Refusal.class,
                () -> new SurvivalDistancePagePlan.Page(address, 0, 0, 0));
    }

    @Test void editExposureAndMeshingHaloRequireTwoCubesOfVerifiedVerticalSupport() {
        CubePos focus = source.anchor().center();
        int bottom = source.envelope().minCenterY(), top = source.envelope().maxCenterYExclusive() - 1;
        for (int y : new int[]{bottom, bottom + 1, top - 1, top}) {
            var cube = new CubePos(focus.x(), y, focus.z());
            assertTrue(source.permitsCenter(cube), "Generation permission is weaker than a complete editable visual halo");
            assertTrue(source.permitsVisualCube(cube));
            assertThrows(MissingTerrainException.class, () -> SurvivalDistancePagePlan.build(source,
                    focus, 2, 2, edits(1, Set.of(cube)), LIMITS));
        }
        for (int y : new int[]{bottom + 2, top - 2}) {
            var cube = new CubePos(focus.x(), y, focus.z());
            var plan = SurvivalDistancePagePlan.build(source, focus, 2, 2, edits(1, Set.of(cube)), LIMITS);
            assertTrue(plan.containsMember(cube.offset(0, -1, 0)));
            assertTrue(plan.containsMember(cube.offset(0, 1, 0)));
            for (var p : plan.pages()) for (CubePos dependency : p.dependencies())
                assertTrue(source.permitsVisualCube(dependency));
        }
    }

    @Test void semanticDigestBindsCompleteContentAndIndexButNotCounterOnlyChanges() {
        CubePos focus = source.anchor().center();
        var caller = new HashSet<CubePos>(); caller.add(focus.offset(20, -100, 0));
        var first = edits(1, caller); caller.clear();
        assertEquals(1, first.modifiedCubes().size());
        assertThrows(UnsupportedOperationException.class, () -> first.modifiedCubes().clear());
        var a = SurvivalDistancePagePlan.build(source, focus, 2, 2, first, LIMITS);
        var b = SurvivalDistancePagePlan.build(source, focus, 2, 2, edits(2, first.modifiedCubes()), LIMITS);
        var c = SurvivalDistancePagePlan.build(source, focus, 2, 2, edits(1, Set.of(focus.offset(21, -100, 0))), LIMITS);
        assertEquals(a.pages(), b.pages()); assertEquals(a.pages(), c.pages());
        // Counters remain observable authority metadata, not semantic geometry identity.
        assertEquals(1, a.edits().contentRevision()); assertEquals(2, b.edits().contentRevision());
        assertEquals(a.identity(), b.identity()); assertNotEquals(a.identity(), c.identity());
        var changedContent = SurvivalDistancePagePlan.CompleteEditSet.copyOf(1,
                hash("different complete offscreen block content"), first.modifiedCubes(), LIMITS);
        var d = SurvivalDistancePagePlan.build(source, focus, 2, 2, changedContent, LIMITS);
        assertEquals(a.pages(), d.pages()); assertNotEquals(a.identity(), d.identity());
        assertEquals(a.identity(), SurvivalDistancePagePlan.build(source, focus, 2, 2, first, LIMITS).identity());
        assertEquals(source.visualIdentity(), a.sourceIdentity());
        assertNotEquals(source.identity(), a.sourceIdentity());
        assertThrows(UnsupportedOperationException.class, () -> a.pages().clear());
        assertThrows(UnsupportedOperationException.class, () -> a.pageIndex().clear());
        assertThrows(UnsupportedOperationException.class, () -> a.pages().getFirst().members().clear());
        assertThrows(UnsupportedOperationException.class, () -> a.pages().getFirst().dependencies().clear());
        assertThrows(SurvivalDistancePagePlan.Refusal.class,
                () -> SurvivalDistancePagePlan.CompleteEditSet.copyOf(-1, EMPTY_ID, Set.of(), LIMITS));
        assertThrows(SurvivalDistancePagePlan.Refusal.class,
                () -> SurvivalDistancePagePlan.CompleteEditSet.copyOf(0, "unbound", Set.of(), LIMITS));
    }

    @Test void finiteTwelveTwentyFourAndThirtyTwoCensusUsesVerifiedSourceAndExactHaloCounts() {
        for (int distance : new int[]{12, 24, 32}) {
            var plan = plan(distance); var c = plan.census();
            long width = (2L * distance + 1) * 16;
            assertEquals(width * width, c.selectedColumns());
            assertEquals((width + 2) * (width + 2), c.sampledColumns());
            assertEquals((2L * distance + 1) * (2L * distance + 1), c.horizontalCubes());
            assertTrue(c.members() >= c.horizontalCubes(), "Every horizontal source cube has a ground surface");
            assertTrue(c.members() < c.horizontalCubes() * 32, "Finite source census must remain sparse rather than extruding the whole signed-Y domain");
            assertTrue(c.pages() > 0); assertTrue(c.treeMembers() > 0);
            assertEquals(0, c.editExceptionMembers());
            assertEquals(c.dependencyReferences() - c.members(), c.haloReferences());
            long members = 0, dependencies = 0, exterior = 0;
            var horizontal = new HashSet<String>();
            for (var p : plan.pages()) {
                assertTrue(p.memberCount() <= 64);
                Set<CubePos> actualDependencies = p.dependencies();
                assertTrue(actualDependencies.size() <= 216);
                assertTrue(actualDependencies.containsAll(p.members()));
                assertTrue(p.address().dependencies().containsAll(actualDependencies));
                for (CubePos cube : p.members()) {
                    assertTrue(plan.bounds().containsHorizontalCube(cube), "Physical page rounding must not expand ownership");
                    horizontal.add(cube.x() + ":" + cube.z());
                }
                Set<CubePos> physicalMembers = p.address().members();
                for (CubePos dependency : actualDependencies) {
                    assertTrue(source.permitsVisualCube(dependency));
                    if (!physicalMembers.contains(dependency)) exterior++;
                }
                members += p.memberCount(); dependencies += actualDependencies.size();
            }
            assertEquals(c.horizontalCubes(), horizontal.size());
            assertEquals(c.members(), members); assertEquals(c.dependencyReferences(), dependencies);
            assertEquals(c.exteriorHaloReferences(), exterior);
            assertTrue(exterior <= c.pages() * 152);
            assertTrue(c.metadataBytes() <= LIMITS.maxMetadataBytes());
            System.out.println("SURVIVAL_DISTANCE_SOURCE_CENSUS distance=" + distance + " source="
                    + plan.sourceIdentity() + " plan=" + plan.identity() + " " + c);
        }
    }

    @Test void everyExplicitBudgetRejectsRatherThanClampingOrReturningPartialPlans() {
        var focus = source.anchor().center(); var empty = edits(0, Set.of());
        var baseline = plan(2).census();
        long[] admitted = {LIMITS.maxSampledColumns(), LIMITS.maxHorizontalCubes(), LIMITS.maxPages(),
                LIMITS.maxMembers(), LIMITS.maxDependencyReferences(), LIMITS.maxHaloReferences(),
                LIMITS.maxEditedCubes(), LIMITS.maxMetadataBytes()};
        long[] tight = {baseline.sampledColumns() - 1, baseline.horizontalCubes() - 1, baseline.pages() - 1,
                baseline.members() - 1, baseline.dependencyReferences() - 1, 1, LIMITS.maxEditedCubes(), 1};
        for (int parameter : new int[]{0, 1, 2, 3, 4, 5, 7}) {
            long[] values = admitted.clone(); values[parameter] = tight[parameter];
            var limits = new SurvivalDistancePagePlan.Limits(values[0], values[1], values[2], values[3],
                    values[4], values[5], values[6], values[7]);
            assertThrows(SurvivalDistancePagePlan.Refusal.class,
                    () -> SurvivalDistancePagePlan.build(source, focus, 2, 2, empty, limits), "Budget field " + parameter);
        }
        var noEdits = new SurvivalDistancePagePlan.Limits(admitted[0], admitted[1], admitted[2], admitted[3],
                admitted[4], admitted[5], 0, admitted[7]);
        assertThrows(SurvivalDistancePagePlan.Refusal.class,
                () -> SurvivalDistancePagePlan.CompleteEditSet.copyOf(1, EMPTY_ID, Set.of(focus), noEdits));
        assertThrows(SurvivalDistancePagePlan.Refusal.class,
                () -> SurvivalDistancePagePlan.build(source, focus, 2, 2, edits(1, Set.of(focus)), noEdits));
        assertThrows(MissingTerrainException.class, () -> SurvivalDistancePagePlan.build(source,
                new CubePos(Integer.MAX_VALUE, 8, Integer.MIN_VALUE), 2, 2, empty, LIMITS));
        assertThrows(MissingTerrainException.class, () -> SurvivalDistancePagePlan.build(source, focus,
                2, 2, edits(1, Set.of(new CubePos(focus.x(), Integer.MAX_VALUE, focus.z()))), LIMITS));
        assertThrows(SurvivalDistancePagePlan.Refusal.class,
                () -> SurvivalDistancePagePlan.build(source, focus, 24, 12, empty, LIMITS));
    }
}
