package io.earthplatform.compat.neoforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent, original differential review. The oracle is the JDK traversal on a
 * stable real filesystem, not a second implementation of the candidate helper.
 * Fault-injection tests elsewhere establish the intentional transient-child change.
 */
class NamesOnlyTempFilesDifferentialReviewTest {
    @TempDir Path temporary;

    @Test void generatedLiteralNameAndPrefixMatrixMatchesTheUnmodifiedJdkTraversal() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("matrix"));
        List<String> stems = List.of("", "map", "map.dat", "map.dat.backup", "MAP.dat", ".hidden",
                "literal[1]*?", "space name", "snowman-\u2603", "\u03bb-data", "DistantHorizons.sqlite-journal");
        List<String> endings = List.of("", ".neoforge-tmp", ".NEOFORGE-TMP", ".neoforge-tmp.extra");
        Set<Path> allCandidates = new HashSet<>();
        for (String stem : stems) for (String ending : endings) {
            String name = stem + ending;
            if (!name.isEmpty()) allCandidates.add(Files.createFile(root.resolve(name)));
        }
        List<String> prefixes = new ArrayList<>();
        prefixes.add(null);
        prefixes.addAll(List.of("", "map", "map.dat", "MAP", ".", "literal[1]*?", "space ",
                "snowman-\u2603", "\u03bb", "no-such-prefix", "map.dat.neoforge-tmp.extra"));
        for (String prefix : prefixes) {
            List<Path> actual = compare(root, prefix);
            Set<Path> explicitlySelected = new HashSet<>();
            for (Path candidate : allCandidates) {
                String name = candidate.getFileName().toString();
                if (name.endsWith(".neoforge-tmp") && (prefix == null || name.startsWith(prefix)))
                    explicitlySelected.add(candidate);
            }
            assertEquals(explicitlySelected, Set.copyOf(actual), "Literal prefix: " + prefix);
        }
        assertTrue(Files.exists(root.resolve("DistantHorizons.sqlite-journal")),
                "Traversal itself must never delete an unrelated file");
    }

    @Test void matchingDirectoryRootIsFirstAndOnlyImmediateEntriesCanBeSelected() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("map.dat.root.neoforge-tmp"));
        Path direct = Files.createFile(root.resolve("map.dat.direct.neoforge-tmp"));
        Path nested = Files.createDirectory(root.resolve("map.dat.directory.neoforge-tmp"));
        Files.createFile(nested.resolve("map.dat.grandchild.neoforge-tmp"));
        Files.createFile(root.resolve("other.neoforge-tmp"));
        List<Path> result = compare(root, "map.dat");
        assertEquals(root, result.getFirst(), "Files.find includes a matching start path before its children");
        assertEquals(Set.of(root, direct, nested), Set.copyOf(result));
    }

    @Test void regularFileRootIsSelectedOnlyByItsOwnName() throws Exception {
        Path selected = Files.createFile(temporary.resolve("map.dat.temporary.neoforge-tmp"));
        Path ordinary = Files.createFile(temporary.resolve("map.dat"));
        assertEquals(List.of(selected), compare(selected, null));
        assertEquals(List.of(selected), compare(selected, "map.dat"));
        assertEquals(List.of(), compare(selected, "different"));
        assertEquals(List.of(), compare(ordinary, null));
    }

    @Test void directoryFileAndDanglingSymlinkRootsAreNeverFollowed() throws Exception {
        Path targetDirectory = Files.createDirectory(temporary.resolve("target-directory"));
        Files.createFile(targetDirectory.resolve("nested.neoforge-tmp"));
        Path targetFile = Files.createFile(temporary.resolve("target-file"));
        List<Path> targets = List.of(targetDirectory, targetFile, temporary.resolve("absent-target"));
        for (int index = 0; index < targets.size(); index++) {
            Path link = Files.createSymbolicLink(temporary.resolve("root-" + index + ".neoforge-tmp"), targets.get(index));
            assertEquals(List.of(link), compare(link, null));
            assertEquals(List.of(), compare(link, "different"));
        }
        Path unselected = Files.createSymbolicLink(temporary.resolve("ordinary-link"), targetDirectory);
        assertEquals(List.of(), compare(unselected, null));
    }

    @Test void selectedChildrenIncludeDirectoriesAndAllKindsOfSymlinksWithoutDescending() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("children"));
        Path ordinaryTarget = Files.createFile(temporary.resolve("ordinary-target"));
        Path directoryTarget = Files.createDirectory(temporary.resolve("directory-target"));
        Files.createFile(directoryTarget.resolve("must-not-visit.neoforge-tmp"));
        Path matchingDirectory = Files.createDirectory(root.resolve("selected-dir.neoforge-tmp"));
        Files.createFile(matchingDirectory.resolve("must-not-visit-either.neoforge-tmp"));
        Path fileLink = Files.createSymbolicLink(root.resolve("selected-file-link.neoforge-tmp"), ordinaryTarget);
        Path directoryLink = Files.createSymbolicLink(root.resolve("selected-dir-link.neoforge-tmp"), directoryTarget);
        Path danglingLink = Files.createSymbolicLink(root.resolve("selected-dangling.neoforge-tmp"), temporary.resolve("missing"));
        Files.createSymbolicLink(root.resolve("ordinary-link"), directoryTarget);
        assertEquals(Set.of(matchingDirectory, fileLink, directoryLink, danglingLink), Set.copyOf(compare(root, "selected")));
    }

    @Test void absentRootRemainsAnEagerCheckedFailureRatherThanAnEmptySuccessfulScan() {
        Path absent = temporary.resolve("missing.neoforge-tmp");
        NoSuchFileException baseline = assertThrows(NoSuchFileException.class,
                () -> Files.find(absent, 1, predicate(null)));
        NoSuchFileException candidate = assertThrows(NoSuchFileException.class,
                () -> NamesOnlyTempFiles.namesOnlyDepthOne(absent, 1, predicate(null)));
        assertEquals(baseline.getFile(), candidate.getFile());
        assertEquals(baseline.getOtherFile(), candidate.getOtherFile());
    }

    @Test void lexicalPathIdentityIsPreservedInsteadOfCanonicalizingTheRequestedRoot() throws Exception {
        Files.createDirectory(temporary.resolve("outer"));
        Path target = Files.createDirectory(temporary.resolve("target.neoforge-tmp"));
        Files.createFile(target.resolve("child.neoforge-tmp"));
        Path lexicalRoot = temporary.resolve("outer").resolve("..").resolve("target.neoforge-tmp");
        List<Path> actual = compare(lexicalRoot, null);
        assertEquals(lexicalRoot, actual.getFirst());
        assertEquals(Set.of(lexicalRoot, lexicalRoot.resolve("child.neoforge-tmp")), Set.copyOf(actual));
        assertNotEquals(lexicalRoot.normalize(), actual.getFirst());
    }

    @Test void enumerationIsReadOnlyEvenWhenEverythingSelectedIsAMatchingDirectoryOrLink() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root.neoforge-tmp"));
        Path directory = Files.createDirectory(root.resolve("directory.neoforge-tmp"));
        Path file = Files.writeString(directory.resolve("preserve.dat"), "independent preservation witness");
        Path link = Files.createSymbolicLink(root.resolve("link.neoforge-tmp"), directory);
        assertEquals(Set.of(root, directory, link), Set.copyOf(compare(root, null)));
        assertEquals("independent preservation witness", Files.readString(file));
        assertTrue(Files.isSymbolicLink(link));
    }

    private static List<Path> compare(Path root, String prefix) throws IOException {
        List<Path> expected;
        try (Stream<Path> baseline = Files.find(root, 1, predicate(prefix))) {
            expected = baseline.toList();
        }
        List<Path> actual;
        try (Stream<Path> candidate = NamesOnlyTempFiles.namesOnlyDepthOne(root, 1, predicate(prefix))) {
            actual = candidate.toList();
        }
        assertEquals(expected.size(), new HashSet<>(expected).size(), "Oracle contains duplicate paths");
        assertEquals(actual.size(), new HashSet<>(actual).size(), "Candidate contains duplicate paths");
        assertEquals(Set.copyOf(expected), Set.copyOf(actual), "Root=" + root + ", prefix=" + prefix);
        if (expected.contains(root)) assertEquals(root, actual.getFirst());
        return actual;
    }

    // The pinned contract is deliberately stated in this independent test, not
    // imported from production or inferred from the implementation's output.
    private static BiPredicate<Path, BasicFileAttributes> predicate(String prefix) {
        return (path, ignored) -> {
            String name = path.getFileName().toString();
            return name.endsWith(".neoforge-tmp") && (prefix == null || name.startsWith(prefix));
        };
    }
}
