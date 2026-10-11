package net.xmilon.craftpy.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Central thread pool for all blocking work (file IO, Python calls, pack
 * building). Nothing on this pool may touch game state directly; results are
 * always applied back on the owning thread via {@code server.execute(...)} or
 * {@code Minecraft.getInstance().execute(...)}.
 */
public final class CraftPyExecutors {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final ThreadFactory FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "CraftPy-Worker-" + COUNTER.incrementAndGet());
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((t, e) -> net.xmilon.craftpy.CraftPy.LOGGER
                .error("Uncaught exception on {}", t.getName(), e));
        return thread;
    };

    private static final ExecutorService IO = Executors.newFixedThreadPool(
            4, FACTORY);

    private CraftPyExecutors() {
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, IO);
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, IO);
    }

    public static ExecutorService pool() {
        return IO;
    }

    public static void shutdown() {
        IO.shutdownNow();
    }
}
