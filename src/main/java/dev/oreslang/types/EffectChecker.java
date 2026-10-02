package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time allocation/copy effect analysis.
 *
 * <p>{@code @NoAlloc} and {@code @NoCopy} are hard semantic contracts, not
 * optimizer hints. The checker is deliberately conservative: when a constrained
 * callable reaches a call whose implementation/effects cannot be proven, that
 * call is treated as potentially allocating and/or copying.</p>
 *
 * <p>The pass runs after type/ownership checking and operates on the composed
 * AST so trait-composed methods are checked in their concrete aggregate.</p>
 */
public final class EffectChecker {
    private enum Effect { ALLOC, COPY }

    private static final String NO_ALLOC = "NoAlloc";
    private static final String NO_COPY = "NoCopy";

    private final Map<String, Callable> functions = new HashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Set<String> ambiguousClasses = new HashSet<>();

    private final List<Callable> callables = new ArrayList<>();
    private final IdentityHashMap<Ast.ClassDecl, List<Callable>> methodsByClass = new IdentityHashMap<>();

    private final IdentityHashMap<Callable, EnumSet<Effect>> directEffects = new IdentityHashMap<>();
    private final IdentityHashMap<Callable, EnumMap<Effect, String>> directReasons = new IdentityHashMap<>();
    private final IdentityHashMap<Callable, List<Callable>> callees = new IdentityHashMap<>();
    private final IdentityHashMap<Callable, EnumSet<Effect>> effects = new IdentityHashMap<>();

    private final Set<Ast.ClassDecl> indexedClasses =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private EffectChecker(Ast.Program program) {
        index(program);
    }

    public static Ast.Program check(Ast.Program program) {
        EffectChecker checker = new EffectChecker(program);
        checker.analyze();
        checker.validateContracts();
        return program;
    }

