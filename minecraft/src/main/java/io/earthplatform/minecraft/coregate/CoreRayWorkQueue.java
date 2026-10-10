package io.earthplatform.minecraft.coregate;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Bounded CPU-only planning. Cancellation never releases a physical worker slot early. */
final class CoreRayWorkQueue implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    CoreRayWorkQueue(){executor=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"earth-core-ray-planner");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());executor.allowCoreThreadTimeOut(true);}
    <T> CompletableFuture<T> submit(AtomicBoolean cancelled,Supplier<T> work){
        Objects.requireNonNull(cancelled);Objects.requireNonNull(work);var result=new CompletableFuture<T>();
        executor.execute(()->{try{if(cancelled.get())throw new CancellationException("Core ray session retired");T value=work.get();if(cancelled.get())throw new CancellationException("Core ray session retired");result.complete(value);}catch(Throwable failure){result.completeExceptionally(failure);}});
        return result;
    }
    int running(){return executor.getActiveCount();}int queued(){return executor.getQueue().size();}
    boolean terminated(){return executor.isTerminated();}
    /** Tests/explicit process disposal only; world retirement signals its own cancellation flag. */
    public void close(){executor.shutdown();}
}
