package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative write-effect analysis for {@code pure} fnc/routine declarations.
 *
 * <p>Oreslang purity is intentionally not mathematical referential transparency:
 * a pure callable may read module/global/imported/captured state. The guarantee is
 * that execution cannot mutate state it did not create and cannot mutate caller
 * inputs. Unknown calls fail closed because their write effects are not proven.</p>
 */
public final class PureEffectChecker {
    private enum SlotOrigin { LOCAL, PARAM, EXTERNAL }
    private enum ValueOrigin { OWNED, EXTERNAL_ALIAS }

    private record Binding(SlotOrigin slot, ValueOrigin value) { }

    private static final class Scope {
        private final Scope parent;
        private final Map<String, Binding> bindings = new HashMap<>();

        private Scope(Scope parent) { this.parent = parent; }

        private void define(String name, Binding binding) {
            if (bindings.putIfAbsent(name, binding) != null) {
                throw error("duplicate binding '" + name + "' while checking pure effects");
            }
        }

        private Binding lookup(String name) {
            Binding binding = bindings.get(name);
            return binding != null ? binding : parent == null ? null : parent.lookup(name);
        }
    }

    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Map<String, Set<String>> moduleBindings = new HashMap<>();

    private PureEffectChecker(Ast.Program program) {
        index(program);
    }

