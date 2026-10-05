package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-file import graph/link validation.
 *
 * Import cycles are legal. Strongly connected components are initialization
 * barriers: every unit in the component must be loaded/linked before any init
 * hook in that component may execute.
 */
final class ImportGraph {
    private ImportGraph() { }

    static String resolveImportUnitId(String unitId, Ast.ImportDecl imported, Set<String> available) {
        return resolveImportUnitId(unitId, imported, available, Map.of());
    }

    static String resolveImportUnitId(
            String unitId,
            Ast.ImportDecl imported,
            Set<String> available,
            Map<String, Map<String, String>> importResolutions) {
        ImportRules.validate(imported);
        if (ImportRules.isJavaPath(imported.path())) return null;

        String normalizedUnitId = normalizeUnitId(unitId);
        String resolved = importResolutions
                .getOrDefault(normalizedUnitId, Map.of())
                .get(imported.path());
        if (resolved != null) {
            String normalizedResolved = normalizeUnitId(resolved);
            if (!available.contains(normalizedResolved)) {
                throw new IllegalArgumentException(
                        "resolved import '" + imported.path() + "' from '" + unitId
                                + "' points to source unit that was not supplied: '" + normalizedResolved + "'");
            }
            return normalizedResolved;
        }

        String raw = imported.path().replace('\\', '/');
        Path parent = Path.of(unitId).getParent();
        Path candidatePath = raw.startsWith(".")
                ? (parent == null ? Path.of(raw) : parent.resolve(raw)).normalize()
                : Path.of(raw).normalize();
        String candidate = normalizeUnitId(candidatePath.toString());
        if (!available.contains(candidate) && !candidate.endsWith(".ores") && !candidate.endsWith(".java")) {
            if (available.contains(candidate + ".ores")) candidate += ".ores";
            else if (available.contains(candidate + ".java")) candidate += ".java";
        }
        if (available.contains(candidate)) return candidate;
        if (raw.startsWith(".")) {
            throw new IllegalArgumentException("relative import '" + imported.path() + "' from '" + unitId
                    + "' does not resolve to a supplied Oreslang/mixed source unit");
        }
        return null;
    }

    static Map<String, Set<String>> resolveDependencies(
            Map<String, Ast.Program> programs,
            Set<String> available) {
        return resolveDependencies(programs, available, Map.of());
    }

    static Map<String, Set<String>> resolveDependencies(
            Map<String, Ast.Program> programs,
            Set<String> available,
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (Map.Entry<String, Ast.Program> entry : programs.entrySet()) {
            LinkedHashSet<String> resolved = new LinkedHashSet<>();
            for (Ast.ImportDecl imported : entry.getValue().imports()) {
                String target = resolveImportUnitId(entry.getKey(), imported, available, importResolutions);
                if (target != null) resolved.add(target);
            }
            dependencies.put(entry.getKey(), Set.copyOf(resolved));
        }
        return Map.copyOf(dependencies);
    }

    static void validateLinkedImports(Map<String, Ast.Program> programs) {
        validateLinkedImports(programs, Map.of());
    }

    static void validateLinkedImports(
            Map<String, Ast.Program> programs,
            Map<String, Map<String, String>> importResolutions) {
        Set<String> available = programs.keySet();
        for (Map.Entry<String, Ast.Program> entry : programs.entrySet()) {
            String importer = entry.getKey();
            for (Ast.ImportDecl imported : entry.getValue().imports()) {
                String targetId = resolveImportUnitId(importer, imported, available, importResolutions);
                if (targetId == null) continue; // package resolver owns unresolved non-relative imports.
                Ast.Program target = programs.get(targetId);
                if (imported.wildcard()) continue;
                for (String name : imported.names()) {
                    int matches = exportedMatches(target, imported.kind(), name);
                    if (matches == 0) {
                        throw new IllegalArgumentException("import " + imported.kind().name().toLowerCase()
                                + " '" + name + "' from '" + imported.path() + "' in '" + importer
                                + "' does not match an exported declaration in '" + targetId + "'");
                    }
                    if (matches > 1) {
                        throw new IllegalArgumentException("import " + imported.kind().name().toLowerCase()
                                + " '" + name + "' from '" + imported.path() + "' in '" + importer
                                + "' is ambiguous in '" + targetId + "'");
                    }
                }
            }
        }
    }

