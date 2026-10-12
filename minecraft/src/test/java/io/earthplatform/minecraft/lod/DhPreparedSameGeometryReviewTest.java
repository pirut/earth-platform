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

/** Independent adversarial review of geometry reuse; fake resources do not validate actual OpenGL. */
@Timeout(30)
class DhPreparedSameGeometryReviewTest {
    private static final String SOURCE = "same-geometry-independent-flat-y1000";
    private static final WorldIdentity WORLD = new WorldIdentity(31, "review", "1", SOURCE, "palette");
    private static final long EPOCH = 11, RESOURCES = 7;

    @Test void freshAuthorityAndCanonicalPositionOrderReuseWithoutPreparingOrPublishingEarly() {
        try (var h = new Harness()) {
            var a = ready(0, 62, 0, 1, 0); var b = ready(1, 62, 0, 2, 0);
            h.commit(0, List.of(b, a));
            var old = h.snapshot(); var presentation = h.view.retainPresentation(); var counts = h.counts();
            var freshA = ready(0, 62, 0, Long.MAX_VALUE, 3);
            var freshB = ready(1, 62, 0, 99, Long.MAX_VALUE);
            var mutable = new ArrayList<>(List.of(freshB, freshA));
            var ticket = h.view.prepareCoverage(1, mutable);
            assertSame(ticket, h.view.prepareCoverage(1, List.of(freshA, freshB)));
            assertThrows(IllegalArgumentException.class, () -> h.view.prepareCoverage(1, List.of(a, b)));
            mutable.clear();
            assertFalse(h.view.isPrepared(ticket), "Reuse still needs its private owner-thread stage");
            assertFalse(h.view.commitPrepared(ticket), "Preparing authority cannot publish the far batch");
            assertTrue(h.view.coverageReady(0)); assertTrue(h.view.isPresentationCurrent(presentation));
            h.view.pump();
            assertTrue(h.view.isPrepared(ticket)); assertEquals(counts, h.counts());
            assertEquals(old, h.snapshot()); assertTrue(h.view.isPresentationCurrent(presentation));
            assertTrue(h.view.commitPrepared(ticket)); assertTrue(h.view.coverageReady(1));
            assertFalse(h.view.isCurrent(ticket)); assertFalse(h.view.commitPrepared(ticket));
            assertFalse(h.view.isPresentationCurrent(presentation)); assertEquals(old, h.snapshot());
            assertEquals(counts, h.counts());
            assertEquals(old.size(), h.number("residentPageTileOwners"));
            assertEquals(0, h.number("summaryRunning")); assertEquals(0, h.number("summaryQueued"));
        }
    }

    @Test void changesToAnyXyzComponentAreNotEquivalentEvenWithSameCardinality() {
        for (var position : List.of(new CubePos(1, 62, 0), new CubePos(0, 61, 0), new CubePos(0, 62, 1))) {
            try (var h = new Harness()) {
                h.commit(0, List.of(ready(0, 62, 0, 1, 0)));
                int samples = h.sampler.samples.get(), compiles = h.driver.compiles.get(), requests = h.submissions.get();
                h.prepare(1, List.of(new SceneReadyCube(position, WORLD, EPOCH, 2, 1, RESOURCES)));
                assertTrue(h.sampler.samples.get() > samples, "Every exact XYZ mask change requests source geometry");
                assertTrue(h.driver.compiles.get() > compiles); assertEquals(requests + 1, h.submissions.get());
            }
        }
    }

    @Test void noCommittedGeometryCannotUseACompletedButUnpublishedCandidate() {
        try (var h = new Harness()) {
            var unpublished = h.prepare(0, List.of(ready(0, 62, 0, 1, 0)));
            int samples = h.sampler.samples.get(), compiles = h.driver.compiles.get();
            h.view.cancelPrepared(unpublished);
            var replacement = h.prepare(1, List.of(ready(0, 62, 0, 2, 1)));
            assertTrue(h.sampler.samples.get() > samples); assertTrue(h.driver.compiles.get() > compiles);
            assertFalse(h.view.hasPublishedGeometry()); assertFalse(h.view.commitPrepared(unpublished));
            assertTrue(h.view.commitPrepared(replacement));
        }
    }

