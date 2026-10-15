package net.xmilon.craftpy.util;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.xmilon.craftpy.CraftPy;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Central thread pools for all background work.
 * (a) IO: for file operations, pack building, etc. (serialized per project).
 * (b) RPC: for Python worker communication.
 * (c) UI: for background IDE tasks (indexing, thumbnails).
 */
public final class CraftPyExecutors {
    private static ExecutorService ioPool;
    private static ExecutorService rpcPool;
    private static ExecutorService uiPool;

    private static final Map<String, Executor> projectExecutors = new ConcurrentHashMap<>();

    private CraftPyExecutors() {
    }

    private static synchronized void ensurePools() {
        if (ioPool == null || ioPool.isShutdown()) {
            ioPool = createPool("IO", 4, 100, new ThreadPoolExecutor.CallerRunsPolicy());
        }
        if (rpcPool == null || rpcPool.isShutdown()) {
            rpcPool = createPool("RPC", 4, 100, new ThreadPoolExecutor.DiscardOldestPolicy());
        }
        if (uiPool == null || uiPool.isShutdown()) {
            uiPool = createPool("UI", 2, 50, new ThreadPoolExecutor.CallerRunsPolicy());
        }
    }

    /**
     * A full queue must not lose work: the IO and UI pools make the submitting thread run the task itself (back pressure),
     * only the RPC pool (events, ticks, where a stale task is worth nothing) drops its oldest.
     */
    private static ExecutorService createPool(String name, int size, int queueCapacity,
                                              java.util.concurrent.RejectedExecutionHandler whenFull) {
        return new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ThreadFactoryBuilder()
                        .setNameFormat("CraftPy-" + name + "-%d")
                        .setDaemon(true)
                        .setUncaughtExceptionHandler((t, e) -> CraftPy.LOGGER.error("Uncaught exception on thread {}: ", t.getName(), e))
                        .build(),
                whenFull);
    }

    public static Executor io() {
        ensurePools();
        return ioPool;
    }

    public static Executor rpc() {
        ensurePools();
        return rpcPool;
    }

    public static Executor ui() {
        ensurePools();
        return uiPool;
    }

    /** Returns the shared IO pool. Kept for backwards compatibility during migration. */
    public static ExecutorService pool() {
        ensurePools();
        return ioPool;
    }

    /** Returns a serial executor that runs tasks on the IO pool, but one at a time for the given project. */
    public static Executor forProject(String projectId) {
        if (projectId == null) return io();
        return projectExecutors.computeIfAbsent(projectId, id -> new SerialExecutor(io()));
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return runAsync(runnable, io());
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
        return CompletableFuture.runAsync(wrap(runnable), executor);
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return supplyAsync(supplier, io());
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor) {
        return CompletableFuture.supplyAsync(wrap(supplier), executor);
    }

    private static Runnable wrap(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                CraftPy.LOGGER.error("Async task failed on thread {}: ", Thread.currentThread().getName(), t);
                throw t;
            }
        };
    }

    private static <T> Supplier<T> wrap(Supplier<T> s) {
        return () -> {
            try {
                return s.get();
            } catch (Throwable t) {
                CraftPy.LOGGER.error("Async task failed on thread {}: ", Thread.currentThread().getName(), t);
                throw t;
            }
        };
    }

    public static synchronized void shutdown() {
        if (ioPool != null) ioPool.shutdownNow();
        if (rpcPool != null) rpcPool.shutdownNow();
        if (uiPool != null) uiPool.shutdownNow();
        projectExecutors.clear();
    }

    /** A simple wrapper that serializes task execution on a delegate executor. */
    private static class SerialExecutor implements Executor {
        private final Executor delegate;
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private boolean running = false;

        public SerialExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void execute(Runnable command) {
            tasks.offer(() -> {
                try {
                    command.run();
                } finally {
                    scheduleNext();
                }
            });
            if (!running) {
                scheduleNext();
            }
        }

        private synchronized void scheduleNext() {
            Runnable next = tasks.poll();
            if (next != null) {
                running = true;
                delegate.execute(next);
            } else {
                running = false;
            }
        }
    }
}
