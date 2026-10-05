package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VM-owned bridge for host operations that cannot participate directly in the
 * Ores nonblocking reactor.
 *
 * <p>JAVA_BLOCKING uses Java virtual threads as an implementation substrate.
 * Those virtual threads are never Ores scheduler authorities and never receive
 * actor/runtime implementation objects. NATIVE_BLOCKING uses a bounded
 * platform-thread pool because JNI/FFM/native calls may pin a carrier or ignore
 * Loom unmounting.</p>
 *
 * <p>Both paths return {@link OresFuture}. Completion only settles the Future;
 * any actor continuation waiting on it is subsequently enqueued by the actor
 * scheduler. No blocking worker executes guest continuation code.</p>
 */
final class BlockingIoExecutor implements AutoCloseable {
    static final int DEFAULT_MAX_JAVA_BLOCKING = 4096;
    static final int DEFAULT_NATIVE_THREADS = Math.max(
            2,
            Math.min(8, Runtime.getRuntime().availableProcessors()));
    static final int DEFAULT_NATIVE_QUEUE = 256;

    enum Kind {
        JAVA_BLOCKING,
        NATIVE_BLOCKING
    }

    private final ExecutorService javaBlocking;
    private final Semaphore javaAdmissions;
    private final int maxJavaBlocking;
    private final ThreadPoolExecutor nativeBlocking;
    private final AtomicBoolean closed = new AtomicBoolean();

    BlockingIoExecutor(String threadPrefix) {
        this(
                threadPrefix,
                DEFAULT_MAX_JAVA_BLOCKING,
                DEFAULT_NATIVE_THREADS,
                DEFAULT_NATIVE_QUEUE);
    }

    BlockingIoExecutor(
            String threadPrefix,
            int maxJavaBlocking,
            int nativeThreads,
            int nativeQueueCapacity) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        if (maxJavaBlocking <= 0) {
            throw new IllegalArgumentException("maxJavaBlocking must be positive");
        }
        if (nativeThreads <= 0) {
            throw new IllegalArgumentException("nativeThreads must be positive");
        }
        if (nativeQueueCapacity <= 0) {
            throw new IllegalArgumentException("nativeQueueCapacity must be positive");
        }

        this.maxJavaBlocking = maxJavaBlocking;
        this.javaAdmissions = new Semaphore(maxJavaBlocking);
        this.javaBlocking = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name(threadPrefix + "java-blocking-", 0)
                        .factory());

        this.nativeBlocking = new ThreadPoolExecutor(
                nativeThreads,
                nativeThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(nativeQueueCapacity),
                Thread.ofPlatform()
                        .daemon(true)
                        .name(threadPrefix + "native-blocking-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
        // Deliberately lazy: OresVM.PROCESS is a static runtime kernel object.
        // Prestarting platform threads here would capture live build-host
        // threads in GraalVM Native Image's image heap.
    }

    <T> OresFuture<T> submit(Kind kind, Callable<? extends T> operation) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operation, "operation");
        return switch (kind) {
            case JAVA_BLOCKING -> submitJava(operation);
            case NATIVE_BLOCKING -> submitNative(operation);
        };
    }

    <T> OresFuture<T> submitJava(Callable<? extends T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (closed.get()) return rejected("blocking I/O executor is closed");
        if (!javaAdmissions.tryAcquire()) {
            return rejected(
                    "Java blocking operation admission limit exceeded: "
                            + maxJavaBlocking);
        }

        // 0=queued, 1=running, 2=terminal/cancelled-before-run.
        AtomicInteger lifecycle = new AtomicInteger(0);
        AtomicBoolean admissionReleased = new AtomicBoolean();
        Runnable releaseAdmission = () -> {
            if (admissionReleased.compareAndSet(false, true)) {
                javaAdmissions.release();
            }
        };
        AtomicReference<Future<?>> taskRef = new AtomicReference<>();
        OresFuture<T> result = new OresFuture<>(() -> {
            Future<?> task = taskRef.get();
            if (task != null) task.cancel(true);
            // Only cancellation that wins before the worker starts may release
            // admission here. A running/uncooperative host call remains charged
            // until its worker actually exits.
            if (lifecycle.compareAndSet(0, 2)) {
                releaseAdmission.run();
            }
        });

        final Future<?> submitted;
        try {
            submitted = javaBlocking.submit(() -> {
                if (!lifecycle.compareAndSet(0, 1)) {
                    return;
                }
                try {
                    if (!result.isCancelled()) {
                        try {
                            result.completeFromRuntime(operation.call());
                        } catch (VirtualMachineError fatal) {
                            result.failFromRuntime(fatal);
                            throw fatal;
                        } catch (ThreadDeath fatal) {
                            result.failFromRuntime(fatal);
                            throw fatal;
                        } catch (LinkageError fatal) {
                            result.failFromRuntime(fatal);
                            throw fatal;
                        } catch (Throwable failure) {
                            result.failFromRuntime(failure);
                        }
                    }
                } finally {
                    lifecycle.set(2);
                    releaseAdmission.run();
                }
            });
        } catch (RejectedExecutionException rejected) {
            lifecycle.set(2);
            releaseAdmission.run();
            result.failFromRuntime(rejected);
            return result;
        }

        taskRef.set(submitted);
        if (result.isCancelled()) {
            submitted.cancel(true);
            if (lifecycle.compareAndSet(0, 2)) releaseAdmission.run();
        }
        return result;
    }

    <T> OresFuture<T> submitNative(Callable<? extends T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (closed.get()) return rejected("blocking I/O executor is closed");

        AtomicReference<Future<?>> taskRef = new AtomicReference<>();
        OresFuture<T> result = new OresFuture<>(() -> {
            Future<?> task = taskRef.get();
            if (task != null) {
                task.cancel(true);
                if (task instanceof Runnable queued) {
                    nativeBlocking.remove(queued);
                }
            }
        });

        final Future<?> submitted;
        try {
            submitted = nativeBlocking.submit(() -> {
                if (result.isCancelled()) return;
                try {
                    result.completeFromRuntime(operation.call());
                } catch (VirtualMachineError fatal) {
                    result.failFromRuntime(fatal);
                    throw fatal;
                } catch (ThreadDeath fatal) {
                    result.failFromRuntime(fatal);
                    throw fatal;
                } catch (LinkageError fatal) {
                    result.failFromRuntime(fatal);
                    throw fatal;
                } catch (Throwable failure) {
                    result.failFromRuntime(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            result.failFromRuntime(rejected);
            return result;
        }

        taskRef.set(submitted);
        if (result.isCancelled()) {
            submitted.cancel(true);
            if (submitted instanceof Runnable queued) nativeBlocking.remove(queued);
        }
        return result;
    }

    int activeNativeCalls() {
        return nativeBlocking.getActiveCount();
    }

    int queuedNativeCalls() {
        return nativeBlocking.getQueue().size();
    }

    int availableJavaAdmissions() {
        return javaAdmissions.availablePermits();
    }

    boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        javaBlocking.shutdownNow();
        nativeBlocking.shutdownNow();
    }

    private static <T> OresFuture<T> rejected(String message) {
        return OresFuture.failed(new RejectedExecutionException(message));
    }
}
