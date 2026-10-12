package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, fail-closed durable summary catalogue. The caller must hold the edit store's exclusive lock.
 * Catalogue capacity is NOT live authority: runtime initialization/admission remains exactly eight keys.
 * Queries scan every page, including invisible records, before issuing any completeness attestation.
 * Neither an Entry nor a Completion is actor publication authority.
 */
public final class ArenaReplacementIndex {
    public static final int ENTRIES_PER_PAGE = 32;
    public static final int MAX_PAGES = 256;
    public static final int MAX_ENTRIES = ENTRIES_PER_PAGE * MAX_PAGES;
    public static final int ENTRY_BYTES = 12 + 8 + 32 + 32;
    public static final int PAGE_HEADER_BYTES = 4 + 16 + 32 + 8 + 16 + 4 + 4;
    public static final int MAX_PAGE_BYTES = PAGE_HEADER_BYTES + ENTRIES_PER_PAGE * ENTRY_BYTES;
    public static final int ROOT_FIXED_BYTES = 4 + 16 + 32 + 8 + 16 + 4 + 4 + 32;
    public static final int MAX_METADATA_BYTES = ROOT_FIXED_BYTES + MAX_PAGES * (4 + 32);
    public static final int MAX_INTENT_BYTES = PagedReplacementIndex.MAX_INTENT_BYTES;
    /** Logical retained cursor payload; excludes shared bounded metadata and JVM object overhead. */
    public static final int MAX_CURSOR_BYTES = 512;
    public static final int MAX_ACTIVE_CURSORS = 1;
    public static final int MAX_LIVE_MATCHES = 8;
    public static final String ROOT_FILE = "arena-paged-v1.root";
    public static final String INTENT_FILE = "arena-paged-v1.intent";
    private static final String PAGE_PREFIX = "arena-paged-v1-page-";
    private static final String TEMP_PREFIX = ".arena-paged-v1-";
    private static final int ROOT_MAGIC = 0x45414931, PAGE_MAGIC = 0x45415031, INTENT_MAGIC = 0x45414a31;
    private static final long MIN_BLOCK = (long) Integer.MIN_VALUE * CubePos.SIZE;
    private static final long MAX_BLOCK_EXCLUSIVE = ((long) Integer.MAX_VALUE + 1) * CubePos.SIZE;
    private static final byte[] RESULT_DOMAIN = "earth-arena-paged-replacement-results-v1\0".getBytes(StandardCharsets.UTF_8);

    public enum Status { COMPLETE, INCOMPLETE, REFUSED }

    /** Half-open exact block-space query bounds; intersection includes all three independent axes. */
    public record Bounds(long minX, long minY, long minZ, long maxXExclusive, long maxYExclusive, long maxZExclusive) {
        public Bounds {
            if (minX < MIN_BLOCK || minY < MIN_BLOCK || minZ < MIN_BLOCK
                    || maxXExclusive > MAX_BLOCK_EXCLUSIVE || maxYExclusive > MAX_BLOCK_EXCLUSIVE || maxZExclusive > MAX_BLOCK_EXCLUSIVE
                    || minX >= maxXExclusive || minY >= maxYExclusive || minZ >= maxZExclusive)
                throw new IllegalArgumentException("Exact nonempty CubePos-domain query bounds required");
        }
        public static Bounds all() {
            return new Bounds(MIN_BLOCK, MIN_BLOCK, MIN_BLOCK, MAX_BLOCK_EXCLUSIVE, MAX_BLOCK_EXCLUSIVE, MAX_BLOCK_EXCLUSIVE);
        }
        public boolean intersects(CubePos p) {
            Objects.requireNonNull(p);
            return p.minBlockX() < maxXExclusive && p.minBlockX() + CubePos.SIZE > minX
                    && p.minBlockY() < maxYExclusive && p.minBlockY() + CubePos.SIZE > minY
                    && p.minBlockZ() < maxZExclusive && p.minBlockZ() + CubePos.SIZE > minZ;
        }
    }

    /** Summary identity is an actual immutable capture digest, never a hash guessed from revision. */
    public record Entry(CubePos position, long revision, String sourcePolicyIdentity, String summaryIdentity) {
        public Entry {
            Objects.requireNonNull(position, "position");
            if (revision < 0) throw new IllegalArgumentException("Negative summary revision");
            requireDigest(sourcePolicyIdentity); requireDigest(summaryIdentity);
        }
    }

