package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Language-level capability admission pass. This executes before guest
 * statements and complements Graal/OS isolation rather than replacing it.
 *
 * Restricted capabilities are checked transitively through type aliases and
 * stored class state so a private actor cannot launder shared-memory authority
 * behind an otherwise ordinary-looking type.
 */
public final class CapabilityChecker {
    private final Map<String, Ast.TypeAliasDecl> aliases = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Set<String> ambiguousAliases = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Map<String, String> javaImports = new HashMap<>();
    private final Set<String> importedRoots = new HashSet<>();
    private final Set<Ast.FunctionDecl> callableStack =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Set<Ast.MethodDecl> methodStack =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Set<Object> typeExpansionStack =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private CapabilityChecker(Ast.Program program) {
        for (Ast.ImportDecl imported : program.imports()) {
            ImportRules.validate(imported);
            if (imported.wildcard()) {
                importedRoots.add(imported.namespace());
            } else {
                importedRoots.addAll(imported.names());
            }
            if (!ImportRules.isJavaPath(imported.path())) continue;
            String className = ImportRules.javaClassName(imported.path());
            for (String binding : ImportRules.exposedBindings(imported)) {
                javaImports.put(binding, className);
            }
        }

        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.TypeAliasDecl alias) {
                    index(aliases, ambiguousAliases, module.name(), alias.name(), alias);
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                } else if (declaration instanceof Ast.FunctionDecl fn) {
                    index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                }
            }
        }
    }

    public static void check(Ast.Program program, IsolatePolicy policy) {
        new CapabilityChecker(program).checkProgram(program, policy);
    }

    private static <T> void index(
            Map<String, T> values,
            Set<String> ambiguous,
            String module,
            String name,
            T value) {
        values.put(module + "." + name, value);
        T previous = values.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            values.remove(name);
            ambiguous.add(name);
        }
    }

    private Ast.TypeAliasDecl findAlias(String name) {
        return ambiguousAliases.contains(name) ? null : aliases.get(name);
    }

    private Ast.ClassDecl findClass(String name) {
        return ambiguousClasses.contains(name) ? null : classes.get(name);
    }

    private Ast.FunctionDecl findFunction(String name) {
        return ambiguousFunctions.contains(name) ? null : functions.get(name);
    }

    private void checkReferencedFunction(Ast.FunctionDecl fn, IsolatePolicy policy) {
        if (!callableStack.add(fn)) return;
        try {
            IsolatePolicy effective = actorPolicy(fn.actorKind(), policy);
            if (fn.actorKind() == Ast.ActorKind.SHARED) {
                require(effective, IsolatePolicy.Capability.SHARED_MEMORY, "shared actor fnc " + fn.name());
            }
            checkCallableTypes(fn.parameters(), fn.returnType(), effective);
            checkStatements(fn.body(), effective);
        } finally {
            callableStack.remove(fn);
        }
    }

    private Ast.MethodDecl findStaticMethod(Ast.Expr callee, int arity) {
        if (!(callee instanceof Ast.MemberExpr member)) return null;
        String ownerName = memberPath(member.receiver());
        if (ownerName == null) return null;
        Ast.ClassDecl owner = findClass(ownerName);
        if (owner == null) return null;

        Ast.MethodDecl found = null;
        for (Ast.MethodDecl method : owner.methods()) {
            if (!method.isStatic()
                    || !method.name().equals(member.member())
                    || method.parameters().size() != arity) {
                continue;
            }
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    private Ast.MethodDecl findUniqueStaticMethodValue(Ast.MemberExpr member) {
        String ownerName = memberPath(member.receiver());
        if (ownerName == null) return null;
        Ast.ClassDecl owner = findClass(ownerName);
        if (owner == null) return null;

        Ast.MethodDecl found = null;
        for (Ast.MethodDecl method : owner.methods()) {
            if (!method.isStatic() || !method.name().equals(member.member())) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    private void checkReferencedMethod(Ast.MethodDecl method, IsolatePolicy policy) {
        if (!methodStack.add(method)) return;
        try {
            checkType(method.explicitReceiverType(), policy);
            checkCallableTypes(method.parameters(), method.returnType(), policy);
            checkStatements(method.body(), policy);
        } finally {
            methodStack.remove(method);
        }
    }

    private void checkProgram(Ast.Program program, IsolatePolicy policy) {
        for (Ast.ImportDecl imported : program.imports()) {
            if (ImportRules.isJavaPath(imported.path())) {
                require(policy, IsolatePolicy.Capability.JAVA_INTEROP,
                        "Java host import " + ImportRules.javaClassName(imported.path()));
            }
        }

        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    IsolatePolicy actorPolicy = actorPolicy(fn.actorKind(), policy);
                    if (fn.actorKind() == Ast.ActorKind.SHARED) {
                        require(actorPolicy, IsolatePolicy.Capability.SHARED_MEMORY, "shared actor fnc " + fn.name());
                    }
                    checkCallableTypes(fn.parameters(), fn.returnType(), actorPolicy);
                    checkStatements(fn.body(), actorPolicy);
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    IsolatePolicy actorPolicy = actorPolicy(klass.actorKind(), policy);
                    if (klass.actorKind() == Ast.ActorKind.SHARED) {
                        require(actorPolicy, IsolatePolicy.Capability.SHARED_MEMORY, "shared actor " + klass.name());
                    }
                    for (Ast.TypeRef parent : klass.parents()) checkType(parent, actorPolicy);
                    for (Ast.TypeRef iface : klass.interfaces()) checkType(iface, actorPolicy);
                    for (Ast.FieldDecl field : klass.fields()) {
                        checkType(field.type(), actorPolicy);
                        if (field.initializer() != null) checkExpr(field.initializer(), actorPolicy);
                    }
                    for (Ast.MethodDecl method : klass.methods()) {
                        checkType(method.explicitReceiverType(), actorPolicy);
                        checkCallableTypes(method.parameters(), method.returnType(), actorPolicy);
                        checkStatements(method.body(), actorPolicy);
                    }
                } else if (declaration instanceof Ast.InterfaceDecl iface) {
                    for (Ast.TypeRef parent : iface.parents()) checkType(parent, policy);
                    for (Ast.InterfaceMember member : iface.members()) {
                        if (member instanceof Ast.InterfaceFunctionDecl fn) {
                            checkCallableTypes(fn.parameters(), fn.returnType(), policy);
                        } else if (member instanceof Ast.InterfaceFieldDecl field) {
                            checkType(field.type(), policy);
                        }
                    }
                } else if (declaration instanceof Ast.FieldDecl field) {
                    checkType(field.type(), policy);
                    if (field.initializer() != null) checkExpr(field.initializer(), policy);
                } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                    checkType(alias.target(), policy);
                }
            }
        }
    }

    private static IsolatePolicy actorPolicy(
            Ast.ActorKind kind,
            IsolatePolicy parent) {
        return switch (kind) {
            case NONE, SHARED -> parent;
            case PRIVATE -> parent.withoutCapabilities(
                    IsolatePolicy.Capability.SHARED_MEMORY,
                    IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                    IsolatePolicy.Capability.JAVA_INTEROP,
                    IsolatePolicy.Capability.JAVA_SOURCE_INTEROP);
            case UNTRUSTED -> {
                IsolatePolicy baseline = IsolatePolicy.untrustedActor();
                long heap = Math.min(
                        parent.maxHeapBytes(), baseline.maxHeapBytes());
                int mailbox = Math.min(
                        parent.maxMailboxMessages(), baseline.maxMailboxMessages());
                java.time.Duration wall =
                        parent.maxWallTime().compareTo(baseline.maxWallTime()) < 0
                                ? parent.maxWallTime()
                                : baseline.maxWallTime();
                yield new IsolatePolicy(
                        Set.of(), heap, mailbox, wall, true);
            }
        };
    }

    private void checkCallableTypes(
            List<Ast.Param> parameters,
            Ast.TypeRef returnType,
            IsolatePolicy policy) {
        for (Ast.Param parameter : parameters) checkType(parameter.type(), policy);
        checkType(returnType, policy);
    }

    private void checkType(Ast.TypeRef type, IsolatePolicy policy) {
        if (type == null) return;

        if (type.name().equals("SharedMutex")) {
            require(policy, IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex<T>");
        }
        String javaClass = javaImports.get(type.name());
        if (javaClass != null) {
            require(policy, IsolatePolicy.Capability.JAVA_INTEROP, "Java host import " + javaClass);
        }
        for (Ast.TypeRef argument : type.arguments()) checkType(argument, policy);
        if (type.isBorrow()) checkType(type.borrowedTarget(), policy);

        Ast.TypeAliasDecl alias = findAlias(type.name());
        if (alias != null && typeExpansionStack.add(alias)) {
            try {
                checkType(alias.target(), policy);
            } finally {
                typeExpansionStack.remove(alias);
            }
        }

        Ast.ClassDecl klass = findClass(type.name());
        if (klass != null && typeExpansionStack.add(klass)) {
            try {
                for (Ast.TypeRef parent : klass.parents()) checkType(parent, policy);
                for (Ast.TypeRef iface : klass.interfaces()) checkType(iface, policy);
                for (Ast.FieldDecl field : klass.fields()) {
                    checkType(field.type(), policy);
                    if (field.initializer() != null) checkExpr(field.initializer(), policy);
                }
                // An object stored in a private actor is itself an authority
                // carrier. Its instance methods must therefore be admissible
                // under the actor's policy; otherwise an ordinary class could
                // hide a SharedMutex/process.share_readonly call behind a method.
                for (Ast.MethodDecl method : klass.methods()) {
                    if (method.isStatic()) continue;
                    checkType(method.explicitReceiverType(), policy);
                    checkCallableTypes(method.parameters(), method.returnType(), policy);
                    checkStatements(method.body(), policy);
                }
            } finally {
                typeExpansionStack.remove(klass);
            }
        }
    }

    private void checkStatements(List<Ast.Stmt> statements, IsolatePolicy policy) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt s) {
                checkType(s.declaredType(), policy);
                checkExpr(s.initializer(), policy);
            }
            else if (stmt instanceof Ast.DestructureStmt s) checkExpr(s.initializer(), policy);
            else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) checkExpr(s.value(), policy);
            else if (stmt instanceof Ast.ExprStmt s) checkExpr(s.expression(), policy);
            else if (stmt instanceof Ast.DeferStmt s) checkExpr(s.expression(), policy);
            else if (stmt instanceof Ast.BlockStmt s) checkStatements(s.body(), policy);
            else if (stmt instanceof Ast.LoopStmt s) checkStatements(s.body(), policy);
            else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch b : s.branches()) {
                    checkExpr(b.condition(), policy);
                    checkStatements(b.body(), policy);
                }
                checkStatements(s.elseBody(), policy);
            } else if (stmt instanceof Ast.SelectStmt s) {
                for (Ast.SelectArm arm : s.arms()) {
                    if (arm.channel() != null) checkExpr(arm.channel(), policy);
                    if (arm.value() != null) checkExpr(arm.value(), policy);
                    checkStatements(arm.body(), policy);
                }
            } else if (stmt instanceof Ast.TryStmt s) {
                checkStatements(s.body(), policy);
                checkStatements(s.catchBody(), policy);
                checkStatements(s.finallyBody(), policy);
            } else if (stmt instanceof Ast.ForOfStmt s) {
                checkExpr(s.iterable(), policy);
                checkStatements(s.body(), policy);
            } else if (stmt instanceof Ast.ForStmt s) {
                if (s.initializer() != null) checkStatements(List.of(s.initializer()), policy);
                if (s.condition() != null) checkExpr(s.condition(), policy);
                if (s.update() != null) checkExpr(s.update(), policy);
                checkStatements(s.body(), policy);
            }
        }
    }

    private void checkExpr(Ast.Expr expr, IsolatePolicy policy) {
        if (expr instanceof Ast.NameExpr javaName) {
            String javaClass = javaImports.get(javaName.name());
            if (javaClass != null) {
                require(policy, IsolatePolicy.Capability.JAVA_INTEROP, "Java host import " + javaClass);
            }
        }

        if (expr instanceof Ast.NameExpr n
                && isZeroAuthorityAdversarial(policy)
                && (isRestrictedFacadeRoot(n.name())
                        || importedRoots.contains(n.name()))) {
            throw new SecurityException(
                    importedRoots.contains(n.name())
                            ? "untrusted actor cannot extract imported code without explicit effect metadata: "
                                    + n.name()
                            : "untrusted actor cannot extract restricted capability facade '"
                                    + n.name() + "'");
        }

        if (expr instanceof Ast.NameExpr n && n.name().equals("print")) {
            require(policy, IsolatePolicy.Capability.STDOUT, "print");
        } else if (expr instanceof Ast.NameExpr n && n.name().equals("SharedMutex")) {
            require(policy, IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex");
        } else if (expr instanceof Ast.NameExpr n) {
            // Function values can be laundered through locals/callbacks before
            // invocation. Check the referenced body at the point the function
            // enters the actor's value graph, not only for direct call syntax.
            Ast.FunctionDecl referenced = findFunction(n.name());
            if (referenced != null) checkReferencedFunction(referenced, policy);
        }
        else if (expr instanceof Ast.CallExpr c) {
            String target = memberPath(c.callee());
            if (isZeroAuthorityAdversarial(policy) && target != null) {
                String root = target.contains(".")
                        ? target.substring(0, target.indexOf('.'))
                        : target;
                if (importedRoots.contains(root)) {
                    throw new SecurityException(
                            "untrusted actor cannot call imported code without explicit effect metadata: "
                                    + target);
                }
            }
            if (target != null) {
                Ast.FunctionDecl fn = findFunction(target);
                if (fn != null) checkReferencedFunction(fn, policy);
            }
            Ast.MethodDecl staticMethod = findStaticMethod(c.callee(), c.arguments().size());
            if (staticMethod != null) checkReferencedMethod(staticMethod, policy);
            checkExpr(c.callee(), policy);
            for (Ast.Expr arg : c.arguments()) checkExpr(arg, policy);
        } else if (expr instanceof Ast.MemberExpr m) {
            String path = memberPath(m);
            if (path != null) {
                // Qualified module function values have the same laundering
                // risk as unqualified function values.
                Ast.FunctionDecl referenced = findFunction(path);
                if (referenced != null) checkReferencedFunction(referenced, policy);
                Ast.MethodDecl staticValue = findUniqueStaticMethodValue(m);
                if (staticValue != null) checkReferencedMethod(staticValue, policy);
                if (path.startsWith("stdio.") || path.equals("stdio")) require(policy, IsolatePolicy.Capability.STDOUT, path);
                if (path.startsWith("process.descriptor") || path.equals("process.context_id")) require(policy, IsolatePolicy.Capability.PROCESS_INFO, path);
                if (path.startsWith("process.share_readonly")) require(policy, IsolatePolicy.Capability.ACTOR_SHARE_READONLY, path);
                if (path.equals("process.gc") || path.startsWith("process.gc.")) require(policy, IsolatePolicy.Capability.GC_CONTROL, path);
                if (path.equals("SharedMutex") || path.startsWith("SharedMutex.")) require(policy, IsolatePolicy.Capability.SHARED_MEMORY, path);
                if (path.startsWith("network.")) require(policy, IsolatePolicy.Capability.NETWORK, path);
                if (path.startsWith("fs.read")) require(policy, IsolatePolicy.Capability.FILESYSTEM_READ, path);
                if (path.startsWith("fs.write")) require(policy, IsolatePolicy.Capability.FILESYSTEM_WRITE, path);
                if (path.startsWith("env.")) require(policy, IsolatePolicy.Capability.ENVIRONMENT, path);
                if (path.startsWith("ffi.")) require(policy, IsolatePolicy.Capability.FFI, path);
                if (path.startsWith("polyglot.")) require(policy, IsolatePolicy.Capability.POLYGLOT, path);
                if (path.startsWith("thread.")) require(policy, IsolatePolicy.Capability.THREAD_CREATE, path);
                if (path.startsWith("process.spawn")) require(policy, IsolatePolicy.Capability.CHILD_PROCESS, path);
            }
            checkExpr(m.receiver(), policy);
        } else if (expr instanceof Ast.BinaryExpr e) { checkExpr(e.left(), policy); checkExpr(e.right(), policy); }
        else if (expr instanceof Ast.UnaryExpr e) checkExpr(e.operand(), policy);
        else if (expr instanceof Ast.AssignExpr e) {
            checkExpr(e.target(), policy);
            checkExpr(e.value(), policy);
        }
        else if (expr instanceof Ast.ConditionalExpr e) { checkExpr(e.condition(), policy); checkExpr(e.whenTrue(), policy); checkExpr(e.whenFalse(), policy); }
        else if (expr instanceof Ast.IndexExpr e) { checkExpr(e.receiver(), policy); checkExpr(e.index(), policy); }
        else if (expr instanceof Ast.NewExpr e) {
            checkType(e.type(), policy);
            for (Ast.Expr a : e.arguments()) checkExpr(a, policy);
        }
        else if (expr instanceof Ast.AwaitExpr e) checkExpr(e.expression(), policy);
        else if (expr instanceof Ast.ChannelOpExpr e) {
            checkExpr(e.channel(), policy);
            if (e.value() != null) checkExpr(e.value(), policy);
        }
        else if (expr instanceof Ast.DynamicSelectExpr e) checkExpr(e.cases(), policy);
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr a : e.elements()) checkExpr(a, policy);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr a : e.elements()) checkExpr(a, policy);
        else if (expr instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField f : e.fields()) {
                if (f.isDynamic()) checkExpr(f.dynamicName(), policy);
                checkExpr(f.value(), policy);
            }
        }
        else if (expr instanceof Ast.LambdaExpr e) {
            if (e.expressionBody() != null) checkExpr(e.expressionBody(), policy);
            if (e.blockBody() != null) checkStatements(e.blockBody(), policy);
        }
    }

    private static boolean isZeroAuthorityAdversarial(IsolatePolicy policy) {
        return policy.adversarial() && policy.capabilities().isEmpty();
    }

    private static boolean isRestrictedFacadeRoot(String name) {
        return switch (name) {
            case "stdio", "process", "actor", "network", "ipc", "gpu",
                    "fs", "env", "ffi", "polyglot", "thread",
                    "SharedMutex", "print" -> true;
            default -> false;
        };
    }

    private static String memberPath(Ast.Expr expr) {
        if (expr instanceof Ast.NameExpr n) return n.name();
        if (expr instanceof Ast.MemberExpr m) {
            String parent = memberPath(m.receiver());
            return parent == null ? null : parent + "." + m.member();
        }
        return null;
    }

    private static void require(IsolatePolicy policy, IsolatePolicy.Capability capability, String api) {
        if (!policy.allows(capability)) {
            throw new SecurityException("Oreslang isolate denies capability " + capability + " required by " + api);
        }
    }
}
