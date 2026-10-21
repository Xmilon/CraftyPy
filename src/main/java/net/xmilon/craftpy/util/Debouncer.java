package net.xmilon.craftpy.util;

import net.xmilon.craftpy.CraftPyLog;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs a job once the requests stop for {@code delayMs}, and never two runs at a time: a request during a run starts one
 * more run after it. Ten asset saves in a row are one pack rebuild and one resource reload, not ten.
 */
public final class Debouncer {
    private final long delayMs;
    private final Supplier<CompletableFuture<?>> job;
    private final ScheduledExecutorService timer;
    private ScheduledFuture<?> pending;
    private boolean running;
    private boolean again;
    private CompletableFuture<Void> pendingCompletion;
    private CompletableFuture<Void> runningCompletion;

    public Debouncer(String name, long delayMs, Supplier<CompletableFuture<?>> job) {
        this.delayMs = delayMs;
        this.job = job;
        this.timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "CraftPy-" + name);
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void request() {
        requestAndWait();
    }

    /** Completes after the run that includes this request, including requests made during another run. */
    public synchronized CompletableFuture<Void> requestAndWait() {
        if (pendingCompletion == null) pendingCompletion = new CompletableFuture<>();
        if (pending != null) {
            pending.cancel(false);
        }
        pending = timer.schedule(this::start, delayMs, TimeUnit.MILLISECONDS);
        return pendingCompletion;
    }

    private synchronized void start() {
        pending = null;
        if (running) {
            again = true;
            return;
        }
        running = true;
        runningCompletion = pendingCompletion;
        pendingCompletion = null;
        CompletableFuture<?> run;
        try {
            run = job.get();
        } catch (RuntimeException error) {
            CraftPyLog.LOGGER.error("CraftPy background job failed", error);
            run = CompletableFuture.failedFuture(error);
        }
        run.whenComplete((ignored, error) -> finished(error));
    }

    private synchronized void finished(Throwable error) {
        CompletableFuture<Void> completed = runningCompletion;
        runningCompletion = null;
        running = false;
        if (again) {
            again = false;
            request();
        }
        if (error == null) completed.complete(null); else completed.completeExceptionally(error);
    }
}
