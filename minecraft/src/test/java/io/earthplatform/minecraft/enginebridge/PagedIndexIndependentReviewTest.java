package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Independent persisted-byte, page-boundary, cursor and durability adversaries for012. */
class PagedIndexIndependentReviewTest {
    private static final String POLICY = "a".repeat(64), SUMMARY = "b".repeat(64);
    private static final CubePos A = new CubePos(0, 8, 0), B = new CubePos(1, 8, 0);
    @TempDir Path temporary;

    @Test void completeResultIncludesCrossPageMatchesAndEveryTrailingInvisiblePage() throws Exception {
        var index = seeded(97, i -> entry(new CubePos(i, 8, 0), 0));
        var token = index.beginQuery(new PagedReplacementIndex.Bounds(31 * 16, 128, 0, 33 * 16, 144, 16), 2);
        var actual = new ArrayList<PagedReplacementIndex.Entry>();
        PagedReplacementIndex.Completion completion = null;
        for (int page = 0; page < 4; page++) {
            var result = index.next(token); actual.addAll(result.entries());
            assertEquals(page == 3 ? PagedReplacementIndex.Status.COMPLETE : PagedReplacementIndex.Status.INCOMPLETE, result.status());
            assertEquals(page == 0 || page == 1 ? 1 : 0, result.entries().size());
            if (page < 3) assertNull(result.completion()); else completion = result.completion();
        }
        assertEquals(List.of(new CubePos(31, 8, 0), new CubePos(32, 8, 0)), actual.stream().map(PagedReplacementIndex.Entry::position).toList());
        assertNotNull(completion); assertEquals(2, completion.resultCount());
        assertEquals(independentResultDigest(actual), completion.resultIdentity());
        assertEquals(PagedReplacementIndex.resultIdentity(actual), completion.resultIdentity());
        assertTrue(index.isCurrent(completion));
        assertEquals(PagedReplacementIndex.Status.REFUSED, index.next(token).status());
    }

    @Test void thirdVisibleRecordOnLastPageRefusesDespiteTwoEarlierGoodPages() throws Exception {
        var index = seeded(65, i -> entry(new CubePos(i, i % 32 == 0 ? 8 : 100, 0), 0));
        var token = index.beginQuery(new PagedReplacementIndex.Bounds(0, 128, 0, 65 * 16, 144, 16), 2);
        for (int page = 0; page < 2; page++) {
            var partial = index.next(token); assertEquals(PagedReplacementIndex.Status.INCOMPLETE, partial.status());
            assertEquals(1, partial.entries().size()); assertNull(partial.completion());
        }
        var refused = index.next(token);
        assertEquals(PagedReplacementIndex.Status.REFUSED, refused.status());
        assertTrue(refused.entries().isEmpty()); assertNull(refused.completion());
        assertTrue(index.isCurrent(index.snapshot()), "Representability refusal alone must not mutate durable authority");
    }

    @Test void missingTruncatedOversizedOrCorruptInvisibleTailCannotCertifyEarlierMatches() throws Exception {
        for (int attack = 0; attack < 4; attack++) {
            Path directory = Files.createDirectory(temporary.resolve("tail-" + attack));
            var index = seeded(directory, 65, i -> entry(new CubePos(i, 8, 0), 0));
            Path tail = page(directory, 2); byte[] saved = Files.readAllBytes(tail);
            if (attack == 0) Files.delete(tail);
            if (attack == 1) Files.write(tail, Arrays.copyOf(saved, saved.length - 1));
            if (attack == 2) Files.write(tail, new byte[PagedReplacementIndex.MAX_PAGE_BYTES + 1]);
            if (attack == 3) { saved[saved.length - 1] ^= 1; Files.write(tail, saved); }
            var reopened = PagedReplacementIndex.open(directory, POLICY);
            var token = reopened.beginQuery(new PagedReplacementIndex.Bounds(0, 128, 0, 32, 144, 16), 2);
            for (int p = 0; p < 2; p++) {
                var partial = reopened.next(token); assertEquals(PagedReplacementIndex.Status.INCOMPLETE, partial.status()); assertNull(partial.completion());
            }
            var refused = reopened.next(token); assertEquals(PagedReplacementIndex.Status.REFUSED, refused.status());
            assertTrue(refused.entries().isEmpty()); assertNull(refused.completion());
            assertFalse(reopened.isCurrent(index.snapshot()));
        }
    }

