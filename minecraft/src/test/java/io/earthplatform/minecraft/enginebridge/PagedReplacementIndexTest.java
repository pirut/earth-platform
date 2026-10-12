package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Encoded-bound, source-identity and serial summary transaction tests; no graphical runtime. */
class PagedReplacementIndexTest {
    private static final String POLICY = "a".repeat(64);
    private static final CubePos FIRST = new CubePos(-5, 8, -7), SECOND = FIRST.offset(1, 0, 0);
    @TempDir Path temporary;

    @Test void encodedBoundsAreExactAndIndependentOfCatalogueCapacity() throws Exception {
        assertEquals(84, PagedReplacementIndex.ENTRY_BYTES);
        assertEquals(84, PagedReplacementIndex.PAGE_HEADER_BYTES);
        assertEquals(2772, PagedReplacementIndex.MAX_PAGE_BYTES);
        assertEquals(9332, PagedReplacementIndex.MAX_METADATA_BYTES);
        assertEquals(304, PagedReplacementIndex.MAX_INTENT_BYTES);
        assertEquals(8192, PagedReplacementIndex.MAX_ENTRIES);
        assertEquals(2, PagedReplacementIndex.MAX_LIVE_MATCHES);
        assertEquals(1, PagedReplacementIndex.MAX_ACTIVE_CURSORS);
        var index = fresh();
        index.seedCatalogueForTests(catalogue(33));
        List<Path> pages = pageFiles(temporary);
        assertEquals(2, pages.size());
        assertTrue(pages.stream().anyMatch(p -> size(p) == 2772));
        assertTrue(pages.stream().anyMatch(p -> size(p) == 168));
        assertEquals(116 + 2 * 36, Files.size(temporary.resolve(PagedReplacementIndex.ROOT_FILE)));
        var token = index.beginQuery(PagedReplacementIndex.Bounds.all(), 33);
        var first = index.next(token);
        assertEquals(PagedReplacementIndex.Status.INCOMPLETE, first.status());
        assertEquals(32, first.entries().size()); assertNull(first.completion());
        var last = index.next(token);
        assertEquals(PagedReplacementIndex.Status.COMPLETE, last.status());
        assertEquals(1, last.entries().size()); assertEquals(33, last.completion().resultCount());
        assertTrue(index.isCurrent(last.completion()));
        assertTrue(index.issuedCompletionCurrent(last.completion()));
    }

    @Test void serialInitializationPersistsExplicitCleanSummariesAndStableIdentity() throws Exception {
        var index = fresh();
        assertFalse(index.isInitialized());
        var empty = complete(index, 2);
        assertEquals(0, empty.completion().resultCount());
        String stable = index.indexIdentity();
        var before = index.snapshot(); index.initializeSummaries(before, clean());
        assertTrue(index.isInitialized()); assertFalse(index.isCurrent(before));
        assertEquals(stable, index.indexIdentity());
        var result = complete(index, 2);
        assertEquals(clean(), result.entries());
        assertEquals(PagedReplacementIndex.resultIdentity(clean()), result.completion().resultIdentity());
        var reopened = PagedReplacementIndex.open(temporary, POLICY);
        assertEquals(index.snapshot(), reopened.snapshot());
        assertEquals(clean(), complete(reopened, 2).entries());
        assertFalse(reopened.isCurrent(result.completion()), "Completion is bound to the issuing instance");
        assertFalse(reopened.issuedCompletionCurrent(result.completion()));
        assertThrows(IOException.class, () -> reopened.initializeSummaries(reopened.snapshot(), clean()));
    }

    @Test void editRefreshesOverlappingNeighborSummaryWithoutInventingNeighborRevision() throws Exception {
        var index = initialized(); var original = index.snapshot();
        var originalCompletion = complete(index, 2).completion();
        List<PagedReplacementIndex.Entry> changed = List.of(entry(FIRST, 1, 3), entry(SECOND, 0, 4));
        index.begin(original, changed);
        assertTrue(Files.exists(temporary.resolve(PagedReplacementIndex.INTENT_FILE)));
        assertEquals(304, Files.size(temporary.resolve(PagedReplacementIndex.INTENT_FILE)));
        assertFalse(index.isCurrent(original)); assertFalse(index.isCurrent(originalCompletion));
        assertFalse(index.issuedCompletionCurrent(originalCompletion));
        assertEquals(PagedReplacementIndex.Status.REFUSED, index.next(index.beginQuery(PagedReplacementIndex.Bounds.all(), 2)).status());
        assertThrows(IOException.class, () -> PagedReplacementIndex.open(temporary, POLICY));
        index.confirm();
        var committed = complete(index, 2);
        assertFalse(index.issuedCompletionCurrent(originalCompletion));
        assertTrue(index.issuedCompletionCurrent(committed.completion()));
        assertEquals(changed, committed.entries());
        assertEquals(0, committed.entries().get(1).revision());
        assertNotEquals(clean().get(1).summaryIdentity(), committed.entries().get(1).summaryIdentity());
        assertEquals(original.generation() + 1, index.snapshot().generation());
        assertEquals(original.indexIdentity(), index.indexIdentity());
        assertNotEquals(original.rootIdentity(), index.snapshot().rootIdentity());
        assertEquals(1, pageFiles(temporary).size(), "Previous generation pages are removed after commit");
        assertFalse(Files.exists(temporary.resolve(PagedReplacementIndex.INTENT_FILE)));
        assertEquals(changed, complete(PagedReplacementIndex.open(temporary, POLICY), 2).entries());
    }