    @Test void invalidFullIdentityCannotBorrowTheCurrentGeometryOrDisplaceValidAuthority() {
        try (var h = new Harness(); var other = new Harness()) {
            var original = ready(0, 62, 0, 1, 0); h.commit(0, List.of(original));
            var pending = h.prepare(1, List.of(ready(0, 62, 0, 2, 1))); var counts = h.counts();
            var foreign = new WorldIdentity(32, "review", "1", SOURCE, "palette");
            for (var invalid : List.of(new SceneReadyCube(original.position(), foreign, EPOCH, 2, 1, RESOURCES),
                    new SceneReadyCube(original.position(), WORLD, EPOCH + 1, 2, 1, RESOURCES),
                    new SceneReadyCube(original.position(), WORLD, EPOCH, 2, 1, RESOURCES + 1),
                    ready(0, -689, 0, 2, 1))) {
                assertThrows(IllegalArgumentException.class, () -> h.view.prepareCoverage(2, List.of(invalid)));
                assertTrue(h.view.isCurrent(pending)); assertTrue(h.view.isPrepared(pending));
            }
            assertThrows(IllegalArgumentException.class, () -> h.view.prepareCoverage(2, List.of(original, original)));
            assertThrows(IllegalArgumentException.class, () -> h.view.prepareCoverage(2, java.util.Collections.nCopies(257, original)));
            assertFalse(other.view.commitPrepared(pending)); assertFalse(other.view.isPrepared(pending));
            assertTrue(h.view.commitPrepared(pending)); assertEquals(counts, h.counts());
        }
    }

    @Test void cancelledPhysicalWorkerDoesNotDelayReusingCommittedGeometryOrPublishItsLateResult() {
        try (var h = new Harness()) {
            h.commit(0, List.of()); var old = h.snapshot(); var presentation = h.view.retainPresentation();
            var gate = new Gate(); h.driver.blockCompile.set(gate);
            var abandoned = h.view.prepareCoverage(1, List.of(ready(0, 62, 0, 1, 0))); await(gate.entered);
            try {
                var counts = h.counts(); long cached = h.number("summaryCachedBytes");
                var replacement = h.view.prepareCoverage(2, List.of()); h.view.pump();
                assertTrue(h.view.isPrepared(replacement), "Reusing current geometry never awaits cancelled source settlement");
                assertEquals(1, h.number("summaryRunning")); assertEquals(0, h.number("summaryQueued"));
                assertTrue(h.view.isPresentationCurrent(presentation)); assertEquals(counts, h.counts());
                assertFalse(h.view.commitPrepared(abandoned)); assertTrue(h.view.commitPrepared(replacement));
                assertFalse(h.view.isPresentationCurrent(presentation)); assertEquals(old, h.snapshot());
                gate.release.countDown(); until(() -> h.number("summaryRunning") == 0, h.view::pump);
                assertEquals(counts, h.counts()); assertEquals(cached, h.number("summaryCachedBytes"));
                assertTrue(h.view.coverageReady(2)); assertEquals(old, h.snapshot());
            } finally { gate.release.countDown(); }
        }
    }

    @Test void replacementOfPrivateUploadsDeletesOnlyPrivateOwnersAndReusesCurrentOwners() {
        try (var h = new Harness()) {
            h.commit(0, List.of()); var old = h.snapshot(); var presentation = h.view.retainPresentation();
            int currentOwners = h.driver.meshes.size();
            var abandoned = h.view.prepareCoverage(1, List.of(ready(0, 62, 0, 1, 0)));
            until(() -> h.driver.meshes.size() > currentOwners, h.view::pump);
            var privateOwners = List.copyOf(h.driver.meshes.subList(currentOwners, h.driver.meshes.size()));
            var counts = h.counts(); var replacement = h.prepare(2, List.of());
            assertTrue(privateOwners.stream().allMatch(Mesh::deleted));
            assertTrue(old.values().stream().noneMatch(DhGeographicLodView.Mesh::deleted));
            assertTrue(h.view.isPresentationCurrent(presentation)); assertFalse(h.view.commitPrepared(abandoned));
            assertEquals(old.size(), h.number("residentPageTileOwners"));
            assertTrue(h.view.commitPrepared(replacement)); assertEquals(old, h.snapshot()); assertEquals(counts, h.counts());
        }
    }

