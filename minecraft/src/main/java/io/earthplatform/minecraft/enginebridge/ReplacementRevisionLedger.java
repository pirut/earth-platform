package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import io.earthplatform.minecraft.lod.DhLodLayout;
import java.util.UUID;

/**
 * Two-entry fail-closed revision journal, held under the native host's exclusive store lock.
 * A durable intent precedes cube mutation; durable confirmation follows cube save and precedes ACK.
 * A crash or failure with an unresolved intent requires explicit recovery, never guessed revision0.
 */
final class ReplacementRevisionLedger {
    private static final String HEADER = "earth-replacement-store-v2\n";
    private final Path directory, target;
    private final ReplacementWorkspaces workspaces;
    private final ReplacementStoreIdentity.TwoWorkspaceIdentity identity;
    private final String scope;
    private final long[] revisions;
    private int pending = -1;
    private long pendingRevision;
    private boolean poisoned;
    private final PagedReplacementIndex paged;
    private final String indexedScope;
    private List<PagedReplacementIndex.Entry> indexedEntries = List.of();
    private PagedReplacementIndex.Snapshot indexedSnapshot;
    private PagedReplacementIndex.Completion indexedCompletion;
    private ReplacementQueryAttestation queryAttestation;

    private ReplacementRevisionLedger(Path directory, ReplacementWorkspaces workspaces, String store,
            long[] revisions, int pending, long pendingRevision) {
        this.directory = directory; this.target = directory.resolve(ReplacementStoreIdentity.setFile(workspaces));
        this.paged = null; this.indexedScope = null;
        this.workspaces = workspaces; this.revisions = revisions; this.pending = pending; this.pendingRevision = pendingRevision;
        scope = (workspaces.isAdjacent() ? "earth-adjacent-replacement-store-v1\n" : HEADER) + store + "\n" + workspaces.canonicalText() + workspaces.identity() + "\n";
        identity = new ReplacementStoreIdentity.TwoWorkspaceIdentity(store, workspaces.identity(), ReplacementWorkspaces.sha256(scope));
    }

    private ReplacementRevisionLedger(Path directory, ReplacementWorkspaces workspaces,
            PagedReplacementIndex paged, String indexedScope) throws IOException {
        this.directory = directory; this.target = null; this.workspaces = workspaces;
        this.paged = paged; this.indexedScope = indexedScope; this.scope = null; this.revisions = new long[2];
        this.indexedSnapshot = paged.snapshot();
        this.identity = new ReplacementStoreIdentity.TwoWorkspaceIdentity(indexedSnapshot.storeIdentity(),
                workspaces.identity(), paged.indexIdentity());
        if (paged.isInitialized()) acceptScan(scan(indexedSnapshot));
    }

    /** Explicit new store mode. Never creates, reads or adopts a legacy 011 revision index. */
    static ReplacementRevisionLedger openIndexed(Path directory, ReplacementWorkspaces workspaces,
            String scopeIdentity) throws IOException {
        Objects.requireNonNull(directory); Objects.requireNonNull(workspaces);
        if (!workspaces.isAdjacent()) throw new IllegalArgumentException("Indexed gate retains exactly two adjacent workspaces");
        return new ReplacementRevisionLedger(directory, workspaces, PagedReplacementIndex.open(directory, scopeIdentity), scopeIdentity);
    }