    private static int exportedMatches(Ast.Program program, Ast.ImportKind kind, String name) {
        int matches = 0;
        for (Ast.ModuleDecl module : program.modules()) {
            if (kind == Ast.ImportKind.MODULE) {
                if (module.name().equals(name)) matches++;
                continue;
            }

            for (Ast.Decl decl : module.declarations()) {
                boolean matched = switch (kind) {
                    case FUNCTION -> decl instanceof Ast.FunctionDecl fn
                            && fn.visibility() == Ast.Visibility.PUBLIC
                            && fn.kind() == Ast.CallableKind.FNC
                            && fn.actorKind() == Ast.ActorKind.NONE
                            && fn.genericParameters().isEmpty()
                            && fn.name().equals(name);
                    case ACTOR -> decl instanceof Ast.ClassDecl klass
                            && klass.actorKind() != Ast.ActorKind.NONE
                            && klass.name().equals(name);
                    case CLASS -> decl instanceof Ast.ClassDecl klass
                            && klass.actorKind() == Ast.ActorKind.NONE
                            && klass.name().equals(name);
                    case INTERFACE -> decl instanceof Ast.InterfaceDecl iface
                            && iface.contractKind() == Ast.ContractKind.INTERFACE
                            && iface.visibility() == Ast.Visibility.PUBLIC
                            && iface.name().equals(name);
                    case TRAIT -> decl instanceof Ast.InterfaceDecl iface
                            && iface.contractKind() == Ast.ContractKind.TRAIT
                            && iface.visibility() == Ast.Visibility.PUBLIC
                            && iface.name().equals(name);
                    case TYPE -> decl instanceof Ast.TypeAliasDecl alias
                            && alias.name().equals(name);
                    case TYPES -> (decl instanceof Ast.InterfaceDecl iface
                                    && iface.visibility() == Ast.Visibility.PUBLIC
                                    && iface.name().equals(name))
                            || (decl instanceof Ast.TypeAliasDecl alias
                                    && alias.name().equals(name));
                    case STRUCT -> false;
                    case ALL -> (decl instanceof Ast.FunctionDecl fn
                                    && fn.visibility() == Ast.Visibility.PUBLIC
                                    && fn.name().equals(name))
                            || (decl instanceof Ast.ClassDecl klass
                                    && klass.name().equals(name));
                    case MODULE -> false;
                };
                if (matched) matches++;
            }
        }
        return matches;
    }

    /**
     * Resolve the public interface declarations explicitly selected by one
     * code unit. This is compiler metadata only; it does not make the imported
     * unit available as a runtime value and does not broaden filesystem access.
     */
    static Map<String, Ast.InterfaceDecl> resolveImportedInterfaces(
            String importerId,
            Map<String, Ast.Program> programs,
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, Ast.InterfaceDecl> result = new LinkedHashMap<>();
        Ast.Program importer = programs.get(normalizeUnitId(importerId));
        if (importer == null) {
            throw new IllegalArgumentException("unknown importer source unit '" + importerId + "'");
        }

        for (Ast.ImportDecl imported : importer.imports()) {
            if (imported.wildcard()
                    || (imported.kind() != Ast.ImportKind.INTERFACE
                        && imported.kind() != Ast.ImportKind.TRAIT
                        && imported.kind() != Ast.ImportKind.TYPES)) {
                continue;
            }

            String targetId = resolveImportUnitId(
                    importerId, imported, programs.keySet(), importResolutions);
            if (targetId == null) continue;
            Ast.Program target = programs.get(targetId);
            if (target == null) continue;

            for (String sourceName : imported.names()) {
                Ast.ContractKind requiredKind = switch (imported.kind()) {
                    case INTERFACE -> Ast.ContractKind.INTERFACE;
                    case TRAIT -> Ast.ContractKind.TRAIT;
                    default -> null;
                };
                Ast.InterfaceDecl iface = exportedInterface(target, sourceName, requiredKind);
                if (iface == null) continue; // import types may select a type alias.
                String localName = ImportRules.localName(imported, sourceName);
                Ast.InterfaceDecl previous = result.putIfAbsent(localName, iface);
                if (previous != null && previous != iface) {
                    throw new IllegalArgumentException(
                            "imported contract binding '" + localName + "' is ambiguous in '" + importerId + "'");
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Ast.InterfaceDecl exportedInterface(
            Ast.Program program,
            String name,
            Ast.ContractKind requiredKind) {
        Ast.InterfaceDecl found = null;
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.InterfaceDecl iface)
                        || iface.visibility() != Ast.Visibility.PUBLIC
                        || !iface.name().equals(name)
                        || (requiredKind != null && iface.contractKind() != requiredKind)) {
                    continue;
                }
                if (found != null && found != iface) {
                    throw new IllegalArgumentException(
                            "exported contract '" + name + "' is ambiguous in linked source unit");
                }
                found = iface;
            }
        }
        return found;
    }

    record ResolvedModuleImport(String targetUnitId, Ast.ModuleDecl module) { }

