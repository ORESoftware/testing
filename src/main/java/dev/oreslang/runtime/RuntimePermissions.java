package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scoped, deny-by-default permissions for host I/O.
 *
 * <p>The {@link IsolatePolicy} capability set remains the coarse actor/isolate
 * authority boundary. This class narrows those capabilities to concrete paths,
 * network endpoints, environment keys, commands, system information, FFI
 * libraries, and runtime import roots. Explicit deny rules always win.</p>
 */
public final class RuntimePermissions {
    public enum Permission {
        READ("read", IsolatePolicy.Capability.FILESYSTEM_READ),
        WRITE("write", IsolatePolicy.Capability.FILESYSTEM_WRITE),
        NET("net", IsolatePolicy.Capability.NETWORK),
        ENV("env", IsolatePolicy.Capability.ENVIRONMENT),
        RUN("run", IsolatePolicy.Capability.CHILD_PROCESS),
        SYS("sys", IsolatePolicy.Capability.PROCESS_INFO),
        FFI("ffi", IsolatePolicy.Capability.FFI),
        IMPORT("import", IsolatePolicy.Capability.HOT_CODE_LOAD);

        private final String cliName;
        private final IsolatePolicy.Capability capability;

        Permission(String cliName, IsolatePolicy.Capability capability) {
            this.cliName = cliName;
            this.capability = capability;
        }

        public String cliName() { return cliName; }
        public IsolatePolicy.Capability capability() { return capability; }

        static Permission fromCliName(String name) {
            for (Permission permission : values()) {
                if (permission.cliName.equals(name)) return permission;
            }
            throw new IllegalArgumentException("unknown Oreslang permission '" + name + "'");
        }
    }

    private static final String ARG_PREFIX = "--ores-permission-";

    private record Rule(
            boolean allowAll,
            Set<String> allowed,
            boolean denyAll,
            Set<String> denied) {
        private Rule {
            allowed = Set.copyOf(allowed);
            denied = Set.copyOf(denied);
        }
    }

    private final EnumMap<Permission, Rule> rules;

    private RuntimePermissions(EnumMap<Permission, Rule> rules) {
        this.rules = new EnumMap<>(Permission.class);
        this.rules.putAll(rules);
    }

    /** Deny every privileged host-I/O operation. */
    public static RuntimePermissions denyAll() {
        return new Builder().build();
    }

    /**
     * Compatibility bridge for embedders that only supply coarse capabilities:
     * an explicitly granted capability means unrestricted access in that
     * category until a scoped permission policy is supplied.
     */
    public static RuntimePermissions fromCapabilities(Set<IsolatePolicy.Capability> capabilities) {
        Builder builder = new Builder();
        for (Permission permission : Permission.values()) {
            if (capabilities.contains(permission.capability())) builder.allowAll(permission);
        }
        return builder.build();
    }

    public static RuntimePermissions fromApplicationArguments(
            String[] args,
            IsolatePolicy isolatePolicy) {
        Builder builder = new Builder();
        boolean sawPermissionArgument = false;

        for (String arg : args) {
            if (!arg.startsWith(ARG_PREFIX) || arg.startsWith("--ores-permission-check=")) continue;
            sawPermissionArgument = true;
            String remainder = arg.substring(ARG_PREFIX.length());
            int equals = remainder.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("invalid Oreslang permission argument: " + arg);
            }
            String key = remainder.substring(0, equals);
            String value = remainder.substring(equals + 1);
            boolean deny;
            String permissionName;
            if (key.endsWith("-allow")) {
                deny = false;
                permissionName = key.substring(0, key.length() - "-allow".length());
            } else if (key.endsWith("-deny")) {
                deny = true;
                permissionName = key.substring(0, key.length() - "-deny".length());
            } else {
                throw new IllegalArgumentException("invalid Oreslang permission argument: " + arg);
            }

            Permission permission = Permission.fromCliName(permissionName);
            if (deny) builder.deny(permission, value);
            else builder.allow(permission, value);
        }

