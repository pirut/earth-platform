package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.survival.render.SurvivalDistancePagePlan;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.CompiledPage;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.CompiledPage.PieceMetadata;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.Layer;
import io.earthplatform.minecraft.survival.render.SurvivalPageGeometry.MemberRange;
import io.earthplatform.minecraft.survival.render.SurvivalVisualPage;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL33C.*;

/** Deterministic production-owner/real-ledger tests. No real-GL, native-model or publication claim. */
class SurvivalNativeGpuPageTest {
    private static NativeDistanceCpuBudget cpu() { return new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES); }
    private static final class Source implements SurvivalNativeGpuPage.Source {
        final List<PieceMetadata> pieces;
        final List<ByteBuffer> buffers = new ArrayList<>();
        final NativeDistanceCpuBudget.Reservation reservation;
        final SurvivalDistancePagePlan.Page page = new SurvivalDistancePagePlan.Page(new SurvivalVisualPage(new CubePos(-4, 0, 4)), 31L, 0, 0);
        boolean claimed, borrowed, closed, failClose, incompleteClose;
        int closeCalls;
        long completedMask = 31L;
        Source(NativeDistanceCpuBudget cpu, int count) {
            var result = new ArrayList<PieceMetadata>();
            for (int i = 0; i < count; i++) {
                var ranges = new ArrayList<MemberRange>();
                for (int member = 0; member < 5; member++) ranges.add(new MemberRange(member, member * 4, 4));
                result.add(new PieceMetadata(Layer.values()[i % 3], i / 3, 32, 20, ranges));
                var bytes = ByteBuffer.allocateDirect(640).order(ByteOrder.nativeOrder());
                for (int at = 0; at < bytes.capacity(); at++) bytes.put((byte) (at * 13 + i));
                bytes.flip(); buffers.add(bytes.asReadOnlyBuffer());
            }
            pieces = List.copyOf(result);
            reservation = cpu.reserve(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD, Math.max(1, count) * 1024L);
        }
        public void claim() { if (claimed || closed) throw new IllegalStateException("transfer repeated"); claimed = true; }
        public SurvivalDistancePagePlan.Page page() { return page; }
        public long completedMemberMask() { return completedMask; }
        public int pieceCount() { return pieces.size(); }
        public int rangeCount(int i) { return pieces.get(i).memberRanges().size(); }
        public int vertexBytes(int i) { return pieces.get(i).vertexBytes(); }
        public int indexBytes(int i) { return pieces.get(i).indexBytes(); }
        public PieceMetadata metadata(int i) { return pieces.get(i); }
        public void withVertices(int i, CompiledPage.VertexUpload upload) {
            if (!claimed || closed || borrowed) throw new IllegalStateException("bad borrow");
            borrowed = true;
            try { upload.upload(buffers.get(i), null); } finally { borrowed = false; }
        }
        public long retainedCpuBytes() { return reservation.released() ? 0 : reservation.bytes(); }
        public boolean closeComplete() { return closed; }
        public void close() {
            if (borrowed) throw new IllegalStateException("borrow active");
            closeCalls++;
            if (failClose) { failClose = false; throw new IllegalStateException("CPU close failure"); }
            if (incompleteClose) return;
            if (!closed) { buffers.clear(); reservation.releaseAfterPhysicalDrain(); closed = true; }
        }
    }
    private static final class Ops extends SurvivalNativeGpuPage.Operations {
        int vao = 700, array = 701, nextName = 1, glCalls, deletes, restores, invalidations, freed, freeAttempts;
        final Map<Integer, Integer> ebos = new HashMap<>(Map.of(700, 702));
        final Map<Integer, Integer> sizes = new HashMap<>();
        final Set<Integer> live = new HashSet<>();
        final List<ByteBuffer> vertexInputs = new ArrayList<>();
        final List<int[]> indexInputs = new ArrayList<>();
        final List<String> draws = new ArrayList<>();
        String failPhase; int failures = 1; boolean failQuery, surviveDelete, failBeforeFree, failFree, failAfterFree;
        Runnable onCreate = () -> {}, onUpload = () -> {}, onIndexAllocate = () -> {};
        @Override void assertRenderThread() {}
        @Override Bindings capture() { glCalls++; return new Bindings(vao, array); }
        @Override void invalidate() { glCalls++; invalidations++; }
        @Override void bindVertexArray(int id) { glCalls++; vao = id; }
        @Override void bindBuffer(int target, int id) { glCalls++; if (target == GL_ARRAY_BUFFER) array = id; else ebos.put(vao, id); }
        @Override int create(int kind) { glCalls++; onCreate.run(); int id = nextName++; live.add(id); return id; }
        private int bound(int target) { return target == GL_ARRAY_BUFFER ? array : ebos.getOrDefault(vao, 0); }
        @Override void allocate(int target, int bytes) { glCalls++; sizes.put(bound(target), bytes); }
        @Override int size(int target) { glCalls++; return sizes.get(bound(target)); }
        @Override void upload(int target, ByteBuffer bytes) { glCalls++; vertexInputs.add(bytes); onUpload.run(); }
        @Override void upload(int target, IntBuffer bytes) { glCalls++; int[] values = new int[bytes.remaining()]; bytes.duplicate().get(values); indexInputs.add(values); }
        @Override void draw(int count, long offset) { glCalls++; draws.add(count + ":" + offset); }
        @Override void delete(int kind, int id) { glCalls++; deletes++; if (surviveDelete) surviveDelete = false; else live.remove(id); }
        @Override boolean alive(int kind, int id) { glCalls++; if (failQuery) { failQuery = false; throw new IllegalStateException("query failed"); } return live.contains(id); }
        @Override void restore(Bindings prior) { restores++; super.restore(prior); }
        @Override void check(String phase, int vao, int vbo, int ibo) { glCalls++; if (phase.equals(failPhase) && failures-- > 0) throw new IllegalStateException(phase); }
        @Override IntBuffer allocateIndices(int count) { onIndexAllocate.run(); return ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer(); }
        @Override void beforeFreeIndices() { if (failBeforeFree) { failBeforeFree = false; throw new IllegalStateException("before free failed"); } }
        @Override void freeIndices(IntBuffer buffer) {
            freeAttempts++;
            if (failFree) { failFree = false; throw new IllegalStateException("inside free failed"); }
            freed++;
            if (failAfterFree) { failAfterFree = false; throw new IllegalStateException("after free failed"); }
        }
        @Override void setupFormat(VertexFormat ignored) {}
    }
    private record Fixture(NativeVisualBudget gpu, NativeDistanceCpuBudget cpu, Source source, Ops ops, SurvivalNativeGpuPage page) {}
    private static Fixture fixture(int count) {
        var gpu = new NativeVisualBudget(); var cpu = cpu(); var source = new Source(cpu, count); var ops = new Ops();
        return new Fixture(gpu, cpu, source, ops, new SurvivalNativeGpuPage(gpu, cpu, ops));
    }
    private static void start(Fixture f) { f.page.startSource(f.source); }
    private static void assertRetained(Fixture f) {
        assertFalse(f.page.physicallyComplete()); assertFalse(f.page.closeComplete());
        assertThrows(IllegalStateException.class, f.page::completion);
        assertFalse(f.source.closed); assertTrue(f.source.retainedCpuBytes() > 0);
    }
    @Test void constructorIsInertAndWholePageAdmissionPrecedesEveryGlCall() {
        var f = fixture(3); assertFalse(f.source.claimed); assertEquals(0, f.ops.glCalls);
        f.ops.onCreate = () -> {
            assertEquals(3, f.gpu.evidence().pieces());
            assertTrue(f.cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.OWNER_METADATA) > 0);
            assertEquals(3 * 1024 + 120, f.cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD));
        };
        start(f); assertTrue(f.source.claimed); assertEquals(0, f.ops.glCalls);
        var originals = List.copyOf(f.source.buffers);
        var first = f.page.step(1, Long.MAX_VALUE); assertFalse(first.complete()); assertEquals(1, first.uploadedPieces()); assertRetained(f);
        assertFalse(f.page.step(1, Long.MAX_VALUE).complete());
        assertTrue(f.page.step(1, Long.MAX_VALUE).complete());
        assertEquals(3, f.page.completion().pieceCount()); assertTrue(f.source.closed); assertEquals(0, f.source.retainedCpuBytes());
        for (int i = 0; i < 3; i++) {
            assertSame(originals.get(i), f.ops.vertexInputs.get(i), "Vertex staging must never copy input bytes");
            assertEquals(Layer.values()[i], f.page.completion().piece(i).layer());
            assertEquals(5, f.page.completion().piece(i).memberRanges().size());
            assertArrayEquals(new int[]{0,1,2,2,3,0,4,5,6,6,7,4,8,9,10,10,11,8,12,13,14,14,15,12,16,17,18,18,19,16}, f.ops.indexInputs.get(i));
        }
        assertEquals(700, f.ops.vao); assertEquals(701, f.ops.array); assertEquals(702, f.ops.ebos.get(700)); assertTrue(f.ops.invalidations >= 6);
        assertEquals(0, f.cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD));
        var receipt = f.page.completion();
        f.page.close(); assertThrows(IllegalStateException.class, receipt::requirePhysicalComplete);
        assertTrue(f.page.closeComplete()); assertEquals(0, f.gpu.evidence().bytes()); assertEquals(0, f.cpu.evidence().bytes());
        f.page.close();
    }
    @Test void eachLayerAndExactMemberMaskDrawsOnlySelectedCanonicalRangesAndRestoresRawBindings() {
        var f = fixture(3); start(f); f.page.step(3, Long.MAX_VALUE);
        for (var layer : Layer.values()) {
            f.ops.draws.clear(); var selected = f.page.drawLayer(layer, (1L << 1) | (1L << 2));
            assertEquals(List.of("12:24"), f.ops.draws); assertEquals(2, selected.quads());
            f.ops.draws.clear(); f.page.drawLayer(layer, 1L | (1L << 2) | (1L << 4));
            assertEquals(List.of("6:0", "6:48", "6:96"), f.ops.draws);
            f.ops.draws.clear(); assertEquals(5, f.page.drawLayer(layer, -1L).quads()); assertEquals(List.of("30:0"), f.ops.draws);
            f.ops.draws.clear(); f.page.drawLayer(layer, 0); f.page.drawLayer(layer, 1L << 63); assertTrue(f.ops.draws.isEmpty());
            assertEquals(700, f.ops.vao); assertEquals(701, f.ops.array); assertEquals(702, f.ops.ebos.get(700));
        }
        f.page.close();
    }
    @Test void partialGpuReservationRefusalRetainsAccessibleOwnerAndNeverCallsGl() {
        var f = fixture(3); var held = new ArrayList<NativeVisualBudget.Reservation>();
        for (int i = 0; i < 1535; i++) held.add(f.gpu.reserve(32, 24));
        assertThrows(IllegalStateException.class, () -> start(f));
        assertEquals(1536, f.gpu.evidence().pieces()); assertEquals(0, f.ops.glCalls); assertRetained(f);
        f.page.close(); assertEquals(1535, f.gpu.evidence().pieces()); assertTrue(f.source.closed);
        held.forEach(NativeVisualBudget.Reservation::releaseAfterPhysicalClose); assertEquals(0, f.cpu.evidence().bytes());
    }
    @Test void metadataAndWorkerStagingRefusalsHappenBeforeGlAndRemainCleanupOwned() {
        for (var pool : List.of(NativeDistanceCpuBudget.Pool.OWNER_METADATA, NativeDistanceCpuBudget.Pool.WORKER_UPLOAD)) {
            var f = fixture(3);
            long used = f.cpu.evidence().pools().get(pool);
            var pressure = f.cpu.reserve(pool, pool.limit() - used);
            assertThrows(IllegalStateException.class, () -> start(f)); assertEquals(0, f.ops.glCalls); assertRetained(f);
            assertEquals(pool == NativeDistanceCpuBudget.Pool.OWNER_METADATA ? 0 : 3, f.gpu.evidence().pieces());
            f.page.close(); assertEquals(pressure.bytes(), f.cpu.evidence().bytes()); pressure.releaseAfterPhysicalDrain();
        }
    }
    @Test void failedNativeIndexAllocationStillLeavesAnAccessibleFullyReservedCleanupOwner() {
        var f = fixture(3);
        f.ops.onIndexAllocate = () -> { throw new IllegalStateException("index allocation failed"); };
        assertThrows(IllegalStateException.class, () -> start(f)); assertEquals(0, f.ops.glCalls);
        assertRetained(f); assertEquals(3, f.gpu.evidence().pieces());
        assertEquals(3 * 1024 + 120, f.cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD));
        f.page.close(); assertTrue(f.page.closeComplete()); assertEquals(0, f.cpu.evidence().bytes());
    }
    @Test void everyUploadFailureRetainsCpuAndScratchUntilGpuCleanupAndNeverMintsCompletion() {
        for (var phase : List.of("upload.entry", "upload.capture", "upload.allocate.vao", "upload.allocate.vbo", "upload.allocate.ibo",
                "upload.bind.vertex", "upload.vertex.storage", "upload.vertex.size", "upload.vertex.bytes", "upload.bind.index",
                "upload.index.storage", "upload.index.size", "upload.index.bytes", "upload.layout", "upload.restore.before", "upload.restore.after")) {
            var f = fixture(3); start(f); f.ops.failPhase = phase;
            assertEquals(phase, assertThrows(IllegalStateException.class, () -> f.page.step(3, Long.MAX_VALUE)).getMessage());
            assertRetained(f); assertEquals(3, f.gpu.evidence().pieces()); assertEquals(0, f.ops.freed);
            assertThrows(IllegalStateException.class, () -> f.page.step(3, Long.MAX_VALUE));
            f.page.close(); assertTrue(f.page.closeComplete()); assertTrue(f.source.closed);
            assertEquals(0, f.gpu.evidence().bytes()); assertEquals(0, f.cpu.evidence().bytes());
        }
    }
    @Test void secondSliceFailureDoesNotDiscardPreviouslyUploadedPiecesOrRetainedCpu() {
        var f = fixture(3); start(f); f.page.step(1, Long.MAX_VALUE);
        f.ops.failPhase = "upload.vertex.bytes";
        assertThrows(IllegalStateException.class, () -> f.page.step(1, Long.MAX_VALUE)); assertRetained(f);
        assertEquals(3, f.gpu.evidence().pieces()); assertEquals(0, f.source.closeCalls);
        f.page.close(); assertTrue(f.page.closeComplete()); assertEquals(0, f.gpu.evidence().pieces());
    }
    @Test void repeatedFailedRestoreRetainsCpuAndIndexStorageUntilCleanRetry() {
        var f = fixture(1); start(f); f.ops.failPhase = "upload.restore.after"; f.ops.failures = 2;
        assertThrows(IllegalStateException.class, () -> f.page.step(1, Long.MAX_VALUE));
        assertThrows(IllegalStateException.class, f.page::close); assertRetained(f);
        assertEquals(0, f.source.closeCalls); assertEquals(0, f.ops.freed); assertEquals(1, f.gpu.evidence().pieces());
        f.page.close(); assertTrue(f.page.closeComplete()); assertEquals(3, f.ops.restores); assertEquals(3, f.ops.deletes);
    }
    @Test void survivingDeletionRetainsExactNamesForRetryAndCpuCharges() {
        var f = fixture(1); start(f); f.ops.failPhase = "upload.layout";
        assertThrows(IllegalStateException.class, () -> f.page.step(1, Long.MAX_VALUE));
        f.ops.surviveDelete = true;
        assertThrows(IllegalStateException.class, f.page::close); assertRetained(f);
        assertEquals(1, f.ops.live.size()); assertEquals(0, f.ops.freed);
        f.page.close(); assertEquals(4, f.ops.deletes); assertTrue(f.page.closeComplete());
    }
    @Test void postDeleteVerificationAndUnqueryableDeletionRetainCpuAndChargedTombstonesNeverDeleteReusedNames() {
        for (boolean query : new boolean[]{false, true}) {
            var f = fixture(1); start(f); f.ops.failPhase = "upload.layout";
            assertThrows(IllegalStateException.class, () -> f.page.step(1, Long.MAX_VALUE));
            if (query) f.ops.failQuery = true; else { f.ops.failPhase = "close.verify.1"; f.ops.failures = 1; }
            assertThrows(IllegalStateException.class, f.page::close); assertRetained(f); assertEquals(3, f.ops.deletes);
            f.ops.live.add(1); f.ops.live.add(2); f.ops.live.add(3); // Simulate unrelated objects reusing deleted names.
            assertThrows(IllegalStateException.class, f.page::close); assertEquals(3, f.ops.deletes);
            assertEquals(Set.of(1, 2, 3), f.ops.live); assertEquals(1, f.gpu.evidence().pieces());
            assertEquals(0, f.source.closeCalls); assertEquals(0, f.ops.freed);
        }
    }
    @Test void deadNamesAreClearedBeforeDeleteDiagnosticFailureAndNeverRetried() {
        var f = fixture(1); start(f); f.ops.failPhase = "upload.layout";
        assertThrows(IllegalStateException.class, () -> f.page.step(1, Long.MAX_VALUE));
        f.ops.failPhase = "close.delete.0"; f.ops.failures = 1;
        assertThrows(IllegalStateException.class, f.page::close); assertRetained(f);
        f.ops.live.add(1); f.page.close(); assertEquals(3, f.ops.deletes); assertTrue(f.ops.live.contains(1));
        assertTrue(f.page.closeComplete());
    }
    @Test void reentrantCloseAndStepCannotFreeBorrowedBytesAndStartCannotReenterCleanup() {
        var f = fixture(1);
        f.ops.onIndexAllocate = () -> assertThrows(IllegalStateException.class, f.page::close);
        start(f);
        f.ops.onUpload = () -> {
            assertTrue(f.source.borrowed); assertThrows(IllegalStateException.class, f.page::close);
            assertThrows(IllegalStateException.class, () -> f.page.step(1, 1));
            assertThrows(IllegalStateException.class, f.source::close); assertEquals(0, f.source.closeCalls);
        };
        assertTrue(f.page.step(1, Long.MAX_VALUE).complete()); f.page.close();
    }
    @Test void failedPreFreeCheckOrCpuCloseCannotProduceFalseCompletionAndCanBeCleanedUp() {
        for (boolean cpuFailure : new boolean[]{false, true}) {
            var f = fixture(1); start(f);
            if (cpuFailure) f.source.failClose = true; else f.ops.failBeforeFree = true;
            assertThrows(IllegalStateException.class, () -> f.page.step(1, Long.MAX_VALUE)); assertRetained(f);
            assertEquals(1, f.gpu.evidence().pieces());
            f.page.close(); assertTrue(f.page.closeComplete()); assertEquals(0, f.cpu.evidence().bytes());
        }
    }
    @Test void unknownIndexFreeOutcomeKeepsCpuAndWorkerChargeWithoutRetryingPotentiallyReusedAddress() {
        for (boolean afterFree : new boolean[]{false, true}) {
            var f = fixture(1); start(f);
            if (afterFree) f.ops.failAfterFree = true; else f.ops.failFree = true;
            assertThrows(IllegalStateException.class, () -> f.page.step(1, Long.MAX_VALUE)); assertRetained(f);
            assertTrue(f.page.indexCleanupTombstone()); assertEquals(1, f.ops.freeAttempts);
            assertEquals(afterFree ? 1 : 0, f.ops.freed);
            assertEquals(1024 + 120, f.cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD));
            assertThrows(IllegalStateException.class, f.page::close); assertRetained(f);
            assertThrows(IllegalStateException.class, f.page::close);
            assertEquals(1, f.ops.freeAttempts, "An uncertain native address must never be freed twice");
            assertEquals(0, f.gpu.evidence().pieces()); assertEquals(0, f.source.closeCalls);
            assertTrue(f.page.retainedCpuBytes() > 1024 + 120);
        }
    }
    @Test void incompleteCpuCloseAndIncompleteMemberMaskAreRejected() {
        var f = fixture(1); start(f); f.source.incompleteClose = true;
        assertThrows(IllegalStateException.class, () -> f.page.step(1, Long.MAX_VALUE)); assertRetained(f);
        assertThrows(IllegalStateException.class, f.page::close); assertRetained(f);
        f.source.incompleteClose = false; f.page.close(); assertTrue(f.page.closeComplete());
        var incomplete = fixture(1); incomplete.source.completedMask = 15;
        assertThrows(IllegalStateException.class, () -> start(incomplete)); assertEquals(0, incomplete.ops.glCalls);
        incomplete.page.close();
    }
    @Test void emptyCompiledPageRequiresCpuDrainBeforePhysicalCompletion() {
        var f = fixture(0); start(f); assertThrows(IllegalStateException.class, f.page::completion);
        assertTrue(f.page.step(1, Long.MAX_VALUE).complete()); assertEquals(0, f.ops.glCalls);
        assertEquals(0, f.page.completion().pieceCount()); assertTrue(f.source.closed); f.page.close();
    }
    @Test void repeatedStartTransferAndDifferentThreadCannotMutateAClaimedPage() throws Exception {
        var f = fixture(1); start(f);
        assertThrows(IllegalStateException.class, () -> start(f)); assertThrows(IllegalStateException.class, f.source::claim);
        var error = new AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { f.page.close(); } catch (Throwable caught) { error.set(caught); } });
        thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, error.get()); assertRetained(f);
        f.page.close();
    }
    @Test void drawFailureAndDrawRestoreFailureRevokeCompletionUntilPhysicalCleanup() {
        for (var phase : List.of("draw.elements", "page.draw.restore.after")) {
            var f = fixture(1); start(f); f.page.step(1, Long.MAX_VALUE); var receipt = f.page.completion();
            f.ops.failPhase = phase;
            assertThrows(IllegalStateException.class, () -> f.page.drawLayer(Layer.SOLID, 1L));
            assertFalse(f.page.physicallyComplete()); assertThrows(IllegalStateException.class, receipt::requirePhysicalComplete);
            assertEquals(1, f.page.issuedDrawCalls(), "Draws issued before a diagnostic fault remain counted");
            assertEquals(1, f.gpu.evidence().pieces());
            f.page.close(); assertTrue(f.page.closeComplete()); assertEquals(0, f.cpu.evidence().bytes());
        }
    }
    @Test void unchangedCapsRemainExact() {
        assertEquals(1L << 30, NativeVisualBudget.MAX_BYTES); assertEquals(1536, NativeVisualBudget.MAX_PIECES);
        assertEquals(384L << 20, NativeDistanceCpuBudget.MAX_BYTES);
        assertEquals(16L << 20, NativeDistanceCpuBudget.Pool.WORKER_UPLOAD.limit());
    }
}
