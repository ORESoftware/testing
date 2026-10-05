package dev.oreslang.compiler;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * File-granular incremental compiler.
 *
 * Each source file is an independently versioned compilation/code unit.
 * Source edits always rebuild their own unit. Importers rebuild only when the
 * dependency's exported ABI digest changes, so implementation-only edits stay
 * local while public contract changes invalidate the necessary dependents.
 */
public final class IncrementalCompiler {
    private final Map<String, CompiledUnit> cache = new LinkedHashMap<>();

    public synchronized BuildResult compile(Map<String, String> sources) {
        return compile(sources, Map.of());
    }

    public synchronized BuildResult compile(
            Map<String, String> sources,
            Map<String, Map<String, String>> importResolutions) {
        if (importResolutions == null) throw new IllegalArgumentException("import resolutions cannot be null");
        if (sources.isEmpty()) return new BuildResult(Map.of(), Set.of(), Set.of(), List.of());

        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String id = normalizeUnitId(entry.getKey());
            if (normalized.putIfAbsent(id, entry.getValue()) != null) {
                throw new IllegalArgumentException("duplicate source unit '" + id + "'");
            }
        }

        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, String> abiHashes = new LinkedHashMap<>();
        Map<String, Ast.Program> parsed = new LinkedHashMap<>();

