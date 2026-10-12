package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.scene.SceneReadyCube;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Independent rerun of the frozen moving-authority fault scenarios against explicit LONG_4096. Fake GL only. */
@Timeout(30)
class DhLongPreparedAuthorityIndependentReviewTest {
    private static final String SOURCE = "moving-authority-review-flat-y1000";
    private static final WorldIdentity WORLD = new WorldIdentity(71, "review", "1", SOURCE, "palette");
    private static final long EPOCH = 29, RESOURCES = 5;
    private static final long AX = 0, AZ = 0, BX = 256, BZ = -256;

    @Test void sameExactXyzAtDifferentCenterCannotBorrowCurrentGeometry() {
        try (var h = new Harness()) {
            h.commit(0, ready(1, 0), AX, AZ); var old = h.snapshot(); var token = h.view.retainPresentation();
            int samples = h.sampler.samples.get(), requests = h.submissions.get(), compiled = h.driver.compiles.get();
            var ticket = h.prepare(1, ready(2, 1), BX, BZ);
            assertFalse(h.flag("pendingSameGeometry"));
            assertTrue(h.sampler.samples.get() > samples); assertTrue(h.driver.compiles.get() > compiled);
            assertEquals(requests + 1, h.submissions.get());
            assertTrue(h.view.coverageReady(0, AX, AZ)); assertFalse(h.view.coverageReady(0, BX, BZ));
            assertFalse(h.view.coverageReady(1, BX, BZ)); assertEquals(old, h.snapshot());
            assertTrue(h.view.isPresentationCurrent(token));
            assertTrue(h.view.commitPrepared(ticket));
            assertTrue(h.view.coverageReady(1, BX, BZ)); assertFalse(h.view.coverageReady(1, AX, AZ));
            assertFalse(h.view.isPresentationCurrent(token)); assertFalse(h.view.commitPrepared(ticket));
            assertEquals(BX, h.number("publishedCenterX")); assertEquals(BZ, h.number("publishedCenterZ"));
        }
    }

    @Test void returningToAFromCommittedBNeedsFreshGeometryAndCannotResurrectOldTickets() {
        try (var h = new Harness()) {
            var first = h.prepare(0, ready(1, 0), AX, AZ); assertTrue(h.view.commitPrepared(first));
            var oldToken = h.view.retainPresentation(); h.commit(1, ready(2, 1), BX, BZ);
            int requests = h.submissions.get(), samples = h.sampler.samples.get();
            var returned = h.prepare(2, ready(3, 2), AX, AZ);
            assertFalse(h.flag("pendingSameGeometry"));
            assertEquals(requests + 1, h.submissions.get()); assertTrue(h.sampler.samples.get() > samples);
            assertFalse(h.view.commitPrepared(first)); assertFalse(h.view.isPresentationCurrent(oldToken));
            assertTrue(h.view.coverageReady(1, BX, BZ));
            assertTrue(h.view.commitPrepared(returned)); assertTrue(h.view.coverageReady(2, AX, AZ));
            assertFalse(h.view.coverageReady(0, AX, AZ)); assertFalse(h.view.coverageReady(1, BX, BZ));
            assertFalse(h.view.commitPrepared(first)); assertFalse(h.view.isPresentationCurrent(oldToken));
            assertEquals(2, h.number("recenterCommits"));
        }
    }

    @Test void pendingCenterBMayBeCancelledForFreshAuthorityAtAWithoutWaitingForLateCpu() {
        try (var h = new Harness()) {
            h.commit(0, ready(1, 0), AX, AZ); var original = h.snapshot(); var token = h.view.retainPresentation();
            var gate = new Gate(); h.driver.blockCompile.set(gate);
            var obsoleteB = h.view.prepareCoverage(1, ready(2, 1), BX, BZ); await(gate.entered);
            try {
                var counts = h.counts(); var returnA = h.view.prepareCoverage(2, ready(3, 2), AX, AZ);
                h.view.pump(); assertTrue(h.view.isPrepared(returnA)); assertTrue(h.flag("pendingSameGeometry"));
                assertEquals(1, h.number("summaryRunning")); assertEquals(0, h.number("summaryQueued"));
                assertEquals(counts, h.counts()); assertEquals(original, h.snapshot());
                assertTrue(h.view.isPresentationCurrent(token)); assertFalse(h.view.commitPrepared(obsoleteB));
                assertTrue(h.view.commitPrepared(returnA)); assertFalse(h.view.isPresentationCurrent(token));
                gate.release.countDown(); until(() -> h.number("summaryRunning") == 0, h.view::pump);
                assertEquals(counts, h.counts()); assertFalse(h.view.commitPrepared(obsoleteB));
                assertTrue(h.view.coverageReady(2, AX, AZ)); assertFalse(h.view.coverageReady(1, BX, BZ));
                assertEquals(original, h.snapshot());
            } finally { gate.release.countDown(); }
        }
    }

