package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.interop.MixedInteropBridge;
import dev.oreslang.interop.MixedJavaCompiler;
import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host-side multi-file loader.
 *
 * Oreslang code-unit identity comes exclusively from the canonical Unix-style
 * filesystem path. Mixed .ores/.java files are split before parsing; no
 * source-level module declaration is synthesized.
 */
public final class LinkedProgramRunner {
    private LinkedProgramRunner() { }

    /** Parses/type-checks the reachable Ores graph and javac-checks mixed Java source. */
    public static IncrementalCompiler.BuildResult validate(Path entryFile) throws IOException {
        return validate(entryFile, System.getenv());
    }

    public static IncrementalCompiler.BuildResult validate(
            Path entryFile,
            Map<String, String> environment) throws IOException {
        Path entry = entryFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(entry)) throw new IllegalArgumentException("not a file: " + entry);

        OresProjectConfig projectConfig = OresProjectConfig.discover(entry, environment);
        LinkedHashMap<String, MixedSourceUnit> units = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, String>> importResolutions = new LinkedHashMap<>();
        collectImportClosure(entry, units, projectConfig, importResolutions);
        ensureJavaEntryContainsOres(entry, units);
        Map<String, String> sources = oresSources(units);
        IncrementalCompiler.BuildResult build =
                new IncrementalCompiler().compile(sources, importResolutions);
        Map<String, Ast.Program> programs = parsePrograms(sources);
        try (MixedJavaCompiler.Compilation ignored = MixedJavaCompiler.compile(new ArrayList<>(units.values()), programs)) {
            return build;
        }
    }

    public static IncrementalCompiler.BuildResult run(
            Path entryFile,
            IsolatePolicy policy,
            ExecutionProfile executionProfile,
            OutputStream out,
            OutputStream err) throws IOException {
        return run(entryFile, policy, executionProfile, Set.of(), System.getenv(), out, err);
    }

    public static IncrementalCompiler.BuildResult run(
            Path entryFile,
            IsolatePolicy policy,
            ExecutionProfile executionProfile,
            Set<String> allowedHostClasses,
            OutputStream out,
            OutputStream err) throws IOException {
        return run(
                entryFile,
                policy,
                executionProfile,
                allowedHostClasses,
                System.getenv(),
                out,
                err);
    }

    public static IncrementalCompiler.BuildResult run(
            Path entryFile,
            IsolatePolicy policy,
            ExecutionProfile executionProfile,
            Set<String> allowedHostClasses,
            Map<String, String> environment,
            OutputStream out,
            OutputStream err) throws IOException {
        Path entry = entryFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(entry)) throw new IllegalArgumentException("not a file: " + entry);

        OresProjectConfig projectConfig = OresProjectConfig.discover(entry, environment);
        LinkedHashMap<String, MixedSourceUnit> units = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, String>> importResolutions = new LinkedHashMap<>();
        collectImportClosure(entry, units, projectConfig, importResolutions);
        ensureJavaEntryContainsOres(entry, units);
        boolean hasJavaSource = units.values().stream().anyMatch(MixedSourceUnit::hasJavaSource);
        if (hasJavaSource) {
            policy.require(IsolatePolicy.Capability.JAVA_SOURCE_INTEROP, "mixed Java/Ores source islands");
            policy.require(IsolatePolicy.Capability.JAVA_INTEROP, "mixed Java/Ores object interop");
            if (policy.adversarial()) throw new SecurityException("mixed Java/Ores source islands are disabled for adversarial isolates");
            if (executionProfile.mode() != ExecutionProfile.Mode.JIT) {
                throw new IllegalArgumentException("java { ... } / ores { ... } source islands currently require --mode=jit; AOT/hybrid builds must precompile Java source");
            }
        }

        Map<String, String> sources = oresSources(units);
        IncrementalCompiler.BuildResult build =
                new IncrementalCompiler().compile(sources, importResolutions);
        Map<String, Ast.Program> programs = parsePrograms(sources);
        String entryId = unitId(entry);
        MixedSourceUnit entryUnit = units.get(entryId);
        if (entryUnit == null) throw new IllegalStateException("entry source unit was not collected: " + entryId);

        try (MixedJavaCompiler.Compilation javaCompilation = MixedJavaCompiler.compile(new ArrayList<>(units.values()), programs)) {
            LinkedHashSet<String> effectiveHostClasses = new LinkedHashSet<>(allowedHostClasses);
            effectiveHostClasses.addAll(javaCompilation.hostClasses());

            Thread currentThread = Thread.currentThread();
            ClassLoader previousLoader = currentThread.getContextClassLoader();
            currentThread.setContextClassLoader(javaCompilation.classLoader());
            try {
                Context.Builder builder = policy.restrictedContextBuilder(executionProfile, effectiveHostClasses);
                if (out != null) builder.out(forwardingStream(out));
                if (err != null) builder.err(forwardingStream(err));

                try (Context context = builder.build()) {
                    LinkedHashMap<String, Value> parsedUnits = new LinkedHashMap<>();
                    List<String> ids = new ArrayList<>(build.units().keySet());
                    ids.sort(String::compareTo);

                    for (String id : ids) {
                        IncrementalCompiler.CompiledUnit unit = build.units().get(id);
                        Source source = Source.newBuilder(OresLanguage.ID, unit.sourceText(), id)
                                .mimeType(OresLanguage.MIME_TYPE)
                                .buildLiteral();
                        parsedUnits.put(id, context.parse(source));
                    }

                    for (Map.Entry<String, Map<String, String>> importer : importResolutions.entrySet()) {
                        Value parsed = parsedUnits.get(importer.getKey());
                        if (parsed == null) continue;
                        for (Map.Entry<String, String> resolution : importer.getValue().entrySet()) {
                            parsed.execute(
                                    OresEvalRootNode.REGISTER_IMPORT_COMMAND,
                                    resolution.getKey(),
                                    resolution.getValue());
                        }
                    }

                    for (String id : ids) parsedUnits.get(id).execute(OresEvalRootNode.LINK_ONLY_COMMAND);
                    for (List<String> group : build.initializationGroups()) {
                        for (String id : group) parsedUnits.get(id).execute(OresEvalRootNode.INIT_ONLY_COMMAND);
                    }

                    LinkedHashMap<String, MixedInteropBridge.Invoker> bridgeInvokers = new LinkedHashMap<>();
                    for (Map.Entry<String, Value> parsed : parsedUnits.entrySet()) {
                        Value unit = parsed.getValue();
                        bridgeInvokers.put(parsed.getKey(), (function, arguments) -> {
                            Object[] invocation = new Object[2 + arguments.length];
                            invocation[0] = OresEvalRootNode.INVOKE_PUBLIC_COMMAND;
                            invocation[1] = function;
                            System.arraycopy(arguments, 0, invocation, 2, arguments.length);
                            return toHostValue(unit.execute(invocation));
                        });
                    }

                    try (MixedInteropBridge.Scope ignored = MixedInteropBridge.open(bridgeInvokers)) {
                        if (entryUnit.primaryLanguage() == MixedSourceUnit.PrimaryLanguage.JAVA) {
                            invokeJavaMain(javaCompilation.classLoader(), javaCompilation.mainClass(entryId));
                        } else {
                            Value entryPoint = parsedUnits.get(entryId);
                            if (entryPoint == null) throw new IllegalStateException("entry unit was not linked: " + entryId);
                            entryPoint.execute(OresEvalRootNode.MAIN_ONLY_COMMAND);
                        }
                    }
                }
            } finally {
                currentThread.setContextClassLoader(previousLoader);
            }
        }
        return build;
    }

    /**
     * Graal's UNTRUSTED sandbox rejects raw System.out/System.err as ambient
     * standard streams. Always present host-selected output as an explicit,
     * non-closing redirection while preserving the caller-owned destination.
     */
    private static OutputStream forwardingStream(OutputStream target) {
        return new OutputStream() {
            @Override public void write(int value) throws IOException {
                target.write(value);
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                target.write(bytes, offset, length);
            }

            @Override public void flush() throws IOException {
                target.flush();
            }

            @Override public void close() throws IOException {
                // The embedding caller owns the underlying stream (often
                // System.out/System.err); Context.close must not close it.
                target.flush();
            }
        };
    }

    private static Object toHostValue(Value value) {
        if (value == null || value.isNull()) return null;
        if (value.isHostObject()) return value.asHostObject();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isString()) return value.asString();
        if (value.fitsInInt()) return value.asInt();
        if (value.fitsInLong()) return value.asLong();
        if (value.fitsInDouble()) return value.asDouble();
        return value;
    }

    private static void invokeJavaMain(ClassLoader loader, String className) {
        if (className == null || className.isBlank()) throw new IllegalStateException("mixed Java entry has no main class");
        try {
            Class<?> type = Class.forName(className, true, loader);
            Method main = type.getMethod("main", String[].class);
            if (!Modifier.isStatic(main.getModifiers()) || main.getReturnType() != void.class) {
                throw new IllegalArgumentException("Java entry main must be public static void main(String[]): " + className);
            }
            main.invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Java mixed-source main failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("cannot invoke Java mixed-source main on " + className, failure);
        }
    }

    private static void ensureJavaEntryContainsOres(Path entry, Map<String, MixedSourceUnit> units) {
        MixedSourceUnit unit = units.get(unitId(entry));
        if (unit != null && unit.primaryLanguage() == MixedSourceUnit.PrimaryLanguage.JAVA && unit.foreignIslands().isEmpty()) {
            throw new IllegalArgumentException("a .java Oreslang entry must contain at least one ores { ... } island");
        }
    }

    private static Map<String, Ast.Program> parsePrograms(Map<String, String> sources) {
        LinkedHashMap<String, Ast.Program> programs = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) programs.put(source.getKey(), Parser.parse(source.getValue()));
        return programs;
    }

    private static Map<String, String> oresSources(Map<String, MixedSourceUnit> units) {
        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        for (MixedSourceUnit unit : units.values()) if (unit.hasOresSource()) sources.put(unit.unitId(), unit.oresSource());
        return sources;
    }

    private static void collectImportClosure(
            Path unit,
            Map<String, MixedSourceUnit> units,
            OresProjectConfig projectConfig,
            Map<String, Map<String, String>> importResolutions) throws IOException {
        Path normalized = unit.toAbsolutePath().normalize();
        String id = unitId(normalized);
        if (units.containsKey(id)) return;

        MixedSourceUnit mixed = MixedSourceUnit.parse(normalized, Files.readString(normalized));
        units.put(id, mixed);
        if (!mixed.hasOresSource()) return;

        Ast.Program program = Parser.parse(mixed.oresSource());
        for (Ast.ImportDecl imported : program.imports()) {
            if (ImportRules.isJavaPath(imported.path())) continue;

            String raw = imported.path().replace('\\', '/');
            java.util.Optional<Path> target = projectConfig.resolveImport(normalized, imported.path());
            if (target.isEmpty()) {
                if (raw.startsWith(".") || Path.of(raw).isAbsolute()) {
                    throw new IllegalArgumentException(
                            "filesystem import '" + imported.path() + "' from '" + id + "' does not resolve to a file");
                }
                // Preserve the existing package-resolver boundary for bare
                // imports that are not present in project/ORESLANG_PATH roots.
                continue;
            }

            String targetId = unitId(target.get());
            recordImportResolution(importResolutions, id, imported.path(), targetId);
            collectImportClosure(target.get(), units, projectConfig, importResolutions);
        }
    }

    private static void recordImportResolution(
            Map<String, Map<String, String>> importResolutions,
            String importerId,
            String importPath,
            String targetId) {
        Map<String, String> importer = importResolutions.computeIfAbsent(
                importerId,
                ignored -> new LinkedHashMap<>());
        String previous = importer.putIfAbsent(importPath, targetId);
        if (previous != null && !previous.equals(targetId)) {
            throw new IllegalArgumentException(
                    "import '" + importPath + "' from '" + importerId
                            + "' resolved to both '" + previous + "' and '" + targetId + "'");
        }
    }

    private static String unitId(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
