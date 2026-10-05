package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

final class OresObservableTest {

    @Test
    void fromValuesIsColdReplayableAndPullBackpressured() {
        OresObservable<Integer> values = OresObservable.fromValues(List.of(1, 2, 3));

        OresSubscription<Integer> first = values.subscribe();
        OresSubscription<Integer> second = values.subscribe();

        assertEquals(1, first.next().join().value());
        assertEquals(2, first.next().join().value());

        assertEquals(1, second.next().join().value());
        assertEquals(3, first.next().join().value());
        assertTrue(first.next().join().isComplete());

        assertEquals(2, second.next().join().value());
        assertEquals(3, second.next().join().value());
        assertTrue(second.next().join().isComplete());
    }

    @Test
    void onlyOneNextMayBeOutstanding() {
        OresFuture<Integer> source = new OresFuture<>();
        OresSubscription<Integer> subscription =
                OresObservable.fromFuture(source).subscribe();

        OresFuture<OresNotification<Integer>> first = subscription.next();
        OresFuture<OresNotification<Integer>> duplicate = subscription.next();

        CompletionException failure =
                assertThrows(CompletionException.class, duplicate::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());

        source.completeFromRuntime(7);
        assertEquals(7, first.join().value());
        assertTrue(subscription.next().join().isComplete());
    }

    @Test
    void takeCancelsUpstreamAfterLimitAndThenCompletes() {
        OresSubscription<Integer> subscription =
                OresObservable.fromValues(List.of(10, 20, 30, 40))
                        .take(2)
                        .subscribe();

        assertEquals(10, subscription.next().join().value());
        assertEquals(20, subscription.next().join().value());
        assertTrue(subscription.next().join().isComplete());
        assertTrue(subscription.isTerminated());
    }

    @Test
    void firstBridgesObservableBackToOresFuture() {
        OresFuture<Integer> first =
                OresObservable.fromValues(List.of(4, 5, 6)).first();

        assertEquals(4, first.join());
    }

    @Test
    void firstFailsForEmptyStream() {
        OresFuture<Integer> first = OresObservable.<Integer>empty().first();

        CompletionException failure =
                assertThrows(CompletionException.class, first::join);
        assertInstanceOf(java.util.NoSuchElementException.class, failure.getCause());
    }

    @Test
    void cancellingOneSharedFutureSubscriptionDoesNotCancelProducer() {
        OresFuture<Integer> source = new OresFuture<>();
        OresObservable<Integer> observable = OresObservable.fromFuture(source);

        OresSubscription<Integer> one = observable.subscribe();
        OresSubscription<Integer> two = observable.subscribe();

        OresFuture<OresNotification<Integer>> first = one.next();
        OresFuture<OresNotification<Integer>> second = two.next();

        assertTrue(one.cancel());
        assertFalse(source.isCancelled(),
                "one rx subscriber must not cancel a shared source Future");

        source.completeFromRuntime(99);

        assertThrows(CancellationException.class, first::join);
        assertEquals(99, second.join().value());
    }

    @Test
    void sourceFailureIsTerminal() {
        OresFuture<Integer> source =
                OresFuture.failed(new IllegalStateException("boom"));
        OresSubscription<Integer> subscription =
                OresObservable.fromFuture(source).subscribe();

        CompletionException failure =
                assertThrows(CompletionException.class, () -> subscription.next().join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());

        assertTrue(subscription.isTerminated());
        assertTrue(subscription.next().join().isComplete());
    }

    @Test
    void publicObservableSurfaceDoesNotExposeGuestCallbackSubscribe() {
        assertFalse(
                java.util.Arrays.stream(OresObservable.class.getMethods())
                        .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                        .anyMatch(type -> java.util.function.Consumer.class.isAssignableFrom(type)
                                || java.util.function.Function.class.isAssignableFrom(type)),
                "initial rx-ores surface must not run guest callbacks on producer threads");
    }
}
