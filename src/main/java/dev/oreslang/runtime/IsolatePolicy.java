package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.io.IOAccess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Deny-by-default security policy for an Oreslang isolate/context. */
public record IsolatePolicy(
        Set<Capability> capabilities,
        long maxHeapBytes,
        int maxMailboxMessages,
        Duration maxWallTime,
        boolean adversarial) {

    public enum Capability {
        STDIN,
        STDOUT,
        PROCESS_INFO,
        GC_CONTROL,
        ACTOR_SHARE_READONLY,
        ACTOR_SPAWN,
        SHARED_MEMORY,
        NETWORK,
        IPC,
        GPU,
        FILESYSTEM_READ,
        FILESYSTEM_WRITE,
        ENVIRONMENT,
        HOT_CODE_LOAD,
        JAVA_INTEROP,
        JAVA_SOURCE_INTEROP,
        FFI,
        NATIVE,
        REFLECTION,
        CHILD_PROCESS,
        THREAD_CREATE,
        POLYGLOT
    }

    public IsolatePolicy(Set<Capability> capabilities, long maxHeapBytes, int maxMailboxMessages, Duration maxWallTime) {
        this(capabilities, maxHeapBytes, maxMailboxMessages, maxWallTime, false);
    }

    public IsolatePolicy {
        capabilities = Set.copyOf(capabilities);
        if (maxHeapBytes < 16L * 1024 * 1024) throw new IllegalArgumentException("maxHeapBytes must be at least 16 MiB");
        if (maxMailboxMessages <= 0) throw new IllegalArgumentException("maxMailboxMessages must be positive");
        if (maxWallTime.isNegative() || maxWallTime.isZero()) throw new IllegalArgumentException("maxWallTime must be positive");
        if (adversarial && capabilities.contains(Capability.THREAD_CREATE)) {
            throw new IllegalArgumentException("adversarial isolates cannot grant THREAD_CREATE");
        }
        if (adversarial && capabilities.contains(Capability.JAVA_INTEROP)) {
            throw new IllegalArgumentException("adversarial isolates cannot grant JAVA_INTEROP");
        }
        if (adversarial && capabilities.contains(Capability.JAVA_SOURCE_INTEROP)) {
            throw new IllegalArgumentException("adversarial isolates cannot grant JAVA_SOURCE_INTEROP");
        }
    }

    /**
     * Production FaaS baseline: malicious-source threat model, stdout only,
     * one guest thread, hard VM isolate and guest resource limits.
     */
    public static IsolatePolicy strictFaas() {
        return new IsolatePolicy(Set.of(Capability.STDOUT), 128L * 1024 * 1024, 1024, Duration.ofSeconds(30), true);
    }

    /**
     * Fixed baseline for hostile hot-loaded actor code. The supervisor owns
     * loading and routing; an untrusted actor starts with no ambient capabilities.
     */
    public static IsolatePolicy untrustedActor() {
        return new IsolatePolicy(
                Set.of(),
                32L * 1024 * 1024,
                128,
                Duration.ofMillis(250),
                true);
    }

    /** Restricted local/test baseline. Java interop/FFI/native/reflection/process spawning remain denied. */
    public static IsolatePolicy developer() {
        return new IsolatePolicy(
                Set.of(Capability.STDIN, Capability.STDOUT, Capability.PROCESS_INFO, Capability.GC_CONTROL,
                        Capability.ACTOR_SHARE_READONLY, Capability.ACTOR_SPAWN,
                        Capability.SHARED_MEMORY, Capability.HOT_CODE_LOAD),
                512L * 1024 * 1024, 8192, Duration.ofMinutes(10), false);
    }

    public IsolatePolicy withCapabilities(Capability... added) {
        EnumSet<Capability> next = capabilities.isEmpty()
                ? EnumSet.noneOf(Capability.class)
                : EnumSet.copyOf(capabilities);
        next.addAll(Arrays.asList(added));
        return new IsolatePolicy(next, maxHeapBytes, maxMailboxMessages, maxWallTime, adversarial);
    }

    public IsolatePolicy withoutCapabilities(Capability... removed) {
        EnumSet<Capability> next = capabilities.isEmpty()
                ? EnumSet.noneOf(Capability.class)
                : EnumSet.copyOf(capabilities);
        next.removeAll(Arrays.asList(removed));
        return new IsolatePolicy(next, maxHeapBytes, maxMailboxMessages, maxWallTime, adversarial);
    }

    public IsolatePolicy asAdversarial() {
        return adversarial ? this : new IsolatePolicy(capabilities, maxHeapBytes, maxMailboxMessages, maxWallTime, true);
    }

    /**
     * Graal baseline: no host reflection, native access, polyglot calls,
     * environment access, guest-created threads, or host filesystem/network IO.
     *
     * For adversarial policies, Graal's UNTRUSTED sandbox is selected. It
     * spawns a VM-level polyglot isolate when the language-isolate artifact is
     * available and enforces guest/isolate resource limits.
     */
    public Context.Builder restrictedContextBuilder() {
        return restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of());
    }

    public Context.Builder restrictedContextBuilder(ExecutionProfile profile) {
        return restrictedContextBuilder(profile, Set.of());
    }

    /**
     * Builds a deny-by-default Graal context. Java host classes require two
     * independent grants: JAVA_INTEROP and this exact fully-qualified allowlist.
     */
    public Context.Builder restrictedContextBuilder(
            ExecutionProfile profile,
            Set<String> allowedHostClasses) {
        Set<String> hostClasses = Set.copyOf(allowedHostClasses);
        if (!hostClasses.isEmpty()) {
            require(Capability.JAVA_INTEROP, "Java host imports");
            if (adversarial) {
                throw new SecurityException("Java host imports are disabled for adversarial isolates");
            }
            for (String className : hostClasses) validateHostClassAuthority(className);
        }

        HostAccess hostAccess;
        if (!hostClasses.isEmpty()) {
            hostAccess = explicitHostAccess(hostClasses);
        } else {
            hostAccess = adversarial
                    ? HostAccess.newBuilder(HostAccess.NONE).allowMutableTargetMappings().methodScoping(true).build()
                    : HostAccess.NONE;
        }

        Context.Builder builder = Context.newBuilder(OresLanguage.ID)
                .allowHostAccess(hostAccess)
                .allowHostClassLookup(hostClasses.isEmpty() ? ignored -> false : hostClasses::contains)
                .allowHostClassLoading(false)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowNativeAccess(false)
                .allowCreateThread(false)
                .allowIO(IOAccess.NONE)
                .in(new ByteArrayInputStream(new byte[0]))
                .out(new ByteArrayOutputStream())
                .err(new ByteArrayOutputStream())
                .arguments(OresLanguage.ID, applicationArguments(profile));

        /*
         * Graal's engine.IsolateLibrary option is experimental in 25.x. Opt in
         * only when the embedding process explicitly supplies a polyglot
         * isolate library; ordinary strict/adversarial contexts remain on the
         * non-experimental builder path.
         */
        if (System.getProperty("polyglot.engine.IsolateLibrary") != null) {
            builder.allowExperimentalOptions(true);
        }

        if (adversarial) {
            long guestHeap = Math.max(8L * 1024 * 1024, maxHeapBytes * 3 / 4);
            long maxOutput = allows(Capability.STDOUT) ? 1024L * 1024 : 0L;
            builder.sandbox(SandboxPolicy.UNTRUSTED)
                    .spawnIsolate(true)
                    .option("engine.MaxIsolateMemory", bytes(maxHeapBytes))
                    .option("sandbox.MaxHeapMemory", bytes(guestHeap))
                    .option("sandbox.MaxCPUTime", duration(maxWallTime))
                    .option("sandbox.MaxASTDepth", "256")
                    .option("sandbox.MaxThreads", "1")
                    .option("sandbox.MaxOutputStreamSize", bytes(maxOutput))
                    .option("sandbox.MaxErrorStreamSize", "64KB");
        }

        return builder;
    }

    private void validateHostClassAuthority(String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("allowlisted Java host class name cannot be blank");
        }

        if (className.equals("java.lang.Class")
                || className.equals("java.lang.ClassLoader")
                || className.equals("java.lang.Module")
                || className.equals("java.lang.Runtime")
                || className.equals("java.lang.System")
                || className.equals("java.lang.Process")
                || className.equals("java.lang.ProcessBuilder")
                || className.equals("java.lang.ProcessHandle")
                || className.equals("java.lang.Thread")
                || className.equals("java.lang.ThreadGroup")
                || className.equals("java.lang.SecurityManager")
                || className.equals("java.util.ServiceLoader")
                || className.startsWith("java.lang.reflect.")
                || className.startsWith("java.lang.invoke.")
                || className.startsWith("java.beans.")
                || className.startsWith("javax.script.")
                || className.startsWith("javax.tools.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.")) {
            throw new SecurityException("Java host class is blocked from class-level interop: " + className);
        }

        if (className.startsWith("java.io.") || className.startsWith("java.nio.file.")) {
            require(Capability.FILESYSTEM_READ, "Java host class " + className);
            require(Capability.FILESYSTEM_WRITE, "Java host class " + className);
        }
        if (className.startsWith("java.net.")) {
            require(Capability.NETWORK, "Java host class " + className);
        }
        if (className.startsWith("java.nio.channels.")) {
            require(Capability.NETWORK, "Java host class " + className);
            require(Capability.FILESYSTEM_READ, "Java host class " + className);
            require(Capability.FILESYSTEM_WRITE, "Java host class " + className);
        }
        if (className.startsWith("java.util.concurrent.")) {
            require(Capability.THREAD_CREATE, "Java host class " + className);
        }
        if (className.startsWith("java.lang.foreign.")) {
            require(Capability.NATIVE, "Java host class " + className);
        }
        if (className.startsWith("java.lang.management.")) {
            require(Capability.PROCESS_INFO, "Java host class " + className);
        }
    }

    private static HostAccess explicitHostAccess(Set<String> hostClasses) {
        HostAccess.Builder access = HostAccess.newBuilder(HostAccess.NONE)
                .allowAccessInheritance(false);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        for (String className : hostClasses) {
            final Class<?> type;
            try {
                type = Class.forName(className, false, loader);
            } catch (ClassNotFoundException failure) {
                throw new IllegalArgumentException("allowlisted Java host class is unavailable: " + className, failure);
            }
            if (!Modifier.isPublic(type.getModifiers())) {
                throw new IllegalArgumentException("allowlisted Java host class must be public: " + className);
            }

            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) access.allowAccess(constructor);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) access.allowAccess(method);
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())) access.allowAccess(field);
            }
        }
        return access.build();
    }

    public boolean allows(Capability capability) {
        return capabilities.contains(capability);
    }

    public void require(Capability capability, String api) {
        if (!allows(capability)) {
            throw new SecurityException("Oreslang isolate denies capability " + capability + " required by " + api);
        }
    }

    public String[] applicationArguments(ExecutionProfile profile) {
        String caps = capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return new String[] {
                "--ores-capabilities=" + caps,
                "--ores-max-heap-bytes=" + maxHeapBytes,
                "--ores-max-mailbox-messages=" + maxMailboxMessages,
                "--ores-max-wall-ms=" + maxWallTime.toMillis(),
                "--ores-adversarial=" + adversarial,
                "--ores-execution-mode=" + profile.mode().name(),
                "--ores-platform=" + profile.platform().name()
        };
    }

    public static IsolatePolicy fromApplicationArguments(String[] args) {
        String raw = null;
        long maxHeap = 128L * 1024 * 1024;
        int maxMailbox = 1024;
        long maxWallMs = 30_000L;
        boolean adversarial = false;
        for (String arg : args) {
            if (arg.startsWith("--ores-capabilities=")) raw = arg.substring("--ores-capabilities=".length());
            else if (arg.startsWith("--ores-max-heap-bytes=")) maxHeap = Long.parseLong(arg.substring("--ores-max-heap-bytes=".length()));
            else if (arg.startsWith("--ores-max-mailbox-messages=")) maxMailbox = Integer.parseInt(arg.substring("--ores-max-mailbox-messages=".length()));
            else if (arg.startsWith("--ores-max-wall-ms=")) maxWallMs = Long.parseLong(arg.substring("--ores-max-wall-ms=".length()));
            else if (arg.startsWith("--ores-adversarial=")) adversarial = Boolean.parseBoolean(arg.substring("--ores-adversarial=".length()));
        }
        if (raw == null) return developer();
        EnumSet<Capability> caps = EnumSet.noneOf(Capability.class);
        if (!raw.isBlank()) {
            for (String value : raw.split(",")) caps.add(Capability.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        }
        return new IsolatePolicy(caps, maxHeap, maxMailbox, Duration.ofMillis(maxWallMs), adversarial);
    }

    public static ExecutionProfile executionProfileFromApplicationArguments(String[] args) {
        String mode = "JIT";
        String platform = "SERVER";
        for (String arg : args) {
            if (arg.startsWith("--ores-execution-mode=")) mode = arg.substring("--ores-execution-mode=".length());
            else if (arg.startsWith("--ores-platform=")) platform = arg.substring("--ores-platform=".length());
        }
        return ExecutionProfile.parse(mode, platform);
    }

    private static String bytes(long value) {
        return value + "B";
    }

    private static String duration(Duration value) {
        return value.toMillis() + "ms";
    }
}
