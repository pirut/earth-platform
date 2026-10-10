package io.earthplatform.streaming;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.streaming.CubeStreamAdmission.Admission;
import io.earthplatform.streaming.CubeStreamAdmission.AdmissionToken;
import io.earthplatform.streaming.CubeStreamAdmission.Header;
import io.earthplatform.streaming.CubeStreamAdmission.InterestChange;
import io.earthplatform.streaming.CubeStreamAdmission.Lease;
import io.earthplatform.streaming.CubeStreamAdmission.LeaseView;
import io.earthplatform.streaming.CubeStreamAdmission.Lifecycle;
import io.earthplatform.streaming.CubeStreamAdmission.Rejection;
import io.earthplatform.streaming.CubeStreamAdmission.State;
import io.earthplatform.streaming.CubeStreamAdmission.View;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free tests; assertions are active without -ea. */
public final class StreamingTests {
    private static final WorldIdentity WORLD = new WorldIdentity(42, "earth", "1", "terrain-1", "blocks-1");
    private static final WorldIdentity OTHER = new WorldIdentity(42, "earth", "1", "terrain-2", "blocks-1");
    private static final CubePos A = new CubePos(-2, 553, -3);
    private static final CubePos B = new CubePos(2, -688, 3);
    private static final CubePos C = new CubePos(Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
    private static final long EPOCH = 7;
    private static int groups;

    private StreamingTests() { }

    public static void main(String[] arguments) throws Exception {
        run("identity, input validation and signed extremes", StreamingTests::validation);
        run("requested/loaded lifecycle, monotone revisions and close", StreamingTests::lifecycle);
        run("atomic replacement, retained leases and defensive outputs", StreamingTests::replacement);
        run("remove/re-add ABA, wrong epochs/worlds and foreign tokens", StreamingTests::abaAndIdentity);
        run("supersession, cancellation, retry and token replay", StreamingTests::supersession);
        run("atomic generation exhaustion and saturating arithmetic", StreamingTests::overflow);
        run("bounded hostile collections and throwing iterators", StreamingTests::hostileCollections);
        run("maximum lease budget and repeated supersession stay bounded", StreamingTests::maximumCapacity);
        run("reentrant reads and rejected mutations", StreamingTests::reentrancy);
        run("partial-install failure poisons gate and requires cleanup", StreamingTests::failure);
        run("lease-tagged delayed unload cannot delete re-added cube", StreamingTests::delayedUnload);
        run("retirement cannot overtake active installation", StreamingTests::retirementRace);
        run("close and replacement races observe poisoned installation", StreamingTests::closeFailureRaces);
        run("8-thread revision and interest stress", StreamingTests::concurrentStress);
        run("100,000 teleports with bounded active metadata", StreamingTests::teleportStress);
        run("10 seeds x 20,000 randomized reference operations", StreamingTests::randomized);
        System.out.println("PASS: " + groups + " test groups; no assertions depend on -ea");
    }

    private static void validation() {
        expect(NullPointerException.class, () -> new CubeStreamAdmission(null, EPOCH, 1));
        expect(IllegalArgumentException.class, () -> new CubeStreamAdmission(WORLD, -1, 1));
        expect(IllegalArgumentException.class, () -> new CubeStreamAdmission(WORLD, EPOCH, 0));
        expect(IllegalArgumentException.class, () -> new CubeStreamAdmission(WORLD, EPOCH, CubeStreamAdmission.MAX_LEASES + 1));
        expect(IllegalArgumentException.class, () -> new Header(WORLD, -1, A, 1, 0));
        expect(IllegalArgumentException.class, () -> new Header(WORLD, EPOCH, A, 0, 0));
        expect(IllegalArgumentException.class, () -> new Header(WORLD, EPOCH, A, 1, -1));
        expect(NullPointerException.class, () -> new Header(WORLD, EPOCH, null, 1, 0));
        expect(NullPointerException.class, () -> new Header(null, EPOCH, A, 1, 0));
        expect(IllegalArgumentException.class, () -> new Lease(A, -1));
        expect(IllegalArgumentException.class, () -> new Admission(Rejection.NONE, null));
        CubeStreamAdmission gate = gate(3);
        View before = gate.view();
        expect(NullPointerException.class, () -> gate.replaceInterest(null));
        expect(NullPointerException.class, () -> gate.admit(null));
        expect(NullPointerException.class, () -> gate.commit(null, () -> { }));
        expect(NullPointerException.class, () -> gate.cancel(null));
        equal(before, gate.view(), "invalid calls are state-neutral");
        List<CubePos> extremes = List.of(A, B, C);
        gate.replaceInterest(extremes);
        equal(extremes, gate.view().leases().stream().map(v -> v.lease().position()).toList(), "full signed cube domain");
        for (CubePos position : extremes) {
            AdmissionToken token = accepted(gate, header(gate, position, 0));
            equal(Rejection.NONE, gate.commit(token, () -> { }), "extreme position installs");
        }
        equal(WORLD, gate.world(), "world binding");
        equal(EPOCH, gate.connectionEpoch(), "epoch binding");
        equal(3, gate.maxLeases(), "configured bound");
    }

    private static void lifecycle() {
        CubeStreamAdmission gate = gate(2);
        equal(State.OPEN, gate.view().state(), "new gate open");
        equal(Rejection.UNSOLICITED_POSITION, gate.admit(new Header(WORLD, EPOCH, A, 1, 0)).rejection(), "no unsolicited admission");
        InterestChange change = gate.replaceInterest(List.of(A, B));
        equal(2, change.requested().size(), "two new requests");
        equal(0, change.retired().size(), "nothing retired");
        equal(Lifecycle.REQUESTED, find(gate, A).lifecycle(), "initial lifecycle");
        equal(-1L, find(gate, A).appliedRevision(), "revision zero is not confused with missing");
        AdmissionToken token = accepted(gate, header(gate, A, 0));
        equal(1, gate.view().metrics().pendingAdmissions(), "one reservation");
        AtomicInteger installs = new AtomicInteger();
        equal(Rejection.NONE, gate.commit(token, installs::incrementAndGet), "initial commit");
        equal(1, installs.get(), "one install");
        equal(1, gate.view().metrics().loadedLeases(), "one loaded");
        equal(Lifecycle.LOADED, find(gate, A).lifecycle(), "loaded lifecycle");
        equal(0L, find(gate, A).appliedRevision(), "zero revision applied");
        equal(Rejection.STALE_REVISION, gate.admit(header(gate, A, 0)).rejection(), "duplicate revision");
        token = accepted(gate, header(gate, A, Long.MAX_VALUE));
        equal(Rejection.NONE, gate.commit(token, installs::incrementAndGet), "maximum revision allowed");
        equal(Rejection.STALE_REVISION, gate.admit(header(gate, A, Long.MAX_VALUE - 1)).rejection(), "lower revision");
        equal(Rejection.STALE_REVISION, gate.admit(header(gate, A, Long.MAX_VALUE)).rejection(), "equal max revision");
        InterestChange closed = gate.close();
        equal(2, closed.retired().size(), "all leases returned for cleanup");
        equal(State.CLOSED, gate.view().state(), "closed state");
        equal(0, gate.view().metrics().activeLeases(), "nothing retained after close");
        equal(0, gate.close().retired().size(), "idempotent close");
        equal(Rejection.NOT_OPEN, gate.commit(token, installs::incrementAndGet), "closed commit");
        equal(Rejection.NOT_OPEN, gate.admit(new Header(WORLD, EPOCH, A, 1, 10)).rejection(), "closed admission");
        expect(IllegalStateException.class, () -> gate.replaceInterest(List.of(A)));
        equal(2, installs.get(), "invalid operations do not install");
    }

    private static void replacement() {
        CubeStreamAdmission gate = gate(2);
        List<CubePos> input = new ArrayList<>(List.of(A, B));
        InterestChange first = gate.replaceInterest(input);
        input.clear();
        equal(2, gate.view().metrics().activeLeases(), "input not retained");
        AdmissionToken token = accepted(gate, header(gate, A, 9));
        Lease oldA = find(gate, A).lease();
        View before = gate.view();
        expect(IllegalArgumentException.class, () -> gate.replaceInterest(List.of(A, B, C)));
        equal(before, gate.view(), "oversized update atomic");
        expect(IllegalArgumentException.class, () -> gate.replaceInterest(List.of(C, C)));
        equal(before, gate.view(), "duplicate update atomic");
        expect(NullPointerException.class, () -> gate.replaceInterest(Arrays.asList(C, null)));
        equal(before, gate.view(), "null update atomic");
        InterestChange next = gate.replaceInterest(List.of(C, A));
        equal(oldA, find(gate, A).lease(), "retained generation stable");
        equal(9L, find(gate, A).pendingRevision(), "retained reservation stable");
        equal(Rejection.NONE, gate.commit(token, () -> { }), "retained token valid");
        equal(List.of(B), next.retired().stream().map(v -> v.lease().position()).toList(), "correct retirements");
        equal(3L, next.requested().getFirst().generation(), "invalid replacements consume no generation");
        expect(UnsupportedOperationException.class, () -> first.requested().clear());
        expect(UnsupportedOperationException.class, () -> next.retired().clear());
        View frozen = gate.view();
        expect(UnsupportedOperationException.class, () -> frozen.leases().clear());
        gate.replaceInterest(List.of());
        equal(2, frozen.leases().size(), "view immutable snapshot");
        equal(9L, frozen.leases().get(1).appliedRevision(), "old view not live");
    }

    private static void abaAndIdentity() {
        CubeStreamAdmission gate = gate(1);
        gate.replaceInterest(List.of(A));
        Header oldHeader = header(gate, A, 99);
        AdmissionToken oldToken = accepted(gate, oldHeader);
        equal(Rejection.WRONG_WORLD, gate.admit(new Header(OTHER, EPOCH, A, 1, 100)).rejection(), "world mismatch");
        equal(Rejection.WRONG_EPOCH, gate.admit(new Header(WORLD, EPOCH + 1, A, 1, 100)).rejection(), "epoch mismatch");
        equal(Rejection.RETIRED_LEASE, gate.admit(new Header(WORLD, EPOCH, A, Long.MAX_VALUE, 100)).rejection(), "arbitrary generation mismatch");
        gate.replaceInterest(List.of());
        equal(Rejection.UNSOLICITED_POSITION, gate.commit(oldToken, () -> fail("retired install")), "retired token");
        gate.replaceInterest(List.of(A));
        equal(2L, find(gate, A).lease().generation(), "re-added lease generation changes");
        equal(Rejection.RETIRED_LEASE, gate.admit(oldHeader).rejection(), "delayed ABA packet rejected");
        equal(Rejection.RETIRED_LEASE, gate.commit(oldToken, () -> fail("ABA install")), "delayed ABA token rejected");
        equal(-1L, find(gate, A).appliedRevision(), "new lease has independent revision history");
        accepted(gate, header(gate, A, 0));
        CubeStreamAdmission foreign = gate(1);
        foreign.replaceInterest(List.of(A));
        AdmissionToken foreignToken = accepted(foreign, header(foreign, A, 100));
        equal(Rejection.FOREIGN_TOKEN, gate.commit(foreignToken, () -> fail("foreign install")), "foreign token rejected");
        check(!gate.cancel(foreignToken), "foreign token cannot cancel");
    }

    private static void supersession() {
        CubeStreamAdmission gate = gate(1);
        gate.replaceInterest(List.of(A));
        AdmissionToken first = accepted(gate, header(gate, A, 4));
        equal(Rejection.STALE_REVISION, gate.admit(header(gate, A, 4)).rejection(), "equal pending revision rejected");
        equal(Rejection.STALE_REVISION, gate.admit(header(gate, A, 3)).rejection(), "lower pending revision rejected");
        AdmissionToken newer = accepted(gate, header(gate, A, 8));
        equal(1, gate.view().metrics().pendingAdmissions(), "one pending slot despite supersession");
        equal(Rejection.SUPERSEDED_TOKEN, gate.commit(first, () -> fail("superseded install")), "superseded commit rejected");
        check(!gate.cancel(first), "superseded cancellation does not affect current token");
        check(gate.cancel(newer), "current cancellation");
        check(!gate.cancel(newer), "cancellation is not replayable");
        equal(0, gate.view().metrics().pendingAdmissions(), "reservation released");
        AdmissionToken retry = accepted(gate, header(gate, A, 8));
        equal(Rejection.SUPERSEDED_TOKEN, gate.commit(newer, () -> fail("old retry")), "same revision still needs new token");
        equal(Rejection.NONE, gate.commit(retry, () -> { }), "retry succeeds");
        equal(Rejection.SUPERSEDED_TOKEN, gate.commit(retry, () -> fail("replay install")), "token single use");
        AdmissionToken pending = accepted(gate, header(gate, A, 100));
        check(gate.cancel(pending), "high reservation cancelled");
        equal(Rejection.STALE_REVISION, gate.admit(header(gate, A, 8)).rejection(), "cancel never rewinds committed revision");
        AdmissionToken lowerRetry = accepted(gate, header(gate, A, 9));
        equal(Rejection.NONE, gate.commit(lowerRetry, () -> { }), "cancelled high revision need not permanently block lower valid update");
        equal(1L, gate.view().metrics().supersessions(), "supersession counter");
        equal(2L, gate.view().metrics().cancellations(), "cancellation counter");
    }

    private static void overflow() {
        CubeStreamAdmission gate = new CubeStreamAdmission(WORLD, EPOCH, 2, Long.MAX_VALUE - 1);
        View empty = gate.view();
        expect(IllegalStateException.class, () -> gate.replaceInterest(List.of(A, B)));
        equal(empty, gate.view(), "generation overflow atomic");
        gate.replaceInterest(List.of(A));
        equal(Long.MAX_VALUE, find(gate, A).lease().generation(), "last positive generation usable");
        AdmissionToken token = accepted(gate, header(gate, A, 0));
        View before = gate.view();
        expect(IllegalStateException.class, () -> gate.replaceInterest(List.of(B)));
        equal(before, gate.view(), "exhaustion does not retire old lease or invalidate token");
        gate.replaceInterest(List.of(A));
        equal(Rejection.NONE, gate.commit(token, () -> { }), "retained lease survives exhaustion");
        gate.replaceInterest(List.of());
        expect(IllegalStateException.class, () -> gate.replaceInterest(List.of(A)));
        equal(0, gate.view().metrics().activeLeases(), "exhaustion never wraps on re-add");
        equal(Long.MAX_VALUE, CubeStreamAdmission.saturatedAdd(Long.MAX_VALUE, 1), "saturation at max");
        equal(Long.MAX_VALUE, CubeStreamAdmission.saturatedAdd(Long.MAX_VALUE - 2, 3), "saturation crossing max");
        equal(Long.MAX_VALUE - 1, CubeStreamAdmission.saturatedAdd(Long.MAX_VALUE - 2, 1), "normal near max");
    }

    private static void hostileCollections() {
        CubeStreamAdmission gate = gate(2);
        gate.replaceInterest(List.of(A));
        View before = gate.view();
        AtomicInteger nextCalls = new AtomicInteger();
        Collection<CubePos> endless = new AbstractCollection<>() {
            @Override public int size() { throw new AssertionError("must not trust or call size"); }
            @Override public Iterator<CubePos> iterator() {
                return new Iterator<>() {
                    @Override public boolean hasNext() { return true; }
                    @Override public CubePos next() { return new CubePos(nextCalls.incrementAndGet(), 0, 0); }
                };
            }
        };
        expect(IllegalArgumentException.class, () -> gate.replaceInterest(endless));
        equal(2, nextCalls.get(), "stops traversal before fetching over-budget element");
        equal(before, gate.view(), "endless input rejected atomically");
        Collection<CubePos> throwing = collection(() -> { throw new IllegalArgumentException("iterator failure"); });
        expect(IllegalArgumentException.class, () -> gate.replaceInterest(throwing));
        equal(before, gate.view(), "throwing iterator atomic");
        for (boolean throwFromHasNext : List.of(false, true)) {
            Collection<CubePos> afterStaging = new AbstractCollection<>() {
                @Override public int size() { return 2; }
                @Override public Iterator<CubePos> iterator() {
                    return new Iterator<>() {
                        private int index;
                        @Override public boolean hasNext() {
                            if (index == 1 && throwFromHasNext) throw new IllegalArgumentException("hasNext after staging");
                            return index < 2;
                        }
                        @Override public CubePos next() {
                            if (index++ == 0) return B;
                            throw new IllegalArgumentException("next after staging");
                        }
                    };
                }
            };
            expect(IllegalArgumentException.class, () -> gate.replaceInterest(afterStaging));
            equal(before, gate.view(), "iterator failure after staging leaves state/counters unchanged");
        }
        gate.replaceInterest(List.of(B));
        equal(2L, find(gate, B).lease().generation(), "hostile input consumes no generation");
    }

    private static void reentrancy() {
        CubeStreamAdmission gate = gate(2);
        gate.replaceInterest(List.of(A));
        View before = gate.view();
        Collection<CubePos> nested = collection(() -> gate.replaceInterest(List.of(B)));
        expect(IllegalStateException.class, () -> gate.replaceInterest(nested));
        equal(before, gate.view(), "iterator cannot reenter mutation");
        AdmissionToken token = accepted(gate, header(gate, A, 1));
        equal(Rejection.NONE, gate.commit(token, () -> {
            equal(Lifecycle.REQUESTED, find(gate, A).lifecycle(), "reentrant read sees pre-commit metadata");
            expect(IllegalStateException.class, () -> gate.replaceInterest(List.of(B)));
            expect(IllegalStateException.class, gate::close);
            expect(IllegalStateException.class, () -> gate.cancel(token));
            expect(IllegalStateException.class, () -> gate.admit(header(gate, A, 2)));
            expect(IllegalStateException.class, () -> gate.commit(token, () -> { }));
            equal(1, gate.view().metrics().pendingAdmissions(), "nested rejections leave outer guard intact");
        }), "outer commit remains usable if installer handles nested rejection");
        equal(State.OPEN, gate.view().state(), "handled nested rejection is not failure");
        gate.replaceInterest(List.of(B));
        equal(B, gate.view().leases().getFirst().lease().position(), "guard released after successful commit");
    }

    private static void maximumCapacity() {
        CubeStreamAdmission gate = gate(CubeStreamAdmission.MAX_LEASES);
        List<CubePos> positions = new ArrayList<>(CubeStreamAdmission.MAX_LEASES + 1);
        for (int i = 0; i < CubeStreamAdmission.MAX_LEASES; i++) positions.add(new CubePos(i, -i, i));
        InterestChange change = gate.replaceInterest(positions);
        equal(CubeStreamAdmission.MAX_LEASES, change.requested().size(), "full supported lease budget");
        View before = gate.view();
        positions.add(A);
        expect(IllegalArgumentException.class, () -> gate.replaceInterest(positions));
        equal(before, gate.view(), "maximum-budget overflow atomic");
        CubePos first = positions.getFirst();
        AdmissionToken oldest = accepted(gate, new Header(WORLD, EPOCH, first, 1, 0));
        AdmissionToken latest = oldest;
        for (int revision = 1; revision <= 100_000; revision++) {
            latest = accepted(gate, new Header(WORLD, EPOCH, first, 1, revision));
        }
        equal(1, gate.view().metrics().pendingAdmissions(), "100,000 supersessions retain one pending slot");
        equal(100_000L, gate.view().metrics().supersessions(), "supersession count exact");
        equal(Rejection.SUPERSEDED_TOKEN, gate.commit(oldest, () -> fail("oldest supersession")), "oldest token remains invalid");
        equal(Rejection.NONE, gate.commit(latest, () -> { }), "latest of many supersessions commits");
        equal(CubeStreamAdmission.MAX_LEASES, gate.close().retired().size(), "maximum-budget cleanup");
        equal(0, gate.view().metrics().pendingAdmissions(), "closure releases pending references");
    }

    private static void failure() {
        for (boolean error : List.of(false, true)) {
            CubeStreamAdmission gate = gate(2);
            gate.replaceInterest(List.of(A, B));
            AdmissionToken first = accepted(gate, header(gate, A, 1));
            AdmissionToken second = accepted(gate, header(gate, B, 1));
            AtomicInteger sideEffect = new AtomicInteger();
            Throwable exact = error ? new AssertionError("partial install") : new IllegalStateException("partial install");
            Throwable thrown = expect(error ? AssertionError.class : IllegalStateException.class, () -> gate.commit(first, () -> {
                sideEffect.incrementAndGet();
                if (exact instanceof Error failure) throw failure;
                throw (RuntimeException) exact;
            }));
            check(thrown == exact, "original installer failure propagated");
            equal(1, sideEffect.get(), "external side effect is not claimed rolled back");
            equal(State.FAILED, gate.view().state(), "gate poisoned");
            equal(0, gate.view().metrics().pendingAdmissions(), "all pending tokens invalidated");
            equal(0L, gate.view().metrics().commits(), "failed install not counted as committed");
            equal(1L, gate.view().metrics().installerFailures(), "failure recorded");
            equal(2, gate.view().metrics().activeLeases(), "bounded metadata retained for cleanup");
            equal(Rejection.NOT_OPEN, gate.commit(second, () -> fail("post-failure install")), "other token blocked");
            equal(Rejection.NOT_OPEN, gate.admit(header(gate, B, 2)).rejection(), "post-failure admission blocked");
            expect(IllegalStateException.class, () -> gate.replaceInterest(List.of(C)));
            check(!gate.cancel(second), "poisoned token cannot reopen gate");
            equal(2, gate.close().retired().size(), "cleanup includes requested lease with possible partial install");
            equal(State.CLOSED, gate.view().state(), "failed gate can close");
        }
        CubeStreamAdmission gate = gate(1);
        gate.replaceInterest(List.of(A));
        AdmissionToken token = accepted(gate, header(gate, A, 0));
        expect(IllegalStateException.class, () -> gate.commit(token, gate::close));
        equal(State.FAILED, gate.view().state(), "unhandled reentrant mutation poisons installer");
        CubeStreamAdmission checkedGate = gate(1);
        checkedGate.replaceInterest(List.of(A));
        AdmissionToken checkedToken = accepted(checkedGate, header(checkedGate, A, 0));
        Exception checked = new Exception("undeclared checked failure");
        Throwable wrapped = expect(IllegalStateException.class,
                () -> checkedGate.commit(checkedToken, () -> StreamingTests.<RuntimeException>sneakyThrow(checked)));
        check(wrapped.getCause() == checked, "undeclared checked failure preserved as cause");
        equal(State.FAILED, checkedGate.view().state(), "undeclared checked exception also poisons gate");
        CubeStreamAdmission loadedGate = gate(1);
        loadedGate.replaceInterest(List.of(A));
        loadedGate.commit(accepted(loadedGate, header(loadedGate, A, 5)), () -> { });
        AdmissionToken update = accepted(loadedGate, header(loadedGate, A, 6));
        View before = loadedGate.view();
        expect(NullPointerException.class, () -> loadedGate.commit(update, null));
        equal(before, loadedGate.view(), "invalid installer leaves reservation and counters unchanged");
        expect(IllegalStateException.class, () -> loadedGate.commit(update, () -> { throw new IllegalStateException("update failed"); }));
        equal(5L, find(loadedGate, A).appliedRevision(), "failure preserves last successfully installed revision");
        equal(1L, loadedGate.view().metrics().commits(), "previous commit remains counted");
        equal(Lifecycle.LOADED, loadedGate.close().retired().getFirst().lifecycle(), "cleanup preserves previous loaded lifecycle");
    }

    private static void delayedUnload() {
        CubeStreamAdmission gate = gate(1);
        Map<CubePos, Lease> external = new HashMap<>();
        gate.replaceInterest(List.of(A));
        Lease oldLease = find(gate, A).lease();
        gate.commit(accepted(gate, header(gate, A, 0)), () -> external.put(A, oldLease));
        InterestChange retired = gate.replaceInterest(List.of());
        gate.replaceInterest(List.of(A));
        Lease newLease = find(gate, A).lease();
        gate.commit(accepted(gate, header(gate, A, 0)), () -> external.put(A, newLease));
        for (LeaseView view : retired.retired()) external.remove(view.lease().position(), view.lease());
        equal(newLease, external.get(A), "compare-and-remove old lease leaves current install");
        for (LeaseView view : gate.close().retired()) external.remove(view.lease().position(), view.lease());
        check(external.isEmpty(), "final cleanup unloads current lease");
    }

    private static void retirementRace() throws Exception {
        CubeStreamAdmission gate = gate(1);
        gate.replaceInterest(List.of(A));
        AdmissionToken token = accepted(gate, header(gate, A, 5));
        CountDownLatch installing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch tryingRetirement = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<InterestChange> change = new AtomicReference<>();
        Thread installer = new Thread(() -> {
            try {
                equal(Rejection.NONE, gate.commit(token, () -> { installing.countDown(); await(release); }), "concurrent install");
            } catch (Throwable failure) { error.set(failure); }
        }, "test-installer");
        Thread retirement = new Thread(() -> {
            tryingRetirement.countDown();
            try { change.set(gate.replaceInterest(List.of(B))); }
            catch (Throwable failure) { error.set(failure); }
        }, "test-retirement");
        installer.start();
        try {
            await(installing);
            retirement.start();
            await(tryingRetirement);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (retirement.getState() != Thread.State.BLOCKED && retirement.isAlive() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            equal(Thread.State.BLOCKED, retirement.getState(), "retirement actually waits for monitor");
            check(change.get() == null, "retirement not published during installation");
        } finally {
            release.countDown();
            installer.join(5_000);
            retirement.join(5_000);
        }
        check(!installer.isAlive() && !retirement.isAlive(), "both threads terminated");
        if (error.get() != null) throw new AssertionError("race worker failure", error.get());
        equal(5L, change.get().retired().getFirst().appliedRevision(), "retirement sees completed installation");
        equal(Lifecycle.LOADED, change.get().retired().getFirst().lifecycle(), "completed lease then retired");
        equal(Rejection.UNSOLICITED_POSITION, gate.commit(token, () -> fail("late install")), "retired token cannot install again");
    }

    private static void concurrentStress() throws Exception {
        CubeStreamAdmission gate = gate(8);
        gate.replaceInterest(List.of(A));
        AtomicLong revisions = new AtomicLong();
        List<Long> installed = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                jobs.add(executor.submit(() -> {
                    for (int i = 0; i < 2_000; i++) {
                        long revision = revisions.getAndIncrement();
                        Admission admission = gate.admit(new Header(WORLD, EPOCH, A, 1, revision));
                        if (admission.accepted()) gate.commit(admission.token(), () -> installed.add(revision));
                    }
                }));
            }
            for (Future<?> job : jobs) job.get(15, TimeUnit.SECONDS);
            long previous = -1;
            for (long value : installed) { check(value > previous, "concurrent installs strictly increasing"); previous = value; }
            equal(previous, find(gate, A).appliedRevision(), "final applied revision consistent");
            check(!installed.isEmpty(), "some concurrent snapshots installed");
            jobs.clear();
            for (int worker = 0; worker < 8; worker++) {
                final int id = worker;
                jobs.add(executor.submit(() -> {
                    for (int i = 0; i < 1_000; i++) {
                        gate.replaceInterest(List.of(new CubePos(id, i, -id), A));
                        check(gate.view().metrics().activeLeases() <= 8, "concurrent replacement bounded");
                    }
                }));
            }
            for (Future<?> job : jobs) job.get(15, TimeUnit.SECONDS);
        }
        equal(2, gate.view().metrics().activeLeases(), "atomic replacement never unions concurrent sets");
        gate.close();
    }

