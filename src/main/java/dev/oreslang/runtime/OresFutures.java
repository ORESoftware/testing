package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Structured combinators for language-level Future<T> values.
 *
 * <p>The public language primitive is {@link OresFuture}. CompletionStage is
 * accepted only as a host-interop input and is immediately normalized into an
 * OresFuture so dependent Oreslang work never inherits a host callback
 * execution policy.</p>
 */
public final class OresFutures {
    private OresFutures() { }

    public record Settled<T>(T value, Throwable error) {
        public boolean ok() {
            return error == null;
        }
    }

    public static <T> OresFuture<List<T>> all(List<?> awaitables) {
        List<OresFuture<T>> children = normalize(awaitables, "Futures.all");
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration> waiters =
                new AtomicReferenceArray<>(children.size());
        Runnable detachWaiters = () -> detachAll(waiters);

        OresFuture<List<T>> result = new OresFuture<>(() -> {
            detachWaiters.run();
            children.forEach(child -> child.cancel(true));
        });
        if (children.isEmpty()) {
            result.completeFromRuntime(List.of());
            return result;
        }

        AtomicReferenceArray<Object> values = new AtomicReferenceArray<>(children.size());
        AtomicInteger remaining = new AtomicInteger(children.size());

        for (int index = 0; index < children.size(); index++) {
            int slot = index;
            OresFuture.RuntimeWaiterRegistration registration =
                    children.get(index).whenCompleteRuntimeCancellable((value, failure) -> {
                        if (result.isDone()) {
                            detachWaiters.run();
                            return;
                        }
                        if (failure != null) {
                            if (children.get(slot).isCancelled()) {
                                result.cancel(false);
                            } else {
                                result.failFromRuntime(OresFuture.unwrap(failure));
                            }
                            detachWaiters.run();
                            return;
                        }
                        values.set(slot, value);
                        if (remaining.decrementAndGet() == 0) {
                            ArrayList<T> ordered = new ArrayList<>(children.size());
                            for (int i = 0; i < children.size(); i++) {
                                @SuppressWarnings("unchecked")
                                T item = (T) values.get(i);
                                ordered.add(item);
                            }
                            result.completeFromRuntime(List.copyOf(ordered));
                            detachWaiters.run();
                        }
                    });
            waiters.set(slot, registration);
            if (result.isDone()) {
                OresFuture.RuntimeWaiterRegistration raced =
                        waiters.getAndSet(slot, null);
                if (raced != null) raced.detach();
            }
        }
        return result;
    }

    public static <T> OresFuture<T> race(List<?> awaitables) {
        List<OresFuture<T>> children = normalize(awaitables, "Futures.race");
        if (children.isEmpty()) {
            return OresFuture.failed(
                    new IllegalArgumentException("Futures.race requires at least one future"));
        }

        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration> waiters =
                new AtomicReferenceArray<>(children.size());
        Runnable detachWaiters = () -> detachAll(waiters);

        OresFuture<T> result = new OresFuture<>(() -> {
            detachWaiters.run();
            children.forEach(child -> child.cancel(true));
        });

        for (int index = 0; index < children.size(); index++) {
            int slot = index;
            OresFuture<T> child = children.get(index);
            OresFuture.RuntimeWaiterRegistration registration =
                    child.whenCompleteRuntimeCancellable((value, failure) -> {
                        if (result.isDone()) {
                            detachWaiters.run();
                            return;
                        }

                        if (failure == null) {
                            result.completeFromRuntime(value);
                        } else if (child.isCancelled()) {
                            result.cancel(false);
                        } else {
                            result.failFromRuntime(OresFuture.unwrap(failure));
                        }
                        detachWaiters.run();
                    });
            waiters.set(slot, registration);
            if (result.isDone()) {
                OresFuture.RuntimeWaiterRegistration raced =
                        waiters.getAndSet(slot, null);
                if (raced != null) raced.detach();
            }
        }
        return result;
    }

    public static <T> OresFuture<List<Settled<T>>> allSettled(List<?> awaitables) {
        List<OresFuture<T>> children = normalize(awaitables, "Futures.all_settled");
        OresFuture<List<Settled<T>>> result = new OresFuture<>(
                () -> children.forEach(child -> child.cancel(true)));
        if (children.isEmpty()) {
            result.completeFromRuntime(List.of());
            return result;
        }

        AtomicReferenceArray<Settled<T>> settled =
                new AtomicReferenceArray<>(children.size());
        AtomicInteger remaining = new AtomicInteger(children.size());

        for (int index = 0; index < children.size(); index++) {
            int slot = index;
            children.get(index).whenCompleteRuntime((value, failure) -> {
                settled.set(
                        slot,
                        new Settled<>(
                                failure == null ? value : null,
                                failure == null ? null : OresFuture.unwrap(failure)));
                if (remaining.decrementAndGet() == 0) {
                    ArrayList<Settled<T>> ordered =
                            new ArrayList<>(children.size());
                    for (int i = 0; i < children.size(); i++) {
                        ordered.add(settled.get(i));
                    }
                    result.completeFromRuntime(List.copyOf(ordered));
                }
            });
        }
        return result;
    }

    private static void detachAll(
            AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration> waiters) {
        for (int i = 0; i < waiters.length(); i++) {
            OresFuture.RuntimeWaiterRegistration registration =
                    waiters.getAndSet(i, null);
            if (registration != null) registration.detach();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<OresFuture<T>> normalize(
            List<?> awaitables,
            String operation) {
        Objects.requireNonNull(awaitables, "awaitables");
        ArrayList<OresFuture<T>> children = new ArrayList<>(awaitables.size());
        for (Object awaitable : awaitables) {
            Objects.requireNonNull(awaitable, "future");
            if (awaitable instanceof OresFuture<?> ores) {
                children.add((OresFuture<T>) ores);
            } else if (awaitable instanceof CompletionStage<?> stage) {
                children.add(OresFuture.from((CompletionStage<? extends T>) stage));
            } else {
                throw new IllegalArgumentException(
                        operation + " expects every list element to be a Future");
            }
        }
        return List.copyOf(children);
    }
}
