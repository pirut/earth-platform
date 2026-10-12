package io.earthplatform.minecraft.lod;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Actual job scheduler/View lifetime with fake GL; no claim of a real-context upload. */
@Timeout(15)
class DhNativeDistanceCpuLifetimeTest {
 static final WorldIdentity WORLD=new WorldIdentity(0,"cpu-test","1","cpu-source","palette");
 static final LodSummaryKey KEY=new LodSummaryKey(WORLD,"cpu-source",new LodSummaryKey.Volume(0,0,0,16,16,16),0,0,"cpu");
 static final LodJobStamp STAMP=new LodJobStamp(1,1,1);
 record Data(long declaredBytes) implements BoundedLodSummaryJobs.Payload{}
 static class Manual implements Executor{final Queue<Runnable> tasks=new ArrayDeque<>();public void execute(Runnable r){tasks.add(r);}void run(){tasks.remove().run();}}
 static BoundedLodSummaryJobs.Limits limits(){return new BoundedLodSummaryJobs.Limits(1,1,1,0,0,1024);}
 @Test void subscriberCancellationDoesNotReleasePhysicalSource(){
  var executor=new Manual();var stage=new CompletableFuture<Data>();var jobs=new BoundedLodSummaryJobs<Data>((k,c,b)->stage,executor,limits());
  var lease=jobs.request(KEY,STAMP);executor.run();jobs.close();assertTrue(lease.future().isCancelled());assertTrue(jobs.physicalWorkPending());assertFalse(jobs.physicalDrainComplete());
  stage.complete(new Data(16));assertTrue(jobs.physicalDrainComplete());assertEquals(0,jobs.stats().cachedItems());
 }
 @Test void cancelledExecutorQueuedInvocationStillOwnsItsSlot(){
  var executor=new Manual();var jobs=new BoundedLodSummaryJobs<Data>((k,c,b)->{fail("cancelled source must not start");return null;},executor,limits());
  jobs.request(KEY,STAMP);jobs.close();assertFalse(jobs.physicalDrainComplete());executor.run();assertTrue(jobs.physicalDrainComplete());
 }
 @Test void resultConsumerCompletionIsPartOfPhysicalDrain() throws Exception{
  var executor=new Manual();var stage=new CompletableFuture<Data>();var jobs=new BoundedLodSummaryJobs<Data>((k,c,b)->stage,executor,limits());
  var lease=jobs.request(KEY,STAMP);executor.run();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  lease.future().thenRun(()->{entered.countDown();await(release);});
  Thread worker=new Thread(()->stage.complete(new Data(16)));worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
  try{assertEquals(0,jobs.stats().running());jobs.close();assertFalse(jobs.physicalDrainComplete());}finally{release.countDown();worker.join(5000);}
  assertFalse(worker.isAlive());assertTrue(jobs.physicalDrainComplete());
 }
 @Test void exceptionalStageDrainsWithoutHidingOriginalFailure(){
  var executor=new Manual();var stage=new CompletableFuture<Data>();var jobs=new BoundedLodSummaryJobs<Data>((k,c,b)->stage,executor,limits());
  var lease=jobs.request(KEY,STAMP);executor.run();var fault=new IllegalStateException("source");stage.completeExceptionally(fault);
  assertSame(fault,assertThrows(CompletionException.class,()->lease.future().join()).getCause());jobs.close();assertTrue(jobs.physicalDrainComplete());
 }
 @Test void strictPhysicalSlotPreventsSuccessorWhileCompletionConsumerRetainsResult() throws Exception{
  var executor=new Manual();var stage=new CompletableFuture<Data>();var calls=new java.util.concurrent.atomic.AtomicInteger();
  var jobs=new BoundedLodSummaryJobs<Data>((k,c,b)->{calls.incrementAndGet();return stage;},executor,limits(),true);
  var first=jobs.request(KEY,STAMP);executor.run();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  first.future().thenRun(()->{entered.countDown();await(release);});Thread worker=new Thread(()->stage.complete(new Data(16)));worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
  try{var key2=new LodSummaryKey(WORLD,"cpu-source",KEY.volume(),0,0,"next");jobs.request(key2,STAMP);assertEquals(1,jobs.stats().queued());assertTrue(executor.tasks.isEmpty());assertEquals(1,calls.get());}
  finally{release.countDown();worker.join(5000);}
  assertEquals(1,executor.tasks.size());jobs.close();executor.run();assertTrue(jobs.physicalDrainComplete());
 }
 static void await(CountDownLatch latch){try{assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}
 static final class Driver implements DhGeographicLodView.Driver{
  boolean closed,failClose;public void checkRenderThread(){}public String renderer(){return "fake CPU lifetime driver";}
  public DhGeographicLodView.State captureState(){return ()->{};}public void initialize(){}
  public DhGeographicLodView.Mesh upload(DhGeographicLodData.TileKey k,List<DhTerrainGeometry.PageFace> f){throw new AssertionError();}
  public DhGeographicLodView.DrawStats draw(Map<DhGeographicLodData.TileKey,DhGeographicLodView.Mesh> m,Matrix4f p,double x,double y,double z){throw new AssertionError();}
  public void close(){if(failClose)throw new IllegalStateException("retained GL owner");closed=true;}public boolean deleted(){return closed;}
 }
 static DhPreparedLodView view(Driver driver,Executor executor){
  SceneSurfaceSampler sampler=new SceneSurfaceSampler(){public long surfaceY(long x,long z){return 7314;}public int argb(long x,long z,long y){return -1;}public String identity(){return "cpu-source";}public boolean supportsConcurrentReads(){return true;}public boolean supportsFootprintCoverage(){return true;}};
  return new DhPreparedLodView(sampler,WORLD,1,1,0,0,DhLodLayout.EARTH_ALPINE_2048,driver,executor,DhNativeDistanceBudget.configuration());
 }
 @Test void exactGlobalReservationRejectsSecondViewAndReleasesAfterClose(){
  long before=NativeDistanceCpuBudget.global().evidence().bytes();var view=view(new Driver(),Runnable::run);
  try{assertEquals(before+(40L<<20),NativeDistanceCpuBudget.global().evidence().bytes());assertThrows(IllegalStateException.class,()->view(new Driver(),Runnable::run));assertEquals(0,view.diagnostics().get("maxPreparedCacheBytes").getAsLong());}
  finally{view.close();}assertTrue(view.physicalDrainComplete());assertEquals(before,NativeDistanceCpuBudget.global().evidence().bytes());view.close();
 }
 @Test void viewCloseRetainsReservationUntilCancelledInvocationActuallyDrains() throws Exception{
  long before=NativeDistanceCpuBudget.global().evidence().bytes();var executor=new Manual();var view=view(new Driver(),executor);
  // Request on the actual retained scheduler, without forging a drawable visual publication.
  var field=DhPreparedLodView.class.getDeclaredField("jobs");field.setAccessible(true);var jobs=(BoundedLodSummaryJobs<?>)field.get(view);jobs.request(KEY,STAMP);
  assertThrows(IllegalStateException.class,view::close);assertFalse(view.physicalDrainComplete());assertEquals(before+(40L<<20),NativeDistanceCpuBudget.global().evidence().bytes());
  executor.run();assertFalse(view.physicalDrainComplete());view.close();assertTrue(view.physicalDrainComplete());assertEquals(before,NativeDistanceCpuBudget.global().evidence().bytes());
 }
 @Test void successfullyDrainedViewDetachesSharedSourceAndCompilerAliases() throws Exception{
  var view=view(new Driver(),Runnable::run);var sampler=DhPreparedLodView.class.getDeclaredField("sampler");sampler.setAccessible(true);assertNotNull(sampler.get(view));
  view.close();assertNull(sampler.get(view));var source=DhPreparedLodView.class.getDeclaredField("cpuSource");source.setAccessible(true);var sourceObject=source.get(view);var captured=sourceObject.getClass().getDeclaredField("sampler");captured.setAccessible(true);assertNull(captured.get(sourceObject));
  var compiler=DhPreparedLodView.class.getDeclaredField("compiler");compiler.setAccessible(true);assertNull(((java.util.concurrent.atomic.AtomicReference<?>)compiler.get(view)).get());assertTrue(view.physicalDrainComplete());
 }
 @Test void failedPhysicalGlCleanupRetainsCpuReservationForRetry(){
  long before=NativeDistanceCpuBudget.global().evidence().bytes();var driver=new Driver();var view=view(driver,Runnable::run);driver.failClose=true;
  assertThrows(IllegalStateException.class,view::close);assertFalse(view.physicalDrainComplete());assertEquals(before+(40L<<20),NativeDistanceCpuBudget.global().evidence().bytes());
  driver.failClose=false;view.close();assertTrue(view.physicalDrainComplete());assertEquals(before,NativeDistanceCpuBudget.global().evidence().bytes());
 }
}
