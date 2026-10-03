package dev.oreslang.runtime;

/**
 * Non-guest-catchable execution control signal.
 *
 * Oreslang try/catch handles ordinary RuntimeException failures. Resource
 * deadlines, runtime shutdown and scheduler interruption are host/supervisor
 * control events and must not be suppressible by guest error handling.
 *
 * This remains a RuntimeException for clean Truffle/host reporting. The
 * Oreslang guest try/catch evaluator recognizes this exact type and rethrows it
 * before ordinary guest RuntimeException handling, so guest source cannot
 * suppress supervisor/resource-control termination.
 */
public final class ExecutionTerminated extends RuntimeException {
    public ExecutionTerminated(String message) {
        super(message, null, false, false);
    }
}
