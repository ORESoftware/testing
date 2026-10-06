package dev.oreslang.ast;

import java.util.List;

public final class Ast {
    private Ast() { }

    public record Program(String namespace, List<ImportDecl> imports, List<ModuleDecl> modules) {
        public Program {
            imports = List.copyOf(imports);
            modules = List.copyOf(modules);
        }
        public Program(List<ImportDecl> imports, List<ModuleDecl> modules) { this(null, imports, modules); }
        public Program(List<ModuleDecl> modules) { this(null, List.of(), modules); }
    }

    public enum ImportKind { MODULE, ACTOR, CLASS, FUNCTION, INTERFACE, TRAIT, STRUCT, TYPE, TYPES, ALL }

    public record ImportDecl(
            ImportKind kind,
            List<String> names,
            boolean wildcard,
            String namespace,
            String path) {
        public ImportDecl { names = List.copyOf(names); }
    }

    public record ModuleDecl(String name, List<Annotation> annotations, List<Decl> declarations) {
        public ModuleDecl {
            annotations = List.copyOf(annotations);
            declarations = List.copyOf(declarations);
        }
        public ModuleDecl(String name, List<Decl> declarations) { this(name, List.of(), declarations); }
    }

    public sealed interface Decl permits FunctionDecl, ClassDecl, InterfaceDecl, FieldDecl, TypeAliasDecl { }

    public enum Visibility { PRIVATE, PUBLIC }
    public enum CallableKind { FNC, ROUTINE }
    public enum ActorKind { NONE, PRIVATE, SHARED }

    public record Annotation(String name, List<TypeRef> arguments) {
        public Annotation { arguments = List.copyOf(arguments); }
    }

    public record TypeRef(String name, List<TypeRef> arguments, boolean inferArguments) {
        public TypeRef { arguments = List.copyOf(arguments); }
        public static TypeRef simple(String name) { return new TypeRef(name, List.of(), false); }
        public static TypeRef inferred() { return new TypeRef("$infer$", List.of(), false); }
        public static TypeRef borrowed(TypeRef target, boolean mutable) {
            return new TypeRef(mutable ? "$borrow_mut$" : "$borrow$", List.of(target), false);
        }
        public boolean isBorrow() { return name.equals("$borrow$") || name.equals("$borrow_mut$"); }
        public boolean mutableBorrow() { return name.equals("$borrow_mut$"); }
        public TypeRef borrowedTarget() {
            if (!isBorrow() || arguments.size() != 1) throw new IllegalStateException("not a borrow type");
            return arguments.getFirst();
        }
        public static TypeRef functionType(List<TypeRef> parameters, TypeRef result) {
            java.util.ArrayList<TypeRef> all = new java.util.ArrayList<>(parameters);
            all.add(result);
            return new TypeRef("Fnc", all, false);
        }
        public static TypeRef stringLiteral(String value) { return new TypeRef("$string$" + value, List.of(), false); }
        public boolean isStringLiteral() { return name.startsWith("$string$"); }
        public String stringLiteralValue() { return name.substring("$string$".length()); }

        public static TypeRef union(List<TypeRef> options) {
            java.util.ArrayList<TypeRef> flattened = new java.util.ArrayList<>();
            for (TypeRef option : options) {
                if (option.isUnion()) {
                    for (TypeRef nested : option.arguments()) if (!flattened.contains(nested)) flattened.add(nested);
                } else if (!flattened.contains(option)) flattened.add(option);
            }
            if (flattened.isEmpty()) throw new IllegalArgumentException("union type requires at least one member");
            if (flattened.size() == 1) return flattened.getFirst();
            flattened.sort(java.util.Comparator.comparing(TypeRef::toString));
            return new TypeRef("$union$", List.copyOf(flattened), false);
        }
        public boolean isUnion() { return name.equals("$union$"); }

        public static TypeRef tupleType(List<TypeRef> elements) {
            return new TypeRef("$tuple$", List.copyOf(elements), false);
        }
        public boolean isTupleType() { return name.equals("$tuple$"); }

