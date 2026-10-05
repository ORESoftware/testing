package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class BlockingIoExecutorTest {

    @Test
    void javaBlockingUsesVirtualThreadsButReturnsOresFuture() throws Exception {
        try (BlockingIoExecutor executor =
                     new BlockingIoExecutor("test-", 8, 1, 4)) {
            OresFuture<String> future = executor.submitJava(() -> {
                assertTrue(Thread.currentThread().isVirtual());
                assertTrue(Thread.currentThread().getName().startsWith(
                        "test-java-blocking-"));
                return "ok";
            });

            assertEquals("ok", future.get(2, TimeUnit.SECONDS));
            assertFalse(
                    java.util.concurrent.CompletionStage.class.isAssignableFrom(
                            future.getClass()),
                    "OresFuture must not inherit host CompletionStage callback semantics");
        }
    }

    @Test
    void nativeBlockingUsesBoundedPlatformThreads() throws Exception {
        try (BlockingIoExecutor executor =
                     new BlockingIoExecutor("test-", 8, 1, 4)) {
            OresFuture<String> future = executor.submitNative(() -> {
                assertFalse(Thread.currentThread().isVirtual());
                assertTrue(Thread.currentThread().getName().startsWith(
                        "test-native-blocking-"));
                return "native-ok";
            });

            assertEquals("native-ok", future.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void nativeSaturationFailsFutureInsteadOfRunningOnCaller() throws Exception {
        try (BlockingIoExecutor executor =
                     new BlockingIoExecutor("test-", 8, 1, 1)) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicBoolean rejectedBodyRan = new AtomicBoolean();

            OresFuture<Integer> first = executor.submitNative(() -> {
                firstStarted.countDown();
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                return 1;
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            OresFuture<Integer> second = executor.submitNative(() -> 2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.queuedNativeCalls() != 1
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, executor.queuedNativeCalls());

            OresFuture<Integer> rejected = executor.submitNative(() -> {
                rejectedBodyRan.set(true);
                return 3;
            });

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> rejected.get(1, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, failure.getCause());
            assertFalse(rejectedBodyRan.get(),
                    "saturated native work must never caller-run on an Ores carrier");

            releaseFirst.countDown();
            assertEquals(1, first.get(2, TimeUnit.SECONDS));
            assertEquals(2, second.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationDoesNotFreeAdmissionUntilRunningHostCallActuallyExits()
            throws Exception {
        try (BlockingIoExecutor executor =
                     new BlockingIoExecutor("test-", 1, 1, 2)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            OresFuture<Integer> first = executor.submitJava(() -> {
                started.countDown();
                while (release.getCount() != 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // Model an uncooperative host call. Cancellation must
                        // not pretend this worker has already stopped.
                    }
                }
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(first.cancel(true));

            OresFuture<Integer> stillRejected = executor.submitJava(() -> 2);
            ExecutionException rejected = assertThrows(
                    ExecutionException.class,
                    () -> stillRejected.get(1, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, rejected.getCause());

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (executor.availableJavaAdmissions() == 0
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, executor.availableJavaAdmissions());

            assertEquals(
                    3,
                    executor.submitJava(() -> 3).get(2, TimeUnit.SECONDS));
        }
    }
}
