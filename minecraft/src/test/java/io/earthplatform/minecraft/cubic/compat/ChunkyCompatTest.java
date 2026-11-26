package io.earthplatform.minecraft.cubic.compat;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.ServerCubeWorldTestBridge;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkyCompatTest {

    @Test
    void generateAsyncPropagatesCoordinatePregenerateKeysShutdownRejection() {
        int failuresBefore = ChunkyCompat.failureCount();
        LongArrayList candidates = LongArrayList.of(CubeKey.pack(4, 5, -7));

        CompletableFuture<Void> future = ChunkyCompat.generateAsync(
                4,
                -7,
                () -> ServerCubeWorldTestBridge.coordinatePregenerateKeys(
                        candidates,
                        false,
                        () -> false,
                        Runnable::run,
                        () -> false,
                        key -> {
                            fail("isLoaded must not be accessed off server thread");
                            return false;
                        },
                        100L),
                Runnable::run);

        assertTrue(future.isCompletedExceptionally(), "shutdown rejection must fail the returned future");
        CompletionException completion = assertThrows(CompletionException.class, future::join);
        ServerCubeWorld.PregenerationRejectedException rejected =
                assertInstanceOf(ServerCubeWorld.PregenerationRejectedException.class, completion.getCause());
        assertTrue(rejected.getMessage().contains("no longer scheduling"), rejected.getMessage());
        assertEquals(failuresBefore, ChunkyCompat.failureCount(),
                "shutdown rejection must not be counted as a tile-server failure");
    }

    @Test
    void generateAsyncPropagatesSubmitPregeneratedCommitShutdownRejection() {
        int failuresBefore = ChunkyCompat.failureCount();

        // Before ServerCubeWorld.close() sets closing=true: server thread stopped scheduling and ran execute() inline.
        CompletableFuture<Void> beforeClose = ChunkyCompat.generateAsync(
                4,
                -7,
                () -> ServerCubeWorldTestBridge.submitPregeneratedCommit(
                        false,
                        () -> false,
                        Runnable::run,
                        () -> false,
                        () -> fail("commit must not execute off server thread"),
                        100L),
                Runnable::run);

        assertTrue(beforeClose.isCompletedExceptionally());
        CompletionException beforeCompletion = assertThrows(CompletionException.class, beforeClose::join);
        ServerCubeWorld.PregenerationRejectedException beforeRejected =
                assertInstanceOf(ServerCubeWorld.PregenerationRejectedException.class, beforeCompletion.getCause());
        assertTrue(beforeRejected.getMessage().contains("no longer scheduling"), beforeRejected.getMessage());

        // After ServerCubeWorld.close() sets closing=true: still rejects off-thread inline execution so Chunky never marks unwritten column done.
        CompletableFuture<Void> whileClosing = ChunkyCompat.generateAsync(
                4,
                -7,
                () -> ServerCubeWorldTestBridge.submitPregeneratedCommit(
                        false,
                        () -> false,
                        Runnable::run,
                        () -> true,
                        () -> fail("commit must not execute off server thread when closing"),
                        100L),
                Runnable::run);

        assertTrue(whileClosing.isCompletedExceptionally());
        CompletionException closingCompletion = assertThrows(CompletionException.class, whileClosing::join);
        ServerCubeWorld.PregenerationRejectedException closingRejected =
                assertInstanceOf(ServerCubeWorld.PregenerationRejectedException.class, closingCompletion.getCause());
        assertTrue(closingRejected.getMessage().contains("closing"), closingRejected.getMessage());

        assertEquals(failuresBefore, ChunkyCompat.failureCount(),
                "shutdown rejections must not increment tile-server failure counter");
    }

    @Test
    void generateAsyncFailsWhenWorldClosesAfterQueuingCommitOnServerThread() throws Exception {
        int failuresBefore = ChunkyCompat.failureCount();
        AtomicBoolean closing = new AtomicBoolean(false);
        AtomicInteger commitCalls = new AtomicInteger();
        BlockingQueue<Runnable> serverQueue = new LinkedBlockingQueue<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Void> future = ChunkyCompat.generateAsync(
                    4,
                    -7,
                    () -> ServerCubeWorldTestBridge.submitPregeneratedCommit(
                            false,
                            () -> true,
                            serverQueue::add,
                            closing::get,
                            commitCalls::incrementAndGet,
                            5_000L),
                    worker);

            Runnable queuedTask = serverQueue.poll(5, TimeUnit.SECONDS);
            assertNotNull(queuedTask, "commit task must be queued on server thread");
            assertFalse(future.isDone(), "Chunky future must not complete before the server-thread commit finishes");

            // World starts closing after the commit task was queued but before the server thread executes it.
            closing.set(true);
            assertDoesNotThrow(queuedTask::run, "queued server-thread commit task must never throw into server executor");
            assertEquals(0, commitCalls.get(), "commit callback must never run once world is closing");

            ExecutionException execution = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            ServerCubeWorld.PregenerationRejectedException rejected =
                    assertInstanceOf(ServerCubeWorld.PregenerationRejectedException.class, execution.getCause());
            assertTrue(rejected.getMessage().contains("closing"), rejected.getMessage());
            assertTrue(future.isCompletedExceptionally());
            assertEquals(failuresBefore, ChunkyCompat.failureCount(),
                    "shutdown rejection must not increment tile-server failure counter");
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void generateAsyncSwallowsTransientRuntimeExceptionSoChunkyContinues() {
        int failuresBefore = ChunkyCompat.failureCount();

        CompletableFuture<Void> future = ChunkyCompat.generateAsync(
                12,
                34,
                () -> {
                    throw new UncheckedIOException(new IOException("transient tile server outage"));
                },
                Runnable::run);

        assertFalse(future.isCompletedExceptionally(),
                "transient map-data failure must be caught so Chunky carries on with the next chunk");
        assertDoesNotThrow(future::join);
        assertEquals(failuresBefore + 1, ChunkyCompat.failureCount());
    }
}
