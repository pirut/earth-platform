package io.earthplatform.compat.dh.shader;

import java.util.Objects;

/** Pure bounded ownership for a future pinned Iris/DH backend. This class alone does
 * not establish shader compatibility. It owns no shader, framebuffer, or upstream pool. */
public final class DhShaderPassGate implements AutoCloseable {
    public enum Pass {
        MAIN_NATIVE_SOLID, MAIN_NATIVE_CUTOUT_MIPPED, MAIN_NATIVE_CUTOUT, MAIN_NATIVE_TRANSLUCENT, MAIN_DH_OPAQUE, MAIN_DH_TRANSLUCENT,
        SHADOW_NATIVE_SOLID, SHADOW_NATIVE_CUTOUT_MIPPED, SHADOW_NATIVE_CUTOUT, SHADOW_NATIVE_TRANSLUCENT, SHADOW_DH_OPAQUE, SHADOW_DH_TRANSLUCENT;
        public int bit(){return 1<<ordinal();}
    }
    public enum Result { PASS_THROUGH, STALE_LEASE, FENCED, NO_PRESENTATION, DUPLICATE, SUBMITTED }
    public interface Resources {
        /** Exact current or display-only historical pair validation under the host frame fence. */
        boolean presentationCurrent(String exactPairIdentity,boolean historical);
        /** Caller supplies only the current pass's borrowed transient program/target context. */
        void draw(Pass pass,Object transientContext);
        /** Close only engine-owned resources; never close a borrowed Iris/DH pipeline. */
        void close();
    }
    public static final class Lease {
        private final Object level,pipeline;private final String world;private final long epoch,resources;private final int allowedMask;
        private Lease(Object level,Object pipeline,String world,long epoch,long resources,int mask){this.level=level;this.pipeline=pipeline;this.world=world;this.epoch=epoch;this.resources=resources;allowedMask=mask;}
        public String world(){return world;}public long epoch(){return epoch;}public long resourceGeneration(){return resources;}public int allowedPassMask(){return allowedMask;}
    }
    public record Counters(boolean ownsCarrier,boolean fenced,boolean cleanupPending,long frame,
            int submittedMask,long submissions,long duplicates,long rejected,String failure){}
    private final Thread owner=Thread.currentThread();private final Object fence=new Object();
    private Lease lease;private Resources owned;private boolean fenced,closing,busy,cleanupBusy,historical;
    private final long[] passSubmissions=new long[Pass.values().length];
    private long frame=-1,submissions,duplicates,rejected;private int submittedMask;private String pair,failure="";

