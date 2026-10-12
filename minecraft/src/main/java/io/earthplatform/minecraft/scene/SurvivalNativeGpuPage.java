package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.minecraft.survival.render.SurvivalDistancePagePlan;
import io.earthplatform.minecraft.survival.render.SurvivalCompleteOverlay;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.AuthenticatedContent;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.CompiledPage;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.CompiledPage.PieceMetadata;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.Layer;
import io.earthplatform.minecraft.survival.render.SurvivalPageGeometry;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

/**
 * Physical page storage only. Construct and retain BEFORE start; retain after every failure until
 * closeComplete. Physical completion/metadata alone cannot prove current content/resources, render admission,
 * collision, camera ownership or a DH complement. Optional private authenticated compile provenance
 * remains independently charged until GPU drain; no legacy admission jobs survive CPU drain.
 *
 * All three native terrain layers are explicit; vertices are synchronously borrowed without copying.
 * Start reserves the whole page before any GL. Slices upload complete physical pieces. A failed slice
 * is never retried: close retries physical cleanup, preserving CPU memory until every GPU cleanup and
 * restoration obligation succeeds. Ordinary Java references to a CompiledPage are not ownership.
 */
public final class SurvivalNativeGpuPage implements AutoCloseable {
    private static final long BASE_METADATA_BYTES = 4096, PIECE_METADATA_BYTES = 4096, RANGE_METADATA_BYTES = 64;
    private final Thread owner = Thread.currentThread();
    private final NativeVisualBudget gpu;
    private final NativeDistanceCpuBudget cpu;
    private final Operations ops;
    private final List<Entry> entries = new ArrayList<>();
    private final Completion completion = new Completion();
    private Source source;
    private AuthenticatedContent authenticated;
    private SurvivalDistancePagePlan.Page page;
    private NativeDistanceCpuBudget.Reservation metadata, scratch;
    private IntBuffer indices;
    private SceneGlRestoreOwner restoration;
    private int uploadedPieces;
    private boolean started, prepared, operating, failed, completed, closed, closeComplete, indexFreeTombstone;

    /** No transfer, reservation, native allocation or GL call occurs in this constructor. */
    public SurvivalNativeGpuPage(NativeVisualBudget gpu, NativeDistanceCpuBudget cpu) {
        this(gpu, cpu, new Operations());
    }
    SurvivalNativeGpuPage(NativeVisualBudget gpu, NativeDistanceCpuBudget cpu, Operations ops) {
        this.gpu = Objects.requireNonNull(gpu); this.cpu = Objects.requireNonNull(cpu); this.ops = Objects.requireNonNull(ops);
    }
    /**
     * Retain this wrapper before calling. Rejected transfer leaves caller ownership unchanged.
     * After successful transfer, all later failures remain this wrapper's cleanup responsibility.
     */
    public void start(CompiledPage compiled) { startSource(new CompiledSource(Objects.requireNonNull(compiled))); }

    // Package-only deterministic test seam. Production's only public input is private-minted CompiledPage.
    interface Source extends AutoCloseable {
        void claim();
        default AuthenticatedContent takeAuthenticatedContent() { return null; }
        SurvivalDistancePagePlan.Page page();
        long completedMemberMask();
        int pieceCount();
        int rangeCount(int index);
        int vertexBytes(int index);
        int indexBytes(int index);
        PieceMetadata metadata(int index);
        void withVertices(int index, CompiledPage.VertexUpload upload);
        long retainedCpuBytes();
        boolean closeComplete();
        @Override void close();
    }
    private static final class CompiledSource implements Source {
        private CompiledPage caller;
        private CompiledPage.UploadOwner owned;
        CompiledSource(CompiledPage caller) { this.caller = caller; }
        public void claim() { owned = caller.transferToUpload(); caller = null; }
        public AuthenticatedContent takeAuthenticatedContent() { return owned.takeAuthenticatedContent(); }
        public SurvivalDistancePagePlan.Page page() { return owned.page(); }
        public long completedMemberMask() { return owned.completedMemberMask(); }
        public int pieceCount() { return owned.pieceCount(); }
        public int rangeCount(int i) { return owned.rangeCount(i); }
        public int vertexBytes(int i) { return owned.vertexBytes(i); }
        public int indexBytes(int i) { return owned.indexBytes(i); }
        public PieceMetadata metadata(int i) { return owned.metadata(i); }
        public void withVertices(int i, CompiledPage.VertexUpload upload) { owned.withVertices(i, upload); }
        public long retainedCpuBytes() { return owned == null ? 0 : owned.retainedCpuBytes(); }
        public boolean closeComplete() { return owned == null || owned.closeComplete(); }
        public void close() { if (owned != null) owned.close(); }
    }
    static class Operations extends NativeVisualGpuStorage.Operations {
        void assertRenderThread() { RenderSystem.assertOnRenderThread(); }
        @Override void invalidate() { BufferUploader.invalidate(); }
        @Override void bindVertexArray(int id) { GlStateManager._glBindVertexArray(id); }
        @Override void bindBuffer(int target, int id) { GlStateManager._glBindBuffer(target, id); }
        IntBuffer allocateIndices(int count) { return MemoryUtil.memAllocInt(count); }
        void beforeFreeIndices() {}
        void freeIndices(IntBuffer buffer) { MemoryUtil.memFree(buffer); }
        void setupFormat(VertexFormat format) { format.setupBufferState(); }
        long nanoTime() { return System.nanoTime(); }
    }
    private record Entry(PieceMetadata metadata, NativeVisualGpuStorage storage) {}
    public record StepStats(int pieces, int uploadedPieces, int totalPieces, long elapsedNanos,
            long budgetNanos, long overshootNanos, boolean complete) {}
    public record DrawStats(long calls, long quads) {}

