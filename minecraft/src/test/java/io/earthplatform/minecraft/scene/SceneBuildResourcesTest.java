package io.earthplatform.minecraft.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

/** Pure ownership journal tests with fallible staged resources instead of native allocations. */
class SceneBuildResourcesTest {
    @ParameterizedTest(name = "initialization failure after {0} allocations, kind {1}")
    @MethodSource("initializationFailures")
    void retainedOwnerSurvivesEveryPartialInitializationFailure(int failAfter, boolean error) {
        var resources = new SceneBuildResources();
        var closes = new ArrayList<String>();
        var staged = new StagedResource(resources, closes);
        resources.retain(staged);
        Throwable failure = error ? new AssertionError("allocation failed")
                : new IllegalStateException("allocation failed");

        assertSame(failure, assertThrows(Throwable.class, () -> staged.initialize(failAfter, failure)));
        assertEquals(1, resources.size(), "The owner must remain retained after fallible initialization");
        assertEquals(failAfter, staged.allocated.size());
        assertTrue(closes.isEmpty(), "Initialization does not discard its cleanup owner");
        resources.close();
        assertEquals(staged.allocated, closes);
        assertEquals(0, resources.size());
        resources.close();
        assertEquals(failAfter, closes.size(), "Successful cleanup cannot be repeated");
    }

    private static Stream<Arguments> initializationFailures() {
        return Stream.of(0, 1, 2, 3).flatMap(stage ->
                Stream.of(false, true).map(error -> Arguments.of(stage, error)));
    }

    @Test void closesAllResourcesInRetentionOrderAndRemovesEverySuccess() {
        var resources = new SceneBuildResources();
        var events = new ArrayList<String>();
        resources.retain(() -> events.add("cpu"));
        resources.retain(() -> events.add("mesh"));
        resources.retain(() -> events.add("gpu"));
        assertEquals(3, resources.size());
        resources.close();
        assertEquals(List.of("cpu", "mesh", "gpu"), events);
        assertEquals(0, resources.size());
        resources.close();
        assertEquals(List.of("cpu", "mesh", "gpu"), events);
    }

    @ParameterizedTest(name = "single cleanup failure at resource {0}")
    @MethodSource("resourceIndices")
    void closesRemainingResourcesAndRetriesOnlyTheFailedOwner(int failureIndex) {
        var resources = new SceneBuildResources();
        var events = new ArrayList<String>();
        var first = new FakeResource("first", events);
        var second = new FakeResource("second", events);
        var third = new FakeResource("third", events);
        List<FakeResource> owners = List.of(first, second, third);
        owners.forEach(resources::retain);
        var failure = new IllegalStateException("cleanup failed");
        owners.get(failureIndex).failure = failure;

        assertSame(failure, assertThrows(IllegalStateException.class, resources::close));
        assertEquals(List.of("first", "second", "third"), events);
        assertEquals(1, resources.size());
        assertEquals(1, first.attempts);
        assertEquals(1, second.attempts);
        assertEquals(1, third.attempts);
        owners.get(failureIndex).failure = null;
        resources.close();
        assertEquals(0, resources.size());
        assertEquals(4, events.size());
        assertEquals(owners.get(failureIndex).name, events.get(3));
        for (int index = 0; index < owners.size(); index++)
            assertEquals(index == failureIndex ? 2 : 1, owners.get(index).attempts);
        resources.close();
        assertEquals(4, events.size());
    }

    private static Stream<Integer> resourceIndices() { return Stream.of(0, 1, 2); }

