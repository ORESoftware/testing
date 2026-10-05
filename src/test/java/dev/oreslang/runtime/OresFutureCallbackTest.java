package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresFutureCallbackTest {

    @Test
    void fromCallbackMaySettleSynchronouslyButRemainsSingleShot() throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> completion = new AtomicReference<>();

        OresFuture<Integer> future = OresFuture.fromCallback(callback -> {
            completion.set(callback);
            callback.resolve(41);
        });

        assertEquals(41, future.get(5, TimeUnit.SECONDS));
        assertTrue(completion.get().isDone());
        assertThrows(
                OresFuture.AlreadySettledException.class,
                () -> completion.get().resolve(42));
        assertEquals(41, future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void lateRuntimeWaiterOnSettledFutureIsDeliveredAndReleased() {
        OresFuture<Integer> future = OresFuture.completed(42);
        AtomicReference<Integer> observed = new AtomicReference<>();

        future.whenCompleteRuntime((value, failure) -> {
            assertNull(failure);
            observed.set(value);
        });

        assertEquals(42, observed.get());
        assertEquals(
                0,
                future.pendingRuntimeWaiterCount(),
                "late terminal registrations must not remain retained in the waiter queue");
    }

    @Test
    void consumerCancellationDropsFirstLateForeignCallbackButStillRejectsDuplicates() {
        AtomicReference<OresFuture.Callback<Integer>> completion = new AtomicReference<>();

        OresFuture<Integer> future = OresFuture.fromCallback(completion::set);

        assertTrue(future.cancel(false));
        assertTrue(future.isCancelled());
        assertTrue(completion.get().isDone());

        assertDoesNotThrow(() -> completion.get().resolve(42));
        assertTrue(future.isCancelled());

        assertThrows(
                OresFuture.AlreadySettledException.class,
                () -> completion.get().resolve(43));
    }

    @Test
    void registrarThrowRejectsFutureWhenCallbackHasNotSettled() {
        OresFuture<Integer> future = OresFuture.fromCallback(callback -> {
            throw new IllegalStateException("registration failed");
        });

        var failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("registration failed", failure.getCause().getMessage());
    }

    @Test
    void attachedCallbackPreservesSharedSourceCancellation() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            AtomicBoolean registrarCalled = new AtomicBoolean();

            OresFuture<Integer> chained = source.attachCallback(
                    scheduler,
                    (value, callback) -> {
                        registrarCalled.set(true);
                        callback.resolve(value + 1);
                    });

            assertTrue(source.cancel(true));

            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> chained.get(5, TimeUnit.SECONDS));
            assertTrue(chained.isCancelled());
            assertFalse(registrarCalled.get(),
                    "callback registrar must not run after its source was cancelled");
        }
    }

    @Test
    void attachedCallbackPreservesCallbackCancellation() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> chained = OresFuture.completed(40)
                    .attachCallback(
                            scheduler,
                            (value, callback) -> callback.cancel());

            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> chained.get(5, TimeUnit.SECONDS));
            assertTrue(chained.isCancelled());
        }
    }

    @Test
    void cancellingAttachedChainDoesNotCancelSharedSourceAndDropsLateCallback()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = OresFuture.completed(40);
            AtomicReference<OresFuture.Callback<Integer>> callbackRef =
                    new AtomicReference<>();

            OresFuture<Integer> chained = source.attachCallback(
                    scheduler,
                    (value, callback) -> callbackRef.set(callback));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (callbackRef.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertNotNull(callbackRef.get());

            assertTrue(chained.cancel(true));
            assertTrue(chained.isCancelled());
            assertFalse(source.isCancelled(),
                    "cancelling a callback chain must not cancel its shared source");

            OresFuture.Callback<Integer> callback = callbackRef.get();
            assertDoesNotThrow(() -> callback.resolve(41),
                    "first late foreign callback after chain cancellation is dropped");
            assertThrows(
                    OresFuture.AlreadySettledException.class,
                    () -> callback.resolve(42),
                    "a true duplicate callback remains a programming error");
        }
    }

    @Test
    void synchronousAttachedCallbackCannotCompleteChainOnRegistrarStack() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicBoolean insideRegistrar = new AtomicBoolean();
            AtomicBoolean completedInsideRegistrar = new AtomicBoolean();

            OresFuture<Integer> source = OresFuture.completed(40);
            OresFuture<Integer> chained = source.attachCallback(
                    scheduler,
                    (value, callback) -> {
                        insideRegistrar.set(true);
                        try {
                            callback.resolve(value + 2);
                        } finally {
                            insideRegistrar.set(false);
                        }
                    });

            chained.whenCompleteRuntime(
                    (value, failure) ->
                            completedInsideRegistrar.set(insideRegistrar.get()));

            assertEquals(42, chained.get(5, TimeUnit.SECONDS));
            assertFalse(
                    completedInsideRegistrar.get(),
                    "synchronous callback settlement must not recursively resume "
                            + "the dependent Future on the registrar stack");
        }
    }
}