        return sawPermissionArgument
                ? builder.build()
                : fromCapabilities(isolatePolicy.capabilities());
    }

    public String[] applicationArguments() {
        ArrayList<String> arguments = new ArrayList<>();
        for (Permission permission : Permission.values()) {
            Rule rule = rules.get(permission);
            if (rule == null) continue;
            if (rule.allowAll()) {
                arguments.add(ARG_PREFIX + permission.cliName() + "-allow=*");
            } else {
                rule.allowed().stream().sorted().forEach(scope ->
                        arguments.add(ARG_PREFIX + permission.cliName() + "-allow=" + scope));
            }
            if (rule.denyAll()) {
                arguments.add(ARG_PREFIX + permission.cliName() + "-deny=*");
            } else {
                rule.denied().stream().sorted().forEach(scope ->
                        arguments.add(ARG_PREFIX + permission.cliName() + "-deny=" + scope));
            }
        }
        return arguments.toArray(String[]::new);
    }

    public RuntimePermissions withAllowed(Permission permission, String... scopes) {
        Builder builder = new Builder(this);
        for (String scope : scopes) builder.allow(permission, scope);
        return builder.build();
    }

    public RuntimePermissions withDenied(Permission permission, String... scopes) {
        Builder builder = new Builder(this);
        for (String scope : scopes) builder.deny(permission, scope);
        return builder.build();
    }

    public RuntimePermissions allowAll(Permission... permissions) {
        Builder builder = new Builder(this);
        for (Permission permission : permissions) builder.allowAll(permission);
        return builder.build();
    }

    public void require(Permission permission, String resource, String api) {
        if (!allows(permission, resource)) {
            String rendered = resource == null || resource.isBlank() ? "<all>" : resource;
            throw new SecurityException(
                    "Oreslang permission denied: " + permission.cliName()
                            + " access to '" + rendered + "' required by " + api);
        }
    }

    public boolean allows(Permission permission, String resource) {
        Rule rule = rules.get(permission);
        if (rule == null) return false;

        String normalized = normalize(permission, resource);
        if (rule.denyAll() || matchesAny(permission, normalized, rule.denied())) return false;
        return rule.allowAll() || matchesAny(permission, normalized, rule.allowed());
    }

    public boolean grantsAnything(Permission permission) {
        Rule rule = rules.get(permission);
        return rule != null && !rule.denyAll() && (rule.allowAll() || !rule.allowed().isEmpty());
    }

    public Set<IsolatePolicy.Capability> grantedCapabilities() {
        EnumSet<IsolatePolicy.Capability> capabilities =
                EnumSet.noneOf(IsolatePolicy.Capability.class);
        for (Permission permission : Permission.values()) {
            if (grantsAnything(permission)) capabilities.add(permission.capability());
        }
        return Set.copyOf(capabilities);
    }

    private static boolean matchesAny(
            Permission permission,
            String resource,
            Set<String> scopes) {
        for (String scope : scopes) {
            if (matches(permission, resource, scope)) return true;
        }
        return false;
    }

    private static boolean matches(Permission permission, String resource, String rawScope) {
        if ("*".equals(rawScope)) return true;
        String scope = normalize(permission, rawScope);

        return switch (permission) {
            case READ, WRITE, FFI, IMPORT -> {
                if (resource.equals(scope)) yield true;
                String separator = java.io.File.separator;
                yield resource.startsWith(scope.endsWith(separator) ? scope : scope + separator);
            }
            case NET -> networkMatches(resource, scope);
            case ENV, RUN, SYS -> tokenMatches(resource, scope);
        };
    }

    private static boolean networkMatches(String resource, String scope) {
        String target = resource.toLowerCase(Locale.ROOT);
        String rule = scope.toLowerCase(Locale.ROOT);
        if (target.equals(rule)) return true;

        // A host-only rule permits every port on that host.
        if (!rule.contains(":")) {
            int targetColon = target.lastIndexOf(':');
            String targetHost = targetColon > 0 ? target.substring(0, targetColon) : target;
            if (targetHost.equals(rule)) return true;
        }

        // "*.example.com" includes subdomains but not the bare parent.
        if (rule.startsWith("*.")) {
            String suffix = rule.substring(1);
            int targetColon = target.lastIndexOf(':');
            String targetHost = targetColon > 0 ? target.substring(0, targetColon) : target;
            return targetHost.endsWith(suffix) && targetHost.length() > suffix.length();
        }
        return false;
    }

    private static boolean tokenMatches(String resource, String scope) {
        if (resource.equals(scope)) return true;
        return scope.endsWith("*")
                && resource.startsWith(scope.substring(0, scope.length() - 1));
    }

    private static String normalize(Permission permission, String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();
        if ("*".equals(trimmed)) return "*";

        return switch (permission) {
            case READ, WRITE, FFI, IMPORT -> normalizePath(trimmed);
            case NET -> trimmed.toLowerCase(Locale.ROOT);
            case ENV, RUN, SYS -> trimmed;
        };
    }

    private static String normalizePath(String value) {
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            Path existing = path;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing != null) {
                try {
                    path = existing.toRealPath().resolve(existing.relativize(path)).normalize();
                } catch (java.io.IOException failure) {
                    throw new IllegalArgumentException(
                            "cannot resolve permission path '" + value + "'", failure);
                }
            }
            return path.toString();
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("invalid permission path '" + value + "'", invalid);
        }
    }

    private static final class MutableRule {
        private boolean allowAll;
        private boolean denyAll;
        private final LinkedHashSet<String> allowed = new LinkedHashSet<>();
        private final LinkedHashSet<String> denied = new LinkedHashSet<>();

        private MutableRule() { }

        private MutableRule(Rule rule) {
            this.allowAll = rule.allowAll();
            this.denyAll = rule.denyAll();
            this.allowed.addAll(rule.allowed());
            this.denied.addAll(rule.denied());
        }

        private Rule freeze() {
            return new Rule(allowAll, allowed, denyAll, denied);
        }
    }

    public static final class Builder {
        private final EnumMap<Permission, MutableRule> rules =
                new EnumMap<>(Permission.class);

        public Builder() { }

        public Builder(RuntimePermissions source) {
            source.rules.forEach((permission, rule) ->
                    rules.put(permission, new MutableRule(rule)));
        }

        public Builder allowAll(Permission permission) {
            MutableRule rule = rules.computeIfAbsent(permission, ignored -> new MutableRule());
            rule.allowAll = true;
            return this;
        }

        public Builder allow(Permission permission, String scope) {
            MutableRule rule = rules.computeIfAbsent(permission, ignored -> new MutableRule());
            if ("*".equals(scope == null ? null : scope.trim())) rule.allowAll = true;
            else rule.allowed.add(normalize(permission, scope));
            return this;
        }

        public Builder allowCsv(Permission permission, String scopes) {
            if (scopes == null || scopes.isBlank()) return this;
            Arrays.stream(scopes.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .forEach(value -> allow(permission, value));
            return this;
        }

        public Builder denyAll(Permission permission) {
            MutableRule rule = rules.computeIfAbsent(permission, ignored -> new MutableRule());
            rule.denyAll = true;
            return this;
        }

        public Builder deny(Permission permission, String scope) {
            MutableRule rule = rules.computeIfAbsent(permission, ignored -> new MutableRule());
            if ("*".equals(scope == null ? null : scope.trim())) rule.denyAll = true;
            else rule.denied.add(normalize(permission, scope));
            return this;
        }

        public Builder denyCsv(Permission permission, String scopes) {
            if (scopes == null || scopes.isBlank()) return this;
            Arrays.stream(scopes.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .forEach(value -> deny(permission, value));
            return this;
        }

        public RuntimePermissions build() {
            EnumMap<Permission, Rule> frozen = new EnumMap<>(Permission.class);
            rules.forEach((permission, rule) -> frozen.put(permission, rule.freeze()));
            return new RuntimePermissions(frozen);
        }
    }
}
