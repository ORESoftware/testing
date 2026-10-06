package dev.oreslang.compiler;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closed-world build configuration used by Oreslang's build optimizer.
 *
 * Build defines are intentionally explicit compiler inputs. Guest code does not
 * read the compiler process environment directly; build tools translate
 * environment variables and CLI --define switches into this immutable object.
 */
public record BuildOptions(
        Map<String, String> defines,
        Set<String> entryPoints,
        boolean preservePublicApi) {

    public static final String DEFINES_ENV = "ORESLANG_BUILD_DEFINES";
    public static final String DEFINE_ENV_PREFIX = "ORESLANG_DEFINE_";

    public BuildOptions {
        defines = Map.copyOf(defines == null ? Map.of() : defines);
        entryPoints = Set.copyOf(entryPoints == null ? Set.of() : entryPoints);
        for (String name : defines.keySet()) validateName(name);
        for (String entryPoint : entryPoints) validateName(entryPoint);
    }

    public static BuildOptions executable() {
        return executable(Map.of());
    }

    public static BuildOptions executable(Map<String, String> defines) {
        return new BuildOptions(defines, Set.of("main"), false);
    }

    public static BuildOptions library(Map<String, String> defines) {
        return new BuildOptions(defines, Set.of(), true);
    }

    /**
     * Merge build defines from the process environment and repeated CLI
     * --define=name=value switches. CLI values win over environment values.
     *
     * Supported environment forms:
     *   ORESLANG_BUILD_DEFINES=use_a=true,backend=native
     *   ORESLANG_DEFINE_USE_A=true
     */
    public static Map<String, String> mergeDefines(
            Map<String, String> environment,
            List<String> cliDefines) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        Map<String, String> env = environment == null ? Map.of() : environment;

        String aggregate = env.get(DEFINES_ENV);
        if (aggregate != null && !aggregate.isBlank()) {
            addAssignments(merged, aggregate, ",", DEFINES_ENV);
        }

        env.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(DEFINE_ENV_PREFIX))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String name = entry.getKey().substring(DEFINE_ENV_PREFIX.length());
                    validateName(name);
                    merged.put(name, entry.getValue());
                });

        if (cliDefines != null) {
            for (String assignment : cliDefines) {
                addAssignment(merged, assignment, "--define");
            }
        }
        return Map.copyOf(merged);
    }

    private static void addAssignments(
            Map<String, String> target,
            String assignments,
            String delimiter,
            String source) {
        for (String assignment : assignments.split(delimiter)) {
            if (!assignment.isBlank()) addAssignment(target, assignment.trim(), source);
        }
    }

    private static void addAssignment(
            Map<String, String> target,
            String assignment,
            String source) {
        int equals = assignment.indexOf('=');
        if (equals <= 0) {
            throw new IllegalArgumentException(
                    source + " build define must use name=value: '" + assignment + "'");
        }
        String name = assignment.substring(0, equals).trim();
        String value = assignment.substring(equals + 1).trim();
        validateName(name);
        target.put(name, value);
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("build define/entry-point name cannot be blank");
        }
        String[] segments = name.split("\\.", -1);
        for (String segment : segments) {
            if (!segment.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("invalid build symbol name '" + name + "'");
            }
        }
    }
}