    @Test void failedPrivateDeletionDuringSupersessionFencesAndRetainsOwnersForCloseRetry() {
        try (var h = new Harness()) {
            h.commit(0, List.of()); var old = h.snapshot(); int before = h.driver.meshes.size();
            h.view.prepareCoverage(1, List.of(ready(0, 62, 0, 1, 0)));
            until(() -> h.driver.meshes.size() > before, h.view::pump);
            var retained = h.driver.meshes.get(before); retained.failClose = true;
            assertThrows(ReviewFailure.class, () -> h.view.prepareCoverage(2, List.of()));
            assertTrue(h.flag("invalidated")); assertFalse(retained.deleted());
            assertTrue(old.values().stream().noneMatch(DhGeographicLodView.Mesh::deleted));
            assertThrows(ReviewFailure.class, h.view::close); assertFalse(h.flag("ownedGlObjectsDeleted"));
            retained.failClose = false; h.view.close(); assertTrue(h.flag("ownedGlObjectsDeleted"));
        }
    }

    @Test void cancellationFromAnotherThreadPreservesOldPairAndCannotReviveTheGeneration() throws Exception {
        try (var h = new Harness()) {
            var ready = List.of(ready(0, 62, 0, 1, 0)); h.commit(0, ready);
            var presentation = h.view.retainPresentation(); var old = h.snapshot(); var counts = h.counts();
            var abandoned = h.prepare(1, List.of(ready(0, 62, 0, 2, 1)));
            var cancel = new Thread(() -> h.view.cancelPrepared(abandoned)); cancel.start(); cancel.join(5000);
            assertFalse(cancel.isAlive()); assertFalse(h.view.isCurrent(abandoned)); assertFalse(h.view.isPrepared(abandoned));
            assertFalse(h.view.commitPrepared(abandoned)); h.view.pump();
            assertTrue(h.view.isPresentationCurrent(presentation)); assertTrue(h.view.coverageReady(0)); assertEquals(old, h.snapshot());
            assertThrows(IllegalArgumentException.class, () -> h.view.prepareCoverage(1, ready));
            h.commit(2, List.of(ready(0, 62, 0, 3, 2))); assertEquals(counts, h.counts());
            assertFalse(h.view.isPresentationCurrent(presentation));
        }
    }

    @Test void reentrantCancellationAtEitherCommitStateCallbackNeverReportsNewAuthority() {
        for (boolean restore : new boolean[]{false, true}) {
            try (var h = new Harness()) {
                h.commit(0, List.of()); var presentation = h.view.retainPresentation(); var old = h.snapshot();
                var ticket = h.prepare(1, List.of());
                if (restore) h.driver.onRestore.set(() -> h.view.cancelPrepared(ticket));
                else h.driver.onCapture.set(() -> h.view.cancelPrepared(ticket));
                assertThrows(IllegalStateException.class, () -> h.view.commitPrepared(ticket));
                assertEquals(0, h.number("coverageGeneration")); assertFalse(h.view.coverageReady(1));
                assertTrue(h.flag("invalidated")); assertFalse(h.view.isPresentationCurrent(presentation));
                assertTrue(old.values().stream().noneMatch(DhGeographicLodView.Mesh::deleted));
                assertFalse(h.view.commitPrepared(ticket));
            }
        }
    }

    @Test void offThreadInvalidationLinearizesAfterAnAlreadyStartedReuseCommit() throws Exception {
        try (var h = new Harness()) {
            h.commit(0, List.of()); var old = h.view.retainPresentation(); var ticket = h.prepare(1, List.of());
            var attempted = new CountDownLatch(1); var finished = new CountDownLatch(1);
            var invalidator = new Thread(() -> { attempted.countDown(); h.view.requestInvalidation(); finished.countDown(); });
            h.driver.onCapture.set(() -> {
                invalidator.start(); await(attempted);
                assertEquals(1, finished.getCount(), "Cross-thread invalidation must wait for the shared publication fence");
            });
            assertTrue(h.view.commitPrepared(ticket)); await(finished); invalidator.join(5000);
            assertFalse(invalidator.isAlive()); assertEquals(1, h.number("coverageGeneration"));
            assertTrue(h.flag("invalidated")); assertFalse(h.view.hasPublishedGeometry());
            assertFalse(h.view.isPresentationCurrent(old)); assertFalse(h.view.commitPrepared(ticket));
        }
    }

