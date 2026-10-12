package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReplacementWorkspaceStoreTest {
    private static final CubePos ORIGINAL = new CubePos(-5, 8, -7);
    @TempDir Path temporary;

    @Test void exactPairHasDisjointCanonicalHalosAndNoNewResidencyBudget() {
        var workspaces = ReplacementWorkspaces.two(ORIGINAL);
        assertEquals(List.of(ORIGINAL, ORIGINAL.offset(4, 0, 0)), workspaces.positions());
        assertEquals(27, workspaces.dependencies(ORIGINAL).size());
        assertEquals(27, workspaces.dependencies(workspaces.second()).size());
        assertTrue(workspaces.dependencies(ORIGINAL).stream().noneMatch(workspaces.dependencies(workspaces.second())::contains));
        assertEquals(workspaces, ReplacementWorkspaces.canonical(workspaces.positions()));
        assertTrue(workspaces.insideWindow(workspaces.second(), ORIGINAL.offset(3, 0, 0)));
        assertTrue(workspaces.insideWindow(workspaces.second(), ORIGINAL.offset(5, 0, 0)));
        assertFalse(workspaces.insideWindow(workspaces.second(), ORIGINAL.offset(2, 0, 0)));
        assertFalse(workspaces.insideWindow(ORIGINAL.offset(2, 0, 0), ORIGINAL.offset(2, 0, 0)));
        assertEquals(213, RollingCubePlan.MAX_STAGED_OWNERS);
        assertEquals(55, RollingCubePlan.MAX_STAGED_MESH_GENERATIONS);
    }

    @Test void rejectsReorderingExtraRegionsOverlapAndNativeOverflow() {
        var workspaces = ReplacementWorkspaces.two(ORIGINAL);
        assertThrows(IllegalArgumentException.class, () -> ReplacementWorkspaces.canonical(List.of(workspaces.second(), ORIGINAL)));
        assertThrows(IllegalArgumentException.class, () -> ReplacementWorkspaces.canonical(List.of(ORIGINAL)));
        assertThrows(IllegalArgumentException.class, () -> ReplacementWorkspaces.canonical(List.of(ORIGINAL, ORIGINAL.offset(3, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> ReplacementWorkspaces.two(new CubePos(RollingCubePlan.NATIVE_XZ_MAX - 2, 8, 0)));
        assertThrows(ArithmeticException.class, () -> ReplacementWorkspaces.two(new CubePos(Integer.MAX_VALUE, 8, 0)));
    }

    @Test void durablePairIndexIsPortableAndRetainsBothPositions() throws Exception {
        var workspaces = ReplacementWorkspaces.two(ORIGINAL);
        var first = ReplacementStoreIdentity.openTwo(temporary, workspaces);
        assertEquals(first, ReplacementStoreIdentity.openTwo(temporary, workspaces));
        assertEquals(workspaces.identity(), first.workspaceIdentity());
        assertTrue(first.indexIdentity().matches("[0-9a-f]{64}"));
        Path relocated = Files.createDirectory(temporary.resolve("relocated"));
        Files.copy(temporary.resolve(ReplacementStoreIdentity.TWO_FILE), relocated.resolve(ReplacementStoreIdentity.TWO_FILE));
        assertEquals(first, ReplacementStoreIdentity.openTwo(relocated, workspaces));
        assertThrows(IOException.class, () -> ReplacementStoreIdentity.openTwo(relocated, ReplacementWorkspaces.two(ORIGINAL.offset(4, 0, 0))));
        assertEquals(first, ReplacementStoreIdentity.openTwo(relocated, workspaces), "Rejected scope change cannot rewrite the index");
    }

    @Test void malformedTruncatedOversizedOrIncompleteIndexFailsClosed() throws Exception {
        var workspaces = ReplacementWorkspaces.two(ORIGINAL);
        ReplacementStoreIdentity.openTwo(temporary, workspaces);
        Path index = temporary.resolve(ReplacementStoreIdentity.TWO_FILE);
        String good = Files.readString(index);
        for (String bad : List.of(good.substring(0, good.length() - 1), good.replace("-1 8 -7\n", ""),
                good.replace(workspaces.identity(), "0".repeat(64)), "x".repeat(ReplacementStoreIdentity.MAX_TWO_BYTES + 1))) {
            Files.writeString(index, bad);
            assertThrows(IOException.class, () -> ReplacementStoreIdentity.openTwo(temporary, workspaces));
        }
    }

    @Test void legacyStoreRemainsReadableButCannotBeSilentlyReinterpreted() throws Exception {
        String legacy = ReplacementStoreIdentity.open(temporary, ORIGINAL);
        assertThrows(IOException.class, () -> ReplacementStoreIdentity.openTwo(temporary, ReplacementWorkspaces.two(ORIGINAL)));
        assertEquals(legacy, ReplacementStoreIdentity.open(temporary, ORIGINAL));
        assertFalse(Files.exists(temporary.resolve(ReplacementStoreIdentity.TWO_FILE)));
    }
}