    /**
     * Resolve named runtime module imports so the importing type checker can
     * retain the provider's exact public module shape instead of degrading the
     * value to Unknown.
     */
    static Map<String, ResolvedModuleImport> resolveImportedModules(
            String importerId,
            Map<String, Ast.Program> programs,
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, ResolvedModuleImport> result = new LinkedHashMap<>();
        Ast.Program importer = programs.get(normalizeUnitId(importerId));
        if (importer == null) {
            throw new IllegalArgumentException("unknown importer source unit '" + importerId + "'");
        }

        for (Ast.ImportDecl imported : importer.imports()) {
            if (imported.wildcard() || imported.kind() != Ast.ImportKind.MODULE) continue;

            String targetId = resolveImportUnitId(
                    importerId, imported, programs.keySet(), importResolutions);
            if (targetId == null) continue;
            Ast.Program target = programs.get(targetId);
            if (target == null) continue;

            for (String sourceName : imported.names()) {
                Ast.ModuleDecl module = exportedModule(target, sourceName);
                if (module == null) continue;
                String localName = ImportRules.localName(imported, sourceName);
                ResolvedModuleImport previous = result.putIfAbsent(
                        localName, new ResolvedModuleImport(targetId, module));
                if (previous != null && previous.module() != module) {
                    throw new IllegalArgumentException(
                            "imported module binding '" + localName + "' is ambiguous in '" + importerId + "'");
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Ast.ModuleDecl exportedModule(Ast.Program program, String name) {
        Ast.ModuleDecl found = null;
        for (Ast.ModuleDecl module : program.modules()) {
            if (!module.name().equals(name)) continue;
            if (found != null && found != module) {
                throw new IllegalArgumentException(
                        "exported module '" + name + "' is ambiguous in linked source unit");
            }
            found = module;
        }
        return found;
    }

    /**
     * Dependency-first SCC order. Members inside one SCC are lexicographically
     * ordered so init order is deterministic even though the cycle itself does
     * not define an order.
     */
    static List<List<String>> initializationGroups(Map<String, Set<String>> dependencies) {
        if (dependencies.isEmpty()) return List.of();

        Tarjan tarjan = new Tarjan(dependencies);
        List<List<String>> components = tarjan.components();
        Map<String, Integer> componentOf = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (String unit : components.get(i)) componentOf.put(unit, i);
        }

        Map<Integer, Set<Integer>> componentDeps = new LinkedHashMap<>();
        for (int i = 0; i < components.size(); i++) componentDeps.put(i, new LinkedHashSet<>());
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            int from = componentOf.get(entry.getKey());
            for (String dependency : entry.getValue()) {
                Integer to = componentOf.get(dependency);
                if (to != null && to != from) componentDeps.get(from).add(to);
            }
        }

        List<Integer> componentIds = new ArrayList<>(componentDeps.keySet());
        componentIds.sort(Comparator.comparing(i -> components.get(i).getFirst()));

        List<List<String>> ordered = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        Set<Integer> visiting = new HashSet<>();
        for (int component : componentIds) {
            visitComponent(component, componentDeps, components, visited, visiting, ordered);
        }
        return List.copyOf(ordered);
    }

    private static void visitComponent(
            int component,
            Map<Integer, Set<Integer>> dependencies,
            List<List<String>> components,
            Set<Integer> visited,
            Set<Integer> visiting,
            List<List<String>> ordered) {
        if (visited.contains(component)) return;
        if (!visiting.add(component)) {
            throw new IllegalStateException("condensed import graph unexpectedly contains a cycle");
        }
        List<Integer> deps = new ArrayList<>(dependencies.getOrDefault(component, Set.of()));
        deps.sort(Comparator.comparing(i -> components.get(i).getFirst()));
        for (int dependency : deps) {
            visitComponent(dependency, dependencies, components, visited, visiting, ordered);
        }
        visiting.remove(component);
        visited.add(component);
        ordered.add(components.get(component));
    }

    static String normalizeUnitId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("source unit id cannot be blank");
        return Path.of(id).normalize().toString().replace('\\', '/');
    }

    private static final class Tarjan {
        private final Map<String, Set<String>> graph;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowLink = new HashMap<>();
        private final ArrayDeque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<List<String>> components = new ArrayList<>();
        private int nextIndex;

        private Tarjan(Map<String, Set<String>> graph) {
            this.graph = graph;
        }

        private List<List<String>> components() {
            List<String> vertices = new ArrayList<>(graph.keySet());
            vertices.sort(String::compareTo);
            for (String vertex : vertices) {
                if (!index.containsKey(vertex)) strongConnect(vertex);
            }
            return List.copyOf(components);
        }

        private void strongConnect(String vertex) {
            int current = nextIndex++;
            index.put(vertex, current);
            lowLink.put(vertex, current);
            stack.push(vertex);
            onStack.add(vertex);

            List<String> edges = new ArrayList<>(graph.getOrDefault(vertex, Set.of()));
            edges.sort(String::compareTo);
            for (String next : edges) {
                if (!graph.containsKey(next)) continue;
                if (!index.containsKey(next)) {
                    strongConnect(next);
                    lowLink.put(vertex, Math.min(lowLink.get(vertex), lowLink.get(next)));
                } else if (onStack.contains(next)) {
                    lowLink.put(vertex, Math.min(lowLink.get(vertex), index.get(next)));
                }
            }

            if (lowLink.get(vertex).equals(index.get(vertex))) {
                List<String> component = new ArrayList<>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(vertex));
                component.sort(String::compareTo);
                components.add(List.copyOf(component));
            }
        }
    }
}