    @Test void staleExtraSkippedAndForeignChangesCannotWriteIntent() throws Exception {
        var index = initialized(); var snapshot = index.snapshot();
        byte[] before = Files.readAllBytes(temporary.resolve(PagedReplacementIndex.ROOT_FILE));
        List<List<PagedReplacementIndex.Entry>> bad = List.of(
                List.of(entry(FIRST, 2, 3), entry(SECOND, 0, 4)),
                clean(), List.of(entry(FIRST, 1, 3)),
                List.of(entry(FIRST, 1, 3), entry(SECOND.offset(1, 0, 0), 0, 4)),
                List.of(entry(SECOND, 0, 2), entry(FIRST, 1, 3)),
                List.of(entry(FIRST, 1, 3), entry(SECOND, 1, 4)));
        for (var changes : bad) {
            assertThrows(IOException.class, () -> index.begin(snapshot, changes));
            assertFalse(Files.exists(temporary.resolve(PagedReplacementIndex.INTENT_FILE)));
            assertArrayEquals(before, Files.readAllBytes(temporary.resolve(PagedReplacementIndex.ROOT_FILE)));
        }
        index.begin(snapshot, List.of(entry(FIRST, 1, 3), entry(SECOND, 0, 4))); index.confirm();
        assertThrows(IOException.class, () -> index.begin(snapshot, List.of(entry(FIRST, 2, 5), entry(SECOND, 0, 6))));
    }

    @Test void impossibleDeclaredCollectionSizeRejectsBeforeAnyElementAccessOrAllocation() throws Exception {
        var index = fresh();
        var malicious = new AbstractList<PagedReplacementIndex.Entry>() {
            @Override public int size() { return Integer.MAX_VALUE; }
            @Override public PagedReplacementIndex.Entry get(int i) { fail("Huge declared count was indexed"); return null; }
        };
        assertThrows(IOException.class, () -> index.initializeSummaries(index.snapshot(), malicious));
        assertThrows(IllegalArgumentException.class, () -> PagedReplacementIndex.resultIdentity(malicious));
        assertFalse(Files.exists(temporary.resolve(PagedReplacementIndex.INTENT_FILE)));
    }

    @Test void boundedListPathsNeverTrustCustomIteratorOrToArrayAllocation() throws Exception {
        var index = fresh();
        var adversarial = new AbstractList<PagedReplacementIndex.Entry>() {
            @Override public int size() { return 2; }
            @Override public PagedReplacementIndex.Entry get(int i) { return clean().get(i); }
            @Override public Iterator<PagedReplacementIndex.Entry> iterator() { throw new AssertionError("Unbounded iterator invoked"); }
            @Override public Object[] toArray() { throw new AssertionError("Unbounded toArray invoked"); }
        };
        assertEquals(PagedReplacementIndex.resultIdentity(clean()), PagedReplacementIndex.resultIdentity(adversarial));
        index.initializeSummaries(index.snapshot(), adversarial);
        assertEquals(clean(), complete(index, 2).entries());
    }

    @Test void maliciousMetadataCountsFailBeforeCountDerivedAllocations() throws Exception {
        var index = initialized();
        Path root = temporary.resolve(PagedReplacementIndex.ROOT_FILE);
        byte[] valid = Files.readAllBytes(root);
        for (int bad : new int[]{-1, Integer.MAX_VALUE, Integer.MIN_VALUE, PagedReplacementIndex.MAX_ENTRIES + 1}) {
            byte[] corrupt = valid.clone(); ByteBuffer.wrap(corrupt).putInt(76, bad); checksum(corrupt); Files.write(root, corrupt);
            assertThrows(IOException.class, () -> PagedReplacementIndex.open(temporary, POLICY));
        }
        for (int bad : new int[]{-1, Integer.MAX_VALUE, Integer.MIN_VALUE, PagedReplacementIndex.MAX_PAGES + 1}) {
            byte[] corrupt = valid.clone(); ByteBuffer.wrap(corrupt).putInt(80, bad); checksum(corrupt); Files.write(root, corrupt);
            assertThrows(IOException.class, () -> PagedReplacementIndex.open(temporary, POLICY));
        }
        Files.write(root, valid);
        assertTrue(index.isCurrent(index.snapshot()));
    }

