package dev.oreslang.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque actor-side capability for group membership/spawn selection.
 *
 * This value deliberately contains no ActorRuntime, ActorGroupRuntime, mailman,
 * supervisor, inbox, outbox, registry, or other mutable object reference. A
 * memory-isolated actor can therefore carry it without gaining a pointer into
 * the shared runtime graph.
 */
public final class ActorGroupHandle<Out> {
    private final ActorGroupId id;
    private final ActorRuntime.ActorKind kind;
    private final UUID capabilityNonce;

    ActorGroupHandle(
            ActorGroupId id,
            ActorRuntime.ActorKind kind,
            UUID capabilityNonce) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.capabilityNonce = Objects.requireNonNull(capabilityNonce, "capabilityNonce");
    }

    public ActorGroupId id() { return id; }
    public ActorRuntime.ActorKind kind() { return kind; }

    UUID capabilityNonce() { return capabilityNonce; }

    @Override
    public String toString() {
        return "ActorGroupHandle[" + kind + ":" + id.value() + "]";
    }
}