    @Test void identityFailureAfterReuseStagingPermanentlyFencesEvenAfterSourceAba() {
        for (boolean throwsInstead : new boolean[]{false, true}) {
            try (var h = new Harness()) {
                h.commit(0, List.of()); var presentation = h.view.retainPresentation(); var counts = h.counts();
                var ticket = h.prepare(1, List.of());
                if (throwsInstead) h.sampler.identityFailure.set(new ReviewFailure("identity"));
                else h.sampler.identity.set("changed");
                assertThrows(RuntimeException.class, () -> h.view.commitPrepared(ticket));
                h.sampler.identityFailure.set(null); h.sampler.identity.set(SOURCE);
                assertFalse(h.view.isPrepared(ticket)); assertFalse(h.view.commitPrepared(ticket));
                assertFalse(h.view.hasPublishedGeometry()); assertFalse(h.view.isPresentationCurrent(presentation));
                assertEquals(counts, h.counts());
            }
        }
    }

    @Test void fastStageCloseRestoreFailureRetainsRestorationObligationWithNoExtraOwner() {
        try (var h = new Harness()) {
            h.commit(0, List.of()); var counts = h.counts(); var ticket = h.prepare(1, List.of());
            h.driver.onRestore.set(() -> { throw new ReviewFailure("restore"); });
            assertThrows(ReviewFailure.class, h.view::close);
            assertFalse(h.flag("ownedGlObjectsDeleted")); assertTrue(h.flag("stateRestorationPending"));
            assertTrue(h.driver.meshes.stream().allMatch(Mesh::deleted)); assertFalse(h.view.commitPrepared(ticket));
            h.view.close(); assertTrue(h.flag("ownedGlObjectsDeleted")); assertEquals(counts, h.counts());
        }
    }

    @Test void repeatedReusedGenerationsRetainFixedCpuAndGpuBounds() {
        try (var h = new Harness()) {
            h.commit(0, List.of(ready(0, 62, 0, 1, 0))); var counts = h.counts();
            long bytes = h.number("residentBufferBytes"), cached = h.number("summaryCachedBytes");
            for (int generation = 1; generation <= 100; generation++) {
                h.commit(generation, List.of(ready(0, 62, 0, generation + 1, generation)));
                assertEquals(counts, h.counts()); assertEquals(bytes, h.number("residentBufferBytes"));
                assertEquals(cached, h.number("summaryCachedBytes")); assertEquals(1, h.number("summaryCachedItems"));
                assertEquals(0, h.number("summaryRunning")); assertEquals(0, h.number("summaryQueued"));
            }
            assertEquals(101, h.number("commits"));
        }
    }