    @Test void duplicatePendingGenerationRequiresBothExactCenterAndFullAuthority() {
        try (var h = new Harness()) {
            h.commit(0, ready(1, 0), AX, AZ);
            var ticket = h.prepare(1, ready(2, 1), BX, BZ);
            assertSame(ticket, h.view.prepareCoverage(1, ready(2, 1), BX, BZ));
            assertThrows(IllegalArgumentException.class, () -> h.view.prepareCoverage(1, ready(2, 1), AX, AZ));
            assertThrows(IllegalArgumentException.class, () -> h.view.prepareCoverage(1, ready(3, 1), BX, BZ));
            assertThrows(IllegalArgumentException.class, () -> h.view.prepareCoverage(1, ready(2, 2), BX, BZ));
            assertTrue(h.view.isCurrent(ticket)); assertTrue(h.view.isPrepared(ticket));
            assertTrue(h.view.commitPrepared(ticket));
        }
    }

    @Test void invalidResourceEpochOrWorldCannotReplacePreparedCenterTicket() {
        try (var h = new Harness()) {
            h.commit(0, ready(1, 0), AX, AZ); var pending = h.prepare(1, ready(2, 1), BX, BZ);
            var p = new CubePos(0, 62, 0);
            for (var wrong : List.of(new SceneReadyCube(p, WORLD, EPOCH + 1, 3, 2, RESOURCES),
                    new SceneReadyCube(p, WORLD, EPOCH, 3, 2, RESOURCES + 1),
                    new SceneReadyCube(p, new WorldIdentity(72, "review", "1", SOURCE, "palette"), EPOCH, 3, 2, RESOURCES))) {
                assertThrows(IllegalArgumentException.class, () -> h.view.prepareCoverage(2, List.of(wrong), AX, AZ));
                assertTrue(h.view.isPrepared(pending)); assertTrue(h.view.coverageReady(0, AX, AZ));
            }
            assertTrue(h.view.commitPrepared(pending)); assertTrue(h.view.coverageReady(1, BX, BZ));
        }
    }

    @Test void sourceAbaWhileCenterWorkerRunsPermanentlyRejectsItsLatePayload() {
        try (var h = new Harness()) {
            h.commit(0, ready(1, 0), AX, AZ); var oldToken = h.view.retainPresentation();
            var gate = new Gate(); h.driver.blockCompile.set(gate);
            var ticket = h.view.prepareCoverage(1, ready(2, 1), BX, BZ); await(gate.entered);
            try {
                int uploaded = h.driver.meshes.size();
                h.sampler.identity.set("replacement-source");
                assertThrows(IllegalStateException.class, () -> h.view.isCurrent(ticket));
                h.sampler.identity.set(SOURCE); gate.release.countDown();
                until(() -> h.number("summaryRunning") == 0, h.view::pump);
                assertEquals(uploaded, h.driver.meshes.size()); assertTrue(h.flag("invalidated"));
                assertFalse(h.view.commitPrepared(ticket)); assertFalse(h.view.coverageReady(0, AX, AZ));
                assertFalse(h.view.coverageReady(1, BX, BZ)); assertFalse(h.view.isPresentationCurrent(oldToken));
            } finally { gate.release.countDown(); }
        }
    }

