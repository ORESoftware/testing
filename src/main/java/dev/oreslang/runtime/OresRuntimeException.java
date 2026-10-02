package dev.oreslang.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/**
 * Guest-visible Oreslang runtime failure.
 *
 * Runtime language errors must cross the Truffle boundary as guest exceptions,
 * never as arbitrary JVM exceptions that Graal reports as internal VM errors.
 */
public final class OresRuntimeException extends AbstractTruffleException {
    public OresRuntimeException(String message) {
        super(message);
    }

    public OresRuntimeException(String message, Throwable cause) {
        super(message, cause, UNLIMITED_STACK_TRACE, null);
    }
}