    private static SceneReadyCube ready(int x, int y, int z, long lease, long revision) {
        return new SceneReadyCube(new CubePos(x, y, z), WORLD, EPOCH, lease, revision, RESOURCES);
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
        final AtomicReference<RuntimeException> identityFailure = new AtomicReference<>();
        public long surfaceY(long x, long z) { samples.incrementAndGet(); return 1000; }
        public int argb(long x, long z, long y) { colors.incrementAndGet(); return 0xffaabbcc; }
        public String identity() { var failure = identityFailure.get(); if (failure != null) throw failure; return identity.get(); }
        public boolean supportsConcurrentReads() { return true; }
    }
    private static final class Harness implements AutoCloseable {
        final Sampler sampler = new Sampler(); final Driver driver = new Driver();
        final AtomicInteger submissions = new AtomicInteger();
        final ExecutorService workers = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("same-geometry-review").factory());
        final DhPreparedLodView view = new DhPreparedLodView(sampler, WORLD, EPOCH, RESOURCES, 0, 0, driver,
                task -> { submissions.incrementAndGet(); workers.execute(task); });
        Harness() { view.initialize(); }
        DhPreparedLodView.CoverageTicket prepare(long generation, List<SceneReadyCube> ready) {
            var ticket = view.prepareCoverage(generation, ready); until(() -> view.isPrepared(ticket), view::pump); return ticket;
        }
        void commit(long generation, List<SceneReadyCube> ready) { assertTrue(view.commitPrepared(prepare(generation, ready))); }
        Map<DhGeographicLodData.TileKey, DhGeographicLodView.Mesh> snapshot() {
            view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES); return driver.drawn;
        }
        Counts counts() { return new Counts(sampler.samples.get(), sampler.colors.get(), driver.compiles.get(), submissions.get(), driver.meshes.size()); }
        long number(String property) { return view.diagnostics().get(property).getAsLong(); }
        boolean flag(String property) { return view.diagnostics().get(property).getAsBoolean(); }
        public void close() {
            sampler.identityFailure.set(null); sampler.identity.set(SOURCE); driver.onCapture.set(null); driver.onRestore.set(null);
            driver.meshes.forEach(mesh -> mesh.failClose = false);
            try { view.close(); assertTrue(driver.meshes.stream().allMatch(Mesh::deleted)); }
            finally {
                workers.shutdownNow();
                try { assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
            }
        }
    }
    private static final class Mesh implements DhGeographicLodView.Mesh {
        final int quads; boolean deleted, failClose;
        Mesh(int quads) { this.quads = quads; }
        public int quads() { return quads; } public int bytes() { return quads * 64; }
        public void close() { if (failClose) throw new ReviewFailure("retained private owner"); deleted = true; }
        public boolean deleted() { return deleted; }
    }
    private static final class Driver implements DhGeographicLodView.Driver {
        final Thread owner = Thread.currentThread(); final List<Mesh> meshes = new ArrayList<>();
        final AtomicInteger compiles = new AtomicInteger(); final AtomicReference<Gate> blockCompile = new AtomicReference<>();
        final AtomicReference<Runnable> onCapture = new AtomicReference<>(), onRestore = new AtomicReference<>();
        Map<DhGeographicLodData.TileKey, DhGeographicLodView.Mesh> drawn = Map.of(); boolean deleted;
        public void checkRenderThread() { assertSame(owner, Thread.currentThread()); }
        public String renderer() { return "independent fake same-geometry review driver"; }
        public void initialize() {}
        public DhGeographicLodView.State captureState() {
            checkRenderThread(); var callback = onCapture.getAndSet(null); if (callback != null) callback.run();
            return () -> { var restore = onRestore.getAndSet(null); if (restore != null) restore.run(); };
        }
        public DhGeographicLodView.CpuCompiler cpuCompiler() {
            return (faces, cancellation) -> {
                assertNotSame(owner, Thread.currentThread()); compiles.incrementAndGet();
                var gate = blockCompile.getAndSet(null); if (gate != null) { gate.entered.countDown(); await(gate.release); }
                // Intentionally ignores this callback; the adapter must still reject the late payload.
                return new DhPreparedVertices(new byte[faces.size() * 64]);
            };
        }
        public DhGeographicLodView.Mesh upload(DhGeographicLodData.TileKey key, List<DhTerrainGeometry.PageFace> faces) {
            throw new AssertionError("Only immutable prepacked vertices may upload");
        }
        public DhGeographicLodView.Mesh uploadPrepared(DhGeographicLodData.TileKey key,
                List<DhTerrainGeometry.PageFace> faces, DhPreparedVertices prepared) {
            checkRenderThread(); var mesh = new Mesh(faces.size()); meshes.add(mesh); return mesh;
        }
        public DhGeographicLodView.DrawStats draw(Map<DhGeographicLodData.TileKey, DhGeographicLodView.Mesh> resources,
                Matrix4f matrix, double x, double y, double z) {
            checkRenderThread(); resources.values().forEach(mesh -> assertFalse(mesh.deleted())); drawn = Map.copyOf(resources);
            return new DhGeographicLodView.DrawStats(0, 0, 0);
        }
        public void close() { checkRenderThread(); deleted = true; } public boolean deleted() { return deleted; }
    }
}