        // Parse the complete source set before resolving imports. This is the
        // first half of cycle tolerance: A may name B while B names A because
        // neither unit is recursively compiled while discovering the other.
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            hashes.put(entry.getKey(), digest(entry.getValue()));
            Ast.Program program = AnnotationExpander.expand(Parser.parse(entry.getValue()));
            parsed.put(entry.getKey(), program);
            abiHashes.put(entry.getKey(), abiDigest(program));
        }

        Map<String, Map<String, String>> normalizedImportResolutions =
                normalizeImportResolutions(importResolutions);
        ImportGraph.validateLinkedImports(parsed, normalizedImportResolutions);
        Map<String, Set<String>> dependencies =
                ImportGraph.resolveDependencies(parsed, normalized.keySet(), normalizedImportResolutions);
        List<List<String>> initializationGroups = ImportGraph.initializationGroups(dependencies);

        LinkedHashSet<String> dirty = new LinkedHashSet<>();
        LinkedHashSet<String> abiChanged = new LinkedHashSet<>();
        for (String id : normalized.keySet()) {
            CompiledUnit previous = cache.get(id);
            boolean sourceChanged = previous == null || !previous.sourceDigest().equals(hashes.get(id));
            boolean depsChanged = previous == null || !previous.dependencies().equals(dependencies.get(id));
            if (sourceChanged || depsChanged) dirty.add(id);
            if (previous == null || !previous.abiDigest().equals(abiHashes.get(id))) abiChanged.add(id);
        }

        /*
         * ABI changes invalidate the full reverse-import closure. This remains
         * conservative until the cross-unit linker tracks exactly which public
         * imported symbols are re-exported by each dependent. Crucially,
         * implementation-only source changes do not enter this queue.
         */
        Map<String, Set<String>> reverse = reverseDependencies(dependencies);
        ArrayDeque<String> abiQueue = new ArrayDeque<>(abiChanged);
        LinkedHashSet<String> abiAffected = new LinkedHashSet<>(abiChanged);
        while (!abiQueue.isEmpty()) {
            String changed = abiQueue.removeFirst();
            for (String dependent : reverse.getOrDefault(changed, Set.of())) {
                dirty.add(dependent);
                if (abiAffected.add(dependent)) abiQueue.addLast(dependent);
            }
        }

        LinkedHashMap<String, CompiledUnit> next = new LinkedHashMap<>();
        LinkedHashSet<String> rebuilt = new LinkedHashSet<>();
        LinkedHashSet<String> reused = new LinkedHashSet<>();

        for (String id : normalized.keySet()) {
            if (!dirty.contains(id) && cache.containsKey(id)) {
                next.put(id, cache.get(id));
                reused.add(id);
                continue;
            }

            Ast.Program checked = OresCompiler.parseAndTypeCheck(normalized.get(id));
            CompiledUnit unit = new CompiledUnit(
                    id,
                    packageId(id, checked),
                    checked.namespace(),
                    hashes.get(id),
                    abiHashes.get(id),
                    dependencies.get(id),
                    normalized.get(id),
                    checked);
            next.put(id, unit);
            rebuilt.add(id);
        }

        cache.keySet().retainAll(normalized.keySet());
        cache.putAll(next);
        return new BuildResult(
                Map.copyOf(next),
                Set.copyOf(rebuilt),
                Set.copyOf(reused),
                initializationGroups);
    }

    public synchronized void clear() {
        cache.clear();
    }

    private static String abiDigest(Ast.Program program) {
        StringBuilder abi = new StringBuilder("ores-abi-v1\n");
        abi.append("namespace=").append(program.namespace() == null ? "" : program.namespace()).append('\n');

        for (Ast.ModuleDecl module : program.modules()) {
            abi.append("module ").append(module.name()).append('\n');
            for (Ast.Annotation annotation : module.annotations()) {
                if (annotation.name().equals("AdheresTo")) {
                    abi.append(" module-annotation AdheresTo:");
                    for (Ast.TypeRef arg : annotation.arguments()) abi.append(typeRef(arg)).append(',');
                    abi.append('\n');
                }
            }
            for (Ast.Decl decl : module.declarations()) appendAbi(abi, decl);
        }
        return digest(abi.toString());
    }

    private static void appendAbi(StringBuilder abi, Ast.Decl decl) {
        if (decl instanceof Ast.FunctionDecl fn) {
            if (fn.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append(fn.actorKind()).append(' ').append(fn.kind()).append(" pub ").append(fn.name());
            appendGenerics(abi, fn.genericParameters());
            appendParams(abi, fn.parameters());
            abi.append("=>").append(typeRef(fn.returnType())).append('\n');
            return;
        }
        if (decl instanceof Ast.ClassDecl klass) {
            abi.append(klass.actorKind()).append(" class ").append(klass.name());
            appendGenerics(abi, klass.genericParameters());
            abi.append(" extends ");
            for (Ast.TypeRef parent : klass.parents()) abi.append(typeRef(parent)).append(',');
            abi.append(" implements ");
            for (Ast.TypeRef iface : klass.interfaces()) abi.append(typeRef(iface)).append(',');
            abi.append('\n');
            for (Ast.FieldDecl field : klass.fields()) {
                String fromJsonKey = AnnotationExpander.fromJsonKey(field);
                if (fromJsonKey != null) {
                    abi.append(" from-json ")
                            .append(field.name()).append('=')
                            .append(fromJsonKey.length()).append(':').append(fromJsonKey)
                            .append(':').append(field.type() == null ? "<inferred>" : typeRef(field.type())).append('\n');
                }
                if (field.visibility() != Ast.Visibility.PUBLIC) continue;
                abi.append(" field ").append(field.bindingKind()).append(' ')
                        .append(field.type() == null ? "<inferred:" + field.initializer() + ">" : typeRef(field.type()))
                        .append(' ').append(field.name()).append('\n');
            }
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.visibility() != Ast.Visibility.PUBLIC) continue;
                abi.append(method.isStatic() ? " static-fnc " : " method ")
                        .append(method.name());
                appendGenerics(abi, method.genericParameters());
                appendParams(abi, method.parameters());
                abi.append("=>").append(typeRef(method.returnType())).append('\n');
            }
            return;
        }
        if (decl instanceof Ast.InterfaceDecl iface) {
            abi.append("interface ").append(iface.visibility()).append(' ').append(iface.name());
            appendGenerics(abi, iface.genericParameters());
            abi.append(" extends ");
            for (Ast.TypeRef parent : iface.parents()) abi.append(typeRef(parent)).append(',');
            abi.append('\n');
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    abi.append(" iface-fnc ").append(fn.name());
                    appendGenerics(abi, fn.genericParameters());
                    appendParams(abi, fn.parameters());
                    abi.append("=>").append(typeRef(fn.returnType())).append('\n');
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    abi.append(" iface-field ").append(field.name()).append(':').append(typeRef(field.type())).append('\n');
                }
            }
            return;
        }
        if (decl instanceof Ast.TypeAliasDecl alias) {
            abi.append("type ").append(alias.name());
            appendGenerics(abi, alias.genericParameters());
            abi.append('=').append(typeRef(alias.target())).append('\n');
            return;
        }
        if (decl instanceof Ast.FieldDecl field && field.visibility() == Ast.Visibility.PUBLIC) {
            abi.append("binding ").append(field.bindingKind()).append(' ')
                    .append(field.type() == null ? "<inferred:" + field.initializer() + ">" : typeRef(field.type()))
                    .append(' ').append(field.name()).append('\n');
        }
    }

    private static void appendGenerics(StringBuilder abi, List<String> generics) {
        abi.append('<');
        for (String generic : generics) abi.append(generic).append(',');
        abi.append('>');
    }

    private static void appendParams(StringBuilder abi, List<Ast.Param> params) {
        abi.append('(');
        for (Ast.Param param : params) {
            if (param.structural()) abi.append("structural ");
            abi.append(typeRef(param.type())).append(',');
        }
        abi.append(')');
    }

    private static String typeRef(Ast.TypeRef ref) {
        if (ref == null) return "<inferred>";
        if (ref.isStringLiteral()) return "'" + ref.stringLiteralValue() + "'";
        StringBuilder out = new StringBuilder(ref.name());
        if (ref.inferArguments()) return out.append("<>").toString();
        if (!ref.arguments().isEmpty()) {
            out.append('<');
            for (Ast.TypeRef arg : ref.arguments()) out.append(typeRef(arg)).append(',');
            out.append('>');
        }
        return out.toString();
    }

    private static Set<String> resolveDependencies(String unitId, Ast.Program program, Set<String> available) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Path parent = Path.of(unitId).getParent();
        for (Ast.ImportDecl imported : program.imports()) {
            String raw = imported.path().replace('\\', '/');
            Path candidatePath = raw.startsWith(".")
                    ? (parent == null ? Path.of(raw) : parent.resolve(raw)).normalize()
                    : Path.of(raw).normalize();
            String candidate = normalizeUnitId(candidatePath.toString());
            if (!available.contains(candidate) && !candidate.endsWith(".ores") && available.contains(candidate + ".ores")) {
                candidate += ".ores";
            }
            if (available.contains(candidate)) {
                result.add(candidate);
            } else if (raw.startsWith(".")) {
                throw new IllegalArgumentException("relative import '" + imported.path() + "' from '" + unitId
                        + "' does not resolve to a supplied Oreslang source unit");
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, Map<String, String>> normalizeImportResolutions(
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, Map<String, String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> importer : importResolutions.entrySet()) {
            String importerId = normalizeUnitId(importer.getKey());
            if (importer.getValue() == null) {
                throw new IllegalArgumentException("import resolution map cannot be null for '" + importerId + "'");
            }
            LinkedHashMap<String, String> imports = new LinkedHashMap<>();
            for (Map.Entry<String, String> resolution : importer.getValue().entrySet()) {
                if (resolution.getKey() == null || resolution.getKey().isBlank()) {
                    throw new IllegalArgumentException("resolved import path cannot be blank");
                }
                String targetId = normalizeUnitId(resolution.getValue());
                String previous = imports.putIfAbsent(resolution.getKey(), targetId);
                if (previous != null && !previous.equals(targetId)) {
                    throw new IllegalArgumentException(
                            "conflicting resolved import '" + resolution.getKey() + "' for '" + importerId + "'");
                }
            }
            normalized.put(importerId, Map.copyOf(imports));
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Set<String>> reverseDependencies(Map<String, Set<String>> dependencies) {
        LinkedHashMap<String, Set<String>> reverse = new LinkedHashMap<>();
        for (String id : dependencies.keySet()) reverse.put(id, new LinkedHashSet<>());
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            for (String dependency : entry.getValue()) {
                reverse.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        LinkedHashMap<String, Set<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : reverse.entrySet()) frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
        return Map.copyOf(frozen);
    }

    private static String packageId(String unitId, Ast.Program program) {
        if (program.namespace() != null) return program.namespace();
        String id = unitId;
        if (id.endsWith(".ores")) id = id.substring(0, id.length() - ".ores".length());
        return id;
    }

    private static String normalizeUnitId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("source unit id cannot be blank");
        return Path.of(id).normalize().toString().replace('\\', '/');
    }

    private static String digest(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record CompiledUnit(
            String unitId,
            String packageId,
            String namespace,
            String sourceDigest,
            String abiDigest,
            Set<String> dependencies,
            String sourceText,
            Ast.Program program) {
        public CompiledUnit {
            dependencies = Set.copyOf(dependencies);
        }
    }

    public record BuildResult(
            Map<String, CompiledUnit> units,
            Set<String> rebuiltUnits,
            Set<String> reusedUnits,
            List<List<String>> initializationGroups) {
        public BuildResult {
            units = Map.copyOf(units);
            rebuiltUnits = Set.copyOf(rebuiltUnits);
            reusedUnits = Set.copyOf(reusedUnits);
            initializationGroups = initializationGroups.stream()
                    .map(List::copyOf)
                    .toList();
        }

        /** Backward-compatible constructor for callers that do not need lifecycle planning. */
        public BuildResult(
                Map<String, CompiledUnit> units,
                Set<String> rebuiltUnits,
                Set<String> reusedUnits) {
            this(units, rebuiltUnits, reusedUnits, List.of());
        }

        public boolean rebuilt(String unitId) { return rebuiltUnits.contains(normalizeUnitId(unitId)); }
        public boolean reused(String unitId) { return reusedUnits.contains(normalizeUnitId(unitId)); }

        /**
         * Flattens the dependency-first SCC plan. Units in the same inner list
         * form one load barrier: all of them must be linked before the first
         * init hook in that group executes.
         */
        public List<String> initializationOrder() {
            return initializationGroups.stream().flatMap(List::stream).toList();
        }
    }
}
