package net.xmilon.craftpy.python;

import net.xmilon.craftpy.CraftPyLog;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.xmilon.craftpy.util.Json;

/**
 * JSON-RPC client for a single {@code worker.py} subprocess (newline-delimited
 * JSON over stdin/stdout). The worker executes one request at a time; this
 * client serializes writes and matches responses by id. If the subprocess dies,
 * pending calls fail and the next call attempts a restart.
 */
public final class RpcClient {
    /** Every worker process that runs; the game closing (or crashing) kills them, Windows would keep them otherwise. */
    private static final java.util.Set<Process> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process process : LIVE) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }, "CraftPy worker cleanup"));
    }

    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    /** The only variables of the game's environment the worker gets (Windows needs SYSTEMROOT to start CPython). */
    private static final java.util.List<String> KEPT_ENVIRONMENT = java.util.List.of("SYSTEMROOT", "SystemRoot", "WINDIR",
            "TEMP", "TMP", "TMPDIR", "LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH");

    private final Path python;
    private final Path workerScript;
    private final Path logFile;
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();

    private Process process;
    private BufferedWriter out;
    private volatile boolean running;
    private Thread readerThread;
    private Thread stderrThread;
    private java.util.function.BiFunction<String, JsonObject, JsonObject> hostHandler;
    private Map<String, String> extraEnvironment = Map.of();
    private java.util.concurrent.ExecutorService hostExecutor;

    public RpcClient(Path python, Path workerScript, Path logFile) {
        this.python = python;
        this.workerScript = workerScript;
        this.logFile = logFile;
    }

    /** Environment variables for the worker process (the client's worker gets {@code CRAFTPY_MODE=client}). */
    public void setEnvironment(Map<String, String> environment) {
        this.extraEnvironment = Map.copyOf(environment);
    }
    /** Installs the handler for reverse calls (Python -> Java). */
    public void setHostHandler(java.util.function.BiFunction<String, JsonObject, JsonObject> handler) {
        this.hostHandler = handler;
    }

    private java.util.concurrent.ExecutorService hostExecutor() {
        if (hostExecutor == null) {
            hostExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "craftpy-host-call");
                thread.setDaemon(true);
                return thread;
            });
        }
        return hostExecutor;
    }

    public synchronized boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    /** Starts (or restarts) the subprocess. */
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        CraftPyLog.LOGGER.info("Starting CraftPy Python worker: {} {}", python, workerScript);
        try {
            // -I: ignore PYTHON* variables, user site and the script folder on sys.path; -B -u replace the old
            // PYTHONDONTWRITEBYTECODE / PYTHONUNBUFFERED; -X utf8 so the pipe encoding does not depend on the locale
            ProcessBuilder builder = new ProcessBuilder(python.toString(), "-I", "-B", "-u", "-X", "utf8",
                    workerScript.toString());
            builder.redirectErrorStream(false);
            // start in Python's own folder: readable anyway, and never one the game deletes or renames (on Windows a
            // process's folder cannot be deleted, so a project or pyforge folder here would be locked)
            Path start = python.toAbsolutePath().getParent();
            builder.directory((start != null && java.nio.file.Files.isDirectory(start) ? start : workerScript.getParent()).toFile());
            Map<String, String> env = builder.environment();
            Map<String, String> inherited = Map.copyOf(env);
            // project code can read its environment: pass nothing of the game's (tokens, keys) except what CPython needs
            env.clear();
            for (String name : KEPT_ENVIRONMENT) {
                String value = inherited.get(name);
                if (value != null) {
                    env.put(name, value);
                }
            }
            // the worker's sandbox (worker.py _harden): the memory cap and the only folders project code may read
            env.put("CRAFTPY_MEMORY_MB", String.valueOf(net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.PYTHON_MEMORY_MB)));
            // CRAFTPY_READ_ROOTS (the owner's own folders) comes from the caller; without it project code reads nothing
            env.put("CRAFTPY_READ_ROOTS", "");
            env.putAll(extraEnvironment);
            process = builder.start();
            LIVE.add(process);
            out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            pending.clear();

            Process started = process;
            readerThread = new Thread(() -> readLoop(started), "craftpy-worker-read");
            readerThread.setDaemon(true);
            readerThread.start();

            stderrThread = new Thread(() -> stderrLoop(started), "craftpy-worker-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            running = true;
        } catch (IOException e) {
            CraftPyLog.LOGGER.error("Failed to start Python worker", e);
            running = false;
        }
    }

    /**
     * Sends one request and blocks until the worker replies or the timeout
     * elapses. Returns the "result" object on success.
     */
    public int pendingCount() { return pending.size(); }

    public JsonObject call(String method, Object params, long timeoutMs) throws IOException {
        while (true) {
            if (!isRunning()) {
                start();
            }
            long id = NEXT_ID.getAndIncrement();
            JsonObject request = new JsonObject();
            request.addProperty("id", id);
            request.addProperty("method", method);
            request.add("params", params instanceof JsonObject obj ? obj
                    : JsonParser.parseString(Json.COMPACT.toJson(params)));
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            pending.put(id, future);
            writeLock.lock();
            try {
                out.write(Json.COMPACT.toJson(request));
                out.newLine();
                out.flush();
            } finally {
                writeLock.unlock();
            }
            try {
                return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                pending.remove(id);
                CraftPyLog.LOGGER.warn("Python worker timeout for {} ({} ms)", method, timeoutMs);
                throw new IOException("Python worker timed out for " + method, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending.remove(id);
                throw new IOException("Interrupted while waiting for " + method, e);
            } catch (java.util.concurrent.ExecutionException e) {
                pending.remove(id);
                Throwable cause = e.getCause();
                if (cause instanceof TransportFailure) {
                    // process died; restart and retry once
                    restart();
                    continue;
                }
                throw new IOException(method + " failed: "
                        + (cause == null ? e.getMessage() : cause.getMessage()), e);
            }
        }
    }

    /** Alias used by call sites that only need the raw JsonObject. */
    public JsonObject request(String method, Map<String, Object> params, long timeoutMs) throws IOException {
        return call(method, params, timeoutMs);
    }

    /** Convenience: fire a one-way request without waiting for a reply. */
    public void notifyMethod(String method) {
        writeLock.lock();
        try {
            JsonObject request = new JsonObject();
            request.addProperty("id", NEXT_ID.getAndIncrement());
            request.addProperty("method", method);
            request.add("params", new JsonObject());
            out.write(Json.COMPACT.toJson(request));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("Failed to notify worker: {}", method, e);
        } finally {
            writeLock.unlock();
        }
    }

    private void readLoop(Process started) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject response = JsonParser.parseString(line).getAsJsonObject();
                    if (response.has("host_call")) {
                        dispatchHostCall(response);
                        continue;
                    }
                    JsonPrimitive idPrimitive = response.getAsJsonPrimitive("id");
                    if (idPrimitive == null || !idPrimitive.isNumber()) {
                        CraftPyLog.LOGGER.info("[worker log] {}", line);
                        continue;
                    }
                    long id = idPrimitive.getAsLong();
                    CompletableFuture<JsonObject> future = pending.remove(id);
                    if (future == null) {
                        continue;
                    }
                    if (response.has("error")) {
                        JsonObject error = response.getAsJsonObject("error");
                        future.completeExceptionally(new WorkerException(
                                error.get("message") == null ? "worker error" : error.get("message").getAsString()));
                    } else {
                        JsonObject result = response.get("result") == null ? new JsonObject()
                                : response.getAsJsonObject("result");
                        future.complete(result);
                    }
                } catch (RuntimeException e) {
                    CraftPyLog.LOGGER.warn("Malformed worker response: {}", line, e);
                }
            }
        } catch (IOException e) {
            if (running) {
                CraftPyLog.LOGGER.warn("Worker stdout closed", e);
            }
        } finally {
            synchronized (this) { if (process == started) transportFailure(); }
        }
    }

    private void stderrLoop(Process started) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder buffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                CraftPyLog.LOGGER.info("[python] {}", line);
                buffer.append(line).append(System.lineSeparator());
                if (buffer.length() > 4096) {
                    buffer.setLength(0);
                }
            }
            if (!buffer.isEmpty()) {
                java.nio.file.Files.writeString(logFile, buffer.toString(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // ignore
        }
    }

    /** Runs a reverse call from the worker off the reader thread and replies. */
    private void dispatchHostCall(JsonObject request) {
        long callId = request.get("host_call").getAsLong();
        String method = request.get("method") == null ? "" : request.get("method").getAsString();
        JsonObject params = request.get("params") != null && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params") : new JsonObject();
        hostExecutor().execute(() -> {
            JsonObject message = new JsonObject();
            message.addProperty("host_response", callId);
            try {
                JsonObject result = hostHandler == null ? new JsonObject() : hostHandler.apply(method, params);
                message.add("result", result == null ? new JsonObject() : result);
            } catch (Exception e) {
                JsonObject error = new JsonObject();
                error.addProperty("message", e.getMessage() == null ? e.toString() : e.getMessage());
                message.add("error", error);
            }
            writeLine(message);
        });
    }

    private void writeLine(JsonObject message) {
        writeLock.lock();
        try {
            if (out != null) {
                out.write(Json.COMPACT.toJson(message));
                out.newLine();
                out.flush();
            }
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("Failed to reply to host call", e);
        } finally {
            writeLock.unlock();
        }
    }

    private void transportFailure() {
        if (running) {
            running = false;
            for (CompletableFuture<JsonObject> future : pending.values()) {
                future.completeExceptionally(new TransportFailure());
            }
            pending.clear();
        }
    }

    private void restart() {
        synchronized (this) {
            stopInternal();
            start();
        }
    }

    public synchronized void stop() {
        stopInternal();
    }

    private void stopInternal() {
        running = false;
        if (hostExecutor != null) {
            hostExecutor.shutdownNow();
            hostExecutor = null;
        }
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            LIVE.remove(process);
            process = null;
        }
    }

    static final class TransportFailure extends RuntimeException {
        TransportFailure() {
            super("Python worker process died");
        }
    }

    static final class WorkerException extends RuntimeException {
        WorkerException(String message) {
            super(message);
        }
    }
}
