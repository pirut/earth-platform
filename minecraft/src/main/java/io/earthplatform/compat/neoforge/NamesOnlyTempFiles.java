package io.earthplatform.compat.neoforge;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/**
 * Name-only depth-one traversal for the exactly validated NeoForge temp-file predicate.
 * This is not a general replacement for Files.find: the pinned predicate ignores its
 * attributes argument. The callsite validator and activation policy own that premise.
 * No error is swallowed, no file is deleted here, and no save queue is reset.
 */
public final class NamesOnlyTempFiles {
    private static final java.util.concurrent.atomic.AtomicLong CALLS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong ROOT_DIRECTORIES = new java.util.concurrent.atomic.AtomicLong();
    private NamesOnlyTempFiles() {}
    /** Two saturating process counters only. Counts do not attest successful scanning or saving. */
    public record Evidence(long calls, long rootDirectoryCalls) {}
    public static Evidence evidence() {
        long directories = ROOT_DIRECTORIES.get();
        return new Evidence(CALLS.get(), directories);
    }
    private static void increment(java.util.concurrent.atomic.AtomicLong counter) {
        counter.updateAndGet(value -> value == Long.MAX_VALUE ? value : value + 1);
    }

    /**
     * Preserve the root's NOFOLLOW_LINKS classification and inclusion. Immediate children
     * are filtered by their names without querying attributes of unrelated transient
     * files. Child predicate attributes are deliberately null under the pinned contract.
     * The returned stream owns its directory stream; the caller must close it. Real
     * root/open/iteration/close errors remain visible to the original caller.
     */
    public static Stream<Path> namesOnlyDepthOne(Path root, int maxDepth,
            BiPredicate<Path, BasicFileAttributes> nameOnlyPredicate, FileVisitOption... options)
            throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(nameOnlyPredicate, "nameOnlyPredicate");
        Objects.requireNonNull(options, "options");
        if (maxDepth != 1 || options.length != 0) {
            throw new IllegalArgumentException("Pinned temp-file traversal requires depth one without link following");
        }
        increment(CALLS);
        BasicFileAttributes rootAttributes = Files.readAttributes(root, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        Stream<Path> rootEntry = Stream.of(root).filter(path -> nameOnlyPredicate.test(path, rootAttributes));
        if (!rootAttributes.isDirectory()) return rootEntry;
        increment(ROOT_DIRECTORIES);

        // Open before yielding the root, matching the depth-one walk's directory-open
        // boundary. Files.list is lazy and performs no per-child attributes reads.
        Stream<Path> children = Files.list(root);
        try {
            return Stream.concat(rootEntry, children.filter(path -> nameOnlyPredicate.test(path, null)));
        } catch (RuntimeException | Error failure) {
            try { children.close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
