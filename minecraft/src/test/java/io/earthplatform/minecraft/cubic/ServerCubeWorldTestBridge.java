package io.earthplatform.minecraft.cubic;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import it.unimi.dsi.fastutil.longs.LongPredicate;

/** Test-only bridge exposing package-private {@link ServerCubeWorld} pregeneration guards to cross-package tests. */
public final class ServerCubeWorldTestBridge {
    private ServerCubeWorldTestBridge() {}

    public static LongArrayList coordinatePregenerateKeys(LongArrayList candidates, boolean onServerThread,
                                                          BooleanSupplier isServerThread, Executor serverThread,
                                                          BooleanSupplier isClosed, LongPredicate isLoaded,
                                                          long timeoutMillis) {
        var untouched = new ServerCubeWorld.UntouchedStoreQueue<String>(
                () -> false, key -> null, (key, tag) -> {}, entry -> true, (key, err) -> {});
        return ServerCubeWorld.coordinatePregenerateKeys(
                candidates, onServerThread, isServerThread, serverThread, isClosed, isLoaded, untouched, timeoutMillis);
    }

    public static void submitPregeneratedCommit(Executor serverThread, BooleanSupplier isServerThread,
                                                BooleanSupplier isClosed, Runnable commitOnServerThread) {
        ServerCubeWorld.submitPregeneratedCommit(serverThread, isServerThread, isClosed, commitOnServerThread);
    }
}