    @Test void foreignSourcePolicyOnAnInvisiblePageFailsEvenAfterManifestChecksumsAreRecomputed() throws Exception {
        var index = seeded(65, i -> entry(new CubePos(i, 8, 0), 0));
        Path tail = page(temporary, 2); byte[] encoded = Files.readAllBytes(tail);
        encoded[PagedReplacementIndex.PAGE_HEADER_BYTES + 20] ^= 1;
        rewritePageAndManifest(temporary, 2, encoded);
        var reopened = PagedReplacementIndex.open(temporary, POLICY);
        assertEquals(PagedReplacementIndex.Status.REFUSED, exhaust(reopened,
                new PagedReplacementIndex.Bounds(0, 128, 0, 32, 144, 16), 2).status());
        assertFalse(index.isCurrent(index.snapshot()), "A changed root cannot be accepted through old in-memory authority");
    }

    @Test void duplicateAndUnsortedKeysAcrossPagesFailAfterValidHashRecomputation() throws Exception {
        for (int key : List.of(30, 31)) {
            Path directory = Files.createDirectory(temporary.resolve("order-" + key));
            seeded(directory, 33, i -> entry(new CubePos(i, 8, 0), 0));
            byte[] encoded = Files.readAllBytes(page(directory, 1));
            ByteBuffer.wrap(encoded).putInt(PagedReplacementIndex.PAGE_HEADER_BYTES, key);
            rewritePageAndManifest(directory, 1, encoded);
            var reopened = PagedReplacementIndex.open(directory, POLICY);
            assertEquals(PagedReplacementIndex.Status.REFUSED, exhaust(reopened, PagedReplacementIndex.Bounds.all(), 33).status());
        }
    }

    @Test void malformedPageCountIsRejectedBeforeCountSizedAllocation() throws Exception {
        seeded(2, i -> entry(new CubePos(i, 8, 0), 0));
        byte[] encoded = Files.readAllBytes(page(temporary, 0));
        ByteBuffer.wrap(encoded).putInt(PagedReplacementIndex.PAGE_HEADER_BYTES - 4, Integer.MAX_VALUE);
        rewritePageAndManifest(temporary, 0, encoded);
        var reopened = PagedReplacementIndex.open(temporary, POLICY);
        assertEquals(PagedReplacementIndex.Status.REFUSED, exhaust(reopened, PagedReplacementIndex.Bounds.all(), 2).status());
    }

    @Test void oldGenerationForeignStoreAndChangedNoncePagesFailEvenWithARecomputedManifestHash() throws Exception {
        for (int attack = 0; attack < 3; attack++) {
            Path directory = Files.createDirectory(temporary.resolve("stale-page-" + attack));
            var index = initialized(directory); byte[] old = Files.readAllBytes(page(directory, 0));
            index.begin(index.snapshot(), List.of(entry(A, 1), entry(B, 0))); index.confirm();
            byte[] corrupt = Files.readAllBytes(page(directory, 0));
            if (attack == 0) corrupt = old;
            if (attack == 1) corrupt[4] ^= 1; // Store UUID inside the page header.
            if (attack == 2) corrupt[60] ^= 1; // Immutable generation nonce inside the page header.
            rewritePageAndManifest(directory, 0, corrupt);
            var reopened = PagedReplacementIndex.open(directory, POLICY);
            assertEquals(PagedReplacementIndex.Status.REFUSED, exhaust(reopened, PagedReplacementIndex.Bounds.all(), 2).status());
        }
    }

