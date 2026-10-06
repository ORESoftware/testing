package dev.oreslang.launcher;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.PermissionCheckMode;
import dev.oreslang.runtime.RuntimePermissions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OresMain {
    private static final Pattern POSITIONED_DIAGNOSTIC = Pattern.compile(
            "^Oreslang\\s+(?:lexer|parse)\\s+error\\s+at\\s+(\\d+):(\\d+):\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private OresMain() { }

    public static void main(String[] args) throws Exception {
        boolean strict = false;
        boolean checkOnly = false;
        boolean buildAnalysis = false;
        List<String> buildDefines = new ArrayList<>();
        Set<String> buildEntryPoints = new LinkedHashSet<>();
        String mode = "jit";
        String platform = "server";
        PermissionCheckMode permissionCheckMode = PermissionCheckMode.RUNTIME;
        RuntimePermissions.Builder permissionBuilder = new RuntimePermissions.Builder();
        EnumSet<RuntimePermissions.Permission> touchedPermissions =
                EnumSet.noneOf(RuntimePermissions.Permission.class);
        List<IsolatePolicy.Capability> additionalCapabilities = new ArrayList<>();
        Set<String> allowedHostClasses = new LinkedHashSet<>();
        String filename = null;

        for (String arg : args) {
            if (arg.equals("--strict-isolate")) strict = true;
            else if (arg.equals("--check")) checkOnly = true;
            else if (arg.equals("--build-analysis")) buildAnalysis = true;
            else if (arg.equals("--no-prompt")) {
                // Oreslang permissions never prompt. This Deno-compatible
                // spelling is accepted so automation can state that intent.
            } else if (arg.startsWith("--permission-check=")) {
                permissionCheckMode = PermissionCheckMode.parse(
                        arg.substring("--permission-check=".length()));
            } else if (arg.equals("--allow-all")) {
                for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
                    permissionBuilder.allowAll(permission);
                    touchedPermissions.add(permission);
                }
            } else if (parsePermissionFlag(arg, permissionBuilder, touchedPermissions)) {
                // handled by the scoped Deno-style permission parser
            } else if (arg.startsWith("--define=")) {
                String raw = arg.substring("--define=".length()).trim();
                if (raw.isEmpty()) throw new IllegalArgumentException("--define requires name=value");
                buildDefines.add(raw);
            } else if (arg.startsWith("--entry=")) {
                String raw = arg.substring("--entry=".length()).trim();
                if (raw.isEmpty()) throw new IllegalArgumentException("--entry requires a symbol name");
                buildEntryPoints.add(raw);
            } else if (arg.startsWith("--mode=")) mode = arg.substring("--mode=".length());
            else if (arg.startsWith("--platform=")) platform = arg.substring("--platform=".length());
            else if (arg.startsWith("--allow=")) {
                String raw = arg.substring("--allow=".length());
                if (!raw.isBlank()) {
                    for (String value : raw.split(",")) {
                        additionalCapabilities.add(IsolatePolicy.Capability.valueOf(
                                value.trim().toUpperCase(Locale.ROOT)));
                    }
                }
            } else if (arg.startsWith("--allow-host-class=")) {
                String raw = arg.substring("--allow-host-class=".length()).trim();
                if (raw.isEmpty()) {
                    throw new IllegalArgumentException("--allow-host-class requires a fully qualified Java class name");
                }
                allowedHostClasses.add(raw);
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("unknown option: " + arg);
            } else if (filename == null) filename = arg;
            else throw new IllegalArgumentException("only one .ores or .java source file may be supplied");
        }

        Path path;
        if (filename == null) {
            OresProjectConfig project = OresProjectConfig.discover(
                    Path.of("").toAbsolutePath().normalize(),
                    System.getenv());
            path = project.mainEntrypoint().orElse(null);
            if (path == null) {
                printUsage();
                System.err.println("or define [entrypoints].main in " + OresProjectConfig.MANIFEST_NAME);
                System.exit(2);
                return;
            }
            filename = path.toString();
        } else {
            path = Path.of(filename);
        }
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("not a file: " + path);

        if (checkOnly && buildAnalysis) {
            throw new IllegalArgumentException("--check and --build-analysis are mutually exclusive");
        }

        String directOresSource = filename.endsWith(".ores") ? Files.readString(path) : null;
        if (directOresSource != null) {
            formatSyntaxWarnings(path, directOresSource).forEach(System.err::println);
        }

        IsolatePolicy policy = strict ? IsolatePolicy.strictFaas() : IsolatePolicy.developer();
        if (!additionalCapabilities.isEmpty()) {
            policy = policy.withCapabilities(additionalCapabilities.toArray(IsolatePolicy.Capability[]::new));
        }

        final RuntimePermissions permissions;
        if (touchedPermissions.isEmpty()) {
            permissions = RuntimePermissions.fromCapabilities(policy.capabilities());
        } else {
            permissions = permissionBuilder.build();

            // A Deno-style permission flag is the authority for its category.
            // Remove any inherited/generic coarse grant, then restore it only
            // when the scoped policy grants at least one resource.
            for (RuntimePermissions.Permission permission : touchedPermissions) {
                policy = policy.withoutCapabilities(permission.capability());
            }
            Set<IsolatePolicy.Capability> scopedCapabilities = permissions.grantedCapabilities();
            if (!scopedCapabilities.isEmpty()) {
                policy = policy.withCapabilities(
                        scopedCapabilities.toArray(IsolatePolicy.Capability[]::new));
            }
        }

        if (buildAnalysis) {
            if (!filename.endsWith(".ores")) {
                throw new IllegalArgumentException("--build-analysis currently requires a .ores source file");
            }
            String source = Files.readString(path);
            if (permissionCheckMode == PermissionCheckMode.COMPILE) {
                OresCompiler.validateForIsolate(source, policy);
            }
            Map<String, String> defines = BuildOptions.mergeDefines(System.getenv(), buildDefines);
            Set<String> entries = buildEntryPoints.isEmpty() ? Set.of("main") : Set.copyOf(buildEntryPoints);
            TreeShaker.Result result = OresCompiler.compileForBuild(
                    directOresSource,
                    new BuildOptions(defines, entries, false));

            System.out.println("tree-shake retained:");
            result.retainedSymbols().stream().sorted().forEach(symbol -> System.out.println("  + " + symbol));
            System.out.println("tree-shake removed:");
            result.removedSymbols().stream().sorted().forEach(symbol -> System.out.println("  - " + symbol));
            return;
        }

        if (!buildDefines.isEmpty() || !buildEntryPoints.isEmpty()) {
            throw new IllegalArgumentException("--define/--entry require --build-analysis until the artifact build command is wired");
        }

        if (checkOnly) {
            try {
                LinkedProgramRunner.validate(
                        path,
                        System.getenv(),
                        policy,
                        permissionCheckMode);
            } catch (Exception error) {
                System.err.println(formatCheckDiagnostic(path, error));
                System.exit(1);
            }
            return;
        }

        ExecutionProfile profile = ExecutionProfile.parse(mode, platform);
        LinkedProgramRunner.run(
                path,
                policy,
                permissions,
                permissionCheckMode,
                profile,
                allowedHostClasses,
                System.getenv(),
                System.out,
                System.err);
    }

    private static boolean parsePermissionFlag(
            String arg,
            RuntimePermissions.Builder builder,
            EnumSet<RuntimePermissions.Permission> touched) {
        for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
            String allow = "--allow-" + permission.cliName();
            String deny = "--deny-" + permission.cliName();

            if (arg.equals(allow)) {
                builder.allowAll(permission);
                touched.add(permission);
                return true;
            }
            if (arg.startsWith(allow + "=")) {
                String scopes = arg.substring((allow + "=").length());
                requirePermissionScopes(arg, scopes);
                builder.allowCsv(permission, scopes);
                touched.add(permission);
                return true;
            }
            if (arg.equals(deny)) {
                builder.denyAll(permission);
                touched.add(permission);
                return true;
            }
            if (arg.startsWith(deny + "=")) {
                String scopes = arg.substring((deny + "=").length());
                requirePermissionScopes(arg, scopes);
                builder.denyCsv(permission, scopes);
                touched.add(permission);
                return true;
            }
        }
        return false;
    }

    private static void requirePermissionScopes(String option, String scopes) {
        if (scopes.isBlank()) {
            throw new IllegalArgumentException(option.substring(0, option.indexOf('='))
                    + " requires a non-empty comma-separated scope or the bare flag for all");
        }
    }

    private static void printUsage() {
        System.err.println(
                "usage: oreslang-compiler [--check|--build-analysis] "
                        + "[--permission-check=runtime|compile] [--allow-all] "
                        + "[--allow-read[=PATH,...]] [--deny-read[=PATH,...]] "
                        + "[--allow-write[=PATH,...]] [--deny-write[=PATH,...]] "
                        + "[--allow-net[=HOST[:PORT],...]] [--deny-net[=HOST[:PORT],...]] "
                        + "[--allow-env[=NAME,...]] [--deny-env[=NAME,...]] "
                        + "[--allow-run[=CMD,...]] [--deny-run[=CMD,...]] "
                        + "[--allow-sys[=API,...]] [--deny-sys[=API,...]] "
                        + "[--allow-ffi[=PATH,...]] [--deny-ffi[=PATH,...]] "
                        + "[--allow-import[=PATH,...]] [--deny-import[=PATH,...]] "
                        + "[--no-prompt] [--define=name=value ...] [--entry=symbol ...] "
                        + "[--strict-isolate] [--mode=aot|jit|hybrid] "
                        + "[--platform=server|windows|macos|linux|android|ios] "
                        + "[--allow=CAP,...] [--allow-host-class=java.util.ArrayList ...] "
                        + "[file.ores|file.java]");
    }

    static List<String> formatSyntaxWarnings(Path path, String source) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return Parser.parseWithWarnings(source).warnings().stream()
                    .map(warning -> normalized
                            + ":" + warning.line()
                            + ":" + warning.column()
                            + ": warning: " + warning.message())
                    .toList();
        } catch (IllegalArgumentException ignored) {
            // The normal compile/check path will report the authoritative parse
            // error. Do not replace it with a secondary warning-scan failure.
            return List.of();
        }
    }

    static String formatCheckDiagnostic(Path path, Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();

        Matcher matcher = POSITIONED_DIAGNOSTIC.matcher(message);
        if (matcher.matches()) {
            return path.toAbsolutePath().normalize()
                    + ":" + matcher.group(1)
                    + ":" + matcher.group(2)
                    + ": error: " + matcher.group(3);
        }

        return path.toAbsolutePath().normalize() + ":1:1: error: " + message;
    }
}
