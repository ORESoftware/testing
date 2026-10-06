package dev.oreslang.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compile-time expansion for language-defined annotations/attributes.
 *
 * The source parser preserves annotations in the AST. This pass turns
 * annotations with code-generation semantics into ordinary AST nodes before
 * type/ownership checking and execution, keeping the runtime reflection-free.
 */
public final class AnnotationExpander {
    public static final String FROM_JSON = "FromJson";

    /** Internal-only markers. '$' cannot be written as an Oreslang identifier. */
    public static final String GENERATED_FROM_JSON_GETTER = "$generated.fromJson.getter";
    public static final String GENERATED_FROM_JSON_SETTER = "$generated.fromJson.setter";

    public record FromJsonBinding(
            String jsonKey,
            String fieldName,
            String getterName,
            String setterName,
            Ast.TypeRef type) { }

    private AnnotationExpander() { }

    public static Ast.Program expand(Ast.Program program) {
        List<Ast.ModuleDecl> modules = new ArrayList<>(program.modules().size());
        for (Ast.ModuleDecl module : program.modules()) modules.add(expandModule(module));
        return new Ast.Program(program.namespace(), program.imports(), modules);
    }

    public static boolean isGeneratedFromJsonSetter(Ast.MethodDecl method) {
        return hasAnnotation(method.annotations(), GENERATED_FROM_JSON_SETTER);
    }

    public static boolean isGeneratedFromJsonGetter(Ast.MethodDecl method) {
        return hasAnnotation(method.annotations(), GENERATED_FROM_JSON_GETTER);
    }

    /**
     * Stable metadata consumed by JSON codecs. The codec can look up an
     * incoming JSON key, then invoke the named generated setter. This keeps
     * deserialization dispatch explicit and reflection-free.
     */
    public static List<FromJsonBinding> fromJsonBindings(Ast.ClassDecl klass) {
        List<FromJsonBinding> result = new ArrayList<>();
        Map<String, String> keys = new LinkedHashMap<>();
        for (Ast.FieldDecl field : klass.fields()) {
            String jsonKey = fromJsonKey(field);
            if (jsonKey == null) continue;
            String previous = keys.putIfAbsent(jsonKey, field.name());
            if (previous != null && !previous.equals(field.name())) {
                throw new IllegalArgumentException("duplicate @FromJson key '" + jsonKey + "' on fields '"
                        + previous + "' and '" + field.name() + "' in class " + klass.name());
            }
            String suffix = accessorSuffix(field.name());
            result.add(new FromJsonBinding(
                    jsonKey,
                    field.name(),
                    "get" + suffix,
                    "set" + suffix,
                    field.type()));
        }
        return List.copyOf(result);
    }

    /**
     * Returns the JSON key for a field or null when the field is not annotated.
     * Annotation shape is validated here so every compiler consumer sees one
     * canonical interpretation.
     */
    public static String fromJsonKey(Ast.FieldDecl field) {
        Ast.Annotation found = null;
        for (Ast.Annotation annotation : field.annotations()) {
            if (!annotation.name().equals(FROM_JSON)) continue;
            if (found != null) {
                throw new IllegalArgumentException("field '" + field.name() + "' has duplicate @FromJson annotations");
            }
            found = annotation;
        }
        if (found == null) return null;
        if (found.arguments().size() != 1 || !found.arguments().getFirst().isStringLiteral()) {
            throw new IllegalArgumentException("@FromJson on field '" + field.name() + "' requires exactly one string key");
        }
        String key = found.arguments().getFirst().stringLiteralValue();
        if (key.isBlank()) throw new IllegalArgumentException("@FromJson key for field '" + field.name() + "' cannot be blank");
        return key;
    }

    private static Ast.ModuleDecl expandModule(Ast.ModuleDecl module) {
        List<Ast.Decl> declarations = new ArrayList<>(module.declarations().size());
        for (Ast.Decl declaration : module.declarations()) {
            if (declaration instanceof Ast.ClassDecl klass) {
                declarations.add(expandClass(klass));
            } else {
                if (declaration instanceof Ast.FieldDecl field && fromJsonKey(field) != null) {
                    throw new IllegalArgumentException("@FromJson is only valid on class fields, not module binding '" + field.name() + "'");
                }
                if (declaration instanceof Ast.FunctionDecl function && hasAnnotation(function.annotations(), FROM_JSON)) {
                    throw new IllegalArgumentException("@FromJson is only valid on class fields, not callable '" + function.name() + "'");
                }
                declarations.add(declaration);
            }
        }
        return new Ast.ModuleDecl(module.name(), module.annotations(), declarations);
    }