    @Test void malformedMetadataCountsPolicyOrGenerationFailBeforeCountSizedAllocation() throws Exception {
        seeded(2, i -> entry(new CubePos(i, 8, 0), 0));
        Path root = temporary.resolve(PagedReplacementIndex.ROOT_FILE); byte[] original = Files.readAllBytes(root);
        for (int attack = 0; attack < 6; attack++) {
            byte[] bytes = original.clone(); ByteBuffer value = ByteBuffer.wrap(bytes);
            if (attack == 0) value.putInt(76, Integer.MAX_VALUE);
            if (attack == 1) value.putInt(80, Integer.MAX_VALUE);
            if (attack == 2) value.putInt(80, -1);
            if (attack == 3) value.putInt(76, 0);
            if (attack == 4) value.putLong(52, 0);
            if (attack == 5) bytes[20] ^= 1;
            checksum(bytes); Files.write(root, bytes);
            assertThrows(IOException.class, () -> PagedReplacementIndex.open(temporary, POLICY), "attack " + attack);
        }
        Files.write(root, new byte[PagedReplacementIndex.MAX_METADATA_BYTES + 1]);
        assertThrows(IOException.class, () -> PagedReplacementIndex.open(temporary, POLICY));
        Files.write(root, original);
        assertEquals(PagedReplacementIndex.Status.COMPLETE, exhaust(PagedReplacementIndex.open(temporary, POLICY), PagedReplacementIndex.Bounds.all(), 2).status());
    }

    @Test void queryTokensCannotCrossInstancesReplaceAnActiveCursorOrReviveAfterCompletion() throws Exception {
        var index = initialized(temporary);
        var first = index.beginQuery(PagedReplacementIndex.Bounds.all(), 2);
        var second = index.beginQuery(PagedReplacementIndex.Bounds.all(), 2);
        assertEquals(PagedReplacementIndex.Status.REFUSED, index.next(first).status());
        var other = initialized(Files.createDirectory(temporary.resolve("other")));
        assertEquals(PagedReplacementIndex.Status.REFUSED, other.next(second).status());
        var complete = index.next(second); assertEquals(PagedReplacementIndex.Status.COMPLETE, complete.status());
        assertTrue(index.isCurrent(complete.completion())); assertFalse(other.isCurrent(complete.completion()));
        assertEquals(PagedReplacementIndex.Status.REFUSED, index.next(second).status());
        assertThrows(IllegalArgumentException.class, () -> index.beginQuery(PagedReplacementIndex.Bounds.all(), -1));
        assertThrows(IllegalArgumentException.class, () -> index.beginQuery(PagedReplacementIndex.Bounds.all(), Integer.MAX_VALUE));
    }

    @Test void completeButOverbudgetCatalogueCannotCreateIntentOrChangeAnyFile() throws Exception {
        var index = seeded(3, i -> entry(new CubePos(i, 8, 0), 0));
        var snapshot = index.snapshot(); Map<String, String> before = fileHashes(temporary);
        assertThrows(IOException.class, () -> index.begin(snapshot, List.of(entry(A, 1), entry(B, 0))));
        assertEquals(before, fileHashes(temporary)); assertTrue(index.isCurrent(snapshot));
        assertFalse(Files.exists(temporary.resolve(PagedReplacementIndex.INTENT_FILE)));
    }

    @Test void allInvalidProspectiveRevisionPolicyAndKeyTransitionsRefuseBeforeDurableIntent() throws Exception {
        var index = initialized(temporary); var snapshot = index.snapshot(); Map<String, String> before = fileHashes(temporary);
        for (var replacements : List.of(
                List.of(entry(A, 0), entry(B, 0)), List.of(entry(A, 1), entry(B, 1)),
                List.of(entry(A, 2), entry(B, 0)), List.of(entry(A, 1)),
                List.of(entry(A, 1), entry(B.offset(1, 0, 0), 0)), List.of(entry(B, 0), entry(A, 1)),
                List.of(entry(A, 1), new PagedReplacementIndex.Entry(B, 0, "f".repeat(64), SUMMARY)))) {
            assertThrows(IOException.class, () -> index.begin(snapshot, replacements));
            assertEquals(before, fileHashes(temporary)); assertTrue(index.isCurrent(snapshot));
        }
    }

