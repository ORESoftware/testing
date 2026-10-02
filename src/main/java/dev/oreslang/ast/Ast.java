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

    public enum ImportKind { MODULE, CLASS, FUNCTION, ALL }

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

    public record Annotation(String name, List<TypeRef> arguments) {
        public Annotation { arguments = List.copyOf(arguments); }
    }

    public record TypeRef(String name, List<TypeRef> arguments, boolean inferArguments) {
        public TypeRef { arguments = List.copyOf(arguments); }
        public static TypeRef simple(String name) { return new TypeRef(name, List.of(), false); }
        public static TypeRef inferred() { return new TypeRef("$infer$", List.of(), false); }
        public static TypeRef borrowed(TypeRef target, boolean mutable) {
            return new TypeRef(mutable ? "BorrowMut" : "Borrow", List.of(target), false);
        }
        public boolean isBorrow() {
            return name.equals("Borrow") || name.equals("BorrowMut")
                    || name.equals("$borrow$") || name.equals("$borrow_mut$");
        }
        public boolean mutableBorrow() {
            return name.equals("BorrowMut") || name.equals("$borrow_mut$");
        }
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

    public record FunctionDecl(
            String name,
            CallableKind kind,
            Visibility visibility,
            boolean async,
            boolean nonLexical,
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
        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            List<String> genericParameters, List<Param> parameters, TypeRef returnType,
                            List<Annotation> annotations, List<Stmt> body) {
            this(name, kind, visibility, async, false, genericParameters, parameters, returnType, annotations, body);
        }
        public FunctionDecl(String name, Visibility visibility, boolean async, List<String> genericParameters,
                            List<Param> parameters, TypeRef returnType, List<Annotation> annotations, List<Stmt> body) {
            this(name, CallableKind.FNC, visibility, async, false, genericParameters, parameters, returnType, annotations, body);
        }
    }

    public record ClassDecl(
            String name,
            boolean isAbstract,
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
                         List<FieldDecl> fields, List<MethodDecl> methods) {
            this(name, isAbstract, genericParameters, List.of(), List.of(), fields, methods);
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
            Expr initializer) implements Decl { }

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
    }

    public record TypeAliasDecl(String name, List<String> genericParameters, TypeRef target) implements Decl {
        public TypeAliasDecl { genericParameters = List.copyOf(genericParameters); }
    }

    public enum BindingKind { CONST, VAL, LET }

    public sealed interface Stmt permits BindingStmt, DestructureStmt, ReturnStmt, ExprStmt, DeferStmt,
            IfStmt, TryStmt, ForOfStmt, ForStmt { }

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
            CallExpr, MemberExpr, IndexExpr, NewExpr, AwaitExpr, ListExpr, TupleExpr, ObjectExpr, LambdaExpr { }

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
