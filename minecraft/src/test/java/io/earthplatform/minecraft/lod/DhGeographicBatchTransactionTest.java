package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.scene.SceneReadyCube;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real view's transaction protocol with bounded fake resources, not GPU behavior. */
@Timeout(30)
class DhGeographicBatchTransactionTest {
    private static final String IDENTITY = "transaction-flat-source-y1000";
    private static final WorldIdentity WORLD = new WorldIdentity(17, "scene", "1", IDENTITY, "native-palette");
    private static final long EPOCH = 9, RESOURCES = 3;
    private static final SceneSurfaceSampler FLAT = flat(new AtomicReference<>(IDENTITY));

    private static SceneSurfaceSampler flat(AtomicReference<String> identity) {
        return new SceneSurfaceSampler() {
            public long surfaceY(long x, long z) { return 1000; }
            public int argb(long x, long z, long y) { return 0xffddeeff; }
            public String identity() { return identity.get(); }
        };
    }

    private static DhGeographicLodView view(FakeDriver driver) {
        var view = new DhGeographicLodView(FLAT, WORLD, EPOCH, RESOURCES, 0, 0, driver);
        view.initialize();
        return view;
    }

    private static SceneReadyCube ready(int x, int z) {
        return new SceneReadyCube(new CubePos(x, 62, z), WORLD, EPOCH, 11, 7, RESOURCES);
    }

    private static List<SceneReadyCube> ready32() {
        List<SceneReadyCube> ready = new ArrayList<>();
        for (int z = -2; z < 2; z++) for (int x = -2; x < 2; x++) {
            ready.add(ready(x, z));
            ready.add(new SceneReadyCube(new CubePos(x, 61, z), WORLD, EPOCH, 11, 7, RESOURCES));
        }
        return List.copyOf(ready);
    }

    private static long number(DhGeographicLodView view, String key) { return view.diagnostics().get(key).getAsLong(); }
    private static boolean flag(DhGeographicLodView view, String key) { return view.diagnostics().get(key).getAsBoolean(); }

    @Test void initializationPublishesOneCompleteEmptyCoverageBatchAndCloseIsIdempotent() {
        FakeDriver driver = new FakeDriver();
        DhGeographicLodView view = view(driver);
        assertEquals(1, driver.initializeCalls);
        assertTrue(driver.restoreCalls > 0);
        assertTrue(view.coverageReady(0));
        assertFalse(view.coverageReady(-1));
        assertFalse(view.coverageReady(1));
        assertEquals(0, number(view, "coverageGeneration"));
        assertEquals(0, number(view, "nearReadyCubes"));
        assertEquals(16, number(view, "pageTileOwners"));
        assertEquals(4096, number(view, "publishedFaces"));
        assertEquals(4096 * 64L, number(view, "currentVertexBytes"));
        assertEquals(0, number(view, "retirementPending"));
        assertGeometry(view, driver, List.of());
        view.close();
        assertTrue(flag(view, "closed"));
        assertTrue(flag(view, "invalidated"));
        assertTrue(flag(view, "ownedGlObjectsDeleted"));
        assertFalse(view.coverageReady(0));
        assertEquals(0, number(view, "pageTileOwners"));
        assertEquals(0, driver.liveMeshes());
        assertTrue(driver.deleted());
        int closes = driver.closeCalls;
        view.close();
        assertEquals(closes, driver.closeCalls);
    }

    @Test void sameGenerationIsIdempotentOnlyForTheExactReadyBatchAndOlderGenerationsNeverUpload() {
        FakeDriver driver = new FakeDriver();
        try (var view = view(driver)) {
            List<SceneReadyCube> ready = List.of(ready(0, 0));
            assertTrue(view.replaceCoverage(4, ready));
            int uploads = driver.uploadCalls;
            long changes = number(view, "coverageChanges");
            assertTrue(view.replaceCoverage(4, List.copyOf(ready)));
            assertFalse(view.replaceCoverage(4, List.of()));
            assertFalse(view.replaceCoverage(3, ready));
            assertFalse(view.replaceCoverage(-1, ready));
            SceneReadyCube first = ready.getFirst();
            assertFalse(view.replaceCoverage(4, List.of(new SceneReadyCube(first.position(), WORLD, EPOCH, 12, 7, RESOURCES))));
            assertFalse(view.replaceCoverage(4, List.of(new SceneReadyCube(first.position(), WORLD, EPOCH, 11, 8, RESOURCES))));
            assertEquals(uploads, driver.uploadCalls);
            assertEquals(changes, number(view, "coverageChanges"));
            assertTrue(view.coverageReady(4));
            assertFalse(view.coverageReady(3));
            assertFalse(view.coverageReady(5));
            assertEquals(1, number(view, "nearReadyCubes"));
            assertGeometry(view, driver, ready);
        }
    }

