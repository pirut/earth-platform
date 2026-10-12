package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.scene.SceneReadyCube;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Modifier;
import java.util.function.BiConsumer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static io.earthplatform.minecraft.lod.DhPreparedReplacementTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent final-lifecycle adversaries. Fake GL tests do not certify an actual shader frame. */
@Timeout(30)
class DhReplacementLifecycleIndependentReviewTest {
    @Test void firstDeclinedRegionRemainsLatchedAfterFallbackToNone() {
        try (var h = new Harness()) {
            h.commit(1, mask(0), null);
            var declined = h.prepare(2, mask(0), input(new Guard(), 1, "unsupported"));
            assertTrue(h.view.isReplacementDeclined(declined));
            var other = at(EDIT.offset(1, 0, 0), new Guard());
            assertThrows(IllegalStateException.class, () -> h.view.prepareCoverage(3, mask(0), 0, 0, other));
            assertEquals(2, h.n("highestRequestedCoverageGeneration"));
            assertTrue(h.view.replacementReady(null));
            h.commit(3, mask(0), null);
            assertThrows(IllegalStateException.class, () -> h.view.prepareCoverage(4, mask(0), 0, 0, other));
            assertTrue(h.view.coverageReady(3));
            assertFalse(h.b("invalidated"));
        }
    }

    @Test void cancelledFirstReplacementCannotChangeOriginalRegionOrReviveItsTicket() {
        try (var h = new Harness()) {
            h.commit(1, mask(0), null);
            var ticket = h.view.prepareCoverage(2, mask(0), 0, 0, input(new Guard(), 1));
            h.view.cancelPrepared(ticket);
            assertFalse(h.view.isCurrent(ticket));
            assertThrows(IllegalStateException.class,
                    () -> h.view.prepareCoverage(3, mask(0), 0, 0, at(EDIT.offset(-1, 0, 0), new Guard())));
            assertEquals(2, h.n("highestRequestedCoverageGeneration"));
            h.commit(3, mask(0), null);
            assertFalse(h.view.commitPrepared(ticket));
            assertTrue(h.view.replacementReady(null));
        }
    }

    @Test void bothClippedNativeEndpointsNeedExactlyEighteenReadyCubesToRemoveSummary() {
        for (int y : new int[]{DhTerrainGeometry.MIN_Y, DhTerrainGeometry.MAX_Y_EXCLUSIVE - 16}) {
            try (var h = new Harness()) {
                var replacement = at(new CubePos(0, Math.floorDiv(y, 16), 0), new Guard());
                var cover = cover(replacement.data().capture().ownedVolume());
                assertEquals(18, cover.size());
                assertEquals(7600, replacement.data().capture().cellCount());
                h.commit(1, List.of(), replacement);
                assertTrue(h.view.replacementReady(replacement.certificate().identity()));
                var incomplete = new ArrayList<>(cover); incomplete.removeFirst();
                assertThrows(IllegalStateException.class, () -> h.view.prepareCoverage(2, incomplete, 0, 0, null));
                assertEquals(1, h.n("highestRequestedCoverageGeneration"));
                assertTrue(h.view.replacementReady(replacement.certificate().identity()));
                h.commit(2, cover, null);
                assertTrue(h.view.replacementReady(null));
                assertFalse(h.view.replacementReady(replacement.certificate().identity()));
                assertTrue(h.n("residentPageTileOwners") <= 128);
            }
        }
    }

    @Test void privateNoneCandidateCannotChangeCommittedCertificateHandshake() {
        try (var h = new Harness()) {
            var replacement = input(new Guard(), 1);
            h.commit(1, mask(0), replacement);
            var none = h.prepare(2, mask(0), null);
            assertFalse(h.view.replacementReady(null));
            assertTrue(h.view.replacementReady(replacement.certificate().identity()));
            assertTrue(h.view.commitPrepared(none));
            assertTrue(h.view.replacementReady(null));
            assertFalse(h.view.replacementReady(replacement.certificate().identity()));
            assertFalse(h.view.commitPrepared(none));
        }
    }

