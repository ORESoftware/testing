package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ownership / borrow / closure-capture analysis.
 *
 * This pass is intentionally independent of the interpreter. Borrows erase at
 * runtime; compile-time ownership remains authoritative for every backend.
 *
 * Current model:
 * - primitive immutable values are Copy;
 * - class/list/object/function values are move-only by default;
 * - by-value call/binding/return moves move-only values;
 * - Borrow<T> permits shared immutable borrows via borrow(value);
 * - BorrowMut<T> is exclusive via borrow_mut(value) and requires a mutable owner;
 * - Bar mut b makes an owned parameter mutable inside the callee;
 * - escaping closures own non-Copy captures and mutable captures;
 * - closures may not capture a borrow (pass it as a lambda parameter instead);
 * - moving an outer value from a repeating loop is rejected conservatively.
 */
public final class OwnershipChecker {
    private final Map<String, Ast.FunctionDecl> functions = new LinkedHashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new LinkedHashMap<>();
    private final Set<String> ambiguousFunctions = new LinkedHashSet<>();
    private final Set<String> ambiguousClasses = new LinkedHashSet<>();

    private OwnershipChecker(Ast.Program program) {
        index(program);
    }

    public static Ast.Program check(Ast.Program program) {
        OwnershipChecker checker = new OwnershipChecker(program);
        checker.validate(program);
        return program;
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) index(classes, ambiguousClasses, module.name(), klass.name(), klass);
            }
        }
    }

    private static <T> void index(Map<String,T> map, Set<String> ambiguous, String module, String name, T value) {
        map.put(module + "." + name, value);
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            map.remove(name);
            ambiguous.add(name);
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(fn);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(klass);
            }
        }
    }

    private void checkFunction(Ast.FunctionDecl fn) {
        Scope scope = new Scope(null, fn.nonLexical());
        for (Ast.Param param : fn.parameters()) {
            scope.define(param.name(), stateForParam(param));
        }
        checkBlock(fn.body(), scope, fn.returnType());
        scope.close();
    }

    private void checkClass(Ast.ClassDecl klass) {
        for (Ast.MethodDecl method : klass.methods()) {
            Scope scope = new Scope(null);
            if (!method.isStatic()) {
                // Receiver is immutable unless a future explicit "mut self"
                // syntax is introduced. Methods can still mutate through an
                // explicit BorrowMut<T> parameter.
                scope.define("self", new VarState(Ast.TypeRef.simple(klass.name()), false, ValueKind.IMM_BORROW, Origin.PARAM));
            }
            for (Ast.Param param : method.parameters()) scope.define(param.name(), stateForParam(param));
            checkBlock(method.body(), scope, method.returnType());
            scope.close();
        }
    }

    private VarState stateForParam(Ast.Param param) {
        ValueKind kind = param.structural() && !param.type().isBorrow() ? ValueKind.IMM_BORROW : kindOfType(param.type());
        boolean mutableOwner = param.mutable();
        if (param.type().isBorrow() && param.type().mutableBorrow()) mutableOwner = false;
        return new VarState(param.type(), mutableOwner, kind, Origin.PARAM);
    }

    private void checkBlock(List<Ast.Stmt> body, Scope parent, Ast.TypeRef returnType) {
        Scope scope = new Scope(parent);
        for (Ast.Stmt stmt : body) checkStatement(stmt, scope, returnType);
        scope.close();
    }

    private void checkStatement(Ast.Stmt stmt, Scope scope, Ast.TypeRef returnType) {
        if (stmt instanceof Ast.BindingStmt binding) {
            checkBinding(binding, scope);
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            ValueInfo source = checkExpr(destructure.initializer(), scope, true);
            for (Ast.DestructureBinding binding : destructure.bindings()) {
                scope.define(binding.name(), new VarState(
                        Ast.TypeRef.inferred(),
                        binding.kind() == Ast.BindingKind.LET,
                        source.kind == ValueKind.COPY ? ValueKind.COPY : ValueKind.MOVE_ONLY,
                        Origin.LOCAL));
            }
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() != null) {
                if (ret.value() instanceof Ast.CallExpr call && isBorrowIntrinsicCall(call, scope)) {
                    if (call.arguments().size() != 1) {
                        throw error(((Ast.NameExpr) call.callee()).name() + " expects exactly one value");
                    }
                    VarState owner = borrowOwner(call.arguments().getFirst(), scope);
                    if (owner.origin == Origin.LOCAL) {
                        throw error("cannot return a borrow of local value '" + owner.debugName + "'; borrowed value would outlive its owner");
                    }
                }
                checkExpr(ret.value(), scope, true);
            }
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) {
            checkExpr(expression.expression(), scope, false);
            return;
        }
        if (stmt instanceof Ast.DeferStmt defer) {
            checkDeferred(defer.expression(), scope);
            return;
        }
        if (stmt instanceof Ast.IfStmt conditional) {
            Map<VarState, StateSnapshot> base = stateSnapshot(scope);
            List<Map<VarState, StateSnapshot>> exits = new ArrayList<>();

            for (Ast.IfBranch branch : conditional.branches()) {
                restoreState(base);
                checkExpr(branch.condition(), scope, false);
                checkBlock(branch.body(), scope, returnType);
                exits.add(stateSnapshot(scope));
            }

            restoreState(base);
            if (!conditional.elseBody().isEmpty()) {
                checkBlock(conditional.elseBody(), scope, returnType);
                exits.add(stateSnapshot(scope));
            } else {
                exits.add(base); // condition may be false with no else
            }

            mergeBranchState(base, exits);
            return;
        }
        if (stmt instanceof Ast.TryStmt attempted) {
            checkBlock(attempted.body(), scope, returnType);
            Scope caught = new Scope(scope);
            caught.define(attempted.errorName(), new VarState(Ast.TypeRef.inferred(), false, ValueKind.MOVE_ONLY, Origin.LOCAL));
            checkBlock(attempted.catchBody(), caught, returnType);
            caught.close();
            checkBlock(attempted.finallyBody(), scope, returnType);
            return;
        }
        if (stmt instanceof Ast.ForOfStmt loop) {
            checkExpr(loop.iterable(), scope, false);
            Map<VarState,Boolean> before = movedSnapshot(scope);
            Scope loopScope = new Scope(scope);
            loopScope.define(loop.bindingName(), new VarState(Ast.TypeRef.inferred(), loop.bindingKind() == Ast.BindingKind.LET, ValueKind.MOVE_ONLY, Origin.LOCAL));
            checkBlock(loop.body(), loopScope, returnType);
            loopScope.close();
            rejectLoopMoves(before, scope);
            return;
        }
        if (stmt instanceof Ast.ForStmt loop) {
            Scope loopScope = new Scope(scope);
            if (loop.initializer() != null) checkStatement(loop.initializer(), loopScope, returnType);
            if (loop.condition() != null) checkExpr(loop.condition(), loopScope, false);
            Map<VarState,Boolean> before = movedSnapshot(scope);
            checkBlock(loop.body(), loopScope, returnType);
            if (loop.update() != null) checkExpr(loop.update(), loopScope, false);
            rejectLoopMoves(before, scope);
            loopScope.close();
        }
    }

    private void checkBinding(Ast.BindingStmt binding, Scope scope) {
        boolean recursiveLambda = binding.initializer() instanceof Ast.LambdaExpr;
        VarState placeholder = null;
        if (recursiveLambda) {
            placeholder = new VarState(
                    binding.declaredType() == null ? Ast.TypeRef.inferred() : binding.declaredType(),
                    binding.kind() == Ast.BindingKind.LET,
                    ValueKind.MOVE_ONLY,
                    Origin.LOCAL);
            scope.define(binding.name(), placeholder);
        }

        ValueInfo value;
        if (binding.initializer() instanceof Ast.CallExpr call && isBorrowIntrinsicCall(call, scope)) {
            boolean mutableBorrow = ((Ast.NameExpr) call.callee()).name().equals("borrow_mut");
            if (call.arguments().size() != 1) throw error((mutableBorrow ? "borrow_mut" : "borrow") + " expects exactly one value");
            VarState owner = borrowOwner(call.arguments().getFirst(), scope);
            beginPersistentBorrow(owner, mutableBorrow);
            value = new ValueInfo(
                    Ast.TypeRef.borrowed(owner.type, mutableBorrow),
                    mutableBorrow ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW,
                    owner);
        } else if (binding.initializer() instanceof Ast.LambdaExpr lambda) {
            value = checkLambda(lambda, scope, binding.name());
        } else {
            value = checkExpr(binding.initializer(), scope, true);
        }

        if (recursiveLambda) {
            placeholder.type = binding.declaredType() == null ? value.type : binding.declaredType();
            placeholder.kind = value.kind;
            placeholder.borrowSource = value.borrowSource;
            return;
        }

        VarState state = new VarState(
                binding.declaredType() == null ? value.type : binding.declaredType(),
                binding.kind() == Ast.BindingKind.LET,
                value.kind,
                Origin.LOCAL);
        state.borrowSource = value.borrowSource;
        scope.define(binding.name(), state);
    }

    private ValueInfo checkExpr(Ast.Expr expr, Scope scope, boolean consuming) {
        if (expr instanceof Ast.LiteralExpr literal) {
            return new ValueInfo(inferLiteralType(literal.value()), ValueKind.COPY, null);
        }
        if (expr instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            if (state == null) return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.COPY, null); // function/module/global
            state.debugName = name.name();
            requireUsable(state, name.name(), false);
            if (consuming && state.kind == ValueKind.MOVE_ONLY) move(state, name.name());
            if (consuming && state.kind == ValueKind.MUT_BORROW) move(state, name.name());
            return new ValueInfo(state.type, state.kind, state.borrowSource);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            return checkExpr(unary.operand(), scope, false);
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            checkAssignmentTarget(assignment.target(), scope);
            return checkExpr(assignment.value(), scope, true);
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            checkExpr(binary.left(), scope, false);
            checkExpr(binary.right(), scope, false);
            return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.COPY, null);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            checkExpr(conditional.condition(), scope, false);
            Map<VarState, StateSnapshot> base = stateSnapshot(scope);

            ValueInfo left = checkExpr(conditional.whenTrue(), scope, consuming);
            Map<VarState, StateSnapshot> leftExit = stateSnapshot(scope);

            restoreState(base);
            ValueInfo right = checkExpr(conditional.whenFalse(), scope, consuming);
            Map<VarState, StateSnapshot> rightExit = stateSnapshot(scope);

            mergeBranchState(base, List.of(leftExit, rightExit));
            return left.kind == ValueKind.COPY && right.kind == ValueKind.COPY
                    ? left
                    : new ValueInfo(left.type, ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.CallExpr call) {
            return checkCall(call, scope);
        }
        if (expr instanceof Ast.MemberExpr member) {
            checkExpr(member.receiver(), scope, false);
            if (member.receiver() instanceof Ast.NameExpr builtin
                    && builtin.name().equals("actor")
                    && member.member().equals("self")) {
                return new ValueInfo(Ast.TypeRef.simple("ActorRef"), ValueKind.COPY, null);
            }
            return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            checkExpr(indexed.receiver(), scope, false);
            checkExpr(indexed.index(), scope, false);
            return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr arg : created.arguments()) checkExpr(arg, scope, true);
            return new ValueInfo(created.type(), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            ValueInfo future = checkExpr(awaited.expression(), scope, true);
            Ast.TypeRef resultType = future.type;
            if (resultType != null
                    && resultType.name().equals("Future")
                    && resultType.arguments().size() == 1) {
                resultType = resultType.arguments().getFirst();
            }
            return new ValueInfo(resultType, kindOfType(resultType), null);
        }
        if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) checkExpr(item, scope, true);
            return new ValueInfo(Ast.TypeRef.simple("Array"), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            boolean copy = true;
            for (Ast.Expr item : tuple.elements()) {
                ValueInfo info = checkExpr(item, scope, true);
                copy &= info.kind == ValueKind.COPY;
            }
            return new ValueInfo(Ast.TypeRef.inferred(), copy ? ValueKind.COPY : ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) checkExpr(field.value(), scope, true);
            return new ValueInfo(Ast.TypeRef.simple("obj"), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.LambdaExpr lambda) return checkLambda(lambda, scope, null);
        return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
    }

    private ValueInfo checkCall(Ast.CallExpr call, Scope scope) {
        if (call.callee() instanceof Ast.NameExpr intrinsic
                && scope.lookup(intrinsic.name()) == null
                && findFunction(intrinsic.name()) == null
                && isOwnershipIntrinsic(intrinsic.name())) {
            if (call.arguments().size() != 1) throw error(intrinsic.name() + " expects exactly one value");
            Ast.Expr argument = call.arguments().getFirst();
            return switch (intrinsic.name()) {
                case "borrow", "borrow_mut" -> {
                    boolean mutable = intrinsic.name().equals("borrow_mut");
                    VarState owner = borrowOwner(argument, scope);
                    validateBorrow(owner, mutable);
                    yield new ValueInfo(
                            Ast.TypeRef.borrowed(owner.type, mutable),
                            mutable ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW,
                            owner);
                }
                case "take" -> checkExpr(argument, scope, true);
                case "copy" -> {
                    ValueInfo source = checkExpr(argument, scope, false);
                    if (source.kind != ValueKind.COPY) {
                        throw error("copy(value) requires a Copy value; heap/class values need explicit copy semantics");
                    }
                    yield source;
                }
                case "share" -> {
                    checkExpr(argument, scope, false);
                    yield new ValueInfo(Ast.TypeRef.simple("Shared"), ValueKind.COPY, null);
                }
                default -> throw error("unknown ownership intrinsic '" + intrinsic.name() + "'");
            };
        }

        if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr builtin
                && builtin.name().equals("process")) {
            checkExpr(member.receiver(), scope, false);
            return switch (member.member()) {
                case "share_readonly" -> {
                    for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, false);
                    yield new ValueInfo(Ast.TypeRef.simple("Shared"), ValueKind.COPY, null);
                }
                case "gc" -> {
                    for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, false);
                    yield new ValueInfo(Ast.TypeRef.simple("obj"), ValueKind.MOVE_ONLY, null);
                }
                default -> {
                    for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, true);
                    yield new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
                }
            };
        }

        if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr builtin
                && builtin.name().equals("actor")) {
            checkExpr(member.receiver(), scope, false);
            return switch (member.member()) {
                case "spawn" -> {
                    for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, false);
                    yield new ValueInfo(Ast.TypeRef.simple("ActorRef"), ValueKind.COPY, null);
                }
                case "singleton" -> {
                    for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, false);
                    yield new ValueInfo(Ast.TypeRef.simple("ActorRef"), ValueKind.COPY, null);
                }
                case "send" -> {
                    // Erlang-style send freezes/copies the message. Sending is
                    // therefore a read, not an ownership transfer.
                    for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, false);
                    yield new ValueInfo(Ast.TypeRef.simple("void"), ValueKind.COPY, null);
                }
                case "monitor" -> {
                    for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, false);
                    yield new ValueInfo(Ast.TypeRef.simple("MonitorRef"), ValueKind.COPY, null);
                }
                case "demonitor" -> {
                    for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, false);
                    yield new ValueInfo(Ast.TypeRef.simple("bool"), ValueKind.COPY, null);
                }
                case "stop", "join", "status" -> {
                    for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, false);
                    yield new ValueInfo(
                            member.member().equals("status") ? Ast.TypeRef.simple("obj") : Ast.TypeRef.simple("bool"),
                            member.member().equals("status") ? ValueKind.MOVE_ONLY : ValueKind.COPY,
                            null);
                }
                default -> {
                    for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, true);
                    yield new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
                }
            };
        }

        if (call.callee() instanceof Ast.NameExpr name) {
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) {
                checkArguments(call.arguments(), fn.parameters(), scope, "function " + fn.name());
                if (fn.async()) {
                    Ast.TypeRef future = new Ast.TypeRef("Future", List.of(fn.returnType()), false);
                    return new ValueInfo(future, ValueKind.MOVE_ONLY, null);
                }
                return new ValueInfo(fn.returnType(), kindOfType(fn.returnType()), null);
            }
        }

        if (call.callee() instanceof Ast.MemberExpr member) {
            checkExpr(member.receiver(), scope, false);
            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            Ast.MethodDecl method = klass == null ? null : findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
            if (method != null) {
                checkArguments(call.arguments(), method.parameters(), scope, "method " + method.name());
                if (method.async()) {
                    Ast.TypeRef future = new Ast.TypeRef("Future", List.of(method.returnType()), false);
                    return new ValueInfo(future, ValueKind.MOVE_ONLY, null);
                }
                return new ValueInfo(method.returnType(), kindOfType(method.returnType()), null);
            }
        }

        checkExpr(call.callee(), scope, false);
        for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, true);
        return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
    }

    private void checkArguments(List<Ast.Expr> arguments, List<Ast.Param> params, Scope scope, String callable) {
        if (arguments.size() != params.size()) return; // arity is TypeChecker's responsibility
        for (int i = 0; i < arguments.size(); i++) {
            Ast.Expr arg = arguments.get(i);
            Ast.Param param = params.get(i);
            if (param.structural() && !param.type().isBorrow()) {
                checkExpr(arg, scope, false);
                continue;
            }
            if (param.type().isBorrow()) {
                boolean mutable = param.type().mutableBorrow();
                if (arg instanceof Ast.CallExpr borrowCall && isBorrowIntrinsicCall(borrowCall, scope)) {
                    String intrinsic = ((Ast.NameExpr) borrowCall.callee()).name();
                    boolean suppliedMutable = intrinsic.equals("borrow_mut");
                    if (mutable && !suppliedMutable) {
                        throw error(callable + " argument " + (i + 1) + " requires borrow_mut(value)");
                    }
                    if (borrowCall.arguments().size() != 1) {
                        throw error(intrinsic + " expects exactly one value");
                    }
                    VarState owner = borrowOwner(borrowCall.arguments().getFirst(), scope);
                    validateBorrow(owner, suppliedMutable);
                    continue; // temporary borrow ends at call boundary
                }
                if (arg instanceof Ast.NameExpr name) {
                    VarState state = requireState(scope, name.name());
                    requireUsable(state, name.name(), false);
                    if (mutable && state.kind != ValueKind.MUT_BORROW) {
                        throw error(callable + " argument " + (i + 1) + " requires a BorrowMut<T> value");
                    }
                    if (!mutable && state.kind != ValueKind.IMM_BORROW && state.kind != ValueKind.MUT_BORROW) {
                        throw error(callable + " argument " + (i + 1) + " requires borrowed value; pass borrow(" + name.name() + ")");
                    }
                    continue;
                }
                throw error(callable + " argument " + (i + 1) + " must be an explicit borrow");
            }
            checkExpr(arg, scope, true);
        }
    }

    private void checkAssignmentTarget(Ast.Expr target, Scope scope) {
        if (target instanceof Ast.NameExpr name) {
            VarState state = requireState(scope, name.name());
            requireUsable(state, name.name(), true);
            if (!state.mutable) throw error("cannot assign immutable binding '" + name.name() + "'; use let or a mut parameter");
            if (state.immutableBorrows > 0 || state.mutableBorrowed) throw error("cannot assign '" + name.name() + "' while it is borrowed");
            return;
        }
        if (target instanceof Ast.MemberExpr member) {
            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            if (klass != null) {
                Ast.FieldDecl field = findField(klass, member.member(), new LinkedHashSet<>());
                if (field == null) throw error("unknown field '" + member.member() + "' on " + klass.name());
                if (field.bindingKind() != Ast.BindingKind.LET) {
                    throw error("field '" + klass.name() + "." + member.member() + "' is immutable; declare the field with let to permit mutation");
                }
            }
            ensureMutableReceiver(member.receiver(), scope, "field '" + member.member() + "'");
            return;
        }
        if (target instanceof Ast.IndexExpr indexed) {
            ensureMutableReceiver(indexed.receiver(), scope, "indexed value");
            checkExpr(indexed.index(), scope, false);
            return;
        }
        throw error("unsupported assignment target");
    }

    private void ensureMutableReceiver(Ast.Expr receiver, Scope scope, String what) {
        if (receiver instanceof Ast.NameExpr name) {
            VarState state = requireState(scope, name.name());
            requireUsable(state, name.name(), true);
            boolean mutableBorrow = state.kind == ValueKind.MUT_BORROW || (state.type.isBorrow() && state.type.mutableBorrow());
            if (!state.mutable && !mutableBorrow) {
                throw error("cannot mutate " + what + " through immutable parameter/binding '" + name.name() + "'; declare the owned parameter as 'mut' or pass borrow_mut(" + name.name() + ")");
            }
            if (state.kind == ValueKind.IMM_BORROW || (state.type.isBorrow() && !state.type.mutableBorrow())) {
                throw error("cannot mutate " + what + " through immutable borrow '" + name.name() + "'");
            }
            if (state.kind != ValueKind.MUT_BORROW && (state.immutableBorrows > 0 || state.mutableBorrowed)) {
                throw error("cannot mutate '" + name.name() + "' while borrowed");
            }
            return;
        }
        throw error("mutation target must be rooted in a mutable local/parameter or BorrowMut<T> value");
    }

    private boolean isOwnershipIntrinsic(String name) {
        return name.equals("borrow") || name.equals("borrow_mut")
                || name.equals("take") || name.equals("copy") || name.equals("share");
    }

    private boolean isBorrowIntrinsicCall(Ast.CallExpr call, Scope scope) {
        if (!(call.callee() instanceof Ast.NameExpr name)) return false;
        if (!name.name().equals("borrow") && !name.name().equals("borrow_mut")) return false;
        return scope.lookup(name.name()) == null && findFunction(name.name()) == null;
    }

    private VarState borrowOwner(Ast.Expr operand, Scope scope) {
        if (!(operand instanceof Ast.NameExpr name)) {
            throw error("borrow(value) and borrow_mut(value) currently require a named owner; bind projected fields/indexes before borrowing");
        }
        VarState owner = requireState(scope, name.name());
        owner.debugName = name.name();
        requireUsable(owner, name.name(), false);
        return owner;
    }

    private void validateBorrow(VarState owner, boolean mutable) {
        if (owner.moved) throw error("cannot borrow moved value '" + owner.debugName + "'");
        if (mutable) {
            if (!owner.mutable) throw error("borrow_mut(value) requires mutable owner '" + owner.debugName + "'");
            if (owner.mutableBorrowed || owner.immutableBorrows > 0) throw error("cannot borrow_mut('" + owner.debugName + "') while another borrow is active");
        } else if (owner.mutableBorrowed) {
            throw error("cannot immutably borrow '" + owner.debugName + "' while a mutable borrow is active");
        }
    }

    private void beginPersistentBorrow(VarState owner, boolean mutable) {
        validateBorrow(owner, mutable);
        if (mutable) owner.mutableBorrowed = true;
        else owner.immutableBorrows++;
    }

    private void checkDeferred(Ast.Expr expression, Scope scope) {
        CaptureSet captures = new CaptureSet();
        scanExpr(expression, Set.of(), scope, null, captures, false);

        for (Capture capture : captures.values.values()) {
            VarState source = capture.source;
            source.debugName = capture.name;
            requireUsable(source, capture.name, capture.write);

            if (source.kind == ValueKind.IMM_BORROW
                    || source.kind == ValueKind.MUT_BORROW
                    || source.type.isBorrow()) {
                throw error(
                        "defer cannot capture borrowed value '" + capture.name
                                + "'; defer an owned value or perform the borrowed operation before scope exit");
            }
            if (capture.write) {
                throw error(
                        "defer cannot directly mutate captured binding '" + capture.name
                                + "'; move owned cleanup state into a cleanup function instead");
            }
        }

        // Validate the deferred expression before transferring ownership.
        checkExpr(expression, scope, false);

        for (Capture capture : captures.values.values()) {
            VarState source = capture.source;
            if (source.kind == ValueKind.MOVE_ONLY && !source.moved) {
                move(source, capture.name);
            }
        }
    }

    private ValueInfo checkLambda(Ast.LambdaExpr lambda, Scope outer, String recursiveBinding) {
        boolean nonLexical = lambda.nonLexical() || outer.descendantsNonLexical();
        CaptureSet captures = nonLexical ? new CaptureSet() : collectCaptures(lambda, outer, recursiveBinding);
        Scope closure = new Scope(null, nonLexical);

        for (Capture capture : captures.values.values()) {
            VarState source = capture.source;
            source.debugName = capture.name;
            requireUsable(source, capture.name, capture.write);

            if (source.kind == ValueKind.IMM_BORROW || source.kind == ValueKind.MUT_BORROW || source.type.isBorrow()) {
                throw error("closure cannot capture borrowed value '" + capture.name + "'; capture its owner by value or pass the borrow as a lambda parameter");
            }

            if (capture.write) {
                if (!source.mutable) throw error("closure cannot mutate immutable capture '" + capture.name + "'");
                if (source.immutableBorrows > 0 || source.mutableBorrowed) throw error("closure cannot capture '" + capture.name + "' mutably while borrowed");
                move(source, capture.name);
                closure.define(capture.name, new VarState(source.type, true, source.kind, Origin.CAPTURE));
            } else if (source.kind == ValueKind.MOVE_ONLY) {
                move(source, capture.name);
                closure.define(capture.name, new VarState(source.type, false, ValueKind.MOVE_ONLY, Origin.CAPTURE));
            } else {
                closure.define(capture.name, new VarState(source.type, false, ValueKind.COPY, Origin.CAPTURE));
            }
        }

        for (Ast.Param param : lambda.parameters()) closure.define(param.name(), stateForParam(param));
        for (Ast.Stmt stmt : lambda.blockBody()) checkStatement(stmt, closure, Ast.TypeRef.inferred());
        closure.close();
        return new ValueInfo(Ast.TypeRef.simple("Fnc"), ValueKind.MOVE_ONLY, null);
    }

    private CaptureSet collectCaptures(Ast.LambdaExpr lambda, Scope outer, String recursiveBinding) {
        CaptureSet captures = new CaptureSet();
        Set<String> locals = new HashSet<>();
        for (Ast.Param param : lambda.parameters()) locals.add(param.name());
        scanStatements(lambda.blockBody(), locals, outer, recursiveBinding, captures);
        return captures;
    }

    private void scanStatements(List<Ast.Stmt> statements, Set<String> locals, Scope outer, String recursiveBinding, CaptureSet captures) {
        Set<String> blockLocals = new HashSet<>(locals);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                scanExpr(binding.initializer(), blockLocals, outer, recursiveBinding, captures, false);
                blockLocals.add(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                scanExpr(destructure.initializer(), blockLocals, outer, recursiveBinding, captures, false);
                for (Ast.DestructureBinding binding : destructure.bindings()) blockLocals.add(binding.name());
            } else if (stmt instanceof Ast.ReturnStmt ret && ret.value() != null) {
                scanExpr(ret.value(), blockLocals, outer, recursiveBinding, captures, false);
            } else if (stmt instanceof Ast.ExprStmt e) scanExpr(e.expression(), blockLocals, outer, recursiveBinding, captures, false);
            else if (stmt instanceof Ast.DeferStmt e) scanExpr(e.expression(), blockLocals, outer, recursiveBinding, captures, false);
            else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch b : s.branches()) {
                    scanExpr(b.condition(), blockLocals, outer, recursiveBinding, captures, false);
                    scanStatements(b.body(), blockLocals, outer, recursiveBinding, captures);
                }
                scanStatements(s.elseBody(), blockLocals, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.TryStmt s) {
                scanStatements(s.body(), blockLocals, outer, recursiveBinding, captures);
                Set<String> caught = new HashSet<>(blockLocals);
                caught.add(s.errorName());
                scanStatements(s.catchBody(), caught, outer, recursiveBinding, captures);
                scanStatements(s.finallyBody(), blockLocals, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.ForOfStmt s) {
                scanExpr(s.iterable(), blockLocals, outer, recursiveBinding, captures, false);
                Set<String> loop = new HashSet<>(blockLocals);
                loop.add(s.bindingName());
                scanStatements(s.body(), loop, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.ForStmt s) {
                if (s.initializer() instanceof Ast.ExprStmt e) scanExpr(e.expression(), blockLocals, outer, recursiveBinding, captures, false);
                if (s.condition() != null) scanExpr(s.condition(), blockLocals, outer, recursiveBinding, captures, false);
                if (s.update() != null) scanExpr(s.update(), blockLocals, outer, recursiveBinding, captures, false);
                scanStatements(s.body(), blockLocals, outer, recursiveBinding, captures);
            }
        }
    }

    private void scanExpr(Ast.Expr expr, Set<String> locals, Scope outer, String recursiveBinding, CaptureSet captures, boolean write) {
        if (expr instanceof Ast.NameExpr name) {
            if (name.name().equals(recursiveBinding)) return;
            if (!locals.contains(name.name())) {
                VarState state = outer.lookup(name.name());
                if (state != null) captures.add(name.name(), state, write);
            }
            return;
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            scanExpr(assignment.target(), locals, outer, recursiveBinding, captures, true);
            scanExpr(assignment.value(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.BinaryExpr e) {
            scanExpr(e.left(), locals, outer, recursiveBinding, captures, false);
            scanExpr(e.right(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.UnaryExpr e) scanExpr(e.operand(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.ConditionalExpr e) {
            scanExpr(e.condition(), locals, outer, recursiveBinding, captures, false);
            scanExpr(e.whenTrue(), locals, outer, recursiveBinding, captures, false);
            scanExpr(e.whenFalse(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.CallExpr e) {
            scanExpr(e.callee(), locals, outer, recursiveBinding, captures, false);
            for (Ast.Expr arg : e.arguments()) scanExpr(arg, locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.MemberExpr e) scanExpr(e.receiver(), locals, outer, recursiveBinding, captures, write);
        else if (expr instanceof Ast.IndexExpr e) {
            scanExpr(e.receiver(), locals, outer, recursiveBinding, captures, write);
            scanExpr(e.index(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.NewExpr e) for (Ast.Expr arg : e.arguments()) scanExpr(arg, locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.AwaitExpr e) scanExpr(e.expression(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr item : e.elements()) scanExpr(item, locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr item : e.elements()) scanExpr(item, locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.ObjectExpr e) for (Ast.ObjectField field : e.fields()) scanExpr(field.value(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.LambdaExpr) {
            // Nested lambda performs its own capture analysis when checked.
        }
    }

    private Ast.ClassDecl classOfReceiver(Ast.Expr receiver, Scope scope) {
        Ast.TypeRef type = null;
        if (receiver instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            if (state != null) type = state.type;
        } else if (receiver instanceof Ast.NewExpr created) type = created.type();
        if (type == null) return null;
        if (type.isBorrow()) type = type.borrowedTarget();
        return findClass(type.name());
    }

    private Ast.FieldDecl findField(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                seen.remove(klass);
                return field;
            }
        }
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.ClassDecl p = findClass(parent.name());
            if (p == null) continue;
            Ast.FieldDecl found = findField(p, name, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.MethodDecl findMethod(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name) && method.parameters().size() == arity) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.ClassDecl p = findClass(parent.name());
            if (p == null) continue;
            Ast.MethodDecl found = findMethod(p, name, arity, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.FunctionDecl findFunction(String name) {
        if (ambiguousFunctions.contains(name)) return null;
        return functions.get(name);
    }

    private Ast.ClassDecl findClass(String name) {
        if (ambiguousClasses.contains(name)) return null;
        return classes.get(name);
    }

    private void requireUsable(VarState state, String name, boolean write) {
        if (state.moved) throw error("use of moved value '" + name + "'");
        if (write) {
            if (state.mutableBorrowed && state.kind != ValueKind.MUT_BORROW) throw error("cannot mutate '" + name + "' while mutably borrowed");
            if (state.immutableBorrows > 0) throw error("cannot mutate '" + name + "' while immutably borrowed");
        } else if (state.mutableBorrowed && state.kind != ValueKind.MUT_BORROW) {
            throw error("cannot read '" + name + "' while it is mutably borrowed");
        }
    }

    private void move(VarState state, String name) {
        requireUsable(state, name, false);
        if (state.immutableBorrows > 0 || state.mutableBorrowed) throw error("cannot move '" + name + "' while it is borrowed");
        state.moved = true;
    }

    private VarState requireState(Scope scope, String name) {
        VarState state = scope.lookup(name);
        if (state == null) throw error("unknown owned binding '" + name + "'");
        state.debugName = name;
        return state;
    }

    private Map<VarState, StateSnapshot> stateSnapshot(Scope scope) {
        Map<VarState, StateSnapshot> result = new IdentityHashMap<>();
        for (VarState state : scope.visibleStates()) {
            result.put(state, new StateSnapshot(state.moved, state.immutableBorrows, state.mutableBorrowed));
        }
        return result;
    }

    private void restoreState(Map<VarState, StateSnapshot> snapshot) {
        for (Map.Entry<VarState, StateSnapshot> entry : snapshot.entrySet()) {
            VarState state = entry.getKey();
            StateSnapshot saved = entry.getValue();
            state.moved = saved.moved();
            state.immutableBorrows = saved.immutableBorrows();
            state.mutableBorrowed = saved.mutableBorrowed();
        }
    }

    private void mergeBranchState(Map<VarState, StateSnapshot> base, List<Map<VarState, StateSnapshot>> exits) {
        restoreState(base);
        for (VarState state : base.keySet()) {
            boolean movedOnAnyPath = base.get(state).moved();
            for (Map<VarState, StateSnapshot> exit : exits) {
                StateSnapshot saved = exit.get(state);
                if (saved != null) movedOnAnyPath |= saved.moved();
            }
            state.moved = movedOnAnyPath;
        }
    }

    private Map<VarState,Boolean> movedSnapshot(Scope scope) {
        Map<VarState,Boolean> result = new IdentityHashMap<>();
        for (VarState state : scope.visibleStates()) result.put(state, state.moved);
        return result;
    }

    private void rejectLoopMoves(Map<VarState,Boolean> before, Scope after) {
        for (Map.Entry<VarState,Boolean> entry : before.entrySet()) {
            if (!entry.getValue() && entry.getKey().moved && entry.getKey().kind != ValueKind.COPY) {
                throw error("cannot move outer value '" + entry.getKey().debugName + "' from a repeating loop; borrow it or move it before entering the loop");
            }
        }
    }

    private ValueKind kindOfType(Ast.TypeRef type) {
        if (type == null) return ValueKind.MOVE_ONLY;
        if (type.isBorrow()) return type.mutableBorrow() ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW;
        return isCopyType(type) ? ValueKind.COPY : ValueKind.MOVE_ONLY;
    }

    private boolean isCopyType(Ast.TypeRef type) {
        if (type == null || type.isBorrow()) return false;
        return switch (type.name()) {
            case "i8","i16","i32","i64","u8","u16","u32","u64","int","uint","bigint",
                    "f32","f64","float","decimal","complex64","complex128","complex",
                    "bool","Bool","str","string","String","void","ActorRef","MonitorRef","Shared" -> true;
            default -> false;
        };
    }

    private Ast.TypeRef inferLiteralType(Object value) {
        if (value instanceof Boolean) return Ast.TypeRef.simple("bool");
        if (value instanceof Long) return Ast.TypeRef.simple("int");
        if (value instanceof Double) return Ast.TypeRef.simple("float");
        if (value instanceof String) return Ast.TypeRef.simple("String");
        if (value instanceof Ast.Imaginary) return Ast.TypeRef.simple("complex");
        return Ast.TypeRef.inferred();
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Oreslang ownership error: " + message);
    }

    private enum ValueKind { COPY, MOVE_ONLY, IMM_BORROW, MUT_BORROW }
    private enum Origin { PARAM, LOCAL, CAPTURE }

    private static final class ValueInfo {
        private final Ast.TypeRef type;
        private final ValueKind kind;
        private final VarState borrowSource;
        private ValueInfo(Ast.TypeRef type, ValueKind kind, VarState borrowSource) {
            this.type = type;
            this.kind = kind;
            this.borrowSource = borrowSource;
        }
    }

    private static final class VarState {
        private Ast.TypeRef type;
        private final boolean mutable;
        private ValueKind kind;
        private final Origin origin;
        private boolean moved;
        private int immutableBorrows;
        private boolean mutableBorrowed;
        private VarState borrowSource;
        private String debugName = "<value>";

        private VarState(Ast.TypeRef type, boolean mutable, ValueKind kind, Origin origin) {
            this.type = type;
            this.mutable = mutable;
            this.kind = kind;
            this.origin = origin;
        }
    }

    private static final class Scope {
        private final Scope parent;
        private final boolean descendantsNonLexical;
        private final Map<String,VarState> locals = new LinkedHashMap<>();
        private boolean closed;

        private Scope(Scope parent) { this(parent, parent != null && parent.descendantsNonLexical); }
        private Scope(Scope parent, boolean descendantsNonLexical) {
            this.parent = parent;
            this.descendantsNonLexical = descendantsNonLexical;
        }
        private boolean descendantsNonLexical() { return descendantsNonLexical; }

        private void define(String name, VarState state) {
            if (locals.putIfAbsent(name, state) != null) throw new IllegalArgumentException("Oreslang ownership error: duplicate binding '" + name + "'");
            state.debugName = name;
        }

        private VarState lookup(String name) {
            VarState local = locals.get(name);
            return local != null ? local : parent == null ? null : parent.lookup(name);
        }

        private List<VarState> visibleStates() {
            ArrayList<VarState> result = new ArrayList<>();
            if (parent != null) result.addAll(parent.visibleStates());
            result.addAll(locals.values());
            return result;
        }

        private void close() {
            if (closed) return;
            closed = true;
            for (VarState state : locals.values()) {
                if (!state.moved
                        && state.type != null
                        && state.type.name().equals("Future")
                        && state.type.arguments().size() == 1) {
                    throw new IllegalArgumentException(
                            "Oreslang ownership error: Future binding '" + state.debugName
                                    + "' leaves scope without being awaited or transferred");
                }
                if (state.borrowSource != null) {
                    if (state.kind == ValueKind.MUT_BORROW) state.borrowSource.mutableBorrowed = false;
                    else if (state.kind == ValueKind.IMM_BORROW) state.borrowSource.immutableBorrows--;
                }
            }
        }
    }

    private record StateSnapshot(boolean moved, int immutableBorrows, boolean mutableBorrowed) { }

    private record Capture(String name, VarState source, boolean write) { }

    private static final class CaptureSet {
        private final Map<String,Capture> values = new LinkedHashMap<>();
        private void add(String name, VarState state, boolean write) {
            Capture existing = values.get(name);
            values.put(name, existing == null ? new Capture(name, state, write)
                    : new Capture(name, state, existing.write() || write));
        }
    }
}
