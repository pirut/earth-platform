package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import io.earthplatform.minecraft.visual.EarthDistanceSurfacePlan;
import io.earthplatform.minecraft.visual.NativeDistanceLimits;
import io.earthplatform.minecraft.visual.NativeVisualStamp;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;

/** Headless completed-work evidence only; never authenticated GPU/capsule or draw authority. */
class NativeVisualCompletedProgressTest {
    private static final String HASH="0123456789abcdef".repeat(4);
    private static final CubePos CENTER=new CubePos(0,0,0);
    private static final EarthDistanceSurfacePlan.Limits LIMITS=NativeDistanceLimits.sourceLimits();
    private static final EarthAlpineDefinition.Envelope ENVELOPE=new EarthAlpineDefinition.Envelope(0,1,0,1,-16,16,-4096,4096,-4096,4096);
    private static final NativeVisualCapsule CAPSULE=new NativeVisualCapsule(1,HASH,HASH,HASH,new byte[NativeVisualCapsule.TABLE_BYTES]);
    private static EarthDistanceSurfacePlan.Sample sample(){return new EarthDistanceSurfacePlan.Sample(-1,5,3,7,0,-1,0);}
    private static EarthDistanceSurfacePlan.Source source(int guard,EarthDistanceSurfacePlan.CompletedWork progress){
        return EarthDistanceSurfacePlan.Source.prepareSampled(ENVELOPE,-256,256,HASH,HASH,HASH,2,guard,LIMITS,(x,z)->sample(),progress);
    }
    private static EarthReplacementCapture capture(EarthDistanceSurfacePlan.Source source){
        try(var b=new EarthReplacementCapture.Builder(CENTER)){
            for(int y=-2;y<18;y++)for(int z=-2;z<18;z++)for(int x=-2;x<18;x++)b.setNext(source.materialAt(x,y,z));
            return b.finish();
        }
    }
    private static NativeVisualStamp stamp(String captureIdentity){return new NativeVisualStamp(new WorldIdentity(1,"test","test","test","test"),new UUID(1,2),1,1,1,0,2,2,0,HASH,HASH,HASH,HASH,captureIdentity);}
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static Object job(EarthReplacementCapture capture)throws Exception{
        Class<?> type=Class.forName(NativeVisualStream.class.getName()+"$Job");var ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);
        return ctor.newInstance(stamp(capture.contentIdentity()),CENTER,capture);
    }
    private static NativeVisualStream stream(Object job)throws Exception{
        Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);var unsafe=(Unsafe)field.get(null);
        var stream=(NativeVisualStream)unsafe.allocateInstance(NativeVisualStream.class);
        set(stream,"owner",Thread.currentThread());set(stream,"job",job);set(stream,"cache",new LinkedHashMap<>());
        set(stream,"physical",Collections.newSetFromMap(new IdentityHashMap<>()));return stream;
    }
    private static Runnable worker(Object job,EarthDistanceSurfacePlan.Source source,Set<String> reused)throws Exception{
        Class<?> type=Class.forName(NativeVisualStream.class.getName()+"$Worker");var ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);
        return(Runnable)ctor.newInstance(job,reused,null,CAPSULE,new AtomicReference<>(source));
    }
    private static byte[] bytes(NativeVisualCpuPiece piece){var b=ByteBuffer.allocate(piece.byteLength());piece.copyTo(b);return b.array();}

    @Test void sourceRowsPublishOnlyCompleteValidatedStoredAndDigestedColumns(){
        var baseline=source(0,null);long width=baseline.bounds().width();var calls=new AtomicLong();var rows=new ArrayList<Long>();
        var observed=EarthDistanceSurfacePlan.Source.prepareSampled(ENVELOPE,-256,256,HASH,HASH,HASH,2,0,LIMITS,
                (x,z)->{calls.incrementAndGet();return sample();},done->{assertEquals(calls.get(),done);assertEquals(0,done%width);rows.add(done);});
        assertEquals(observed.bounds().depth(),rows.size());assertEquals(observed.sampledColumns(),rows.getLast().longValue());
        for(int i=0;i<rows.size();i++)assertEquals((i+1)*width,rows.get(i).longValue());
        assertEquals(baseline.identity(),observed.identity());assertEquals(baseline.sampleIdentity(),observed.sampleIdentity());
        // Independent big-endian (-1L, 477L, -1.0, 0.0) digest for the 130x130 fixture slab.
        assertEquals("3df38001ed21970a9c0f7523a49d028698900c4825aebafa4868d6e0a4415f9c",observed.sampleIdentity());
        assertEquals(baseline.bounds(),observed.bounds());
        for(long z=observed.bounds().minZ();z<observed.bounds().maxZExclusive();z++)for(long x=observed.bounds().minX();x<observed.bounds().maxXExclusive();x++){
            assertEquals(baseline.surfaceY(x,z),observed.surfaceY(x,z));assertEquals(baseline.materialAt(x,-1,z),observed.materialAt(x,-1,z));
        }
        rows.clear();calls.set(0);
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.Source.prepareSampled(ENVELOPE,-256,256,HASH,HASH,HASH,2,0,LIMITS,
                (x,z)->calls.incrementAndGet()==width+3?new EarthDistanceSurfacePlan.Sample(255,5,3,7,0,255,0):sample(),rows::add));
        assertEquals(List.of(width),rows,"An invalid partially stored second row must not be credited");
        rows.clear();calls.set(0);var tiny=new EarthDistanceSurfacePlan.Limits(10,1024,10,1,1,1,232,1024);
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.Source.prepareSampled(ENVELOPE,-256,256,HASH,HASH,HASH,2,0,tiny,
                (x,z)->{calls.incrementAndGet();return sample();},rows::add));
        assertEquals(0,calls.get());assertTrue(rows.isEmpty());
    }

    @Test void planTilesPublishAfterFullCensusHashAndPageRecordsWithoutChangingGeometry(){
        var source=source(0,null);var baseline=EarthDistanceSurfacePlan.build(source,null,CENTER,2,0,LIMITS);var tiles=new ArrayList<Long>();
        var observed=EarthDistanceSurfacePlan.build(source,null,CENTER,2,0,LIMITS,tiles::add);
        assertEquals(List.of(1L,2L,3L,4L),tiles);assertEquals(baseline.identity(),observed.identity());assertEquals(baseline.pages(),observed.pages());
        assertEquals(baseline.quads(),observed.quads());assertEquals(baseline.pieces(),observed.pieces());
        for(var page:baseline.pages()){
            var expected=new ArrayList<String>();var actual=new ArrayList<String>();
            baseline.forEachFace(page.key(),(x,y,z,d,m)->expected.add(x+":"+y+":"+z+":"+d+":"+m));
            observed.forEachFace(page.key(),(x,y,z,d,m)->actual.add(x+":"+y+":"+z+":"+d+":"+m));assertEquals(expected,actual);
        }
        var oneTile=new EarthDistanceSurfacePlan.Limits(LIMITS.maxSourceColumns(),LIMITS.maxSourceBytes(),LIMITS.maxPlanColumns(),LIMITS.maxPages(),LIMITS.maxPieces(),4096,4096*232L,LIMITS.maxCoverageBytes());
        tiles.clear();assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.build(source,null,CENTER,2,0,oneTile,tiles::add));
        assertEquals(List.of(1L),tiles,"Rejected second tile must not advance progress");
        tiles.clear();assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.build(source,null,CENTER,3,0,LIMITS,tiles::add));assertTrue(tiles.isEmpty());
    }

    @Test void builderBatchesCompletedScansFlushesRemaindersAndPreservesEveryPieceByte(){
        byte[] halo=new byte[NativeVisualCpuBuilder.HALO_BYTES];
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(((x+y+z)&1)==0)halo[NativeVisualCpuBuilder.Input.haloIndex(x,y,z)]=1;
        var input=new NativeVisualCpuBuilder.Input(0,0,0,halo,new int[]{0,63},HASH);
        var baseline=new NativeVisualCpuBuilder(input,CAPSULE,new NativeVisualCpuBuilder.Cancellation());var counts=new ArrayList<Long>();
        var observed=new NativeVisualCpuBuilder(input,CAPSULE,new NativeVisualCpuBuilder.Cancellation(),counts::add);long scans=0;int pieces=0;
        while(true){
            var expected=baseline.nextPiece();var actual=observed.nextPiece();pieces++;
            assertArrayEquals(bytes(expected),bytes(actual));assertEquals(expected.bytesDigest(),actual.bytesDigest());assertEquals(expected.memberRanges(),actual.memberRanges());
            assertEquals(expected.ordinal(),actual.ordinal());assertEquals(expected.completesRequest(),actual.completesRequest());
            scans+=actual.memberRanges().stream().mapToLong(r->r.endFace()-r.firstFace()).sum();assertEquals(scans,counts.getLast().longValue());
            assertEquals(new HashSet<>(counts).size(),counts.size(),"Successful piece flush must not duplicate a completed batch");
            if(actual.completesRequest())break;
        }
        assertEquals(3,pieces);assertEquals(2L*4096*6,scans);assertEquals(4096,counts.getFirst().longValue());
        long previous=0;for(long count:counts){assertTrue(count>previous&&count-previous<=4096);previous=count;}
        var saved=List.copyOf(counts);assertThrows(IllegalStateException.class,observed::nextPiece);assertEquals(saved,counts);
    }

    @Test void cancelledFailedAndRetriedBuilderCallsCannotInventCompletedScans(){
        var counts=new ArrayList<Long>();var cancel=new NativeVisualCpuBuilder.Cancellation();cancel.cancel();
        var input=new NativeVisualCpuBuilder.Input(0,0,0,new byte[NativeVisualCpuBuilder.HALO_BYTES],new int[]{0},HASH);
        var cancelled=new NativeVisualCpuBuilder(input,CAPSULE,cancel,counts::add);
        assertThrows(CancellationException.class,cancelled::nextPiece);assertThrows(IllegalStateException.class,cancelled::nextPiece);assertTrue(counts.isEmpty());
        byte[] halo=new byte[NativeVisualCpuBuilder.HALO_BYTES];halo[NativeVisualCpuBuilder.Input.haloIndex(0,0,0)]=(byte)255;
        var unknown=new NativeVisualCpuBuilder(new NativeVisualCpuBuilder.Input(0,0,0,halo,new int[]{0},HASH),CAPSULE,new NativeVisualCpuBuilder.Cancellation(),counts::add);
        assertThrows(NativeVisualCpuBuilder.UnknownDependency.class,unknown::nextPiece);assertThrows(IllegalStateException.class,unknown::nextPiece);assertTrue(counts.isEmpty());
        var stop=new NativeVisualCpuBuilder.Cancellation();var partial=new NativeVisualCpuBuilder(input,CAPSULE,stop,done->{counts.add(done);stop.cancel();});
        assertThrows(CancellationException.class,partial::nextPiece);assertEquals(List.of(4096L),counts);
        assertThrows(IllegalStateException.class,partial::nextPiece);assertEquals(List.of(4096L),counts);
    }

    /** Gates every attempted piece offer without wall-clock sleeps or crediting retries. */
    private static final class RetryQueue extends ArrayBlockingQueue<Object> {
        final BlockingQueue<Integer> attempts=new LinkedBlockingQueue<>();final Semaphore advance=new Semaphore(0);int tries;
        RetryQueue(){super(2);}
        @Override public boolean offer(Object value,long timeout,TimeUnit unit)throws InterruptedException{
            if(value.getClass().getSimpleName().equals("Plans"))return super.offer(value,timeout,unit);
            attempts.add(++tries);advance.acquire();return false;
        }
    }
    @Test void streamReportsRealPreuploadWorkButPollingAndOfferRetriesLeaveItUnchanged()throws Exception{
        var source=source(1,null);var capture=capture(source);Object job=job(capture);var queue=new RetryQueue();set(job,"output",queue);var stream=stream(job);
        var initial=stream.progress();assertEquals(initial,stream.progress());assertEquals(0,initial.sampledColumnsDone());assertEquals(0,initial.planTilesDone());
        assertEquals(3_200_000,initial.sourceColumnsBound());assertEquals(732,initial.planTilesBound());assertEquals(3072,initial.pagesBound());
        assertEquals(202752,initial.haloRowsBound());assertEquals(4_831_838_208L,initial.faceScansBound());assertEquals(3072,initial.piecesBound());
        Thread thread=new Thread(worker(job,source,Set.of()),"completed-progress-retry-fixture");thread.start();NativeVisualStream.Progress completed;
        try{
            assertEquals(1,queue.attempts.poll(10,TimeUnit.SECONDS));completed=stream.progress();
            assertEquals(0,completed.sampledColumnsDone());assertEquals(0,completed.sourceColumnsBound());assertEquals(20,completed.planTilesDone());assertEquals(20,completed.planTilesBound());
            assertEquals(66,completed.haloRowsDone());assertEquals(16L*4096*6,completed.faceScansDone());assertEquals(1,completed.producedPieces());assertEquals(0,completed.uploadedPieces());
            assertEquals(16,completed.pagesBound());assertEquals(16*66,completed.haloRowsBound());assertEquals(16L*16*4096*6,completed.faceScansBound());assertEquals(16,completed.piecesBound());
            assertEquals(stamp(capture.contentIdentity()),completed.stamp());assertEquals(CENTER,completed.center());assertEquals(capture.contentIdentity(),completed.captureIdentity());
            for(int attempt=2;attempt<=3;attempt++){queue.advance.release();assertEquals(attempt,queue.attempts.poll(10,TimeUnit.SECONDS));assertEquals(completed,stream.progress());}
            for(int poll=0;poll<100;poll++)assertEquals(completed,stream.progress());
        }finally{stream.cancelRequest();queue.advance.release();thread.join(10000);if(thread.isAlive()){thread.interrupt();thread.join(10000);}}
        assertFalse(thread.isAlive());assertNull(get(job,"failure"));var terminal=stream.progress();assertTrue(terminal.workerExited());
        assertEquals(1,terminal.producedPieces());assertEquals(0,terminal.uploadedPieces());assertTrue(stream.drainCancelled());assertNull(get(stream,"job"));assertEquals(terminal,stream.progress());
        assertEquals(terminal,stream.progress());assertTrue(stream.finishRequest());assertEquals(terminal,stream.progress());
        for(var component:NativeVisualStream.Progress.class.getRecordComponents())assertTrue(component.getType().isPrimitive()||Set.of(NativeVisualStamp.class,CubePos.class,String.class).contains(component.getType()));
    }

    @Test void reusedPagesAndPrecancelledWorkersReceiveNoSourceHaloScanOrPieceCredit()throws Exception{
        var source=source(1,null);var capture=capture(source);var cache=EarthDistanceSurfacePlan.build(source,capture,CENTER,2,1,LIMITS);
        var reused=new HashSet<String>();for(var page:cache.pages())reused.add(page.identity());Object job=job(capture);var stream=stream(job);worker(job,source,Set.copyOf(reused)).run();
        assertNull(get(job,"failure"));var progress=stream.progress();assertTrue(progress.workerExited());assertEquals(20,progress.planTilesDone());
        assertEquals(0,progress.sampledColumnsDone());assertEquals(0,progress.haloRowsDone());assertEquals(0,progress.faceScansDone());assertEquals(0,progress.producedPieces());assertEquals(0,progress.uploadedPieces());
        assertEquals(0,progress.pagesBound());assertEquals(0,progress.haloRowsBound());assertEquals(0,progress.faceScansBound());assertEquals(0,progress.piecesBound());
        Object noWork=job(capture);var cancelled=stream(noWork);cancelled.cancelRequest();worker(noWork,source,Set.of()).run();var zero=cancelled.progress();
        assertTrue(zero.workerExited());assertEquals(0,zero.sampledColumnsDone());assertEquals(0,zero.planTilesDone());assertEquals(0,zero.haloRowsDone());assertEquals(0,zero.faceScansDone());assertEquals(0,zero.producedPieces());assertEquals(0,zero.uploadedPieces());
    }

    @Test void physicalClosePreservesSmallFinalSnapshotAfterReleasingCpuOwners()throws Exception{
        var source=source(1,null);var capture=capture(source);var cache=EarthDistanceSurfacePlan.build(source,capture,CENTER,2,1,LIMITS);
        var reused=new HashSet<String>();for(var page:cache.pages())reused.add(page.identity());Object job=job(capture);var stream=stream(job);
        worker(job,source,Set.copyOf(reused)).run();var completed=stream.progress();assertTrue(completed.workerExited());assertEquals(20,completed.planTilesDone());
        var executor=Executors.newSingleThreadExecutor();var sourceHolder=new AtomicReference<>(source);
        set(stream,"executor",executor);set(stream,"source",sourceHolder);set(stream,"cpuReservations",new ArrayList<>());set(stream,"retainedPresentation",Set.of());
        try{stream.close();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));stream.close();}
        finally{executor.shutdownNow();}
        assertTrue(stream.closeComplete());assertNull(get(stream,"job"));assertNull(sourceHolder.get());assertNull(get(stream,"sealed"));
        assertEquals(completed,stream.progress());assertEquals(completed,stream.progress());
    }

    @Test void incompleteHaloSliceCannotAdvanceRowsOrScans()throws Exception{
        var source=EarthDistanceSurfacePlan.Source.prepareSampled(ENVELOPE,-64,256,HASH,HASH,HASH,2,1,LIMITS,(x,z)->sample());
        Object job=job(capture(source));var stream=stream(job);worker(job,source,Set.of()).run();
        assertInstanceOf(EarthDistanceSurfacePlan.Refusal.class,get(job,"failure"));var progress=stream.progress();assertTrue(progress.workerExited());
        assertEquals(20,progress.planTilesDone());assertEquals(0,progress.haloRowsDone());assertEquals(0,progress.faceScansDone());assertEquals(0,progress.producedPieces());
    }
}
