package dev.oreslang.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * Supervisor/root-side management reference. Actor code should receive only
 * ActorGroupHandle, never this richer runtime-bound reference.
 */
public final class ActorGroupRef<Out> {
    private final ActorRuntime runtime;
    private final ActorGroupId id;
    private final ActorRuntime.ActorKind kind;
    private final UUID capabilityNonce;

    ActorGroupRef(
            ActorRuntime runtime,
            ActorGroupId id,
            ActorRuntime.ActorKind kind,
            UUID capabilityNonce) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.capabilityNonce = Objects.requireNonNull(capabilityNonce, "capabilityNonce");
    }

    public ActorGroupId id() { return id; }
    public ActorRuntime.ActorKind kind() { return kind; }

    public ActorGroupHandle<Out> handle() {
        return new ActorGroupHandle<>(id, kind, capabilityNonce);
    }

    boolean ownedBy(ActorRuntime candidate) {
        return runtime == candidate;
    }

    UUID capabilityNonce() {
        return capabilityNonce;
    }

    @Override
    public String toString() {
        return "ActorGroupRef[" + kind + ":" + id.value() + "]";
    }
}
