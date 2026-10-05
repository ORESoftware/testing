package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Pull-oriented reactive subscription used by rx-ores.
 *
 * <p>Exactly one pull may be outstanding at a time. That gives the initial
 * native implementation real backpressure without an additional demand
 * protocol: one {@link #next()} call admits at most one element.</p>
 *
 * <p>The returned {@link OresFuture} is the suspension boundary. Producer
 * threads only settle Futures; they never invoke guest callbacks directly.</p>
 */
public abstract class OresSubscription<T> {
    private final Object gate = new Object();

    private boolean cancelled;
    private boolean terminal;
    private boolean pulling;
    private OresFuture<OresNotification<T>> active;

    /**
     * Request exactly one next stream notification.
     *
     * <p>Calling next concurrently is a programming error. After cancellation
     * or terminal completion, next returns COMPLETE.</p>
     */
    public final OresFuture<OresNotification<T>> next() {
        synchronized (gate) {
            if (cancelled || terminal) {
                return OresFuture.completed(OresNotification.complete());
            }
            if (pulling) {
                return OresFuture.failed(new IllegalStateException(
                        "rx-ores subscription already has an outstanding next()"));
            }
            pulling = true;
        }

        final OresFuture<OresNotification<T>> source;
        try {
            source = Objects.requireNonNull(
                    nextFromRuntime(),
                    "nextFromRuntime returned null Future");
        } catch (Throwable failure) {
            synchronized (gate) {
                pulling = false;
                terminal = true;
            }
            return OresFuture.failed(failure);
        }

        OresFuture<OresNotification<T>> exposed =
                new OresFuture<>(() -> source.cancel(true));

        synchronized (gate) {
            if (cancelled || terminal) {
                pulling = false;
                source.cancel(true);
                return OresFuture.completed(OresNotification.complete());
            }
            active = exposed;
        }

        source.whenCompleteRuntime((notification, failure) -> {
            boolean shouldCancelRuntime = false;
            synchronized (gate) {
                if (active == exposed) {
                    active = null;
                }
                pulling = false;

                if (failure != null) {
                    terminal = true;
                    shouldCancelRuntime = true;
                } else if (notification == null) {
                    terminal = true;
                    shouldCancelRuntime = true;
                    failure = new IllegalStateException(
                            "rx-ores source completed a pull with null notification");
                } else if (notification.isComplete()) {
                    terminal = true;
                    shouldCancelRuntime = true;
                }
            }

            if (shouldCancelRuntime) {
                try {
                    cancelFromRuntime();
                } catch (RuntimeException | Error ignored) {
                    // Stream terminal state is already authoritative.
                }
            }

            if (exposed.isDone()) {
                return;
            }
            if (failure == null) {
                exposed.completeFromRuntime(notification);
            } else {
                exposed.failFromRuntime(OresFuture.unwrap(failure));
            }
        });

        return exposed;
    }

    /**
     * Cancel this subscription and any currently outstanding pull.
     */
    public final boolean cancel() {
        OresFuture<OresNotification<T>> toCancel;
        synchronized (gate) {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            terminal = true;
            pulling = false;
            toCancel = active;
            active = null;
        }

        if (toCancel != null) {
            toCancel.cancel(true);
        }
        cancelFromRuntime();
        return true;
    }

    public final boolean isCancelled() {
        synchronized (gate) {
            return cancelled;
        }
    }

    public final boolean isTerminated() {
        synchronized (gate) {
            return terminal;
        }
    }

    /**
     * Runtime source implementation. It must not execute guest code from an
     * arbitrary producer thread.
     */
    protected abstract OresFuture<OresNotification<T>> nextFromRuntime();

    /**
     * Runtime cancellation hook. Implementations should be idempotent.
     */
    protected void cancelFromRuntime() {
        // Default no-op.
    }
}
