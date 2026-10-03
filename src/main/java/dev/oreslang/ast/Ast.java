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

    public enum ImportKind { MODULE, CLASS, STRUCT, FUNCTION, ALL }

    public record ImportDecl(
            ImportKind kind,
            List<String> names,
            boolean wildcard,
            String namespace,
            String path) {
        public ImportDecl { names = List.copyOf(names); }
    }

    public record ModuleDecl(String name, boolean singleton, List<Annotation> annotations, List<Decl> declarations) {
        public ModuleDecl {
            annotations = List.copyOf(annotations);
            declarations = List.copyOf(declarations);
        }
        public ModuleDecl(String name, List<Annotation> annotations, List<Decl> declarations) {
            this(name, false, annotations, declarations);
        }
        public ModuleDecl(String name, List<Decl> declarations) {
            this(name, false, List.of(), declarations);
        }
    }

    public sealed interface Decl permits FunctionDecl, InitDecl, ClassDecl, TraitDecl, InterfaceDecl, FieldDecl, TypeAliasDecl { }

    public enum Visibility { PRIVATE, PUBLIC }
    public enum CallableKind { FNC, ROUTINE }
    public enum AggregateKind { CLASS, STRUCT }

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
    }

    public record Param(TypeRef type, String name, boolean structural, boolean mutable) {
        public Param(TypeRef type, String name) { this(type, name, false, false); }
        public Param(TypeRef type, String name, boolean structural) { this(type, name, structural, false); }
    }

    /** Lifecycle routine. Scope is derived from its containing module:
     * root/ordinary module => actor-local; singleton module => process-local. */
    public record InitDecl(List<Stmt> body) implements Decl {
        public InitDecl { body = List.copyOf(body); }
    }

    public record FunctionDecl(
            String name,
            CallableKind kind,
            Visibility visibility,
            boolean async,
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
        public FunctionDecl(String name, Visibility visibility, boolean async, List<String> genericParameters,
                            List<Param> parameters, TypeRef returnType, List<Annotation> annotations, List<Stmt> body) {
            this(name, CallableKind.FNC, visibility, async, genericParameters, parameters, returnType, annotations, body);
        }
    }

    public record ClassDecl(
            String name,
            AggregateKind kind,
            boolean isAbstract,
            List<String> genericParameters,
            List<TypeRef> parents,
            List<TypeRef> interfaces,
            List<TypeRef> traits,
            List<FieldDecl> fields,
            List<MethodDecl> methods) implements Decl {
        public ClassDecl {
            genericParameters = List.copyOf(genericParameters);
            parents = List.copyOf(parents);
            interfaces = List.copyOf(interfaces);
            traits = List.copyOf(traits);
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
            if (kind == AggregateKind.STRUCT && isAbstract) {
                throw new IllegalArgumentException("structs cannot be abstract");
            }
            if (kind == AggregateKind.STRUCT && !parents.isEmpty()) {
                throw new IllegalArgumentException("structs cannot extend classes");
            }
        }
        public ClassDecl(String name, boolean isAbstract, List<String> genericParameters,
                         List<TypeRef> parents, List<TypeRef> interfaces, List<TypeRef> traits,
                         List<FieldDecl> fields, List<MethodDecl> methods) {
            this(name, AggregateKind.CLASS, isAbstract, genericParameters, parents, interfaces, traits, fields, methods);
        }
        public ClassDecl(String name, boolean isAbstract, List<String> genericParameters,
                         List<TypeRef> parents, List<TypeRef> interfaces,
                         List<FieldDecl> fields, List<MethodDecl> methods) {
            this(name, AggregateKind.CLASS, isAbstract, genericParameters, parents, interfaces, List.of(), fields, methods);
        }
        public ClassDecl(String name, boolean isAbstract, List<String> genericParameters,
                         List<FieldDecl> fields, List<MethodDecl> methods) {
            this(name, AggregateKind.CLASS, isAbstract, genericParameters, List.of(), List.of(), List.of(), fields, methods);
        }
        public boolean isStruct() { return kind == AggregateKind.STRUCT; }
    }

    /**
     * Compile-time composition unit. Traits have reusable instance state and
     * behavior but no independent object identity and cannot be instantiated.
     */
    public record TraitDecl(
            String name,
            List<String> genericParameters,
            List<TypeRef> interfaces,
            List<TypeRef> traits,
            List<FieldDecl> fields,
            List<MethodDecl> methods) implements Decl {
        public TraitDecl {
            genericParameters = List.copyOf(genericParameters);
            interfaces = List.copyOf(interfaces);
            traits = List.copyOf(traits);
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
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

    /** Compatibility-only node; InterfaceDecl rejects data members. */
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
            if (members.stream().anyMatch(member -> !(member instanceof InterfaceFunctionDecl))) {
                throw new IllegalArgumentException("interfaces are storage-free method contracts; data members are not allowed");
            }
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
            Expr initializer,
            String compositionOwner) implements Decl {
        public FieldDecl(String name, Visibility visibility, BindingKind bindingKind, TypeRef type, Expr initializer) {
            this(name, visibility, bindingKind, type, initializer, null);
        }
        public boolean composed() { return compositionOwner != null; }
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
            List<Stmt> body,
            String compositionOwner) {
        public MethodDecl {
            genericParameters = List.copyOf(genericParameters);
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
            body = List.copyOf(body);
        }
        public MethodDecl(
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
            this(name, visibility, isStatic, isAbstract, async, explicitReceiverType,
                    genericParameters, parameters, returnType, annotations, body, null);
        }
        public int arity() { return parameters.size(); }
        public boolean composed() { return compositionOwner != null; }
    }

    public record TypeAliasDecl(String name, List<String> genericParameters, TypeRef target) implements Decl {
        public TypeAliasDecl { genericParameters = List.copyOf(genericParameters); }
    }

    public enum BindingKind { CONST, VAL, LET }

    public sealed interface Stmt permits TypeDeclStmt, BindingStmt, DestructureStmt, ReturnStmt, ExprStmt, DeferStmt,
            IfStmt, TryStmt, ForOfStmt, ForStmt { }

    /** Lexically scoped compile-time type declaration inside a callable/block. */
    public record TypeDeclStmt(Decl declaration) implements Stmt {
        public TypeDeclStmt {
            if (!(declaration instanceof ClassDecl klass && klass.isStruct())
                    && !(declaration instanceof InterfaceDecl)
                    && !(declaration instanceof TraitDecl)
                    && !(declaration instanceof TypeAliasDecl)) {
                throw new IllegalArgumentException("callable-local type declarations are limited to struct/interface/trait/type");
            }
        }
    }

    public record BindingStmt(BindingKind kind, TypeRef declaredType, String name, Expr initializer) implements Stmt { }
    public record DestructureBinding(BindingKind kind, String name) { }

    public record DestructureStmt(List<DestructureBinding> bindings, Expr initializer) implements Stmt {
        public DestructureStmt { bindings = List.copyOf(bindings); }
    }

    public record ReturnStmt(Expr value) implements Stmt { }
    public record ExprStmt(Expr expression) implements Stmt { }
    public record DeferStmt(Expr expression) implements Stmt { }

    public record IfBranch(Expr condition, List<Stmt> body) {
        public IfBranch { body = List.copyOf(body); }
    }

    public record IfStmt(List<IfBranch> branches, List<Stmt> elseBody) implements Stmt {
        public IfStmt {
            branches = List.copyOf(branches);
            elseBody = List.copyOf(elseBody);
        }
    }

    public record TryStmt(List<Stmt> body, String errorName, List<Stmt> catchBody, List<Stmt> finallyBody) implements Stmt {
        public TryStmt {
            body = List.copyOf(body);
            catchBody = List.copyOf(catchBody);
            finallyBody = List.copyOf(finallyBody);
        }
    }

    public record ForOfStmt(BindingKind bindingKind, String bindingName, Expr iterable, List<Stmt> body) implements Stmt {
        public ForOfStmt { body = List.copyOf(body); }
    }

    public record ForStmt(Stmt initializer, Expr condition, Expr update, List<Stmt> body) implements Stmt {
        public ForStmt { body = List.copyOf(body); }
    }

    public sealed interface Expr permits LiteralExpr, NameExpr, BinaryExpr, UnaryExpr, AssignExpr, ConditionalExpr,
            CallExpr, MemberExpr, IndexExpr, NewExpr, StructInitExpr, AwaitExpr, ListExpr, TupleExpr, ObjectExpr, LambdaExpr { }

    public record LiteralExpr(Object value) implements Expr { }
    public record Imaginary(double coefficient) { }
    public record NameExpr(String name) implements Expr { }
    public record BinaryExpr(String operator, Expr left, Expr right) implements Expr { }
    public record UnaryExpr(String operator, Expr operand) implements Expr { }
    public record AssignExpr(Expr target, Expr value) implements Expr { }
    public record ConditionalExpr(Expr condition, Expr whenTrue, Expr whenFalse) implements Expr { }

    public record CallExpr(Expr callee, List<Expr> arguments) implements Expr {
        public CallExpr { arguments = List.copyOf(arguments); }
    }

    public record MemberExpr(Expr receiver, String member) implements Expr { }
    public record IndexExpr(Expr receiver, Expr index) implements Expr { }

    public record NewExpr(TypeRef type, List<Expr> arguments) implements Expr {
        public NewExpr { arguments = List.copyOf(arguments); }
    }

    public record StructInitExpr(TypeRef type, List<ObjectField> fields) implements Expr {
        public StructInitExpr { fields = List.copyOf(fields); }
        public boolean anonymous() { return type == null; }
    }

    public record AwaitExpr(Expr expression) implements Expr { }

    public record ListExpr(List<Expr> elements) implements Expr {
        public ListExpr { elements = List.copyOf(elements); }
    }

    public record TupleExpr(List<Expr> elements) implements Expr {
        public TupleExpr { elements = List.copyOf(elements); }
    }

    public record ObjectField(String name, Expr value) { }

    public record ObjectExpr(List<ObjectField> fields) implements Expr {
        public ObjectExpr { fields = List.copyOf(fields); }
    }

    public record LambdaExpr(List<Param> parameters, Expr expressionBody, List<Stmt> blockBody) implements Expr {
        public LambdaExpr {
            parameters = List.copyOf(parameters);
            blockBody = blockBody == null ? null : List.copyOf(blockBody);
        }
    }
}
