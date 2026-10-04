package dev.oreslang.imports;

import dev.oreslang.ast.Ast;

import java.util.List;
import java.util.regex.Pattern;

/** Shared invariants for source imports and capability-gated Java host imports. */
public final class ImportRules {
    public static final String JAVA_PREFIX = "java:";
    private static final Pattern JAVA_BINARY_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private ImportRules() { }

    public static boolean isJavaPath(String path) {
        return path != null && path.startsWith(JAVA_PREFIX);
    }

    public static String javaClassName(String path) {
        if (!isJavaPath(path)) throw new IllegalArgumentException("not a Java host import path: " + path);
        String className = path.substring(JAVA_PREFIX.length());
        if (!JAVA_BINARY_NAME.matcher(className).matches()) {
            throw new IllegalArgumentException("invalid Java host class name '" + className + "'");
        }
        return className;
    }

    public static String javaSimpleName(String path) {
        String className = javaClassName(path);
        int dot = className.lastIndexOf('.');
        String simple = dot < 0 ? className : className.substring(dot + 1);
        int nested = simple.lastIndexOf('$');
        return nested < 0 ? simple : simple.substring(nested + 1);
    }

    public static String localName(Ast.ImportDecl imported, String sourceName) {
        if (!imported.wildcard() && imported.namespace() != null) {
            if (imported.names().size() != 1 || !imported.names().getFirst().equals(sourceName)) {
                throw new IllegalArgumentException("named import alias does not match its selected source name");
            }
            return imported.namespace();
        }
        return sourceName;
    }

    public static List<String> exposedBindings(Ast.ImportDecl imported) {
        if (imported.wildcard()) return List.of(imported.namespace());
        return imported.names().stream().map(name -> localName(imported, name)).toList();
    }

    public static void validate(Ast.ImportDecl imported) {
        if (imported == null) throw new IllegalArgumentException("import declaration cannot be null");
        if (imported.path() == null || imported.path().isBlank()) {
            throw new IllegalArgumentException("import path cannot be empty");
        }
        if (imported.path().length() > 4096 || imported.path().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("import path contains invalid control characters or is too long");
        }

        if (imported.wildcard()) {
            if (imported.namespace() == null || imported.namespace().isBlank()) {
                throw new IllegalArgumentException("wildcard imports require a namespace alias");
            }
            if (!imported.names().isEmpty()) {
                throw new IllegalArgumentException("wildcard imports cannot also contain named selections");
            }
        } else {
            if (imported.kind() == Ast.ImportKind.ALL || imported.names().isEmpty()) {
                throw new IllegalArgumentException("named import must select at least one name");
            }
            if (imported.namespace() != null) {
                if (imported.namespace().isBlank()) {
                    throw new IllegalArgumentException("named import alias cannot be blank");
                }
                if (imported.names().size() != 1) {
                    throw new IllegalArgumentException("named import aliases require exactly one selected name");
                }
            }
        }

        if (!isJavaPath(imported.path())) return;

        String simpleName = javaSimpleName(imported.path());
        if (imported.kind() == Ast.ImportKind.MODULE) {
            throw new IllegalArgumentException("Java host classes cannot be imported as Oreslang modules");
        }
        if (imported.kind() == Ast.ImportKind.CLASS) {
            if (imported.wildcard() || imported.names().size() != 1) {
                throw new IllegalArgumentException("Java class imports must select exactly one class name");
            }
            if (!imported.names().getFirst().equals(simpleName)) {
                throw new IllegalArgumentException("Java class import name '" + imported.names().getFirst()
                        + "' must match host class simple name '" + simpleName + "'");
            }
        }
    }
}