    @Test void explicitResourceFenceWhileCenterWorkerRunsCannotLeakIntoReplacementEpoch() {
        try (var h = new Harness(); var replacement = new Harness(EPOCH + 1, RESOURCES + 1)) {
            h.commit(0, ready(1, 0), AX, AZ); var gate = new Gate(); h.driver.blockCompile.set(gate);
            var ticket = h.view.prepareCoverage(1, ready(2, 1), BX, BZ); await(gate.entered);
            try {
                int uploaded = h.driver.meshes.size(); h.view.requestInvalidation();
                assertFalse(replacement.view.isCurrent(ticket)); assertFalse(replacement.view.commitPrepared(ticket));
                gate.release.countDown(); until(() -> h.number("summaryRunning") == 0, h.view::pump);
                assertEquals(uploaded, h.driver.meshes.size()); assertFalse(h.view.isPrepared(ticket));
                assertFalse(h.view.hasPublishedGeometry()); assertFalse(h.view.commitPrepared(ticket));
                assertEquals(0, replacement.driver.meshes.size()); assertFalse(replacement.view.hasPublishedGeometry());
            } finally { gate.release.countDown(); }
        }
    }

    @Test void ordinaryCameraDrawsCannotAutonomouslyRequestOrCommitAnotherCenter() {
        try (var h = new Harness()) {
            h.commit(0, ready(1, 0), AX, AZ); var counts = h.counts();
            for (double camera : new double[]{-1000000, -65, -32, 31, 64, 1000000}) {
                h.view.draw(new Matrix4f(), camera, 1000, -camera, RESOURCES); h.view.pump();
                assertTrue(h.view.coverageReady(0, AX, AZ)); assertEquals(counts, h.counts());
                assertEquals(1, h.number("commits")); assertEquals(0, h.number("recenterCommits"));
            }
        }
    }

    @Test void farPresentationKeepsOldCenterThroughPrivatePreparationAndDiesAtCommit() {
        try (var h = new Harness()) {
            h.commit(0, ready(1, 0), AX, AZ); var old = h.snapshot(); var presentation = h.view.retainPresentation();
            var ticket = h.prepare(1, ready(2, 1), BX, BZ);
            assertTrue(h.view.drawPresented(presentation, new Matrix4f(), BX, 1000, BZ, RESOURCES));
            assertEquals(old, h.driver.drawn); assertTrue(h.view.coverageReady(0, AX, AZ));
            assertFalse(h.view.drawPresented(presentation, new Matrix4f(), BX, 1000, BZ, RESOURCES + 1));
            assertTrue(h.view.isPresentationCurrent(presentation));
            assertTrue(h.view.commitPrepared(ticket));
            int draws = h.driver.drawCount;
            assertFalse(h.view.drawPresented(presentation, new Matrix4f(), AX, 1000, AZ, RESOURCES));
            assertEquals(draws, h.driver.drawCount); assertTrue(h.view.coverageReady(1, BX, BZ));
        }
    }

    @Test void retirementFailureAfterCenterCacheCommitFencesBothPairsAndRetainsOwnersForRetry() {
        try (var h = new Harness()) {
            h.commit(0, ready(1, 0), AX, AZ); var old = h.snapshot(); var token = h.view.retainPresentation();
            var ticket = h.prepare(1, ready(2, 1), BX, BZ);
            old.values().forEach(mesh -> ((Mesh) mesh).failClose = true);
            assertThrows(ReviewFailure.class, () -> h.view.commitPrepared(ticket));
            assertTrue(h.flag("invalidated")); assertFalse(h.view.coverageReady(0, AX, AZ));
            assertFalse(h.view.coverageReady(1, BX, BZ)); assertFalse(h.view.isPresentationCurrent(token));
            assertFalse(h.view.commitPrepared(ticket)); assertEquals(0, h.number("coverageGeneration"));
            assertEquals(AX, h.number("publishedCenterX")); assertEquals(AZ, h.number("publishedCenterZ"));
            long retained = h.driver.meshes.stream().filter(mesh -> !mesh.deleted).count();
            assertTrue(retained > 0); assertEquals(retained, h.number("residentPageTileOwners"));
            assertThrows(ReviewFailure.class, h.view::close); assertFalse(h.flag("ownedGlObjectsDeleted"));
            assertEquals(h.driver.meshes.stream().filter(mesh -> !mesh.deleted).count(), h.number("residentPageTileOwners"));
            h.driver.meshes.forEach(mesh -> mesh.failClose = false); h.view.close();
            assertEquals(0, h.number("residentPageTileOwners")); assertTrue(h.flag("ownedGlObjectsDeleted"));
        }
    }