    private static Ast.ClassDecl expandClass(Ast.ClassDecl klass) {
        List<Ast.MethodDecl> methods = new ArrayList<>(klass.methods());
        Map<String, Ast.MethodDecl> signatures = new HashMap<>();
        for (Ast.MethodDecl method : methods) {
            if (hasAnnotation(method.annotations(), FROM_JSON)) {
                throw new IllegalArgumentException("@FromJson is only valid on class fields, not method '" + klass.name() + "." + method.name() + "'");
            }
            signatures.put(signature(method.name(), method.arity()), method);
        }

        Map<String, String> jsonKeys = new LinkedHashMap<>();
        for (Ast.FieldDecl field : klass.fields()) {
            String jsonKey = fromJsonKey(field);
            if (jsonKey == null) continue;
            if (klass.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalArgumentException("@FromJson is not valid on actor state field '"
                        + klass.name() + "." + field.name() + "'");
            }
            if (field.type() == null) {
                throw new IllegalArgumentException("@FromJson field '" + klass.name() + "." + field.name()
                        + "' requires an explicit field type");
            }

            String previous = jsonKeys.putIfAbsent(jsonKey, field.name());
            if (previous != null && !previous.equals(field.name())) {
                throw new IllegalArgumentException("duplicate @FromJson key '" + jsonKey + "' on fields '"
                        + previous + "' and '" + field.name() + "' in class " + klass.name());
            }
            if (field.bindingKind() != Ast.BindingKind.LET) {
                throw new IllegalArgumentException("@FromJson field '" + klass.name() + "." + field.name()
                        + "' must be mutable; use 'let " + field.type().name() + " " + field.name()
                        + "' or the shorthand '" + field.name() + ": " + field.type().name() + "'");
            }

            String suffix = accessorSuffix(field.name());
            addGeneratedAccessor(klass, methods, signatures, getter(field, jsonKey, "get" + suffix), true);
            addGeneratedAccessor(klass, methods, signatures, setter(field, jsonKey, "set" + suffix), false);
        }

        return new Ast.ClassDecl(
                klass.name(),
                klass.visibility(),
                klass.isAbstract(),
                klass.actorKind(),
                klass.genericParameters(),
                klass.parents(),
                klass.interfaces(),
                klass.fields(),
                klass.constructor(),
                methods);
    }

    private static void addGeneratedAccessor(
            Ast.ClassDecl klass,
            List<Ast.MethodDecl> methods,
            Map<String, Ast.MethodDecl> signatures,
            Ast.MethodDecl generated,
            boolean getter) {
        String key = signature(generated.name(), generated.arity());
        Ast.MethodDecl existing = signatures.get(key);
        if (existing != null) {
            boolean sameGeneratedKind = getter
                    ? isGeneratedFromJsonGetter(existing)
                    : isGeneratedFromJsonSetter(existing);
            if (sameGeneratedKind) return; // idempotent expansion
            throw new IllegalArgumentException("@FromJson generated accessor '" + klass.name() + "."
                    + generated.name() + "' collides with an existing method of arity " + generated.arity());
        }
        methods.add(generated);
        signatures.put(key, generated);
    }

    private static Ast.MethodDecl getter(Ast.FieldDecl field, String jsonKey, String name) {
        Ast.Annotation marker = new Ast.Annotation(
                GENERATED_FROM_JSON_GETTER,
                List.of(Ast.TypeRef.stringLiteral(jsonKey)));
        return new Ast.MethodDecl(
                name,
                Ast.Visibility.PUBLIC,
                false,
                false,
                false,
                null,
                List.of(),
                List.of(),
                field.type(),
                List.of(marker),
                List.of(new Ast.ReturnStmt(new Ast.MemberExpr(new Ast.NameExpr("self"), field.name()))));
    }

    private static Ast.MethodDecl setter(Ast.FieldDecl field, String jsonKey, String name) {
        Ast.Annotation marker = new Ast.Annotation(
                GENERATED_FROM_JSON_SETTER,
                List.of(Ast.TypeRef.stringLiteral(jsonKey)));
        return new Ast.MethodDecl(
                name,
                Ast.Visibility.PUBLIC,
                false,
                false,
                false,
                null,
                List.of(),
                List.of(new Ast.Param(field.type(), "value")),
                Ast.TypeRef.simple("void"),
                List.of(marker),
                List.of(
                        new Ast.ExprStmt(new Ast.AssignExpr(
                                new Ast.MemberExpr(new Ast.NameExpr("self"), field.name()),
                                new Ast.NameExpr("value"))),
                        new Ast.ReturnStmt(null)));
    }

    private static String accessorSuffix(String fieldName) {
        StringBuilder result = new StringBuilder(fieldName.length());
        boolean uppercase = true;
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (c == '_') {
                uppercase = true;
                continue;
            }
            result.append(uppercase ? Character.toUpperCase(c) : c);
            uppercase = false;
        }
        if (result.isEmpty()) throw new IllegalArgumentException("cannot generate accessor for empty field name");
        return result.toString();
    }

    private static String signature(String name, int arity) {
        return name + "/" + arity;
    }

    private static boolean hasAnnotation(List<Ast.Annotation> annotations, String name) {
        for (Ast.Annotation annotation : annotations) if (annotation.name().equals(name)) return true;
        return false;
    }
}