    @Test void neighborSummaryRefreshChangesRootNotItsOwnRevisionAndColdHashesRemainExact() throws Exception {
        var index = initialized(temporary); var before = index.snapshot();
        var oldQuery = exhaust(index, PagedReplacementIndex.Bounds.all(), 2).completion();
        var a = new PagedReplacementIndex.Entry(A, 1, POLICY, "c".repeat(64));
        var b = new PagedReplacementIndex.Entry(B, 0, POLICY, "d".repeat(64));
        index.begin(before, List.of(a, b));
        assertFalse(index.isCurrent(before)); assertFalse(index.isCurrent(oldQuery));
        assertThrows(IllegalStateException.class, index::snapshot);
        assertThrows(IOException.class, () -> PagedReplacementIndex.open(temporary, POLICY));
        index.confirm(); var after = index.snapshot();
        assertEquals(before.storeIdentity(), after.storeIdentity()); assertEquals(before.indexIdentity(), after.indexIdentity());
        assertEquals(before.generation() + 1, after.generation()); assertNotEquals(before.rootIdentity(), after.rootIdentity());
        assertFalse(index.isCurrent(before)); assertFalse(index.isCurrent(oldQuery));
        var result = exhaust(index, PagedReplacementIndex.Bounds.all(), 2);
        assertEquals(List.of(a, b), result.entries()); assertEquals(0, result.entries().getLast().revision());
        var reopened = PagedReplacementIndex.open(temporary, POLICY);
        assertEquals(after, reopened.snapshot());
        assertEquals(result.completion().resultIdentity(), exhaust(reopened, PagedReplacementIndex.Bounds.all(), 2).completion().resultIdentity());
        Map<String, String> committed = fileHashes(temporary);
        assertThrows(IOException.class, () -> index.begin(before, List.of(entry(A, 2), b)));
        assertEquals(committed, fileHashes(temporary));
    }

    @Test void sameRevisionSummaryCannotBeResealedAsFreshInitialization() throws Exception {
        var index = initialized(temporary); var before = fileHashes(temporary);
        assertThrows(IOException.class, () -> index.initializeSummaries(index.snapshot(),
                List.of(new PagedReplacementIndex.Entry(A, 0, POLICY, "e".repeat(64)), entry(B, 0))));
        assertEquals(before, fileHashes(temporary));
    }

    @Test void everyDurabilityCutRevokesLiveAuthorityAndColdOpenAcceptsOnlyCertainWholeStates() throws Exception {
        for (var cut : PagedReplacementIndex.DurablePoint.values()) {
            Path directory = Files.createDirectory(temporary.resolve(cut.name()));
            var index = initialized(directory); var old = index.snapshot();
            var completion = exhaust(index, PagedReplacementIndex.Bounds.all(), 2).completion();
            index.failureInjectorForTests((point, ordinal) -> { if (point == cut) throw new IOException("Independent crash cut " + point); });
            if (cut.name().startsWith("INTENT_") && !cut.name().startsWith("INTENT_DELETE"))
                assertThrows(IOException.class, () -> index.begin(old, List.of(entry(A, 1), entry(B, 0))), cut.name());
            else {
                index.begin(old, List.of(entry(A, 1), entry(B, 0)));
                assertThrows(IOException.class, index::confirm, cut.name());
            }
            assertFalse(index.isCurrent(old)); assertFalse(index.isCurrent(completion));
            if (cut == PagedReplacementIndex.DurablePoint.INTENT_FILE_FORCED) {
                var reopened = PagedReplacementIndex.open(directory, POLICY);
                assertEquals(old, reopened.snapshot());
                assertEquals(0, exhaust(reopened, PagedReplacementIndex.Bounds.all(), 2).entries().getFirst().revision());
            } else if (cut == PagedReplacementIndex.DurablePoint.PREVIOUS_PAGE_DELETED
                    || cut == PagedReplacementIndex.DurablePoint.PREVIOUS_PAGES_DIRECTORY_FORCED) {
                // This fixture has one prior page, already removed at both cleanup cuts.
                var reopened = PagedReplacementIndex.open(directory, POLICY);
                var complete = exhaust(reopened, PagedReplacementIndex.Bounds.all(), 2);
                assertEquals(PagedReplacementIndex.Status.COMPLETE, complete.status());
                assertEquals(1, complete.entries().getFirst().revision()); assertEquals(0, complete.entries().getLast().revision());
            } else {
                // Even after intent removal, old generation pages still coexist at these cut
                // points. Opening deliberately refuses those uncertain cleanup leftovers.
                assertThrows(IOException.class, () -> PagedReplacementIndex.open(directory, POLICY), cut.name());
            }
        }
    }

