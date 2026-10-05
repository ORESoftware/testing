package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Native rx-ores observable substrate.
 *
 * <p>The initial runtime contract is intentionally pull-oriented. A subscriber
 * requests one item with {@link OresSubscription#next()}, receives an
 * {@link OresFuture}, and therefore composes with the exact same scheduler
 * suspension primitive as Oreslang {@code await}.</p>
 *
 * <p>This class deliberately does not expose Consumer/Function callback-style
 * subscribe/map/filter APIs yet. Guest callbacks must execute on the owning Ores
 * scheduler domain, not on whichever timer/I/O/JNI thread happens to settle a
 * Future. Higher-order operators will be enabled once source lowering can bind
 * those lambdas to resumable Ores tasks safely.</p>
 *
 * <p><strong>Linking boundary:</strong> rx-ores is a core library, not an
 * implicit runtime dependency. Base OresVM/runtime initialization must not
 * eagerly register or instantiate this class merely to advertise RX support.
 * The standard-library linker/source lowering should make the RX runtime
 * reachable only for programs that explicitly import the RX core library.
 * This keeps closed-world AOT reachability capable of removing the entire RX
 * substrate from applications that do not use it.</p>
 */
public abstract class OresObservable<T> {

    public abstract OresSubscription<T> subscribe();

    public static <T> OresObservable<T> empty() {
        return new OresObservable<>() {
            @Override
            public OresSubscription<T> subscribe() {
                return new OresSubscription<>() {
                    @Override
                    protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        return OresFuture.completed(OresNotification.complete());
                    }
                };
            }
        };
    }

    public static <T> OresObservable<T> just(T value) {
        return fromValues(Collections.singletonList(value));
    }

    /**
     * Cold, replayable observable backed by an immutable subscription snapshot.
     */
    public static <T> OresObservable<T> fromValues(List<? extends T> values) {
        Objects.requireNonNull(values, "values");
        ArrayList<T> snapshot = new ArrayList<>(values.size());
        snapshot.addAll(values);
        List<T> immutable = Collections.unmodifiableList(snapshot);

        return new OresObservable<>() {
            @Override
            public OresSubscription<T> subscribe() {
                return new OresSubscription<>() {
                    private int index;

                    @Override
                    protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        if (index >= immutable.size()) {
                            return OresFuture.completed(OresNotification.complete());
                        }
                        T value = immutable.get(index++);
                        return OresFuture.completed(OresNotification.next(value));
                    }
                };
            }
        };
    }

    /**
     * Adapt one shared Future into a single-item observable.
     *
     * <p>Subscription cancellation does not cancel the supplied Future because
     * another subscriber may be observing the same operation. Use an owned
     * source primitive in a later rx-ores layer when per-subscription producer
     * lifetime is required.</p>
     */
    public static <T> OresObservable<T> fromFuture(OresFuture<? extends T> future) {
        Objects.requireNonNull(future, "future");

        return new OresObservable<>() {
            @Override
            public OresSubscription<T> subscribe() {
                return new OresSubscription<>() {
                    private boolean emitted;

                    @Override
                    protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        if (emitted) {
                            return OresFuture.completed(OresNotification.complete());
                        }
                        emitted = true;
                        return mapRuntime(
                                future,
                                OresNotification::next,
                                false);
                    }
                };
            }
        };
    }

    /**
     * Emit at most {@code limit} items, then cancel the upstream subscription.
     */
    public final OresObservable<T> take(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        if (limit == 0) {
            return empty();
        }

        OresObservable<T> upstream = this;
        return new OresObservable<>() {
            @Override
            public OresSubscription<T> subscribe() {
                OresSubscription<T> inner = upstream.subscribe();

                return new OresSubscription<>() {
                    private int remaining = limit;
                    private boolean cutOff;

                    @Override
                    protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        if (cutOff || remaining == 0) {
                            return OresFuture.completed(OresNotification.complete());
                        }

                        return mapRuntime(inner.next(), notification -> {
                            if (notification.isComplete()) {
                                cutOff = true;
                                return notification;
                            }

                            remaining--;
                            if (remaining == 0) {
                                cutOff = true;
                                inner.cancel();
                            }
                            return notification;
                        }, true);
                    }

                    @Override
                    protected void cancelFromRuntime() {
                        inner.cancel();
                    }
                };
            }
        };
    }

    /**
     * Bridge the first item back to the ordinary Ores Future world.
     */
    public final OresFuture<T> first() {
        OresSubscription<T> subscription = subscribe();
        OresFuture<T> result = new OresFuture<>(subscription::cancel);

        OresFuture<OresNotification<T>> pull = subscription.next();
        pull.whenCompleteRuntime((notification, failure) -> {
            if (result.isDone()) {
                return;
            }
            if (failure != null) {
                result.failFromRuntime(OresFuture.unwrap(failure));
                subscription.cancel();
                return;
            }
            if (notification == null || notification.isComplete()) {
                result.failFromRuntime(new NoSuchElementException(
                        "rx-ores first() observed an empty stream"));
                subscription.cancel();
                return;
            }

            result.completeFromRuntime(notification.value());
            subscription.cancel();
        });

        return result;
    }

    /**
     * Runtime-only Future transformation. The mapper must be trusted runtime
     * plumbing, never an arbitrary Oreslang guest callback.
     */
    private static <I, O> OresFuture<O> mapRuntime(
            OresFuture<? extends I> source,
            Function<? super I, ? extends O> mapper,
            boolean propagateCancellation) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mapper, "mapper");

        OresFuture<O> result = propagateCancellation
                ? new OresFuture<>(() -> source.cancel(true))
                : new OresFuture<>();

        source.whenCompleteRuntime((value, failure) -> {
            if (result.isDone()) {
                return;
            }
            if (failure != null) {
                result.failFromRuntime(OresFuture.unwrap(failure));
                return;
            }
            try {
                result.completeFromRuntime(mapper.apply(value));
            } catch (Throwable mappingFailure) {
                result.failFromRuntime(mappingFailure);
            }
        });

        return result;
    }
}