    @Test void wrongWorldEpochResourcesDuplicateNullAndOverbudgetReadyBatchesLeaveCurrentCoverageAlone() {
        FakeDriver driver = new FakeDriver();
        try (var view = view(driver)) {
            SceneReadyCube cube = ready(0, 0);
            assertTrue(view.replaceCoverage(1, List.of(cube)));
            int uploads = driver.uploadCalls;
            var otherWorld = new WorldIdentity(18, "scene", "1", IDENTITY, "native-palette");
            for (var invalid : List.of(
                    new SceneReadyCube(cube.position(), otherWorld, EPOCH, 11, 7, RESOURCES),
                    new SceneReadyCube(cube.position(), WORLD, EPOCH + 1, 11, 7, RESOURCES),
                    new SceneReadyCube(cube.position(), WORLD, EPOCH, 11, 7, RESOURCES + 1))) {
                assertFalse(view.replaceCoverage(2, List.of(invalid)));
            }
            assertFalse(view.replaceCoverage(2, List.of(cube, cube)));
            assertFalse(view.replaceCoverage(2, List.of(cube,
                    new SceneReadyCube(cube.position(), WORLD, EPOCH, 12, 8, RESOURCES))));
            assertFalse(view.replaceCoverage(2, java.util.Arrays.asList(cube, null)));
            List<SceneReadyCube> overbudget = new ArrayList<>();
            for (int x = 0; x < 257; x++) overbudget.add(ready(x, 0));
            assertFalse(view.replaceCoverage(2, overbudget));
            assertEquals(uploads, driver.uploadCalls);
            assertTrue(view.coverageReady(1));
            assertEquals(1, number(view, "coverageGeneration"));
            assertEquals(1, number(view, "nearReadyCubes"));
            assertEquals(0, number(view, "retirementPending"));
            assertGeometry(view, driver, List.of(cube));
        }
    }

    @Test void invalidNativeReadinessStampsCannotBeConstructed() {
        CubePos pos = new CubePos(0, 62, 0);
        for (long lease : new long[]{0, -1})
            assertThrows(IllegalArgumentException.class, () -> new SceneReadyCube(pos, WORLD, EPOCH, lease, 7, RESOURCES));
        assertThrows(IllegalArgumentException.class, () -> new SceneReadyCube(pos, WORLD, EPOCH, 11, -1, RESOURCES));
        assertThrows(IllegalArgumentException.class, () -> new SceneReadyCube(pos, WORLD, -1, 11, 7, RESOURCES));
        assertThrows(IllegalArgumentException.class, () -> new SceneReadyCube(pos, WORLD, EPOCH, 11, 7, 0));
    }

    @Test void completeMaskEmptyMaskTransactionsOwnExactlyThePublishedGeometry() {
        FakeDriver driver = new FakeDriver();
        try (var view = view(driver)) {
            List<SceneReadyCube> mask = ready32();
            List<FakeMesh> previous = driver.live();
            int peak=previous.size();String profile=null;
            for (int generation = 1; generation <= 3; generation++) {
                List<SceneReadyCube> proposed = generation == 2 ? List.of() : mask;
                assertTrue(view.replaceCoverage(generation, proposed));
                assertTrue(previous.stream().allMatch(FakeMesh::deleted));
                assertEquals(proposed.size(), number(view, "nearReadyCubes"));
                var expected=geometry(proposed,mask);
                peak=Math.max(peak,previous.size()+expected.tiles().size());
                assertEquals(expected.faces(), number(view, "publishedFaces"));
                assertEquals(expected.clippedSourceFaces(), number(view, "clippedSourceFaces"));
                assertEquals(expected.tiles().size(), number(view, "pageTileOwners"));
                assertEquals(expected.tiles().size(), driver.liveMeshes());
                String currentProfile=view.diagnostics().get("refinementProfileIdentity").getAsString();
                if(profile!=null)assertEquals(profile,currentProfile,"Empty mask must retain the refined fallback");
                profile=currentProfile;assertEquals(1,number(view,"refinementProfileGeneration"));
                assertEquals(0, number(view, "retirementPending"));
                assertTrue(view.coverageReady(generation));
                assertFalse(view.coverageReady(generation - 1));
                assertGeometry(view, driver, proposed);
                previous = driver.live();
            }
            assertEquals(peak, driver.maxLiveMeshes);
            assertEquals(peak, number(view, "peakResidentPageTileOwners"));
        }
        assertEquals(0, driver.liveMeshes());
    }

