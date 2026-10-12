package io.earthplatform.minecraft.coregate;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * One bounded, presentation-only previous revision. This owns opaque native/far retention tickets,
 * never authority to collide, raycast, edit, acknowledge readiness, or advance the network protocol.
 * Acquisition registers each returned ticket before any following fallible operation. Failed
 * cleanup stays reachable for retry. The existing session frame fence surrounds all uses.
 */
public final class RollingEditPresentation<N, F> implements AutoCloseable {
    public record Stamp(long epoch, long publication, long resources, long coverage, long revision, int meshes, String manifestDigest) {
        public Stamp {
            if (manifestDigest == null || !manifestDigest.matches("[0-9a-f]{64}") || epoch < 0 || publication <= 0 || resources <= 0 || coverage <= 0 || revision < 0
                    || meshes < 1 || meshes > 28) throw new IllegalArgumentException("Invalid bounded frozen presentation stamp");
        }
    }
    public interface Driver<N, F> {
        N captureNative(); F captureFar();
        boolean nativeCurrent(N nativeTicket); boolean farCurrent(F farTicket);
        void closeNative(N nativeTicket); void closeFar(F farTicket);
    }
    private final Thread owner = Thread.currentThread();
    private final Driver<N, F> driver;
    private N nativeTicket;
    private F farTicket;
    private Stamp stamp;
    private boolean frozen, closed, acquiring, captureStackActive, retiring;
    private long captures, draws, invalidations;

    public RollingEditPresentation(Driver<N, F> driver) { this.driver = Objects.requireNonNull(driver); }

    public void capture(Stamp wanted) {
        checkOwner(); Objects.requireNonNull(wanted);
        if (closed || captureStackActive || retiring || acquiring || nativeTicket != null || farTicket != null || stamp != null)
            throw new IllegalStateException("Only one retained edit presentation is allowed");
        captureStackActive = true; acquiring = true; stamp = wanted;
        try {
            nativeTicket = Objects.requireNonNull(driver.captureNative(), "Native presentation ticket required");
            requireAcquiring(wanted);
            farTicket = Objects.requireNonNull(driver.captureFar(), "Far presentation ticket required");
            requireAcquiring(wanted);
            if (!driver.nativeCurrent(nativeTicket) || !driver.farCurrent(farTicket))
                throw new IllegalStateException("Presentation changed during capture");
            requireAcquiring(wanted);
            frozen = true; captures = increment(captures);
        } catch (RuntimeException | Error failure) {
            frozen = false;
            // Returned owners intentionally remain registered. Session shutdown retries retire().
            throw failure;
        } finally { acquiring = false; captureStackActive = false; }
    }
    private void requireAcquiring(Stamp expected) {
        if (closed || !acquiring || stamp != expected) throw new IllegalStateException("Presentation capture was cancelled");
    }
    public boolean current() {
        checkOwner();
        if (!frozen || closed || nativeTicket == null || farTicket == null) return false;
        N n = nativeTicket; F f = farTicket; Stamp captured = stamp;
        try {
            if (!driver.nativeCurrent(n)) { invalidate(); return false; }
            if (!sameLiveTickets(n, f, captured)) return false;
            if (!driver.farCurrent(f)) { invalidate(); return false; }
            return sameLiveTickets(n, f, captured);
        } catch (RuntimeException | Error failure) { invalidate(); throw failure; }
    }
    private boolean sameLiveTickets(N n, F f, Stamp expected) {
        return frozen && !closed && nativeTicket == n && farTicket == f && stamp == expected;
    }
    /** Validation brackets the real paired submissions; false never attests current terrain. */
    public boolean draw(BiFunction<N, F, Boolean> submissions) {
        checkOwner(); Objects.requireNonNull(submissions);
        if (!current()) return false;
        N n = nativeTicket; F f = farTicket; Stamp captured = stamp;
        try {
            if (!Boolean.TRUE.equals(submissions.apply(n, f))) { invalidate(); return false; }
            if (nativeTicket != n || farTicket != f || stamp != captured || !current()) return false;
            draws = increment(draws); return true;
        } catch (RuntimeException | Error failure) { invalidate(); throw failure; }
    }
    /** No ticket destruction here; can be called under the session's off-thread reload fence. */
    public void invalidate() { frozen = false; acquiring = false; invalidations = increment(invalidations); }
    public boolean frozen() { checkOwner(); return frozen && !closed; }
    public boolean retainsOwners() { checkOwner(); return nativeTicket != null || farTicket != null; }
    public int retainedTicketOwners() { checkOwner(); return (nativeTicket == null ? 0 : 1) + (farTicket == null ? 0 : 1); }
    public Stamp stamp() { checkOwner(); return stamp; }
    public long captures() { checkOwner(); return captures; }
    public long draws() { checkOwner(); return draws; }
    public long invalidations() { checkOwner(); return invalidations; }

    /** Ends this presentation; all cleanup is attempted, and failed owners survive for retry. */
    public void retire() {
        checkOwner(); invalidate();
        if (retiring) return;
        retiring = true;
        try {
            Throwable failure = null;
            if (nativeTicket != null) try { driver.closeNative(nativeTicket); nativeTicket = null; }
            catch (RuntimeException | Error e) { failure = e; }
            if (farTicket != null) try { driver.closeFar(farTicket); farTicket = null; }
            catch (RuntimeException | Error e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            if (nativeTicket == null && farTicket == null) stamp = null;
            if (failure instanceof RuntimeException e) throw e;
            if (failure instanceof Error e) throw e;
        } finally { retiring = false; }
    }
    @Override public void close() { checkOwner(); closed = true; retire(); }
    private void checkOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("Presentation render owner required"); }
    private static long increment(long n) { return n == Long.MAX_VALUE ? n : n + 1; }
}