    public record Snapshot(String storeIdentity, String indexIdentity, long generation, String rootIdentity) {
        public Snapshot {
            if (!UUID.fromString(storeIdentity).toString().equals(storeIdentity) || generation <= 0)
                throw new IllegalArgumentException("Canonical index snapshot required");
            requireDigest(indexIdentity); requireDigest(rootIdentity);
        }
    }

    /** Opaque, single-use stream owned by this index instance. Replaced cursors cannot be resumed. */
    public static final class QueryToken {
        private final ArenaReplacementIndex owner;
        private final Snapshot snapshot;
        private final Bounds bounds;
        private final int maxMatches;
        private final MessageDigest digest = resultDigest();
        private int page, scanned, matched;
        private CubePos last;
        private boolean terminal;
        private QueryToken(ArenaReplacementIndex owner, Snapshot snapshot, Bounds bounds, int maxMatches) {
            this.owner = owner; this.snapshot = snapshot; this.bounds = bounds; this.maxMatches = maxMatches;
        }
    }

    /** Immutable COMPLETE-only evidence. Construction is private; copies/wire values confer no authority. */
    public static final class Completion {
        private final ArenaReplacementIndex owner;
        private final Snapshot snapshot;
        private final Bounds bounds;
        private final String scopeIdentity, resultIdentity;
        private final int resultCount;
        private Completion(ArenaReplacementIndex owner, QueryToken token) {
            this.owner = owner; snapshot = token.snapshot; bounds = token.bounds;
            scopeIdentity = owner.scopeIdentity; resultCount = token.matched;
            token.digest.update(ByteBuffer.allocate(4).putInt(resultCount).array());
            resultIdentity = hex(token.digest.digest());
        }
        public Snapshot snapshot() { return snapshot; }
        public Bounds bounds() { return bounds; }
        public String scopeIdentity() { return scopeIdentity; }
        public int resultCount() { return resultCount; }
        public String resultIdentity() { return resultIdentity; }
    }

    /** Entries are page-local. A caller must not publish any page without a final current Completion. */
    public record QueryPage(Status status, List<Entry> entries, QueryToken token, Completion completion, String reason) {
        public QueryPage {
            Objects.requireNonNull(status); Objects.requireNonNull(entries); Objects.requireNonNull(reason);
            int count = entries.size();
            if (count < 0 || count > ENTRIES_PER_PAGE) throw new IllegalArgumentException("Oversized result page");
            List<Entry> bounded = new ArrayList<>(count);
            for (int i = 0; i < count; i++) bounded.add(Objects.requireNonNull(entries.get(i)));
            entries = List.copyOf(bounded);
            if ((status == Status.COMPLETE) != (completion != null)) throw new IllegalArgumentException("Invalid query completion");
            if (status == Status.REFUSED && !entries.isEmpty()) throw new IllegalArgumentException("Refusal cannot expose entries");
        }
    }

    /** Explicit boundaries used by independent crash-cut tests, after the named operation. */
    enum DurablePoint { INTENT_FILE_FORCED, INTENT_RENAMED, INTENT_DIRECTORY_FORCED,
        PAGE_FILE_FORCED, PAGE_RENAMED, PAGE_DIRECTORY_FORCED,
        ROOT_FILE_FORCED, ROOT_RENAMED, ROOT_DIRECTORY_FORCED,
        INTENT_DELETED, INTENT_DELETE_DIRECTORY_FORCED, PREVIOUS_PAGE_DELETED, PREVIOUS_PAGES_DIRECTORY_FORCED }
    @FunctionalInterface interface FailureInjector { void after(DurablePoint point, int ordinal) throws IOException; }
    private static final FailureInjector NO_FAILURE = (point, ordinal) -> {};
    private final Path directory;
    private final String scopeIdentity;
    private Root root;
    private QueryToken activeQuery;
    private List<Entry> pending;
    private String pendingIntentIdentity;
    private boolean poisoned;
    private FailureInjector failureInjector = NO_FAILURE;

