package net.xmilon.craftpy.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebouncerTest {

    @Test
    void requestsCloseTogetherRunOnce() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        Debouncer debouncer = new Debouncer("test", 50, () -> {
            runs.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        for (int i = 0; i < 10; i++) {
            debouncer.request();
        }
        Thread.sleep(300);
        assertEquals(1, runs.get());
    }

    @Test
    void aRequestDuringARunRunsOnceMoreAfterIt() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger overlapping = new AtomicInteger();
        CompletableFuture<Void> first = new CompletableFuture<>();
        Debouncer debouncer = new Debouncer("test", 20, () -> {
            if (runs.incrementAndGet() == 1) {
                return first; // still running until completed below
            }
            return CompletableFuture.completedFuture(null);
        });
        debouncer.request();
        Thread.sleep(150);
        debouncer.request();
        debouncer.request();
        Thread.sleep(150);
        overlapping.set(runs.get()); // the second run waits for the first
        first.complete(null);
        Thread.sleep(300);
        assertEquals(1, overlapping.get());
        assertEquals(2, runs.get());
    }
}