    @Test void allRuntimeAndErrorFailuresAreAttemptedAggregatedAndRetainedForRetry() {
        var resources = new SceneBuildResources();
        var events = new ArrayList<String>();
        var first = new FakeResource("first", events);
        var second = new FakeResource("second", events);
        var third = new FakeResource("third", events);
        var fourth = new FakeResource("fourth", events);
        first.failure = new AssertionError("first failed");
        second.failure = new IllegalStateException("second failed");
        fourth.failure = new AssertionError("fourth failed");
        resources.retain(first);
        resources.retain(second);
        resources.retain(third);
        resources.retain(fourth);

        assertSame(first.failure, assertThrows(AssertionError.class, resources::close));
        assertArrayEquals(new Throwable[]{second.failure, fourth.failure}, first.failure.getSuppressed());
        assertEquals(List.of("first", "second", "third", "fourth"), events);
        assertEquals(3, resources.size());

        first.failure = null;
        fourth.failure = null;
        assertSame(second.failure, assertThrows(IllegalStateException.class, resources::close));
        assertEquals(List.of("first", "second", "third", "fourth", "first", "second", "fourth"), events);
        assertEquals(1, resources.size());
        assertEquals(1, third.attempts, "The successful owner is never retried");

        assertSame(second.failure, assertThrows(IllegalStateException.class, resources::close));
        assertEquals(1, resources.size(), "Repeated close failure must keep the resource retained");
        assertEquals(3, second.attempts);
        second.failure = null;
        resources.close();
        assertEquals(0, resources.size());
        assertEquals(4, second.attempts);
        assertEquals(2, first.attempts);
        assertEquals(2, fourth.attempts);
        resources.close();
        assertEquals(9, events.size());
    }

    @Test void runtimePrimaryRetainsSuppressedErrorAndStillClosesFollowingOwner() {
        var resources = new SceneBuildResources();
        var events = new ArrayList<String>();
        var first = new FakeResource("first", events);
        var second = new FakeResource("second", events);
        var third = new FakeResource("third", events);
        first.failure = new IllegalStateException("runtime");
        second.failure = new AssertionError("error");
        resources.retain(first);
        resources.retain(second);
        resources.retain(third);
        assertSame(first.failure, assertThrows(IllegalStateException.class, resources::close));
        assertArrayEquals(new Throwable[]{second.failure}, first.failure.getSuppressed());
        assertEquals(List.of("first", "second", "third"), events);
        assertEquals(2, resources.size());
        first.failure = null;
        second.failure = null;
        resources.close();
        assertEquals(0, resources.size());
    }

    @Test void sameThrowableFromMultipleOwnersDoesNotPreventLaterCleanup() {
        var resources = new SceneBuildResources();
        var events = new ArrayList<String>();
        var first = new FakeResource("first", events);
        var second = new FakeResource("second", events);
        var third = new FakeResource("third", events);
        var shared = new IllegalStateException("shared cleanup failure");
        first.failure = shared;
        second.failure = shared;
        resources.retain(first);
        resources.retain(second);
        resources.retain(third);
        assertSame(shared, assertThrows(IllegalStateException.class, resources::close));
        assertEquals(0, shared.getSuppressed().length);
        assertEquals(List.of("first", "second", "third"), events);
        assertEquals(2, resources.size());
        first.failure = null;
        second.failure = null;
        resources.close();
        assertEquals(0, resources.size());
    }

    @Test void publishedOwnershipTransferDoesNotCloseTransferredResources() {
        var resources = new SceneBuildResources();
        var events = new ArrayList<String>();
        var cpu = new FakeResource("cpu", events);
        var gpu = new FakeResource("gpu", events);
        resources.retain(cpu);
        resources.retain(gpu);
        resources.releaseToPublishedOwner();
        assertEquals(0, resources.size());
        assertTrue(events.isEmpty());
        resources.close();
        resources.close();
        assertTrue(events.isEmpty(), "Closing the old journal cannot close published resources");
        cpu.close();
        gpu.close();
        assertEquals(List.of("cpu", "gpu"), events);
        assertEquals(1, cpu.attempts);
        assertEquals(1, gpu.attempts);
    }

    @Test void cleanupSuccessPermanentlyRejectsNewRetentionAndOwnershipTransfer() {
        var resources = new SceneBuildResources();
        var attempts = new AtomicInteger();
        resources.retain(attempts::incrementAndGet);
        resources.close();
        assertEquals(1, attempts.get());
        assertThrows(IllegalStateException.class, () -> resources.retain(attempts::incrementAndGet));
        assertThrows(IllegalStateException.class, resources::releaseToPublishedOwner);
        assertEquals(0, resources.size());
        resources.close();
        assertEquals(1, attempts.get());
    }

