package io.earthplatform.minecraft.lod;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Independent source-identity fault regressions, with CPU-only fake GL ownership. */
@Timeout(30)
class DhPreparedIdentityFaultReviewTest {
    private static final String ID = "identity-fault-review";
    private static final WorldIdentity WORLD = new WorldIdentity(17, "review", "1", ID, "palette");

    @Test void everyActiveIdentityReadFailurePermanentlyFencesEvenIfIdentityRecovers() {
        List<Consumer<Harness>> operations = List.of(
                h -> h.view.isPrepared(h.ticket),
                h -> h.view.isCurrent(h.ticket),
                h -> h.view.hasPublishedGeometry(),
                h -> h.view.coverageReady(0),
                h -> h.view.prepareCoverage(1, List.of()),
                h -> h.view.pump(),
                h -> h.view.commitPrepared(h.ticket),
                h -> h.view.draw(new Matrix4f(), 0, 128, 0, 1));
        for (boolean error : new boolean[]{false, true}) {
            for (int operation = 0; operation < operations.size(); operation++) {
                try (var h = new Harness()) {
                    Throwable failure = error ? new AssertionError("identity lookup failed")
                            : new IllegalStateException("identity lookup failed");
                    h.failure.set(failure);
                    var invoke = operations.get(operation);
                    assertSame(failure, assertThrows(Throwable.class, () -> invoke.accept(h)),
                            "Identity fault must propagate at operation " + operation);
                    h.failure.set(null);
                    assertFalse(h.view.isPrepared(h.ticket), "Recovered identity must not revive a failed ticket");
                    assertFalse(h.view.commitPrepared(h.ticket));
                    assertFalse(h.view.coverageReady(0), "Failed source cannot keep the old pair renderable");
                    assertTrue(h.view.diagnostics().get("invalidated").getAsBoolean());
                    assertThrows(IllegalStateException.class, () -> h.view.prepareCoverage(2, List.of()));
                }
            }
        }
    }

    @Test void cancellationReenteredThroughIdentityCannotReturnAReadyTicket() {
        try (var h = new Harness()) {
            h.identityCallback.set(() -> h.view.cancelPrepared(h.ticket));
            assertFalse(h.view.isPrepared(h.ticket));
            assertFalse(h.view.commitPrepared(h.ticket));
            assertTrue(h.view.coverageReady(0), "Ordinary candidate cancellation preserves the old pair");
            h.view.pump();
        }
    }

    @Test void invalidationReenteredThroughIdentityCannotResurrectPublishedGeometry() {
        try (var h = new Harness()) {
            h.identityCallback.set(h.view::requestInvalidation);
            assertThrows(IllegalStateException.class, h.view::hasPublishedGeometry);
            assertFalse(h.view.isPrepared(h.ticket));
            assertFalse(h.view.commitPrepared(h.ticket));
            assertFalse(h.view.coverageReady(0));
        }
    }

    private static final class Harness implements AutoCloseable {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<Runnable> identityCallback = new AtomicReference<>();
        final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("identity-review-source").factory());
        final Driver driver = new Driver();
        final DhPreparedLodView view;
        final DhPreparedLodView.CoverageTicket ticket;
        Harness() {
            var source = new SceneSurfaceSampler() {
                public long surfaceY(long x, long z) { return 128; }
                public int argb(long x, long z, long y) { return 0xff808080; }
                public boolean supportsConcurrentReads() { return true; }
                public String identity() {
                    var callback = identityCallback.getAndSet(null);
                    if (callback != null) callback.run();
                    var fault = failure.get();
                    if (fault instanceof Error e) throw e;
                    if (fault instanceof RuntimeException e) throw e;
                    return ID;
                }
            };
            view = new DhPreparedLodView(source, WORLD, 9, 1, 0, 0, driver, worker);
            view.initialize();
            assertTrue(view.commitPrepared(prepare(0)));
            ticket = prepare(1);
        }
        DhPreparedLodView.CoverageTicket prepare(long generation) {
            var result = view.prepareCoverage(generation, List.of());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!view.isPrepared(result)) {
                assertTrue(System.nanoTime() < deadline, "Timed out preparing fake view");
                view.pump();
                try { Thread.sleep(1); } catch (InterruptedException e) { throw new AssertionError(e); }
            }
            return result;
        }
        public void close() {
            failure.set(null); identityCallback.set(null);
            try { view.close(); assertTrue(driver.owners.stream().allMatch(Mesh::deleted)); }
            finally {
                worker.shutdownNow();
                try { assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
        }
    }
    private static final class Mesh implements DhGeographicLodView.Mesh {
        final int quads; boolean deleted;
        Mesh(int quads) { this.quads = quads; }
        public int quads() { return quads; }
        public int bytes() { return quads * 64; }
        public void close() { deleted = true; }
        public boolean deleted() { return deleted; }
    }
    private static final class Driver implements DhGeographicLodView.Driver {
        final Thread owner = Thread.currentThread();
        final List<Mesh> owners = new ArrayList<>();
        boolean deleted;
        public void checkRenderThread() { assertSame(owner, Thread.currentThread()); }
        public String renderer() { return "independent fake identity-fault driver"; }
        public DhGeographicLodView.State captureState() { return () -> {}; }
        public void initialize() {}
        public DhGeographicLodView.CpuCompiler cpuCompiler() {
            return (faces, cancel) -> { cancel.run(); return new DhPreparedVertices(new byte[faces.size() * 64]); };
        }
        public DhGeographicLodView.Mesh upload(DhGeographicLodData.TileKey key, List<DhTerrainGeometry.PageFace> faces) {
            throw new AssertionError("Prepared-only upload required");
        }
        public DhGeographicLodView.Mesh uploadPrepared(DhGeographicLodData.TileKey key,
                List<DhTerrainGeometry.PageFace> faces, DhPreparedVertices prepared) {
            checkRenderThread(); var mesh = new Mesh(faces.size()); owners.add(mesh); return mesh;
        }
        public DhGeographicLodView.DrawStats draw(Map<DhGeographicLodData.TileKey, DhGeographicLodView.Mesh> meshes,
                Matrix4f matrix, double x, double y, double z) { return new DhGeographicLodView.DrawStats(0, 0, 0); }
        public void close() { deleted = true; }
        public boolean deleted() { return deleted; }
    }
}
