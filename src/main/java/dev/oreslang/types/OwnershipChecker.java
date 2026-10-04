package dev.oreslang.types;

import dev.oreslang.ast.AnnotationExpander;
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
 * - &T permits shared immutable borrows;
 * - &mut T is exclusive and requires a mutable owner;
 * - Bar mut b makes an owned parameter mutable inside the callee;
 * - escaping closures own non-Copy captures and mutable captures;
 * - closures may not capture a borrow (pass it as a lambda parameter instead);
 * - moving an outer value from a repeating loop is rejected conservatively.
 */
public final class OwnershipChecker {
    private record ResolvedMethod(Ast.ClassDecl owner, Ast.TypeRef ownerType, Ast.MethodDecl method) { }
    private record ResolvedField(Ast.ClassDecl owner, Ast.TypeRef ownerType, Ast.FieldDecl field) { }
    private record CallSignature(List<Ast.Param> parameters, Ast.TypeRef result) { }
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private int mutexCriticalSectionDepth;

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
                if (klass.actorKind() != Ast.ActorKind.NONE) {
                    scope.define("self", new VarState(
                            Ast.TypeRef.borrowed(Ast.TypeRef.simple(klass.name()), true),
                            false,
                            ValueKind.MUT_BORROW,
                            Origin.PARAM));
                } else {
                    ValueKind receiverKind = AnnotationExpander.isGeneratedFromJsonSetter(method)
                            ? ValueKind.MUT_BORROW
                            : ValueKind.IMM_BORROW;
                    scope.define("self", new VarState(Ast.TypeRef.simple(klass.name()), false, receiverKind, Origin.PARAM));
                }
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
            for (int i = 0; i < destructure.bindings().size(); i++) {
                Ast.DestructureBinding binding = destructure.bindings().get(i);
                if (binding.isDiscard()) continue;
                Ast.TypeRef bindingType = destructureBindingType(destructure, source.type, i, binding.name());
                ValueKind bindingKind = bindingType.name().equals("$infer$")
                        ? (source.kind == ValueKind.COPY ? ValueKind.COPY : ValueKind.MOVE_ONLY)
                        : kindOfType(bindingType);
                scope.define(binding.name(), new VarState(
                        bindingType,
                        binding.kind() == Ast.BindingKind.LET,
                        bindingKind,
                        Origin.LOCAL));
            }
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() != null) {
                if (mutexCriticalSectionDepth > 0) {
                    throw error("with_lock/recover critical-section callbacks cannot return a value");
                }
                if (ret.value() instanceof Ast.UnaryExpr unary && (unary.operator().equals("&") || unary.operator().equals("&mut"))) {
                    VarState owner = borrowOwner(unary.operand(), scope);
                    if (owner.origin == Origin.LOCAL) {
                        throw error("cannot return a borrow of local value '" + owner.debugName + "'; borrowed value would outlive its owner");
                    }
                    if (isActorConfinedBorrow(owner)) {
                        throw error("actor self cannot escape its mailbox turn as a returned borrow");
                    }
                }
                ValueInfo returned = checkExpr(ret.value(), scope, true);
                if (containsMutexGuardType(returned.type)) {
                    throw error("MutexGuard values are lexical and cannot be returned from a function/routine");
                }
            }
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) {
            ValueInfo value = checkExpr(expression.expression(), scope, false);
            if (containsMutexGuardType(value.type)) {
                throw error("guard-bearing values cannot be discarded; bind the result with val and release/await it");
            }
            return;
        }
        if (stmt instanceof Ast.DeferStmt defer) {
            ValueInfo value = checkExpr(defer.expression(), scope, false);
            if (containsMutexGuardType(value.type)) {
                throw error("defer cannot produce a guard-bearing value");
            }
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

        Ast.TypeRef storedType = binding.declaredType() == null ? value.type : binding.declaredType();
        if (binding.kind() == Ast.BindingKind.LET && containsMutexGuardType(storedType)) {
            throw error("guard-bearing values are linear and cannot use let; bind them once with val");
        }

        ValueKind storedKind = binding.declaredType() == null ? value.kind : kindOfType(storedType);
        VarState state = new VarState(
                storedType,
                binding.kind() == Ast.BindingKind.LET,
                storedKind,
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
            if (state == null && name.name().equals("None")) {
                return new ValueInfo(
                        new Ast.TypeRef("Option", List.of(Ast.TypeRef.inferred()), false),
                        ValueKind.MOVE_ONLY,
                        null);
            }
            if (state == null) return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.COPY, null); // function/module/global
            state.debugName = name.name();
            requireUsable(state, name.name(), false);
            if (consuming && isActorConfinedBorrow(state)) {
                throw error("actor self is a non-escapable mailbox capability; access its fields/methods inside the actor turn");
            }
            if (consuming && mutexCriticalSectionDepth > 0 && state.kind == ValueKind.MUT_BORROW) {
                throw error("protected with_lock/recover state cannot be moved by value; use it through its &mut critical-section borrow");
            }
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
            ValueInfo assigned = checkExpr(assignment.value(), scope, true);
            if (containsMutexGuardType(assigned.type)) {
                throw error("guard-bearing values cannot be assigned or overwritten; bind them once with val");
            }
            return assigned;
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
            Ast.TypeRef joinedType = joinConditionalType(left.type, right.type);
            return new ValueInfo(
                    joinedType,
                    left.kind == ValueKind.COPY && right.kind == ValueKind.COPY
                            ? ValueKind.COPY
                            : ValueKind.MOVE_ONLY,
                    null);
        }
        if (expr instanceof Ast.CallExpr call) {
            return checkCall(call, scope);
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr receiverName) {
                VarState receiverState = scope.lookup(receiverName.name());
                if (receiverState != null && isScopedMutexReceiver(receiverState)) {
                    requireUsable(receiverState, receiverName.name(), false);
                    Ast.TypeRef concreteReceiver = receiverType(member.receiver(), scope);
                    Ast.ClassDecl klass = concreteReceiver == null ? null : findClass(concreteReceiver.name());
                    if (klass != null) {
                        ResolvedField target = findFieldTarget(klass, concreteReceiver, member.member(), new LinkedHashSet<>());
                        if (target != null) {
                            Ast.TypeRef fieldType = substituteType(
                                    ownershipFieldType(target.field()),
                                    genericBindings(target.owner().genericParameters(), target.ownerType().arguments()));
                            if (!isCopyType(fieldType)) {
                                throw error("cannot extract move-only field '" + target.owner().name() + "." + member.member()
                                        + "' from protected mutex state; operate on it inside with_lock or replace the field as a whole");
                            }
                            return new ValueInfo(fieldType, ValueKind.COPY, null);
                        }
                        if (hasMethodNamed(klass, member.member(), new LinkedHashSet<>())) {
                            throw error("cannot extract a bound method from protected mutex state; invoke it directly while the guard is live");
                        }
                    }
                    if (isMutexGuardType(receiverState.type)) {
                        throw error("unknown or non-extractable MutexGuard member '" + member.member() + "'");
                    }
                }
            }
            checkExpr(member.receiver(), scope, false);
            Ast.TypeRef concreteReceiver = receiverType(member.receiver(), scope);
            if (concreteReceiver != null
                    && concreteReceiver.name().equals("DynamicStruct")
                    && concreteReceiver.arguments().size() == 1) {
                Ast.TypeRef valueType = concreteReceiver.arguments().getFirst();
                return new ValueInfo(valueType, kindOfType(valueType), null);
            }
            Ast.ClassDecl klass = concreteReceiver == null ? null : findClass(concreteReceiver.name());
            if (klass != null) {
                ResolvedField target = findFieldTarget(klass, concreteReceiver, member.member(), new LinkedHashSet<>());
                if (target != null) {
                    Ast.TypeRef fieldType = substituteType(
                            ownershipFieldType(target.field()),
                            genericBindings(target.owner().genericParameters(), target.ownerType().arguments()));
                    ValueKind fieldKind = kindOfType(fieldType);
                    if (consuming && isRootedAtActorSelf(member.receiver(), scope) && fieldKind != ValueKind.COPY) {
                        throw error("cannot move actor-owned field '" + member.member()
                                + "' out of its mailbox turn; return a copy/immutable value or explicit shared snapshot");
                    }
                    return new ValueInfo(fieldType, fieldKind, null);
                }
            }
            return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            ValueInfo receiver = checkExpr(indexed.receiver(), scope, false);
            checkExpr(indexed.index(), scope, false);
            Ast.TypeRef elementType = collectionElementType(receiver.type);
            ValueKind elementKind = elementType.name().equals("$infer$") ? ValueKind.MOVE_ONLY : kindOfType(elementType);
            if (consuming && isRootedAtActorSelf(indexed.receiver(), scope) && elementKind != ValueKind.COPY) {
                throw error("cannot move actor-owned indexed state out of its mailbox turn");
            }
            return new ValueInfo(elementType, elementKind, null);
        }
        if (expr instanceof Ast.NewExpr created) {
            List<Ast.TypeRef> argumentTypes = new ArrayList<>(created.arguments().size());
            for (Ast.Expr arg : created.arguments()) {
                ValueInfo info = checkExpr(arg, scope, true);
                if (containsMutexGuardType(info.type)) {
                    throw error("MutexGuard cannot be stored in a constructed object");
                }
                argumentTypes.add(info.type);
            }
            Ast.TypeRef constructedType = inferConstructedType(created, argumentTypes);
            return new ValueInfo(constructedType, ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            if (mutexCriticalSectionDepth > 0 || scope.hasLiveMutexGuard()) {
                throw error("cannot await while holding a MutexGuard; release the guard before suspension");
            }
            ValueInfo awaitedValue = checkExpr(awaited.expression(), scope, consuming);
            if (awaitedValue.type.name().equals("Future") && awaitedValue.type.arguments().size() == 1) {
                Ast.TypeRef result = awaitedValue.type.arguments().getFirst();
                return new ValueInfo(result, kindOfType(result), null);
            }
            return awaitedValue;
        }
        if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) {
                ValueInfo info = checkExpr(item, scope, true);
                if (containsMutexGuardType(info.type)) throw error("MutexGuard cannot be stored in an array/list");
            }
            return new ValueInfo(Ast.TypeRef.simple("Array"), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            boolean copy = true;
            for (Ast.Expr item : tuple.elements()) {
                ValueInfo info = checkExpr(item, scope, true);
                if (containsMutexGuardType(info.type)) throw error("MutexGuard cannot be stored in a tuple");
                copy &= info.kind == ValueKind.COPY;
            }
            return new ValueInfo(Ast.TypeRef.inferred(), copy ? ValueKind.COPY : ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.ObjectExpr object) {
            boolean dynamic = false;
            for (Ast.ObjectField field : object.fields()) {
                if (field.isDynamic()) {
                    dynamic = true;
                    ValueInfo key = checkExpr(field.dynamicName(), scope, false);
                    if (containsMutexGuardType(key.type)) {
                        throw error("dynamic object keys cannot contain MutexGuard");
                    }
                }
                ValueInfo info = checkExpr(field.value(), scope, true);
                if (containsMutexGuardType(info.type)) throw error("MutexGuard cannot be stored in an object/map");
            }
            return new ValueInfo(
                    dynamic ? new Ast.TypeRef("DynamicStruct", List.of(Ast.TypeRef.inferred()), false)
                            : Ast.TypeRef.simple("obj"),
                    ValueKind.MOVE_ONLY,
                    null);
        }
        if (expr instanceof Ast.LambdaExpr lambda) return checkLambda(lambda, scope, null);
        return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
    }

    private ValueInfo checkCall(Ast.CallExpr call, Scope scope) {
        if (call.callee() instanceof Ast.MemberExpr factoryCall
                && factoryCall.receiver() instanceof Ast.NameExpr factory
                && (factory.name().equals("Mutex") || factory.name().equals("SharedMutex"))
                && factoryCall.member().equals("new")
                && call.arguments().size() == 1) {
            ValueInfo owned = checkExpr(call.arguments().getFirst(), scope, true);
            if (owned.type != null && owned.type.isBorrow()) {
                throw error(factory.name()
                        + ".new requires an owned value; borrowed values cannot become mutex state");
            }
            if (containsMutexGuardType(owned.type)) {
                throw error(factory.name() + ".new cannot hide a guard-bearing value");
            }
            Ast.TypeRef mutexType = new Ast.TypeRef(factory.name(), List.of(owned.type), false);
            return new ValueInfo(mutexType, factory.name().equals("SharedMutex") ? ValueKind.COPY : ValueKind.MOVE_ONLY, null);
        }

        if (call.callee() instanceof Ast.NameExpr name
                && (name.name().equals("Some") || name.name().equals("Ok") || name.name().equals("Err"))) {
            if (call.arguments().size() != 1) {
                return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
            }
            ValueInfo payload = checkExpr(call.arguments().getFirst(), scope, true);
            if (payload.kind == ValueKind.IMM_BORROW || payload.kind == ValueKind.MUT_BORROW
                    || (payload.type != null && payload.type.isBorrow())) {
                throw error(name.name()
                        + " cannot store a borrow in an owned sum value until explicit lifetime parameters are supported");
            }
            if (name.name().equals("Some")) {
                Ast.TypeRef type = new Ast.TypeRef("Option", List.of(payload.type), false);
                return new ValueInfo(type, kindOfType(type), null);
            }
            Ast.TypeRef unknown = Ast.TypeRef.inferred();
            Ast.TypeRef type = name.name().equals("Ok")
                    ? new Ast.TypeRef("Result", List.of(payload.type, unknown), false)
                    : new Ast.TypeRef("Result", List.of(unknown, payload.type), false);
            return new ValueInfo(type, ValueKind.MOVE_ONLY, null);
        }

        if (call.callee() instanceof Ast.NameExpr name) {
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) {
                CallSignature signature = specializeCall(
                        fn.genericParameters(), fn.genericParameters(),
                        fn.parameters(), fn.returnType(), call, scope, Map.of());
                checkArguments(call.arguments(), signature.parameters(), scope, "function " + fn.name());
                Ast.TypeRef result = fn.trapped() ? trapResultType(signature.result()) : signature.result();
                return new ValueInfo(result, kindOfType(result), null);
            }
        }

        if (call.callee() instanceof Ast.MemberExpr qualified
                && qualified.receiver() instanceof Ast.NameExpr namespace) {
            Ast.FunctionDecl fn = findFunction(namespace.name() + "." + qualified.member());
            if (fn != null) {
                CallSignature signature = specializeCall(
                        fn.genericParameters(), fn.genericParameters(),
                        fn.parameters(), fn.returnType(), call, scope, Map.of());
                checkArguments(
                        call.arguments(),
                        signature.parameters(),
                        scope,
                        "function " + namespace.name() + "." + qualified.member());
                Ast.TypeRef result = fn.trapped() ? trapResultType(signature.result()) : signature.result();
                return new ValueInfo(result, kindOfType(result), null);
            }
        }

        if (call.callee() instanceof Ast.MemberExpr member) {
            ValueInfo sumCall = checkBuiltinSumCall(member, call.arguments(), scope);
            if (sumCall != null) return sumCall;

            Ast.ClassDecl staticClass = classNamespaceOf(member.receiver(), scope);
            if (staticClass != null) {
                Ast.MethodDecl staticFunction = findStaticMethod(
                        staticClass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                if (staticFunction != null) {
                    CallSignature signature = specializeCall(
                            staticFunction.genericParameters(),
                            staticFunction.genericParameters(),
                            staticFunction.parameters(),
                            staticFunction.returnType(),
                            call,
                            scope,
                            Map.of());
                    checkArguments(
                            call.arguments(),
                            signature.parameters(),
                            scope,
                            "static function " + staticClass.name() + "." + staticFunction.name());
                    return new ValueInfo(signature.result(), kindOfType(signature.result()), null);
                }
            }

            if (member.receiver() instanceof Ast.NameExpr receiverName) {
                VarState receiverState = scope.lookup(receiverName.name());
                if (receiverState != null) {
                    requireUsable(receiverState, receiverName.name(), false);
                    Ast.TypeRef receiverType = receiverState.type;
                    if ((receiverType.name().equals("Mutex") || receiverType.name().equals("SharedMutex"))
                            && receiverType.arguments().size() == 1) {
                        Ast.TypeRef element = receiverType.arguments().getFirst();
                        if (member.member().equals("lock") && call.arguments().isEmpty()) {
                            return new ValueInfo(new Ast.TypeRef("MutexGuard", List.of(element), false), ValueKind.MOVE_ONLY, null);
                        }
                        if (member.member().equals("try_lock") && call.arguments().isEmpty()) {
                            Ast.TypeRef guard = new Ast.TypeRef("MutexGuard", List.of(element), false);
                            return new ValueInfo(new Ast.TypeRef("Option", List.of(guard), false), ValueKind.MOVE_ONLY, null);
                        }
                        if (member.member().equals("lock_async") && call.arguments().isEmpty()) {
                            Ast.TypeRef guard = new Ast.TypeRef("MutexGuard", List.of(element), false);
                            return new ValueInfo(new Ast.TypeRef("Future", List.of(guard), false), ValueKind.MOVE_ONLY, null);
                        }
                        if ((member.member().equals("with_lock") || member.member().equals("recover"))
                                && call.arguments().size() == 1) {
                            if (!(call.arguments().getFirst() instanceof Ast.LambdaExpr lambda)) {
                                throw error(member.member()
                                        + " requires an inline lambda so protected mutex state remains lexical");
                            }
                            if (member.member().equals("recover") && !receiverType.name().equals("SharedMutex")) {
                                throw error("recover is only available on SharedMutex<T>");
                            }
                            if (lambda.parameters().size() != 1) {
                                throw error(member.member() + " callback must accept exactly one protected-value parameter");
                            }
                            Ast.Param original = lambda.parameters().getFirst();
                            Ast.Param protectedParam = new Ast.Param(
                                    Ast.TypeRef.borrowed(element, true),
                                    original.name(),
                                    original.structural(),
                                    false);
                            Ast.LambdaExpr protectedLambda = new Ast.LambdaExpr(
                                    List.of(protectedParam), lambda.expressionBody(), lambda.blockBody());
                            mutexCriticalSectionDepth++;
                            try {
                                checkLambda(protectedLambda, scope, null);
                            } finally {
                                mutexCriticalSectionDepth--;
                            }
                            return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
                        }
                    }
                    if (isMutexGuardType(receiverType) && member.member().equals("release") && call.arguments().isEmpty()) {
                        move(receiverState, receiverName.name());
                        return new ValueInfo(Ast.TypeRef.simple("void"), ValueKind.COPY, null);
                    }
                    if (isMutexGuardType(receiverType) && member.member().equals("is_released") && call.arguments().isEmpty()) {
                        return new ValueInfo(Ast.TypeRef.simple("bool"), ValueKind.COPY, null);
                    }
                }
            }
            checkExpr(member.receiver(), scope, false);
            Ast.TypeRef concreteReceiver = receiverType(member.receiver(), scope);
            Ast.ClassDecl klass = concreteReceiver == null ? null : findClass(concreteReceiver.name());
            ResolvedMethod target = klass == null ? null
                    : findMethodTarget(klass, concreteReceiver, member.member(), call.arguments().size(), new LinkedHashSet<>());
            if (target != null) {
                Ast.MethodDecl method = target.method();
                if (AnnotationExpander.isGeneratedFromJsonSetter(method)) {
                    ensureMutableReceiver(member.receiver(), scope, "generated JSON setter '" + method.name() + "'");
                }
                boolean protectedReceiver = false;
                if (member.receiver() instanceof Ast.NameExpr receiverName) {
                    VarState receiverState = scope.lookup(receiverName.name());
                    protectedReceiver = receiverState != null && isScopedMutexReceiver(receiverState);
                }
                Map<String, Ast.TypeRef> ownerBindings = genericBindings(
                        target.owner().genericParameters(), target.ownerType().arguments());
                List<String> allGenerics = new ArrayList<>(target.owner().genericParameters());
                allGenerics.addAll(method.genericParameters());
                CallSignature signature = specializeCall(
                        allGenerics, method.genericParameters(),
                        method.parameters(), method.returnType(), call, scope, ownerBindings);
                checkArguments(call.arguments(), signature.parameters(), scope, "method " + method.name());
                if (protectedReceiver && !isCopyType(signature.result())
                        && !signature.result().name().equals("void")) {
                    throw error("method '" + target.owner().name() + "." + method.name()
                            + "' cannot return move-only state through a mutex guard/critical-section borrow");
                }
                return new ValueInfo(signature.result(), kindOfType(signature.result()), null);
            }
        }

        checkExpr(call.callee(), scope, false);
        for (Ast.Expr arg : call.arguments()) {
            ValueInfo argument = checkExpr(arg, scope, true);
            if (containsMutexGuardType(argument.type)) {
                throw error("guard-bearing values cannot cross an arbitrary call boundary");
            }
        }
        return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
    }

    private ValueInfo checkBuiltinSumCall(Ast.MemberExpr member, List<Ast.Expr> arguments, Scope scope) {
        boolean query = member.member().equals("is_some") || member.member().equals("is_none")
                || member.member().equals("is_ok") || member.member().equals("is_err");
        boolean extracting = member.member().equals("unwrap") || member.member().equals("unwrap_safe")
                || member.member().equals("expect") || member.member().equals("unwrap_or");
        if (!query && !extracting) return null;

        ValueInfo receiver = checkExpr(member.receiver(), scope, false);
        Ast.TypeRef receiverType = receiver.type;
        if (receiverType == null || receiverType.isBorrow()) return null;
        boolean option = receiverType.name().equals("Option") && receiverType.arguments().size() == 1;
        boolean result = receiverType.name().equals("Result") && receiverType.arguments().size() == 2;
        if (!option && !result) return null;

        if (query) {
            for (Ast.Expr argument : arguments) checkExpr(argument, scope, true);
            return new ValueInfo(Ast.TypeRef.simple("bool"), ValueKind.COPY, null);
        }

        if (member.receiver() instanceof Ast.NameExpr receiverName) {
            VarState state = scope.lookup(receiverName.name());
            if (state != null && state.kind == ValueKind.MOVE_ONLY) move(state, receiverName.name());
        }

        Ast.TypeRef okType = option ? receiverType.arguments().getFirst() : receiverType.arguments().get(0);
        if (okType.isBorrow()) {
            throw error(member.member()
                    + " cannot extract a borrow from an owned Option/Result until explicit lifetime parameters are supported");
        }

        if (member.member().equals("unwrap_or") && containsMutexGuardType(okType)) {
            throw error("unwrap_or cannot eagerly discard a MutexGuard fallback; use explicit branching so every guard is released");
        }
        for (Ast.Expr argument : arguments) {
            ValueInfo arg = checkExpr(argument, scope, true);
            if (containsMutexGuardType(arg.type) && !member.member().equals("unwrap_or")) {
                throw error(member.member() + " argument cannot contain MutexGuard");
            }
        }

        if (member.member().equals("unwrap_safe")) {
            if (option) {
                Ast.TypeRef safe = new Ast.TypeRef(
                        "Result",
                        List.of(okType, Ast.TypeRef.simple("OptionUnwrapError")),
                        false);
                return new ValueInfo(safe, kindOfType(safe), null);
            }
            return new ValueInfo(receiverType, kindOfType(receiverType), null);
        }
        return new ValueInfo(okType, kindOfType(okType), null);
    }

    private void checkArguments(List<Ast.Expr> arguments, List<Ast.Param> params, Scope scope, String callable) {
        if (arguments.size() != params.size()) return; // arity is TypeChecker's responsibility
        for (int i = 0; i < arguments.size(); i++) {
            Ast.Expr arg = arguments.get(i);
            Ast.Param param = params.get(i);
            if (param.structural() && !param.type().isBorrow()) {
                ValueInfo argument = checkExpr(arg, scope, false);
                if (containsMutexGuardType(argument.type)
                        || (mutexCriticalSectionDepth > 0 && argument.type.isBorrow())) {
                    throw error(callable + " argument " + (i + 1)
                            + " cannot consume protected mutex state through a structural by-value parameter");
                }
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
                    if (isActorConfinedBorrow(owner)) {
                        throw error("actor self borrow cannot cross an ordinary callable boundary");
                    }
                    continue; // temporary borrow ends at call boundary
                }
                if (arg instanceof Ast.NameExpr name) {
                    VarState state = requireState(scope, name.name());
                    requireUsable(state, name.name(), false);
                    if (isActorConfinedBorrow(state)) {
                        throw error("actor self borrow cannot cross an ordinary callable boundary");
                    }
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
            ValueInfo argument = checkExpr(arg, scope, true);
            if (containsMutexGuardType(argument.type)) {
                throw error(callable + " argument " + (i + 1) + " cannot consume a guard-bearing value");
            }
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
            if (isMutexGuardType(state.type)) return;
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
            if (containsMutexGuardType(source.type)) {
                throw error("closure cannot capture guard-bearing value '" + capture.name + "'; MutexGuard values are lexical");
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
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard()) blockLocals.add(binding.name());
                }
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
        else if (expr instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField field : e.fields()) {
                if (field.isDynamic()) {
                    scanExpr(field.dynamicName(), locals, outer, recursiveBinding, captures, false);
                }
                scanExpr(field.value(), locals, outer, recursiveBinding, captures, false);
            }
        }
        else if (expr instanceof Ast.LambdaExpr) {
            // Nested lambda performs its own capture analysis when checked.
        }
    }

    private Ast.ClassDecl classNamespaceOf(Ast.Expr expr, Scope scope) {
        if (expr instanceof Ast.NameExpr name) {
            if (scope.lookup(name.name()) != null) return null;
            return findClass(name.name());
        }
        if (expr instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace
                && scope.lookup(namespace.name()) == null) {
            return findClass(namespace.name() + "." + member.member());
        }
        return null;
    }

    private Ast.TypeRef receiverType(Ast.Expr receiver, Scope scope) {
        Ast.TypeRef type = null;
        if (receiver instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            if (state != null) type = state.type;
        } else if (receiver instanceof Ast.NewExpr created) type = created.type();
        if (type == null) return null;
        if (type.isBorrow()) type = type.borrowedTarget();
        if (isMutexGuardType(type)) type = type.arguments().getFirst();
        return type;
    }

    private Ast.ClassDecl classOfReceiver(Ast.Expr receiver, Scope scope) {
        Ast.TypeRef type = receiverType(receiver, scope);
        return type == null ? null : findClass(type.name());
    }

    private Map<String, Ast.TypeRef> genericBindings(List<String> names, List<Ast.TypeRef> arguments) {
        Map<String, Ast.TypeRef> result = new HashMap<>();
        if (names.size() != arguments.size()) return result;
        for (int i = 0; i < names.size(); i++) result.put(names.get(i), arguments.get(i));
        return result;
    }

    private Ast.TypeRef substituteType(Ast.TypeRef type, Map<String, Ast.TypeRef> bindings) {
        if (type == null) return null;
        if (type.isBorrow()) {
            return Ast.TypeRef.borrowed(substituteType(type.borrowedTarget(), bindings), type.mutableBorrow());
        }
        Ast.TypeRef replacement = bindings.get(type.name());
        if (replacement != null && type.arguments().isEmpty() && !type.inferArguments()) return replacement;
        return new Ast.TypeRef(
                type.name(),
                type.arguments().stream().map(arg -> substituteType(arg, bindings)).toList(),
                type.inferArguments());
    }

    private Ast.TypeRef concreteParentType(Ast.TypeRef parentRef, Ast.ClassDecl child, Ast.TypeRef childType) {
        return substituteType(parentRef, genericBindings(child.genericParameters(), childType.arguments()));
    }

    private Ast.TypeRef inferConstructedType(Ast.NewExpr created, List<Ast.TypeRef> argumentTypes) {
        if (!created.type().inferArguments()) return created.type();
        Ast.ClassDecl klass = findClass(created.type().name());
        if (klass == null || klass.genericParameters().isEmpty()) return created.type();

        Ast.TypeRef patternType = new Ast.TypeRef(
                created.type().name(),
                klass.genericParameters().stream().map(Ast.TypeRef::simple).toList(),
                false);
        List<ResolvedField> fields = effectiveFieldTargets(klass, patternType, new LinkedHashSet<>());
        Map<String, Ast.TypeRef> bindings = new HashMap<>();
        Set<String> genericNames = Set.copyOf(klass.genericParameters());

        for (int i = 0; i < Math.min(argumentTypes.size(), fields.size()); i++) {
            ResolvedField target = fields.get(i);
            Ast.TypeRef fieldPattern = substituteType(
                    target.field().type(),
                    genericBindings(target.owner().genericParameters(), target.ownerType().arguments()));
            inferGenericBindings(fieldPattern, argumentTypes.get(i), genericNames, bindings, Set.of());
        }

        List<Ast.TypeRef> inferred = new ArrayList<>(klass.genericParameters().size());
        for (String generic : klass.genericParameters()) {
            Ast.TypeRef bound = bindings.get(generic);
            if (bound == null || bound.name().equals("$infer$")) return created.type();
            inferred.add(bound);
        }
        return new Ast.TypeRef(created.type().name(), inferred, false);
    }

    private List<ResolvedField> effectiveFieldTargets(
            Ast.ClassDecl klass, Ast.TypeRef concreteType, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) return List.of();
        LinkedHashMap<String, ResolvedField> fields = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = findClass(parentRef.name());
            if (parent == null) continue;
            Ast.TypeRef parentType = concreteParentType(parentRef, klass, concreteType);
            for (ResolvedField field : effectiveFieldTargets(parent, parentType, stack)) {
                fields.putIfAbsent(field.field().name(), field);
            }
        }
        for (Ast.FieldDecl field : klass.fields()) {
            fields.put(field.name(), new ResolvedField(klass, concreteType, field));
        }
        stack.remove(klass);
        return List.copyOf(fields.values());
    }

    private ResolvedField findFieldTarget(
            Ast.ClassDecl klass, Ast.TypeRef concreteType, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                seen.remove(klass);
                return new ResolvedField(klass, concreteType, field);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = findClass(parentRef.name());
            if (parent == null) continue;
            Ast.TypeRef parentType = concreteParentType(parentRef, klass, concreteType);
            ResolvedField found = findFieldTarget(parent, parentType, name, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.MethodDecl findStaticMethod(
            Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() && method.name().equals(name) && method.parameters().size() == arity) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = findClass(parentRef.name());
            if (parent == null) continue;
            Ast.MethodDecl found = findStaticMethod(parent, name, arity, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private ResolvedMethod findMethodTarget(
            Ast.ClassDecl klass, Ast.TypeRef concreteType, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name) && method.parameters().size() == arity) {
                seen.remove(klass);
                return new ResolvedMethod(klass, concreteType, method);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = findClass(parentRef.name());
            if (parent == null) continue;
            Ast.TypeRef parentType = concreteParentType(parentRef, klass, concreteType);
            ResolvedMethod found = findMethodTarget(parent, parentType, name, arity, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.TypeRef syntacticType(Ast.Expr expr, Scope scope) {
        if (expr instanceof Ast.LiteralExpr literal) return inferLiteralType(literal.value());
        if (expr instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            return state == null ? Ast.TypeRef.inferred() : state.type;
        }
        if (expr instanceof Ast.NewExpr created) return created.type();
        if (expr instanceof Ast.UnaryExpr unary) {
            Ast.TypeRef operand = syntacticType(unary.operand(), scope);
            if (unary.operator().equals("&") || unary.operator().equals("&mut")) {
                return Ast.TypeRef.borrowed(operand, unary.operator().equals("&mut"));
            }
            return operand;
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            return Ast.TypeRef.tupleType(tuple.elements().stream().map(item -> syntacticType(item, scope)).toList());
        }
        if (expr instanceof Ast.ListExpr list && !list.elements().isEmpty()) {
            return new Ast.TypeRef("Array", List.of(syntacticType(list.elements().getFirst(), scope)), false);
        }
        return Ast.TypeRef.inferred();
    }

    private void inferGenericBindings(
            Ast.TypeRef pattern,
            Ast.TypeRef actual,
            Set<String> genericNames,
            Map<String, Ast.TypeRef> bindings,
            Set<String> fixed) {
        if (pattern == null || actual == null || actual.name().equals("$infer$")) return;
        if (genericNames.contains(pattern.name()) && pattern.arguments().isEmpty() && !pattern.inferArguments()) {
            if (!fixed.contains(pattern.name())) bindings.putIfAbsent(pattern.name(), actual);
            return;
        }
        if (pattern.isBorrow() && actual.isBorrow()) {
            inferGenericBindings(pattern.borrowedTarget(), actual.borrowedTarget(), genericNames, bindings, fixed);
            return;
        }
        if (pattern.name().equals(actual.name()) && pattern.arguments().size() == actual.arguments().size()) {
            for (int i = 0; i < pattern.arguments().size(); i++) {
                inferGenericBindings(pattern.arguments().get(i), actual.arguments().get(i), genericNames, bindings, fixed);
            }
        }
    }

    private CallSignature specializeCall(
            List<String> allGenericNames,
            List<String> explicitGenericNames,
            List<Ast.Param> parameters,
            Ast.TypeRef result,
            Ast.CallExpr call,
            Scope scope,
            Map<String, Ast.TypeRef> initialBindings) {
        Map<String, Ast.TypeRef> bindings = new HashMap<>(initialBindings);
        Set<String> fixed = new HashSet<>(initialBindings.keySet());
        if (!call.typeArguments().isEmpty() && call.typeArguments().size() == explicitGenericNames.size()) {
            for (int i = 0; i < explicitGenericNames.size(); i++) {
                bindings.put(explicitGenericNames.get(i), call.typeArguments().get(i));
                fixed.add(explicitGenericNames.get(i));
            }
        }
        for (int i = 0; i < Math.min(parameters.size(), call.arguments().size()); i++) {
            inferGenericBindings(parameters.get(i).type(), syntacticType(call.arguments().get(i), scope),
                    Set.copyOf(allGenericNames), bindings, fixed);
        }
        List<Ast.Param> specialized = parameters.stream()
                .map(param -> new Ast.Param(
                        substituteType(param.type(), bindings),
                        param.name(), param.structural(), param.mutable()))
                .toList();
        return new CallSignature(specialized, substituteType(result, bindings));
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

    private boolean hasMethodNamed(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return false;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name)) {
                seen.remove(klass);
                return true;
            }
        }
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.ClassDecl p = findClass(parent.name());
            if (p == null) continue;
            if (hasMethodNamed(p, name, seen)) {
                seen.remove(klass);
                return true;
            }
        }
        seen.remove(klass);
        return false;
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

    private boolean isRootedAtActorSelf(Ast.Expr expr, Scope scope) {
        Ast.Expr current = expr;
        while (true) {
            if (current instanceof Ast.MemberExpr member) current = member.receiver();
            else if (current instanceof Ast.IndexExpr indexed) current = indexed.receiver();
            else break;
        }
        if (!(current instanceof Ast.NameExpr name)) return false;
        VarState root = scope.lookup(name.name());
        return root != null && isActorConfinedBorrow(root);
    }

    private boolean isActorConfinedBorrow(VarState state) {
        VarState current = state;
        Set<VarState> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            if ("self".equals(current.debugName)
                    && current.kind == ValueKind.MUT_BORROW
                    && current.origin == Origin.PARAM) {
                Ast.TypeRef type = current.type.isBorrow() ? current.type.borrowedTarget() : current.type;
                Ast.ClassDecl klass = findClass(type.name());
                return klass != null && klass.actorKind() != Ast.ActorKind.NONE;
            }
            current = current.borrowSource;
        }
        return false;
    }

    private Ast.TypeRef trapResultType(Ast.TypeRef successType) {
        Ast.TypeRef success = successType.name().equals("void")
                ? Ast.TypeRef.tupleType(List.of())
                : successType;
        return Ast.TypeRef.tupleType(List.of(
                new Ast.TypeRef("Option", List.of(success), false),
                new Ast.TypeRef("Option", List.of(Ast.TypeRef.simple("Exception")), false)));
    }

    private Ast.TypeRef collectionElementType(Ast.TypeRef type) {
        if (type == null) return Ast.TypeRef.inferred();
        Ast.TypeRef concrete = type.isBorrow() ? type.borrowedTarget() : type;
        if ((concrete.name().equals("Array") || concrete.name().equals("List")
                || concrete.name().equals("DynamicStruct")) && concrete.arguments().size() == 1) {
            return concrete.arguments().getFirst();
        }
        if (concrete.isTupleType() && !concrete.arguments().isEmpty()) {
            Ast.TypeRef first = concrete.arguments().getFirst();
            boolean same = concrete.arguments().stream().allMatch(first::equals);
            return same ? first : Ast.TypeRef.inferred();
        }
        return Ast.TypeRef.inferred();
    }

    private Ast.TypeRef destructureBindingType(Ast.DestructureStmt destructure, Ast.TypeRef source, int index, String name) {
        if (source == null) return Ast.TypeRef.inferred();
        if (destructure.kind() == Ast.DestructureKind.SEQUENCE) {
            if (source.isTupleType() && index < source.arguments().size()) return source.arguments().get(index);
            if ((source.name().equals("Array") || source.name().equals("List")) && source.arguments().size() == 1) {
                return source.arguments().getFirst();
            }
        } else {
            Ast.TypeRef concrete = source.isBorrow() ? source.borrowedTarget() : source;
            if (concrete.isRecordType()) {
                Ast.TypeRef member = concrete.recordMembers().get(name);
                if (member != null) return member;
            }
            if (concrete.name().equals("DynamicStruct") && concrete.arguments().size() == 1) {
                return concrete.arguments().getFirst();
            }
            Ast.ClassDecl klass = findClass(concrete.name());
            if (klass != null) {
                ResolvedField target = findFieldTarget(klass, concrete, name, new LinkedHashSet<>());
                if (target != null && target.field().visibility() == Ast.Visibility.PUBLIC) {
                    return substituteType(
                            target.field().type(),
                            genericBindings(target.owner().genericParameters(), target.ownerType().arguments()));
                }
            }
        }
        return Ast.TypeRef.inferred();
    }

    private Ast.TypeRef joinConditionalType(Ast.TypeRef left, Ast.TypeRef right) {
        if (left == null) return right == null ? Ast.TypeRef.inferred() : right;
        if (right == null) return left;
        if (left.equals(right)) return left;
        if (left.name().equals("$infer$")) return right;
        if (right.name().equals("$infer$")) return left;
        return Ast.TypeRef.union(List.of(left, right));
    }

    private ValueKind kindOfType(Ast.TypeRef type) {
        if (type == null) return ValueKind.MOVE_ONLY;
        if (type.isBorrow()) return type.mutableBorrow() ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW;
        return isCopyType(type) ? ValueKind.COPY : ValueKind.MOVE_ONLY;
    }

    private boolean isCopyType(Ast.TypeRef type) {
        if (type == null || type.isBorrow()) return false;
        if (type.isUnion()) return type.arguments().stream().allMatch(this::isCopyType);
        return switch (type.name()) {
            case "i8","i16","i32","i64","u8","u16","u32","u64","int","uint","bigint",
                    "f32","f64","float","decimal","complex64","complex128","complex",
                    "bool","Bool","string","String","void","SharedMutex","OptionUnwrapError" -> true;
            case "Option" -> type.arguments().size() == 1 && isCopyType(type.arguments().getFirst());
            case "Result" -> type.arguments().size() == 2
                    && isCopyType(type.arguments().get(0))
                    && isCopyType(type.arguments().get(1));
            default -> false;
        };
    }

    private boolean isScopedMutexReceiver(VarState state) {
        return isMutexGuardType(state.type)
                || (mutexCriticalSectionDepth > 0 && state.type.isBorrow() && state.type.mutableBorrow());
    }

    private static boolean isMutexGuardType(Ast.TypeRef type) {
        return type != null && !type.isBorrow() && type.name().equals("MutexGuard") && type.arguments().size() == 1;
    }

    private static boolean containsMutexGuardType(Ast.TypeRef type) {
        if (type == null) return false;
        if (type.isBorrow()) return containsMutexGuardType(type.borrowedTarget());
        if (isMutexGuardType(type)) return true;
        for (Ast.TypeRef argument : type.arguments()) {
            if (containsMutexGuardType(argument)) return true;
        }
        return false;
    }

    private static boolean containsAcquiredMutexGuardType(Ast.TypeRef type) {
        if (type == null || type.isBorrow()) return false;
        if (isMutexGuardType(type)) return true;
        if (type.name().equals("Future")) return false;
        for (Ast.TypeRef argument : type.arguments()) {
            if (containsAcquiredMutexGuardType(argument)) return true;
        }
        return false;
    }

    private Ast.TypeRef ownershipFieldType(Ast.FieldDecl field) {
        if (field.type() != null) return field.type();
        if (field.initializer() instanceof Ast.LiteralExpr literal) {
            return inferLiteralType(literal.value());
        }
        return Ast.TypeRef.inferred();
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

        private boolean hasLiveMutexGuard() {
            for (VarState state : visibleStates()) {
                if (!state.moved && containsAcquiredMutexGuardType(state.type)) return true;
            }
            return false;
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