    @Test void reentrantGuardCancellationRejectsPreparedAndCommitWithoutTouchingOldPair() {
        try (var h = new Harness()) {
            h.commit(1, mask(0), null);
            var guard = new Guard(); var pending = h.prepare(2, mask(10), input(guard, 1));
            guard.callback = () -> h.view.cancelPrepared(pending);
            assertFalse(h.view.isPrepared(pending));
            assertFalse(h.view.commitPrepared(pending));
            h.view.pump();
            assertTrue(h.view.coverageReady(1));
            assertTrue(h.view.replacementReady(null));
            assertFalse(h.b("invalidated"));
        }
    }

    @Test void reentrantGuardInvalidationRejectsReadyAndCommitWithoutPublication() {
        try (var h = new Harness()) {
            h.commit(1, mask(0), null);
            var guard = new Guard(); var pending = h.prepare(2, mask(0), input(guard, 1));
            guard.callback = h.view::requestInvalidation;
            assertFalse(h.view.isPrepared(pending));
            assertFalse(h.view.commitPrepared(pending));
            assertFalse(h.view.replacementReady(null));
            assertTrue(h.b("invalidated"));
            assertEquals(1, h.n("coverageGeneration"));
        }
    }

    @Test void reentrantHistoricalReleaseCannotSubmitOrRetainAnOldDisplayTicket() {
        try (var h = new Harness()) {
            var guard = new Guard(); h.commit(1, mask(0), input(guard, 1));
            var retained = h.view.retainPresentation();
            guard.callback = () -> h.view.releasePresentation(retained);
            assertFalse(h.view.drawPresented(retained, new Matrix4f(), 0, 1001, 0, RESOURCE));
            assertEquals(0, h.driver.draws);
            assertFalse(h.view.isPresentationCurrent(retained));
            assertTrue(h.view.coverageReady(1));
            assertFalse(h.b("invalidated"));
        }
    }

    @Test void exactSharedGpuBoundaryIncludesEntireNewPieceAndRetainedOwners() {
        var piece = Collections.nCopies(4096, 1);
        long bytes = 8L * 1024 * 1024;
        var exact = DhReplacementGpuBudget.assess(Map.of(), Map.of("piece", piece), 127, bytes - 4096L * 88);
        assertEquals(128, exact.owners()); assertEquals(bytes, exact.bytes()); assertTrue(DhReplacementGpuBudget.fits(exact));
        assertFalse(DhReplacementGpuBudget.fits(DhReplacementGpuBudget.assess(Map.of(), Map.of("piece", piece), 128, 0)));
        assertFalse(DhReplacementGpuBudget.fits(DhReplacementGpuBudget.assess(Map.of(), Map.of("piece", piece), 127, bytes - 4096L * 88 + 1)));
        var alias = DhReplacementGpuBudget.assess(Map.of("piece", piece), Map.of("piece", List.copyOf(piece)), 128, bytes);
        assertEquals(0, alias.changedOwners()); assertTrue(DhReplacementGpuBudget.fits(alias));
    }