    @Test void layoutSpecificTicketsAndPresentationsCannotCrossViewsEvenWithIdenticalAuthorityFields() {
        try (var distant = new Harness(DhLodLayout.LONG_4096); var fixed = new Harness(DhLodLayout.FIXED_512)) {
            var longTicket = distant.prepare(0, ready(1, 0), 0, 0);
            var fixedTicket = fixed.prepare(0, ready(1, 0), 0, 0);
            assertFalse(distant.view.isCurrent(fixedTicket)); assertFalse(fixed.view.isCurrent(longTicket));
            assertFalse(distant.view.commitPrepared(fixedTicket)); assertFalse(fixed.view.commitPrepared(longTicket));
            assertTrue(distant.view.commitPrepared(longTicket)); assertTrue(fixed.view.commitPrepared(fixedTicket));
            assertTrue(distant.view.coverageReady(0, 0, 0, DhLodLayout.LONG_4096));
            assertFalse(distant.view.coverageReady(0, 0, 0, DhLodLayout.FIXED_512));
            assertTrue(fixed.view.coverageReady(0, 0, 0, DhLodLayout.FIXED_512));
            assertFalse(fixed.view.coverageReady(0, 0, 0, DhLodLayout.LONG_4096));
            var longPresentation = distant.view.retainPresentation(); var fixedPresentation = fixed.view.retainPresentation();
            assertFalse(distant.view.drawPresented(fixedPresentation, new Matrix4f(), 0, 1000, 0, RESOURCES));
            assertFalse(fixed.view.drawPresented(longPresentation, new Matrix4f(), 0, 1000, 0, RESOURCES));
            assertTrue(distant.view.isPresentationCurrent(longPresentation)); assertTrue(fixed.view.isPresentationCurrent(fixedPresentation));
            assertEquals(4096, distant.number("radiusBlocks")); assertEquals(512, fixed.number("radiusBlocks"));
        }
    }