    public static Ast.Program check(Ast.Program program) {
        PureEffectChecker checker = new PureEffectChecker(program);
        checker.validate(program);
        return program;
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            Set<String> bindings = new HashSet<>();
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    functions.put(module.name() + "." + fn.name(), fn);
                    Ast.FunctionDecl previous = functions.putIfAbsent(fn.name(), fn);
                    if (previous != null && previous != fn) {
                        functions.remove(fn.name());
                        ambiguousFunctions.add(fn.name());
                    }
                } else if (declaration instanceof Ast.FieldDecl field) {
                    bindings.add(field.name());
                }
            }
            moduleBindings.put(module.name(), Set.copyOf(bindings));
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn && fn.pure()) {
                    checkFunction(module.name(), fn);
                }
            }
        }
    }

    private void checkFunction(String module, Ast.FunctionDecl fn) {
        Scope external = new Scope(null);
        for (String name : moduleBindings.getOrDefault(module, Set.of())) {
            external.define(name, new Binding(SlotOrigin.EXTERNAL, ValueOrigin.EXTERNAL_ALIAS));
        }

        Scope scope = new Scope(external);
        for (Ast.Param param : fn.parameters()) {
            if (param.mutable()) {
                throw error("pure callable '" + fn.name()
                        + "' cannot declare mutable parameter '" + param.name() + "'");
            }
            scope.define(param.name(), new Binding(SlotOrigin.PARAM, ValueOrigin.EXTERNAL_ALIAS));
        }
        checkStatements(fn.body(), scope, module, fn.name());
    }

    private void checkStatements(List<Ast.Stmt> statements, Scope scope, String module, String callable) {
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.BindingStmt binding) {
                checkExpr(binding.initializer(), scope, module, callable);
                scope.define(binding.name(), new Binding(
                        SlotOrigin.LOCAL,
                        aliasesExternal(binding.initializer(), scope)
                                ? ValueOrigin.EXTERNAL_ALIAS
                                : ValueOrigin.OWNED));
                continue;
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                checkExpr(destructure.initializer(), scope, module, callable);
                ValueOrigin value = aliasesExternal(destructure.initializer(), scope)
                        ? ValueOrigin.EXTERNAL_ALIAS
                        : ValueOrigin.OWNED;
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard()) {
                        scope.define(binding.name(), new Binding(SlotOrigin.LOCAL, value));
                    }
                }
                continue;
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                if (returned.value() != null) checkExpr(returned.value(), scope, module, callable);
                continue;
            }
            if (statement instanceof Ast.ExprStmt expression) {
                checkExpr(expression.expression(), scope, module, callable);
                continue;
            }
            if (statement instanceof Ast.DeferStmt defer) {
                checkExpr(defer.expression(), scope, module, callable);
                continue;
            }
            if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    checkExpr(branch.condition(), scope, module, callable);
                    checkStatements(branch.body(), new Scope(scope), module, callable);
                }
                checkStatements(conditional.elseBody(), new Scope(scope), module, callable);
                continue;
            }
            if (statement instanceof Ast.TryStmt attempted) {
                checkStatements(attempted.body(), new Scope(scope), module, callable);
                Scope caught = new Scope(scope);
                caught.define(attempted.errorName(), new Binding(SlotOrigin.LOCAL, ValueOrigin.OWNED));
                checkStatements(attempted.catchBody(), caught, module, callable);
                checkStatements(attempted.finallyBody(), new Scope(scope), module, callable);
                continue;
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                checkExpr(loop.iterable(), scope, module, callable);
                Scope body = new Scope(scope);
                body.define(loop.bindingName(), new Binding(
                        SlotOrigin.LOCAL,
                        aliasesExternal(loop.iterable(), scope)
                                ? ValueOrigin.EXTERNAL_ALIAS
                                : ValueOrigin.OWNED));
                checkStatements(loop.body(), body, module, callable);
                continue;
            }
            if (statement instanceof Ast.ForStmt loop) {
                Scope body = new Scope(scope);
                if (loop.initializer() != null) checkStatements(List.of(loop.initializer()), body, module, callable);
                if (loop.condition() != null) checkExpr(loop.condition(), body, module, callable);
                if (loop.update() != null) checkExpr(loop.update(), body, module, callable);
                checkStatements(loop.body(), body, module, callable);
            }
        }
    }

    private void checkExpr(Ast.Expr expr, Scope scope, String module, String callable) {
        if (expr == null || expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return;

        if (expr instanceof Ast.AssignExpr assignment) {
            assertWritableTarget(assignment.target(), scope, callable);
            checkExpr(assignment.value(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            checkExpr(binary.left(), scope, module, callable);
            checkExpr(binary.right(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            checkExpr(unary.operand(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            checkExpr(conditional.condition(), scope, module, callable);
            checkExpr(conditional.whenTrue(), scope, module, callable);
            checkExpr(conditional.whenFalse(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            for (Ast.Expr argument : call.arguments()) checkExpr(argument, scope, module, callable);
            assertPureCall(call.callee(), module, callable);
            return;
        }
        if (expr instanceof Ast.RuntimeCallExpr runtime) {
            for (Ast.Expr argument : runtime.arguments()) checkExpr(argument, scope, module, callable);
            if (runtime.operation().equals("take")
                    && aliasesExternal(runtime.arguments().getFirst(), scope)) {
                throw error("pure callable '" + callable
                        + "' cannot rt take caller/external state");
            }
            if (runtime.operation().equals("recover")) {
                throw error("pure callable '" + callable
                        + "' cannot use rt recover until recovery effects are typed");
            }
            return;
        }
        if (expr instanceof Ast.MemberExpr member) {
            checkExpr(member.receiver(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            checkExpr(indexed.receiver(), scope, module, callable);
            checkExpr(indexed.index(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr argument : created.arguments()) checkExpr(argument, scope, module, callable);
            return;
        }
        if (expr instanceof Ast.AwaitExpr) {
            throw error("pure callable '" + callable
                    + "' cannot await until asynchronous effects are typed");
        }
        if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) checkExpr(item, scope, module, callable);
            return;
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) checkExpr(item, scope, module, callable);
            return;
        }
        if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                if (field.dynamicName() != null) checkExpr(field.dynamicName(), scope, module, callable);
                checkExpr(field.value(), scope, module, callable);
            }
            return;
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            Scope nested = new Scope(scope);
            for (Ast.Param param : lambda.parameters()) {
                if (param.mutable()) {
                    throw error("lambda created by pure callable '" + callable
                            + "' cannot expose mutable parameter '" + param.name() + "'");
                }
                nested.define(param.name(), new Binding(SlotOrigin.PARAM, ValueOrigin.EXTERNAL_ALIAS));
            }
            if (lambda.expressionBody() != null) checkExpr(lambda.expressionBody(), nested, module, callable);
            if (lambda.blockBody() != null) checkStatements(lambda.blockBody(), nested, module, callable);
        }
    }

    private void assertPureCall(Ast.Expr callee, String module, String callable) {
        if (callee instanceof Ast.NameExpr name) {
            if (name.name().equals("Some") || name.name().equals("Ok") || name.name().equals("Err")) return;
            Ast.FunctionDecl target = ambiguousFunctions.contains(name.name()) ? null : functions.get(name.name());
            if (target != null && target.pure()) return;
            throw error("pure callable '" + callable + "' cannot call unproven-effect callable '"
                    + name.name() + "'");
        }
        if (callee instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace) {
            Ast.FunctionDecl target = functions.get(namespace.name() + "." + member.member());
            if (target != null && target.pure()) return;
        }
        throw error("pure callable '" + callable
                + "' may call only statically known pure fnc/routine declarations; method, imported, runtime-object, and indirect calls fail closed");
    }

    private void assertWritableTarget(Ast.Expr target, Scope scope, String callable) {
        Ast.NameExpr root = rootName(target);
        if (root == null) {
            throw error("pure callable '" + callable
                    + "' cannot mutate an unrooted/unknown target");
        }
        Binding binding = scope.lookup(root.name());
        if (binding == null) {
            throw error("pure callable '" + callable
                    + "' cannot write external binding '" + root.name() + "'");
        }
        if (target instanceof Ast.NameExpr) {
            if (binding.slot() == SlotOrigin.PARAM) {
                throw error("pure callable '" + callable
                        + "' cannot modify input parameter '" + root.name() + "'");
            }
            if (binding.slot() == SlotOrigin.EXTERNAL) {
                throw error("pure callable '" + callable
                        + "' cannot write outside binding '" + root.name() + "'");
            }
            return;
        }
        if (binding.slot() != SlotOrigin.LOCAL || binding.value() == ValueOrigin.EXTERNAL_ALIAS) {
            throw error("pure callable '" + callable
                    + "' cannot mutate state reachable from parameter/outside binding '" + root.name() + "'");
        }
    }

    private Ast.NameExpr rootName(Ast.Expr expr) {
        if (expr instanceof Ast.NameExpr name) return name;
        if (expr instanceof Ast.MemberExpr member) return rootName(member.receiver());
        if (expr instanceof Ast.IndexExpr indexed) return rootName(indexed.receiver());
        return null;
    }

    private boolean aliasesExternal(Ast.Expr expr, Scope scope) {
        if (expr instanceof Ast.NameExpr name) {
            Binding binding = scope.lookup(name.name());
            return binding != null && (binding.slot() != SlotOrigin.LOCAL
                    || binding.value() == ValueOrigin.EXTERNAL_ALIAS);
        }
        if (expr instanceof Ast.MemberExpr member) return aliasesExternal(member.receiver(), scope);
        if (expr instanceof Ast.IndexExpr indexed) return aliasesExternal(indexed.receiver(), scope);
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return aliasesExternal(conditional.whenTrue(), scope)
                    || aliasesExternal(conditional.whenFalse(), scope);
        }
        if (expr instanceof Ast.RuntimeCallExpr runtime) {
            return switch (runtime.operation()) {
                case "copy", "share" -> false;
                case "borrow", "take" -> !runtime.arguments().isEmpty()
                        && aliasesExternal(runtime.arguments().getFirst(), scope);
                default -> true;
            };
        }
        return false;
    }

    private static IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Oreslang pure effect error: " + message);
    }
}
