package dev.oreslang.runtime;

/**
 * Serialized group mail handler.
 *
 * One logical instance belongs to one ActorGroup. The runtime owns the endless
 * scheduling loop; subclasses implement one bounded mail-handling turn rather
 * than writing while(true) themselves.
 */
public abstract class ActorMailman<Out> {
    public abstract void receiveMail(
            ActorMail<Out> mail,
            ActorGroupContext<Out> group) throws Exception;
}
