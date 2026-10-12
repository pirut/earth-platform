package io.earthplatform.minecraft.storage;

import io.earthplatform.compat.neoforge.NamesOnlyTempFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.earthplatform.minecraft.storage.VanishingJournalFileSystem.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independently asserted failure identities, suppression, and all-or-failure collection boundaries. */
class TempScanFailureBoundaryIndependentTest {
    @TempDir Path temporary;

    @Test void failureAfterOneMatchingChildCannotReturnTheAlreadyCollectedPrefix() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root.neoforge-tmp"));
        Files.createFile(root.resolve("a.neoforge-tmp"));
        Files.createFile(root.resolve("b.neoforge-tmp"));
        IOException iteration = new IOException("independent failure after one returned child");
        try (var provider = new VanishingJournalFileSystem(root)) {
            provider.failDirectoryIterationAfter(1, iteration);
            UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> collect(provider.root()));
            assertSame(iteration, thrown.getCause());
            assertEquals(1, provider.count(ROOT_ATTRIBUTES));
            assertEquals(0, provider.count(CHILD_ATTRIBUTES));
            assertEquals(1, provider.count(DIRECTORY_OPEN));
            assertEquals(1, provider.count(DIRECTORY_CLOSE));
            assertEquals(0, provider.count(DELETE));
        }
    }

    @Test void closeFailureInvalidatesAnOtherwiseCompleteCollection() throws Exception {
        Files.createFile(temporary.resolve("selected.neoforge-tmp"));
        IOException close = new IOException("independent close failure");
        try (var provider = new VanishingJournalFileSystem(temporary)) {
            provider.fail(DIRECTORY_CLOSE, close);
            UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> collect(provider.root()));
            assertSame(close, thrown.getCause());
            assertEquals(1, provider.count(DIRECTORY_CLOSE));
            assertEquals(0, provider.count(CHILD_ATTRIBUTES));
        }
    }

    @Test void iterationFailureIsPrimaryAndCloseFailureIsRetainedAsSuppressed() throws Exception {
        Files.createFile(temporary.resolve("selected.neoforge-tmp"));
        IOException iteration = new IOException("independent iteration failure");
        IOException close = new IOException("independent secondary close failure");
        try (var provider = new VanishingJournalFileSystem(temporary)) {
            provider.failDirectoryIterationAfter(1, iteration).fail(DIRECTORY_CLOSE, close);
            UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> collect(provider.root()));
            assertSame(iteration, thrown.getCause());
            assertEquals(1, thrown.getSuppressed().length);
            assertSame(close, assertInstanceOf(UncheckedIOException.class, thrown.getSuppressed()[0]).getCause());
            assertEquals(1, provider.count(DIRECTORY_CLOSE));
        }
    }

    @Test void matchingRootCannotHideEagerDirectoryOpenFailure() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("selected.neoforge-tmp"));
        IOException opening = new IOException("independent directory open failure");
        try (var provider = new VanishingJournalFileSystem(root)) {
            provider.fail(DIRECTORY_OPEN, opening);
            IOException thrown = assertThrows(IOException.class,
                    () -> NamesOnlyTempFiles.namesOnlyDepthOne(provider.root(), 1, (path, attributes) -> true));
            assertSame(opening, thrown);
            assertEquals(1, provider.count(ROOT_ATTRIBUTES));
            assertEquals(1, provider.count(DIRECTORY_OPEN));
            assertEquals(0, provider.count(DIRECTORY_ITERATE));
            assertEquals(0, provider.count(DIRECTORY_CLOSE));
        }
    }

    @Test void shortCircuitConsumerMustStillObserveDirectoryCloseFailure() throws Exception {
        IOException close = new IOException("independent early close failure");
        try (var provider = new VanishingJournalFileSystem(temporary)) {
            provider.fail(DIRECTORY_CLOSE, close);
            UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> {
                try (var stream = NamesOnlyTempFiles.namesOnlyDepthOne(provider.root(), 1, (path, attributes) -> true)) {
                    assertEquals(provider.root(), stream.findFirst().orElseThrow());
                }
            });
            assertSame(close, thrown.getCause());
            assertEquals(0, provider.count(DIRECTORY_ITERATE));
            assertEquals(1, provider.count(DIRECTORY_CLOSE));
        }
    }

    @Test void arbitraryPredicateFailureMustNotBeMisreportedAsDirectoryIo() throws Exception {
        RuntimeException predicate = new IllegalArgumentException("independent predicate failure");
        IOException close = new IOException("independent close after predicate failure");
        try (var provider = new VanishingJournalFileSystem(temporary)) {
            provider.fail(DIRECTORY_CLOSE, close);
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
                try (var stream = NamesOnlyTempFiles.namesOnlyDepthOne(provider.root(), 1,
                        (path, attributes) -> { throw predicate; })) {
                    stream.toList();
                }
            });
            assertSame(predicate, thrown);
            assertEquals(1, thrown.getSuppressed().length);
            assertSame(close, assertInstanceOf(UncheckedIOException.class, thrown.getSuppressed()[0]).getCause());
            assertEquals(0, provider.count(DIRECTORY_ITERATE));
            assertEquals(1, provider.count(DIRECTORY_CLOSE));
        }
    }

    private static List<Path> collect(Path root) throws IOException {
        try (var stream = NamesOnlyTempFiles.namesOnlyDepthOne(root, 1,
                (path, ignored) -> path.getFileName().toString().endsWith(".neoforge-tmp"))) {
            return stream.toList();
        }
    }
}