    private static void closeFailureRaces() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            boolean failInstall = scenario != 0;
            boolean close = scenario != 2;
            CubeStreamAdmission gate = gate(1);
            gate.replaceInterest(List.of(A));
            AdmissionToken token = accepted(gate, header(gate, A, 3));
            CountDownLatch installing = new CountDownLatch(1), release = new CountDownLatch(1), trying = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            AtomicReference<InterestChange> result = new AtomicReference<>();
            IllegalStateException expected = new IllegalStateException("partly installed");
            Thread installer = new Thread(() -> {
                try {
                    Runnable apply = () -> {
                        installing.countDown();
                        await(release);
                        if (failInstall) throw expected;
                    };
                    if (failInstall) check(expect(IllegalStateException.class, () -> gate.commit(token, apply)) == expected, "exact race failure");
                    else equal(Rejection.NONE, gate.commit(token, apply), "race commit completes");
                } catch (Throwable failure) { error.set(failure); }
            }, "failure-race-installer");
            Thread mutator = new Thread(() -> {
                trying.countDown();
                try {
                    if (close) result.set(gate.close());
                    else expect(IllegalStateException.class, () -> gate.replaceInterest(List.of(B)));
                } catch (Throwable failure) { error.set(failure); }
            }, "failure-race-mutator");
            installer.start();
            try {
                await(installing);
                mutator.start();
                await(trying);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (mutator.getState() != Thread.State.BLOCKED && mutator.isAlive() && System.nanoTime() < deadline) Thread.onSpinWait();
                equal(Thread.State.BLOCKED, mutator.getState(), "other mutation waits for live installer");
            } finally {
                release.countDown();
                installer.join(5_000);
                mutator.join(5_000);
            }
            check(!installer.isAlive() && !mutator.isAlive(), "failure race workers terminated");
            if (error.get() != null) throw new AssertionError("failure race worker", error.get());
            equal(close ? State.CLOSED : State.FAILED, gate.view().state(), "waiting mutation observes completed commit or failure");
            if (close) {
                equal(1, result.get().retired().size(), "close includes possibly partial install");
                equal(failInstall ? -1L : 3L, result.get().retired().getFirst().appliedRevision(), "close records only successful revision");
            } else {
                equal(A, gate.view().leases().getFirst().lease().position(), "failed gate refuses waiting replacement");
                equal(0, gate.view().metrics().pendingAdmissions(), "failed race clears token");
                gate.close();
            }
        }
    }

    private static void teleportStress() {
        CubeStreamAdmission gate = gate(4);
        AdmissionToken previous = null;
        long maximumGeneration = 0;
        for (int teleport = 0; teleport < 100_000; teleport++) {
            int offset = (teleport & 1) == 0 ? -1_000_000 : 1_000_000;
            List<CubePos> desired = List.of(new CubePos(offset, 553, teleport), new CubePos(offset, -688, teleport),
                    new CubePos(offset, -1, teleport), new CubePos(offset, 0, teleport));
            InterestChange change = gate.replaceInterest(desired);
            equal(4, change.requested().size(), "all teleport positions new");
            check(change.retired().size() <= 4, "bounded retired result");
            for (Lease lease : change.requested()) {
                check(lease.generation() > maximumGeneration, "generation never reused");
                maximumGeneration = lease.generation();
            }
            if (previous != null) {
                equal(Rejection.UNSOLICITED_POSITION, gate.commit(previous, () -> fail("stale teleport install")), "late teleport rejected");
            }
            for (int i = 0; i < desired.size(); i++) {
                AdmissionToken token = accepted(gate, header(gate, desired.get(i), teleport));
                if (i == 0) previous = token;
                else equal(Rejection.NONE, gate.commit(token, () -> { }), "current teleport accepted");
            }
            View view = gate.view();
            equal(4, view.metrics().activeLeases(), "active metadata stays bounded");
            equal(1, view.metrics().pendingAdmissions(), "pending metadata stays bounded");
            equal(3, view.metrics().loadedLeases(), "loaded metadata stays bounded");
        }
        equal(400_000L, gate.view().metrics().leasesCreated(), "teleport total leases");
        equal(399_996L, gate.view().metrics().leasesRetired(), "teleport retired count without retained tombstones");
        equal(4, gate.close().retired().size(), "only live metadata retained at completion");
    }

    private static void randomized() {
        for (int seed = 0; seed < 10; seed++) randomSequence(0x5EED_1234L + seed);
    }

    private static void randomSequence(long seed) {
        Random random = new Random(seed);
        CubeStreamAdmission gate = gate(8);
        Reference reference = new Reference();
        List<TokenPair> history = new ArrayList<>(); // fixed-size test history, never an unbounded archive
        List<CubePos> pool = new ArrayList<>();
        for (int i = 0; i < 16; i++) pool.add(new CubePos(i - 8, i % 2 == 0 ? 553 : -688, -i));
        for (int operation = 0; operation < 20_000; operation++) {
            int action = random.nextInt(10);
            if (action < 3) {
                List<CubePos> shuffled = new ArrayList<>(pool);
                Collections.shuffle(shuffled, random);
                List<CubePos> desired = List.copyOf(shuffled.subList(0, random.nextInt(9)));
                InterestChange expected = reference.replace(desired);
                equal(expected, gate.replaceInterest(desired), "reference replacement seed=" + seed + " operation=" + operation);
            } else if (action < 7) {
                CubePos position = pool.get(random.nextInt(pool.size()));
                ModelEntry entry = reference.entries.get(position);
                long generation = entry == null ? 1 : entry.generation;
                if (random.nextInt(8) == 0) generation++;
                long revision = random.nextInt(20);
                if (random.nextInt(200) == 0) revision = Long.MAX_VALUE;
                Header header = new Header(random.nextInt(20) == 0 ? OTHER : WORLD,
                        random.nextInt(20) == 0 ? EPOCH + 1 : EPOCH, position, generation, revision);
                Rejection expected = reference.admissionRejection(header);
                Admission actual = gate.admit(header);
                equal(expected, actual.rejection(), "reference admission");
                if (actual.accepted()) {
                    long id = ++reference.nextToken;
                    ModelEntry current = reference.entries.get(position);
                    current.pendingToken = id;
                    current.pendingRevision = revision;
                    if (history.size() == 64) history.removeFirst();
                    history.add(new TokenPair(actual.token(), header, id));
                }
            } else if (!history.isEmpty()) {
                TokenPair pair = history.get(random.nextInt(history.size()));
                Rejection expected = reference.tokenRejection(pair);
                if (action == 9) {
                    equal(expected == Rejection.NONE, gate.cancel(pair.actual()), "reference cancellation");
                    if (expected == Rejection.NONE) reference.entries.get(pair.header().position()).clearPending();
                } else {
                    AtomicInteger called = new AtomicInteger();
                    equal(expected, gate.commit(pair.actual(), called::incrementAndGet), "reference commit");
                    equal(expected == Rejection.NONE ? 1 : 0, called.get(), "only accepted commit invokes installer");
                    if (expected == Rejection.NONE) {
                        ModelEntry current = reference.entries.get(pair.header().position());
                        current.applied = pair.header().revision();
                        current.clearPending();
                    }
                }
            }
            reference.check(gate.view());
        }
        equal(reference.entries.size(), gate.close().retired().size(), "reference final cleanup");
    }

    private record TokenPair(AdmissionToken actual, Header header, long id) { }
    private static final class ModelEntry {
        final long generation;
        long applied = -1, pendingRevision = -1, pendingToken = -1;
        ModelEntry(long generation) { this.generation = generation; }
        void clearPending() { pendingRevision = -1; pendingToken = -1; }
    }

    /** Independent scalar/set model; no use of production mutation or validation helpers. */
    private static final class Reference {
        final Map<CubePos, ModelEntry> entries = new LinkedHashMap<>();
        long generation, nextToken;

        InterestChange replace(List<CubePos> desired) {
            Set<CubePos> keep = new HashSet<>(desired);
            List<LeaseView> retired = new ArrayList<>();
            for (Map.Entry<CubePos, ModelEntry> item : entries.entrySet()) {
                if (!keep.contains(item.getKey())) retired.add(view(item.getKey(), item.getValue()));
            }
            entries.keySet().removeIf(position -> !keep.contains(position));
            List<Lease> requested = new ArrayList<>();
            Map<CubePos, ModelEntry> reordered = new LinkedHashMap<>();
            for (CubePos position : desired) {
                ModelEntry entry = entries.get(position);
                if (entry == null) {
                    entry = new ModelEntry(++generation);
                    requested.add(new Lease(position, generation));
                }
                reordered.put(position, entry);
            }
            entries.clear();
            entries.putAll(reordered);
            return new InterestChange(requested, retired);
        }

        Rejection common(Header header) {
            if (!WORLD.equals(header.world())) return Rejection.WRONG_WORLD;
            if (header.connectionEpoch() != EPOCH) return Rejection.WRONG_EPOCH;
            ModelEntry entry = entries.get(header.position());
            if (entry == null) return Rejection.UNSOLICITED_POSITION;
            if (entry.generation != header.leaseGeneration()) return Rejection.RETIRED_LEASE;
            return Rejection.NONE;
        }
        Rejection admissionRejection(Header header) {
            Rejection common = common(header);
            if (common != Rejection.NONE) return common;
            ModelEntry entry = entries.get(header.position());
            return header.revision() <= Math.max(entry.applied, entry.pendingRevision)
                    ? Rejection.STALE_REVISION : Rejection.NONE;
        }
        Rejection tokenRejection(TokenPair pair) {
            Rejection common = common(pair.header());
            if (common != Rejection.NONE) return common;
            ModelEntry entry = entries.get(pair.header().position());
            if (entry.pendingToken != pair.id()) return Rejection.SUPERSEDED_TOKEN;
            return pair.header().revision() <= entry.applied ? Rejection.STALE_REVISION : Rejection.NONE;
        }
        void check(View actual) {
            List<LeaseView> expected = new ArrayList<>();
            int loaded = 0, pending = 0;
            for (Map.Entry<CubePos, ModelEntry> item : entries.entrySet()) {
                expected.add(view(item.getKey(), item.getValue()));
                if (item.getValue().applied >= 0) loaded++;
                if (item.getValue().pendingToken >= 0) pending++;
            }
            equal(expected, actual.leases(), "reference complete state");
            equal(entries.size(), actual.metrics().activeLeases(), "reference active gauge");
            equal(loaded, actual.metrics().loadedLeases(), "reference loaded gauge");
            equal(pending, actual.metrics().pendingAdmissions(), "reference pending gauge");
            equal(State.OPEN, actual.state(), "reference stays open");
        }
        private static LeaseView view(CubePos position, ModelEntry entry) {
            return new LeaseView(new Lease(position, entry.generation),
                    entry.applied < 0 ? Lifecycle.REQUESTED : Lifecycle.LOADED, entry.applied, entry.pendingRevision);
        }
    }

    private static CubeStreamAdmission gate(int maximum) { return new CubeStreamAdmission(WORLD, EPOCH, maximum); }
    private static LeaseView find(CubeStreamAdmission gate, CubePos position) {
        return gate.view().leases().stream().filter(v -> v.lease().position().equals(position)).findFirst().orElseThrow();
    }
    private static Header header(CubeStreamAdmission gate, CubePos position, long revision) {
        return new Header(WORLD, EPOCH, position, find(gate, position).lease().generation(), revision);
    }
    private static AdmissionToken accepted(CubeStreamAdmission gate, Header header) {
        Admission result = gate.admit(header);
        equal(Rejection.NONE, result.rejection(), "admission expected");
        return result.token();
    }
    private static Collection<CubePos> collection(Runnable duringIteration) {
        return new AbstractCollection<>() {
            @Override public int size() { return 1; }
            @Override public Iterator<CubePos> iterator() {
                duringIteration.run();
                return List.of(A).iterator();
            }
        };
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "latch timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void run(String name, Checked test) throws Exception {
        test.run();
        groups++;
        System.out.println("PASS " + name);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static Throwable expect(Class<? extends Throwable> type, Runnable operation) {
        try { operation.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return failure;
            throw new AssertionError("Expected " + type.getName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getName());
    }
    private static void check(boolean condition, String message) { if (!condition) fail(message); }
    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) fail(message + ": expected=" + expected + ", actual=" + actual);
    }
    private static void fail(String message) { throw new AssertionError(message); }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T { throw (T) throwable; }
}