    @Test void workerSourceAndCompletionCleanupDoNotCaptureRendererPendingOrAuthority() throws Exception {
        Class<?> source = Class.forName(DhPreparedLodView.class.getName() + "$GeometrySource");
        Class<?> input = Class.forName(DhPreparedLodView.class.getName() + "$Input");
        Class<?> geometry = Class.forName(DhPreparedLodView.class.getName() + "$Geometry");
        assertTrue(Modifier.isStatic(source.getModifiers()));
        Set<Class<?>> allowed = Set.of(io.earthplatform.minecraft.scene.SceneSurfaceSampler.class,
                ConcurrentMap.class, Thread.class, AtomicReference.class);
        for (var field : source.getDeclaredFields()) {
            assertTrue(allowed.contains(field.getType()), field.toString());
            if (field.getName().equals("sampler")) {
                assertEquals(io.earthplatform.minecraft.scene.SceneSurfaceSampler.class, field.getType());
                assertTrue(Modifier.isPrivate(field.getModifiers()));
                assertFalse(Modifier.isStatic(field.getModifiers()));
                //031 must release this shared source alias after physical worker drain.
                //All other captures remain final; no mutable owner/authority is permitted.
                assertFalse(Modifier.isFinal(field.getModifiers()));
            } else assertTrue(Modifier.isFinal(field.getModifiers()), field.toString());
        }
        var executor = new DhNativeDistanceCpuLifetimeTest.Manual();
        var view = DhNativeDistanceCpuLifetimeTest.view(new DhNativeDistanceCpuLifetimeTest.Driver(), executor);
        try {
            var sourceField = DhPreparedLodView.class.getDeclaredField("cpuSource"); sourceField.setAccessible(true);
            Object workerSource = sourceField.get(view);
            var samplerField = source.getDeclaredField("sampler"); samplerField.setAccessible(true);
            Object retainedSampler = samplerField.get(workerSource); assertNotNull(retainedSampler);
            var jobsField = DhPreparedLodView.class.getDeclaredField("jobs"); jobsField.setAccessible(true);
            var jobs = (BoundedLodSummaryJobs<?>) jobsField.get(view);
            jobs.request(DhNativeDistanceCpuLifetimeTest.KEY, DhNativeDistanceCpuLifetimeTest.STAMP);
            assertThrows(IllegalStateException.class, view::close);
            assertSame(retainedSampler, samplerField.get(workerSource), "Logical cancellation must retain source");
            assertFalse(view.physicalDrainComplete());
            executor.run();
            assertSame(retainedSampler, samplerField.get(workerSource), "Physical completion does not mutate render-owner aliases");
            view.close();
            assertTrue(view.physicalDrainComplete());
            assertNull(samplerField.get(workerSource), "Only drained owner close detaches source");
        } finally {
            while (!executor.tasks.isEmpty()) executor.run();
            view.close();
        }
        for (var valueType : List.of(input, geometry, DhReplacementInput.Data.class)) {
            assertTrue(Modifier.isStatic(valueType.getModifiers()));
            for (var field : valueType.getDeclaredFields()) {
                assertNotEquals(DhPreparedLodView.class, field.getType());
                assertNotEquals(DhReplacementInput.class, field.getType());
                assertFalse(DhReplacementInput.AuthorityGuard.class.isAssignableFrom(field.getType()));
                assertFalse(field.getType().getName().endsWith("$Pending"));
            }
        }
        var method = DhPreparedLodView.class.getDeclaredMethod("retainInputCleanup", CompletableFuture.class,
                ConcurrentMap.class, LodSummaryKey.class, input);
        assertTrue(Modifier.isStatic(method.getModifiers())); method.setAccessible(true);
        var observed = new CleanupCaptureFuture();
        var key = new LodSummaryKey(WORLD, SOURCE, new LodSummaryKey.Volume(0, 0, 0, 1, 1, 1), 0, 0, "review");
        // The callback is captured but never invoked; null is enough to inspect its declared capture types.
        method.invoke(null, observed, new ConcurrentHashMap<>(), key, null);
        assertNotNull(observed.cleanup);
        var captured = observed.cleanup.getClass().getDeclaredFields();
        assertEquals(3, captured.length);
        for (var field : captured) assertTrue(Set.of(ConcurrentMap.class, LodSummaryKey.class, input).contains(field.getType()), field.toString());
    }

    private static final class CleanupCaptureFuture extends CompletableFuture<Object> {
        private BiConsumer<? super Object, ? super Throwable> cleanup;
        @Override public CompletableFuture<Object> whenComplete(BiConsumer<? super Object, ? super Throwable> action) {
            cleanup = action; return this;
        }
    }

    private static DhReplacementInput at(CubePos original, Guard guard) {
        var capture = DhVoxelReplacementGeometry.capture(original, (x, y, z) -> 0, () -> {});
        var certificate = new DhReplacementInput.Certificate(WORLD, EPOCH, RESOURCE, 2, original, 1,
                "d".repeat(64), "e".repeat(64), DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        return new DhReplacementInput(certificate, capture, guard);
    }

    private static List<SceneReadyCube> cover(DhTerrainGeometry.Volume volume) {
        var result = new ArrayList<SceneReadyCube>(); long lease = 1;
        for (long x = Math.floorDiv(volume.minX(), 16); x <= Math.floorDiv(volume.maxXExclusive() - 1, 16); x++)
            for (long y = Math.floorDiv(volume.minY(), 16); y <= Math.floorDiv(volume.maxYExclusive() - 1, 16); y++)
                for (long z = Math.floorDiv(volume.minZ(), 16); z <= Math.floorDiv(volume.maxZExclusive() - 1, 16); z++)
                    result.add(new SceneReadyCube(new CubePos(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z)),
                            WORLD, EPOCH, lease++, 1, RESOURCE));
        return List.copyOf(result);
    }
}