    @Test void all8192CatalogueRecordsCanBeVerifiedWithoutAnAllIndexResultOrCursorGrowth() throws Exception {
        AtomicInteger consumed = new AtomicInteger();
        var index = PagedReplacementIndex.open(temporary, POLICY);
        index.seedCatalogueForTests(iterator(PagedReplacementIndex.MAX_ENTRIES, i -> {
            consumed.incrementAndGet(); return entry(new CubePos(i, 8, 0), 0);
        }));
        assertEquals(8192, consumed.get());
        assertEquals(1, PagedReplacementIndex.MAX_ACTIVE_CURSORS); assertTrue(PagedReplacementIndex.MAX_CURSOR_BYTES <= 512);
        assertEquals(84, PagedReplacementIndex.ENTRY_BYTES); assertEquals(84, PagedReplacementIndex.PAGE_HEADER_BYTES);
        assertEquals(2772, PagedReplacementIndex.MAX_PAGE_BYTES); assertEquals(9332, PagedReplacementIndex.MAX_METADATA_BYTES);
        assertEquals(PagedReplacementIndex.MAX_METADATA_BYTES, Files.size(temporary.resolve(PagedReplacementIndex.ROOT_FILE)));
        var token = index.beginQuery(new PagedReplacementIndex.Bounds(-16, 0, 0, 0, 16, 16), 0);
        for (int page = 0; page < 256; page++) {
            assertEquals(PagedReplacementIndex.MAX_PAGE_BYTES, Files.size(page(temporary, page)));
            var result = index.next(token); assertTrue(result.entries().isEmpty());
            assertEquals(page == 255 ? PagedReplacementIndex.Status.COMPLETE : PagedReplacementIndex.Status.INCOMPLETE, result.status());
            if (page == 255) { assertEquals(0, result.completion().resultCount()); assertEquals(independentResultDigest(List.of()), result.completion().resultIdentity()); }
            else assertNull(result.completion());
        }
    }

    @Test void record8193IsRejectedBeforeItIsConsumedAndNoTruncatedRootCanBePublished() throws Exception {
        var index = PagedReplacementIndex.open(temporary, POLICY); var empty = index.snapshot();
        AtomicInteger consumed = new AtomicInteger();
        assertThrows(IOException.class, () -> index.seedCatalogueForTests(iterator(8193, i -> {
            consumed.incrementAndGet(); return entry(new CubePos(i, 8, 0), 0);
        })));
        assertEquals(8192, consumed.get()); assertFalse(index.isCurrent(empty));
        assertThrows(IOException.class, () -> PagedReplacementIndex.open(temporary, POLICY));
    }