    @Test void sourcePolicyIsCheckedBeforeFilteringInvisibleEntries() throws Exception {
        var index = fresh();
        var foreign = new PagedReplacementIndex.Entry(FIRST, 0, "b".repeat(64), "1".repeat(64));
        assertThrows(IOException.class, () -> index.initializeSummaries(index.snapshot(), List.of(foreign, entry(SECOND, 0, 2))));
        assertFalse(Files.exists(temporary.resolve(PagedReplacementIndex.INTENT_FILE)));
        assertThrows(IOException.class, () -> PagedReplacementIndex.open(temporary, "b".repeat(64)));
    }

    @Test void generationExhaustionIsRejectedBeforeIntentAndBeforeCubeSave() throws Exception {
        initialized(); Path root = temporary.resolve(PagedReplacementIndex.ROOT_FILE);
        byte[] bytes = Files.readAllBytes(root); ByteBuffer.wrap(bytes).putLong(52, Long.MAX_VALUE); checksum(bytes); Files.write(root, bytes);
        // A modified generation cannot make the old generation's pages complete; no write may follow.
        var index = PagedReplacementIndex.open(temporary, POLICY);
        assertThrows(IOException.class, () -> index.begin(index.snapshot(), List.of(entry(FIRST, 1, 3), entry(SECOND, 0, 4))));
        assertFalse(Files.exists(temporary.resolve(PagedReplacementIndex.INTENT_FILE)));
        Path emptyDir = Files.createDirectory(temporary.resolve("empty"));
        PagedReplacementIndex.open(emptyDir, POLICY);
        byte[] empty = Files.readAllBytes(emptyDir.resolve(PagedReplacementIndex.ROOT_FILE));
        ByteBuffer.wrap(empty).putLong(52, Long.MAX_VALUE); checksum(empty); Files.write(emptyDir.resolve(PagedReplacementIndex.ROOT_FILE), empty);
        var exhausted = PagedReplacementIndex.open(emptyDir, POLICY);
        assertThrows(IOException.class, () -> exhausted.initializeSummaries(exhausted.snapshot(), clean()));
        assertFalse(Files.exists(emptyDir.resolve(PagedReplacementIndex.INTENT_FILE)));
    }

    @Test void changedOrMissingIntentCannotBeConfirmedAndUnexpectedIntentCannotComplete() throws Exception {
        var index = initialized(); index.begin(index.snapshot(), List.of(entry(FIRST, 1, 3), entry(SECOND, 0, 4)));
        Files.write(temporary.resolve(PagedReplacementIndex.INTENT_FILE), new byte[]{1});
        assertThrows(IOException.class, index::confirm);
        assertFalse(index.isCurrent(completeSnapshotFromDisk()));
        assertThrows(IOException.class, () -> PagedReplacementIndex.open(temporary, POLICY));
        Path other = Files.createDirectory(temporary.resolve("other"));
        var fresh = PagedReplacementIndex.open(other, POLICY);
        Files.write(other.resolve(PagedReplacementIndex.INTENT_FILE), new byte[]{1});
        assertEquals(PagedReplacementIndex.Status.REFUSED, fresh.next(fresh.beginQuery(PagedReplacementIndex.Bounds.all(), 2)).status());
    }

    @Test void freshIdentityNeverAdoptsLegacyOrUnsealedCubeData() throws Exception {
        for (String file : List.of("replacement-region-v1.index", "replacement-regions-v2.index", "replacement-adjacent-regions-v1.index")) {
            Path store = Files.createDirectory(temporary.resolve("legacy-fixture-" + file)); Files.writeString(store.resolve(file), "legacy");
            assertThrows(IOException.class, () -> PagedReplacementIndex.open(store, POLICY));
            assertFalse(Files.exists(store.resolve(PagedReplacementIndex.ROOT_FILE)));
        }
        var index = fresh(); Path cubes = Files.createDirectory(temporary.resolve("cubes")); Files.writeString(cubes.resolve("edited.cube"), "existing");
        assertThrows(IOException.class, () -> PagedReplacementIndex.open(temporary, POLICY));
        assertFalse(index.isInitialized());
        assertThrows(IOException.class, () -> index.initializeSummaries(index.snapshot(), clean()));
        assertFalse(Files.exists(temporary.resolve(PagedReplacementIndex.INTENT_FILE)));
    }