    public Lease acquire(Object exactCarrier,Object exactPipeline,String world,long epoch,long resourceGeneration,int allowedPassMask,Resources resources){
        checkOwner();Objects.requireNonNull(exactCarrier);Objects.requireNonNull(exactPipeline);Objects.requireNonNull(world);Objects.requireNonNull(resources);
        if(world.isBlank()||world.length()>256||epoch<0||resourceGeneration<1||allowedPassMask<=0||(allowedPassMask&~((1<<Pass.values().length)-1))!=0)throw new IllegalArgumentException("Exact bounded shader carrier policy required");
        synchronized(fence){require(lease==null&&!busy&&!cleanupBusy,"Prior shader resources remain owned");
            lease=new Lease(exactCarrier,exactPipeline,world,epoch,resourceGeneration,allowedPassMask);owned=resources;
            fenced=false;closing=false;frame=-1;pair=null;submittedMask=0;failure="";return lease;}
    }
    /** One actual client frame pins one pair across native/DH/main/shadow passes.
     * Null identity is a legitimate initial loading frame, never a ready presentation. */
    public boolean beginFrame(Lease expected,long serial,Object exactPipeline,long resourceGeneration,String exactPairIdentity,boolean historical){
        checkOwner();if(exactPairIdentity!=null&&!isDigest(exactPairIdentity))throw new IllegalArgumentException("Exact pair digest required");
        synchronized(fence){
            if(lease==null||lease!=expected)return false;
            if(fenced||closing)return false;
            if(busy){markFence("Frame changed during an owned shader pass");return false;}
            if(exactPipeline!=lease.pipeline||resourceGeneration!=lease.resources){markFence("Shader pipeline/resource identity changed");return false;}
            if(serial<0||serial<frame){markFence("Actual shader frame identity regressed");return false;}
            if(serial==frame){
                if(!Objects.equals(pair,exactPairIdentity)||this.historical!=historical)markFence("Published pair changed within a shader frame");
                return !fenced;
            }
            frame=serial;pair=exactPairIdentity;this.historical=historical;submittedMask=0;return true;
        }
    }
    /** The hook must replace only the stock buffer list for this exact owned carrier,
     * even while fenced. It must let upstream setup, dispatch and cleanup continue. */
    public boolean replaceStockBuffers(Object exactLevel){synchronized(fence){return lease!=null&&lease.level==exactLevel;}}
    public Result submit(Lease expected,Object exactLevel,long serial,Object exactPipeline,long resourceGeneration,Pass pass,Object context){
        checkOwner();Objects.requireNonNull(pass);
        synchronized(fence){
            if(lease==null||lease.level!=exactLevel)return Result.PASS_THROUGH;
            if(lease!=expected)return Result.STALE_LEASE;
            if(fenced||closing)return Result.FENCED;
            if(frame!=serial||serial<0||exactPipeline!=lease.pipeline||resourceGeneration!=lease.resources){markFence("Unprepared or changed shader pass identity");return Result.FENCED;}
            if((lease.allowedMask&pass.bit())==0){markFence("Shader pass is outside the pinned pack contract");return Result.FENCED;}
            if(busy){markFence("Reentrant shader submission");return Result.FENCED;}
            if((submittedMask&pass.bit())!=0){duplicates=increment(duplicates);return Result.DUPLICATE;}
            if(pair==null)return Result.NO_PRESENTATION;
            busy=true;
            try{
                if(!owned.presentationCurrent(pair,historical)){markFence("Shader presentation authority changed");return Result.FENCED;}
                if(fenced||closing)return Result.FENCED;
                submittedMask|=pass.bit(); // consume before callback; no retry after partial submission
                owned.draw(pass,context);submissions=increment(submissions);passSubmissions[pass.ordinal()]=increment(passSubmissions[pass.ordinal()]);
                return fenced?Result.FENCED:Result.SUBMITTED;
            }catch(RuntimeException|Error error){markFence("Shader pass failed: "+error.getClass().getSimpleName());throw error;}
            finally{busy=false;}
        }
    }
    public void invalidate(String reason){synchronized(fence){if(lease!=null)markFence(reason);}}
    public void levelUnloaded(Object exactLevel){synchronized(fence){if(lease!=null&&lease.level==exactLevel)markFence("Shader carrier unloaded");}}
    public boolean current(Lease expected){synchronized(fence){return expected!=null&&expected==lease&&!fenced&&!closing;}}
    public long submissions(Pass pass){synchronized(fence){return passSubmissions[Objects.requireNonNull(pass).ordinal()];}}
    public Counters counters(){synchronized(fence){return new Counters(lease!=null,fenced,closing,frame,submittedMask,submissions,duplicates,rejected,failure);}}
    @Override public void close(){checkOwner();synchronized(fence){
        if(lease==null)return;require(!busy&&!cleanupBusy,"Cannot close during owned shader callback");
        closing=true;markFence("Shader carrier closing");cleanupBusy=true;
        try{owned.close();owned=null;lease=null;closing=false;pair=null;frame=-1;submittedMask=0;}finally{cleanupBusy=false;}
    }}
    private void markFence(String reason){fenced=true;rejected=increment(rejected);if(failure.isEmpty()){String text=reason==null?"Shader carrier fenced":reason;failure=text.substring(0,Math.min(256,text.length()));}}
    private static boolean isDigest(String value){if(value.length()!=64)return false;for(int i=0;i<64;i++){char c=value.charAt(i);if(!(c>='0'&&c<='9'||c>='a'&&c<='f'))return false;}return true;}
    private static long increment(long value){return value==Long.MAX_VALUE?value:value+1;}
    private void checkOwner(){require(Thread.currentThread()==owner,"Shader gate owner thread required");}
    private static void require(boolean value,String text){if(!value)throw new IllegalStateException(text);}
}