    @Test void boundsAreExactHalfOpenThreeDimensionalIntersectionsAtSignedAndExtremeCoordinates() {
        var box = new PagedReplacementIndex.Bounds(-16, -16, -16, 0, 0, 0);
        for (int x = -2; x <= 1; x++) for (int y = -2; y <= 1; y++) for (int z = -2; z <= 1; z++)
            assertEquals(x == -1 && y == -1 && z == -1, box.intersects(new CubePos(x, y, z)));
        var crossing = new PagedReplacementIndex.Bounds(-1, -1, -1, 1, 1, 1);
        for (int x = -2; x <= 1; x++) for (int y = -2; y <= 1; y++) for (int z = -2; z <= 1; z++)
            assertEquals((x == -1 || x == 0) && (y == -1 || y == 0) && (z == -1 || z == 0), crossing.intersects(new CubePos(x, y, z)));
        assertTrue(PagedReplacementIndex.Bounds.all().intersects(new CubePos(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE)));
        assertTrue(PagedReplacementIndex.Bounds.all().intersects(new CubePos(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> new PagedReplacementIndex.Bounds(Long.MIN_VALUE, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PagedReplacementIndex.Bounds(0, 0, 0, Long.MAX_VALUE, 1, 1));
    }

    private static PagedReplacementIndex.Entry entry(CubePos position, long revision) { return new PagedReplacementIndex.Entry(position, revision, POLICY, SUMMARY); }
    private PagedReplacementIndex seeded(int count, java.util.function.IntFunction<PagedReplacementIndex.Entry> factory) throws IOException {
        return seeded(temporary, count, factory);
    }
    private static PagedReplacementIndex seeded(Path directory, int count, java.util.function.IntFunction<PagedReplacementIndex.Entry> factory) throws IOException {
        var index = PagedReplacementIndex.open(directory, POLICY); index.seedCatalogueForTests(iterator(count, factory)); return index;
    }
    private static Iterator<PagedReplacementIndex.Entry> iterator(int count, java.util.function.IntFunction<PagedReplacementIndex.Entry> factory) {
        return new Iterator<>() { int next; public boolean hasNext() { return next < count; }
            public PagedReplacementIndex.Entry next() { if (!hasNext()) throw new java.util.NoSuchElementException(); return factory.apply(next++); } };
    }
    private static PagedReplacementIndex initialized(Path directory) throws IOException {
        var index = PagedReplacementIndex.open(directory, POLICY);
        index.initializeSummaries(index.snapshot(), List.of(entry(A, 0), entry(B, 0))); return index;
    }
    private static PagedReplacementIndex.QueryPage exhaust(PagedReplacementIndex index, PagedReplacementIndex.Bounds bounds, int cap) {
        var token = index.beginQuery(bounds, cap);
        for (int page = 0; page <= PagedReplacementIndex.MAX_PAGES; page++) {
            var result = index.next(token); if (result.status() != PagedReplacementIndex.Status.INCOMPLETE) return result;
            assertNull(result.completion());
        }
        throw new AssertionError("Unbounded cursor continuation");
    }
    private static Path page(Path directory, int ordinal) throws IOException {
        try (var files = Files.list(directory)) {
            var matches = files.filter(p -> p.getFileName().toString().startsWith("replacement-paged-v1-page-")
                    && p.getFileName().toString().endsWith("-" + ordinal + ".bin")).toList();
            assertEquals(1, matches.size(), "Expected one persisted current page for ordinal " + ordinal); return matches.getFirst();
        }
    }
    private static void rewritePageAndManifest(Path directory, int ordinal, byte[] encoded) throws Exception {
        Files.write(page(directory, ordinal), encoded);
        Path root = directory.resolve(PagedReplacementIndex.ROOT_FILE); byte[] rootBytes = Files.readAllBytes(root);
        System.arraycopy(MessageDigest.getInstance("SHA-256").digest(encoded), 0, rootBytes, 84 + ordinal * 36 + 4, 32);
        checksum(rootBytes); Files.write(root, rootBytes);
    }
    private static void checksum(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(digest, 0, bytes, bytes.length - 32, 32);
    }
    private static Map<String, String> fileHashes(Path directory) throws Exception {
        var result = new TreeMap<String, String>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                result.put(file.getFileName().toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        return result;
    }
    private static String independentResultDigest(List<PagedReplacementIndex.Entry> entries) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update("earth-paged-replacement-results-v1\0".getBytes(StandardCharsets.UTF_8));
        for (var entry : entries) {
            var position = entry.position();
            digest.update(ByteBuffer.allocate(84).putInt(position.x()).putInt(position.y()).putInt(position.z()).putLong(entry.revision())
                    .put(HexFormat.of().parseHex(entry.sourcePolicyIdentity())).put(HexFormat.of().parseHex(entry.summaryIdentity())).array());
        }
        digest.update(ByteBuffer.allocate(4).putInt(entries.size()).array()); return HexFormat.of().formatHex(digest.digest());
    }
}
