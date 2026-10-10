package io.earthplatform.core;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation. Providers must settle their future after work has really stopped. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public boolean isCancelled() { return cancelled.get(); }
    public void throwIfCancelled() { if (isCancelled()) throw new CancellationException("Cube no longer requested"); }
    void cancel() { cancelled.set(true); }
}
