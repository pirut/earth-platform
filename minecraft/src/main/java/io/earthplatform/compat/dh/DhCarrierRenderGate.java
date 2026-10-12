package io.earthplatform.compat.dh;

import java.util.Objects;

/** Exact-object, single-carrier ownership. Normal DH worlds always pass through unchanged. */
public final class DhCarrierRenderGate implements AutoCloseable {
    public interface Resources extends AutoCloseable {
        /** One logical engine composition, including its own near/far depth passes. */
        void draw(Object transientRenderContext);
        @Override void close();
    }
    public static final class Lease {
        private final Object runtime,level;private final String world;private final long epoch,resources;
        private Lease(Object runtime,Object level,String world,long epoch,long resources){this.runtime=runtime;this.level=level;this.world=world;this.epoch=epoch;this.resources=resources;}
        public String world(){return world;}public long epoch(){return epoch;}public long resourceGeneration(){return resources;}
    }
    public enum Result { PASS_THROUGH, CANCELLED, SUBMITTED }
    public record Counters(boolean ownsCarrier,boolean fenced,boolean cleanupPending,long submissions,
                           long duplicateFrames,long cancelledOpaque,long cancelledDeferred,long passedThrough,String failure) { }
    private final Thread owner=Thread.currentThread();private final Object fence=new Object();
    private Lease lease;private Resources resources;private boolean fenced,closing,busy,cleanupBusy;
    private long lastFrame=Long.MIN_VALUE,submissions,duplicates,opaque,deferred,passed;private String failure="";
    /** Ownership transfers only after this succeeds; caller retains a failed constructor's resources. */
    public Lease acquire(InstalledDhResolver.Runtime runtime,Object exactCarrier,String world,long epoch,long resourceGeneration,Resources owned){
        checkOwner();Objects.requireNonNull(runtime);Objects.requireNonNull(exactCarrier);Objects.requireNonNull(world);Objects.requireNonNull(owned);
        if(world.isBlank()||world.length()>256||epoch<0||resourceGeneration<=0)throw new IllegalArgumentException("Exact bounded carrier identity required");
        synchronized(fence){require(lease==null&&!busy,"Previous carrier ownership must be completely released");
            lease=new Lease(runtime.apiClass(),exactCarrier,world,epoch,resourceGeneration);resources=owned;
            fenced=false;closing=false;failure="";lastFrame=Long.MIN_VALUE;return lease;}
    }
    /**
     * cancel must set the actual DH event flag before any engine callback is invoked.
     * An external frame ID is supplied by the actual client frame event, not by DH callback count.
     */
    public Result opaque(Object level,long frameId,long currentResourceGeneration,boolean alreadyCancelled,
                         boolean supportedMode,Runnable cancel,Object context){
        checkOwner();Objects.requireNonNull(cancel);
        synchronized(fence){
            if(lease==null||lease.level!=level){passed++;return Result.PASS_THROUGH;}
            cancel(cancel);opaque++;
            if(fenced||closing)return Result.CANCELLED;
            if(currentResourceGeneration!=lease.resources||!supportedMode){markFence("Installed renderer mode/resource generation changed");return Result.CANCELLED;}
            if(alreadyCancelled){markFence("Another renderer cancelled the owned carrier DH event");return Result.CANCELLED;}
            if(frameId<0||lastFrame!=Long.MIN_VALUE&&frameId<lastFrame){markFence("Client frame identity regressed");return Result.CANCELLED;}
            if(frameId==lastFrame){duplicates++;return Result.CANCELLED;}
            if(busy){markFence("Reentrant carrier rendering");return Result.CANCELLED;}
            lastFrame=frameId;busy=true;
            try{resources.draw(context);submissions++;return fenced?Result.CANCELLED:Result.SUBMITTED;}
            catch(RuntimeException|Error error){markFence("Carrier render failed: "+error.getClass().getSimpleName());throw error;}
            finally{busy=false;}
        }
    }
    /** The stock deferred pass is suppressed even after fencing until owned cleanup completes. */
    public Result deferred(Object level,Runnable cancel){checkOwner();Objects.requireNonNull(cancel);synchronized(fence){
        if(lease==null||lease.level!=level){passed++;return Result.PASS_THROUGH;}
        cancel(cancel);deferred++;return Result.CANCELLED;
    }}
    /** API unload callbacks may only fence; GL cleanup remains on its owner thread. */
    public void levelUnloaded(Object exactLevel){synchronized(fence){if(lease!=null&&lease.level==exactLevel)markFence("Owned carrier unloaded");}}
    public void invalidate(String reason){synchronized(fence){if(lease!=null)markFence(reason);}}
    public boolean owns(Object exactLevel){synchronized(fence){return lease!=null&&lease.level==exactLevel;}}
    public boolean isCurrent(Lease expected){synchronized(fence){return expected!=null&&lease==expected&&!fenced&&!closing;}}
    public Counters counters(){synchronized(fence){return new Counters(lease!=null,fenced,closing,submissions,duplicates,opaque,deferred,passed,failure);}}
    /** Retryable cleanup. Failed owners remain retained and stock carrier rendering stays suppressed. */
    @Override public void close(){checkOwner();synchronized(fence){
        if(lease==null)return;require(!busy&&!cleanupBusy,"Cannot reenter owned resource cleanup");closing=true;markFence("Carrier closing");
        cleanupBusy=true;try{resources.close();resources=null;lease=null;closing=false;lastFrame=Long.MIN_VALUE;}finally{cleanupBusy=false;}
    }}
    private void cancel(Runnable cancel){try{cancel.run();}catch(RuntimeException|Error failure){markFence("Cannot establish DH render exclusion");throw failure;}}
    private void markFence(String reason){fenced=true;if(failure.isEmpty())failure=reason==null?"Carrier fenced":reason.substring(0,Math.min(256,reason.length()));}
    private static void require(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
    private void checkOwner(){require(Thread.currentThread()==owner,"Carrier render owner thread required");}
}
