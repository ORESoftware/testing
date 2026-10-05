package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-side multi-file loader.
 *
 * Guest import statements never read the filesystem. The host discovers the
 * reachable relative-import closure, compiles the whole graph, links every
 * code unit into one Truffle context, then starts the explicitly selected
 * program entry. Linking is inert: function names such as init have no magic
 * lifecycle behavior.
 */
public final class LinkedProgramRunner {
    private LinkedProgramRunner() { }

    public static IncrementalCompiler.BuildResult run(
            Path entryFile,
            IsolatePolicy policy,
            ExecutionProfile executionProfile,
            OutputStream out,
            OutputStream err) throws IOException {
        Path entry = entryFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(entry)) throw new IllegalArgumentException("not a file: " + entry);

        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        collectRelativeImportClosure(entry, sources);

        IncrementalCompiler compiler = new IncrementalCompiler();
        IncrementalCompiler.BuildResult build = compiler.compile(sources);
        String entryId = unitId(entry);

        // The official launcher owns the context-entry thread. Schedule the
        // entire Graal lifecycle on the process SHARED/root carrier before the
        // context is built/entered; RootNode must never hop threads after entry.
        ActorRuntime.executeProcessRoot(policy, () -> {
            Context.Builder builder = policy.restrictedContextBuilder(executionProfile);
            // Graal's UNTRUSTED sandbox rejects the JVM's standard streams even
            // when they were explicitly supplied to Builder.out/err. Wrap host
            // streams in a non-closing forwarding stream so the sandbox sees a
            // redirected sink while the CLI can still surface guest output.
            if (out != null) builder.out(sandboxSafeOutput(out));
            if (err != null) builder.err(sandboxSafeOutput(err));

            try (Context context = builder.build()) {
                LinkedHashMap<String, Value> parsedUnits = new LinkedHashMap<>();
                List<String> ids = new ArrayList<>(build.units().keySet());
                ids.sort(String::compareTo);

                // Parse every unit before linking. Parsing/linking are inert.
                for (String id : ids) {
                    IncrementalCompiler.CompiledUnit unit = build.units().get(id);
                    Source source = Source.newBuilder(OresLanguage.ID, unit.sourceText(), id)
                            .mimeType(OresLanguage.MIME_TYPE)
                            .buildLiteral();
                    parsedUnits.put(id, context.parse(source));
                }

                // Link every evaluator into the shared context. Cycles terminate
                // because this is a flat installation pass, never recursive import
                // execution.
                for (String id : ids) {
                    parsedUnits.get(id).execute(OresEvalRootNode.LINK_ONLY_COMMAND);
                }

                // initializationGroups is historical naming for the dependency-first
                // SCC/link plan. Every member is already linked above; no implicit
                // lifecycle function is executed. Startup/hot-load behavior must be
                // selected explicitly (main or export entry).
                Value entryPoint = parsedUnits.get(entryId);
                if (entryPoint == null) {
                    throw new IllegalStateException("entry unit was not linked: " + entryId);
                }
                entryPoint.execute(OresEvalRootNode.MAIN_ONLY_COMMAND);
            }
            return null;
        });

        return build;
    }

    static OutputStream sandboxSafeOutput(OutputStream target) {
        if (target == null) throw new NullPointerException("target");
        return new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                target.write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                target.write(bytes, offset, length);
            }

            @Override
            public void flush() throws IOException {
                target.flush();
            }

            @Override
            public void close() throws IOException {
                // Context ownership must never close System.out/System.err or a
                // caller-owned embedding stream. Flush is sufficient.
                target.flush();
            }
        };
    }

    private static void collectRelativeImportClosure(
            Path unit,
            Map<String, String> sources) throws IOException {
        Path normalized = unit.toAbsolutePath().normalize();
        String id = unitId(normalized);
        if (sources.containsKey(id)) return;

        String source = Files.readString(normalized);
        sources.put(id, source);

        Ast.Program program = Parser.parse(source);
        for (Ast.ImportDecl imported : program.imports()) {
            String raw = imported.path().replace('\\', '/');
            if (!raw.startsWith(".")) continue;

            Path target = normalized.getParent().resolve(raw).normalize();
            if (!Files.isRegularFile(target) && !target.toString().endsWith(".ores")) {
                Path withExtension = Path.of(target.toString() + ".ores");
                if (Files.isRegularFile(withExtension)) target = withExtension;
            }
            if (!Files.isRegularFile(target)) {
                throw new IllegalArgumentException(
                        "relative import '" + imported.path() + "' from '" + id + "' does not resolve to a file");
            }
            collectRelativeImportClosure(target, sources);
        }
    }

    private static String unitId(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