    void startSource(Source input) {
        checkOwner();
        if (started || closed) throw new IllegalStateException("Native GPU page start is single-use");
        started = true;
        source = Objects.requireNonNull(input); // Keep the recoverable owner BEFORE claim or admission can fail.
        operating = true;
        try {
            source.claim();
            authenticated = source.takeAuthenticatedContent(); // Exact producer transfer, never caller attachment.
            int count = source.pieceCount();
            if (count < 0 || count > NativeVisualBudget.MAX_PIECES)
                throw new IllegalStateException("Native page exceeds bounded physical piece count");
            long bytes = Math.addExact(BASE_METADATA_BYTES, Math.multiplyExact(PIECE_METADATA_BYTES, count));
            int maxIndexBytes = 0;
            for (int i = 0; i < count; i++) {
                int ranges = source.rangeCount(i);
                if (ranges < 1 || ranges > 64) throw new IllegalArgumentException("Invalid native page member range count");
                bytes = Math.addExact(bytes, Math.multiplyExact(RANGE_METADATA_BYTES, ranges));
                NativeVisualBudget.allocationBytes(source.vertexBytes(i));
                NativeVisualBudget.allocationBytes(source.indexBytes(i));
                maxIndexBytes = Math.max(maxIndexBytes, source.indexBytes(i));
            }
            metadata = cpu.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA, bytes);
            page = source.page();
            if (authenticated != null) authenticated.requireExactPage(page);
            if (page.memberMask() != source.completedMemberMask())
                throw new IllegalStateException("GPU input does not cover every selected native page member");
            int[] nextOrdinal = new int[Layer.values().length];
            int previousLayer = -1;
            for (int i = 0; i < count; i++) {
                var piece = source.metadata(i);
                validate(piece, page.memberMask());
                if (piece.layer().ordinal() < previousLayer || piece.ordinal() != nextOrdinal[piece.layer().ordinal()]++)
                    throw new IllegalArgumentException("Native layer pieces must be canonical and contiguous");
                previousLayer = piece.layer().ordinal();
                if (piece.vertexBytes() != source.vertexBytes(i) || piece.indexBytes() != source.indexBytes(i)
                        || piece.memberRanges().size() != source.rangeCount(i))
                    throw new IllegalArgumentException("Native page metadata changed during reservation");
                var storage = new NativeVisualGpuStorage(gpu, ops);
                entries.add(new Entry(piece, storage)); // Register before a partial reservation failure.
                storage.reserve(piece.vertexBytes(), piece.indexBytes());
            }
            if (maxIndexBytes != 0) {
                scratch = cpu.reserve(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD, maxIndexBytes);
                indices = ops.allocateIndices(maxIndexBytes / Integer.BYTES);
                if (indices == null || !indices.isDirect() || indices.capacity() != maxIndexBytes / Integer.BYTES)
                    throw new IllegalStateException("Index allocator returned different physical storage");
            }
            prepared = true;
        } catch (RuntimeException | Error error) { failed = true; throw error; }
        finally { operating = false; }
    }
    private static void validate(PieceMetadata piece, long mask) {
        Objects.requireNonNull(piece); Objects.requireNonNull(piece.layer());
        if (piece.ordinal() < 0 || piece.vertices() < 4 || piece.vertices() > SurvivalPageGeometry.capacityVertices(piece.stride()))
            throw new IllegalArgumentException("Invalid native page piece metadata");
        SurvivalPageGeometry.requireRanges(piece.memberRanges(), piece.vertices());
        for (var range : piece.memberRanges()) if ((mask & 1L << range.member()) == 0)
            throw new IllegalArgumentException("Native piece names an unselected member");
    }
    /** Count is hard; time is soft because a single GL upload cannot be interrupted. */
    public StepStats step(int maxPieces, long maxNanos) {
        checkOwner();
        if (maxPieces < 1 || maxNanos < 1) throw new IllegalArgumentException("Positive upload slice limits required");
        if (!prepared || failed || closed || completed || operating)
            throw new IllegalStateException("Native page is not resumable");
        operating = true;
        int initial = uploadedPieces;
        try {
            long begin = ops.nanoTime();
            while (uploadedPieces < entries.size()) {
                var entry = entries.get(uploadedPieces);
                canonicalIndices(indices, entry.metadata.vertices());
                source.withVertices(uploadedPieces, (vertices, format) ->
                        entry.storage.upload(vertices, indices, () -> ops.setupFormat(format)));
                uploadedPieces++;
                if (uploadedPieces - initial >= maxPieces || elapsed(begin) >= maxNanos) break;
            }
            if (uploadedPieces == entries.size()) {
                drainScratch();
                source.close();
                if (!source.closeComplete()) throw new IllegalStateException("Native page CPU close did not complete");
                source = null; // No bridge, Build, job or CPU byte-buffer alias survives completion.
                // Independent charged provenance retains exact Capture format/resource identities, when present.
                completed = true;
            }
            long duration = elapsed(begin);
            return new StepStats(uploadedPieces - initial, uploadedPieces, entries.size(), duration, maxNanos,
                    Math.max(0, duration - maxNanos), completed);
        } catch (RuntimeException | Error error) { failed = true; throw error; }
        finally { operating = false; }
    }
    static void canonicalIndices(IntBuffer target, int vertices) {
        if (vertices < 4 || vertices % 4 != 0 || target == null
                || Math.multiplyExact(vertices / 4, 6) > target.capacity())
            throw new IllegalArgumentException("Bounded complete-quad index staging required");
        target.clear();
        for (int v = 0; v < vertices; v += 4) target.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
        target.flip();
    }
    private long elapsed(long begin) { return Math.max(0, ops.nanoTime() - begin); }
    public Completion completion() { requireComplete(); return completion; }
    /** Live physical receipt with optional private input provenance; never a whole-scene publication lease. */
    public final class Completion {
        private Completion() {}
        public SurvivalDistancePagePlan.Page page() { requireComplete(); return page; }
        public int pieceCount() { requireComplete(); return entries.size(); }
        public PieceMetadata piece(int index) { requireComplete(); return entries.get(index).metadata; }
        public void requirePhysicalComplete() { requireComplete(); }
        public void restorePendingBindings(){checkOwner();if(operating)throw new IllegalStateException("Native page operation has not returned");restore();}
        DrawStats drawJoined(SurvivalSceneFrameJoin.NativeDraw invocation,int index){
            invocation.beginPage(this,index);boolean success=false;
            try{var result=drawLayer(invocation.layer(),invocation.mask(index),invocation);success=true;return result;}
            finally{invocation.endPage(success);}
        }

        /**
         * Authenticate this exact live physical page's actual compiler source/resource/policy proof.
         * Diagnostic completions refuse. This does not latch current content, a full native/DH pair,
         * camera/depth eligibility, collision or draw readiness; the parent publisher must join them.
         */
        public void requireAuthenticated(SurvivalCompleteOverlay.PlanInput exactPlan,
                SceneNativeShaderContract.Capture exactCapture, SceneNeighborhood.LightingPolicy expectedLighting,
                boolean expectedDirectionalShadingDisabled) {
            requireComplete();
            if (authenticated == null) throw new IllegalStateException("Diagnostic native page has no authenticated compile provenance");
            authenticated.requireMatches(exactPlan, exactCapture, expectedLighting, expectedDirectionalShadingDisabled);
        }
    }
    private void requireComplete() {
        checkOwner();
        if (!completed || failed || closed || operating || restoration != null || source != null || indices != null || scratch != null)
            throw new IllegalStateException("Native GPU page is not physically complete");
        for (var entry : entries) entry.storage.requireDrawable();
    }
    /** Host must establish its separate shader/resource/currentness pass before this package-only draw. */
    DrawStats drawLayer(Layer layer, long memberMask) {
        return drawLayer(layer,memberMask,null);
    }
    private DrawStats drawLayer(Layer layer,long memberMask,SurvivalSceneFrameJoin.NativeDraw invocation){
        requireComplete(); Objects.requireNonNull(layer);
        long calls = 0, quads = 0; Throwable failure = null;
        operating = true;
        try {
            try {
                ops.check("page.draw.entry", 0, 0, 0);
                var prior = ops.capture(); ops.check("page.draw.capture", 0, 0, 0);
                restoration = new SceneGlRestoreOwner(() -> ops.restore(prior),
                        () -> ops.check("page.draw.restore.before", 0, 0, 0), () -> ops.check("page.draw.restore.after", 0, 0, 0));
                for (var entry : entries) {
                    if (entry.metadata.layer() != layer) continue;
                    int first = -1, count = 0;
                    for (var range : entry.metadata.memberRanges()) {
                        if ((memberMask & 1L << range.member()) == 0) continue;
                        if (first >= 0 && first + count == range.firstIndex()) count = Math.addExact(count, range.indexCount());
                        else {
                            if (first >= 0) { entry.storage.bind(); if(invocation!=null)invocation.beforeRange(); entry.storage.drawRange(count, first); calls++; quads += count / 6; }
                            first = range.firstIndex(); count = range.indexCount();
                        }
                    }
                    if (first >= 0) { entry.storage.bind(); if(invocation!=null)invocation.beforeRange(); entry.storage.drawRange(count, first); calls++; quads += count / 6; }
                }
            } catch (RuntimeException | Error error) { failure = error; failed = true; }
            try { restore(); } catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); failed = true; }
            SceneBuildStepper.rethrow(failure); return new DrawStats(calls, quads);
        } finally { operating = false; }
    }
    private void restore() {
        if (restoration != null) { restoration.restore(); if (!restoration.pending()) restoration = null; }
    }
    private void drainScratch() {
        if (indexFreeTombstone)
            throw new IllegalStateException("Index free outcome is unknown; retain charged tombstone without retrying the address");
        if (indices != null) {
            ops.beforeFreeIndices(); // Failure here is proven to precede the one-shot native free.
            var pending = indices; indices = null;
            indexFreeTombstone = true; // A throwing free may already have consumed/reused the address.
            ops.freeIndices(pending);
            indexFreeTombstone = false;
        }
        if (scratch != null) { scratch.releaseAfterPhysicalDrain(); scratch = null; }
    }
    /** Counts issued GL draws even if their following diagnostic threw. No visibility/currentness proof. */
    public long issuedDrawCalls() { checkOwner(); long count = 0; for (var entry : entries) count += entry.storage.drawCalls(); return count; }
    public long chargedGpuBytes() { checkOwner(); long bytes = 0; for (var entry : entries) bytes += entry.storage.chargedBytes(); return bytes; }
    public long retainedCpuBytes() {
        checkOwner(); return (source == null ? 0 : source.retainedCpuBytes())
                + (metadata == null ? 0 : metadata.bytes()) + (scratch == null ? 0 : scratch.bytes())
                + (authenticated == null ? 0 : authenticated.retainedMetadataBytes());
    }
    public boolean closeComplete() { checkOwner(); return closeComplete; }
    public boolean indexCleanupTombstone() { checkOwner(); return indexFreeTombstone; }
    public boolean physicallyComplete() {
        checkOwner(); return completed && !failed && !closed && !operating && restoration == null
                && source == null && indices == null && scratch == null;
    }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Native GPU page requires its render owner");
        ops.assertRenderThread();
    }
    @Override public void close() {
        checkOwner();
        if (operating) throw new IllegalStateException("Retain native page until the active operation returns");
        if (closeComplete) return;
        closed = true; operating = true;
        try {
            Throwable failure = null;
            try { restore(); } catch (RuntimeException | Error error) { failure = error; }
            for (var entry : entries) try { entry.storage.close(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            // Never drain native CPU bytes while even one GPU restoration/deletion/tombstone remains.
            if (failure == null && restoration == null && entries.stream().allMatch(e -> e.storage.closeComplete())) {
                try { drainScratch(); } catch (RuntimeException | Error error) { failure = error; }
                if (failure == null && source != null) try {
                    source.close();
                    if (!source.closeComplete()) throw new IllegalStateException("Native CPU owner remains open");
                    source = null;
                } catch (RuntimeException | Error error) { failure = error; }
                if (failure == null) {
                    entries.clear(); page = null;
                    if (authenticated != null) { authenticated.close(); authenticated = null; }
                    if (metadata != null) { metadata.releaseAfterPhysicalDrain(); metadata = null; }
                    closeComplete = true;
                }
            }
            SceneBuildStepper.rethrow(failure);
        } finally { operating = false; }
    }
}
