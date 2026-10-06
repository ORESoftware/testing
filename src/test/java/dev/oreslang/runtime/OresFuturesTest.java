package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

final class OresFuturesTest {

    @Test
    void allPreservesInputOrderIndependentOfCompletionOrder() {
        CompletableFuture<Integer> first = new CompletableFuture<>();
        CompletableFuture<Integer> second = new CompletableFuture<>();
        CompletableFuture<Integer> third = new CompletableFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(first, second, third));
        third.complete(3);
        first.complete(1);
        assertFalse(all.isDone());

        second.complete(2);
        assertEquals(List.of(1, 2, 3), all.join());
    }

    @Test
    void completionStageCancellationNormalizesToCancelledOresFuture() {
        CompletableFuture<Integer> host = new CompletableFuture<>();
        OresFuture<Integer> ores = OresFuture.from(host);

        assertTrue(host.cancel(true));

        assertTrue(ores.isCancelled());
        assertThrows(CancellationException.class, ores::join);
    }

    @Test
    void allPreservesChildCancellationIdentity() {
        CompletableFuture<Integer> first = new CompletableFuture<>();
        CompletableFuture<Integer> second = new CompletableFuture<>();
        OresFuture<List<Integer>> all = OresFutures.all(List.of(first, second));

        first.complete(1);
        assertTrue(second.cancel(true));

        assertTrue(all.isCancelled());
        assertThrows(CancellationException.class, all::join);
    }

    @Test
    void racePreservesFirstCancellationIdentity() {
        CompletableFuture<Integer> cancelled = new CompletableFuture<>();
        CompletableFuture<Integer> later = new CompletableFuture<>();
        OresFuture<Integer> race = OresFutures.race(List.of(cancelled, later));

        assertTrue(cancelled.cancel(true));

        assertTrue(race.isCancelled());
        assertThrows(CancellationException.class, race::join);
        later.complete(9);
        assertTrue(race.isCancelled());
    }

    @Test
    void allPropagatesFailure() {
        CompletableFuture<Integer> ok = new CompletableFuture<>();
        CompletableFuture<Integer> bad = new CompletableFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(ok, bad));
        ok.complete(1);
        bad.completeExceptionally(new IllegalStateException("boom"));

        CompletionException failure = assertThrows(CompletionException.class, all::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test
    void allFailureDetachesOtherChildWaitersWithoutCancellingSharedChildren() {
        OresFuture<Integer> pending = new OresFuture<>();
        OresFuture<Integer> failed = new OresFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(pending, failed));
        assertEquals(1, pending.pendingRuntimeWaiterCount());
        assertEquals(1, failed.pendingRuntimeWaiterCount());

        failed.failFromRuntime(new IllegalStateException("boom"));

        CompletionException failure =
                assertThrows(CompletionException.class, all::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(0, pending.pendingRuntimeWaiterCount(),
                "terminal all() must detach from losing pending children");
        assertEquals(0, failed.pendingRuntimeWaiterCount());
        assertFalse(pending.isCancelled(),
                "child failure must not cancel unrelated shared children");
    }

    @Test
    void allChildCancellationCancelsSiblingsAndDetachesAllWaiters() {
        OresFuture<Integer> pending = new OresFuture<>();
        OresFuture<Integer> cancelled = new OresFuture<>();

        OresFuture<List<Integer>> all =
                OresFutures.all(List.of(pending, cancelled));
        assertTrue(cancelled.cancel(false));

        assertTrue(all.isCancelled());
        assertThrows(CancellationException.class, all::join);
        assertEquals(0, pending.pendingRuntimeWaiterCount());
        assertEquals(0, cancelled.pendingRuntimeWaiterCount());
        assertTrue(pending.isCancelled(),
                "all() owns its child set: aggregate cancellation cancels remaining siblings");
    }

    @Test
    void raceDetachesLosingWaitersWithoutCancellingSharedChildren() {
        OresFuture<Integer> slow = new OresFuture<>();
        OresFuture<Integer> fast = new OresFuture<>();

        OresFuture<Integer> race = OresFutures.race(List.of(slow, fast));
        assertEquals(1, slow.pendingRuntimeWaiterCount());
        assertEquals(1, fast.pendingRuntimeWaiterCount());

        fast.completeFromRuntime(7);

        assertEquals(7, race.join());
        assertEquals(0, slow.pendingRuntimeWaiterCount(),
                "race winner must detach callbacks from losing children");
        assertEquals(0, fast.pendingRuntimeWaiterCount());
        assertFalse(slow.isCancelled(),
                "race completion must not cancel a shared losing child");
    }

    @Test
    void cancellingAggregateCancelsChildren() {
        CompletableFuture<Integer> first = new CompletableFuture<>();
        CompletableFuture<Integer> second = new CompletableFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(first, second));
        assertTrue(all.cancel(true));

        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
    }

    @Test
    void guestCannotForgeFutureCompletion() {
        OresFuture<Integer> future = new OresFuture<>();

        assertThrows(UnsupportedOperationException.class, () -> future.complete(99));
        assertThrows(
                UnsupportedOperationException.class,
                () -> future.completeExceptionally(new IllegalStateException("forged")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> future.completeOnTimeout(99, 1, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertThrows(
                UnsupportedOperationException.class,
                () -> future.orTimeout(1, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertFalse(future.isDone());
    }

    @Test
    void oresFutureDoesNotExposeCompletionStageCallbackSurface() {
        assertFalse(
                java.util.concurrent.CompletionStage.class.isAssignableFrom(
                        OresFuture.class));
        assertFalse(
                java.util.Arrays.stream(OresFuture.class.getMethods())
                        .anyMatch(method -> method.getName().equals("thenApply")
                                || method.getName().equals("thenAccept")
                                || method.getName().equals("thenRun")),
                "guest-visible OresFuture must not inherit producer-thread callback APIs");
    }

    @Test
    void runtimeWaiterIsDeliveredExactlyOnceWhenRegistrationRacesCompletion()
            throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            OresFuture<Integer> future = new OresFuture<>();
            java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger callbacks =
                    new java.util.concurrent.atomic.AtomicInteger();

            Thread registrar = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                future.whenCompleteRuntime((value, failure) -> {
                    assertNull(failure);
                    assertEquals(7, value);
                    callbacks.incrementAndGet();
                });
            });
            Thread completer = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                future.completeFromRuntime(7);
            });

            start.countDown();
            registrar.join();
            completer.join();

            assertEquals(7, future.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, callbacks.get(),
                    "completion/registration race must not duplicate a waiter");
        }
    }

    @Test
    void terminalFutureDoesNotRetainLateRuntimeWaiters() {
        OresFuture<Integer> future = OresFuture.completed(7);
        java.util.concurrent.atomic.AtomicInteger callbacks =
                new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < 1_000; i++) {
            future.whenCompleteRuntime((value, failure) -> {
                assertNull(failure);
                assertEquals(7, value);
                callbacks.incrementAndGet();
            });
        }

        assertEquals(1_000, callbacks.get());
        assertEquals(
                0,
                future.pendingRuntimeWaiterCount(),
                "already-terminal Futures must not retain delivered runtime waiters");
    }

    @Test
    void raceCompletesWithFirstCompletion() {
        CompletableFuture<Integer> slow = new CompletableFuture<>();
        CompletableFuture<Integer> fast = new CompletableFuture<>();

        OresFuture<Integer> race = OresFutures.race(List.of(slow, fast));
        fast.complete(7);

        assertEquals(7, race.join());
    }
}
