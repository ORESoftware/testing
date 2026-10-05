package dev.oreslang.runtime;

import java.util.Objects;

/**
 * One immutable actor -> group-outbox envelope.
 *
 * The originating ActorId is metadata only. Mailmen send back through
 * ActorGroupContext so all replies re-enter an actor's single inbox.
 */
public record ActorMail<Out>(
        ActorRuntime.ActorId actor,
        ActorGroupId group,
        long sequence,
        Out message) {
    public ActorMail {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(group, "group");
        if (sequence < 0) throw new IllegalArgumentException("sequence must be >= 0");
    }
}
