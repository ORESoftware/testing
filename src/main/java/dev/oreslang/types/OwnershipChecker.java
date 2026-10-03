package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayDeque;
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
 * - classes/lists/objects/functions are move-only; named structs derive Copy when every stored field is Copy;
 * - by-value call/binding/return moves move-only values;
 * - &T permits shared immutable borrows;
 * - &mut T is exclusive and requires a mutable owner;
 * - Bar mut b makes an owned parameter mutable inside the callee;
 * - escaping closures own non-Copy captures and mutable captures;
 * - closures may not capture a borrow (pass it as a lambda parameter instead);
 * - moving an outer value from a repeating loop is rejected conservatively.
 */
public final class OwnershipChecker {
    private Ast.ModuleDecl activeModule;
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
    private final ArrayDeque<Map<String, Ast.ClassDecl>> localClassScopes = new ArrayDeque<>();
    private final ArrayDeque<Map<String, Ast.TypeAliasDecl>> localAliasScopes = new ArrayDeque<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousTypeAliases = new HashSet<>();

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
                else if (decl instanceof Ast.TypeAliasDecl alias) index(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias);
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
            Ast.ModuleDecl previous = activeModule;
            activeModule = module;
            try {
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) checkFunction(module, fn);
                    else if (decl instanceof Ast.InitDecl init) checkInit(module, init);
                    else if (decl instanceof Ast.ClassDecl klass) checkClass(module, klass);
                }
            } finally {
                activeModule = previous;
            }
        }
    }

    private void checkInit(Ast.ModuleDecl module, Ast.InitDecl init) {
        Scope moduleScope = new Scope(null);
        seedModuleState(module, moduleScope);
        Scope scope = new Scope(moduleScope);
        checkBlock(init.body(), scope, Ast.TypeRef.simple("void"));
        scope.close();
        moduleScope.close();
    }

    private void checkFunction(Ast.ModuleDecl module, Ast.FunctionDecl fn) {
        Scope moduleScope = new Scope(null);
        seedModuleState(module, moduleScope);
        Scope scope = new Scope(moduleScope);
        for (Ast.Param param : fn.parameters()) {
            scope.define(param.name(), stateForParam(param));
        }
        checkBlock(fn.body(), scope, fn.returnType());
        scope.close();
        moduleScope.close();
    }

    private void seedModuleState(Ast.ModuleDecl module, Scope scope) {
        for (Ast.Decl decl : module.declarations()) {
            if (!(decl instanceof Ast.FieldDecl field)) continue;
            Ast.TypeRef type = field.type() == null ? inferFieldType(field.initializer()) : field.type();
            scope.define(field.name(), new VarState(
                    type,
                    field.bindingKind() == Ast.BindingKind.LET,
                    kindOfType(type),
                    Origin.MODULE));
        }
    }

    private Ast.TypeRef inferFieldType(Ast.Expr initializer) {
        if (initializer instanceof Ast.LiteralExpr literal) return inferLiteralType(literal.value());
        if (initializer instanceof Ast.ListExpr) return Ast.TypeRef.simple("Array");
        if (initializer instanceof Ast.ObjectExpr) return Ast.TypeRef.simple("obj");
        if (initializer instanceof Ast.NewExpr created) return created.type();
        if (initializer instanceof Ast.LambdaExpr) return Ast.TypeRef.simple("Fnc");
        return Ast.TypeRef.inferred();
    }

    private void checkClass(Ast.ModuleDecl module, Ast.ClassDecl klass) {
        for (Ast.MethodDecl method : klass.methods()) {
            Scope moduleScope = new Scope(null);
            seedModuleState(module, moduleScope);
            Scope scope = new Scope(moduleScope);
            if (!method.isStatic()) {
                Ast.TypeRef explicit = method.explicitReceiverType();
                boolean mutableReceiver = explicit != null
                        && explicit.isBorrow()
                        && explicit.mutableBorrow();
                Ast.TypeRef selfType = Ast.TypeRef.simple(klass.name());
                scope.define(
                        "self",
                        new VarState(
                                selfType,
                                false,
                                mutableReceiver ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW,
                                Origin.PARAM));
            }
            for (Ast.Param param : method.parameters()) scope.define(param.name(), stateForParam(param));
            checkBlock(method.body(), scope, method.returnType());
            scope.close();
            moduleScope.close();
        }
    }

    private VarState stateForParam(Ast.Param param) {
        ValueKind kind = param.structural() && !param.type().isBorrow() ? ValueKind.IMM_BORROW : kindOfType(param.type());
        boolean mutableOwner = param.mutable();
        if (param.type().isBorrow() && param.type().mutableBorrow()) mutableOwner = false;
        return new VarState(param.type(), mutableOwner, kind, Origin.PARAM);
    }

    private void checkBlock(List<Ast.Stmt> body, Scope parent, Ast.TypeRef returnType) {
        pushLocalTypeScopes(body);
        Scope scope = new Scope(parent);
        try {
            for (Ast.Stmt stmt : body) checkStatement(stmt, scope, returnType);
        } finally {
            scope.close();
            localAliasScopes.pop();
            localClassScopes.pop();
        }
    }

    private void pushLocalTypeScopes(List<Ast.Stmt> body) {
        LinkedHashMap<String, Ast.ClassDecl> localClasses = new LinkedHashMap<>();
        LinkedHashMap<String, Ast.TypeAliasDecl> localAliases = new LinkedHashMap<>();
        for (Ast.Stmt stmt : body) {
            if (!(stmt instanceof Ast.TypeDeclStmt typeDecl)) continue;
            if (typeDecl.declaration() instanceof Ast.ClassDecl klass) {
                localClasses.put(klass.name(), klass);
            } else if (typeDecl.declaration() instanceof Ast.TypeAliasDecl alias) {
                localAliases.put(alias.name(), alias);
            }
        }
        localClassScopes.push(localClasses);
        localAliasScopes.push(localAliases);
    }

    private void checkStatement(Ast.Stmt stmt, Scope scope, Ast.TypeRef returnType) {
        if (stmt instanceof Ast.TypeDeclStmt localType) {
            if (localType.declaration() instanceof Ast.ClassDecl klass) {
                if (activeModule == null) throw error("local aggregate has no enclosing module ownership scope");
                checkClass(activeModule, klass);
            }
            return;
        }
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
                if (ret.value() instanceof Ast.UnaryExpr unary && (unary.operator().equals("&") || unary.operator().equals("&mut"))) {
                    VarState owner = borrowOwner(unary.operand(), scope);
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
            checkExpr(defer.expression(), scope, false);
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
        if (binding.initializer() instanceof Ast.UnaryExpr unary && (unary.operator().equals("&") || unary.operator().equals("&mut"))) {
            boolean mutableBorrow = unary.operator().equals("&mut");
            VarState owner = borrowOwner(unary.operand(), scope);
            beginPersistentBorrow(owner, mutableBorrow);
            value = new ValueInfo(Ast.TypeRef.borrowed(owner.type, mutableBorrow), mutableBorrow ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW, owner);
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
            if (unary.operator().equals("&") || unary.operator().equals("&mut")) {
                boolean mutable = unary.operator().equals("&mut");
                VarState owner = borrowOwner(unary.operand(), scope);
                validateBorrow(owner, mutable);
                return new ValueInfo(Ast.TypeRef.borrowed(owner.type, mutable), mutable ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW, owner);
            }
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
            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            if (klass != null
                    && findField(klass, member.member(), new LinkedHashSet<>()) == null
                    && hasMutableReceiverMethod(klass, member.member(), new LinkedHashSet<>())) {
                throw error("mutable-receiver method value '" + member.member()
                        + "' cannot be extracted; call it directly through a mutable owner"
                        + " until persistent &mut bound-method lifetimes are modeled");
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
        if (expr instanceof Ast.StructInitExpr created) {
            for (Ast.ObjectField field : created.fields()) checkExpr(field.value(), scope, true);
            Ast.TypeRef type = created.type() == null ? Ast.TypeRef.inferred() : created.type();
            return new ValueInfo(type, created.type() != null ? kindOfType(type) : ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.AwaitExpr awaited) return checkExpr(awaited.expression(), scope, consuming);
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
        if (call.callee() instanceof Ast.NameExpr name) {
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) {
                checkArguments(call.arguments(), fn.parameters(), scope, "function " + fn.name());
                return new ValueInfo(fn.returnType(), kindOfType(fn.returnType()), null);
            }
        }

        if (call.callee() instanceof Ast.MemberExpr member) {
            checkExpr(member.receiver(), scope, false);
            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            Ast.MethodDecl method = klass == null ? null : findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
            if (method != null) {
                if (requiresMutableReceiver(method)) {
                    ensureMutableReceiver(member.receiver(), scope, "method '" + method.name() + "' receiver");
                }
                checkArguments(call.arguments(), method.parameters(), scope, "method " + method.name());
                return new ValueInfo(method.returnType(), kindOfType(method.returnType()), null);
            }
        }

        checkExpr(call.callee(), scope, false);
        for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, true);
        return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
    }

    private boolean requiresMutableReceiver(Ast.MethodDecl method) {
        Ast.TypeRef receiver = method.explicitReceiverType();
        return receiver != null && receiver.isBorrow() && receiver.mutableBorrow();
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
                if (arg instanceof Ast.UnaryExpr unary && (unary.operator().equals("&") || unary.operator().equals("&mut"))) {
                    if (mutable && !unary.operator().equals("&mut")) {
                        throw error(callable + " argument " + (i + 1) + " requires &mut borrow");
                    }
                    VarState owner = borrowOwner(unary.operand(), scope);
                    validateBorrow(owner, mutable);
                    continue; // temporary borrow ends at call boundary
                }
                if (arg instanceof Ast.NameExpr name) {
                    VarState state = requireState(scope, name.name());
                    requireUsable(state, name.name(), false);
                    if (mutable && state.kind != ValueKind.MUT_BORROW) {
                        throw error(callable + " argument " + (i + 1) + " requires &mut value");
                    }
                    if (!mutable && state.kind != ValueKind.IMM_BORROW && state.kind != ValueKind.MUT_BORROW) {
                        throw error(callable + " argument " + (i + 1) + " requires borrowed value; pass &" + name.name());
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
                throw error("cannot mutate " + what + " through immutable parameter/binding '" + name.name() + "'; declare the owned parameter as 'mut' or pass '&mut'");
            }
            if (state.kind == ValueKind.IMM_BORROW || (state.type.isBorrow() && !state.type.mutableBorrow())) {
                throw error("cannot mutate " + what + " through immutable borrow '" + name.name() + "'");
            }
            if (state.kind != ValueKind.MUT_BORROW && (state.immutableBorrows > 0 || state.mutableBorrowed)) {
                throw error("cannot mutate '" + name.name() + "' while borrowed");
            }
            return;
        }
        if (receiver instanceof Ast.UnaryExpr unary && unary.operator().equals("&mut")) {
            VarState owner = borrowOwner(unary.operand(), scope);
            validateBorrow(owner, true);
            return;
        }
        throw error("mutation target must be rooted in a mutable local/parameter or &mut borrow");
    }

    private VarState borrowOwner(Ast.Expr operand, Scope scope) {
        if (!(operand instanceof Ast.NameExpr name)) {
            throw error("borrows currently require a named owner; borrow the binding before projecting fields/indexes");
        }
        VarState owner = requireState(scope, name.name());
        owner.debugName = name.name();
        requireUsable(owner, name.name(), false);
        return owner;
    }

    private void validateBorrow(VarState owner, boolean mutable) {
        if (owner.moved) throw error("cannot borrow moved value '" + owner.debugName + "'");
        if (mutable) {
            if (!owner.mutable) throw error("cannot mutably borrow immutable owner '" + owner.debugName + "'");
            if (owner.mutableBorrowed || owner.immutableBorrows > 0) throw error("cannot mutably borrow '" + owner.debugName + "' while another borrow is active");
        } else if (owner.mutableBorrowed) {
            throw error("cannot immutably borrow '" + owner.debugName + "' while a mutable borrow is active");
        }
    }

    private void beginPersistentBorrow(VarState owner, boolean mutable) {
        validateBorrow(owner, mutable);
        if (mutable) owner.mutableBorrowed = true;
        else owner.immutableBorrows++;
    }

    private ValueInfo checkLambda(Ast.LambdaExpr lambda, Scope outer, String recursiveBinding) {
        CaptureSet captures = collectCaptures(lambda, outer, recursiveBinding);
        Scope closure = new Scope(null);

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
        else if (expr instanceof Ast.StructInitExpr e) for (Ast.ObjectField field : e.fields()) scanExpr(field.value(), locals, outer, recursiveBinding, captures, false);
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
        else if (receiver instanceof Ast.StructInitExpr created) type = created.type();
        if (type == null) return null;
        type = resolveOwnershipAlias(type, new LinkedHashSet<>());
        if (type.isBorrow()) type = resolveOwnershipAlias(type.borrowedTarget(), new LinkedHashSet<>());
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
            Ast.TypeRef resolvedParent = resolveOwnershipAlias(parent, new LinkedHashSet<>());
            Ast.ClassDecl p = findClass(resolvedParent.name());
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

    private boolean hasMutableReceiverMethod(
            Ast.ClassDecl klass,
            String name,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return false;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic()
                    && method.name().equals(name)
                    && requiresMutableReceiver(method)) {
                seen.remove(klass);
                return true;
            }
        }
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.ClassDecl parentClass = findClass(parent.name());
            if (parentClass != null
                    && hasMutableReceiverMethod(parentClass, name, seen)) {
                seen.remove(klass);
                return true;
            }
        }
        seen.remove(klass);
        return false;
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
        if (!name.contains(".")) {
            for (Map<String, Ast.ClassDecl> scope : localClassScopes) {
                Ast.ClassDecl local = scope.get(name);
                if (local != null) return local;
            }
        }
        if (ambiguousClasses.contains(name)) return null;
        return classes.get(name);
    }

    private Ast.TypeAliasDecl findTypeAlias(String name) {
        if (!name.contains(".")) {
            for (Map<String, Ast.TypeAliasDecl> scope : localAliasScopes) {
                Ast.TypeAliasDecl local = scope.get(name);
                if (local != null) return local;
            }
        }
        if (ambiguousTypeAliases.contains(name)) return null;
        return typeAliases.get(name);
    }

    private Ast.TypeRef resolveOwnershipAlias(Ast.TypeRef type, Set<Ast.TypeAliasDecl> seen) {
        if (type == null) return null;
        if (type.isBorrow()) {
            return Ast.TypeRef.borrowed(
                    resolveOwnershipAlias(type.borrowedTarget(), seen),
                    type.mutableBorrow());
        }

        Ast.TypeAliasDecl alias = findTypeAlias(type.name());
        if (alias == null) return type;
        if (!seen.add(alias)) throw error("type alias cycle involving '" + alias.name() + "'");
        if (type.arguments().size() != alias.genericParameters().size()) {
            throw error("type alias '" + alias.name() + "' expects " + alias.genericParameters().size()
                    + " type argument(s), got " + type.arguments().size());
        }

        LinkedHashMap<String, Ast.TypeRef> substitutions = new LinkedHashMap<>();
        for (int i = 0; i < alias.genericParameters().size(); i++) {
            substitutions.put(alias.genericParameters().get(i), type.arguments().get(i));
        }
        Ast.TypeRef target = substituteOwnershipAliasType(alias.target(), substitutions);
        return resolveOwnershipAlias(target, seen);
    }

    private Ast.TypeRef substituteOwnershipAliasType(
            Ast.TypeRef type,
            Map<String, Ast.TypeRef> substitutions) {
        if (type.isBorrow()) {
            return Ast.TypeRef.borrowed(
                    substituteOwnershipAliasType(type.borrowedTarget(), substitutions),
                    type.mutableBorrow());
        }
        Ast.TypeRef replacement = substitutions.get(type.name());
        if (replacement != null && type.arguments().isEmpty() && !type.inferArguments()) return replacement;
        if (type.arguments().isEmpty()) return type;
        return new Ast.TypeRef(
                type.name(),
                type.arguments().stream()
                        .map(argument -> substituteOwnershipAliasType(argument, substitutions))
                        .toList(),
                type.inferArguments());
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
        Ast.TypeRef resolved = resolveOwnershipAlias(type, new LinkedHashSet<>());
        if (resolved.isBorrow()) return resolved.mutableBorrow() ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW;
        return isCopyType(resolved) ? ValueKind.COPY : ValueKind.MOVE_ONLY;
    }

    private boolean isCopyType(Ast.TypeRef type) {
        return isCopyType(type, new LinkedHashSet<>());
    }

    private boolean isCopyType(Ast.TypeRef type, Set<Ast.ClassDecl> visiting) {
        if (type == null || type.isBorrow() || type.inferArguments()) return false;
        Ast.TypeRef resolved = resolveOwnershipAlias(type, new LinkedHashSet<>());
        if (resolved == null || resolved.isBorrow() || resolved.inferArguments()) return false;

        switch (resolved.name()) {
            case "i8","i16","i32","i64","u8","u16","u32","u64","int","uint","bigint",
                    "f32","f64","float","decimal","complex64","complex128","complex",
                    "bool","Bool","string","String","void" -> {
                return true;
            }
            case "Option" -> {
                return resolved.arguments().size() == 1
                        && isCopyType(resolved.arguments().getFirst(), visiting);
            }
            default -> { }
        }

        Ast.ClassDecl aggregate = findClass(resolved.name());
        if (aggregate == null || !aggregate.isStruct()) return false;
        if (aggregate.genericParameters().size() != resolved.arguments().size()) return false;
        if (!visiting.add(aggregate)) return false;

        LinkedHashMap<String, Ast.TypeRef> substitutions = new LinkedHashMap<>();
        for (int i = 0; i < aggregate.genericParameters().size(); i++) {
            substitutions.put(aggregate.genericParameters().get(i), resolved.arguments().get(i));
        }

        try {
            for (Ast.FieldDecl field : aggregate.fields()) {
                Ast.TypeRef fieldType = substituteOwnershipAliasType(field.type(), substitutions);
                if (!isCopyType(fieldType, visiting)) return false;
            }
            return true;
        } finally {
            visiting.remove(aggregate);
        }
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
    private enum Origin { PARAM, LOCAL, CAPTURE, MODULE }

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
        private final Map<String,VarState> locals = new LinkedHashMap<>();
        private boolean closed;

        private Scope(Scope parent) { this.parent = parent; }

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
