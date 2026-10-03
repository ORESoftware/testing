package dev.oreslang.runtime;

import java.util.Locale;

/**
 * Declares how the Oreslang runtime itself is deployed and whether guest code
 * may be optimized by a JIT. This is deliberately separate from source
 * semantics so the same .ores program can run on server, desktop, Android or
 * iOS profiles.
 */
public record ExecutionProfile(Mode mode, Platform platform) {
    public enum Mode { AOT, JIT, HYBRID }
    public enum Platform { SERVER, WINDOWS, MACOS, LINUX, ANDROID, IOS }

    public ExecutionProfile {
        if (mode == null || platform == null) throw new IllegalArgumentException("mode/platform are required");
        if (platform == Platform.IOS && mode != Mode.AOT) {
            throw new IllegalArgumentException("iOS profile is AOT-only; hot reload uses interpreted guest source/IR, not executable-code JIT");
        }
    }

    public static ExecutionProfile serverJit() { return new ExecutionProfile(Mode.JIT, Platform.SERVER); }
    public static ExecutionProfile serverHybrid() { return new ExecutionProfile(Mode.HYBRID, Platform.SERVER); }
    public static ExecutionProfile mobileAot(Platform platform) {
        if (platform != Platform.ANDROID && platform != Platform.IOS) throw new IllegalArgumentException("mobile profile requires ANDROID or IOS");
        return new ExecutionProfile(Mode.AOT, platform);
    }

    public boolean hostAheadOfTime() { return mode == Mode.AOT || mode == Mode.HYBRID; }
    public boolean guestJitAllowed() { return mode == Mode.JIT || mode == Mode.HYBRID; }
    public boolean supportsSourceHotReload() { return true; }

    public static ExecutionProfile parse(String mode, String platform) {
        return new ExecutionProfile(
                Mode.valueOf(mode.toUpperCase(Locale.ROOT)),
                Platform.valueOf(platform.toUpperCase(Locale.ROOT)));
    }
}
