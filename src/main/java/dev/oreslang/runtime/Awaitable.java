package dev.oreslang.runtime;

/**
 * Compiler/runtime projection used by the Oreslang {@code await} operator.
 *
 * <p>{@code await} does not execute a continuation from this method. The
 * projection only exposes the runtime Future that represents readiness. The
 * owning actor/task scheduler remains solely responsible for suspending,
 * unwinding the current turn, and dispatching the continuation later.</p>
 */
@FunctionalInterface
public interface Awaitable<T> {
    OresFuture<T> getAwaited();
}
