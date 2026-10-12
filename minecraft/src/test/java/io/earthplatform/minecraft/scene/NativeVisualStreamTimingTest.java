package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import io.earthplatform.minecraft.visual.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;

/** Executes production stream/worker bytecode with explicit headless lease, upload, and clock substitutes.
 * The child loader removes only requireOpen, substitutes GPU creation/upload and System.nanoTime.
 * Pump limits, poll order, identity checks, owner registration, progress, failure and drain bodies are real.
 * No GPU allocation, authenticated capsule/readiness, installed-client latency, or CPU-time claim is made.
 */
public class NativeVisualStreamTimingTest {
    private static final String HASH="0123456789abcdef".repeat(4);
    private static final String STREAM=NativeVisualStream.class.getName();
    private static final String HOOKS=NativeVisualStreamTimingTest.class.getName().replace('.','/');
    private static final String GPU=NativeVisualGpuPiece.class.getName().replace('.','/');
    private static final Unsafe UNSAFE=unsafe();
    private static final CubePos CENTER=new CubePos(0,0,0);
    private static final NativeVisualCapsule CAPSULE=new NativeVisualCapsule(1,HASH,HASH,HASH,new byte[NativeVisualCapsule.TABLE_BYTES]);
    private static final ThreadLocal<long[]> CLOCK=new ThreadLocal<>();
    private static final ThreadLocal<RuntimeException> UPLOAD_FAILURE=new ThreadLocal<>();
    private static Unsafe unsafe(){try{Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return (Unsafe)f.get(null);}catch(Exception e){throw new AssertionError(e);}}
    private static Object get(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static Object read(Object target,String name)throws Exception{var method=target.getClass().getMethod(name);method.setAccessible(true);return method.invoke(target);}
    private static long number(Object target,String name)throws Exception{return ((Number)read(target,name)).longValue();}
    private static Object invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception{
        try{return target.getClass().getMethod(name,types).invoke(target,args);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error error)throw error;throw e;}
    }
    public static long nanoTime(){long[] value=CLOCK.get();return value==null?System.nanoTime():value[0];}
    public static NativeVisualGpuPiece fakeGpu(NativeVisualCpuPiece cpu,NativeVisualCapsuleCapture.Lease lease,NativeVisualBudget budget){
        try{var gpu=(NativeVisualGpuPiece)UNSAFE.allocateInstance(NativeVisualGpuPiece.class);set(gpu,"owner",Thread.currentThread());set(gpu,"quads",cpu.quads());set(gpu,"cpu",cpu);return gpu;}
        catch(Exception e){throw new AssertionError(e);}
    }
    public static void fakeUpload(NativeVisualGpuPiece gpu){
        long[] clock=CLOCK.get();if(clock!=null)clock[0]+=10;
        if(UPLOAD_FAILURE.get()!=null)throw UPLOAD_FAILURE.get();
        try{set(gpu,"cpu",null);set(gpu,"uploaded",true);}catch(Exception e){throw new AssertionError(e);}
    }
    private static final class HeadlessLoader extends ClassLoader {
        HeadlessLoader(){super(NativeVisualStreamTimingTest.class.getClassLoader());}
        @Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
            String stepper="io.earthplatform.minecraft.scene.SceneBuildStepper";
            // Its package-private append/rethrow helpers share the stream's runtime package in production.
            if(!name.equals(STREAM)&&!name.startsWith(STREAM+"$")&&!name.equals(stepper)&&!name.startsWith(stepper+"$"))return super.loadClass(name,resolve);
            Class<?> found=findLoadedClass(name);
            if(found==null){
                try(var in=getParent().getResourceAsStream(name.replace('.','/')+".class")){
                    if(in==null)throw new ClassNotFoundException(name);byte[] bytes=in.readAllBytes();
                    if(name.equals(STREAM)){
                        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
                            @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions){
                                MethodVisitor out=super.visitMethod(access,method,descriptor,signature,exceptions);
                                if(method.equals("requireOpen")){out.visitCode();out.visitInsn(Opcodes.RETURN);out.visitMaxs(0,1);out.visitEnd();return null;}
                                return new MethodVisitor(Opcodes.ASM9,out){
                                    boolean skipDup;
                                    @Override public void visitTypeInsn(int opcode,String type){if(method.equals("pump")&&opcode==Opcodes.NEW&&type.equals(GPU)){skipDup=true;return;}super.visitTypeInsn(opcode,type);}
                                    @Override public void visitInsn(int opcode){if(skipDup&&opcode==Opcodes.DUP){skipDup=false;return;}super.visitInsn(opcode);}
                                    @Override public void visitMethodInsn(int opcode,String owner,String name,String desc,boolean itf){
                                        if(owner.equals("java/lang/System")&&name.equals("nanoTime")){super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOKS,"nanoTime",desc,false);return;}
                                        if(method.equals("pump")&&owner.equals(GPU)&&name.equals("<init>")){super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOKS,"fakeGpu",desc.substring(0,desc.length()-1)+"L"+GPU+";",false);return;}
                                        if(method.equals("pump")&&owner.equals(GPU)&&name.equals("upload")){super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOKS,"fakeUpload","(L"+GPU+";)V",false);return;}
                                        super.visitMethodInsn(opcode,owner,name,desc,itf);
                                    }
                                };
                            }
                        },0);bytes=writer.toByteArray();
                    }
                    found=defineClass(name,bytes,0,bytes.length);
                }catch(IOException e){throw new ClassNotFoundException(name,e);}
            }
            if(resolve)resolveClass(found);return found;
        }
    }
    private static EarthDistanceSurfacePlan.Source source(int minY){
        var envelope=new EarthAlpineDefinition.Envelope(0,1,0,1,-16,16,-4096,4096,-4096,4096);
        return EarthDistanceSurfacePlan.Source.prepareSampled(envelope,minY,256,HASH,HASH,HASH,2,1,NativeDistanceLimits.sourceLimits(),(x,z)->new EarthDistanceSurfacePlan.Sample(-1,5,3,7,0,-1,0));
    }
    private static EarthReplacementCapture capture(EarthDistanceSurfacePlan.Source source){
        try(var b=new EarthReplacementCapture.Builder(CENTER)){for(int y=-2;y<18;y++)for(int z=-2;z<18;z++)for(int x=-2;x<18;x++)b.setNext(source.materialAt(x,y,z));return b.finish();}
    }
    private static final class Fixture {
        final HeadlessLoader loader=new HeadlessLoader();final Object stream,job;final EarthDistanceSurfacePlan.Source source;final EarthReplacementCapture capture;
        Fixture(EarthDistanceSurfacePlan.Source source)throws Exception{
            this.source=source;capture=capture(source);var stamp=new NativeVisualStamp(new WorldIdentity(1,"test","test","test","test"),new UUID(1,2),1,1,1,0,2,2,0,HASH,HASH,HASH,HASH,capture.contentIdentity());
            var jobType=loader.loadClass(STREAM+"$Job");var ctor=jobType.getDeclaredConstructors()[0];ctor.setAccessible(true);job=ctor.newInstance(stamp,CENTER,capture);
            stream=UNSAFE.allocateInstance(loader.loadClass(STREAM));set(stream,"owner",Thread.currentThread());set(stream,"job",job);set(stream,"cache",new LinkedHashMap<>());set(stream,"physical",Collections.newSetFromMap(new IdentityHashMap<>()));
            set(stream,"source",new AtomicReference<>(source));set(stream,"gpuBudget",new NativeVisualBudget());set(stream,"sealed",new NativeVisualCapsuleCapture.Sealed(CAPSULE,null,null,null));set(stream,"retainedPresentation",Set.of());
            @SuppressWarnings({"rawtypes","unchecked"}) Object none=Enum.valueOf((Class)loader.loadClass(STREAM+"$PumpStopReason"),"NONE");set(stream,"lastPumpStop",none);
        }
        Runnable worker(EarthDistanceSurfacePlan.Source input,Set<String> reused)throws Exception{var type=loader.loadClass(STREAM+"$Worker");var ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);return (Runnable)ctor.newInstance(job,reused,null,CAPSULE,new AtomicReference<>(input));}
        Object call(String name)throws Exception{return invoke(stream,name,new Class<?>[0]);}
        Object timing(String name)throws Exception{return read(call("evidence"),name);}
        int pump(int count,long nanos)throws Exception{return (Integer)invoke(stream,"pump",new Class<?>[]{int.class,long.class},count,nanos);}
        @SuppressWarnings("unchecked") ArrayBlockingQueue<Object> queue()throws Exception{return (ArrayBlockingQueue<Object>)get(job,"output");}
        void makeUnboundedFixtureOutput()throws Exception{set(job,"output",new ArrayBlockingQueue<>(32));}
    }
    private static void assertStop(Object timing,String stop)throws Exception{assertEquals(stop,read(timing,"lastStop").toString());assertEquals(number(timing,"calls"),number(timing,"noRequestStops")+number(timing,"uploadLimitStops")+number(timing,"timeLimitStops")+number(timing,"emptyMailboxStops")+number(timing,"failedStops"));}
    private static void await(BooleanSupplier condition)throws InterruptedException{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!condition.getAsBoolean()){assertTrue(System.nanoTime()<end,"Worker fixture timed out");Thread.sleep(1);}}
    private static NativeVisualCpuPiece piece(Object output)throws Exception{return (NativeVisualCpuPiece)read(output,"value");}

    @Test void exactPumpStopsAndSuccessfulUploadWallTimePreserveLimitPrecedenceAndCompletion()throws Exception{
        var f=new Fixture(source(-256));f.makeUnboundedFixtureOutput();f.worker(f.source,Set.of()).run();assertNull(get(f.job,"failure"));
        assertEquals("COMPLETED",read(f.timing("workerTiming"),"stop").toString());
        var before=f.call("progress");assertEquals(16,number(before,"producedPieces"));assertEquals(0,number(before,"uploadedPieces"));
        CLOCK.set(new long[]{1000});
        try{
            assertEquals(4,f.pump(4,40));var first=f.timing("pumpTiming");assertStop(first,"UPLOAD_LIMIT");assertEquals(40,number(first,"ownerWallNanos"));assertEquals(40,number(first,"successfulUploadWallNanos"));assertEquals(10,number(first,"maxSuccessfulUploadWallNanos"));
            assertEquals(4,number(first,"lastUploads"));assertEquals(4,number(first,"lastUploadLimit"));assertEquals(40,number(first,"lastBudgetNanos"));
            // Same queue, exact same production loop: two10ns uploads exhaust15ns before the count limit.
            assertEquals(2,f.pump(4,15));var timed=f.timing("pumpTiming");assertStop(timed,"TIME_LIMIT");assertEquals(20,number(timed,"lastWallNanos"));assertEquals(1,number(timed,"timeLimitStops"));
            assertEquals(4,f.pump(4,Long.MAX_VALUE));assertEquals(4,f.pump(4,Long.MAX_VALUE));assertEquals(2,f.pump(4,Long.MAX_VALUE));assertStop(f.timing("pumpTiming"),"EMPTY_MAILBOX");
            var after=f.call("progress");assertEquals(16,number(after,"uploadedPieces"));assertEquals(number(before,"producedPieces"),number(after,"producedPieces"));assertEquals(number(before,"faceScansDone"),number(after,"faceScansDone"));
            var terminal=f.timing("workerTiming");assertTrue((Boolean)f.call("finishRequest"));assertNull(get(f.stream,"job"));assertEquals(after,f.call("progress"));assertEquals(terminal,f.timing("workerTiming"));
            assertEquals(0,f.pump(4,6_000_000));var empty=f.timing("pumpTiming");assertStop(empty,"NO_REQUEST");assertEquals(1,number(empty,"noRequestStops"));assertEquals(160,number(empty,"successfulUploadWallNanos"));assertEquals(6,number(empty,"calls"));
            assertEquals(16,number(f.call("evidence"),"uploadedPieces"));assertEquals(1,number(f.call("evidence"),"completedRequests"));
        }finally{CLOCK.remove();}
    }

    @Test void emptyPollingAndInvalidArgumentsNeverInventWork()throws Exception{
        var f=new Fixture(source(-256));var initial=f.call("progress");CLOCK.set(new long[]{100});
        try{
            for(int i=0;i<5;i++){assertEquals(0,f.pump(4,6_000_000));assertEquals(initial,f.call("progress"));}
            var timing=f.timing("pumpTiming");assertStop(timing,"EMPTY_MAILBOX");assertEquals(5,number(timing,"emptyMailboxStops"));assertEquals(0,number(timing,"successfulUploadWallNanos"));
            assertThrows(IllegalArgumentException.class,()->f.pump(0,1));assertThrows(IllegalArgumentException.class,()->f.pump(1,0));assertEquals(timing,f.timing("pumpTiming"));
        }finally{CLOCK.remove();}
    }

    @Test void failedUploadKeepsOriginalFailureOwnerAndZeroSuccessfulCredit()throws Exception{
        var f=new Fixture(source(-256));f.makeUnboundedFixtureOutput();f.worker(f.source,Set.of()).run();var original=new IllegalStateException("intentional upload failure");CLOCK.set(new long[]{100});UPLOAD_FAILURE.set(original);
        try{
            assertSame(original,assertThrows(IllegalStateException.class,()->f.pump(4,6_000_000)));var timing=f.timing("pumpTiming");assertStop(timing,"FAILED");assertEquals(1,number(timing,"failedStops"));assertEquals(10,number(timing,"ownerWallNanos"));assertEquals(0,number(timing,"successfulUploadWallNanos"));assertEquals(0,number(timing,"lastUploads"));
            assertEquals(1,((Set<?>)get(f.stream,"physical")).size());assertNotNull(get(f.stream,"job"));assertTrue((Boolean)get(f.stream,"failed"));assertTrue(((NativeVisualCpuBuilder.Cancellation)get(f.job,"cancel")).isCancelled());assertEquals(0,number(f.call("progress"),"uploadedPieces"));
            assertTrue((Boolean)f.call("drainCancelled"));assertEquals(0,((Set<?>)get(f.stream,"physical")).size());assertNull(get(f.stream,"job"));assertEquals("COMPLETED",read(f.timing("workerTiming"),"stop").toString());
        }finally{CLOCK.remove();UPLOAD_FAILURE.remove();}
    }

    @Test void workerFailureStillOverridesASelectedNormalPumpStop()throws Exception{
        var f=new Fixture(source(-256));var original=new AssertionError("worker marker");set(f.job,"failure",original);CLOCK.set(new long[]{100});
        try{assertSame(original,assertThrows(AssertionError.class,()->f.pump(4,6_000_000)));assertStop(f.timing("pumpTiming"),"FAILED");assertEquals(0,number(f.timing("pumpTiming"),"emptyMailboxStops"));assertSame(original,get(f.job,"failure"));}
        finally{CLOCK.remove();}
    }

    @Test void actualBoundedMailboxTimeoutsAreSeparateFromComputeAndNeverUsefulProgress()throws Exception{
        var f=new Fixture(source(-256));assertEquals(NativeDistanceLimits.CPU_MAILBOX_PIECES,f.queue().remainingCapacity());
        var thread=new Thread(f.worker(f.source,Set.of()),"native-timing-full-mailbox-fixture");thread.start();
        try{
            await(()->{try{return number(f.timing("workerTiming"),"outputOfferTimeouts")>=1;}catch(Exception e){throw new AssertionError(e);}});
            var progress=f.call("progress");var first=f.timing("workerTiming");assertEquals("RUNNING",read(first,"stop").toString());assertEquals(0,number(first,"workerWallNanos"));
            long firstTimeouts=number(first,"outputOfferTimeouts");await(()->{try{return number(f.timing("workerTiming"),"outputOfferTimeouts")>firstTimeouts;}catch(Exception e){throw new AssertionError(e);}});
            assertEquals(progress,f.call("progress"));assertEquals(0,number(progress,"uploadedPieces"));assertEquals(NativeDistanceLimits.CPU_MAILBOX_PIECES,f.queue().size());
            for(int i=0;i<50;i++)assertEquals(progress,f.call("progress"));
        }finally{f.call("cancelRequest");thread.join(10000);if(thread.isAlive()){thread.interrupt();thread.join(10000);}}
        assertFalse(thread.isAlive());assertNull(get(f.job,"failure"));var timing=f.timing("workerTiming");assertEquals("CANCELLED",read(timing,"stop").toString());assertEquals("IDLE",read(timing,"phase").toString());
        assertTrue(number(timing,"planWallNanos")>0);assertTrue(number(timing,"haloWallNanos")>0);assertTrue(number(timing,"meshWallNanos")>0);assertTrue(number(timing,"outputOfferWallNanos")>=TimeUnit.MILLISECONDS.toNanos(100));
        assertEquals(number(timing,"outputOfferAttempts"),number(timing,"outputsOffered")+number(timing,"outputOfferTimeouts"));assertTrue(number(timing,"workerWallNanos")>=number(timing,"planWallNanos")+number(timing,"haloWallNanos")+number(timing,"meshWallNanos")+number(timing,"outputOfferWallNanos"));
        var finalProgress=f.call("progress");assertTrue((Boolean)f.call("drainCancelled"));assertEquals(timing,f.timing("workerTiming"));assertEquals(finalProgress,f.call("progress"));
    }

    @Test void precancelAndFailedSourceAndHaloRetainPartialWallTimeWithoutCompletedWork()throws Exception{
        var cancelled=new Fixture(source(-256));cancelled.call("cancelRequest");cancelled.worker(cancelled.source,Set.of()).run();var stop=cancelled.timing("workerTiming");assertEquals("CANCELLED",read(stop,"stop").toString());
        for(String field:List.of("sourceWallNanos","planWallNanos","haloWallNanos","meshWallNanos","outputOfferAttempts"))assertEquals(0,number(stop,field));assertEquals(0,number(cancelled.call("progress"),"producedPieces"));
        var sourceFailure=new Fixture(source(-256));sourceFailure.worker(null,Set.of()).run();var timing=sourceFailure.timing("workerTiming");assertNotNull(get(sourceFailure.job,"failure"));assertEquals("FAILED",read(timing,"stop").toString());assertTrue(number(timing,"sourceWallNanos")>0);assertEquals(0,number(timing,"planWallNanos"));assertEquals(0,number(sourceFailure.call("progress"),"sampledColumnsDone"));
        var haloFailure=new Fixture(source(-64));haloFailure.worker(haloFailure.source,Set.of()).run();var halo=haloFailure.timing("workerTiming");assertInstanceOf(EarthDistanceSurfacePlan.Refusal.class,get(haloFailure.job,"failure"));assertEquals("FAILED",read(halo,"stop").toString());assertTrue(number(halo,"planWallNanos")>0);assertTrue(number(halo,"haloWallNanos")>0);assertEquals(0,number(halo,"meshWallNanos"));assertEquals(0,number(haloFailure.call("progress"),"haloRowsDone"));assertEquals(0,number(haloFailure.call("progress"),"faceScansDone"));
    }

    @Test void workerOutputAndCompletedProgressMatchUntimedGeometryAndReusedPagesStayZero()throws Exception{
        var f=new Fixture(source(-256));f.makeUnboundedFixtureOutput();f.worker(f.source,Set.of()).run();
        Object plans=f.queue().remove();var draw=(EarthDistanceSurfacePlan)read(plans,"draw");var cache=(EarthDistanceSurfacePlan)read(plans,"cache");var expectedDraw=EarthDistanceSurfacePlan.build(f.source,f.capture,CENTER,2,0,NativeDistanceLimits.sourceLimits());var expectedCache=EarthDistanceSurfacePlan.build(f.source,f.capture,CENTER,2,1,NativeDistanceLimits.sourceLimits());
        assertEquals(expectedDraw.identity(),draw.identity());assertEquals(expectedDraw.pages(),draw.pages());assertEquals(expectedCache.identity(),cache.identity());assertEquals(expectedCache.pages(),cache.pages());
        long pieces=0,scans=0;var pages=new LinkedHashMap<String,EarthDistanceSurfacePlan.Page>();for(var p:draw.pages())pages.put(p.identity(),p);for(var p:cache.pages())pages.putIfAbsent(p.identity(),p);
        for(var page:pages.values()){
            byte[] halo=new byte[NativeVisualCpuBuilder.HALO_BYTES];var key=page.key();for(int y=-1;y<=64;y++)for(int z=-1;z<=64;z++)for(int x=-1;x<=64;x++)halo[NativeVisualCpuBuilder.Input.haloIndex(x,y,z)]=(byte)expectedCache.materialAt(key.minX()+x,key.minY()+y,key.minZ()+z);
            int[] members=new int[page.memberCount()];int n=0;for(int i=0;i<64;i++)if((page.memberMask()&(1L<<i))!=0)members[n++]=i;
            var builder=new NativeVisualCpuBuilder(new NativeVisualCpuBuilder.Input(Math.toIntExact(key.minX()),Math.toIntExact(key.minY()),Math.toIntExact(key.minZ()),halo,members,page.identity()),CAPSULE,new NativeVisualCpuBuilder.Cancellation());
            while(true){var expected=builder.nextPiece();var actual=piece(f.queue().remove());assertEquals(expected.bytesDigest(),actual.bytesDigest());assertEquals(expected.requestStamp(),actual.requestStamp());assertEquals(expected.memberRanges(),actual.memberRanges());assertEquals(expected.ordinal(),actual.ordinal());assertEquals(expected.completesRequest(),actual.completesRequest());pieces++;scans+=actual.memberRanges().stream().mapToLong(r->r.endFace()-r.firstFace()).sum();if(expected.completesRequest())break;}
        }
        assertTrue(f.queue().isEmpty());assertEquals(pieces,number(f.call("progress"),"producedPieces"));assertEquals(scans,number(f.call("progress"),"faceScansDone"));assertEquals(pages.size()*66L,number(f.call("progress"),"haloRowsDone"));
        var reused=new Fixture(f.source);reused.worker(f.source,Set.copyOf(pages.keySet())).run();var timing=reused.timing("workerTiming");assertEquals("COMPLETED",read(timing,"stop").toString());assertEquals(0,number(timing,"sourceWallNanos"));assertEquals(0,number(timing,"haloWallNanos"));assertEquals(0,number(timing,"meshWallNanos"));assertEquals(1,number(timing,"outputsOffered"));assertEquals(0,number(timing,"outputOfferTimeouts"));assertEquals(0,number(reused.call("progress"),"producedPieces"));
    }

    @Test void scalarTimingSaturatesWithoutThrowingAndCannotRetainCorpus()throws Exception{
        var add=NativeVisualStream.class.getDeclaredMethod("addTiming",long.class,long.class);add.setAccessible(true);assertEquals(Long.MAX_VALUE,add.invoke(null,Long.MAX_VALUE-1,5L));assertEquals(Long.MAX_VALUE,add.invoke(null,Long.MAX_VALUE,1L));assertEquals(12L,add.invoke(null,7L,5L));
        for(Class<?> type:List.of(NativeVisualStream.PumpTiming.class,NativeVisualStream.WorkerTiming.class))for(var component:type.getRecordComponents())assertTrue(component.getType().isPrimitive()||component.getType().isEnum(),component.toString());
        Class<?> holder=Class.forName(STREAM+"$WorkerCounters");for(var field:holder.getDeclaredFields())assertTrue(field.getType().isPrimitive()||field.getType().isEnum(),field.toString());
    }
}
