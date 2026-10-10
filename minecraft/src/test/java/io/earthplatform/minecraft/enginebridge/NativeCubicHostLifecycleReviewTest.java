package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.network.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeCubicHostLifecycleReviewTest {
  static EnginePaletteContext palette;
  static WorldIdentity identity;
  static int stone, gold, air;
  @TempDir Path directory;
  ThreadPoolExecutor bootstrap;
  List<NativeCubicHost> hosts = new ArrayList<>();
  List<CountDownLatch> gates = new ArrayList<>();
  @BeforeAll static void registries() {
    var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
    biomes.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
    palette=EnginePaletteContext.create(biomes.freeze());
    identity=new WorldIdentity(91,"review","v1","review-fixture",NativeCubeCodec.paletteIdentity(palette));
    stone=Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
    gold=Block.BLOCK_STATE_REGISTRY.getId(Blocks.GOLD_BLOCK.defaultBlockState());
    air=Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
  }
  @BeforeEach void setup() { bootstrap=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),new ThreadPoolExecutor.AbortPolicy()); }
  @AfterEach void cleanup() throws Exception {
    gates.forEach(CountDownLatch::countDown);
    for(var host:hosts) { host.beginClose(); await(()->host.pollClose().complete()); }
    bootstrap.shutdown(); assertTrue(bootstrap.awaitTermination(10,TimeUnit.SECONDS));
  }
  static void await(BooleanSupplier condition) throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
    while(!condition.getAsBoolean()) { if(System.nanoTime()>deadline) fail("Timed out"); Thread.sleep(1); }
  }
  CubeProvider stoneProvider() { return (p,t)->CompletableFuture.completedFuture(ImmutableCube.uniform(stone)); }
  NativeCubicHost open(CubeProvider provider) throws Exception {
    var host=NativeCubicHost.openAsync(directory,identity,provider,palette,new NativeCubicHost.Limits(1,1,1,2),bootstrap).get(10,TimeUnit.SECONDS);
    hosts.add(host); return host;
  }
  static EngineCubeSnapshot ready(NativeCubicHost host,CubePos p) throws Exception {
    await(()->host.pollSnapshot(p,Long.MAX_VALUE,Long.MAX_VALUE).status()!=NativeCubicHost.Status.LOADING);
    var result=host.pollSnapshot(p,Long.MAX_VALUE,Long.MAX_VALUE);
    assertEquals(NativeCubicHost.Status.READY,result.status(),()->String.valueOf(result.failure())); return result.snapshot();
  }
  @Test void closeCopiesCannotAbandonResourcesAndCallbacksMayReenterAndReopen() throws Exception {
    var host=open(stoneProvider());
    var cancelled=host.beginClose(); assertTrue(cancelled.cancel(false));
    var forged=host.beginClose(); assertTrue(forged.complete(null));
    assertEquals(NativeCubicHost.Lifecycle.CLOSING,host.lifecycle());
    AtomicInteger callbacks=new AtomicInteger();
    AtomicReference<CompletableFuture<NativeCubicHost>> replacement=new AtomicReference<>();
    var observed=host.beginClose().thenRun(()->{
      assertEquals(NativeCubicHost.Lifecycle.CLOSED,host.lifecycle());
      assertTrue(host.pollClose().complete()); assertTrue(host.beginClose().isDone());
      assertEquals(0,host.stats().loader().running()); callbacks.incrementAndGet();
      replacement.set(NativeCubicHost.openAsync(directory,identity,stoneProvider(),palette,new NativeCubicHost.Limits(1,1,1,2),bootstrap));
    });
    await(()->host.pollClose().complete()); observed.get(10,TimeUnit.SECONDS);
    assertEquals(1,callbacks.get());
    var reopened=replacement.get().get(10,TimeUnit.SECONDS); hosts.add(reopened);
    assertEquals(NativeCubicHost.Lifecycle.OPEN,reopened.lifecycle());
  }
  @Test void providerThrowNullAndAsyncFailureEachRetireBeforeExplicitRetry() throws Exception {
    AtomicInteger attempts=new AtomicInteger(); IOException original=new IOException("upstream");
    var host=open((p,t)->switch(attempts.incrementAndGet()) {
      case 1 -> throw new IllegalArgumentException("synchronous failure");
      case 2 -> null;
      case 3 -> CompletableFuture.failedFuture(original);
      default -> CompletableFuture.completedFuture(ImmutableCube.uniform(stone));
    });
    var p=new CubePos(-1,-1,-1); host.updateTicket("camera",p,0,0);
    for(Class<? extends Throwable> failure:List.of(IllegalArgumentException.class,NullPointerException.class,IOException.class)) {
      await(()->host.pollSnapshot(p,1,1).status()==NativeCubicHost.Status.FAILED);
      assertInstanceOf(failure,host.pollSnapshot(p,1,1).failure());
      assertEquals(0,host.stats().loader().running()); host.retry(p);
    }
    assertSame(Blocks.STONE.defaultBlockState(),ready(host,p).materialize(palette).get(0,0,0));
    assertEquals(4,attempts.get());
  }
  @Test void exactSignedNativeSectionKeyBoundariesRemainLosslessAndAdjacentOverflowIsAtomic() throws Exception {
    var host=open(stoneProvider());
    for(int x:new int[]{-2097152,2097151}) for(int y:new int[]{-524288,524287}) for(int z:new int[]{-2097152,2097151}) {
      var p=new CubePos(x,y,z); host.updateTicket("camera",p,0,0);
      var snap=ready(host,p); assertEquals(p,snap.position());
      assertEquals(Long.MAX_VALUE,snap.epoch()); assertEquals(Long.MAX_VALUE,snap.leaseGeneration());
      assertEquals(1L,host.setBlockId(p.minBlockX()+15,p.minBlockY()+15,p.minBlockZ()+15,air).get(10,TimeUnit.SECONDS));
      assertEquals(1,ready(host,p).revision()); assertTrue(ready(host,p).materialize(palette).get(15,15,15).isEmpty());
    }
    var before=host.residentPositions();
    for(var p:List.of(new CubePos(-2097153,0,0),new CubePos(2097152,0,0),new CubePos(0,-524289,0),new CubePos(0,524288,0),new CubePos(0,0,-2097153),new CubePos(0,0,2097152))) {
      assertThrows(IllegalArgumentException.class,()->host.updateTicket("camera",p,0,0));
      assertEquals(before,host.residentPositions());
    }
  }
  @Test void saturatedEditQueueRejectsExcessButCancellationAndEvictionCannotLoseAcceptedWrites() throws Exception {
    var host=open(stoneProvider()); var p=new CubePos(-1,-1,-1); host.updateTicket("camera",p,0,0); ready(host,p);
    var entered=new CountDownLatch(1); var release=new CountDownLatch(1); gates.add(release);
    var worldField=NativeCubicHost.class.getDeclaredField("world"); worldField.setAccessible(true);
    var ioField=CubicWorld.class.getDeclaredField("io"); ioField.setAccessible(true);
    ((ThreadPoolExecutor)ioField.get(worldField.get(host))).execute(()->{
      entered.countDown(); try { release.await(); } catch(InterruptedException ex) { throw new AssertionError(ex); }
    });
    assertTrue(entered.await(10,TimeUnit.SECONDS));
    var first=host.setBlockId(-1,-1,-1,air); var second=host.setBlockId(-2,-1,-1,gold);
    assertTrue(first.cancel(false)); assertEquals(2,host.stats().pendingEdits());
    assertThrows(RejectedExecutionException.class,()->host.setBlockId(-3,-1,-1,gold));
    host.removeTicket("camera"); var closing=host.beginClose(); assertFalse(host.pollClose().storeSettled());
    release.countDown(); assertEquals(2L,second.get(10,TimeUnit.SECONDS));
    await(()->host.pollClose().complete()); closing.get(10,TimeUnit.SECONDS);
    var reopened=open(stoneProvider()); reopened.updateTicket("camera",p,0,0); var snapshot=ready(reopened,p);
    assertEquals(2,snapshot.revision());
    assertSame(Blocks.AIR.defaultBlockState(),snapshot.materialize(palette).get(15,15,15));
    assertSame(Blocks.GOLD_BLOCK.defaultBlockState(),snapshot.materialize(palette).get(14,15,15));
    assertSame(Blocks.STONE.defaultBlockState(),snapshot.materialize(palette).get(13,15,15));
  }
  @Test void ownerOnlyObservationsRejectCrossThreadUseEvenAfterClosure() throws Exception {
    var host=open(stoneProvider());
    bootstrap.submit(()->{
      assertThrows(IllegalStateException.class,host::identity); assertThrows(IllegalStateException.class,host::directory);
      assertThrows(IllegalStateException.class,host::limits); assertThrows(IllegalStateException.class,host::lifecycle);
      assertThrows(IllegalStateException.class,host::residentPositions); assertThrows(IllegalStateException.class,host::pollClose);
      assertThrows(IllegalStateException.class,()->host.removeTicket("camera"));
      assertThrows(IllegalStateException.class,()->host.retry(new CubePos(0,0,0)));
      assertThrows(IllegalStateException.class,()->host.setBlockId(0,0,0,stone));
    }).get(10,TimeUnit.SECONDS);
    host.beginClose(); await(()->host.pollClose().complete());
    bootstrap.submit(()->assertThrows(IllegalStateException.class,host::stats)).get(10,TimeUnit.SECONDS);
  }
}