    @Test void cleanupFailureRejectsNewRetentionAndTransferWithoutLosingFailedOwnership() {
        var resources = new SceneBuildResources();
        var events = new ArrayList<String>();
        var resource = new FakeResource("pending", events);
        resource.failure = new IllegalStateException("cleanup failed");
        resources.retain(resource);
        assertThrows(IllegalStateException.class, resources::close);
        assertThrows(IllegalStateException.class, () -> resources.retain(() -> fail("Must not be retained")));
        assertThrows(IllegalStateException.class, resources::releaseToPublishedOwner);
        assertEquals(1, resources.size());
        assertEquals(List.of("pending"), events);
        resource.failure = null;
        resources.close();
        assertEquals(0, resources.size());
        assertEquals(List.of("pending", "pending"), events);
        assertThrows(IllegalStateException.class, resources::releaseToPublishedOwner);
    }

    @Test void emptyCleanupAlsoRetiresTheJournal() {
        var resources = new SceneBuildResources();
        resources.close();
        assertEquals(0, resources.size());
        assertThrows(IllegalStateException.class, () -> resources.retain(() -> {}));
        assertThrows(IllegalStateException.class, resources::releaseToPublishedOwner);
        resources.close();
    }

    @Test void wrongThreadCannotRetainTransferInspectOrCloseBeforeAnySideEffect() throws Exception {
        var resources = new SceneBuildResources();
        var calls = new AtomicInteger();
        resources.retain(calls::incrementAndGet);
        assertOwnerFailure(() -> resources.retain(calls::incrementAndGet));
        assertOwnerFailure(() -> resources.retain(null));
        assertOwnerFailure(resources::releaseToPublishedOwner);
        assertOwnerFailure(resources::size);
        assertOwnerFailure(resources::close);
        assertEquals(1, resources.size());
        assertEquals(0, calls.get());
        resources.retain(calls::incrementAndGet);
        assertEquals(2, resources.size(), "Wrong-thread close must not retire the owner journal");
        resources.close();
        assertEquals(2, calls.get());
        assertEquals(0, resources.size());
    }

    @Test void nullRetentionIsRejectedWithoutChangingExistingOwnership() {
        var resources = new SceneBuildResources();
        var calls = new AtomicInteger();
        resources.retain(calls::incrementAndGet);
        assertThrows(NullPointerException.class, () -> resources.retain(null));
        assertEquals(1, resources.size());
        resources.close();
        assertEquals(1, calls.get());
    }

    private static void assertOwnerFailure(Runnable action) throws InterruptedException {
        var thrown = new AtomicReference<Throwable>();
        Thread worker = Thread.ofPlatform().daemon().start(() -> {
            try { action.run(); } catch (Throwable failure) { thrown.set(failure); }
        });
        worker.join(5000);
        assertFalse(worker.isAlive(), "Thread-affinity check did not return");
        var failure = assertInstanceOf(IllegalStateException.class, thrown.get());
        assertTrue(failure.getMessage().contains("owner thread"));
    }

    private static final class FakeResource implements SceneBuildResources.Resource {
        private final String name;
        private final List<String> events;
        private Throwable failure;
        private int attempts;

        private FakeResource(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override public void close() {
            attempts++;
            events.add(name);
            throwUnchecked(failure);
        }
    }

    private static final class StagedResource implements SceneBuildResources.Resource {
        private final SceneBuildResources journal;
        private final List<String> allocated = new ArrayList<>();
        private final List<String> closed;

        private StagedResource(SceneBuildResources journal, List<String> closed) {
            this.journal = journal;
            this.closed = closed;
        }

        private void initialize(int failAfter, Throwable failure) {
            assertEquals(1, journal.size(), "Retain the cleanup owner before starting initialization");
            for (int index = 0; index < 3; index++) {
                if (index == failAfter) throwUnchecked(failure);
                allocated.add("allocation:" + index);
            }
            if (failAfter == 3) throwUnchecked(failure);
        }

        @Override public void close() { closed.addAll(allocated); }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