    private static List<SceneReadyCube> ready(long lease, long revision) {
        return List.of(new SceneReadyCube(new CubePos(0, 62, 0), WORLD, EPOCH, lease, revision, RESOURCES));
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "Worker gate timeout"); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    private static void until(BooleanSupplier condition, Runnable step) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, "Preparation timeout"); step.run();
            try { Thread.sleep(1); } catch (InterruptedException e) { throw new AssertionError(e); }
        }
    }
    private record Counts(int samples, int colors, int compiles, int submissions, int uploads) {}
    private static final class Gate { final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); }
    private static final class ReviewFailure extends RuntimeException { ReviewFailure(String message) { super(message); } }
    private static final class Sampler implements SceneSurfaceSampler {
        final AtomicInteger samples = new AtomicInteger(), colors = new AtomicInteger();
        final AtomicReference<String> identity = new AtomicReference<>(SOURCE);
        public long surfaceY(long x, long z) { samples.incrementAndGet(); return 1000; }
        public int argb(long x, long z, long y) { colors.incrementAndGet(); return 0xffaabbcc; }
        public String identity() { return identity.get(); }
        public boolean supportsConcurrentReads() { return true; }
        public boolean supportsFootprintCoverage() { return true; }
        public boolean knownFootprint(long x,long z,long ex,long ez) { return true; }
    }
    private static final class Harness implements AutoCloseable {
        final Sampler sampler = new Sampler(); final Driver driver = new Driver();
        final AtomicInteger submissions = new AtomicInteger();
        final ExecutorService workers = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("moving-authority-review").factory());
        final DhPreparedLodView view;
        Harness() { this(EPOCH, RESOURCES, DhLodLayout.LONG_4096); }
        Harness(DhLodLayout layout) { this(EPOCH, RESOURCES, layout); }
        Harness(long epoch, long resources) { this(epoch, resources, DhLodLayout.LONG_4096); }
        Harness(long epoch, long resources, DhLodLayout layout) {
            view = new DhPreparedLodView(sampler, WORLD, epoch, resources, AX, AZ, layout, driver,
                    task -> { submissions.incrementAndGet(); workers.execute(task); });
            view.initialize();
        }
        DhPreparedLodView.CoverageTicket prepare(long generation, List<SceneReadyCube> ready, long x, long z) {
            var ticket = view.prepareCoverage(generation, ready, x, z); until(() -> view.isPrepared(ticket), () -> { view.pump(); caps(); }); caps(); return ticket;
        }
        void caps() {
            assertTrue(number("residentPageTileOwners") <= 128);
            assertTrue(number("residentBufferBytes") <= 8L * 1024 * 1024);
            assertTrue(number("summaryCachedBytes") <= 20L * 1024 * 1024);
            assertTrue(number("summaryRunning") <= 1); assertTrue(number("summaryQueued") <= 1);
        }
        void commit(long generation, List<SceneReadyCube> ready, long x, long z) {
            assertTrue(view.commitPrepared(prepare(generation, ready, x, z)));
        }
        Map<DhGeographicLodData.TileKey, DhGeographicLodView.Mesh> snapshot() {
            view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES); return driver.drawn;
        }
        Counts counts() { return new Counts(sampler.samples.get(), sampler.colors.get(), driver.compiles.get(), submissions.get(), driver.meshes.size()); }
        long number(String property) { return view.diagnostics().get(property).getAsLong(); }
        boolean flag(String property) { return view.diagnostics().get(property).getAsBoolean(); }
        public void close() {
            sampler.identity.set(SOURCE); driver.meshes.forEach(mesh -> mesh.failClose = false);
            try { view.close(); assertTrue(driver.meshes.stream().allMatch(Mesh::deleted)); }
            finally {
                workers.shutdownNow();
                try { assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
        }
    }
    private static final class Mesh implements DhGeographicLodView.Mesh {
        final int quads; boolean deleted, failClose;
        Mesh(int quads) { this.quads = quads; }
        public int quads() { return quads; } public int bytes() { return quads * 64; }
        public void close() { if (failClose) throw new ReviewFailure("Retained previous-center owner"); deleted = true; }
        public boolean deleted() { return deleted; }
    }
    private static final class Driver implements DhGeographicLodView.Driver {
        final Thread owner = Thread.currentThread(); final List<Mesh> meshes = new ArrayList<>();
        final AtomicInteger compiles = new AtomicInteger(); final AtomicReference<Gate> blockCompile = new AtomicReference<>();
        Map<DhGeographicLodData.TileKey, DhGeographicLodView.Mesh> drawn = Map.of(); boolean deleted; int drawCount;
        public void checkRenderThread() { assertSame(owner, Thread.currentThread()); }
        public String renderer() { return "Independent fake moving-authority review driver"; }
        public void initialize() {}
        public DhGeographicLodView.State captureState() { checkRenderThread(); return () -> {}; }
        public DhGeographicLodView.CpuCompiler cpuCompiler() {
            return (faces, cancellation) -> {
                assertNotSame(owner, Thread.currentThread()); compiles.incrementAndGet();
                var gate = blockCompile.getAndSet(null); if (gate != null) { gate.entered.countDown(); await(gate.release); }
                // Intentionally ignores callback cancellation: outer jobs must reject this late result.
                return new DhPreparedVertices(new byte[faces.size() * 64]);
            };
        }
        public DhGeographicLodView.Mesh upload(DhGeographicLodData.TileKey key, List<DhTerrainGeometry.PageFace> faces) {
            throw new AssertionError("Only immutable prepared bytes may upload");
        }
        public DhGeographicLodView.Mesh uploadPrepared(DhGeographicLodData.TileKey key,
                List<DhTerrainGeometry.PageFace> faces, DhPreparedVertices prepared) {
            checkRenderThread(); var mesh = new Mesh(faces.size()); meshes.add(mesh); return mesh;
        }
        public DhGeographicLodView.DrawStats draw(Map<DhGeographicLodData.TileKey, DhGeographicLodView.Mesh> resources,
                Matrix4f matrix, double x, double y, double z) {
            checkRenderThread(); resources.values().forEach(mesh -> assertFalse(mesh.deleted()));
            drawn = Map.copyOf(resources); drawCount++; return new DhGeographicLodView.DrawStats(0, 0, 0);
        }
        public void close() { checkRenderThread(); deleted = true; } public boolean deleted() { return deleted; }
    }
}