    @Test void failedFirstMiddleAndLastUploadsCleanEveryPrivateCandidateAndKeepTheOldGenerationDrawable() {
        for (int failedCandidate : new int[]{1, 8, geometry(ready32(),ready32()).tiles().size()}) {
            FakeDriver driver = new FakeDriver();
            try (var view = view(driver)) {
                List<SceneReadyCube> prior = List.of(ready(0, 0));
                assertTrue(view.replaceCoverage(1, prior));
                List<FakeMesh> old = driver.live();
                int start = driver.allocated.size();
                long uploadedBytes = number(view, "uploadedVertexBytes");
                long changes = number(view, "coverageChanges");
                int restores = driver.restoreCalls;
                driver.failUpload = driver.uploadCalls + failedCandidate;
                assertThrows(InjectedFailure.class, () -> view.replaceCoverage(2, ready32()));
                assertEquals(start + failedCandidate, driver.allocated.size());
                assertTrue(driver.allocated.subList(start, driver.allocated.size()).stream().allMatch(FakeMesh::deleted));
                assertTrue(old.stream().noneMatch(FakeMesh::deleted));
                assertEquals(old, driver.live());
                assertTrue(driver.restoreCalls > restores);
                assertTrue(view.coverageReady(1));
                assertFalse(view.coverageReady(2));
                assertFalse(flag(view, "invalidated"));
                assertEquals(1, number(view, "coverageGeneration"));
                assertEquals(1, number(view, "nearReadyCubes"));
                assertEquals(uploadedBytes, number(view, "uploadedVertexBytes"));
                assertEquals(changes, number(view, "coverageChanges"));
                assertGeometry(view, driver, prior);
                driver.failUpload = -1;
                assertTrue(view.replaceCoverage(2, ready32()));
            }
            assertEquals(0, driver.liveMeshes());
        }
    }

    @Test void illegalArgumentFromUploadIsAnOperationalFailureRatherThanABudgetRejection() {
        FakeDriver driver = new FakeDriver();
        try (var view = view(driver)) {
            List<FakeMesh> old = driver.live();
            driver.uploadFailure = new IllegalArgumentException("injected upload argument failure");
            driver.failUpload = driver.uploadCalls + 3;
            var failure = assertThrows(IllegalArgumentException.class, () -> view.replaceCoverage(1, ready32()));
            assertEquals("injected upload argument failure", failure.getMessage());
            assertTrue(view.coverageReady(0));
            assertEquals(old, driver.live());
        }
    }

    @Test void stateRestorationCompletesBeforeNewCoverageIsCommittedOrOldOwnersRetired() {
        FakeDriver driver = new FakeDriver();
        try (var view = view(driver)) {
            assertTrue(view.replaceCoverage(1, List.of(ready(0, 0))));
            List<FakeMesh> old = driver.live();
            driver.events.clear();
            driver.onRestore = () -> {
                assertEquals(1, number(view, "coverageGeneration"));
                assertTrue(view.coverageReady(1));
                assertTrue(old.stream().noneMatch(FakeMesh::deleted));
                assertEquals(old.size()+geometry(ready32(),ready32()).tiles().size(), driver.liveMeshes());
            };
            assertTrue(view.replaceCoverage(2, ready32()));
            driver.onRestore = () -> {};
            int restored = driver.events.indexOf("restore");
            assertTrue(restored >= 0);
            for (var mesh : old) assertTrue(driver.events.indexOf("close-" + mesh.id) > restored);
            assertTrue(view.coverageReady(2));
        }
    }