    @Test void fullSignedAndFullHeightBoundsUseHalfOpenThreeDimensionalIntersection() {
        var negative = new CubePos(-1, -1, -1);
        assertTrue(new PagedReplacementIndex.Bounds(-16, -16, -16, 0, 0, 0).intersects(negative));
        assertFalse(new PagedReplacementIndex.Bounds(0, -16, -16, 16, 0, 0).intersects(negative));
        assertFalse(new PagedReplacementIndex.Bounds(-16, 0, -16, 0, 16, 0).intersects(negative));
        assertFalse(new PagedReplacementIndex.Bounds(-16, -16, 0, 0, 0, 16).intersects(negative));
        assertTrue(new PagedReplacementIndex.Bounds(-1, -1, -1, 1, 1, 1).intersects(negative));
        assertTrue(PagedReplacementIndex.Bounds.all().intersects(new CubePos(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE)));
        assertTrue(PagedReplacementIndex.Bounds.all().intersects(new CubePos(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> new PagedReplacementIndex.Bounds(Long.MIN_VALUE, 0, 0, Long.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PagedReplacementIndex.Bounds(0, 0, 0, 1, 0, 1));
    }

    @Test void resultIdentityIsCanonicalAndIncludesActualSummaryIdentityAndPolicy() {
        String clean = PagedReplacementIndex.resultIdentity(clean());
        assertNotEquals(clean, PagedReplacementIndex.resultIdentity(List.of(entry(FIRST, 0, 3), entry(SECOND, 0, 2))));
        assertNotEquals(clean, PagedReplacementIndex.resultIdentity(List.of(
                new PagedReplacementIndex.Entry(FIRST, 0, "b".repeat(64), "1".repeat(64)), entry(SECOND, 0, 2))));
        assertThrows(IllegalArgumentException.class, () -> PagedReplacementIndex.resultIdentity(List.of(entry(SECOND, 0, 2), entry(FIRST, 0, 1))));
        assertThrows(IllegalArgumentException.class, () -> new PagedReplacementIndex.Entry(FIRST, 0, POLICY, "revision-0"));
    }

    @Test void streamingCatalogueCapacityRefusesBeforeReadingAn8193rdEntry() throws Exception {
        var index = fresh(); AtomicInteger reads = new AtomicInteger();
        Iterator<PagedReplacementIndex.Entry> infinite = new Iterator<>() {
            public boolean hasNext() { return true; }
            public PagedReplacementIndex.Entry next() { int i = reads.getAndIncrement(); return entry(new CubePos(i, 0, 0), 0, 1); }
        };
        assertThrows(IOException.class, () -> index.seedCatalogueForTests(infinite));
        assertEquals(PagedReplacementIndex.MAX_ENTRIES, reads.get());
        assertEquals(PagedReplacementIndex.MAX_PAGES, pageFiles(temporary).size());
        assertTrue(Files.exists(temporary.resolve(PagedReplacementIndex.INTENT_FILE)));
        assertThrows(IOException.class, () -> PagedReplacementIndex.open(temporary, POLICY));
    }

    private PagedReplacementIndex fresh() throws IOException { return PagedReplacementIndex.open(temporary, POLICY); }
    private PagedReplacementIndex initialized() throws IOException {
        var index = fresh(); index.initializeSummaries(index.snapshot(), clean()); return index;
    }
    private static List<PagedReplacementIndex.Entry> clean() { return List.of(entry(FIRST, 0, 1), entry(SECOND, 0, 2)); }
    private static PagedReplacementIndex.Entry entry(CubePos p, long revision, int summary) {
        return new PagedReplacementIndex.Entry(p, revision, POLICY, Integer.toHexString(summary).repeat(64));
    }
    private static Iterator<PagedReplacementIndex.Entry> catalogue(int count) {
        return new Iterator<>() {
            int i;
            public boolean hasNext() { return i < count; }
            public PagedReplacementIndex.Entry next() { return entry(new CubePos(i++, 0, 0), 0, 1); }
        };
    }
    private static PagedReplacementIndex.QueryPage complete(PagedReplacementIndex index, int cap) {
        var token = index.beginQuery(PagedReplacementIndex.Bounds.all(), cap);
        var page = index.next(token);
        assertEquals(PagedReplacementIndex.Status.COMPLETE, page.status(), page.reason()); return page;
    }
    private static List<Path> pageFiles(Path directory) throws IOException {
        try (var paths = Files.list(directory)) { return paths.filter(p -> p.getFileName().toString().startsWith("replacement-paged-v1-page-")).sorted().toList(); }
    }
    private static long size(Path path) { try { return Files.size(path); } catch (IOException e) { throw new AssertionError(e); } }
    private static void checksum(byte[] bytes) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256"); digest.update(bytes, 0, bytes.length - 32);
        System.arraycopy(digest.digest(), 0, bytes, bytes.length - 32, 32);
    }
    private PagedReplacementIndex.Snapshot completeSnapshotFromDisk() {
        // An arbitrary well-formed value cannot revive a poisoned instance.
        return new PagedReplacementIndex.Snapshot("00000000-0000-0000-0000-000000000000", "0".repeat(64), 1, "0".repeat(64));
    }
}
