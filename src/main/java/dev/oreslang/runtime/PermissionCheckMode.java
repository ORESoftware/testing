package dev.oreslang.runtime;

import java.util.Locale;

/**
 * Controls when statically identifiable external-I/O permission failures are
 * reported. Runtime enforcement is never disabled.
 */
public enum PermissionCheckMode {
    /** Compile/check rejects statically visible calls whose coarse capability is absent. */
    COMPILE,
    /** Compilation succeeds; the native I/O boundary rejects an unauthorized operation. */
    RUNTIME;

    public static PermissionCheckMode parse(String value) {
        if (value == null || value.isBlank()) return RUNTIME;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "compile", "static", "strict" -> COMPILE;
            case "runtime", "dynamic", "defer" -> RUNTIME;
            default -> throw new IllegalArgumentException(
                    "unknown permission check mode '" + value + "'; expected runtime or compile");
        };
    }

    public static PermissionCheckMode fromApplicationArguments(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--ores-permission-check=")) {
                return parse(arg.substring("--ores-permission-check=".length()));
            }
        }
        return COMPILE;
    }

    public boolean defersToRuntime(IsolatePolicy.Capability capability) {
        if (this != RUNTIME) return false;
        return switch (capability) {
            case NETWORK,
                    FILESYSTEM_READ,
                    FILESYSTEM_WRITE,
                    ENVIRONMENT,
                    PROCESS_INFO,
                    CHILD_PROCESS -> true;
            default -> false;
        };
    }
}
