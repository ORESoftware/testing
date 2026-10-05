package dev.oreslang.interop;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compiles trusted Java source islands and generated Java-to-Ores facades in JIT/source mode. */
public final class MixedJavaCompiler {
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "record", "sealed", "permits", "yield", "var");

    private MixedJavaCompiler() { }

    public static Compilation compile(List<MixedSourceUnit> units, Map<String, Ast.Program> programs) throws IOException {
        List<MixedSourceUnit> javaUnits = units.stream().filter(MixedSourceUnit::hasJavaSource).toList();
        if (javaUnits.isEmpty()) return Compilation.empty();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("mixed Java/Ores source requires a JDK with javax.tools.JavaCompiler available");
        }

        Path output = Files.createTempDirectory("oreslang-mixed-java-");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> sources = new ArrayList<>();
        LinkedHashSet<String> hostClasses = new LinkedHashSet<>();
        LinkedHashMap<String, String> mainClasses = new LinkedHashMap<>();
        Map<String, String> facadePackageOwners = new HashMap<>();

        for (MixedSourceUnit unit : javaUnits) {
            for (MixedSourceUnit.JavaSource source : unit.javaSources()) {
                sources.add(new StringJavaSource(source.className(), source.sourceText()));
            }
            for (MixedSourceUnit.JavaBinding binding : unit.javaBindings()) hostClasses.add(binding.binaryName());
            if (unit.primaryLanguage() == MixedSourceUnit.PrimaryLanguage.JAVA) {
                mainClasses.put(unit.unitId(), unit.primaryJavaClassName());
            }
            if (unit.hasOresSource()) {
                String packageName = unit.javaPackage();
                String owner = facadePackageOwners.putIfAbsent(packageName, unit.unitId());
                if (owner != null && !owner.equals(unit.unitId())) {
                    throw new IllegalArgumentException("multiple mixed source units in Java package '" + packageName
                            + "' would generate the same Ores bridge class; split them into distinct Java packages");
                }
                Ast.Program program = programs.get(unit.unitId());
                if (program == null) throw new IllegalStateException("missing parsed Oreslang program for " + unit.unitId());
                String facadeClass = packageName.isBlank() ? "Ores" : packageName + ".Ores";
                sources.add(new StringJavaSource(facadeClass, generateFacade(unit, program)));
            }
        }

        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>();
            options.add("--release"); options.add("21");
            options.add("-proc:none");
            options.add("-d"); options.add(output.toString());
            String classPath = System.getProperty("java.class.path");
            if (classPath != null && !classPath.isBlank()) {
                options.add("-classpath"); options.add(classPath);
            }
            Boolean ok = compiler.getTask(null, manager, diagnostics, options, null, sources).call();
            if (!Boolean.TRUE.equals(ok)) throw new IllegalArgumentException(formatDiagnostics(diagnostics));
        } catch (RuntimeException | IOException failure) {
            deleteTree(output);
            throw failure;
        }

        URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, MixedJavaCompiler.class.getClassLoader());
        try {
            for (String className : hostClasses) Class.forName(className, false, loader);
        } catch (Throwable failure) {
            try { loader.close(); } catch (IOException ignored) { }
            deleteTree(output);
            throw new IllegalStateException("compiled mixed Java class could not be loaded", failure);
        }
        return new Compilation(output, loader, Set.copyOf(hostClasses), Map.copyOf(mainClasses));
    }

    private static String generateFacade(MixedSourceUnit unit, Ast.Program program) {
        StringBuilder out = new StringBuilder();
        if (!unit.javaPackage().isBlank()) out.append("package ").append(unit.javaPackage()).append(";\n\n");
        out.append("public final class Ores {\n")
                .append("  private Ores() {}\n")
                .append("  public static Object call(String name, Object... args) {\n")
                .append("    return dev.oreslang.interop.MixedInteropBridge.invoke(\"")
                .append(javaString(unit.unitId())).append("\", name, args);\n")
                .append("  }\n\n");

        Map<String, String> importedJavaTypes = importedJavaTypes(program);
        Map<String, List<Ast.FunctionDecl>> publicByName = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC) {
                    publicByName.computeIfAbsent(fn.name(), ignored -> new ArrayList<>()).add(fn);
                }
            }
        }

        for (Map.Entry<String, List<Ast.FunctionDecl>> entry : publicByName.entrySet()) {
            if (entry.getValue().size() != 1) continue;
            Ast.FunctionDecl fn = entry.getValue().getFirst();
            if (!javaIdentifier(fn.name()) || fn.name().equals("call")) continue;
            String returnType = javaReturnType(fn.returnType(), importedJavaTypes);
            out.append("  public static ").append(returnType).append(' ').append(fn.name()).append('(');
            for (int i = 0; i < fn.parameters().size(); i++) {
                if (i > 0) out.append(", ");
                out.append("Object p").append(i);
            }
            out.append(") {\n    ");
            String call = "call(\"" + javaString(fn.name()) + "\"";
            for (int i = 0; i < fn.parameters().size(); i++) call += ", p" + i;
            call += ")";
            appendReturn(out, returnType, call);
            out.append("\n  }\n\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private static void appendReturn(StringBuilder out, String returnType, String call) {
        switch (returnType) {
            case "void" -> out.append(call).append(';');
            case "int" -> out.append("return ((Number) ").append(call).append(").intValue();");
            case "long" -> out.append("return ((Number) ").append(call).append(").longValue();");
            case "double" -> out.append("return ((Number) ").append(call).append(").doubleValue();");
            case "boolean" -> out.append("return (Boolean) ").append(call).append(';');
            case "String" -> out.append("return (String) ").append(call).append(';');
            case "Object" -> out.append("return ").append(call).append(';');
            default -> out.append("return (").append(returnType).append(") ").append(call).append(';');
        }
    }

    private static Map<String, String> importedJavaTypes(Ast.Program program) {
        Map<String, String> result = new HashMap<>();
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.kind() != Ast.ImportKind.CLASS || !ImportRules.isJavaPath(imported.path()) || imported.wildcard()) continue;
            String sourceName = imported.names().getFirst();
            result.put(ImportRules.localName(imported, sourceName), ImportRules.javaClassName(imported.path()));
        }
        return result;
    }

    private static String javaReturnType(Ast.TypeRef type, Map<String, String> importedJavaTypes) {
        if (type == null) return "Object";
        return switch (type.name()) {
            case "void" -> "void";
            case "int" -> "int";
            case "uint" -> "long";
            case "float", "decimal" -> "double";
            case "bool" -> "boolean";
            case "String" -> "String";
            default -> importedJavaTypes.getOrDefault(type.name(), "Object");
        };
    }

    private static boolean javaIdentifier(String value) {
        if (value == null || value.isBlank() || JAVA_KEYWORDS.contains(value)) return false;
        if (!Character.isJavaIdentifierStart(value.charAt(0))) return false;
        for (int i = 1; i < value.length(); i++) if (!Character.isJavaIdentifierPart(value.charAt(i))) return false;
        return true;
    }

    private static String javaString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder out = new StringBuilder("mixed Java source compilation failed");
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            out.append("\n");
            if (diagnostic.getSource() != null) out.append(diagnostic.getSource().getName()).append(':');
            if (diagnostic.getLineNumber() >= 0) out.append(diagnostic.getLineNumber()).append(':').append(diagnostic.getColumnNumber()).append(':');
            out.append(' ').append(diagnostic.getKind().name().toLowerCase(Locale.ROOT)).append(": ")
                    .append(diagnostic.getMessage(Locale.ROOT));
        }
        return out.toString();
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static final class StringJavaSource extends SimpleJavaFileObject {
        private final String source;
        private StringJavaSource(String binaryName, String source) {
            super(URI.create("string:///" + binaryName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
    }

    public static final class Compilation implements AutoCloseable {
        private final Path outputDirectory;
        private final URLClassLoader classLoader;
        private final Set<String> hostClasses;
        private final Map<String, String> mainClasses;

        private Compilation(Path outputDirectory, URLClassLoader classLoader, Set<String> hostClasses, Map<String, String> mainClasses) {
            this.outputDirectory = outputDirectory;
            this.classLoader = classLoader;
            this.hostClasses = hostClasses;
            this.mainClasses = mainClasses;
        }

        private static Compilation empty() { return new Compilation(null, null, Set.of(), Map.of()); }
        public ClassLoader classLoader() { return classLoader == null ? MixedJavaCompiler.class.getClassLoader() : classLoader; }
        public Set<String> hostClasses() { return hostClasses; }
        public String mainClass(String unitId) { return mainClasses.get(unitId); }

        @Override public void close() {
            if (classLoader != null) {
                try { classLoader.close(); } catch (IOException ignored) { }
            }
            deleteTree(outputDirectory);
        }
    }
}
