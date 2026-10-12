package io.earthplatform.minecraft.survival.render;

/** Thread-confined lifetime gate. Tokens are identities, never caller-supplied authority. */
final class SurvivalPageTransferGate {
    private final Thread thread = Thread.currentThread();
    private Object transferredOwner;
    private boolean diagnosticBorrowed, closed, borrowing;

    void requireOwner(Object token) {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Native page owner thread required");
        if (transferredOwner != token) throw new IllegalStateException("Native page caller ownership was transferred");
    }
    void requireOpen(Object token) {
        requireOwner(token);
        if (closed) throw new IllegalStateException("Native CPU page is closed");
    }
    void diagnosticBorrow() {
        requireOpen(null);
        diagnosticBorrowed = true; // No raw ByteBuffer alias can subsequently become an upload input.
    }
    void transfer(Object token) {
        requireOpen(null);
        if (token == null || diagnosticBorrowed || borrowing)
            throw new IllegalStateException("Native page with a raw diagnostic borrow cannot transfer");
        transferredOwner = token;
    }
    boolean callerOwnsCleanup() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Native page owner thread required");
        return transferredOwner == null;
    }
    void beginBorrow(Object token) {
        requireOpen(token);
        if (borrowing) throw new IllegalStateException("Nested native page upload borrow");
        borrowing = true;
    }
    void endBorrow(Object token) { requireOwner(token); borrowing = false; }
    void beginClose(Object token) {
        requireOwner(token);
        if (borrowing) throw new IllegalStateException("Native page bytes are borrowed by an active upload");
        closed = true;
    }
}
