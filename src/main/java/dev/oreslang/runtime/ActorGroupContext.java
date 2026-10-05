package dev.oreslang.runtime;

/**
 * Narrow authority exposed to a mailman turn.
 *
 * It can route messages into actor inboxes but cannot mutate actor state or
 * obtain direct references to actor-owned memory.
 */
public interface ActorGroupContext<Out> {
    ActorGroupId groupId();
    int actorCount();

    <M> void send(ActorRuntime.ActorRef<M> target, M message);
}
