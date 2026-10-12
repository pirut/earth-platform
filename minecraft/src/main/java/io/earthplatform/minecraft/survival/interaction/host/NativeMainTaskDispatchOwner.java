package io.earthplatform.minecraft.survival.interaction.host;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.thread.BlockableEventLoop;

/** Source-bound observation of ONE original doRunTask -> Runnable.run invocation.
 * Concrete NETWORK/SHUTDOWN owners must be fully prepaid, immutable-profile admitted and failure-retained.
 * A metadata-only ordinary-baseline observer cannot bind here because exact Throwables may retain payloads.
 * This observer itself allocates no task, schedules nothing and grants no shutdown/save readiness.
 */
public abstract class NativeMainTaskDispatchOwner {
    public enum Lane { NETWORK, SHUTDOWN }
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private final BlockableEventLoop<?> executor;
    private final Lane lane;
    private volatile Throwable firstFailure;
    protected NativeMainTaskDispatchOwner(BlockableEventLoop<?> exactExecutor, Lane lane) {
        if (exactExecutor == null || lane == null) throw new IllegalArgumentException("Exact native task owner required");
        this.executor = exactExecutor; this.lane = lane;
    }
    public final boolean matches(BlockableEventLoop<?> exact) { return exact == executor; }
    public final Lane lane() { return lane; }

    private static boolean nativeSite() {
        return WALKER.walk(frames -> {
            var path = frames.limit(4).toList();
            return path.size() == 4
                    && path.get(0).getDeclaringClass() == NativeMainTaskDispatchOwner.class
                    && path.get(0).getMethodName().equals("nativeSite")
                    && path.get(1).getDeclaringClass() == NativeMainTaskDispatchOwner.class
                    && path.get(1).getMethodName().equals("originalRun")
                    && path.get(2).getDeclaringClass() == BlockableEventLoop.class
                    && path.get(2).getMethodName().contains("earthplatform$observeTaskRun")
                    && path.get(3).getDeclaringClass() == BlockableEventLoop.class
                    && path.get(3).getMethodName().equals("doRunTask");
        });
    }

    public static void originalRun(BlockableEventLoop<?> executor, Runnable task,
            NativeMainTaskDispatchOwner network, NativeMainTaskDispatchOwner shutdown, Operation<Void> original) {
        if (!nativeSite()) throw new IllegalStateException("Original task dispatch callsite required");
        if (network != null && (!network.matches(executor) || network.lane != Lane.NETWORK)
                || shutdown != null && (!shutdown.matches(executor) || shutdown.lane != Lane.SHUTDOWN))
            throw new IllegalStateException("Original task dispatch owner changed");
        long networkReceipt = -1, shutdownReceipt = -1;
        Throwable bookkeeping = null;
        try { if (network != null) networkReceipt = network.beforeOriginal(task); }
        catch (Throwable failure) { bookkeeping = note(network, failure, bookkeeping); }
        try { if (shutdown != null) shutdownReceipt = shutdown.beforeOriginal(task); }
        catch (Throwable failure) { bookkeeping = note(shutdown, failure, bookkeeping); }
        Throwable primary = null;
        try {
            original.call(task); // Preserve accepted native work exactly once, even if an observer fails.
        } catch (Throwable failure) {
            primary = failure;
            if (network != null && networkReceipt >= 0) bookkeeping = notifyFailure(network, failure, bookkeeping);
            if (shutdown != null && shutdownReceipt >= 0) bookkeeping = notifyFailure(shutdown, failure, bookkeeping);
        }
        // Both observations occur after the same original invocation; no second task invocation exists.
        try { if (network != null && networkReceipt >= 0) network.afterOriginal(task, networkReceipt, primary); }
        catch (Throwable failure) { bookkeeping = note(network, failure, bookkeeping); }
        try { if (shutdown != null && shutdownReceipt >= 0) shutdown.afterOriginal(task, shutdownReceipt, primary); }
        catch (Throwable failure) { bookkeeping = note(shutdown, failure, bookkeeping); }
        if (primary != null) {
            if (bookkeeping != null && bookkeeping != primary) primary.addSuppressed(bookkeeping);
            throwUnchecked(primary);
        }
        // An observer failure is sticky in its own owner, not a replacement native exception.
        // Ordinary original exception/catch behavior and accepted task execution remain unchanged.
    }

    private static Throwable note(NativeMainTaskDispatchOwner owner, Throwable failure, Throwable prior) {
        owner.recordFailure(failure);
        return prior == null ? failure : prior;
    }
    private static Throwable notifyFailure(NativeMainTaskDispatchOwner owner, Throwable failure, Throwable prior) {
        owner.recordFailure(failure);
        return prior;
    }
    private synchronized void recordFailure(Throwable failure) {
        if (firstFailure == null) firstFailure = failure;
    }
    /** Read-only own-state failure port; concrete readiness must check it before claiming completion. */
    public final Throwable failure() { return firstFailure; }
    /** Return -1 for an unrelated task; never call a getter before exact task-class admission. */
    protected abstract long beforeOriginal(Runnable exactTask);
    protected abstract void afterOriginal(Runnable exactTask, long receipt, Throwable originalFailure);
    @SuppressWarnings("unchecked") private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T { throw (T) failure; }
}
