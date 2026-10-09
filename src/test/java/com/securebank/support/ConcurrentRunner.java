package com.securebank.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks on separate threads, releasing them together, and captures each task's result or
 * exception so assertions can inspect every outcome.
 */
public final class ConcurrentRunner<T> implements AutoCloseable {

    public static final long TIMEOUT_SECONDS = 30;

    /** A task's result, or the exception it threw. */
    public record Outcome<T>(T result, Throwable error) {

        public boolean succeeded() {
            return error == null;
        }
    }

    private final ExecutorService executor;
    private final CountDownLatch ready;
    private final CountDownLatch start = new CountDownLatch(1);
    private final List<Future<T>> futures = new ArrayList<>();

    public ConcurrentRunner(List<Callable<T>> tasks) {
        executor = Executors.newFixedThreadPool(tasks.size());
        ready = new CountDownLatch(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return task.call();
            }));
        }
    }

    /** Waits until every worker thread is running, then releases them at the same moment. */
    public ConcurrentRunner<T> start() throws InterruptedException {
        if (!ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Worker threads did not start in time");
        }
        start.countDown();
        return this;
    }

    /** Waits (bounded) for every task and returns the outcomes in submission order. */
    public List<Outcome<T>> awaitOutcomes() throws InterruptedException {
        List<Outcome<T>> outcomes = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                outcomes.add(new Outcome<>(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), null));
            } catch (java.util.concurrent.ExecutionException ex) {
                outcomes.add(new Outcome<>(null, ex.getCause()));
            } catch (java.util.concurrent.TimeoutException ex) {
                throw new AssertionError("Concurrent task did not finish within " + TIMEOUT_SECONDS
                        + "s (possible deadlock or lost lock)", ex);
            }
        }
        return outcomes;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