    static ReplacementRevisionLedger open(Path directory, ReplacementWorkspaces workspaces) throws IOException {
        Objects.requireNonNull(directory); Objects.requireNonNull(workspaces);
        if (Files.exists(directory.resolve(PagedReplacementIndex.ROOT_FILE)) || Files.exists(directory.resolve(PagedReplacementIndex.INTENT_FILE)))
            throw new IOException("Paged replacement store requires its explicit indexed mode; refusing legacy adoption");
        if (Files.exists(directory.resolve(ReplacementStoreIdentity.FILE)))
            throw new IOException("Legacy single-region store requires explicit two-workspace migration");
        if (Files.exists(directory.resolve(workspaces.isAdjacent() ? ReplacementStoreIdentity.TWO_FILE : ReplacementStoreIdentity.ADJACENT_FILE)))
            throw new IOException("Different replacement workspace mode requires a separate store; refusing adoption");
        Path target = directory.resolve(ReplacementStoreIdentity.setFile(workspaces));
        if (!Files.exists(target)) {
            Path cubes = directory.resolve("cubes");
            if (Files.exists(cubes)) {
                if (!Files.isDirectory(cubes)) throw new IOException("Invalid cube data directory");
                try (var files = Files.list(cubes)) {
                    if (files.findAny().isPresent()) throw new IOException("Missing replacement-set index for existing cube data; refusing adoption");
                }
            }
            var fresh = new ReplacementRevisionLedger(directory, workspaces, UUID.randomUUID().toString(), new long[2], -1, 0);
            fresh.write(false); return fresh;
        }
        byte[] bytes;
        try (var in = Files.newInputStream(target)) { bytes = in.readNBytes(ReplacementStoreIdentity.MAX_TWO_BYTES + 1); }
        if (bytes.length > ReplacementStoreIdentity.MAX_TWO_BYTES) throw new IOException("Oversized replacement-set index");
        String value = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = value.split("\n", -1);
        if (lines.length != 10 || !lines[0].equals(workspaces.isAdjacent() ? "earth-adjacent-replacement-store-v1" : "earth-replacement-store-v2") || !lines[9].isEmpty())
            throw new IOException("Malformed replacement-set index framing");
        String id = lines[1];
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException malformed) { throw new IOException("Malformed replacement-set store identity", malformed); }
        long[] revisions = new long[2]; int pending = -1; long next = 0;
        for (int i = 0; i < 2; i++) {
            String[] fields = lines[6 + i].split(" ", -1);
            if (fields.length != 2) throw new IOException("Malformed replacement revision record");
            revisions[i] = natural(fields[0]); long proposed = natural(fields[1]);
            if (proposed != 0) {
                if (pending != -1 || revisions[i] == Long.MAX_VALUE || proposed != revisions[i] + 1)
                    throw new IOException("Invalid replacement write-intent revision");
                pending = i; next = proposed;
            }
        }
        var result = new ReplacementRevisionLedger(directory, workspaces, id, revisions, pending, next);
        if (!result.encode().equals(value)) throw new IOException("Noncanonical or mismatched replacement workspace index");
        if (pending != -1) throw new IOException("Unresolved durable replacement write intent; explicit recovery required");
        try (FileChannel file = FileChannel.open(target, StandardOpenOption.WRITE)) { file.force(true); }
        forceDirectory(directory);
        return result;
    }

    ReplacementStoreIdentity.TwoWorkspaceIdentity identity() { return identity; }
    synchronized long expectedRevision(CubePos position) { check(); return revisions[index(position)]; }
    synchronized String durableIdentity() {
        check();
        return paged == null ? ReplacementWorkspaces.sha256(encode()) : Objects.requireNonNull(attestation(), "Complete indexed query required").identity();
    }
    synchronized boolean indexed() { return paged != null; }
    synchronized ReplacementQueryAttestation attestation() {
        if (paged == null) return null;
        // Native/publication paths never perform filesystem IO. The private completion remains
        // current under exclusive host ownership; disk-worker scans fence every mutation.
        return !poisoned && pending == -1 && paged.issuedCompletionCurrent(indexedCompletion) ? queryAttestation : null;
    }
    synchronized boolean queryCurrent(ReplacementQueryAttestation query) {
        return paged == null ? query == null : query != null && query.equals(attestation());
    }
    synchronized void verifyCapture(ReplacementSetCertificate.Region region) throws IOException {
        check();
        if (paged == null || !paged.isInitialized()) return;
        PagedReplacementIndex.Entry stored = indexedEntries.stream().filter(e -> e.position().equals(region.position()))
                .findFirst().orElseThrow(() -> new IOException("Missing persisted replacement summary"));
        if (stored.revision() != region.revision() || !stored.summaryIdentity().equals(region.captureIdentity()))
            throw new IOException("Reconstructed capture differs from committed indexed summary");
    }
    /** Called on the bounded disk worker after both serial captures, never on the native owner. */
    synchronized void sealCaptures(List<NativeReplacementCapture> captures) throws IOException {
        check();
        if (paged == null) return;
        List<PagedReplacementIndex.Entry> reconstructed = entries(captures, null, -1);
        if (!paged.isInitialized()) {
            paged.initializeSummaries(indexedSnapshot, reconstructed);
            indexedSnapshot = paged.snapshot();
        }
        Scan checked = scan(indexedSnapshot);
        if (!checked.entries().equals(reconstructed)) throw new IOException("Serial reconstruction differs from persisted indexed capture identities");
        acceptScan(checked);
    }

    private record Scan(PagedReplacementIndex.Completion completion, List<PagedReplacementIndex.Entry> entries) { }
    private Scan scan(PagedReplacementIndex.Snapshot expected) throws IOException {
        if (!paged.isCurrent(expected)) throw new IOException("Stale or uncertain replacement index snapshot");
        var token = paged.beginQuery(PagedReplacementIndex.Bounds.all(), ReplacementWorkspaces.COUNT);
        var values = new ArrayList<PagedReplacementIndex.Entry>(ReplacementWorkspaces.COUNT);
        for (;;) {
            var page = paged.next(token);
            if (page.status() == PagedReplacementIndex.Status.REFUSED)
                throw new IOException("Complete bounded replacement index query refused");
            if (values.size() + page.entries().size() > ReplacementWorkspaces.COUNT)
                throw new IOException("A third visible indexed region cannot be admitted by the two-region gate");
            values.addAll(page.entries());
            if (page.status() == PagedReplacementIndex.Status.COMPLETE) {
                var completion = Objects.requireNonNull(page.completion(), "Missing COMPLETE query receipt");
                if (!completion.snapshot().equals(expected) || !paged.isCurrent(expected)
                        || completion.resultCount() != ReplacementWorkspaces.COUNT || values.size() != ReplacementWorkspaces.COUNT
                        || !values.stream().map(PagedReplacementIndex.Entry::position).toList().equals(workspaces.positions())
                        || values.stream().anyMatch(e -> !e.sourcePolicyIdentity().equals(indexedScope))
                        || !completion.resultIdentity().equals(PagedReplacementIndex.resultIdentity(values)))
                    throw new IOException("Incomplete, foreign or stale canonical indexed workspace result");
                return new Scan(completion, List.copyOf(values));
            }
            token = page.token();
        }
    }
    private void acceptScan(Scan scan) {
        var completion = scan.completion(); var snapshot = completion.snapshot(); var bounds = completion.bounds();
        indexedEntries = scan.entries(); indexedSnapshot = snapshot; indexedCompletion = completion;
        for (int i = 0; i < 2; i++) revisions[i] = indexedEntries.get(i).revision();
        queryAttestation = new ReplacementQueryAttestation(snapshot.storeIdentity(), snapshot.indexIdentity(),
                snapshot.generation(), snapshot.rootIdentity(), bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxXExclusive(), bounds.maxYExclusive(), bounds.maxZExclusive(), DhLodLayout.LONG_4096.identity(), indexedScope,
                completion.resultIdentity(), completion.resultCount());
    }
    private List<PagedReplacementIndex.Entry> entries(List<NativeReplacementCapture> captures,
            CubePos changed, long changedRevision) throws IOException {
        if (captures == null || captures.size() != ReplacementWorkspaces.COUNT)
            throw new IOException("Exactly two canonical capture summaries required");
        NativeReplacementCapture.verifySharedValues(captures);
        var values = new ArrayList<PagedReplacementIndex.Entry>(2);
        for (int i = 0; i < 2; i++) {
            var position = workspaces.positions().get(i); var capture = captures.get(i);
            if (!position.equals(capture.position())) throw new IOException("Canonical indexed summary order required");
            values.add(new PagedReplacementIndex.Entry(position, position.equals(changed) ? changedRevision : revisions[i],
                    indexedScope, capture.contentIdentity()));
        }
        return List.copyOf(values);
    }

    /** Checks all54 dependencies: only the two configured centers may ever have nonzero edits. */
    synchronized void verifyObserved(CubePos position, long revision) throws IOException {
        check();
        if (pending != -1) throw new IOException("Replacement write intent is unresolved");
        int slot = workspaces.positions().indexOf(position);
        long expected = slot < 0 ? 0 : revisions[slot];
        if (revision != expected)
            throw new IOException("Durable replacement revision differs from index for " + position + ": expected " + expected + ", observed " + revision);
    }

    /** Call only after all pure material/budget preflight passes. No-op writes still advance revision. */
    synchronized void begin(CubePos position, long expectedRevision) throws IOException {
        if (paged != null) throw new IOException("Indexed edit requires complete prospective summary preflight");
        check(); int slot = index(position);
        if (pending != -1) throw new IOException("Only one durable replacement write intent may be pending");
        if (revisions[slot] != expectedRevision) throw new IOException("Stale replacement ledger revision");
        long next;
        try { next = Math.incrementExact(expectedRevision); }
        catch (ArithmeticException overflow) { throw new IOException("Replacement revision exhausted", overflow); }
        pending = slot; pendingRevision = next;
        try { write(true); } catch (IOException failure) { poisoned = true; throw failure; }
    }

    /** The complete paged query and both prospective summaries precede the durable intent. */
    synchronized void begin(CubePos position, long expectedRevision, List<NativeReplacementCapture> prospective) throws IOException {
        if (paged == null) { begin(position, expectedRevision); return; }
        check(); int slot = index(position);
        if (pending != -1 || revisions[slot] != expectedRevision) throw new IOException("Stale or pending indexed edit");
        final long next;
        try { next = Math.incrementExact(expectedRevision); }
        catch (ArithmeticException overflow) { throw new IOException("Replacement revision exhausted", overflow); }
        // A fresh complete scan must match the exact prior publication. No implicit snapshot upgrade.
        Scan checked = scan(indexedSnapshot);
        if (!checked.entries().equals(indexedEntries)) throw new IOException("Indexed summaries changed before durable admission");
        var proposed = entries(prospective, position, next);
        try { paged.begin(indexedSnapshot, proposed); pending = slot; pendingRevision = next; }
        catch (IOException | RuntimeException failure) { poisoned = !paged.isCurrent(indexedSnapshot); throw failure; }
    }

    /** A matching successful cube save must already have crossed its strict file+directory barrier. */
    synchronized void confirm(CubePos position, long durableRevision) throws IOException {
        check(); int slot = index(position);
        if (pending != slot || pendingRevision != durableRevision)
            throw new IOException("Durable replacement confirmation does not match its pending intent");
        if (paged != null) {
            try {
                paged.confirm();
                acceptScan(scan(paged.snapshot()));
                if (revisions[slot] != durableRevision) throw new IOException("Confirmed index revision differs from saved cube");
                pending = -1; pendingRevision = 0;
            } catch (IOException | RuntimeException failure) { poisoned = true; throw failure; }
            return;
        }
        revisions[slot] = durableRevision; pending = -1; pendingRevision = 0;
        try { write(true); } catch (IOException failure) { poisoned = true; throw failure; }
    }

    private String encode() {
        String payload = scope + revisions[0] + " " + (pending == 0 ? pendingRevision : 0) + "\n"
                + revisions[1] + " " + (pending == 1 ? pendingRevision : 0) + "\n";
        return payload + ReplacementWorkspaces.sha256(payload) + "\n";
    }
    private void write(boolean replace) throws IOException {
        byte[] bytes = encode().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ReplacementStoreIdentity.MAX_TWO_BYTES) throw new IOException("Replacement revision index exceeds its fixed bound");
        Path staged = Files.createTempFile(directory, ".replacement-set-index-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
            }
            if (replace) Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(directory);
        } finally { Files.deleteIfExists(staged); }
    }
    private int index(CubePos position) {
        int result = workspaces.positions().indexOf(position);
        if (result < 0) throw new IllegalArgumentException("Unconfigured replacement workspace");
        return result;
    }
    private void check() { if (poisoned) throw new IllegalStateException("Replacement journal durability is uncertain"); }
    private static long natural(String value) throws IOException {
        try {
            long result = Long.parseLong(value);
            if (result < 0 || !Long.toString(result).equals(value)) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException malformed) { throw new IOException("Malformed replacement revision", malformed); }
    }
    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
}