    private void index(Ast.Program program) {
        List<Callable> roots = new ArrayList<>();

        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    Callable callable = Callable.function(module.name(), fn);
                    roots.add(callable);
                    callables.add(callable);
                    index(functions, ambiguousFunctions, module.name(), fn.name(), callable);
                } else if (decl instanceof Ast.ClassDecl klass) {
                    index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                    indexClass(module.name(), klass, roots);
                }
            }
        }

        // Callable-local structs/classes can own methods with effects too. They
        // are not placed in the global class map because their names are lexical.
        for (Callable root : List.copyOf(roots)) {
            indexLocalTypes(root.module(), root.body());
        }
    }

    private void indexClass(String module, Ast.ClassDecl klass, List<Callable> roots) {
        if (!indexedClasses.add(klass)) return;

        List<Callable> owned = methodsByClass.computeIfAbsent(klass, ignored -> new ArrayList<>());
        for (Ast.MethodDecl method : klass.methods()) {
            Callable callable = Callable.method(module, klass, method);
            callables.add(callable);
            roots.add(callable);
            owned.add(callable);
        }
    }

    private void indexLocalTypes(String module, List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.TypeDeclStmt local) {
                if (local.declaration() instanceof Ast.ClassDecl klass) {
                    List<Callable> added = new ArrayList<>();
                    indexClass(module, klass, added);
                    for (Callable callable : added) indexLocalTypes(module, callable.body());
                }
                continue;
            }
            if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    indexLocalTypes(module, branch.body());
                }
                indexLocalTypes(module, conditional.elseBody());
            } else if (stmt instanceof Ast.TryStmt attempted) {
                indexLocalTypes(module, attempted.body());
                indexLocalTypes(module, attempted.catchBody());
                indexLocalTypes(module, attempted.finallyBody());
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                indexLocalTypes(module, loop.body());
            } else if (stmt instanceof Ast.ForStmt loop) {
                if (loop.initializer() instanceof Ast.TypeDeclStmt local
                        && local.declaration() instanceof Ast.ClassDecl klass) {
                    List<Callable> added = new ArrayList<>();
                    indexClass(module, klass, added);
                    for (Callable callable : added) indexLocalTypes(module, callable.body());
                }
                indexLocalTypes(module, loop.body());
            }
        }
    }

    private static <T> void index(
            Map<String, T> map,
            Set<String> ambiguous,
            String module,
            String name,
            T value) {
        map.put(module + "." + name, value);
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            map.remove(name);
            ambiguous.add(name);
        }
    }

    private void analyze() {
        for (Callable callable : callables) {
            validateContractAnnotations(callable);
            directEffects.put(callable, EnumSet.noneOf(Effect.class));
            directReasons.put(callable, new EnumMap<>(Effect.class));
            callees.put(callable, new ArrayList<>());

            Scope scope = new Scope(null);
            if (callable.ownerClass() != null) {
                scope.defineType(callable.ownerClass().name(), callable.ownerClass());
                if (!callable.isStatic()) {
                    scope.define("self", Ast.TypeRef.simple(callable.ownerClass().name()));
                }
            }
            for (Ast.Param parameter : callable.parameters()) {
                scope.define(parameter.name(), parameter.type());
            }
            predeclareLocalTypes(callable.body(), scope);

            if (callable.async()) {
                addEffect(callable, Effect.ALLOC,
                        "async callable creates/schedules an asynchronous result/task");
            }

            scanBlock(callable.body(), callable, scope);
            effects.put(callable, EnumSet.copyOf(directEffects.get(callable)));
        }

        boolean changed;
        do {
            changed = false;
            for (Callable caller : callables) {
                EnumSet<Effect> accumulated = effects.get(caller);
                int before = accumulated.size();
                for (Callable callee : callees.get(caller)) {
                    accumulated.addAll(effects.get(callee));
                }
                if (accumulated.size() != before) changed = true;
            }
        } while (changed);
    }

    private void validateContracts() {
        for (Callable callable : callables) {
            validateOwnContract(callable, Effect.ALLOC, NO_ALLOC);
            validateOwnContract(callable, Effect.COPY, NO_COPY);

            if (callable.ownerClass() != null && !callable.isStatic()) {
                if (inheritsContract(callable, Effect.ALLOC, newIdentitySet())
                        && effects.get(callable).contains(Effect.ALLOC)) {
                    throw inheritedViolation(callable, Effect.ALLOC, NO_ALLOC);
                }
                if (inheritsContract(callable, Effect.COPY, newIdentitySet())
                        && effects.get(callable).contains(Effect.COPY)) {
                    throw inheritedViolation(callable, Effect.COPY, NO_COPY);
                }
            }
        }
    }

    private void validateOwnContract(Callable callable, Effect effect, String annotation) {
        if (hasAnnotation(callable.annotations(), annotation)
                && effects.get(callable).contains(effect)) {
            throw violation(callable, effect, annotation);
        }
    }

    private IllegalArgumentException inheritedViolation(
            Callable callable,
            Effect effect,
            String annotation) {
        String path = violationPath(callable, effect, newIdentitySet());
        String noun = effect == Effect.ALLOC ? "allocation" : "copy";
        return new IllegalArgumentException("inherited @" + annotation
                + " contract violated by " + callable.displayName() + ": " + noun + " effect"
                + (path == null ? "" : " via " + path));
    }

    private IllegalArgumentException violation(Callable callable, Effect effect, String annotation) {
        String path = violationPath(callable, effect, newIdentitySet());
        String noun = effect == Effect.ALLOC ? "allocation" : "copy";
        return new IllegalArgumentException("@" + annotation + " contract violated by "
                + callable.displayName() + ": " + noun + " effect"
                + (path == null ? "" : " via " + path));
    }

    private String violationPath(Callable callable, Effect effect, Set<Callable> active) {
        String direct = directReasons.get(callable).get(effect);
        if (direct != null) return direct;
        if (!active.add(callable)) return null;

        for (Callable callee : callees.get(callable)) {
            if (!effects.get(callee).contains(effect)) continue;
            String nested = violationPath(callee, effect, active);
            if (nested != null) {
                active.remove(callable);
                return "call to " + callee.displayName() + " -> " + nested;
            }
        }

        active.remove(callable);
        return null;
    }

    private void validateContractAnnotations(Callable callable) {
        Set<String> seen = new HashSet<>();
        for (Ast.Annotation annotation : callable.annotations()) {
            if (!annotation.name().equals(NO_ALLOC) && !annotation.name().equals(NO_COPY)) continue;
            if (!annotation.arguments().isEmpty()) {
                throw new IllegalArgumentException("@" + annotation.name()
                        + " does not accept arguments on " + callable.displayName());
            }
            if (!seen.add(annotation.name())) {
                throw new IllegalArgumentException("duplicate @" + annotation.name()
                        + " on " + callable.displayName());
            }
        }
    }

    private static boolean hasAnnotation(List<Ast.Annotation> annotations, String name) {
        return annotations.stream().anyMatch(annotation -> annotation.name().equals(name));
    }

    private boolean hasEffectiveContract(Callable callable, Effect effect) {
        String annotation = effect == Effect.ALLOC ? NO_ALLOC : NO_COPY;
        if (hasAnnotation(callable.annotations(), annotation)) return true;
        return callable.ownerClass() != null
                && !callable.isStatic()
                && inheritsContract(callable, effect, newIdentitySet());
    }

    private boolean inheritsContract(
            Callable callable,
            Effect effect,
            Set<Ast.ClassDecl> seen) {
        Ast.ClassDecl owner = callable.ownerClass();
        if (owner == null || callable.isStatic()) return false;

        String annotation = effect == Effect.ALLOC ? NO_ALLOC : NO_COPY;
        for (Ast.TypeRef parentRef : owner.parents()) {
            Ast.ClassDecl parent = resolveClass(parentRef.name(), callable.module(), null);
            if (parent == null || !seen.add(parent)) continue;
            Callable parentMethod = findMethodCallable(
                    parent, callable.name(), callable.arity(), false, callable.module(),
                    java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
            if (parentMethod != null) {
                if (parentMethod.visibility() == Ast.Visibility.PUBLIC
                        && hasAnnotation(parentMethod.annotations(), annotation)) {
                    return true;
                }
                if (inheritsContract(parentMethod, effect, seen)) return true;
            }
        }
        return false;
    }

    private void addEffect(Callable callable, Effect effect, String reason) {
        directEffects.get(callable).add(effect);
        directReasons.get(callable).putIfAbsent(effect, reason);
    }

    private void addOpaqueEffects(Callable callable, String description) {
        addEffect(callable, Effect.ALLOC, description + " has no provable @NoAlloc guarantee");
        addEffect(callable, Effect.COPY, description + " has no provable @NoCopy guarantee");
    }

    private void addVirtualEffects(Callable current, Callable target, String description) {
        if (!hasEffectiveContract(target, Effect.ALLOC)) {
            addEffect(current, Effect.ALLOC,
                    description + " is virtual/dynamic and has no @NoAlloc contract");
        }
        if (!hasEffectiveContract(target, Effect.COPY)) {
            addEffect(current, Effect.COPY,
                    description + " is virtual/dynamic and has no @NoCopy contract");
        }
    }

    private void addCallee(Callable caller, Callable callee) {
        List<Callable> list = callees.get(caller);
        for (Callable existing : list) if (existing == callee) return;
        list.add(callee);
    }

    private void scanBlock(List<Ast.Stmt> body, Callable current, Scope scope) {
        predeclareLocalTypes(body, scope);
        for (Ast.Stmt stmt : body) scanStmt(stmt, current, scope);
    }

    private void predeclareLocalTypes(List<Ast.Stmt> body, Scope scope) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.TypeDeclStmt local
                    && local.declaration() instanceof Ast.ClassDecl klass) {
                scope.defineType(klass.name(), klass);
            }
        }
    }

    private void scanStmt(Ast.Stmt stmt, Callable current, Scope scope) {
        if (stmt instanceof Ast.TypeDeclStmt local) {
            if (local.declaration() instanceof Ast.ClassDecl klass) {
                scope.defineType(klass.name(), klass);
            }
            return;
        }

        if (stmt instanceof Ast.BindingStmt binding) {
            scanExpr(binding.initializer(), current, scope);
            Ast.TypeRef type = binding.declaredType() != null
                    ? binding.declaredType()
                    : inferType(binding.initializer(), current, scope);
            scope.define(binding.name(), type);
            return;
        }

        if (stmt instanceof Ast.DestructureStmt destructure) {
            scanExpr(destructure.initializer(), current, scope);
            for (Ast.DestructureBinding binding : destructure.bindings()) {
                if (binding.name() != null && !binding.name().equals("_")) {
                    scope.define(binding.name(), Ast.TypeRef.inferred());
                }
            }
            return;
        }

        if (stmt instanceof Ast.ReturnStmt returned) {
            if (returned.value() != null) scanExpr(returned.value(), current, scope);
            return;
        }

        if (stmt instanceof Ast.ExprStmt expression) {
            scanExpr(expression.expression(), current, scope);
            return;
        }

        if (stmt instanceof Ast.DeferStmt deferred) {
            // The deferred expression still executes before this callable exits.
            scanExpr(deferred.expression(), current, scope);
            return;
        }

        if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                scanExpr(branch.condition(), current, scope);
                scanBlock(branch.body(), current, new Scope(scope));
            }
            scanBlock(conditional.elseBody(), current, new Scope(scope));
            return;
        }

        if (stmt instanceof Ast.TryStmt attempted) {
            scanBlock(attempted.body(), current, new Scope(scope));
            Scope caught = new Scope(scope);
            if (attempted.errorName() != null && !attempted.errorName().isBlank()) {
                caught.define(attempted.errorName(), Ast.TypeRef.inferred());
            }
            scanBlock(attempted.catchBody(), current, caught);
            scanBlock(attempted.finallyBody(), current, new Scope(scope));
            return;
        }

        if (stmt instanceof Ast.ForOfStmt loop) {
            scanExpr(loop.iterable(), current, scope);

            // Class-backed for-of is syntactic sugar for a protocol call to
            // [Symbol.iterator](). Its effects are therefore part of the loop's
            // callable contract just as if the method were invoked explicitly.
            // Built-in arrays/lists/tuples can lower to index-based iteration and
            // do not require an iterator-object allocation at the language level.
            ResolvedMethod iterator = resolveMethodCall(
                    loop.iterable(), "Symbol.iterator", 0, current, scope);
            if (iterator != null) {
                Callable target = iterator.callable();
                if (iterator.exactDispatch()
                        || target.isStatic()
                        || target.visibility() == Ast.Visibility.PRIVATE
                        || (target.ownerClass() != null && target.ownerClass().isStruct())) {
                    addCallee(current, target);
                } else {
                    addVirtualEffects(current, target,
                            "implicit for-of call to " + target.displayName());
                }
            }

            Scope bodyScope = new Scope(scope);
            Ast.TypeRef iterable = inferType(loop.iterable(), current, scope);
            Ast.TypeRef element = collectionElement(iterable);
            if (element == null && iterator != null) {
                element = iteratorElementType(
                        iterator.callable(), iterable, current.module(), scope);
            }
            bodyScope.define(loop.bindingName(), element == null ? Ast.TypeRef.inferred() : element);
            scanBlock(loop.body(), current, bodyScope);
            return;
        }

        if (stmt instanceof Ast.ForStmt loop) {
            Scope loopScope = new Scope(scope);
            if (loop.initializer() != null) scanStmt(loop.initializer(), current, loopScope);
            if (loop.condition() != null) scanExpr(loop.condition(), current, loopScope);
            if (loop.update() != null) scanExpr(loop.update(), current, loopScope);
            scanBlock(loop.body(), current, new Scope(loopScope));
        }
    }

    private void scanExpr(Ast.Expr expr, Callable current, Scope scope) {
        if (expr == null) return;
        if (expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return;

        if (expr instanceof Ast.BinaryExpr binary) {
            scanExpr(binary.left(), current, scope);
            scanExpr(binary.right(), current, scope);
            if (binary.operator().equals("+")
                    && isStringLikeBinary(binary, current, scope)) {
                addEffect(current, Effect.ALLOC, "string concatenation/materialization with '+'");
            }
            return;
        }

        if (expr instanceof Ast.UnaryExpr unary) {
            scanExpr(unary.operand(), current, scope);
            return;
        }

        if (expr instanceof Ast.AssignExpr assignment) {
            scanExpr(assignment.target(), current, scope);
            scanExpr(assignment.value(), current, scope);
            return;
        }

        if (expr instanceof Ast.ConditionalExpr conditional) {
            scanExpr(conditional.condition(), current, scope);
            scanExpr(conditional.whenTrue(), current, scope);
            scanExpr(conditional.whenFalse(), current, scope);
            return;
        }

        if (expr instanceof Ast.CallExpr call) {
            scanCall(call, current, scope);
            return;
        }

        if (expr instanceof Ast.MemberExpr member) {
            scanExpr(member.receiver(), current, scope);
            return;
        }

        if (expr instanceof Ast.IndexExpr indexed) {
            scanExpr(indexed.receiver(), current, scope);
            scanExpr(indexed.index(), current, scope);
            return;
        }

        if (expr instanceof Ast.NewExpr created) {
            addEffect(current, Effect.ALLOC, "new " + created.type().name() + "(...)");
            for (Ast.Expr argument : created.arguments()) scanExpr(argument, current, scope);

            Ast.ClassDecl klass = resolveClass(created.type().name(), current.module(), scope);
            if (klass != null) {
                scanDefaultFieldInitializers(
                        klass, created.arguments().size(), Set.of(), current, scope,
                        java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
            }
            return;
        }

        if (expr instanceof Ast.StructInitExpr struct) {
            Set<String> supplied = new HashSet<>();
            for (Ast.ObjectField field : struct.fields()) {
                supplied.add(field.name());
                scanExpr(field.value(), current, scope);
            }
            if (struct.type() != null) {
                Ast.ClassDecl klass = resolveClass(struct.type().name(), current.module(), scope);
                if (klass != null) {
                    scanDefaultFieldInitializers(
                            klass, 0, supplied, current, scope,
                            java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
                }
            }
            return;
        }

        if (expr instanceof Ast.AwaitExpr awaited) {
            scanExpr(awaited.expression(), current, scope);
            return;
        }

        if (expr instanceof Ast.ListExpr list) {
            addEffect(current, Effect.ALLOC, "array/list literal");
            for (Ast.Expr item : list.elements()) scanExpr(item, current, scope);
            return;
        }

        if (expr instanceof Ast.TupleExpr tuple) {
            // Tuples are fixed-size values. Backends may lower them inline,
            // into registers, or caller-provided storage without heap allocation.
            for (Ast.Expr item : tuple.elements()) scanExpr(item, current, scope);
            return;
        }

        if (expr instanceof Ast.ObjectExpr object) {
            addEffect(current, Effect.ALLOC, "object/map literal");
            for (Ast.ObjectField field : object.fields()) scanExpr(field.value(), current, scope);
            return;
        }

        if (expr instanceof Ast.LambdaExpr lambda) {
            // Creating a non-capturing callable can lower to a static call target.
            // A capturing closure needs an environment materialization.
            if (capturesOuterState(lambda, scope)) {
                addEffect(current, Effect.ALLOC, "capturing closure/lambda environment");
            }
            // Do not charge the lambda body to the creator. Its body executes
            // only when the callable is invoked; opaque callback invocations are
            // conservatively handled at their call sites.
        }
    }

    private void scanCall(Ast.CallExpr call, Callable current, Scope scope) {
        for (Ast.Expr argument : call.arguments()) scanExpr(argument, current, scope);

        if (call.callee() instanceof Ast.NameExpr name) {
            switch (name.name()) {
                case "borrow", "take" -> {
                    requireArity(call, name.name(), 1);
                    return;
                }
                case "copy" -> {
                    requireArity(call, "copy", 1);
                    addEffect(current, Effect.COPY, "copy(...) ownership intrinsic");
                    Ast.TypeRef operand = inferType(call.arguments().getFirst(), current, scope);
                    if (copyMayAllocate(operand, current.module(), scope, newIdentitySet())) {
                        addEffect(current, Effect.ALLOC,
                                "copy(...) materializes owned heap/storage state");
                    }
                    return;
                }
                case "share" -> {
                    requireArity(call, "share", 1);
                    Ast.TypeRef operand = inferType(call.arguments().getFirst(), current, scope);
                    if (!isZeroCopyShare(operand)) {
                        addEffect(current, Effect.COPY, "share(...) creates a snapshot copy");
                    }
                    if (copyMayAllocate(operand, current.module(), scope, newIdentitySet())) {
                        addEffect(current, Effect.ALLOC,
                                "share(...) materializes frozen heap/storage state");
                    }
                    return;
                }
                case "Some" -> {
                    requireArity(call, "Some", 1);
                    // Option is a tagged value and does not semantically require
                    // heap allocation even if the interpreter boxes it internally.
                    return;
                }
                default -> {
                    if (scope.lookup(name.name()) != null) {
                        addOpaqueEffects(current,
                                "dynamic callable binding '" + name.name() + "(...)'");
                        return;
                    }
                    Callable target = resolveFunction(name.name(), current.module());
                    if (target != null) {
                        addCallee(current, target);
                        return;
                    }
                    addOpaqueEffects(current, "unverified call '" + name.name() + "(...)'");
                    return;
                }
            }
        }

        if (call.callee() instanceof Ast.MemberExpr member) {
            scanExpr(member.receiver(), current, scope);

            if (member.receiver() instanceof Ast.NameExpr receiver
                    && (receiver.name().equals("Mutex") || receiver.name().equals("SharedMutex"))
                    && member.member().equals("new")) {
                addEffect(current, Effect.ALLOC,
                        receiver.name() + ".new(...) runtime state construction");
                return;
            }

            if (member.receiver() instanceof Ast.NameExpr namespace
                    && scope.lookup(namespace.name()) == null) {
                Callable moduleTarget = functions.get(namespace.name() + "." + member.member());
                if (moduleTarget != null) {
                    addCallee(current, moduleTarget);
                    return;
                }
            }

            ResolvedMethod resolved = resolveMethodCall(member.receiver(), member.member(),
                    call.arguments().size(), current, scope);
            if (resolved != null) {
                Callable target = resolved.callable();
                if (resolved.exactDispatch()
                        || target.isStatic()
                        || target.visibility() == Ast.Visibility.PRIVATE
                        || (target.ownerClass() != null && target.ownerClass().isStruct())) {
                    addCallee(current, target);
                } else {
                    addVirtualEffects(current, target,
                            "call to " + target.displayName());
                }
                return;
            }

            addOpaqueEffects(current,
                    "unverified call '" + renderCallee(call.callee()) + "(...)'");
            return;
        }

        scanExpr(call.callee(), current, scope);
        addOpaqueEffects(current, "dynamic callable invocation");
    }

    private void scanDefaultFieldInitializers(
            Ast.ClassDecl klass,
            int suppliedPositional,
            Set<String> suppliedNames,
            Callable current,
            Scope callerScope,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return;

        List<Ast.FieldDecl> fields = effectiveFields(
                klass, current.module(), callerScope,
                java.util.Collections.newSetFromMap(new IdentityHashMap<>()));

        Scope initializerScope = new Scope(callerScope);
        initializerScope.define("self", Ast.TypeRef.simple(klass.name()));

        int positionalIndex = 0;
        for (Ast.FieldDecl field : fields) {
            boolean positionalSupplied = !field.composed() && positionalIndex < suppliedPositional;
            boolean supplied = positionalSupplied || suppliedNames.contains(field.name());
            if (!field.composed()) positionalIndex++;
            if (!supplied && field.initializer() != null) {
                scanExpr(field.initializer(), current, initializerScope);
            }
        }
    }

    private List<Ast.FieldDecl> effectiveFields(
            Ast.ClassDecl klass,
            String module,
            Scope scope,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<String, Ast.FieldDecl> result = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClass(parentRef.name(), module, scope);
            if (parent != null) {
                for (Ast.FieldDecl field : effectiveFields(parent, module, scope, seen)) {
                    result.putIfAbsent(field.name(), field);
                }
            }
        }
        for (Ast.FieldDecl field : klass.fields()) result.put(field.name(), field);
        return List.copyOf(result.values());
    }

    private ResolvedMethod resolveMethodCall(
            Ast.Expr receiverExpr,
            String methodName,
            int arity,
            Callable current,
            Scope scope) {
        boolean exact = receiverExpr instanceof Ast.NewExpr
                || receiverExpr instanceof Ast.StructInitExpr;

        boolean staticCall = false;
        Ast.ClassDecl owner = null;

        if (receiverExpr instanceof Ast.NameExpr name) {
            Ast.TypeRef variableType = scope.lookup(name.name());
            if (variableType != null) {
                owner = classForType(variableType, current.module(), scope);
            } else if (name.name().equals("self") && current.ownerClass() != null) {
                owner = current.ownerClass();
            } else {
                owner = resolveClass(name.name(), current.module(), scope);
                staticCall = owner != null;
                exact = staticCall;
            }
        } else {
            Ast.TypeRef type = inferType(receiverExpr, current, scope);
            owner = classForType(type, current.module(), scope);
        }

        if (owner == null) return null;

        Callable target = findMethodCallable(
                owner, methodName, arity, staticCall, current.module(),
                java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        if (target == null) return null;

        return new ResolvedMethod(target, exact);
    }

    private Callable findMethodCallable(
            Ast.ClassDecl owner,
            String name,
            int arity,
            boolean isStatic,
            String module,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(owner)) return null;

        List<Callable> methods = methodsByClass.getOrDefault(owner, List.of());
        Callable found = null;
        for (Callable callable : methods) {
            if (callable.name().equals(name)
                    && callable.arity() == arity
                    && callable.isStatic() == isStatic) {
                if (found != null) return null;
                found = callable;
            }
        }
        if (found != null) return found;

        for (Ast.TypeRef parentRef : owner.parents()) {
            Ast.ClassDecl parent = resolveClass(parentRef.name(), module, null);
            if (parent == null) continue;
            Callable inherited = findMethodCallable(
                    parent, name, arity, isStatic, module, seen);
            if (inherited != null) return inherited;
        }
        return null;
    }

    private Ast.FieldDecl findField(
            Ast.ClassDecl owner,
            String name,
            String module,
            Scope scope,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(owner)) return null;
        for (Ast.FieldDecl field : owner.fields()) {
            if (field.name().equals(name)) return field;
        }
        for (Ast.TypeRef parentRef : owner.parents()) {
            Ast.ClassDecl parent = resolveClass(parentRef.name(), module, scope);
            if (parent == null) continue;
            Ast.FieldDecl inherited = findField(parent, name, module, scope, seen);
            if (inherited != null) return inherited;
        }
        return null;
    }

    private Callable resolveFunction(String name, String module) {
        Callable local = functions.get(module + "." + name);
        if (local != null) return local;
        if (ambiguousFunctions.contains(name)) return null;
        return functions.get(name);
    }

    private Ast.ClassDecl resolveClass(String name, String module, Scope scope) {
        if (scope != null) {
            Ast.ClassDecl local = scope.lookupType(name);
            if (local != null) return local;
        }
        Ast.ClassDecl inModule = classes.get(module + "." + name);
        if (inModule != null) return inModule;
        if (ambiguousClasses.contains(name)) return null;
        return classes.get(name);
    }

    private Ast.ClassDecl classForType(Ast.TypeRef type, String module, Scope scope) {
        if (type == null) return null;
        Ast.TypeRef target = type.isBorrow() ? type.borrowedTarget() : type;
        return resolveClass(target.name(), module, scope);
    }

    private Ast.TypeRef inferType(Ast.Expr expr, Callable current, Scope scope) {
        if (expr == null) return null;

        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value instanceof Boolean) return Ast.TypeRef.simple("bool");
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return Ast.TypeRef.simple("int");
            }
            if (value instanceof Float || value instanceof Double) return Ast.TypeRef.simple("float");
            if (value instanceof Ast.Imaginary) return Ast.TypeRef.simple("complex");
            if (value instanceof String) return Ast.TypeRef.simple("String");
            return null;
        }

        if (expr instanceof Ast.NameExpr name) {
            return scope.lookup(name.name());
        }

        if (expr instanceof Ast.UnaryExpr unary) {
            if (unary.operator().equals("!")) return Ast.TypeRef.simple("bool");
            Ast.TypeRef operand = inferType(unary.operand(), current, scope);
            if (unary.operator().equals("&") || unary.operator().equals("&mut")) {
                if (operand == null) return null;
                return Ast.TypeRef.borrowed(operand, unary.operator().equals("&mut"));
            }
            return operand;
        }

        if (expr instanceof Ast.BinaryExpr binary) {
            if (binary.operator().equals("==") || binary.operator().equals("!=")
                    || binary.operator().equals("<") || binary.operator().equals("<=")
                    || binary.operator().equals(">") || binary.operator().equals(">=")
                    || binary.operator().equals("|")) {
                return Ast.TypeRef.simple("bool");
            }
            Ast.TypeRef left = inferType(binary.left(), current, scope);
            Ast.TypeRef right = inferType(binary.right(), current, scope);
            if (binary.operator().equals("+")
                    && (isStringType(left) || isStringType(right))) {
                return Ast.TypeRef.simple("String");
            }
            if (isNumericType(left) && isNumericType(right)) {
                if (typeName(left).equals("complex") || typeName(right).equals("complex")) {
                    return Ast.TypeRef.simple("complex");
                }
                if (typeName(left).equals("decimal") || typeName(right).equals("decimal")) {
                    return Ast.TypeRef.simple("decimal");
                }
                if (typeName(left).equals("float") || typeName(right).equals("float")) {
                    return Ast.TypeRef.simple("float");
                }
                return Ast.TypeRef.simple("int");
            }
            return null;
        }

        if (expr instanceof Ast.AssignExpr assignment) {
            return inferType(assignment.target(), current, scope);
        }

        if (expr instanceof Ast.ConditionalExpr conditional) {
            Ast.TypeRef left = inferType(conditional.whenTrue(), current, scope);
            Ast.TypeRef right = inferType(conditional.whenFalse(), current, scope);
            return sameType(left, right) ? left : null;
        }

        if (expr instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.NameExpr name) {
                if ((name.name().equals("borrow") || name.name().equals("share"))
                        && call.arguments().size() == 1) {
                    Ast.TypeRef inner = inferType(call.arguments().getFirst(), current, scope);
                    if (inner == null) return null;
                    Ast.TypeRef target = inner.isBorrow() ? inner.borrowedTarget() : inner;
                    return Ast.TypeRef.borrowed(target, false);
                }
                if ((name.name().equals("copy") || name.name().equals("take"))
                        && call.arguments().size() == 1) {
                    return inferType(call.arguments().getFirst(), current, scope);
                }
                if (name.name().equals("Some") && call.arguments().size() == 1) {
                    Ast.TypeRef inner = inferType(call.arguments().getFirst(), current, scope);
                    return inner == null ? null : new Ast.TypeRef("Option", List.of(inner), false);
                }
                if (scope.lookup(name.name()) != null) return null;
                Callable target = resolveFunction(name.name(), current.module());
                if (target != null) return target.returnType();
            }
            if (call.callee() instanceof Ast.MemberExpr member) {
                ResolvedMethod method = resolveMethodCall(
                        member.receiver(), member.member(), call.arguments().size(), current, scope);
                if (method != null) return method.callable().returnType();

                if (member.receiver() instanceof Ast.NameExpr factory
                        && (factory.name().equals("Mutex") || factory.name().equals("SharedMutex"))
                        && member.member().equals("new")
                        && call.arguments().size() == 1) {
                    Ast.TypeRef inner = inferType(call.arguments().getFirst(), current, scope);
                    return inner == null ? null
                            : new Ast.TypeRef(factory.name(), List.of(inner), false);
                }
            }
            return null;
        }

        if (expr instanceof Ast.MemberExpr member) {
            Ast.TypeRef receiver = inferType(member.receiver(), current, scope);
            Ast.ClassDecl owner = classForType(receiver, current.module(), scope);
            if (owner == null) return null;
            Ast.FieldDecl field = findField(
                    owner, member.member(), current.module(), scope,
                    java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
            return field == null ? null : field.type();
        }

        if (expr instanceof Ast.IndexExpr indexed) {
            Ast.TypeRef receiver = inferType(indexed.receiver(), current, scope);
            return collectionElement(receiver);
        }

        if (expr instanceof Ast.NewExpr created) return created.type();
        if (expr instanceof Ast.StructInitExpr struct) return struct.type();

        if (expr instanceof Ast.AwaitExpr awaited) {
            Ast.TypeRef awaitedType = inferType(awaited.expression(), current, scope);
            if (awaitedType != null && awaitedType.name().equals("Future")
                    && awaitedType.arguments().size() == 1) {
                return awaitedType.arguments().getFirst();
            }
            return null;
        }

        if (expr instanceof Ast.ListExpr list) {
            Ast.TypeRef element = list.elements().isEmpty()
                    ? Ast.TypeRef.inferred()
                    : inferType(list.elements().getFirst(), current, scope);
            if (element == null) element = Ast.TypeRef.inferred();
            return new Ast.TypeRef("Array", List.of(element), false);
        }

        if (expr instanceof Ast.TupleExpr tuple) {
            List<Ast.TypeRef> members = new ArrayList<>();
            for (Ast.Expr item : tuple.elements()) {
                Ast.TypeRef type = inferType(item, current, scope);
                members.add(type == null ? Ast.TypeRef.inferred() : type);
            }
            return new Ast.TypeRef("$tuple$", members, false);
        }

        if (expr instanceof Ast.ObjectExpr) return Ast.TypeRef.simple("obj");

        if (expr instanceof Ast.LambdaExpr lambda) {
            List<Ast.TypeRef> args = new ArrayList<>();
            for (Ast.Param parameter : lambda.parameters()) args.add(parameter.type());
            args.add(Ast.TypeRef.inferred());
            return new Ast.TypeRef("Fnc", args, false);
        }

        return null;
    }

    private boolean isStringLikeBinary(Ast.BinaryExpr binary, Callable current, Scope scope) {
        Ast.TypeRef left = inferType(binary.left(), current, scope);
        Ast.TypeRef right = inferType(binary.right(), current, scope);

        if (isStringType(left) || isStringType(right)) return true;
        if (isNumericType(left) && isNumericType(right)) return false;

        // '+' is overloaded for numeric addition and string concatenation.
        // Unknown operands cannot be certified no-allocation.
        return true;
    }

    private boolean copyMayAllocate(
            Ast.TypeRef type,
            String module,
            Scope scope,
            Set<Ast.ClassDecl> active) {
        if (type == null || type.inferArguments()) return true;
        Ast.TypeRef target = type.isBorrow() ? type.borrowedTarget() : type;
        String name = target.name();

        if (isScalarOrImmutableReference(name)) return false;

        if (name.equals("$tuple$")) {
            for (Ast.TypeRef member : target.arguments()) {
                if (copyMayAllocate(member, module, scope, active)) return true;
            }
            return false;
        }

        if (name.equals("Option") && target.arguments().size() == 1) {
            return copyMayAllocate(target.arguments().getFirst(), module, scope, active);
        }

        if (name.equals("Array") || name.equals("List") || name.equals("Map")
                || name.equals("obj") || name.equals("Mutex") || name.equals("SharedMutex")
                || name.equals("Future") || name.equals("Fnc")) {
            return true;
        }

        Ast.ClassDecl klass = resolveClass(name, module, scope);
        if (klass == null) return true;
        if (!klass.isStruct()) return true;

        if (!active.add(klass)) return true;
        try {
            Map<String, Ast.TypeRef> substitutions = classGenericSubstitutions(klass, target);
            for (Ast.FieldDecl field : effectiveFields(
                    klass, module, scope,
                    java.util.Collections.newSetFromMap(new IdentityHashMap<>()))) {
                Ast.TypeRef fieldType = substituteType(field.type(), substitutions);
                if (fieldType == null
                        || copyMayAllocate(fieldType, module, scope, active)) {
                    return true;
                }
            }
            return false;
        } finally {
            active.remove(klass);
        }
    }

    private static boolean isZeroCopyShare(Ast.TypeRef type) {
        if (type == null || type.inferArguments()) return false;
        Ast.TypeRef target = type.isBorrow() ? type.borrowedTarget() : type;
        return target.arguments().isEmpty() && isScalarOrImmutableReference(target.name());
    }

    private static Map<String, Ast.TypeRef> classGenericSubstitutions(
            Ast.ClassDecl klass,
            Ast.TypeRef concrete) {
        if (klass.genericParameters().isEmpty()) return Map.of();
        if (concrete == null
                || concrete.arguments().size() != klass.genericParameters().size()) {
            return Map.of();
        }
        LinkedHashMap<String, Ast.TypeRef> result = new LinkedHashMap<>();
        for (int i = 0; i < klass.genericParameters().size(); i++) {
            result.put(klass.genericParameters().get(i), concrete.arguments().get(i));
        }
        return result;
    }

    private static Ast.TypeRef substituteType(
            Ast.TypeRef type,
            Map<String, Ast.TypeRef> substitutions) {
        if (type == null || substitutions.isEmpty()) return type;
        Ast.TypeRef direct = substitutions.get(type.name());
        if (direct != null && type.arguments().isEmpty()) return direct;
        if (type.arguments().isEmpty()) return type;
        return new Ast.TypeRef(
                type.name(),
                type.arguments().stream()
                        .map(argument -> substituteType(argument, substitutions))
                        .toList(),
                type.inferArguments());
    }

    private static boolean isScalarOrImmutableReference(String name) {
        return switch (name) {
            case "int", "Int", "float", "Float", "decimal", "Decimal",
                    "complex", "Complex", "bool", "Bool", "boolean", "Boolean",
                    "String", "string" -> true;
            default -> false;
        };
    }

    private static boolean isNumericType(Ast.TypeRef type) {
        if (type == null) return false;
        String name = typeName(type);
        return name.equals("int") || name.equals("Int")
                || name.equals("float") || name.equals("Float")
                || name.equals("decimal") || name.equals("Decimal")
                || name.equals("complex") || name.equals("Complex");
    }

    private static boolean isStringType(Ast.TypeRef type) {
        if (type == null) return false;
        String name = typeName(type);
        return name.equals("String") || name.equals("string");
    }

    private static String typeName(Ast.TypeRef type) {
        Ast.TypeRef target = type.isBorrow() ? type.borrowedTarget() : type;
        return target.name();
    }

    private static boolean sameType(Ast.TypeRef left, Ast.TypeRef right) {
        return left != null && right != null
                && left.name().equals(right.name())
                && left.arguments().equals(right.arguments())
                && left.inferArguments() == right.inferArguments();
    }

    private Ast.TypeRef iteratorElementType(
            Callable iterator,
            Ast.TypeRef receiverType,
            String module,
            Scope scope) {
        Ast.TypeRef result = iterator.returnType();
        Ast.ClassDecl owner = iterator.ownerClass();
        if (owner != null && receiverType != null) {
            Ast.TypeRef concrete = receiverType.isBorrow()
                    ? receiverType.borrowedTarget()
                    : receiverType;
            Ast.ClassDecl receiverClass = resolveClass(concrete.name(), module, scope);
            if (receiverClass == owner) {
                result = substituteType(
                        result,
                        classGenericSubstitutions(owner, concrete));
            } else if (!owner.genericParameters().isEmpty()) {
                // Inherited generic iterator substitution is not yet provable
                // from the lightweight effect-type model. Stay conservative
                // rather than inventing a concrete loop-element type.
                return null;
            }
        }
        return collectionElement(result);
    }

    private static Ast.TypeRef collectionElement(Ast.TypeRef collection) {
        if (collection == null) return null;
        Ast.TypeRef target = collection.isBorrow() ? collection.borrowedTarget() : collection;
        if ((target.name().equals("Array") || target.name().equals("List")
                || target.name().equals("Option"))
                && target.arguments().size() == 1) {
            return target.arguments().getFirst();
        }
        if (target.name().equals("$tuple$") && !target.arguments().isEmpty()) {
            Ast.TypeRef first = target.arguments().getFirst();
            for (Ast.TypeRef member : target.arguments()) {
                if (!sameType(first, member)) return Ast.TypeRef.inferred();
            }
            return first;
        }
        return null;
    }

    private boolean capturesOuterState(Ast.LambdaExpr lambda, Scope outer) {
        Set<String> locals = new HashSet<>();
        for (Ast.Param parameter : lambda.parameters()) locals.add(parameter.name());

        Set<String> references = new LinkedHashSet<>();
        if (lambda.expressionBody() != null) {
            collectLambdaNames(lambda.expressionBody(), locals, references);
        }
        if (lambda.blockBody() != null) {
            collectLambdaNames(lambda.blockBody(), locals, references);
        }

        for (String name : references) {
            if (outer.lookup(name) != null) return true;
        }
        return false;
    }

    private void collectLambdaNames(
            List<Ast.Stmt> body,
            Set<String> locals,
            Set<String> references) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.BindingStmt binding) {
                collectLambdaNames(binding.initializer(), locals, references);
                locals.add(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                collectLambdaNames(destructure.initializer(), locals, references);
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (binding.name() != null && !binding.name().equals("_")) locals.add(binding.name());
                }
            } else if (stmt instanceof Ast.ReturnStmt returned) {
                if (returned.value() != null) collectLambdaNames(returned.value(), locals, references);
            } else if (stmt instanceof Ast.ExprStmt expression) {
                collectLambdaNames(expression.expression(), locals, references);
            } else if (stmt instanceof Ast.DeferStmt deferred) {
                collectLambdaNames(deferred.expression(), locals, references);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    collectLambdaNames(branch.condition(), locals, references);
                    collectLambdaNames(branch.body(), new HashSet<>(locals), references);
                }
                collectLambdaNames(conditional.elseBody(), new HashSet<>(locals), references);
            } else if (stmt instanceof Ast.TryStmt attempted) {
                collectLambdaNames(attempted.body(), new HashSet<>(locals), references);
                Set<String> caught = new HashSet<>(locals);
                if (attempted.errorName() != null) caught.add(attempted.errorName());
                collectLambdaNames(attempted.catchBody(), caught, references);
                collectLambdaNames(attempted.finallyBody(), new HashSet<>(locals), references);
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                collectLambdaNames(loop.iterable(), locals, references);
                Set<String> loopLocals = new HashSet<>(locals);
                loopLocals.add(loop.bindingName());
                collectLambdaNames(loop.body(), loopLocals, references);
            } else if (stmt instanceof Ast.ForStmt loop) {
                Set<String> loopLocals = new HashSet<>(locals);
                if (loop.initializer() instanceof Ast.BindingStmt binding) {
                    collectLambdaNames(binding.initializer(), loopLocals, references);
                    loopLocals.add(binding.name());
                }
                if (loop.condition() != null) collectLambdaNames(loop.condition(), loopLocals, references);
                if (loop.update() != null) collectLambdaNames(loop.update(), loopLocals, references);
                collectLambdaNames(loop.body(), loopLocals, references);
            }
        }
    }

    private void collectLambdaNames(
            Ast.Expr expr,
            Set<String> locals,
            Set<String> references) {
        if (expr == null) return;
        if (expr instanceof Ast.NameExpr name) {
            if (!locals.contains(name.name())) references.add(name.name());
            return;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            collectLambdaNames(binary.left(), locals, references);
            collectLambdaNames(binary.right(), locals, references);
        } else if (expr instanceof Ast.UnaryExpr unary) {
            collectLambdaNames(unary.operand(), locals, references);
        } else if (expr instanceof Ast.AssignExpr assignment) {
            collectLambdaNames(assignment.target(), locals, references);
            collectLambdaNames(assignment.value(), locals, references);
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            collectLambdaNames(conditional.condition(), locals, references);
            collectLambdaNames(conditional.whenTrue(), locals, references);
            collectLambdaNames(conditional.whenFalse(), locals, references);
        } else if (expr instanceof Ast.CallExpr call) {
            collectLambdaNames(call.callee(), locals, references);
            for (Ast.Expr argument : call.arguments()) {
                collectLambdaNames(argument, locals, references);
            }
        } else if (expr instanceof Ast.MemberExpr member) {
            collectLambdaNames(member.receiver(), locals, references);
        } else if (expr instanceof Ast.IndexExpr indexed) {
            collectLambdaNames(indexed.receiver(), locals, references);
            collectLambdaNames(indexed.index(), locals, references);
        } else if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr argument : created.arguments()) {
                collectLambdaNames(argument, locals, references);
            }
        } else if (expr instanceof Ast.StructInitExpr struct) {
            for (Ast.ObjectField field : struct.fields()) {
                collectLambdaNames(field.value(), locals, references);
            }
        } else if (expr instanceof Ast.AwaitExpr awaited) {
            collectLambdaNames(awaited.expression(), locals, references);
        } else if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) collectLambdaNames(item, locals, references);
        } else if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) collectLambdaNames(item, locals, references);
        } else if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                collectLambdaNames(field.value(), locals, references);
            }
        } else if (expr instanceof Ast.LambdaExpr nested) {
            // A nested lambda is responsible for its own capture environment.
        }
    }

    private static void requireArity(Ast.CallExpr call, String name, int expected) {
        if (call.arguments().size() != expected) {
            throw new IllegalArgumentException(name + " expects exactly " + expected + " value"
                    + (expected == 1 ? "" : "s"));
        }
    }

    private static String renderCallee(Ast.Expr callee) {
        if (callee instanceof Ast.NameExpr name) return name.name();
        if (callee instanceof Ast.MemberExpr member) {
            return renderCallee(member.receiver()) + "." + member.member();
        }
        return "<dynamic>";
    }

    private static <T> Set<T> newIdentitySet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private record ResolvedMethod(Callable callable, boolean exactDispatch) { }

    private static final class Scope {
        private final Scope parent;
        private final Map<String, Ast.TypeRef> bindings = new HashMap<>();
        private final Map<String, Ast.ClassDecl> localTypes = new HashMap<>();

        private Scope(Scope parent) {
            this.parent = parent;
        }

        private void define(String name, Ast.TypeRef type) {
            bindings.put(name, type == null ? Ast.TypeRef.inferred() : type);
        }

        private Ast.TypeRef lookup(String name) {
            Ast.TypeRef type = bindings.get(name);
            return type != null ? type : parent == null ? null : parent.lookup(name);
        }

        private void defineType(String name, Ast.ClassDecl type) {
            localTypes.put(name, type);
        }

        private Ast.ClassDecl lookupType(String name) {
            Ast.ClassDecl type = localTypes.get(name);
            return type != null ? type : parent == null ? null : parent.lookupType(name);
        }
    }

    private static final class Callable {
        private final String module;
        private final Ast.ClassDecl ownerClass;
        private final String name;
        private final int arity;
        private final List<Ast.Param> parameters;
        private final Ast.TypeRef returnType;
        private final List<Ast.Annotation> annotations;
        private final List<Ast.Stmt> body;
        private final String displayName;
        private final boolean async;
        private final boolean isStatic;
        private final boolean isAbstract;
        private final Ast.Visibility visibility;

        private Callable(
                String module,
                Ast.ClassDecl ownerClass,
                String name,
                int arity,
                List<Ast.Param> parameters,
                Ast.TypeRef returnType,
                List<Ast.Annotation> annotations,
                List<Ast.Stmt> body,
                String displayName,
                boolean async,
                boolean isStatic,
                boolean isAbstract,
                Ast.Visibility visibility) {
            this.module = module;
            this.ownerClass = ownerClass;
            this.name = name;
            this.arity = arity;
            this.parameters = parameters;
            this.returnType = returnType;
            this.annotations = annotations;
            this.body = body;
            this.displayName = displayName;
            this.async = async;
            this.isStatic = isStatic;
            this.isAbstract = isAbstract;
            this.visibility = visibility;
        }

        private static Callable function(String module, Ast.FunctionDecl fn) {
            return new Callable(
                    module,
                    null,
                    fn.name(),
                    fn.parameters().size(),
                    fn.parameters(),
                    fn.returnType(),
                    fn.annotations(),
                    fn.body(),
                    (fn.kind() == Ast.CallableKind.ROUTINE ? "routine '" : "function '")
                            + module + "." + fn.name() + "'",
                    fn.async(),
                    true,
                    false,
                    fn.visibility());
        }

        private static Callable method(String module, Ast.ClassDecl owner, Ast.MethodDecl method) {
            return new Callable(
                    module,
                    owner,
                    method.name(),
                    method.parameters().size(),
                    method.parameters(),
                    method.returnType(),
                    method.annotations(),
                    method.body(),
                    "method '" + module + "." + owner.name() + "." + method.name()
                            + "/" + method.parameters().size() + "'",
                    method.async(),
                    method.isStatic(),
                    method.isAbstract(),
                    method.visibility());
        }

        private String module() { return module; }
        private Ast.ClassDecl ownerClass() { return ownerClass; }
        private String name() { return name; }
        private int arity() { return arity; }
        private List<Ast.Param> parameters() { return parameters; }
        private Ast.TypeRef returnType() { return returnType; }
        private List<Ast.Annotation> annotations() { return annotations; }
        private List<Ast.Stmt> body() { return body; }
        private String displayName() { return displayName; }
        private boolean async() { return async; }
        private boolean isStatic() { return isStatic; }
        private boolean isAbstract() { return isAbstract; }
        private Ast.Visibility visibility() { return visibility; }
    }
}
