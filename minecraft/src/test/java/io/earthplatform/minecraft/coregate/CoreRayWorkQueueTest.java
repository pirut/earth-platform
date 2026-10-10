package io.earthplatform.minecraft.coregate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CoreRayWorkQueueTest {
 @Test void runningAndQueuedWorkStayPhysicallyChargedAfterCancellation() throws Exception {
  var queue=new CoreRayWorkQueue();var started=new CountDownLatch(1);var release=new CountDownLatch(1);var cancelled=new AtomicBoolean();var ranSecond=new AtomicBoolean();
  try{var first=queue.submit(cancelled,()->{started.countDown();await(release);return 1;});assertTrue(started.await(5,TimeUnit.SECONDS));var second=queue.submit(new AtomicBoolean(),()->{ranSecond.set(true);return 2;});cancelled.set(true);first.cancel(true);assertEquals(1,queue.running());assertEquals(1,queue.queued());assertThrows(RejectedExecutionException.class,()->queue.submit(new AtomicBoolean(),()->3));assertFalse(ranSecond.get());release.countDown();assertEquals(2,second.get(5,TimeUnit.SECONDS));assertTrue(first.isCancelled());}finally{release.countDown();queue.close();}
 }
 @Test void retiringSessionStopsQueuedAndCompletedPublication() throws Exception {
  var queue=new CoreRayWorkQueue();var started=new CountDownLatch(1);var release=new CountDownLatch(1);var retired=new AtomicBoolean();var queuedRan=new AtomicBoolean();
  try{var first=queue.submit(retired,()->{started.countDown();await(release);return 1;});assertTrue(started.await(5,TimeUnit.SECONDS));var second=queue.submit(retired,()->{queuedRan.set(true);return 2;});retired.set(true);release.countDown();assertThrows(CancellationException.class,()->first.join());assertThrows(CancellationException.class,()->second.join());assertFalse(queuedRan.get());}finally{release.countDown();queue.close();}
 }
 @Test void closeRejectsNewWorkButDrainsAdmittedWork() throws Exception {var queue=new CoreRayWorkQueue();var started=new CountDownLatch(1);var release=new CountDownLatch(1);try{var first=queue.submit(new AtomicBoolean(),()->{started.countDown();await(release);return 1;});assertTrue(started.await(5,TimeUnit.SECONDS));var second=queue.submit(new AtomicBoolean(),()->2);queue.close();assertFalse(queue.terminated());assertThrows(RejectedExecutionException.class,()->queue.submit(new AtomicBoolean(),()->3));release.countDown();assertEquals(1,first.get(5,TimeUnit.SECONDS));assertEquals(2,second.get(5,TimeUnit.SECONDS));}finally{release.countDown();queue.close();}}
 private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
}
