package io.earthplatform.minecraft.survival.runtime;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.EventBus;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;

/** One process-lifetime, observation-only startup marker. No callback/approval minting.
 * It authenticates the transformed Minecraft-owned origin before the stock loader starts
 * the global event bus. It permits a diagnostic-only startup copy after the reviewed source-cut guards pass.
 * Never infer writer exclusion merely from the booleans this marker records.
 */
public final class InstalledCallbackStartupMarker {
    // Prepay before StackWalker, reflection metadata, streaming hash scratch, or records.
    // Existing 384 MiB global and 72 MiB owner pool limits are unchanged.
    private static volatile NativeDistanceCpuBudget.Reservation charge;
    private static final AtomicInteger CALLS=new AtomicInteger();
    private static volatile Snapshot snapshot;
    private static volatile boolean invalid;
    private static volatile Trace retainedTrace,publishedTrace;
    private static volatile Class<?> reservationFailureClass;
    static final class Trace {
        String stage="ENTRY",key="",expected="",actual="",directMethod="",callerMethod="";
        int frameCount;
        Class<?> directOwner,callerOwner,failureClass;
    }
    record Snapshot(Class<?> host,Class<?> loaderHost,Class<?> busHost,
                    boolean loading,boolean loadingComplete,boolean noLoadingError,
                    boolean shutdown,boolean production,boolean beforeWorld,String originalMinecraftSha256,
                    String originalLoaderSha256) {}
    private InstalledCallbackStartupMarker() {}
    public static void observe(Minecraft actual) {
        if(CALLS.incrementAndGet()!=1){invalid=true;return;}
        Trace trace=null;
        try {
            charge=NativeDistanceCpuBudget.global().reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,64L<<10);
            trace=new Trace();retainedTrace=trace;charge.claimRetainedOwner(trace);
            trace.stage="ENTRY_PREDICATES";
            if(!Boolean.getBoolean("earthplatform.installedSessionProbe")
                    || Boolean.getBoolean("neoforge.gameTestServer") || actual==null
                    || actual.getClass()!=Minecraft.class || !actual.isSameThread()) {
                invalid=true;return;
            }
            trace.stage="ORIGIN";
            var frames=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .walk(stream->stream.skip(1).limit(2).toList());
            trace.frameCount=frames.size();
            if(!frames.isEmpty())trace.directOwner=frames.get(0).getDeclaringClass();
            if(frames.size()>1)trace.callerOwner=frames.get(1).getDeclaringClass();
            if(trace.directOwner==Minecraft.class)trace.directMethod=clip(frames.get(0).getMethodName());
            if(trace.callerOwner==Minecraft.class)trace.callerMethod=clip(frames.get(1).getMethodName());
            if(frames.size()!=2 || frames.get(0).getDeclaringClass()!=Minecraft.class
                    || !frames.get(0).getMethodName().endsWith("$earthplatform$installedCallbackStartupCut")
                    || frames.get(1).getDeclaringClass()!=Minecraft.class
                    || !frames.get(1).getMethodName().equals("buildInitialScreens")) {
                invalid=true;return;
            }
            trace.stage="LOADER_CLASS";trace.key="net.neoforged.neoforge.client.loading.ClientModLoader";
            Class<?> loader=Class.forName("net.neoforged.neoforge.client.loading.ClientModLoader",false,
                    InstalledCallbackStartupMarker.class.getClassLoader());
            String minecraftHash=pin(trace,Minecraft.class,"495f5dc97d07476e23835fcdd034019a020a58b980f7c764d4953ce4ed16d283");
            String loaderHash=pin(trace,loader,"f4671fd5a47fb476dc0e5ee0ba0e6974411f51eb8256999f1c7a7f26d976eaa3");
            pin(trace,FMLLoader.class,"67b3ae93d48098995897b54e01a992bd9734d308a0408713f286c94f4bceb798");
            pin(trace,EventBus.class,"cda8876c904d1374f77bbff02e8cdb01fba48ae0f528c69713eaefa11b5128a4");
            trace.stage="LOADER_STATE";
            if(NeoForge.EVENT_BUS.getClass()!=EventBus.class){invalid=true;return;}
            boolean loading=(boolean)state(trace,loader,null,"loading");
            boolean done=(boolean)state(trace,loader,null,"loadingComplete");
            boolean noError=state(trace,loader,null,"error")==null;
            boolean shutdown=(boolean)state(trace,EventBus.class,NeoForge.EVENT_BUS,"shutdown");
            // Exact pinned FMLLoader.isProduction is a three-instruction raw boolean getter.
            boolean production=FMLLoader.isProduction();
            boolean beforeWorld=actual.level==null && actual.player==null && actual.getSingleplayerServer()==null;
            snapshot=new Snapshot(actual.getClass(),loader,NeoForge.EVENT_BUS.getClass(),
                    loading,done,noError,shutdown,production,beforeWorld,minecraftHash,loaderHash);
            if(loading || !done || !noError || !shutdown || !production || !beforeWorld)invalid=true;
            if(!invalid){
                trace.stage="MARKER_AUTHENTICATED";InstalledCallbackStartupCensus.captureFromMarker();
                // Explicit sequencing: source-closed census precedes the separate origin join.
                // Observer linkage/failure cannot replace the original loader continuation.
                try { InstalledConsumerOriginStartupJoin.freezeFromMarker(); }
                catch(Throwable observerFailure) {
                    try { InstalledConsumerOrigins.observerFailed(observerFailure.getClass()); } catch(Throwable ignored) {}
                }
            }
        } catch(ReflectiveOperationException | java.io.IOException | java.security.NoSuchAlgorithmException
                | RuntimeException failure) {
            invalid=true; // No foreign throwable formatting or recursive cause walk.
            if(trace==null)reservationFailureClass=failure.getClass();else trace.failureClass=failure.getClass();
        } finally {
            if(trace!=null)publishedTrace=trace; // Publish complete failure/origin evidence, including null Snapshot cases.
        }
    }
    static Trace trace(){return publishedTrace;}
    static Class<?> reservationFailureClass(){return reservationFailureClass;}
    private static String pin(Trace trace,Class<?> type,String expected)throws java.io.IOException,java.security.NoSuchAlgorithmException {
        trace.stage="ORIGINAL_PIN";trace.key=type.getName();trace.expected=expected;trace.actual="CHECKING";
        trace.actual=hash(type);
        if(!trace.actual.equals(expected))throw new java.io.IOException("Changed startup marker original");
        return trace.actual;
    }
    static Snapshot snapshot() { return snapshot; }
    static boolean invalid() { return invalid || CALLS.get()!=1; }
    static long modeledBytes() { var paid=charge;return paid==null?0:paid.bytes(); }
    private static String clip(String value){return value.substring(0,Math.min(value.length(),128));}
    private static Object state(Trace trace,Class<?> owner,Object receiver,String name)throws ReflectiveOperationException {
        trace.stage="RAW_STATE";trace.key=owner.getName()+"#"+name;trace.expected="original loader predicate";trace.actual="CHECKING";
        Object value=field(owner,receiver,name);
        trace.actual=value==null?"NULL":value.getClass()==Boolean.class?((Boolean)value?"true":"false"):"NON_NULL";
        return value;
    }
    private static Object field(Class<?> owner,Object receiver,String name)throws ReflectiveOperationException {
        Field field=owner.getDeclaredField(name);
        if(!field.trySetAccessible())throw new IllegalStateException("Startup marker field inaccessible");
        return field.get(receiver);
    }
    private static String hash(Class<?> type)throws java.io.IOException,java.security.NoSuchAlgorithmException {
        try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
            if(in==null)throw new java.io.IOException("Startup marker original resource unavailable");
            var digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[8192];int total=0;
            for(int read;(read=in.read(bytes))!=-1;){
                total=Math.addExact(total,read);
                if(total>2*1024*1024)throw new java.io.IOException("Startup marker class-resource bound");
                digest.update(bytes,0,read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
