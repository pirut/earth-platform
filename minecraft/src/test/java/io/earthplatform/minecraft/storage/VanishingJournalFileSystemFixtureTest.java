package io.earthplatform.minecraft.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.earthplatform.minecraft.storage.VanishingJournalFileSystem.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Validates the fault injector itself independently of the production fix. */
class VanishingJournalFileSystemFixtureTest {
    private static final String JOURNAL = "DistantHorizons.sqlite-journal";
    @TempDir Path temporary;

    @Test void filesFindStatsUnrelatedJournalBeforeItsNamePredicateCanRejectIt() throws Exception {
        Path journal = Files.writeString(temporary.resolve(JOURNAL), "unrelated DH bytes");
        try (var fixture = new VanishingJournalFileSystem(temporary).failChildAttributes(JOURNAL)) {
            Set<String> predicateNames = new HashSet<>();
            UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> {
                try (var found = Files.find(fixture.root(), 1, (path, attributes) -> {
                    String name = path.getFileName().toString();
                    predicateNames.add(name);
                    return name.endsWith(".tmp");
                })) {
                    found.toList();
                }
            });
            assertInstanceOf(NoSuchFileException.class, failure.getCause());
            assertFalse(predicateNames.contains(JOURNAL), "The unrelated name never reached the predicate");
            assertEquals(1, fixture.childAttributeReads(JOURNAL));
            assertEquals(1, fixture.count(ROOT_ATTRIBUTES));
            assertEquals(1, fixture.count(DIRECTORY_OPEN));
            assertEquals(1, fixture.count(DIRECTORY_CLOSE));
            assertEquals("unrelated DH bytes", Files.readString(journal), "The fault must never delete a real file");
        }
    }

    @Test void namesOnlyEnumerationYieldsWrappedChildrenWithoutReadingTheirAttributes() throws Exception {
        Files.writeString(temporary.resolve(JOURNAL), "DH");
        Files.writeString(temporary.resolve("level.dat.tmp"), "candidate");
        try (var fixture = new VanishingJournalFileSystem(temporary).failChildAttributes(JOURNAL)) {
            Set<String> names = new HashSet<>();
            try (var entries = Files.newDirectoryStream(fixture.root())) {
                for (Path entry : entries) {
                    assertSame(fixture, entry.getFileSystem());
                    assertSame(fixture.provider(), entry.getFileSystem().provider());
                    assertEquals(fixture.root(), entry.getParent());
                    names.add(entry.getFileName().toString());
                }
                assertThrows(IllegalStateException.class, entries::iterator);
            }
            assertEquals(Set.of(JOURNAL, "level.dat.tmp"), names);
            assertEquals(0, fixture.count(CHILD_ATTRIBUTES));
            assertEquals(0, fixture.count(ROOT_ATTRIBUTES));
            assertEquals(1, fixture.count(DIRECTORY_CLOSE));
            assertEquals("DH", Files.readString(temporary.resolve(JOURNAL)));
        }
    }

    @Test void delayedIterationFailureComesAfterOneEntryAndCloseStillRuns() throws Exception {
        Files.writeString(temporary.resolve("one.tmp"), "one");
        Files.writeString(temporary.resolve("two.tmp"), "two");
        IOException injected = new IOException("iteration fault");
        try (var fixture = new VanishingJournalFileSystem(temporary).failAfter(DIRECTORY_ITERATE, 1, injected)) {
            var emitted = new ArrayList<Path>();
            DirectoryIteratorException failure = assertThrows(DirectoryIteratorException.class, () -> {
                try (var entries = Files.newDirectoryStream(fixture.root())) {
                    var iterator = entries.iterator();
                    assertTrue(iterator.hasNext());
                    assertTrue(iterator.hasNext(), "Repeated look-ahead must not consume another fault allowance");
                    emitted.add(iterator.next());
                    iterator.hasNext();
                }
            });
            assertSame(injected, failure.getCause());
            assertEquals(1, emitted.size());
            assertEquals(2, fixture.count(DIRECTORY_ITERATE));
            assertEquals(1, fixture.count(DIRECTORY_CLOSE));
            assertTrue(Files.exists(temporary.resolve("one.tmp")));
            assertTrue(Files.exists(temporary.resolve("two.tmp")));
        }
    }

    @Test void closeFaultIsVisibleAndBackingStreamIsClosedOnlyOnce() throws Exception {
        IOException injected = new IOException("close fault");
        try (var fixture = new VanishingJournalFileSystem(temporary).fail(DIRECTORY_CLOSE, injected)) {
            var entries = Files.newDirectoryStream(fixture.root());
            assertSame(injected, assertThrows(IOException.class, entries::close));
            entries.close();
            assertEquals(1, fixture.count(DIRECTORY_CLOSE));
        }
    }

    @Test void rootAttributesCanFailIndependentlyAndResetDoesNotEraseRealData() throws Exception {
        IOException injected = new IOException("root attributes fault");
        try (var fixture = new VanishingJournalFileSystem(temporary).fail(ROOT_ATTRIBUTES, injected)) {
            assertSame(injected, assertThrows(IOException.class,
                    () -> Files.readAttributes(fixture.root(), BasicFileAttributes.class)));
            assertEquals(1, fixture.count(ROOT_ATTRIBUTES));
            assertEquals(0, fixture.count(CHILD_ATTRIBUTES));
            fixture.resetFaults();
            fixture.resetCounts();
            assertTrue(Files.readAttributes(fixture.root(), BasicFileAttributes.class).isDirectory());
            assertEquals(1, fixture.count(ROOT_ATTRIBUTES));
        }
    }

    @Test void realFileAndSymlinkRootAttributesAreNotFabricatedAsDirectories() throws Exception {
        Path file = Files.writeString(temporary.resolve("ordinary-file"), "bytes");
        try (var fixture = new VanishingJournalFileSystem(file)) {
            assertTrue(Files.readAttributes(fixture.root(), BasicFileAttributes.class).isRegularFile());
            assertThrows(NotDirectoryException.class, () -> Files.newDirectoryStream(fixture.root()));
        }
        Path directory = Files.createDirectory(temporary.resolve("directory"));
        Path link = Files.createSymbolicLink(temporary.resolve("directory-link"), directory);
        try (var fixture = new VanishingJournalFileSystem(link)) {
            assertTrue(Files.readAttributes(fixture.root(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isSymbolicLink());
            assertTrue(Files.readAttributes(fixture.root(), BasicFileAttributes.class).isDirectory());
            try (var entries = Files.find(fixture.root(), 1, (path, attributes) -> true)) {
                assertEquals(java.util.List.of(fixture.root()), entries.toList());
            }
            assertEquals(0, fixture.count(DIRECTORY_OPEN), "Default find must not traverse a symbolic-link root");
        }
    }

    @Test void writeForceMoveAndDeleteFaultsReachTheCallerBeforeThoseOperations() throws Exception {
        Path real = Files.writeString(temporary.resolve("source.tmp"), "original");
        IOException injected = new IOException("operation fault");
        try (var fixture = new VanishingJournalFileSystem(temporary)) {
            Path source = fixture.wrap(real);
            fixture.fail(WRITE, injected);
            try (var output = Files.newOutputStream(source, StandardOpenOption.WRITE)) {
                assertSame(injected, assertThrows(IOException.class, () -> output.write(new byte[]{1})));
            }
            assertEquals("original", Files.readString(real));
            fixture.resetFaults();
            fixture.fail(FORCE, injected);
            try (var channel = FileChannel.open(source, StandardOpenOption.WRITE)) {
                assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{'O'})));
                assertSame(injected, assertThrows(IOException.class, () -> channel.force(true)));
            }
            fixture.resetFaults();
            fixture.fail(MOVE, injected);
            assertSame(injected, assertThrows(IOException.class,
                    () -> Files.move(source, fixture.root().resolve("destination"))));
            fixture.resetFaults();
            fixture.fail(DELETE, injected);
            assertSame(injected, assertThrows(IOException.class, () -> Files.delete(source)));
            assertEquals("Original", Files.readString(real));
            assertFalse(Files.exists(temporary.resolve("destination")));
            assertEquals(2, fixture.count(WRITE));
            assertEquals(1, fixture.count(FORCE));
            assertEquals(1, fixture.count(MOVE));
            assertEquals(1, fixture.count(DELETE));
            assertEquals(2, fixture.count(CHANNEL_OPEN));
        }
    }

    @Test void closingFixtureNeverClosesTheRealTemporaryFilesystem() throws Exception {
        var fixture = new VanishingJournalFileSystem(temporary);
        fixture.close();
        assertFalse(fixture.isOpen());
        assertTrue(temporary.getFileSystem().isOpen());
        assertEquals("safe", Files.readString(Files.writeString(temporary.resolve("after-close"), "safe")));
    }
}
