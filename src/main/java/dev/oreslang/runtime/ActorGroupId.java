package dev.oreslang.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity for one concrete actor-group incarnation.
 *
 * Logical names may be reused after a group terminates; an ActorGroupId may not.
 */
public record ActorGroupId(UUID value, long generation) {
    public ActorGroupId {
        Objects.requireNonNull(value, "value");
        if (generation < 0) throw new IllegalArgumentException("generation must be >= 0");
    }

    public static ActorGroupId create(long generation) {
        return new ActorGroupId(UUID.randomUUID(), generation);
    }
}