        public static TypeRef recordType(java.util.Map<String, TypeRef> members) {
            java.util.ArrayList<TypeRef> fields = new java.util.ArrayList<>(members.size());
            java.util.ArrayList<java.util.Map.Entry<String, TypeRef>> entries = new java.util.ArrayList<>(members.entrySet());
            entries.sort(java.util.Map.Entry.comparingByKey());
            for (java.util.Map.Entry<String, TypeRef> entry : entries) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    throw new IllegalArgumentException("record type field name cannot be blank");
                }
                fields.add(new TypeRef("$field$" + entry.getKey(), List.of(entry.getValue()), false));
            }
            return new TypeRef("$record$", List.copyOf(fields), false);
        }
        public boolean isRecordType() { return name.equals("$record$"); }
        public java.util.Map<String, TypeRef> recordMembers() {
            if (!isRecordType()) throw new IllegalStateException("not a record type");
            java.util.LinkedHashMap<String, TypeRef> members = new java.util.LinkedHashMap<>();
            for (TypeRef field : arguments) {
                if (!field.name().startsWith("$field$") || field.arguments().size() != 1 || field.inferArguments()) {
                    throw new IllegalStateException("malformed record type field");
                }
                String fieldName = field.name().substring("$field$".length());
                if (members.putIfAbsent(fieldName, field.arguments().getFirst()) != null) {
                    throw new IllegalStateException("duplicate record type field " + fieldName);
                }
            }
            return java.util.Collections.unmodifiableMap(members);
        }
    }

    public record Param(TypeRef type, String name, boolean structural, boolean mutable) {
        public Param(TypeRef type, String name) { this(type, name, false, false); }
        public Param(TypeRef type, String name, boolean structural) { this(type, name, structural, false); }
    }

    public record FunctionDecl(
            String name,
            CallableKind kind,
            Visibility visibility,
            boolean async,
            boolean generator,
            boolean nonLexical,
            ActorKind actorKind,
            List<String> genericParameters,
            List<Param> parameters,
            TypeRef returnType,
            List<Annotation> annotations,
            List<Stmt> body) implements Decl {
        public FunctionDecl {
            genericParameters = List.copyOf(genericParameters);
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
            body = List.copyOf(body);
        }

        /** Compatibility constructor for non-generator callables. */
        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            boolean nonLexical, ActorKind actorKind, List<String> genericParameters,
                            List<Param> parameters, TypeRef returnType, List<Annotation> annotations,
                            List<Stmt> body) {
            this(name, kind, visibility, async, false, nonLexical, actorKind,
                    genericParameters, parameters, returnType, annotations, body);
        }

        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            ActorKind actorKind, List<String> genericParameters, List<Param> parameters,
                            TypeRef returnType, List<Annotation> annotations, List<Stmt> body) {
            this(name, kind, visibility, async, false, false, actorKind,
                    genericParameters, parameters, returnType, annotations, body);
        }

        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            List<String> genericParameters, List<Param> parameters, TypeRef returnType,
                            List<Annotation> annotations, List<Stmt> body) {
            this(name, kind, visibility, async, false, false, ActorKind.NONE,
                    genericParameters, parameters, returnType, annotations, body);
        }

        public FunctionDecl(String name, Visibility visibility, boolean async, List<String> genericParameters,
                            List<Param> parameters, TypeRef returnType, List<Annotation> annotations, List<Stmt> body) {
            this(name, CallableKind.FNC, visibility, async, false, false, ActorKind.NONE,
                    genericParameters, parameters, returnType, annotations, body);
        }
    }

    public record ClassDecl(
            String name,
            boolean isAbstract,
            ActorKind actorKind,
            List<String> genericParameters,
            List<TypeRef> parents,
            List<TypeRef> interfaces,
            List<FieldDecl> fields,
            List<MethodDecl> methods) implements Decl {
        public ClassDecl {
            genericParameters = List.copyOf(genericParameters);
            parents = List.copyOf(parents);
            interfaces = List.copyOf(interfaces);
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
        }
        public ClassDecl(String name, boolean isAbstract, List<String> genericParameters,
                         List<TypeRef> parents, List<TypeRef> interfaces,
                         List<FieldDecl> fields, List<MethodDecl> methods) {
            this(name, isAbstract, ActorKind.NONE, genericParameters, parents, interfaces, fields, methods);
        }
        public ClassDecl(String name, boolean isAbstract, List<String> genericParameters,
                         List<FieldDecl> fields, List<MethodDecl> methods) {
            this(name, isAbstract, ActorKind.NONE, genericParameters, List.of(), List.of(), fields, methods);
        }
    }

    public sealed interface InterfaceMember permits InterfaceFunctionDecl, InterfaceFieldDecl { }

    public record InterfaceFunctionDecl(
            String name,
            List<String> genericParameters,
            List<Param> parameters,
            TypeRef returnType) implements InterfaceMember {
        public InterfaceFunctionDecl {
            genericParameters = List.copyOf(genericParameters);
            parameters = List.copyOf(parameters);
        }
    }

    public record InterfaceFieldDecl(String name, TypeRef type) implements InterfaceMember { }

    public record InterfaceDecl(
            String name,
            Visibility visibility,
            List<String> genericParameters,
            List<TypeRef> parents,
            List<InterfaceMember> members) implements Decl {
        public InterfaceDecl {
            genericParameters = List.copyOf(genericParameters);
            parents = List.copyOf(parents);
            members = List.copyOf(members);
        }
        public InterfaceDecl(String name, List<String> genericParameters, List<InterfaceMember> members) {
            this(name, Visibility.PRIVATE, genericParameters, List.of(), members);
        }
    }

    public record FieldDecl(
            String name,
            Visibility visibility,
            BindingKind bindingKind,
            TypeRef type,
            List<Annotation> annotations,
            Expr initializer) implements Decl {
        public FieldDecl { annotations = List.copyOf(annotations); }
        public FieldDecl(String name, Visibility visibility, BindingKind bindingKind, TypeRef type, Expr initializer) {
            this(name, visibility, bindingKind, type, List.of(), initializer);
        }
    }

    public record MethodDecl(
            String name,
            Visibility visibility,
            boolean isStatic,
            boolean isAbstract,
            boolean async,
            TypeRef explicitReceiverType,
            List<String> genericParameters,
            List<Param> parameters,
            TypeRef returnType,
            List<Annotation> annotations,
            List<Stmt> body) {
        public MethodDecl {
            genericParameters = List.copyOf(genericParameters);
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
            body = List.copyOf(body);
        }
        public int arity() { return parameters.size(); }

        /**
         * Overload identity for class callables is deliberately only name + arity.
         * Parameter types, parameter names, generic parameters, return types,
         * and the implicit/explicit self receiver never participate in overload selection.
         */
        public MethodArity overloadIdentity() { return new MethodArity(name, arity()); }

        public boolean matchesArity(String candidateName, int candidateArity) {
            return name.equals(candidateName) && arity() == candidateArity;
        }
    }

    public record MethodArity(String name, int arity) {
        public MethodArity {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("method name cannot be blank");
            if (arity < 0) throw new IllegalArgumentException("method arity cannot be negative");
        }
    }

    public record TypeAliasDecl(String name, List<String> genericParameters, TypeRef target) implements Decl {
        public TypeAliasDecl { genericParameters = List.copyOf(genericParameters); }
    }

    public enum BindingKind { CONST, VAL, LET }

    public sealed interface Stmt permits BindingStmt, DestructureStmt, ReturnStmt, YieldStmt, ExprStmt, DeferStmt,
            BlockStmt, BreakStmt, ContinueStmt, IfStmt, MatchStmt, SwitchStmt, TryStmt,
            ForOfStmt, ForOfDestructureStmt, ForStmt, LoopStmt { }

    public record BindingStmt(BindingKind kind, TypeRef declaredType, String name, Expr initializer) implements Stmt { }
    public record DestructureBinding(BindingKind kind, String name) {
        public DestructureBinding {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("destructure binding name cannot be blank");
            }
        }

        public static DestructureBinding discard() {
            return new DestructureBinding(BindingKind.VAL, "_");
        }

        public boolean isDiscard() {
            return "_".equals(name);
        }
    }

    public enum DestructureKind { SEQUENCE, OBJECT }

    public record DestructureStmt(DestructureKind kind, List<DestructureBinding> bindings, Expr initializer) implements Stmt {
        public DestructureStmt { bindings = List.copyOf(bindings); }
        public DestructureStmt(List<DestructureBinding> bindings, Expr initializer) {
            this(DestructureKind.SEQUENCE, bindings, initializer);
        }
    }

    public record ReturnStmt(Expr value) implements Stmt { }
    public record YieldStmt(Expr value) implements Stmt {
        public YieldStmt {
            if (value == null) throw new IllegalArgumentException("yield requires a value");
        }
    }
    public record ExprStmt(Expr expression) implements Stmt { }
    public record DeferStmt(Expr expression) implements Stmt { }

    public record BlockStmt(List<Stmt> body) implements Stmt {
        public BlockStmt { body = List.copyOf(body); }
    }
    public record BreakStmt() implements Stmt { }
    public record ContinueStmt() implements Stmt { }

    public record IfBranch(Expr condition, List<Stmt> body) {
        public IfBranch { body = List.copyOf(body); }
    }

    public record IfStmt(List<IfBranch> branches, List<Stmt> elseBody) implements Stmt {
        public IfStmt {
            branches = List.copyOf(branches);
            elseBody = List.copyOf(elseBody);
        }
    }

    /**
     * Oreslang patterns are language-level values for static analysis and native lowering.
     * They deliberately do not encode JVM Class/instanceof semantics.
     */
    public sealed interface Pattern permits WildcardPattern, LiteralPattern, BindingPattern,
            TypePattern, ConstructorPattern { }

    public record WildcardPattern() implements Pattern { }
    public record LiteralPattern(Object value) implements Pattern { }
    public record BindingPattern(String name) implements Pattern { }
    public record TypePattern(TypeRef type, String binding) implements Pattern { }
    public record ConstructorPattern(String constructor, List<Pattern> arguments) implements Pattern {
        public ConstructorPattern { arguments = List.copyOf(arguments); }
    }

    public record MatchArm(Pattern pattern, Expr guard, List<Stmt> body) {
        public MatchArm { body = List.copyOf(body); }
    }

    /**
     * ordered=false is the normal proof-checked form: arm predicates must be disjoint.
     * ordered=true ("match first") is an explicit priority/first-match escape hatch.
     */
    public record MatchStmt(Expr subject, boolean ordered, List<MatchArm> arms) implements Stmt {
        public MatchStmt { arms = List.copyOf(arms); }
    }

    public record SwitchCase(List<Expr> constants, List<Stmt> body) {
        public SwitchCase {
            constants = List.copyOf(constants);
            body = List.copyOf(body);
        }
    }

    public record SwitchStmt(Expr subject, List<SwitchCase> cases, List<Stmt> defaultBody) implements Stmt {
        public SwitchStmt {
            cases = List.copyOf(cases);
            defaultBody = List.copyOf(defaultBody);
        }
    }

    public record TryStmt(List<Stmt> body, String errorName, List<Stmt> catchBody, List<Stmt> finallyBody) implements Stmt {
        public TryStmt {
            body = List.copyOf(body);
            catchBody = List.copyOf(catchBody);
            finallyBody = List.copyOf(finallyBody);
        }
    }

    public record ForOfStmt(
            BindingKind bindingKind,
            String bindingName,
            Expr iterable,
            boolean asyncIteration,
            List<Stmt> body) implements Stmt {
        public ForOfStmt { body = List.copyOf(body); }
        public ForOfStmt(BindingKind bindingKind, String bindingName, Expr iterable, List<Stmt> body) {
            this(bindingKind, bindingName, iterable, false, body);
        }
    }

    public record ForOfDestructureStmt(
            List<DestructureBinding> bindings,
            Expr iterable,
            boolean asyncIteration,
            List<Stmt> body) implements Stmt {
        public ForOfDestructureStmt {
            bindings = List.copyOf(bindings);
            body = List.copyOf(body);
            if (bindings.isEmpty()) {
                throw new IllegalArgumentException("for-of destructure pattern cannot be empty");
            }
        }
        public ForOfDestructureStmt(List<DestructureBinding> bindings, Expr iterable, List<Stmt> body) {
            this(bindings, iterable, false, body);
        }
    }

    public record ForStmt(Stmt initializer, Expr condition, Expr update, List<Stmt> body) implements Stmt {
        public ForStmt { body = List.copyOf(body); }
    }

    public record LoopStmt(List<Stmt> body) implements Stmt {
        public LoopStmt { body = List.copyOf(body); }
    }

    public sealed interface Expr permits LiteralExpr, NameExpr, BinaryExpr, UnaryExpr, AssignExpr, ConditionalExpr,
            TypeTestExpr, PatternTestExpr, CastExpr,
            CallExpr, MemberExpr, IndexExpr, NewExpr, AwaitExpr, ListExpr, TupleExpr, ObjectExpr, LambdaExpr { }

    public record LiteralExpr(Object value) implements Expr { }
    public record Imaginary(double coefficient) { }
    public record NameExpr(String name) implements Expr { }
    public record BinaryExpr(String operator, Expr left, Expr right) implements Expr { }
    public record UnaryExpr(String operator, Expr operand) implements Expr { }
    public record AssignExpr(Expr target, Expr value) implements Expr { }
    public record ConditionalExpr(Expr condition, Expr whenTrue, Expr whenFalse) implements Expr { }

    /** "value is Type [binding]" -- a nominal/refinement test, not general pattern matching. */
    public record TypeTestExpr(Expr value, TypeRef targetType, String binding) implements Expr { }

    /** "value matches Pattern" -- full pattern predicate, with bindings scoped by the enclosing condition. */
    public record PatternTestExpr(Expr value, Pattern pattern) implements Expr { }

    public enum CastMode { CHECKED, OPTIONAL }

    /** "value as Type" or "value as? Type". */
    public record CastExpr(Expr value, TypeRef targetType, CastMode mode) implements Expr { }

    public record CallExpr(
            Expr callee,
            List<TypeRef> typeArguments,
            boolean typeArgumentsPresent,
            List<Expr> arguments) implements Expr {
        public CallExpr {
            typeArguments = List.copyOf(typeArguments);
            arguments = List.copyOf(arguments);
            if (!typeArgumentsPresent && !typeArguments.isEmpty()) {
                throw new IllegalArgumentException("call type arguments require an explicit <...> marker");
            }
        }
        public CallExpr(Expr callee, List<Expr> arguments) {
            this(callee, List.of(), false, arguments);
        }
        public CallExpr(Expr callee, List<TypeRef> typeArguments, List<Expr> arguments) {
            this(callee, typeArguments, true, arguments);
        }
    }

    public record MemberExpr(Expr receiver, String member) implements Expr { }
    public record IndexExpr(Expr receiver, Expr index) implements Expr { }

    public record NewExpr(TypeRef type, List<Expr> arguments) implements Expr {
        public NewExpr { arguments = List.copyOf(arguments); }
    }

    public record AwaitExpr(Expr expression) implements Expr { }

    public record ListExpr(List<Expr> elements) implements Expr {
        public ListExpr { elements = List.copyOf(elements); }
    }

    public record TupleExpr(List<Expr> elements) implements Expr {
        public TupleExpr { elements = List.copyOf(elements); }
    }

    public record ObjectField(String name, Expr dynamicName, Expr value) {
        public ObjectField {
            if ((name == null) == (dynamicName == null)) {
                throw new IllegalArgumentException("object field must have exactly one static or dynamic key");
            }
        }
        public static ObjectField named(String name, Expr value) {
            return new ObjectField(java.util.Objects.requireNonNull(name, "name"), null, value);
        }
        public static ObjectField dynamic(Expr key, Expr value) {
            return new ObjectField(null, java.util.Objects.requireNonNull(key, "key"), value);
        }
        public boolean isDynamic() { return dynamicName != null; }
    }

    public record ObjectExpr(List<ObjectField> fields) implements Expr {
        public ObjectExpr { fields = List.copyOf(fields); }
    }

    public record LambdaExpr(List<Param> parameters, Expr expressionBody, List<Stmt> blockBody, boolean nonLexical) implements Expr {
        public LambdaExpr {
            parameters = List.copyOf(parameters);
            blockBody = blockBody == null ? null : List.copyOf(blockBody);
        }
        public LambdaExpr(List<Param> parameters, Expr expressionBody, List<Stmt> blockBody) {
            this(parameters, expressionBody, blockBody, false);
        }
    }
}
