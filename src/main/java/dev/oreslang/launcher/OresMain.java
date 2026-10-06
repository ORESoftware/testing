package dev.oreslang.launcher;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
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
        List<IsolatePolicy.Capability> additionalCapabilities = new ArrayList<>();
        Set<String> allowedHostClasses = new LinkedHashSet<>();
        String filename = null;

        for (String arg : args) {
            if (arg.equals("--strict-isolate")) strict = true;
            else if (arg.equals("--check")) checkOnly = true;
            else if (arg.equals("--build-analysis")) buildAnalysis = true;
            else if (arg.startsWith("--define=")) {
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
                        additionalCapabilities.add(IsolatePolicy.Capability.valueOf(value.trim().toUpperCase(Locale.ROOT)));
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
                System.err.println("usage: oreslang-compiler [--check|--build-analysis] [--define=name=value ...] [--entry=symbol ...] [--strict-isolate] [--mode=aot|jit|hybrid] [--platform=server|windows|macos|linux|android|ios] [--allow=CAP,...] [--allow-host-class=java.util.ArrayList ...] [file.ores|file.java]");
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

        if (buildAnalysis) {
            if (!filename.endsWith(".ores")) {
                throw new IllegalArgumentException("--build-analysis currently requires a .ores source file");
            }
            Map<String, String> defines = BuildOptions.mergeDefines(System.getenv(), buildDefines);
            Set<String> entries = buildEntryPoints.isEmpty() ? Set.of("main") : Set.copyOf(buildEntryPoints);
            TreeShaker.Result result = OresCompiler.compileForBuild(
                    Files.readString(path),
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
                LinkedProgramRunner.validate(path);
            } catch (Exception error) {
                System.err.println(formatCheckDiagnostic(path, error));
                System.exit(1);
            }
            return;
        }

        ExecutionProfile profile = ExecutionProfile.parse(mode, platform);
        IsolatePolicy policy = strict ? IsolatePolicy.strictFaas() : IsolatePolicy.developer();
        if (!additionalCapabilities.isEmpty()) {
            policy = policy.withCapabilities(additionalCapabilities.toArray(IsolatePolicy.Capability[]::new));
        }

        LinkedProgramRunner.run(path, policy, profile, allowedHostClasses, System.out, System.err);
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
