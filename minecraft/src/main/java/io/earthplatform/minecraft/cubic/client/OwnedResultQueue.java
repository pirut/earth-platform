package io.earthplatform.minecraft.cubic.client;

import java.util.AbstractQueue;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Worker-to-renderer ownership transfer. Polling transfers ownership to the consumer; closing
 * releases queued results and consumes/disposes every later offer, even after the world is gone.
 */
final class OwnedResultQueue<T> extends AbstractQueue<T> implements AutoCloseable {
    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
    private final Consumer<? super T> dispose;
    private volatile boolean closed;

    OwnedResultQueue(Consumer<? super T> dispose) { this.dispose = Objects.requireNonNull(dispose); }

    /** Always accepts ownership; after closure the value is disposed rather than queued. */
    @Override public boolean offer(T value) {
        Objects.requireNonNull(value);
        synchronized (this) {
            if (!closed) { queue.add(value); return true; }
        }
        dispose.accept(value);
        return true;
    }

    boolean isClosed() { return closed; }
    @Override public T poll() { return queue.poll(); }
    @Override public T peek() { return queue.peek(); }
    @Override public int size() { return queue.size(); }
    @Override public boolean isEmpty() { return queue.isEmpty(); }
    @Override public Iterator<T> iterator() { return Collections.unmodifiableCollection(queue).iterator(); }

    @Override public void close() {
        // Publication and closure share the lock; no producer can enqueue after the drain.
        synchronized (this) { closed = true; }
        T value;
        while ((value = queue.poll()) != null) dispose.accept(value);
    }
}
