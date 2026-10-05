package dev.oreslang.runtime;

import java.util.Objects;

/**
 * One pull result from an {@link OresSubscription}.
 *
 * <p>Reactive errors are represented by a failed {@link OresFuture}; they are
 * not smuggled through the value channel. This keeps the stream error model
 * aligned with ordinary Oreslang Future/await failure propagation.</p>
 */
public record OresNotification<T>(Kind kind, T value) {

    public enum Kind {
        NEXT,
        COMPLETE
    }

    public OresNotification {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.COMPLETE && value != null) {
            throw new IllegalArgumentException("COMPLETE notifications cannot carry a value");
        }
    }

    public static <T> OresNotification<T> next(T value) {
        return new OresNotification<>(Kind.NEXT, value);
    }

    public static <T> OresNotification<T> complete() {
        return new OresNotification<>(Kind.COMPLETE, null);
    }

    public boolean isNext() {
        return kind == Kind.NEXT;
    }

    public boolean isComplete() {
        return kind == Kind.COMPLETE;
    }
}