    private record Descriptor(int count, String digest) {}
    private record Root(UUID store, long generation, UUID nonce, int totalEntries, List<Descriptor> pages,
                        Snapshot snapshot) {}
    private ArenaReplacementIndex(Path directory, String scopeIdentity) {
        this.directory = directory; this.scopeIdentity = scopeIdentity;
    }

    public static ArenaReplacementIndex open(Path directory, String scopeIdentity) throws IOException {
        Objects.requireNonNull(directory); requireDigest(scopeIdentity);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Existing real index directory required");
        for (String legacy : List.of("replacement-region-v1.index", "replacement-regions-v2.index", "replacement-adjacent-regions-v1.index", "replacement-paged-v1.root", "replacement-paged-v1.intent"))
            if (Files.exists(directory.resolve(legacy), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Legacy replacement store requires a separate indexed store; no implicit migration");
        if (Files.exists(directory.resolve(INTENT_FILE), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unresolved durable paged index intent; explicit recovery required");
        boolean rootExists = Files.exists(directory.resolve(ROOT_FILE), LinkOption.NOFOLLOW_LINKS);
        int pageFiles = 0;
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.startsWith(TEMP_PREFIX)) throw new IOException("Uncertain staged index write; explicit recovery required");
                if (name.startsWith(PAGE_PREFIX) && (!rootExists || ++pageFiles > 2 * MAX_PAGES))
                    throw new IOException("Rootless or excessive paged index data");
            }
        }
        ArenaReplacementIndex index = new ArenaReplacementIndex(directory, scopeIdentity);
        if (!rootExists) {
            rejectExistingCubes(directory);
            index.root = index.makeRoot(UUID.randomUUID(), 1, UUID.randomUUID(), 0, List.of());
            index.atomicWrite(directory.resolve(ROOT_FILE), index.encodeRoot(index.root), "ROOT", -1);
        } else {
            index.root = index.readRoot();
            if (pageFiles > index.root.pages.size())
                throw new IOException("Unreferenced index generation pages; explicit recovery required");
            if (index.root.totalEntries == 0) rejectExistingCubes(directory);
            // Re-establish the committed root barrier before any query authority is exposed.
            try (FileChannel file = FileChannel.open(directory.resolve(ROOT_FILE), StandardOpenOption.WRITE)) { file.force(true); }
            forceDirectory(directory);
        }
        return index;
    }

    public synchronized Snapshot snapshot() { check(); return root.snapshot; }
    public String indexIdentity() { return root.snapshot.indexIdentity(); }
    public synchronized boolean isInitialized() { check(); return root.totalEntries != 0; }
    public synchronized boolean isCurrent(Snapshot snapshot) {
        if (poisoned || pending != null || snapshot == null || !root.snapshot.equals(snapshot)) return false;
        try { verifyRootCurrent(); return true; } catch (IOException invalid) { poisoned = true; return false; }
    }
    /**
     * Constant-space, no-IO actor-owner check of an already fully verified completion. The exclusive
     * store lock and immutable COW pages are required; disk workers use strict isCurrent/next checks.
     */
    public synchronized boolean issuedCompletionCurrent(Completion completion) {
        return !poisoned && pending == null && completion != null && completion.owner == this
                && root.snapshot.equals(completion.snapshot);
    }
    public synchronized boolean isCurrent(Completion completion) {
        return completion != null && completion.owner == this && isCurrent(completion.snapshot);
    }

    public synchronized QueryToken beginQuery(Bounds bounds, int maxMatches) {
        Objects.requireNonNull(bounds);
        if (maxMatches < 0 || maxMatches > MAX_ENTRIES) throw new IllegalArgumentException("Bounded result count required");
        if (activeQuery != null) activeQuery.terminal = true;
        activeQuery = new QueryToken(this, root.snapshot, bounds, maxMatches);
        return activeQuery;
    }

    public synchronized QueryPage next(QueryToken token) {
        if (token == null || token.owner != this || token != activeQuery || token.terminal)
            return refused(token, "Foreign, replaced, or exhausted query token");
        if (poisoned || pending != null || !token.snapshot.equals(root.snapshot)) return refused(token, "Index is stale or unavailable");
        try {
            verifyRootCurrent();
            List<Entry> matches = new ArrayList<>(ENTRIES_PER_PAGE);
            if (token.page < root.pages.size()) {
                for (Entry entry : readPage(root, token.page++)) {
                    if (token.last != null && token.last.compareTo(entry.position()) >= 0)
                        throw new IOException("Noncanonical or duplicated cross-page catalogue key");
                    token.last = entry.position(); token.scanned = Math.incrementExact(token.scanned);
                    if (token.bounds.intersects(entry.position())) {
                        if (token.matched == token.maxMatches) return refused(token, "Complete result would exceed the admitted match bound");
                        token.matched++; token.digest.update(encodeEntry(entry)); matches.add(entry);
                    }
                }
            }
            // Recheck after page IO; no stale/ABA completion after a root replacement.
            verifyRootCurrent();
            if (token.page == root.pages.size()) {
                if (token.scanned != root.totalEntries) throw new IOException("Incomplete catalogue entry count");
                token.terminal = true;
                return new QueryPage(Status.COMPLETE, matches, token, new Completion(this, token), "");
            }
            return new QueryPage(Status.INCOMPLETE, matches, token, null, "More pages must be verified");
        } catch (IOException | RuntimeException invalid) {
            poisoned = true;
            return refused(token, "Invalid or unavailable catalogue page: " + invalid.getMessage());
        }
    }

    /** Actual serially reconstructed capture identities only. Existing summaries cannot be resealed. */
    public synchronized void initializeSummaries(Snapshot expected, List<Entry> entries) throws IOException {
        requireCurrent(expected);
        if (root.totalEntries != 0) throw new IOException("Summary initialization is fresh-store only");
        rejectExistingCubes(directory);
        List<Entry> bounded = canonicalBounded(entries, MAX_LIVE_MATCHES);
        if (bounded.size() != MAX_LIVE_MATCHES || !bounded.stream().map(Entry::position).toList().equals(ArenaDefinition.FIXED.positions()) || bounded.stream().map(Entry::summaryIdentity).distinct().count() != 1 || bounded.stream().anyMatch(e -> e.revision() != 0))
            throw new IOException("Exactly eight actual clean summaries required for initialization");
        startIntent(bounded);
        commitCatalogue(bounded.iterator());
    }

    /**
     * Pure full-domain prospective admission completes BEFORE durable intent and hence before cube save.
     * Exactly eight existing keys remain live; one revision advances and the canonical union summary refresh.
     */
    public synchronized void begin(Snapshot expected, List<Entry> replacements) throws IOException {
        requireCurrent(expected);
        List<Entry> changes = canonicalBounded(replacements, MAX_LIVE_MATCHES);
        List<Entry> current = completeSmallCatalogue();
        if (current.size() != MAX_LIVE_MATCHES || changes.size() != MAX_LIVE_MATCHES)
            throw new IOException("Eight complete replacement summaries are required");
        if (!current.stream().map(Entry::position).toList().equals(ArenaDefinition.FIXED.positions()) || changes.stream().map(Entry::summaryIdentity).distinct().count() != 1)
            throw new IOException("Exactly eight fixed keys and one canonical union summary required");
        int advanced = 0;
        for (int i = 0; i < MAX_LIVE_MATCHES; i++) {
            Entry before = current.get(i), after = changes.get(i);
            if (!before.position().equals(after.position())) throw new IOException("Prospective ninth or foreign replacement key refused");
            if (after.revision() == before.revision()) continue;
            if (before.revision() == Long.MAX_VALUE || after.revision() != before.revision() + 1)
                throw new IOException("Replacement revision must advance exactly once");
            advanced++;
        }
        if (advanced != 1) throw new IOException("Exactly one cube revision must advance");
        // The prospective key set is identical to the completely verified eight-key catalogue.
        // Recheck the root immediately before journaling; no failed query can reach a cube save.
        requireCurrent(expected);
        startIntent(changes);
    }

    /** The matching cube save must already have crossed strict file and directory durability barriers. */
    public synchronized void confirm() throws IOException {
        if (poisoned || pending == null) throw new IOException("No certain pending index transaction");
        commitCatalogue(pending.iterator());
    }

    /** Canonical digest helper for validating decoded immutable attestations, not minting authority. */
    public static String resultIdentity(List<Entry> canonicalEntries) {
        Objects.requireNonNull(canonicalEntries);
        int count = canonicalEntries.size();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Oversized result identity input");
        MessageDigest digest = resultDigest(); CubePos last = null;
        for (int i = 0; i < count; i++) {
            Entry entry = Objects.requireNonNull(canonicalEntries.get(i));
            if (last != null && last.compareTo(entry.position()) >= 0) throw new IllegalArgumentException("Canonical result ordering required");
            last = entry.position(); digest.update(encodeEntry(entry));
        }
        digest.update(ByteBuffer.allocate(4).putInt(count).array());
        return hex(digest.digest());
    }

    /* Synthetic catalogue fixtures cannot expand actor/renderer authority; streaming avoids all-index lists. */
    synchronized void seedCatalogueForTests(Iterator<Entry> entries) throws IOException {
        requireCurrent(root.snapshot);
        if (root.totalEntries != 0) throw new IOException("Fixture seed requires an empty isolated index");
        startIntent(List.of()); commitCatalogue(entries);
    }
    synchronized void failureInjectorForTests(FailureInjector injector) { failureInjector = Objects.requireNonNull(injector); }

    private List<Entry> completeSmallCatalogue() throws IOException {
        QueryToken token = beginQuery(Bounds.all(), MAX_LIVE_MATCHES);
        List<Entry> result = new ArrayList<>(MAX_LIVE_MATCHES);
        while (true) {
            QueryPage page = next(token);
            if (page.status() == Status.REFUSED) throw new IOException("Prospective admission refused: " + page.reason());
            if (result.size() + page.entries().size() > MAX_LIVE_MATCHES) throw new IOException("Prospective match overflow");
            result.addAll(page.entries());
            if (page.status() == Status.COMPLETE) return List.copyOf(result);
        }
    }
    private List<Entry> canonicalBounded(List<Entry> entries, int cap) throws IOException {
        if (entries == null) throw new IOException("Missing replacement transaction");
        int count = entries.size();
        if (count < 0 || count > cap) throw new IOException("Oversized replacement transaction");
        List<Entry> bounded = new ArrayList<>(count); CubePos last = null;
        for (int i = 0; i < count; i++) {
            Entry entry = entries.get(i);
            if (entry == null || !scopeIdentity.equals(entry.sourcePolicyIdentity())) throw new IOException("Foreign summary policy");
            if (last != null && last.compareTo(entry.position()) >= 0) throw new IOException("Canonical distinct summary keys required");
            last = entry.position(); bounded.add(entry);
        }
        return List.copyOf(bounded);
    }
    private void startIntent(List<Entry> entries) throws IOException {
        try {
            verifyRootCurrent();
            if (root.generation == Long.MAX_VALUE) throw new IOException("Index generation exhausted before durable intent");
            if (Files.exists(directory.resolve(INTENT_FILE), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Existing unresolved intent");
            // The immutable definition/scope fixes all eight coordinates and one union summary.
            // Eight revisions plus one digest require232 bytes, below the unchanged304 cap.
            if (!entries.isEmpty() && entries.size() != ArenaDefinition.CUBE_COUNT)
                throw new IOException("A complete fixed arena intent is required");
            ByteBuffer b = ByteBuffer.allocate(4 + 16 + 32 + 32 + 16 + 4
                    + (entries.isEmpty() ? 0 : 32 + entries.size() * Long.BYTES) + 32);
            b.putInt(INTENT_MAGIC); putUuid(b, root.store); b.put(unhex(scopeIdentity)); b.put(unhex(root.snapshot.rootIdentity()));
            putUuid(b, UUID.randomUUID()); b.putInt(entries.size());
            if (!entries.isEmpty()) {
                b.put(unhex(entries.getFirst().summaryIdentity()));
                for (Entry entry : entries) b.putLong(entry.revision());
            }
            appendChecksum(b);
            pending = entries; pendingIntentIdentity = sha(b.array());
            if (activeQuery != null) activeQuery.terminal = true;
            atomicWrite(directory.resolve(INTENT_FILE), b.array(), "INTENT", -1);
        } catch (IOException | RuntimeException failure) { poisoned = true; throw asIo(failure); }
    }

    private void commitCatalogue(Iterator<Entry> entries) throws IOException {
        Root before = root;
        try {
            if (pending == null) throw new IOException("Catalogue commit without durable intent");
            verifyRootCurrent();
            long generation;
            try { generation = Math.incrementExact(before.generation); }
            catch (ArithmeticException exhausted) { throw new IOException("Index generation exhausted", exhausted); }
            UUID nonce = UUID.randomUUID(); List<Descriptor> descriptors = new ArrayList<>(MAX_PAGES);
            int total = 0; CubePos last = null;
            while (entries.hasNext()) {
                if (descriptors.size() == MAX_PAGES) throw new IOException("Catalogue page capacity exceeded");
                List<Entry> page = new ArrayList<>(ENTRIES_PER_PAGE);
                while (page.size() < ENTRIES_PER_PAGE && entries.hasNext()) {
                    Entry entry = Objects.requireNonNull(entries.next());
                    if (!scopeIdentity.equals(entry.sourcePolicyIdentity())) throw new IOException("Foreign catalogue source/material policy");
                    if (last != null && last.compareTo(entry.position()) >= 0) throw new IOException("Unsorted or duplicated catalogue key");
                    last = entry.position(); page.add(entry); total = Math.incrementExact(total);
                }
                byte[] bytes = encodePage(before.store, generation, nonce, descriptors.size(), page);
                atomicWrite(pagePath(generation, nonce, descriptors.size()), bytes, "PAGE", descriptors.size());
                descriptors.add(new Descriptor(page.size(), sha(bytes)));
            }
            Root committed = makeRoot(before.store, generation, nonce, total, descriptors);
            atomicWrite(directory.resolve(ROOT_FILE), encodeRoot(committed), "ROOT", -1);
            Files.delete(directory.resolve(INTENT_FILE)); failureInjector.after(DurablePoint.INTENT_DELETED, -1);
            forceDirectory(directory); failureInjector.after(DurablePoint.INTENT_DELETE_DIRECTORY_FORCED, -1);
            root = committed; pending = null; pendingIntentIdentity = null;
            // At most current+previous pages coexist. Delete only the precisely known old manifest's files.
            for (int ordinal = 0; ordinal < before.pages.size(); ordinal++) {
                Files.deleteIfExists(pagePath(before.generation, before.nonce, ordinal));
                failureInjector.after(DurablePoint.PREVIOUS_PAGE_DELETED, ordinal);
            }
            if (!before.pages.isEmpty()) {
                forceDirectory(directory); failureInjector.after(DurablePoint.PREVIOUS_PAGES_DIRECTORY_FORCED, -1);
            }
        } catch (IOException | RuntimeException failure) { poisoned = true; throw asIo(failure); }
    }

    private Root makeRoot(UUID store, long generation, UUID nonce, int total, List<Descriptor> pages) {
        String stable = sha(("earth-arena-paged-replacement-index-v1\n" + store + "\n" + scopeIdentity + "\n").getBytes(StandardCharsets.UTF_8));
        Root provisional = new Root(store, generation, nonce, total, List.copyOf(pages), null);
        Snapshot snapshot = new Snapshot(store.toString(), stable, generation, sha(encodeRoot(provisional)));
        return new Root(store, generation, nonce, total, provisional.pages, snapshot);
    }
    private byte[] encodeRoot(Root value) {
        ByteBuffer b = ByteBuffer.allocate(ROOT_FIXED_BYTES + value.pages.size() * 36);
        b.putInt(ROOT_MAGIC); putUuid(b, value.store); b.put(unhex(scopeIdentity)); b.putLong(value.generation); putUuid(b, value.nonce);
        b.putInt(value.totalEntries); b.putInt(value.pages.size());
        for (Descriptor descriptor : value.pages) b.putInt(descriptor.count).put(unhex(descriptor.digest));
        appendChecksum(b); return b.array();
    }
    private Root readRoot() throws IOException {
        byte[] bytes = readBounded(directory.resolve(ROOT_FILE), MAX_METADATA_BYTES);
        if (bytes.length < ROOT_FIXED_BYTES) throw new IOException("Truncated index root");
        verifyChecksum(bytes); ByteBuffer b = ByteBuffer.wrap(bytes);
        if (b.getInt() != ROOT_MAGIC) throw new IOException("Unknown index root version");
        UUID store = getUuid(b); String policy = readDigest(b); long generation = b.getLong(); UUID nonce = getUuid(b);
        int total = b.getInt(), count = b.getInt();
        // All attacker-supplied counts are checked before array/list allocation or count-derived math.
        if (!scopeIdentity.equals(policy) || generation <= 0 || total < 0 || total > MAX_ENTRIES || count < 0 || count > MAX_PAGES)
            throw new IOException("Foreign or oversized index metadata");
        if (count != (total + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE || bytes.length != ROOT_FIXED_BYTES + count * 36)
            throw new IOException("Incomplete index page manifest");
        List<Descriptor> pages = new ArrayList<>(count); int sum = 0;
        for (int i = 0; i < count; i++) {
            int entries = b.getInt(); String digest = readDigest(b);
            if (entries < 1 || entries > ENTRIES_PER_PAGE || (i + 1 < count && entries != ENTRIES_PER_PAGE))
                throw new IOException("Invalid fixed page occupancy");
            sum = Math.addExact(sum, entries); pages.add(new Descriptor(entries, digest));
        }
        if (sum != total) throw new IOException("Incomplete declared catalogue size");
        Root decoded = makeRoot(store, generation, nonce, total, pages);
        if (!decoded.snapshot.rootIdentity().equals(sha(bytes))) throw new IOException("Noncanonical index root");
        return decoded;
    }
    private List<Entry> readPage(Root value, int ordinal) throws IOException {
        Descriptor descriptor = value.pages.get(ordinal);
        byte[] bytes = readBounded(pagePath(value.generation, value.nonce, ordinal), MAX_PAGE_BYTES);
        if (bytes.length != PAGE_HEADER_BYTES + descriptor.count * ENTRY_BYTES || !sha(bytes).equals(descriptor.digest))
            throw new IOException("Missing, truncated, or corrupt index page");
        ByteBuffer b = ByteBuffer.wrap(bytes);
        if (b.getInt() != PAGE_MAGIC || !getUuid(b).equals(value.store) || !readDigest(b).equals(scopeIdentity)
                || b.getLong() != value.generation || !getUuid(b).equals(value.nonce) || b.getInt() != ordinal)
            throw new IOException("Foreign, stale, or ABA index page");
        int count = b.getInt();
        if (count < 1 || count > ENTRIES_PER_PAGE || count != descriptor.count) throw new IOException("Invalid page record count");
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Entry entry;
            try { entry = new Entry(new CubePos(b.getInt(), b.getInt(), b.getInt()), b.getLong(), readDigest(b), readDigest(b)); }
            catch (IllegalArgumentException malformed) { throw new IOException("Malformed summary entry", malformed); }
            if (!scopeIdentity.equals(entry.sourcePolicyIdentity())) throw new IOException("Foreign source/material policy in page");
            entries.add(entry);
        }
        return entries;
    }
    private byte[] encodePage(UUID store, long generation, UUID nonce, int ordinal, List<Entry> entries) {
        ByteBuffer b = ByteBuffer.allocate(PAGE_HEADER_BYTES + entries.size() * ENTRY_BYTES);
        b.putInt(PAGE_MAGIC); putUuid(b, store); b.put(unhex(scopeIdentity)); b.putLong(generation); putUuid(b, nonce);
        b.putInt(ordinal).putInt(entries.size());
        for (Entry entry : entries) b.put(encodeEntry(entry));
        return b.array();
    }
    private void verifyRootCurrent() throws IOException {
        Path intent = directory.resolve(INTENT_FILE);
        if (pending == null && Files.exists(intent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unexpected durable write intent");
        if (pending != null && (pendingIntentIdentity == null || !sha(readBounded(intent, MAX_INTENT_BYTES)).equals(pendingIntentIdentity)))
            throw new IOException("Missing or changed durable write intent");
        if (!readRoot().snapshot.equals(root.snapshot)) throw new IOException("Index generation or root changed; stale/ABA state rejected");
    }
    private void requireCurrent(Snapshot expected) throws IOException {
        if (poisoned || pending != null || expected == null || !root.snapshot.equals(expected)) throw new IOException("Stale or unavailable index transaction");
        try { verifyRootCurrent(); } catch (IOException failure) { poisoned = true; throw failure; }
    }
    private void check() { if (poisoned || pending != null) throw new IllegalStateException("Index durability is pending or uncertain"); }
    private QueryPage refused(QueryToken token, String reason) {
        if (token != null && token.owner == this) token.terminal = true;
        return new QueryPage(Status.REFUSED, List.of(), token, null, reason);
    }
    private Path pagePath(long generation, UUID nonce, int ordinal) {
        return directory.resolve(PAGE_PREFIX + generation + "-" + nonce + "-" + ordinal + ".bin");
    }
    private void atomicWrite(Path target, byte[] bytes, String kind, int ordinal) throws IOException {
        int cap = switch (kind) { case "ROOT" -> MAX_METADATA_BYTES; case "PAGE" -> MAX_PAGE_BYTES; default -> MAX_INTENT_BYTES; };
        if (bytes.length > cap) throw new IOException("Bounded durable record overflow");
        Path staged = Files.createTempFile(directory, TEMP_PREFIX, ".tmp");
        try {
            try (FileChannel file = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                ByteBuffer b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) file.write(b); file.force(true);
            }
            failureInjector.after(DurablePoint.valueOf(kind + "_FILE_FORCED"), ordinal);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            failureInjector.after(DurablePoint.valueOf(kind + "_RENAMED"), ordinal);
            forceDirectory(directory); failureInjector.after(DurablePoint.valueOf(kind + "_DIRECTORY_FORCED"), ordinal);
        } finally { Files.deleteIfExists(staged); }
    }
    private static byte[] readBounded(Path path, int cap) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > cap)
            throw new IOException("Missing, nonregular, or oversized durable index file");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(cap + 1); }
        if (bytes.length > cap) throw new IOException("Durable index file exceeded byte cap during read");
        return bytes;
    }
    private static void rejectExistingCubes(Path directory) throws IOException {
        Path cubes = directory.resolve("cubes");
        if (Files.exists(cubes, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(cubes, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid cube data directory");
            try (var files = Files.newDirectoryStream(cubes)) {
                if (files.iterator().hasNext()) throw new IOException("Uninitialized index cannot adopt existing cube data");
            }
        }
    }
    private static byte[] encodeEntry(Entry entry) {
        return ByteBuffer.allocate(ENTRY_BYTES).putInt(entry.position().x()).putInt(entry.position().y()).putInt(entry.position().z())
                .putLong(entry.revision()).put(unhex(entry.sourcePolicyIdentity())).put(unhex(entry.summaryIdentity())).array();
    }
    private static void appendChecksum(ByteBuffer b) {
        MessageDigest digest = sha256(); digest.update(b.array(), 0, b.position()); b.put(digest.digest());
    }
    private static void verifyChecksum(byte[] bytes) throws IOException {
        MessageDigest digest = sha256(); digest.update(bytes, 0, bytes.length - 32);
        byte[] expected = digest.digest();
        for (int i = 0; i < 32; i++) if (bytes[bytes.length - 32 + i] != expected[i]) throw new IOException("Corrupt index root checksum");
    }
    private static MessageDigest resultDigest() { MessageDigest result = sha256(); result.update(RESULT_DOMAIN); return result; }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String sha(byte[] bytes) { return hex(sha256().digest(bytes)); }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static byte[] unhex(String digest) { return HexFormat.of().parseHex(digest); }
    private static void requireDigest(String value) {
        if (value == null || value.length() != 64 || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Exact SHA-256 identity required");
    }
    private static String readDigest(ByteBuffer b) { byte[] bytes = new byte[32]; b.get(bytes); return hex(bytes); }
    private static void putUuid(ByteBuffer b, UUID uuid) { b.putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()); }
    private static UUID getUuid(ByteBuffer b) { return new UUID(b.getLong(), b.getLong()); }
    private static IOException asIo(Exception failure) { return failure instanceof IOException io ? io : new IOException("Invalid bounded index operation", failure); }
    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel file = FileChannel.open(directory, StandardOpenOption.READ)) { file.force(true); }
    }
}