    @Test void restorationFailureRejectsPrivateBatchAndFencesTheStillOwnedOldBatch() {
        FakeDriver driver = new FakeDriver();
        var view = view(driver);
        assertTrue(view.replaceCoverage(1, List.of(ready(0, 0))));
        List<FakeMesh> old = driver.live();
        int start = driver.allocated.size();
        driver.failNextRestore = true;
        assertThrows(InjectedFailure.class, () -> view.replaceCoverage(2, ready32()));
        assertEquals(1, number(view, "coverageGeneration"));
        assertEquals(1, number(view, "nearReadyCubes"));
        assertEquals(old.size(), number(view, "pageTileOwners"));
        assertTrue(flag(view, "invalidated"));
        assertFalse(view.coverageReady(1));
        assertTrue(old.stream().noneMatch(FakeMesh::deleted));
        assertTrue(driver.allocated.subList(start, driver.allocated.size()).stream().allMatch(FakeMesh::deleted));
        assertEquals(old, driver.live());
        assertFalse(view.replaceCoverage(3, List.of()));
        view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES);
        assertEquals(0, driver.drawCalls);
        view.close();
        assertTrue(flag(view, "ownedGlObjectsDeleted"));
        assertEquals(0, driver.liveMeshes());
    }

    @Test void retirementFailureAttemptsEveryOwnerFencesPublicationAndRemainsOwnedAcrossCloseRetries() {
        FakeDriver driver = new FakeDriver();
        var view = view(driver);
        assertTrue(view.replaceCoverage(1, List.of(ready(0, 0))));
        List<FakeMesh> old = driver.live();
        Set<Integer> failed = Set.of(old.getFirst().id, old.getLast().id);
        driver.failMeshClose.addAll(failed);
        assertThrows(InjectedFailure.class, () -> view.replaceCoverage(2, ready32()));
        assertEquals(2, number(view, "coverageGeneration"));
        assertEquals(32, number(view, "nearReadyCubes"));
        assertEquals(geometry(ready32(),ready32()).tiles().size(), number(view, "pageTileOwners"));
        assertEquals(2, number(view, "retirementPending"));
        assertTrue(flag(view, "invalidated"));
        assertFalse(view.coverageReady(1));
        assertFalse(view.coverageReady(2));
        for (var mesh : old) {
            assertEquals(1, mesh.closeCalls, "All retired owners must receive a cleanup attempt");
            assertEquals(!failed.contains(mesh.id), mesh.deleted());
        }
        assertEquals(geometry(ready32(),ready32()).tiles().size()+2, driver.liveMeshes());
        int uploads = driver.uploadCalls;
        assertFalse(view.replaceCoverage(3, List.of()));
        assertEquals(uploads, driver.uploadCalls);
        view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES);
        assertEquals(0, driver.drawCalls);
        assertThrows(InjectedFailure.class, view::close);
        assertTrue(flag(view, "closed"));
        assertFalse(flag(view, "ownedGlObjectsDeleted"));
        assertEquals(2, number(view, "retirementPending"));
        assertEquals(2, driver.liveMeshes());
        assertTrue(driver.deleted(), "Global cleanup must also run despite mesh cleanup failures");
        driver.failMeshClose.clear();
        view.close();
        assertTrue(flag(view, "ownedGlObjectsDeleted"));
        assertEquals(0, number(view, "retirementPending"));
        assertEquals(0, driver.liveMeshes());
        assertTrue(driver.allocated.stream().allMatch(FakeMesh::deleted));
    }

    @Test void closeAttemptsAllMeshesAndGlobalsEvenWhenBothKindsOfCleanupFail() {
        FakeDriver driver = new FakeDriver();
        var view = view(driver);
        List<FakeMesh> old = driver.live();
        driver.failMeshClose.add(old.getFirst().id);
        driver.failGlobalClose = true;
        assertThrows(InjectedFailure.class, view::close);
        assertTrue(old.stream().allMatch(mesh -> mesh.closeCalls == 1));
        assertEquals(1, driver.closeCalls);
        assertFalse(driver.deleted());
        assertFalse(flag(view, "ownedGlObjectsDeleted"));
        assertTrue(flag(view, "closed"));
        assertFalse(view.coverageReady(0));
        driver.failMeshClose.clear();
        driver.failGlobalClose = false;
        view.close();
        assertTrue(flag(view, "ownedGlObjectsDeleted"));
        assertTrue(driver.deleted());
        assertEquals(0, driver.liveMeshes());
    }

    @Test void rejectedCandidateCleanupFailuresStayOwnedAndDoNotPreventOtherCandidateCleanup() {
        FakeDriver driver = new FakeDriver();
        var view = view(driver);
        assertTrue(view.replaceCoverage(1, List.of(ready(0, 0))));
        List<FakeMesh> old = driver.live();
        int start = driver.allocated.size();
        driver.failMeshClose.addAll(Set.of(start + 1, start + 3));
        driver.failUpload = driver.uploadCalls + 8;
        assertThrows(InjectedFailure.class, () -> view.replaceCoverage(2, ready32()));
        assertEquals(1, number(view, "coverageGeneration"));
        assertEquals(1, number(view, "nearReadyCubes"));
        assertEquals(2, number(view, "retirementPending"));
        assertTrue(flag(view, "invalidated"));
        assertFalse(view.coverageReady(1));
        assertTrue(old.stream().noneMatch(FakeMesh::deleted));
        assertEquals(old.size()+2, driver.liveMeshes());
        for (var candidate : driver.allocated.subList(start, driver.allocated.size())) {
            assertEquals(1, candidate.closeCalls, "One failing candidate must not abort the cleanup loop");
            assertEquals(!driver.failMeshClose.contains(candidate.id), candidate.deleted());
        }
        driver.failMeshClose.clear();
        view.close();
        assertTrue(flag(view, "ownedGlObjectsDeleted"));
        assertEquals(0, number(view, "retirementPending"));
        assertEquals(0, driver.liveMeshes());
    }

    @Test void partialUploadCleanupFailureRemainsDriverOwnedFencesRetriesAndIsReleasedByClose() {
        FakeDriver driver = new FakeDriver();
        var view = view(driver);
        assertTrue(view.replaceCoverage(1, List.of(ready(0, 0))));
        List<FakeMesh> old = driver.live();
        int start = driver.allocated.size();
        driver.failUpload = driver.uploadCalls + 8;
        driver.failMeshClose.add(start + 8);
        assertThrows(InjectedFailure.class, () -> view.replaceCoverage(2, ready32()));
        assertEquals(1, number(view, "coverageGeneration"));
        assertEquals(1, number(view, "nearReadyCubes"));
        assertEquals(0, number(view, "retirementPending"));
        assertEquals(1, number(view, "driverPartialOwners"));
        assertEquals(1, driver.pendingOwners());
        assertTrue(flag(view, "invalidated"));
        assertFalse(view.coverageReady(1));
        assertTrue(old.stream().noneMatch(FakeMesh::deleted));
        assertEquals(old.size()+1, driver.liveMeshes());
        for (var candidate : driver.allocated.subList(start, driver.allocated.size() - 1)) assertTrue(candidate.deleted());
        int uploads = driver.uploadCalls;
        assertFalse(view.replaceCoverage(3, List.of()));
        assertEquals(uploads, driver.uploadCalls, "Retained partial allocations must not accumulate through retries");
        view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES);
        assertEquals(0, driver.drawCalls);
        assertThrows(InjectedFailure.class, view::close);
        assertTrue(flag(view, "closed"));
        assertFalse(flag(view, "ownedGlObjectsDeleted"));
        assertEquals(1, driver.liveMeshes());
        assertEquals(1, number(view, "driverPartialOwners"));
        driver.failMeshClose.clear();
        view.close();
        assertEquals(0, number(view, "driverPartialOwners"));
        assertEquals(0, driver.pendingOwners());
        assertEquals(0, driver.liveMeshes());
        assertTrue(flag(view, "ownedGlObjectsDeleted"));
        assertTrue(driver.deleted());
    }

    @Test void initializationAndFirstMiddleLastUploadFailuresReleaseEverythingTheyAcquired() {
        FakeDriver initialization = new FakeDriver();
        initialization.failInitialize = true;
        assertThrows(InjectedFailure.class, () -> view(initialization));
        assertEquals(1, initialization.initializeCalls);
        assertEquals(1, initialization.closeCalls);
        assertTrue(initialization.deleted());
        assertEquals(0, initialization.liveMeshes());
        assertTrue(initialization.restoreCalls > 0);
        for (int failedUpload : new int[]{1, 8, 16}) {
            FakeDriver driver = new FakeDriver();
            driver.failUpload = failedUpload;
            assertThrows(InjectedFailure.class, () -> view(driver));
            assertEquals(failedUpload, driver.allocated.size());
            assertTrue(driver.allocated.stream().allMatch(FakeMesh::deleted));
            assertEquals(1, driver.closeCalls);
            assertTrue(driver.deleted());
            assertTrue(driver.restoreCalls > 0);
        }
    }

    @Test void failedInitializationAndCleanupLeaveAnAlreadyRetainedOwnerAvailableForCloseRetry() {
        FakeDriver driver = new FakeDriver();
        var view = new DhGeographicLodView(FLAT, WORLD, EPOCH, RESOURCES, 0, 0, driver);
        assertEquals(0, driver.initializeCalls);
        assertEquals(0, driver.uploadCalls);
        assertEquals(0, driver.liveMeshes());
        assertTrue(driver.deleted());
        assertFalse(view.coverageReady(-1));
        assertFalse(view.coverageReady(0));
        view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES);
        assertEquals(0, driver.drawCalls);
        assertThrows(IllegalStateException.class, () -> view.replaceCoverage(0, List.of()));
        driver.failInitialize = true;
        driver.failGlobalClose = true;
        var failure = assertThrows(InjectedFailure.class, view::initialize);
        assertTrue(failure.getSuppressed().length > 0, "Cleanup failure must remain attached to the initialization fault");
        assertEquals(1, driver.initializeCalls);
        assertEquals(1, driver.closeCalls);
        assertEquals(0, driver.uploadCalls);
        assertFalse(driver.deleted());
        assertTrue(flag(view, "closed"));
        assertTrue(flag(view, "invalidated"));
        assertFalse(flag(view, "ownedGlObjectsDeleted"));
        assertFalse(view.coverageReady(0));
        driver.failInitialize = false;
        driver.failGlobalClose = false;
        view.close();
        assertEquals(2, driver.closeCalls);
        assertTrue(driver.deleted());
        assertTrue(flag(view, "ownedGlObjectsDeleted"));
        assertEquals(0, driver.liveMeshes());
    }

    @Test void invalidationDuringPrivateUploadReturnsBeforeUploadResumesAndPreventsPublication() throws Exception {
        FakeDriver driver = new FakeDriver();
        try (var view = view(driver); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertTrue(view.replaceCoverage(1, List.of(ready(0, 0))));
            List<FakeMesh> old = driver.live();
            int start = driver.allocated.size();
            CountDownLatch uploadEntered = new CountDownLatch(1);
            CountDownLatch invalidationReturned = new CountDownLatch(1);
            Future<?> invalidation = executor.submit(() -> {
                await(uploadEntered);
                view.requestInvalidation();
                invalidationReturned.countDown();
            });
            driver.onUpload = () -> {
                uploadEntered.countDown();
                await(invalidationReturned);
            };
            assertFalse(view.replaceCoverage(2, ready32()));
            invalidation.get(5, TimeUnit.SECONDS);
            assertEquals(1, number(view, "coverageGeneration"));
            assertEquals(1, number(view, "nearReadyCubes"));
            assertTrue(flag(view, "invalidated"));
            assertFalse(view.coverageReady(1));
            assertTrue(old.stream().noneMatch(FakeMesh::deleted));
            assertTrue(driver.allocated.size() > start);
            assertTrue(driver.allocated.subList(start, driver.allocated.size()).stream().allMatch(FakeMesh::deleted));
            assertEquals(old, driver.live());
        }
    }

    @Test void invalidationCannotReturnWhileDrawCallbackIsInsideTheSubmissionFence() throws Exception {
        FakeDriver driver = new FakeDriver();
        try (var view = view(driver); ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch drawEntered = new CountDownLatch(1);
            CountDownLatch invalidationStarted = new CountDownLatch(1);
            CountDownLatch invalidationReturned = new CountDownLatch(1);
            CountDownLatch letDrawLeave = new CountDownLatch(1);
            AtomicBoolean insideDraw = new AtomicBoolean();
            driver.onDraw = () -> {
                insideDraw.set(true);
                drawEntered.countDown();
                try { await(letDrawLeave); }
                finally { insideDraw.set(false); }
            };
            Future<?> invalidation = executor.submit(() -> {
                await(drawEntered);
                invalidationStarted.countDown();
                view.requestInvalidation();
                assertFalse(insideDraw.get(), "Invalidation returned before the entered draw callback left");
                invalidationReturned.countDown();
            });
            Future<?> release = executor.submit(() -> {
                await(invalidationStarted);
                try {
                    assertFalse(invalidationReturned.await(150, TimeUnit.MILLISECONDS),
                            "Reload must wait for the complete entered submission callback");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally { letDrawLeave.countDown(); }
            });
            try { view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES); }
            finally { letDrawLeave.countDown(); }
            invalidation.get(5, TimeUnit.SECONDS);
            release.get(5, TimeUnit.SECONDS);
            assertEquals(1, driver.drawCalls);
            assertTrue(flag(view, "invalidated"));
            assertFalse(view.coverageReady(0));
            view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES);
            assertEquals(1, driver.drawCalls, "No later submission is allowed after invalidation returns");
        }
    }

    @Test void staleResourcesAndChangedSourceIdentityNeverSubmitOrBecomeReady() {
        FakeDriver driver = new FakeDriver();
        AtomicReference<String> identity = new AtomicReference<>(IDENTITY);
        try (var view = new DhGeographicLodView(flat(identity), WORLD, EPOCH, RESOURCES, 0, 0, driver)) {
            view.initialize();
            view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES + 1);
            assertEquals(0, driver.drawCalls);
            assertTrue(view.coverageReady(0));
            List<FakeMesh> old = driver.live();
            driver.onUpload = () -> identity.set("changed-source");
            assertFalse(view.replaceCoverage(1, ready32()));
            assertEquals(0, number(view, "coverageGeneration"));
            assertEquals(old, driver.live());
            assertFalse(view.coverageReady(0));
            view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES);
            assertEquals(0, driver.drawCalls);
            assertTrue(flag(view, "invalidated"));
        }
    }

    @Test void drawAndDrawRestorationFailuresFenceLaterSubmissionWithoutDroppingPublishedOwners() {
        for (boolean restorationFailure : new boolean[]{false, true}) {
            FakeDriver driver = new FakeDriver();
            try (var view = view(driver)) {
                List<FakeMesh> old = driver.live();
                int restores = driver.restoreCalls;
                driver.failNextRestore = restorationFailure;
                if (!restorationFailure) driver.onDraw = () -> { throw new InjectedFailure("draw failure"); };
                assertThrows(InjectedFailure.class, () -> view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES));
                assertEquals(restores + 1, driver.restoreCalls);
                assertEquals(1, driver.drawCalls);
                assertTrue(flag(view, "invalidated"));
                assertFalse(view.coverageReady(0));
                assertEquals(0, number(view, "coverageGeneration"));
                assertEquals(16, number(view, "pageTileOwners"));
                assertEquals(old, driver.live());
                view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES);
                assertEquals(1, driver.drawCalls);
            }
            assertEquals(0, driver.liveMeshes());
            assertTrue(driver.deleted());
        }
    }

    private static DhAdaptiveTerrainData.Batch geometry(List<SceneReadyCube> ready,List<SceneReadyCube> profile) {
        return new DhAdaptiveTerrainData(FLAT,0,0).clip(ready.stream().map(SceneReadyCube::position).toList(),profile.stream().map(SceneReadyCube::position).toList());
    }

    private static void assertGeometry(DhGeographicLodView view, FakeDriver driver, List<SceneReadyCube> ready) {
        if(!ready.isEmpty())driver.expectedRefinement=List.copyOf(ready);
        var expected=geometry(ready,driver.expectedRefinement);
        view.draw(new Matrix4f(), 0, 1000, 0, RESOURCES);
        assertEquals(expected.tiles().keySet(), driver.lastDraw.keySet());
        for (var entry : driver.lastDraw.entrySet()) {
            FakeMesh mesh = entry.getValue();
            assertFalse(mesh.deleted());
            assertEquals(entry.getKey(), mesh.key);
            assertEquals(expected.tiles().get(entry.getKey()), mesh.faces);
            assertEquals(mesh.faces.size(), mesh.quads());
            assertEquals(mesh.faces.size() * 64, mesh.bytes());
        }
        assertEquals(expected.faces(), driver.lastDraw.values().stream().mapToInt(FakeMesh::quads).sum());
        assertEquals(expected.faces(), number(view, "publishedFaces"));
        assertEquals(expected.faces() * 64L, number(view, "currentVertexBytes"));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out awaiting the forced test interleaving"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class InjectedFailure extends RuntimeException {
        InjectedFailure(String message) { super(message); }
    }

    private static final class FakeMesh implements DhGeographicLodView.Mesh {
        final FakeDriver driver;
        final int id;
        final DhGeographicLodData.TileKey key;
        final List<DhTerrainGeometry.PageFace> faces;
        boolean deleted;
        int closeCalls;

        FakeMesh(FakeDriver driver, int id, DhGeographicLodData.TileKey key, List<DhTerrainGeometry.PageFace> faces) {
            this.driver = driver;
            this.id = id;
            this.key = key;
            this.faces = List.copyOf(faces);
            assertFalse(faces.isEmpty());
            assertTrue(faces.size() <= 4096);
        }

        public int quads() { return faces.size(); }
        public int bytes() { return faces.size() * 64; }
        public boolean deleted() { return deleted; }
        public void close() {
            closeCalls++;
            driver.events.add("close-" + id);
            if (driver.failMeshClose.contains(id)) throw new InjectedFailure("mesh cleanup " + id);
            deleted = true;
        }
    }

    private static final class FakeDriver implements DhGeographicLodView.Driver {
        final Thread owner = Thread.currentThread();
        List<SceneReadyCube> expectedRefinement=List.of();
        final List<FakeMesh> allocated = new ArrayList<>();
        final List<FakeMesh> incomplete = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final Set<Integer> failMeshClose = new HashSet<>();
        Map<DhGeographicLodData.TileKey, FakeMesh> lastDraw = Map.of();
        int initializeCalls, uploadCalls, restoreCalls, closeCalls, drawCalls, maxLiveMeshes;
        int failUpload = -1;
        RuntimeException uploadFailure = new InjectedFailure("upload failure");
        boolean globalsAllocated, globalsDeleted, failInitialize, failNextRestore, failGlobalClose;
        Runnable onUpload = () -> {}, onRestore = () -> {}, onDraw = () -> {};

        public void checkRenderThread() { assertSame(owner, Thread.currentThread()); }
        public String renderer() { return "bounded fault-injection driver; no GPU proof"; }
        public DhGeographicLodView.State captureState() {
            checkRenderThread();
            return () -> {
                restoreCalls++;
                events.add("restore");
                onRestore.run();
                if (failNextRestore) {
                    failNextRestore = false;
                    throw new InjectedFailure("state restoration failure");
                }
            };
        }

        public void initialize() {
            checkRenderThread();
            initializeCalls++;
            globalsAllocated = true;
            if (failInitialize) throw new InjectedFailure("global initialization failure");
        }

        public DhGeographicLodView.Mesh upload(DhGeographicLodData.TileKey key, List<DhTerrainGeometry.PageFace> faces) {
            checkRenderThread();
            uploadCalls++;
            FakeMesh mesh = new FakeMesh(this, allocated.size() + 1, key, faces);
            allocated.add(mesh);
            maxLiveMeshes = Math.max(maxLiveMeshes, liveMeshes());
            assertTrue(liveMeshes() <= 2 * DhGeographicLodData.MAX_TILES, "Published plus private owners must stay bounded");
            // The upload boundary owns its partial allocation until it successfully returns the mesh.
            try {
                onUpload.run();
                if (uploadCalls == failUpload) throw uploadFailure;
                return mesh;
            } catch (RuntimeException | Error failure) {
                try { mesh.close(); }
                catch (RuntimeException | Error cleanup) {
                    incomplete.add(mesh);
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        public DhGeographicLodView.DrawStats draw(Map<DhGeographicLodData.TileKey, DhGeographicLodView.Mesh> meshes,
                Matrix4f matrix, double x, double y, double z) {
            checkRenderThread();
            drawCalls++;
            Map<DhGeographicLodData.TileKey, FakeMesh> copy = new LinkedHashMap<>();
            meshes.forEach((key, mesh) -> {
                assertFalse(mesh.deleted(), "A retired or rejected allocation reached draw submission");
                copy.put(key, (FakeMesh) mesh);
            });
            lastDraw = Map.copyOf(copy);
            onDraw.run();
            return new DhGeographicLodView.DrawStats(meshes.size(), meshes.values().stream().mapToLong(m -> m.quads() * 4L).sum(), 123);
        }

        public void close() {
            checkRenderThread();
            closeCalls++;
            RuntimeException failure = null;
            for (var iterator = incomplete.iterator(); iterator.hasNext();) {
                var mesh = iterator.next();
                try { mesh.close(); iterator.remove(); }
                catch (RuntimeException cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            }
            if (failGlobalClose) {
                var cleanup = new InjectedFailure("global cleanup failure");
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            } else globalsDeleted = true;
            if (failure != null) throw failure;
        }

        public boolean deleted() { return (!globalsAllocated || globalsDeleted) && incomplete.isEmpty(); }
        public int pendingOwners() { return incomplete.size(); }
        int liveMeshes() { return (int) allocated.stream().filter(mesh -> !mesh.deleted()).count(); }
        List<FakeMesh> live() { return allocated.stream().filter(mesh -> !mesh.deleted()).toList(); }
    }
}
