package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative GPU admission pass.
 *
 * <p>The reserved {@code gpu} keyword is an execution-placement contract, not a hint. Until a
 * backend lowers the checked subset to a real GPU kernel, this pass rejects constructs that could
 * accidentally cross back into host/CPU execution. The evaluator separately fails closed if a
 * checked GPU callable reaches the interpreter.</p>
 */
public final class GpuSafetyChecker {
    private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Map<String, String> classOwners = new HashMap<>();
    private final Map<String, Ast.TypeAliasDecl> aliases = new HashMap<>();
    private final Set<String> ambiguousAliases = new HashSet<>();

    private GpuSafetyChecker() { }

    public static Ast.Program check(Ast.Program program) {
        GpuSafetyChecker checker = new GpuSafetyChecker();
        checker.index(program);
        checker.validate(program);
        return program;
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            modules.put(module.name(), module);
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                } else if (decl instanceof Ast.ClassDecl klass) {
                    index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                    classOwners.put(module.name() + "." + klass.name(), module.name());
                    classOwners.putIfAbsent(klass.name(), module.name());
                } else if (decl instanceof Ast.TypeAliasDecl alias) {
                    aliases.put(module.name() + "." + alias.name(), alias);
                    Ast.TypeAliasDecl previous = aliases.putIfAbsent(alias.name(), alias);
                    if (previous != null && previous != alias) {
                        ambiguousAliases.add(alias.name());
                        aliases.remove(alias.name());
                    }
                }
            }
        }
    }

    private static <T> void index(Map<String, T> map, Set<String> ambiguous, String module, String name, T value) {
        map.put(module + "." + name, value);
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            ambiguous.add(name);
            map.remove(name);
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    if (Ast.hasGpuPlacement(fn.annotations())) validateGpuFunction(module.name(), fn);
                    else scanStatements(module.name(), fn.body());
                } else if (decl instanceof Ast.ClassDecl klass) {
                    for (Ast.FieldDecl field : klass.fields()) {
                        if (field.initializer() != null) scanExpression(module.name(), field.initializer());
                    }
                    for (Ast.MethodDecl method : klass.methods()) {
                        if (Ast.hasGpuPlacement(method.annotations())) validateGpuMethod(module.name(), klass, method);
                        else scanStatements(module.name(), method.body());
                    }
                } else if (decl instanceof Ast.FieldDecl field && field.initializer() != null) {
                    scanExpression(module.name(), field.initializer());
                }
            }
        }
    }

    private void validateGpuFunction(String module, Ast.FunctionDecl fn) {
        if (fn.kind() != Ast.CallableKind.FNC) {
            throw new IllegalArgumentException("'gpu' is supported on fnc, not routine: " + module + "." + fn.name());
        }
        validateSignature(module, module + "." + fn.name(), fn.genericParameters(), fn.parameters(), fn.returnType());
        Scope scope = new Scope(null);
        for (Ast.Param param : fn.parameters()) scope.define(param.name());
        validateGpuStatements(module, scope, fn.body(), false);
    }

    private void validateGpuMethod(String module, Ast.ClassDecl klass, Ast.MethodDecl method) {
        if (!method.isStatic()) {
            throw new IllegalArgumentException("GPU instance methods are not supported until class layouts can be lowered safely; use 'gpu static fnc'");
        }
        validateSignature(module, module + "." + klass.name() + "." + method.name(),
                method.genericParameters(), method.parameters(), method.returnType());
        Scope scope = new Scope(null);
        for (Ast.Param param : method.parameters()) scope.define(param.name());
        validateGpuStatements(module, scope, method.body(), false);
    }

    private void validateSignature(String module, String label, List<String> generics, List<Ast.Param> parameters, Ast.TypeRef result) {
        if (!generics.isEmpty()) {
            throw new IllegalArgumentException("GPU callable '" + label + "' cannot be generic until GPU monomorphization is implemented");
        }
        for (Ast.Param param : parameters) {
            if (param.structural()) {
                throw new IllegalArgumentException("GPU callable '" + label + "' cannot use structural parameters");
            }
            if (param.type().isTupleType()) {
                throw new IllegalArgumentException("GPU callable '" + label + "' cannot pass tuple parameters through the current device ABI");
            }
            requireGpuType(module, param.type(), false, new HashSet<>(), label + " parameter " + param.name());
        }
        requireGpuType(module, result, true, new HashSet<>(), label + " result");
        Ast.TypeRef resolvedResult = resolveAlias(module, result, new HashSet<>());
        if (resolvedResult.isTupleType() || resolvedResult.name().equals("Array") || resolvedResult.name().equals("List")) {
            throw new IllegalArgumentException("GPU callable '" + label
                    + "' must return a scalar or void; write array results through an explicit mutable Array<T>/List<T> parameter");
        }
    }

    private void requireGpuType(String module, Ast.TypeRef type, boolean allowVoid, Set<String> resolving, String where) {
        if (type == null || type.name().equals("$infer$")) {
            throw new IllegalArgumentException(where + " must have an explicit GPU-transferable type");
        }
        if (type.isBorrow()) {
            throw new IllegalArgumentException(where + " cannot be a host borrow/reference");
        }
        if (type.isTupleType()) {
            for (Ast.TypeRef element : type.arguments()) requireGpuType(module, element, false, resolving, where);
            return;
        }
        if (type.isUnion() || type.isRecordType() || type.isStringLiteral()) {
            throw new IllegalArgumentException(where + " uses a type that is not in the current GPU ABI subset: " + type.name());
        }

        Ast.TypeAliasDecl alias = findAlias(module, type.name());
        if (alias != null) {
            if (!alias.genericParameters().isEmpty() || !type.arguments().isEmpty()) {
                throw new IllegalArgumentException(where + " uses a generic alias; GPU monomorphization is not implemented");
            }
            if (!resolving.add(alias.name())) throw new IllegalArgumentException("GPU type alias cycle involving " + alias.name());
            requireGpuType(module, alias.target(), allowVoid, resolving, where);
            resolving.remove(alias.name());
            return;
        }

        switch (type.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
                    "int", "uint", "f32", "f64", "float", "bool", "Bool" -> {
                if (!type.arguments().isEmpty()) throw new IllegalArgumentException(where + " has unexpected type arguments");
            }
            case "void" -> {
                if (!allowVoid) throw new IllegalArgumentException(where + " cannot be void");
            }
            case "Array", "List" -> {
                if (type.inferArguments() || type.arguments().size() != 1) {
                    throw new IllegalArgumentException(where + " must use an explicit Array<T>/List<T> element type");
                }
                Ast.TypeRef element = type.arguments().getFirst();
                if (element.name().equals("Array") || element.name().equals("List")) {
                    throw new IllegalArgumentException(where + " cannot use nested host list layouts in the GPU ABI");
                }
                requireGpuType(module, element, false, resolving, where + " element");
            }
            case "complex64", "complex128", "complex" -> throw new IllegalArgumentException(
                    where + " uses complex arithmetic, which is rejected until the GPU backend lowers Oreslang complex operators semantically");
            default -> throw new IllegalArgumentException(where + " type '" + type.name()
                    + "' is not GPU-transferable yet (classes, strings, bigint/decimal, Option, records and host objects are rejected)");
        }
    }

    private Ast.TypeRef resolveAlias(String module, Ast.TypeRef type, Set<String> resolving) {
        Ast.TypeAliasDecl alias = findAlias(module, type.name());
        if (alias == null) return type;
        if (!alias.genericParameters().isEmpty() || !type.arguments().isEmpty()) return type;
        String key = module + "." + alias.name();
        if (!resolving.add(key)) throw new IllegalArgumentException("GPU type alias cycle involving " + alias.name());
        Ast.TypeRef resolved = resolveAlias(module, alias.target(), resolving);
        resolving.remove(key);
        return resolved;
    }

    private Ast.TypeAliasDecl findAlias(String module, String name) {
        Ast.TypeAliasDecl local = aliases.get(module + "." + name);
        if (local != null) return local;
        if (ambiguousAliases.contains(name)) return null;
        return aliases.get(name);
    }

    private void scanStatements(String module, List<Ast.Stmt> statements) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) scanExpression(module, binding.initializer());
            else if (stmt instanceof Ast.DestructureStmt destructure) scanExpression(module, destructure.initializer());
            else if (stmt instanceof Ast.ReturnStmt returned && returned.value() != null) scanExpression(module, returned.value());
            else if (stmt instanceof Ast.ExprStmt expression) scanExpression(module, expression.expression());
            else if (stmt instanceof Ast.DeferStmt defer) scanExpression(module, defer.expression());
            else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    scanExpression(module, branch.condition());
                    scanStatements(module, branch.body());
                }
                scanStatements(module, conditional.elseBody());
            } else if (stmt instanceof Ast.TryStmt tried) {
                scanStatements(module, tried.body());
                scanStatements(module, tried.catchBody());
                scanStatements(module, tried.finallyBody());
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                scanExpression(module, loop.iterable());
                scanStatements(module, loop.body());
            } else if (stmt instanceof Ast.ForStmt loop) {
                if (loop.initializer() != null) scanStatements(module, List.of(loop.initializer()));
                if (loop.condition() != null) scanExpression(module, loop.condition());
                if (loop.update() != null) scanExpression(module, loop.update());
                scanStatements(module, loop.body());
            }
        }
    }

    private void scanExpression(String module, Ast.Expr expr) {
        if (expr == null) return;
        if (expr instanceof Ast.GpuExpr gpu) {
            validateGpuExpression(module, gpu);
            return;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            scanExpression(module, binary.left()); scanExpression(module, binary.right());
        } else if (expr instanceof Ast.UnaryExpr unary) scanExpression(module, unary.operand());
        else if (expr instanceof Ast.AssignExpr assign) {
            scanExpression(module, assign.target()); scanExpression(module, assign.value());
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            scanExpression(module, conditional.condition()); scanExpression(module, conditional.whenTrue()); scanExpression(module, conditional.whenFalse());
        } else if (expr instanceof Ast.CallExpr call) {
            scanExpression(module, call.callee());
            for (Ast.Expr arg : call.arguments()) scanExpression(module, arg);
        } else if (expr instanceof Ast.MemberExpr member) scanExpression(module, member.receiver());
        else if (expr instanceof Ast.IndexExpr indexed) {
            scanExpression(module, indexed.receiver()); scanExpression(module, indexed.index());
        } else if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr arg : created.arguments()) scanExpression(module, arg);
        } else if (expr instanceof Ast.AwaitExpr awaited) scanExpression(module, awaited.expression());
        else if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) scanExpression(module, item);
        } else if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) scanExpression(module, item);
        } else if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) scanExpression(module, field.value());
        } else if (expr instanceof Ast.LambdaExpr lambda) {
            scanStatements(module, lambda.blockBody());
        }
    }

    private void validateGpuExpression(String module, Ast.GpuExpr gpu) {
        if (gpu.mode() == Ast.GpuMode.SINGLE) {
            if (!(gpu.expression() instanceof Ast.LambdaExpr lambda)) {
                throw new IllegalArgumentException("'gpu' expression placement requires a direct lambda");
            }
            validateGpuLambda(module, lambda);
            return;
        }
        List<Ast.Expr> elements = gpuParallelElements(gpu.expression());
        if (elements.isEmpty()) throw new IllegalArgumentException("'gpu parallel' requires at least one lambda");
        for (Ast.Expr element : elements) {
            if (!(element instanceof Ast.LambdaExpr lambda)) {
                throw new IllegalArgumentException("'gpu parallel' accepts only direct lambda literals");
            }
            validateGpuLambda(module, lambda);
        }
    }

    private List<Ast.Expr> gpuParallelElements(Ast.Expr expression) {
        if (expression instanceof Ast.ListExpr list) return list.elements();
        if (expression instanceof Ast.TupleExpr tuple) return tuple.elements();
        if (expression instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr name
                && name.name().equals("List")
                && member.member().equals("of")) {
            return call.arguments();
        }
        throw new IllegalArgumentException("'gpu parallel' requires a list/tuple literal or List.of(...)");
    }

    private void validateGpuLambda(String module, Ast.LambdaExpr lambda) {
        Scope scope = new Scope(null);
        for (Ast.Param param : lambda.parameters()) {
            if (param.structural()) throw new IllegalArgumentException("GPU lambdas cannot use structural parameters");
            if (param.type().name().equals("$infer$")) {
                throw new IllegalArgumentException("GPU lambda parameter '" + param.name()
                        + "' must have an explicit GPU-transferable type");
            }
            if (param.type().isTupleType()) {
                throw new IllegalArgumentException("GPU lambda parameter '" + param.name() + "' cannot use a tuple device ABI");
            }
            requireGpuType(module, param.type(), false, new HashSet<>(), "GPU lambda parameter " + param.name());
            scope.define(param.name());
        }
        validateGpuStatements(module, scope, lambda.blockBody(), true);
    }

    private void validateGpuStatements(String module, Scope scope, List<Ast.Stmt> statements, boolean lambda) {
        Scope block = new Scope(scope);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                validateGpuExpressionTree(module, block, binding.initializer());
                block.define(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt) {
                throw new IllegalArgumentException("destructuring is not supported inside GPU code until tuple/register lowering is implemented");
            } else if (stmt instanceof Ast.ReturnStmt returned) {
                if (returned.value() != null) validateGpuExpressionTree(module, block, returned.value());
            } else if (stmt instanceof Ast.ExprStmt expression) {
                validateGpuExpressionTree(module, block, expression.expression());
            } else if (stmt instanceof Ast.DeferStmt) {
                throw new IllegalArgumentException("defer is not supported inside GPU code");
            } else if (stmt instanceof Ast.TryStmt) {
                throw new IllegalArgumentException("try/catch/finally is not supported inside GPU code");
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    validateGpuExpressionTree(module, block, branch.condition());
                    validateGpuStatements(module, block, branch.body(), lambda);
                }
                validateGpuStatements(module, block, conditional.elseBody(), lambda);
            } else if (stmt instanceof Ast.ForOfStmt) {
                throw new IllegalArgumentException("for-of is not supported inside GPU code; use an index-based for loop with an explicit bound");
            } else if (stmt instanceof Ast.ForStmt loop) {
                Scope loopScope = new Scope(block);
                if (loop.initializer() instanceof Ast.BindingStmt binding) {
                    validateGpuExpressionTree(module, loopScope, binding.initializer());
                    loopScope.define(binding.name());
                } else if (loop.initializer() instanceof Ast.ExprStmt expression) {
                    validateGpuExpressionTree(module, loopScope, expression.expression());
                } else if (loop.initializer() != null) {
                    throw new IllegalArgumentException(
                            "GPU for-loop initializer must be a scalar binding or expression");
                }
                if (loop.condition() != null) validateGpuExpressionTree(module, loopScope, loop.condition());
                if (loop.update() != null) validateGpuExpressionTree(module, loopScope, loop.update());
                validateGpuStatements(module, loopScope, loop.body(), lambda);
            }
        }
    }

    private void validateGpuExpressionTree(String module, Scope scope, Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr literal) {
            if (literal.value() instanceof String) throw new IllegalArgumentException("strings are not in the current GPU kernel subset");
            return;
        }
        if (expr instanceof Ast.NameExpr name) {
            if (!scope.contains(name.name())) {
                throw new IllegalArgumentException("GPU code cannot capture or read host/global value '" + name.name()
                        + "'; pass GPU-transferable values as parameters");
            }
            return;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            validateGpuExpressionTree(module, scope, binary.left());
            validateGpuExpressionTree(module, scope, binary.right());
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            if (unary.operator().equals("&") || unary.operator().equals("&mut")) {
                throw new IllegalArgumentException("host borrows/references cannot cross into GPU code");
            }
            validateGpuExpressionTree(module, scope, unary.operand());
            return;
        }
        if (expr instanceof Ast.AssignExpr assign) {
            if (assign.target() instanceof Ast.MemberExpr) {
                throw new IllegalArgumentException("GPU code cannot mutate host object fields");
            }
            validateGpuExpressionTree(module, scope, assign.target());
            validateGpuExpressionTree(module, scope, assign.value());
            return;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            validateGpuExpressionTree(module, scope, conditional.condition());
            validateGpuExpressionTree(module, scope, conditional.whenTrue());
            validateGpuExpressionTree(module, scope, conditional.whenFalse());
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            requireGpuCallTarget(module, call);
            for (Ast.Expr arg : call.arguments()) {
                if (!sideEffectFreeGpuArgument(arg)) {
                    throw new IllegalArgumentException(
                            "GPU call arguments must be side-effect-free; bind complex work to a local before the call");
                }
                validateGpuExpressionTree(module, scope, arg);
            }
            return;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            validateGpuExpressionTree(module, scope, indexed.receiver());
            validateGpuExpressionTree(module, scope, indexed.index());
            return;
        }
        if (expr instanceof Ast.ListExpr) {
            throw new IllegalArgumentException("GPU code cannot allocate list/array literals; pass preallocated buffers as Array<T>/List<T> parameters");
        }
        if (expr instanceof Ast.TupleExpr) {
            throw new IllegalArgumentException("GPU tuple values are rejected until tuple/register lowering is implemented");
        }
        if (expr instanceof Ast.GpuExpr) {
            throw new IllegalArgumentException("nested gpu placement inside an already-GPU callable is redundant and rejected");
        }
        if (expr instanceof Ast.MemberExpr) {
            throw new IllegalArgumentException("GPU code cannot dereference host object/module members except as a statically checked GPU call target");
        }
        if (expr instanceof Ast.NewExpr) throw new IllegalArgumentException("class/object allocation is not supported inside GPU code");
        if (expr instanceof Ast.ObjectExpr) throw new IllegalArgumentException("obj records are not supported inside GPU code");
        if (expr instanceof Ast.AwaitExpr) throw new IllegalArgumentException("await is not supported inside GPU code");
        if (expr instanceof Ast.LambdaExpr) throw new IllegalArgumentException("nested closures are not supported inside GPU code");
        throw new IllegalArgumentException("expression is not supported inside GPU code: " + expr.getClass().getSimpleName());
    }

    private boolean sideEffectFreeGpuArgument(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return true;
        if (expr instanceof Ast.UnaryExpr unary) return sideEffectFreeGpuArgument(unary.operand());
        if (expr instanceof Ast.BinaryExpr binary) {
            return sideEffectFreeGpuArgument(binary.left()) && sideEffectFreeGpuArgument(binary.right());
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return sideEffectFreeGpuArgument(conditional.condition())
                    && sideEffectFreeGpuArgument(conditional.whenTrue())
                    && sideEffectFreeGpuArgument(conditional.whenFalse());
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            return sideEffectFreeGpuArgument(indexed.receiver())
                    && sideEffectFreeGpuArgument(indexed.index());
        }
        return false;
    }

    private void requireGpuCallTarget(String module, Ast.CallExpr call) {
        if (call.callee() instanceof Ast.NameExpr name) {
            Ast.FunctionDecl fn = functions.get(module + "." + name.name());
            if (fn == null && !ambiguousFunctions.contains(name.name())) fn = functions.get(name.name());
            if (fn == null || !Ast.hasGpuPlacement(fn.annotations())) {
                throw new IllegalArgumentException("GPU code may call only statically known gpu functions; '" + name.name() + "' is not gpu");
            }
            return;
        }
        if (call.callee() instanceof Ast.MemberExpr member && member.receiver() instanceof Ast.NameExpr owner) {
            Ast.ModuleDecl moduleDecl = modules.get(owner.name());
            if (moduleDecl != null) {
                Ast.FunctionDecl fn = functions.get(owner.name() + "." + member.member());
                if (fn == null || !Ast.hasGpuPlacement(fn.annotations())) {
                    throw new IllegalArgumentException("GPU code may call only gpu module functions; " + owner.name() + "." + member.member() + " is not gpu");
                }
                return;
            }
            Ast.ClassDecl klass = findClass(owner.name());
            if (klass != null) {
                Ast.MethodDecl match = null;
                for (Ast.MethodDecl method : klass.methods()) {
                    if (method.isStatic() && method.name().equals(member.member()) && method.parameters().size() == call.arguments().size()) {
                        match = method;
                        break;
                    }
                }
                if (match == null || !Ast.hasGpuPlacement(match.annotations())) {
                    throw new IllegalArgumentException("GPU code may call only gpu static class functions; " + owner.name() + "." + member.member() + " is not gpu");
                }
                return;
            }
        }
        throw new IllegalArgumentException("GPU call target must be a statically known gpu fnc/static fnc; dynamic closures and host methods are rejected");
    }

    private Ast.ClassDecl findClass(String name) {
        if (ambiguousClasses.contains(name)) return null;
        return classes.get(name);
    }

    private static final class Scope {
        private final Scope parent;
        private final Set<String> names = new HashSet<>();

        private Scope(Scope parent) { this.parent = parent; }

        private void define(String name) {
            if (!names.add(name)) throw new IllegalArgumentException("duplicate GPU local binding '" + name + "'");
        }

        private boolean contains(String name) {
            return names.contains(name) || (parent != null && parent.contains(name));
        }
    }
}
