package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.SingletonProxy;
import dev.oreslang.types.Types.StringLiteral;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.Types.Unknown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static semantic pass run before Oreslang code is lowered/executed. */
public final class TypeChecker {
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.InterfaceDecl> interfaces = new HashMap<>();
    private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
    private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();

    private final IdentityHashMap<Ast.FunctionDecl, String> functionOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, String> classOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.InterfaceDecl, String> interfaceOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, Record> classShapeCache = new IdentityHashMap<>();
    private final java.util.ArrayDeque<Map<String, Ast.ClassDecl>> localClassScopes = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Map<String, Ast.InterfaceDecl>> localInterfaceScopes = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Map<String, Ast.TypeAliasDecl>> localTypeAliasScopes = new java.util.ArrayDeque<>();
    private final IdentityHashMap<Ast.ClassDecl, String> localClassIdentities = new IdentityHashMap<>();
    private final Map<String, Ast.ClassDecl> escapedLocalClasses = new HashMap<>();
    private int nextLocalTypeIdentity = 1;

    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousInterfaces = new HashSet<>();
    private final Set<String> ambiguousTypeAliases = new HashSet<>();
    private final Set<String> importedValues = new HashSet<>();
    private final Set<String> importedNames = new HashSet<>();
    private final Set<Ast.TypeAliasDecl> resolvingAliases = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Ast.ClassDecl> processEffectCheckedClasses =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    // Set only while checking a method flattened from a trait.
    private String activeTraitOwner;
    // Lexical aggregate owner for ordinary private member access.
    private Ast.ClassDecl activeClassOwner;

    public static Ast.Program check(Ast.Program program) {
        Ast.Program composed = TraitComposer.compose(program);
        TypeChecker checker = new TypeChecker();
        checker.validateImports(composed);
        checker.collect(composed);
        checker.validateRoutineRecursion();
        checker.validate(composed);
        OwnershipChecker.check(composed);
        return composed;
    }

    private void validateImports(Ast.Program program) {
        Set<String> exposed = new HashSet<>();
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.path() == null || imported.path().isBlank()) throw new IllegalArgumentException("import path cannot be empty");
            if (imported.wildcard()) {
                if (imported.namespace() == null || imported.namespace().isBlank()) throw new IllegalArgumentException("wildcard imports require a namespace alias");
                if (!exposed.add(imported.namespace())) throw new IllegalArgumentException("duplicate imported name '" + imported.namespace() + "'");
                importedNames.add(imported.namespace());
                importedValues.add(imported.namespace());
            } else {
                if (imported.names().isEmpty()) throw new IllegalArgumentException("named import must select at least one name");
                for (String name : imported.names()) {
                    if (!exposed.add(name)) throw new IllegalArgumentException("duplicate imported name '" + name + "'");
                    importedNames.add(name);
                    if (imported.kind() != Ast.ImportKind.CLASS && imported.kind() != Ast.ImportKind.STRUCT) importedValues.add(name);
                }
            }
        }
    }

    private void collect(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            if (modules.putIfAbsent(module.name(), module) != null) throw new IllegalArgumentException("duplicate module '" + module.name() + "'");
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    putQualified(functions, ambiguousFunctions, module.name(), fn.name(), fn, fn.kind() == Ast.CallableKind.ROUTINE ? "routine" : "function");
                    functionOwners.put(fn, module.name());
                } else if (decl instanceof Ast.ClassDecl klass) {
                    putQualified(classes, ambiguousClasses, module.name(), klass.name(), klass, klass.isStruct() ? "struct" : "class");
                    classOwners.put(klass, module.name());
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    putQualified(interfaces, ambiguousInterfaces, module.name(), iface.name(), iface, "interface");
                    interfaceOwners.put(iface, module.name());
                } else if (decl instanceof Ast.TypeAliasDecl alias) {
                    putQualified(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias, "type alias");
                }
            }
        }
    }

    private static <T> void putQualified(Map<String, T> map, Set<String> ambiguous, String module, String name, T value, String kind) {
        String qualified = module + "." + name;
        if (map.putIfAbsent(qualified, value) != null) {
            throw new IllegalArgumentException("duplicate " + kind + " '" + qualified + "'; fnc/routine declarations cannot overload");
        }
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            ambiguous.add(name);
            map.remove(name);
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ClassDecl klass : classOwners.keySet()) classShape(klass, new LinkedHashSet<>());
        for (Ast.InterfaceDecl iface : interfaceOwners.keySet()) interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());

        for (Ast.ModuleDecl module : program.modules()) {
            checkModuleAdherence(module);
            long initCount = module.declarations().stream().filter(Ast.InitDecl.class::isInstance).count();
            if (initCount > 1) {
                String scope = module.name().equals(Parser.ROOT_MODULE) ? "source file" : "module '" + module.name() + "'";
                throw new IllegalArgumentException(scope + " may declare at most one init routine");
            }
            if (module.singleton()) validateSingletonModule(module);
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(module, fn);
                else if (decl instanceof Ast.InitDecl init) checkInit(module, init);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(module.name(), klass);
                else if (decl instanceof Ast.InterfaceDecl iface) checkInterface(iface);
                else if (decl instanceof Ast.TypeAliasDecl alias) resolve(alias.target(), Set.copyOf(alias.genericParameters()), null);
                else if (decl instanceof Ast.FieldDecl field) checkModuleBinding(module, field);
            }
        }
    }

    private void checkModuleAdherence(Ast.ModuleDecl module) {
        for (Ast.Annotation annotation : module.annotations()) {
            if (!annotation.name().equals("AdheresTo")) continue;
            if (module.singleton()) {
                throw new IllegalArgumentException("singleton module '" + module.name()
                        + "' cannot use ordinary @AdheresTo interfaces because its external surface is asynchronous;"
                        + " define a service/singleton interface kind before advertising synchronous conformance");
            }
            if (annotation.arguments().isEmpty()) throw new IllegalArgumentException("@AdheresTo requires at least one interface");
            Record actual = moduleShape(module);
            for (Ast.TypeRef ref : annotation.arguments()) {
                Ast.InterfaceDecl iface = findInterface(ref.name());
                if (iface == null) throw new IllegalArgumentException("unknown module interface '" + ref.name() + "'");
                Record expected = interfaceShapeForUse(iface, ref, Set.of(), null);
                if (!assignable(actual, expected)) {
                    throw new IllegalArgumentException("module '" + module.name() + "' does not adhere to interface '" + ref.name() + "': expected " + expected + " but got " + actual);
                }
            }
        }
    }

    private Record moduleShape(Ast.ModuleDecl module) {
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC) {
                Type signature = declaredFunctionType(fn);
                mergeMember(members, fn.name(), signature, "module " + module.name());
                mergeMember(members, methodKey(fn.name(), fn.parameters().size()), signature, "module " + module.name());
            } else if (decl instanceof Ast.FieldDecl field && field.visibility() == Ast.Visibility.PUBLIC) {
                Type type = field.type() == null ? typeOf(field.initializer(), new Env(null), Set.of(), null) : resolve(field.type(), Set.of(), null);
                mergeMember(members, field.name(), type, "module " + module.name());
            }
        }
        return new Record(members);
    }

    private void checkInterface(Ast.InterfaceDecl iface) {
        Set<String> generics = uniqueGenerics(iface.genericParameters(), "interface " + iface.name());
        Set<String> memberKeys = new HashSet<>();
        for (Ast.TypeRef parentRef : iface.parents()) {
            if (findInterface(parentRef.name()) == null) throw new IllegalArgumentException("unknown parent interface '" + parentRef.name() + "' for " + iface.name());
        }

        for (Ast.InterfaceMember member : iface.members()) {
            Ast.InterfaceFunctionDecl fn = (Ast.InterfaceFunctionDecl) member;
            String key = methodKey(fn.name(), fn.parameters().size());
            if (!memberKeys.add(key)) throw new IllegalArgumentException("duplicate interface method '" + iface.name() + "." + fn.name() + "' with arity " + fn.parameters().size());
            Set<String> all = new HashSet<>(generics);
            for (String generic : fn.genericParameters()) {
                if (!all.add(generic)) throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in interface " + iface.name() + "." + fn.name());
            }
            functionType(fn.parameters(), fn.returnType(), all, null);
        }
    }

    private void checkInit(Ast.ModuleDecl module, Ast.InitDecl init) {
        Env env = module.singleton() ? singletonModuleEnv(module) : moduleBindingEnv(module);
        if (module.singleton()) validatePureSingletonInit(module, init);
        validateSingletonTransportStatements(init.body(), module.name());
        checkBlock(init.body(), env, Set.of(), Primitive.VOID, null);
    }

    /**
     * Process init cannot inherit ambient authority from whichever actor or
     * tenant first touches the singleton. Keep it deterministic and confined
     * to already-declared singleton state until a supervisor-owned init
     * capability model exists.
     */
    private void validatePureSingletonInit(Ast.ModuleDecl module, Ast.InitDecl init) {
        Set<String> names = new LinkedHashSet<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FieldDecl field) names.add(field.name());
        }
        validatePureSingletonInitStatements(module, init.body(), names);
    }

    private void validatePureSingletonInitStatements(
            Ast.ModuleDecl module,
            List<Ast.Stmt> statements,
            Set<String> visibleNames) {
        Set<String> names = new LinkedHashSet<>(visibleNames);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (!isPureSingletonInitializer(binding.initializer(), names)) {
                    throw impureSingletonInit(module, "local binding '" + binding.name() + "'");
                }
                names.add(binding.name());
                continue;
            }
            if (stmt instanceof Ast.ExprStmt expression
                    && expression.expression() instanceof Ast.AssignExpr assignment
                    && assignment.target() instanceof Ast.NameExpr target
                    && names.contains(target.name())
                    && isPureSingletonInitializer(assignment.value(), names)) {
                continue;
            }
            if (stmt instanceof Ast.ReturnStmt ret && ret.value() == null) continue;
            if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    if (!isPureSingletonInitializer(branch.condition(), names)) {
                        throw impureSingletonInit(module, "if condition");
                    }
                    validatePureSingletonInitStatements(module, branch.body(), names);
                }
                validatePureSingletonInitStatements(module, conditional.elseBody(), names);
                continue;
            }
            throw impureSingletonInit(module, stmt.getClass().getSimpleName());
        }
    }

    private IllegalArgumentException impureSingletonInit(Ast.ModuleDecl module, String construct) {
        return new IllegalArgumentException("singleton init routine '" + module.name()
                + "' must be deterministic and context-free; " + construct
                + " would make process state depend on the first caller");
    }

    private Env moduleBindingEnv(Ast.ModuleDecl module) {
        Env env = new Env(null, module.name());
        for (Ast.Decl decl : module.declarations()) {
            if (!(decl instanceof Ast.FieldDecl field)) continue;
            if (field.initializer() == null) {
                throw new IllegalArgumentException("module binding '" + module.name() + "." + field.name() + "' requires an initializer");
            }
            Type actual = typeOf(field.initializer(), env, Set.of(), null);
            Type declared = field.type() == null ? actual : resolve(field.type(), Set.of(), null);
            requireAssignable(actual, declared, "initializer for " + module.name() + "." + field.name());
            env.define(field.name(), declared, field.bindingKind());
        }
        return env;
    }

    private void checkFunction(Ast.ModuleDecl module, Ast.FunctionDecl fn) {
        Set<String> generics = uniqueGenerics(fn.genericParameters(), (fn.kind() == Ast.CallableKind.ROUTINE ? "routine " : "function ") + fn.name());
        rejectLocalTypeGenericCollisions(fn.body(), generics);
        pushLocalTypeScope(fn.body());
        try {
            Env env = module.singleton() ? singletonModuleEnv(module) : moduleBindingEnv(module);
            for (Ast.Param param : fn.parameters()) env.define(param.name(), resolveParam(param, generics, null), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            Type returns = resolve(fn.returnType(), generics, null);
            validateSingletonTransportStatements(fn.body(), module.name());
            checkBlock(fn.body(), env, generics, returns, null);
            if (returns != Primitive.VOID && !definitelyReturns(fn.body())) {
                throw new IllegalArgumentException("non-void " + fn.kind().name().toLowerCase() + " '" + module.name() + "." + fn.name() + "' must explicitly return on every path");
            }
            if (fn.visibility() == Ast.Visibility.PUBLIC && hasInferredArgs(fn.returnType())) {
                throw new IllegalArgumentException("public callable '" + fn.name() + "' cannot export unresolved <> type arguments");
            }
        } finally {
            popLocalTypeScope();
        }
    }

    private Env singletonModuleEnv(Ast.ModuleDecl module) {
        Env env = new Env(null, module.name());
        for (Ast.Decl decl : module.declarations()) {
            if (!(decl instanceof Ast.FieldDecl field)) continue;
            if (field.initializer() == null) {
                throw new IllegalArgumentException("singleton module binding '" + module.name() + "." + field.name() + "' requires an initializer");
            }
            Type actual = typeOf(field.initializer(), env, Set.of(), null);
            Type declared = field.type() == null ? actual : resolve(field.type(), Set.of(), null);
            requireAssignable(actual, declared, "initializer for " + module.name() + "." + field.name());
            env.define(field.name(), declared, field.bindingKind());
        }
        return env;
    }

    private void validateSingletonModule(Ast.ModuleDecl module) {
        Set<String> initializedFields = new LinkedHashSet<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FieldDecl field) {
                if (field.type() == null) {
                    throw new IllegalArgumentException("singleton module field '" + module.name() + "." + field.name()
                            + "' requires an explicit type so process-lifetime state has a stable hot-reload schema");
                }
                if (containsTypeAlias(field.type())) {
                    throw new IllegalArgumentException("singleton module field '" + module.name() + "." + field.name()
                            + "' cannot use type aliases in process-lifetime state; spell the stable storage type explicitly");
                }

                Ast.ClassDecl proxyClass = singletonProxyClass(field);
                boolean exportedProxy = field.visibility() == Ast.Visibility.PUBLIC && proxyClass != null;
                if (field.visibility() == Ast.Visibility.PUBLIC && !exportedProxy) {
                    throw new IllegalArgumentException("singleton module field '" + module.name() + "." + field.name()
                            + "' cannot be public unless it is an immutable class-instance proxy");
                }

                if (exportedProxy) {
                    if (field.bindingKind() == Ast.BindingKind.LET) {
                        throw new IllegalArgumentException("exported singleton object '" + module.name() + "." + field.name()
                                + "' must use val or const so the process-wide capability cannot be rebound");
                    }
                    validateSingletonProxyClass(module, field, proxyClass);
                    if (!isPureSingletonProxyInitializer(field.initializer(), proxyClass, initializedFields)) {
                        throw new IllegalArgumentException("exported singleton object '" + module.name() + "." + field.name()
                                + "' must be initialized with a context-free 'new " + proxyClass.name() + "(...)'");
                    }
                } else {
                    Type stateType = resolve(field.type(), Set.of(), null);
                    if (!isProcessStableStateType(stateType)) {
                        throw new IllegalArgumentException("singleton module field '" + module.name() + "." + field.name()
                                + "' uses process-unstable state type " + field.type()
                                + "; use scalars, Option<T>, or Array/List<T> of stable values");
                    }
                    if (field.initializer() == null
                            || !isPureSingletonInitializer(field.initializer(), initializedFields)) {
                        throw new IllegalArgumentException("singleton module field '" + module.name() + "." + field.name()
                                + "' requires a context-free initializer; singleton initialization cannot call functions,"
                                + " access capabilities, await work, construct arbitrary classes, or mutate state");
                    }
                }
                initializedFields.add(field.name());
            }

            if (decl instanceof Ast.FunctionDecl fn && fn.name().equals("main")) {
                throw new IllegalArgumentException("singleton module '" + module.name()
                        + "' cannot declare main; the process entrypoint must remain outside singleton actor ownership");
            }

            if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC) {
                if (fn.async()) {
                    throw new IllegalArgumentException("singleton callable '" + module.name() + "." + fn.name()
                            + "' cannot be declared async; the actor transport already supplies Future<T>");
                }
                if (!fn.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException("singleton callable '" + module.name() + "." + fn.name()
                            + "' cannot be generic until Oreslang has explicit Send constraints");
                }

                Set<String> generics = Set.copyOf(fn.genericParameters());
                for (Ast.Param param : fn.parameters()) {
                    if (param.structural()) {
                        throw new IllegalArgumentException("singleton callable '" + module.name() + "." + fn.name()
                                + "' cannot use structural parameters until structural Send is explicit");
                    }
                    Type parameter = resolveParam(param, generics, null);
                    if (!isActorSendableType(parameter, false)) {
                        throw new IllegalArgumentException("singleton callable '" + module.name() + "." + fn.name()
                                + "' parameter '" + param.name() + "' is not statically Sendable: " + param.type());
                    }
                }
                Type result = resolve(fn.returnType(), generics, null);
                if (!isActorSendableType(result, true)) {
                    throw new IllegalArgumentException("singleton callable '" + module.name() + "." + fn.name()
                            + "' result is not statically Sendable: " + fn.returnType());
                }
            }
        }
        validateProcessOwnedModuleEffects(module);
    }

    private Ast.ClassDecl singletonProxyClass(Ast.FieldDecl field) {
        if (field.type() == null || !field.type().arguments().isEmpty()) return null;
        return findClass(field.type().name());
    }

    private boolean isPureSingletonProxyInitializer(
            Ast.Expr initializer,
            Ast.ClassDecl klass,
            Set<String> initializedFields) {
        if (!(initializer instanceof Ast.NewExpr created)) return false;
        Ast.ClassDecl createdClass = findClass(created.type().name());
        if (createdClass != klass) return false;
        if (created.arguments().stream().anyMatch(arg -> !isPureSingletonInitializer(arg, initializedFields))) {
            return false;
        }
        for (Ast.FieldDecl classField : effectiveFields(klass, new LinkedHashSet<>())) {
            if (classField.type() == null) return false;
            Type fieldType = resolve(classField.type(), Set.copyOf(klass.genericParameters()), nominalClassType(klass));
            if (!isProcessStableStateType(fieldType)) return false;
            if (classField.initializer() != null
                    && !isPureSingletonInitializer(classField.initializer(), Set.of())) return false;
        }
        return true;
    }

    private void validateSingletonProxyClass(
            Ast.ModuleDecl owner,
            Ast.FieldDecl exportedField,
            Ast.ClassDecl klass) {
        if (!klass.parents().isEmpty()) {
            throw new IllegalArgumentException("exported singleton object '" + owner.name() + "."
                    + exportedField.name()
                    + "' cannot use class inheritance until inherited proxy methods are flattened and Send-checked");
        }
        if (!klass.genericParameters().isEmpty()) {
            throw new IllegalArgumentException("exported singleton object '" + owner.name() + "." + exportedField.name()
                    + "' cannot use a generic class until proxy Send constraints are explicit");
        }

        for (Ast.FieldDecl classField : klass.fields()) {
            if (classField.type() == null) {
                throw new IllegalArgumentException("singleton proxy storage field '" + klass.name() + "."
                        + classField.name() + "' requires an explicit process-stable type");
            }
            if (containsTypeAlias(classField.type())) {
                throw new IllegalArgumentException("singleton proxy storage field '" + klass.name() + "."
                        + classField.name()
                        + "' cannot use type aliases in process-lifetime state; spell the storage type explicitly");
            }
        }

        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() || method.visibility() != Ast.Visibility.PUBLIC) continue;
            if (method.async()) {
                throw new IllegalArgumentException("singleton proxy method '" + klass.name() + "." + method.name()
                        + "' cannot be async; mailbox transport already returns Future<T>");
            }
            if (!method.genericParameters().isEmpty()) {
                throw new IllegalArgumentException("singleton proxy method '" + klass.name() + "." + method.name()
                        + "' cannot be generic until proxy Send constraints are explicit");
            }
            Function signature = methodFunctionType(method, klass, nominalClassType(klass));
            for (int i = 0; i < method.parameters().size(); i++) {
                Ast.Param param = method.parameters().get(i);
                if (param.structural()) {
                    throw new IllegalArgumentException("singleton proxy method '" + klass.name() + "." + method.name()
                            + "' cannot use structural transported parameters");
                }
                Type parameter = signature.parameters().get(i);
                if (!isActorSendableType(parameter, false)) {
                    throw new IllegalArgumentException("singleton proxy method '" + klass.name() + "." + method.name()
                            + "' parameter '" + param.name() + "' is not statically Sendable");
                }
            }
            Type result = signature.result();
            if (!isActorSendableType(result, true)) {
                throw new IllegalArgumentException("singleton proxy method '" + klass.name() + "." + method.name()
                        + "' result is not statically Sendable");
            }

        }

        String classOwnerName = classOwners.get(klass);
        Ast.ModuleDecl classOwner = classOwnerName == null ? null : modules.get(classOwnerName);
        if (classOwner != null && classOwner != owner) {
            Set<String> actorBindings = new LinkedHashSet<>();
            for (Ast.Decl decl : classOwner.declarations()) {
                if (decl instanceof Ast.FieldDecl field) actorBindings.add(field.name());
            }
            if (!actorBindings.isEmpty()) {
                for (Ast.FieldDecl classField : klass.fields()) {
                    if (classField.initializer() != null
                            && referencesActorModuleBinding(classField.initializer(), actorBindings, Set.of())) {
                        throw new IllegalArgumentException("exported singleton class '" + klass.name()
                                + "' cannot capture actor-local module state from '" + classOwner.name() + "'");
                    }
                }
                for (Ast.MethodDecl method : klass.methods()) {
                    Set<String> shadowed = new LinkedHashSet<>();
                    shadowed.add("self");
                    for (Ast.Param param : method.parameters()) shadowed.add(param.name());
                    if (referencesActorModuleBinding(method.body(), actorBindings, shadowed)) {
                        throw new IllegalArgumentException("exported singleton class '" + klass.name()
                                + "' cannot capture actor-local module state from '" + classOwner.name() + "'");
                    }
                }
            }
        }
        validateProcessOwnedClassEffects(owner, klass);
    }

    private void validateProcessOwnedModuleEffects(Ast.ModuleDecl owner) {
        for (Ast.Decl decl : owner.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn) {
                Set<String> locals = new LinkedHashSet<>();
                for (Ast.Param param : fn.parameters()) locals.add(param.name());
                validateProcessOwnedStatements(owner, null, fn.body(), locals,
                        "singleton callable '" + owner.name() + "." + fn.name() + "'");
            } else if (decl instanceof Ast.ClassDecl klass) {
                validateProcessOwnedClassEffects(owner, klass);
            }
        }
    }

    private void validateProcessOwnedClassEffects(Ast.ModuleDecl owner, Ast.ClassDecl klass) {
        if (!processEffectCheckedClasses.add(klass)) return;

        for (Ast.FieldDecl field : klass.fields()) {
            if (field.initializer() != null) {
                validateProcessOwnedExpr(owner, klass, field.initializer(), Set.of(),
                        "process-owned class '" + klass.name() + "' field initializer");
            }
        }

        for (Ast.MethodDecl method : klass.methods()) {
            Set<String> locals = new LinkedHashSet<>();
            if (!method.isStatic()) locals.add("self");
            for (Ast.Param param : method.parameters()) locals.add(param.name());
            validateProcessOwnedStatements(owner, klass, method.body(), locals,
                    "process-owned method '" + klass.name() + "." + method.name() + "'");
        }
    }

    private void validateProcessOwnedStatements(
            Ast.ModuleDecl processOwner,
            Ast.ClassDecl processClass,
            List<Ast.Stmt> statements,
            Set<String> inheritedLocals,
            String where) {
        Set<String> locals = new LinkedHashSet<>(inheritedLocals);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.TypeDeclStmt localType) {
                if (localType.declaration() instanceof Ast.ClassDecl struct) {
                    validateProcessOwnedClassEffects(processOwner, struct);
                }
            } else if (stmt instanceof Ast.BindingStmt binding) {
                validateProcessOwnedExpr(processOwner, processClass, binding.initializer(), locals, where);
                locals.add(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                validateProcessOwnedExpr(processOwner, processClass, destructure.initializer(), locals, where);
                for (Ast.DestructureBinding binding : destructure.bindings()) locals.add(binding.name());
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                if (ret.value() != null) validateProcessOwnedExpr(processOwner, processClass, ret.value(), locals, where);
            } else if (stmt instanceof Ast.ExprStmt expression) {
                validateProcessOwnedExpr(processOwner, processClass, expression.expression(), locals, where);
            } else if (stmt instanceof Ast.DeferStmt defer) {
                validateProcessOwnedExpr(processOwner, processClass, defer.expression(), locals, where);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    validateProcessOwnedExpr(processOwner, processClass, branch.condition(), locals, where);
                    validateProcessOwnedStatements(processOwner, processClass, branch.body(),
                            new LinkedHashSet<>(locals), where);
                }
                validateProcessOwnedStatements(processOwner, processClass, conditional.elseBody(),
                        new LinkedHashSet<>(locals), where);
            } else if (stmt instanceof Ast.TryStmt attempted) {
                validateProcessOwnedStatements(processOwner, processClass, attempted.body(),
                        new LinkedHashSet<>(locals), where);
                Set<String> caught = new LinkedHashSet<>(locals);
                caught.add(attempted.errorName());
                validateProcessOwnedStatements(processOwner, processClass, attempted.catchBody(), caught, where);
                validateProcessOwnedStatements(processOwner, processClass, attempted.finallyBody(),
                        new LinkedHashSet<>(locals), where);
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                validateProcessOwnedExpr(processOwner, processClass, loop.iterable(), locals, where);
                Set<String> loopLocals = new LinkedHashSet<>(locals);
                loopLocals.add(loop.bindingName());
                validateProcessOwnedStatements(processOwner, processClass, loop.body(), loopLocals, where);
            } else if (stmt instanceof Ast.ForStmt loop) {
                Set<String> loopLocals = new LinkedHashSet<>(locals);
                if (loop.initializer() != null) {
                    validateProcessOwnedStatements(processOwner, processClass,
                            List.of(loop.initializer()), loopLocals, where);
                    if (loop.initializer() instanceof Ast.BindingStmt binding) loopLocals.add(binding.name());
                }
                if (loop.condition() != null) {
                    validateProcessOwnedExpr(processOwner, processClass, loop.condition(), loopLocals, where);
                }
                if (loop.update() != null) {
                    validateProcessOwnedExpr(processOwner, processClass, loop.update(), loopLocals, where);
                }
                validateProcessOwnedStatements(processOwner, processClass, loop.body(), loopLocals, where);
            }
        }
    }

    private void validateProcessOwnedExpr(
            Ast.ModuleDecl processOwner,
            Ast.ClassDecl processClass,
            Ast.Expr expr,
            Set<String> locals,
            String where) {
        if (expr instanceof Ast.LiteralExpr) return;

        if (expr instanceof Ast.NameExpr name) {
            if (locals.contains(name.name()) || name.name().equals("self")
                    || name.name().equals("Some") || name.name().equals("None")) return;
            if (name.name().equals("stdio") || name.name().equals("process") || name.name().equals("print")) {
                throw processEffectError(where, "ambient caller capability '" + name.name() + "' (ambient capability)");
            }
            if (importedNames.contains(name.name())) {
                throw processEffectError(where, "imported dependency '" + name.name() + "'");
            }
            Ast.ModuleDecl referencedModule = modules.get(name.name());
            if (referencedModule != null && !referencedModule.singleton()) {
                throw processEffectError(where, "actor/context-local module '" + referencedModule.name() + "'");
            }

            Ast.FunctionDecl referencedFunction = findFunction(name.name());
            if (referencedFunction != null) {
                String ownerName = functionOwners.get(referencedFunction);
                Ast.ModuleDecl functionOwner = ownerName == null ? null : modules.get(ownerName);
                if (functionOwner == null || !functionOwner.singleton()
                        || !functionOwner.name().equals(processOwner.name())) {
                    throw processEffectError(where,
                            "ordinary/foreign function value '" + name.name() + "'");
                }
            }

            Ast.ClassDecl referencedClass = findClass(name.name());
            if (referencedClass != null && referencedClass != processClass) {
                String ownerName = classOwners.get(referencedClass);
                Ast.ModuleDecl classOwner = ownerName == null ? null : modules.get(ownerName);
                if (classOwner == null || !classOwner.singleton()
                        || !classOwner.name().equals(processOwner.name())) {
                    throw processEffectError(where,
                            "ordinary/foreign class namespace '" + name.name() + "'");
                }
            }
            return;
        }

        if (expr instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.NameExpr name && !locals.contains(name.name())) {
                Ast.FunctionDecl target = findFunction(name.name());
                if (target != null) {
                    String targetOwnerName = functionOwners.get(target);
                    Ast.ModuleDecl targetOwner = targetOwnerName == null ? null : modules.get(targetOwnerName);
                    if (targetOwner == null || !targetOwner.singleton()) {
                        throw processEffectError(where, "actor/context-local function '" + name.name() + "' (ordinary helper function)");
                    }
                }
            }
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr receiver
                    && !locals.contains(receiver.name())) {
                Ast.ModuleDecl targetModule = modules.get(receiver.name());
                if (targetModule != null && !targetModule.singleton()) {
                    throw processEffectError(where, "actor/context-local module '" + targetModule.name() + "'");
                }
                Ast.ClassDecl targetClass = findClass(receiver.name());
                if (targetClass != null && targetClass != processClass) {
                    String ownerName = classOwners.get(targetClass);
                    Ast.ModuleDecl classOwner = ownerName == null ? null : modules.get(ownerName);
                    if (classOwner == null || !classOwner.singleton()
                            || !classOwner.name().equals(processOwner.name())) {
                        throw processEffectError(where, "ordinary static class '" + targetClass.name() + "'");
                    }
                }
            }
            validateProcessOwnedExpr(processOwner, processClass, call.callee(), locals, where);
            for (Ast.Expr arg : call.arguments()) {
                validateProcessOwnedExpr(processOwner, processClass, arg, locals, where);
            }
            return;
        }

        if (expr instanceof Ast.NewExpr created) {
            Ast.ClassDecl target = findClass(created.type().name());
            if (target == null) {
                throw processEffectError(where, "unresolved or imported class construction '" + created.type().name() + "'");
            }
            if (target != processClass) {
                String ownerName = classOwners.get(target);
                Ast.ModuleDecl classOwner = ownerName == null ? null : modules.get(ownerName);
                if (classOwner == null || !classOwner.singleton()
                        || !classOwner.name().equals(processOwner.name())) {
                    throw processEffectError(where, "ordinary class construction '" + target.name() + "'");
                }
            }
            for (Ast.Expr arg : created.arguments()) {
                validateProcessOwnedExpr(processOwner, processClass, arg, locals, where);
            }
            return;
        }

        if (expr instanceof Ast.StructInitExpr created) {
            for (Ast.ObjectField field : created.fields()) {
                validateProcessOwnedExpr(processOwner, processClass, field.value(), locals, where);
            }
            return;
        }

        if (expr instanceof Ast.AssignExpr assignment) {
            validateProcessOwnedExpr(processOwner, processClass, assignment.target(), locals, where);
            validateProcessOwnedExpr(processOwner, processClass, assignment.value(), locals, where);
        } else if (expr instanceof Ast.BinaryExpr binary) {
            validateProcessOwnedExpr(processOwner, processClass, binary.left(), locals, where);
            validateProcessOwnedExpr(processOwner, processClass, binary.right(), locals, where);
        } else if (expr instanceof Ast.UnaryExpr unary) {
            validateProcessOwnedExpr(processOwner, processClass, unary.operand(), locals, where);
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            validateProcessOwnedExpr(processOwner, processClass, conditional.condition(), locals, where);
            validateProcessOwnedExpr(processOwner, processClass, conditional.whenTrue(), locals, where);
            validateProcessOwnedExpr(processOwner, processClass, conditional.whenFalse(), locals, where);
        } else if (expr instanceof Ast.MemberExpr member) {
            validateProcessOwnedExpr(processOwner, processClass, member.receiver(), locals, where);
        } else if (expr instanceof Ast.IndexExpr indexed) {
            validateProcessOwnedExpr(processOwner, processClass, indexed.receiver(), locals, where);
            validateProcessOwnedExpr(processOwner, processClass, indexed.index(), locals, where);
        } else if (expr instanceof Ast.AwaitExpr awaited) {
            validateProcessOwnedExpr(processOwner, processClass, awaited.expression(), locals, where);
        } else if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) {
                validateProcessOwnedExpr(processOwner, processClass, item, locals, where);
            }
        } else if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) {
                validateProcessOwnedExpr(processOwner, processClass, item, locals, where);
            }
        } else if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                validateProcessOwnedExpr(processOwner, processClass, field.value(), locals, where);
            }
        } else if (expr instanceof Ast.LambdaExpr lambda) {
            Set<String> lambdaLocals = new LinkedHashSet<>(locals);
            for (Ast.Param param : lambda.parameters()) lambdaLocals.add(param.name());
            if (lambda.expressionBody() != null) {
                validateProcessOwnedExpr(processOwner, processClass, lambda.expressionBody(), lambdaLocals, where);
            }
            if (lambda.blockBody() != null) {
                validateProcessOwnedStatements(processOwner, processClass, lambda.blockBody(), lambdaLocals, where);
            }
        }
    }

    private IllegalArgumentException processEffectError(String where, String dependency) {
        return new IllegalArgumentException(where
                + " cannot depend on " + dependency
                + "; process-singleton code may use only its own state/helpers and explicit singleton-service calls"
                + " until Oreslang has a process-safe effect declaration");
    }

    private boolean referencesActorModuleBinding(
            List<Ast.Stmt> statements,
            Set<String> actorBindings,
            Set<String> inheritedShadowed) {
        Set<String> shadowed = new LinkedHashSet<>(inheritedShadowed);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.TypeDeclStmt localType) {
                if (localType.declaration() instanceof Ast.ClassDecl struct) {
                    for (Ast.FieldDecl field : struct.fields()) {
                        if (field.initializer() != null
                                && referencesActorModuleBinding(field.initializer(), actorBindings, Set.of())) {
                            return true;
                        }
                    }
                    for (Ast.MethodDecl method : struct.methods()) {
                        Set<String> methodShadowed = new LinkedHashSet<>();
                        if (!method.isStatic()) methodShadowed.add("self");
                        for (Ast.Param param : method.parameters()) methodShadowed.add(param.name());
                        if (referencesActorModuleBinding(method.body(), actorBindings, methodShadowed)) {
                            return true;
                        }
                    }
                }
            } else if (stmt instanceof Ast.BindingStmt binding) {
                if (referencesActorModuleBinding(binding.initializer(), actorBindings, shadowed)) return true;
                shadowed.add(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                if (referencesActorModuleBinding(destructure.initializer(), actorBindings, shadowed)) return true;
                for (Ast.DestructureBinding binding : destructure.bindings()) shadowed.add(binding.name());
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                if (ret.value() != null && referencesActorModuleBinding(ret.value(), actorBindings, shadowed)) return true;
            } else if (stmt instanceof Ast.ExprStmt expression) {
                if (referencesActorModuleBinding(expression.expression(), actorBindings, shadowed)) return true;
            } else if (stmt instanceof Ast.DeferStmt defer) {
                if (referencesActorModuleBinding(defer.expression(), actorBindings, shadowed)) return true;
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    if (referencesActorModuleBinding(branch.condition(), actorBindings, shadowed)
                            || referencesActorModuleBinding(branch.body(), actorBindings, shadowed)) return true;
                }
                if (referencesActorModuleBinding(conditional.elseBody(), actorBindings, shadowed)) return true;
            } else if (stmt instanceof Ast.TryStmt attempted) {
                if (referencesActorModuleBinding(attempted.body(), actorBindings, shadowed)) return true;
                Set<String> caught = new LinkedHashSet<>(shadowed);
                caught.add(attempted.errorName());
                if (referencesActorModuleBinding(attempted.catchBody(), actorBindings, caught)
                        || referencesActorModuleBinding(attempted.finallyBody(), actorBindings, shadowed)) return true;
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                if (referencesActorModuleBinding(loop.iterable(), actorBindings, shadowed)) return true;
                Set<String> loopShadowed = new LinkedHashSet<>(shadowed);
                loopShadowed.add(loop.bindingName());
                if (referencesActorModuleBinding(loop.body(), actorBindings, loopShadowed)) return true;
            } else if (stmt instanceof Ast.ForStmt loop) {
                Set<String> loopShadowed = new LinkedHashSet<>(shadowed);
                if (loop.initializer() != null
                        && referencesActorModuleBinding(List.of(loop.initializer()), actorBindings, loopShadowed)) return true;
                if (loop.condition() != null
                        && referencesActorModuleBinding(loop.condition(), actorBindings, loopShadowed)) return true;
                if (loop.update() != null
                        && referencesActorModuleBinding(loop.update(), actorBindings, loopShadowed)) return true;
                if (referencesActorModuleBinding(loop.body(), actorBindings, loopShadowed)) return true;
            }
        }
        return false;
    }

    private boolean referencesActorModuleBinding(
            Ast.Expr expr,
            Set<String> actorBindings,
            Set<String> shadowed) {
        if (expr instanceof Ast.NameExpr name) {
            return actorBindings.contains(name.name()) && !shadowed.contains(name.name());
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            return referencesActorModuleBinding(assignment.target(), actorBindings, shadowed)
                    || referencesActorModuleBinding(assignment.value(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            return referencesActorModuleBinding(binary.left(), actorBindings, shadowed)
                    || referencesActorModuleBinding(binary.right(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            return referencesActorModuleBinding(unary.operand(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return referencesActorModuleBinding(conditional.condition(), actorBindings, shadowed)
                    || referencesActorModuleBinding(conditional.whenTrue(), actorBindings, shadowed)
                    || referencesActorModuleBinding(conditional.whenFalse(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.CallExpr call) {
            if (referencesActorModuleBinding(call.callee(), actorBindings, shadowed)) return true;
            for (Ast.Expr arg : call.arguments()) {
                if (referencesActorModuleBinding(arg, actorBindings, shadowed)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.MemberExpr member) {
            return referencesActorModuleBinding(member.receiver(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            return referencesActorModuleBinding(indexed.receiver(), actorBindings, shadowed)
                    || referencesActorModuleBinding(indexed.index(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr arg : created.arguments()) {
                if (referencesActorModuleBinding(arg, actorBindings, shadowed)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.StructInitExpr created) {
            for (Ast.ObjectField field : created.fields()) {
                if (referencesActorModuleBinding(field.value(), actorBindings, shadowed)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            return referencesActorModuleBinding(awaited.expression(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) {
                if (referencesActorModuleBinding(item, actorBindings, shadowed)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) {
                if (referencesActorModuleBinding(item, actorBindings, shadowed)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                if (referencesActorModuleBinding(field.value(), actorBindings, shadowed)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            Set<String> lambdaShadowed = new LinkedHashSet<>(shadowed);
            for (Ast.Param param : lambda.parameters()) lambdaShadowed.add(param.name());
            if (lambda.expressionBody() != null
                    && referencesActorModuleBinding(lambda.expressionBody(), actorBindings, lambdaShadowed)) return true;
            return lambda.blockBody() != null
                    && referencesActorModuleBinding(lambda.blockBody(), actorBindings, lambdaShadowed);
        }
        return false;
    }

    private boolean containsTypeAlias(Ast.TypeRef type) {
        if (type == null) return false;
        if (findTypeAlias(type.name()) != null) return true;
        for (Ast.TypeRef argument : type.arguments()) {
            if (containsTypeAlias(argument)) return true;
        }
        return false;
    }

    private boolean isProcessStableStateType(Type type) {
        if (type instanceof StringLiteral) return true;
        if (type instanceof Primitive primitive) return primitive != Primitive.VOID;
        if (type instanceof ListType list) return isProcessStableStateType(list.element());
        if (type instanceof Tuple tuple) return tuple.elements().stream().allMatch(this::isProcessStableStateType);
        if (type instanceof Named named && named.name().equals("Option") && named.arguments().size() == 1) {
            return isProcessStableStateType(named.arguments().getFirst());
        }
        return false;
    }

    private boolean isActorSendableType(Type type, boolean allowVoid) {
        if (type instanceof StringLiteral) return true;
        if (type instanceof Primitive primitive) {
            return primitive != Primitive.VOID || allowVoid;
        }
        if (type instanceof ListType list) return isActorSendableType(list.element(), false);
        if (type instanceof Tuple tuple) return tuple.elements().stream().allMatch(item -> isActorSendableType(item, false));
        if (type instanceof Record record) return record.members().values().stream()
                .allMatch(item -> isActorSendableType(item, false));
        if (type instanceof Named named && named.name().equals("Option") && named.arguments().size() == 1) {
            return isActorSendableType(named.arguments().getFirst(), false);
        }
        return false;
    }

    private boolean isPureSingletonInitializer(Ast.Expr expr, Set<String> initializedFields) {
        if (expr instanceof Ast.LiteralExpr) return true;
        if (expr instanceof Ast.NameExpr name) {
            return name.name().equals("None") || initializedFields.contains(name.name());
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            return !unary.operator().equals("&")
                    && !unary.operator().equals("&mut")
                    && isPureSingletonInitializer(unary.operand(), initializedFields);
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            return isPureSingletonInitializer(binary.left(), initializedFields)
                    && isPureSingletonInitializer(binary.right(), initializedFields);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return isPureSingletonInitializer(conditional.condition(), initializedFields)
                    && isPureSingletonInitializer(conditional.whenTrue(), initializedFields)
                    && isPureSingletonInitializer(conditional.whenFalse(), initializedFields);
        }
        if (expr instanceof Ast.ListExpr list) {
            return list.elements().stream().allMatch(item -> isPureSingletonInitializer(item, initializedFields));
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            return tuple.elements().stream().allMatch(item -> isPureSingletonInitializer(item, initializedFields));
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            return isPureSingletonInitializer(indexed.receiver(), initializedFields)
                    && isPureSingletonInitializer(indexed.index(), initializedFields);
        }
        if (expr instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.NameExpr name
                && name.name().equals("Some")
                && call.arguments().size() == 1) {
            return isPureSingletonInitializer(call.arguments().getFirst(), initializedFields);
        }
        return false;
    }

    private Function singletonTransportType(Ast.FunctionDecl fn) {
        Function logical = declaredFunctionType(fn);
        return new Function(logical.parameters(), new Named("Future", List.of(logical.result())));
    }

    private void checkClass(String module, Ast.ClassDecl klass) {
        String aggregateLabel = klass.isStruct() ? "struct " : "class ";
        Set<String> classGenerics = uniqueGenerics(klass.genericParameters(), aggregateLabel + klass.name());
        Type self = nominalClassType(klass);

        Set<String> parentNames = new HashSet<>();
        for (Ast.TypeRef parent : klass.parents()) {
            if (!parentNames.add(parent.name())) throw new IllegalArgumentException("duplicate parent class '" + parent.name() + "' on " + klass.name());
            resolveClassParent(parent, klass);
        }

        Set<String> localMethodSignatures = new HashSet<>();
        for (Ast.MethodDecl method : klass.methods()) {
            String memberKind = method.isStatic() ? "static:" : "instance:";
            String signature = memberKind + methodKey(method.name(), method.arity());
            if (!localMethodSignatures.add(signature)) {
                String label = method.isStatic() ? "static function" : "method";
                throw new IllegalArgumentException(label + " '" + klass.name() + "." + method.name() + "' already has arity " + method.arity()
                        + "; class callables may overload only by arity within their own static/instance namespace");
            }
        }

        Ast.ModuleDecl lexicalOwner = modules.get(module);
        Env classModuleEnv = lexicalOwner == null
                ? new Env(null, module)
                : lexicalOwner.singleton() ? singletonModuleEnv(lexicalOwner) : moduleBindingEnv(lexicalOwner);

        for (Ast.FieldDecl field : klass.fields()) {
            Type fieldType = resolve(field.type(), classGenerics, self);
            if (field.initializer() != null) {
                validateSingletonTransportExpr(field.initializer(), module);
                Type actual = typeOf(field.initializer(), new Env(classModuleEnv), classGenerics, self);
                requireAssignable(actual, fieldType, "field initializer " + klass.name() + "." + field.name());
            }
            if (field.bindingKind() == Ast.BindingKind.CONST && field.initializer() != null && !constant(field.initializer())) {
                throw new IllegalArgumentException("const field '" + field.name() + "' needs a compile-time constant initializer");
            }
        }

        for (Ast.MethodDecl method : klass.methods()) {
            String previousTraitOwner = activeTraitOwner;
            Ast.ClassDecl previousClassOwner = activeClassOwner;
            activeTraitOwner = method.compositionOwner();
            activeClassOwner = klass;
            pushLocalTypeScope(method.body());
            try {
                Set<String> inheritedGenerics = method.isStatic() ? Set.of() : classGenerics;
                if (method.isStatic() && !classGenerics.isEmpty()) {
                    LinkedHashSet<String> forbidden = new LinkedHashSet<>(classGenerics);
                    forbidden.removeAll(method.genericParameters());
                    if (!forbidden.isEmpty() && methodUsesTypeNames(method, forbidden)) {
                        throw new IllegalArgumentException("static function '" + klass.name() + "." + method.name()
                                + "' cannot use enclosing class generic parameter(s) " + forbidden
                                + "; declare independent static-function generics instead");
                    }
                }

                Set<String> generics = new HashSet<>(inheritedGenerics);
                for (String generic : method.genericParameters()) {
                    if (!generics.add(generic)) throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in " + klass.name() + "." + method.name());
                }
                rejectLocalTypeGenericCollisions(method.body(), generics);

                if (method.explicitReceiverType() != null) {
                    Ast.TypeRef receiverRef = method.explicitReceiverType();
                    Ast.TypeRef receiverTargetRef = receiverRef.isBorrow()
                            ? receiverRef.borrowedTarget()
                            : receiverRef;
                    boolean namesEnclosingClass = receiverTargetRef.name().equals(klass.name())
                            || receiverTargetRef.name().equals(qualifiedClassName(klass))
                            || receiverTargetRef.name().equals("self");
                    if (!namesEnclosingClass) {
                        Type receiver = resolve(receiverTargetRef, generics, self);
                        requireAssignable(self, receiver, "explicit self receiver in " + klass.name() + "." + method.name());
                    }
                }

                Type callableSelf = method.isStatic() ? null : self;
                Env env = new Env(classModuleEnv);
                if (!method.isStatic()) env.define("self", self, Ast.BindingKind.VAL);
                for (Ast.Param param : method.parameters()) env.define(param.name(), resolveParam(param, generics, callableSelf), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
                Type returns = resolve(method.returnType(), generics, callableSelf);
                validateSingletonTransportStatements(method.body(), module);
                checkBlock(method.body(), env, generics, returns, callableSelf);
                if (!method.isAbstract() && returns != Primitive.VOID && !definitelyReturns(method.body())) {
                    String label = method.isStatic() ? "static function" : "method";
                    throw new IllegalArgumentException("non-void " + label + " '" + module + "." + klass.name() + "." + method.name() + "' must explicitly return on every path");
                }
            } finally {
                popLocalTypeScope();
                activeTraitOwner = previousTraitOwner;
                activeClassOwner = previousClassOwner;
            }
        }

        Set<String> implemented = new HashSet<>();
        for (Ast.TypeRef interfaceRef : klass.interfaces()) {
            if (!implemented.add(interfaceRef.name())) throw new IllegalArgumentException("duplicate implemented interface '" + interfaceRef.name() + "' on " + klass.name());
            Ast.InterfaceDecl iface = findInterface(interfaceRef.name());
            if (iface == null) throw new IllegalArgumentException("unknown interface '" + interfaceRef.name() + "' implemented by " + klass.name());
            Record expected = interfaceShapeForUse(iface, interfaceRef, classGenerics, self);
            Record actual = publicClassShape(klass, new LinkedHashSet<>());
            if (!assignable(actual, expected)) {
                throw new IllegalArgumentException((klass.isStruct() ? "struct '" : "class '") + klass.name()
                        + "' does not implement interface '" + interfaceRef.name() + "': expected " + expected + " but got " + actual);
            }
        }
    }

    private void checkModuleBinding(Ast.ModuleDecl module, Ast.FieldDecl field) {
        if (field.initializer() == null) throw new IllegalArgumentException("module binding '" + field.name() + "' requires an initializer");
        validateSingletonTransportExpr(field.initializer(), module.name());
        Type actual = typeOf(field.initializer(), new Env(null, module.name()), Set.of(), null);
        if (field.type() != null) requireAssignable(actual, resolve(field.type(), Set.of(), null), "initializer for " + field.name());
        if (field.bindingKind() == Ast.BindingKind.CONST && !constant(field.initializer())) {
            throw new IllegalArgumentException("const '" + field.name() + "' needs a compile-time constant initializer");
        }
    }

    private void checkBlock(List<Ast.Stmt> body, Env parent, Set<String> generics, Type expectedReturn, Type self) {
        rejectLocalTypeGenericCollisions(body, generics);
        pushLocalTypeScope(body);
        try {
            Env env = new Env(parent);
            for (Ast.Stmt stmt : body) checkStatement(stmt, env, generics, expectedReturn, self);
        } finally {
            popLocalTypeScope();
        }
    }

    private void checkStatement(Ast.Stmt stmt, Env env, Set<String> generics, Type expectedReturn, Type self) {
        if (stmt instanceof Ast.TypeDeclStmt localType) {
            if (localType.declaration() instanceof Ast.ClassDecl struct) {
                checkClass(env.moduleName, struct);
            } else if (localType.declaration() instanceof Ast.InterfaceDecl iface) {
                checkInterface(iface);
            } else if (localType.declaration() instanceof Ast.TypeAliasDecl alias) {
                resolve(alias.target(), Set.copyOf(alias.genericParameters()), self);
            }
            return;
        }
        if (stmt instanceof Ast.BindingStmt binding) {
            Type declaredAhead = binding.declaredType() == null ? null : resolve(binding.declaredType(), generics, self);
            boolean recursiveLambda = binding.initializer() instanceof Ast.LambdaExpr;
            if (recursiveLambda && declaredAhead instanceof Function) {
                env.define(binding.name(), declaredAhead, binding.kind());
            }
            if (binding.initializer() instanceof Ast.LambdaExpr lambda && declaredAhead instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }
            Type actual = typeOf(binding.initializer(), env, generics, self);
            if (actual instanceof SingletonProxy proxy) {
                throw new IllegalArgumentException("singleton object proxy '" + proxy.moduleName() + "."
                        + proxy.fieldName() + "' cannot be rebound locally; invoke and await its methods directly");
            }
            Type declared = declaredAhead == null ? actual : declaredAhead;
            requireAssignableExpression(binding.initializer(), actual, declared, "initializer for " + binding.name());
            if (binding.kind() == Ast.BindingKind.CONST && !constant(binding.initializer())) {
                throw new IllegalArgumentException("const '" + binding.name() + "' needs a compile-time constant initializer");
            }
            if (recursiveLambda && declaredAhead instanceof Function) env.replace(binding.name(), declared, binding.kind());
            else env.define(binding.name(), declared, binding.kind());
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            Type source = typeOf(destructure.initializer(), env, generics, self);
            if (source instanceof Tuple tuple) {
                if (tuple.elements().size() != destructure.bindings().size()) throw new IllegalArgumentException("destructure arity mismatch");
                for (int i = 0; i < destructure.bindings().size(); i++) {
                    Ast.DestructureBinding binding = destructure.bindings().get(i);
                    env.define(binding.name(), tuple.elements().get(i), binding.kind());
                }
            } else if (source instanceof ListType list) {
                for (Ast.DestructureBinding binding : destructure.bindings()) env.define(binding.name(), list.element(), binding.kind());
            } else throw new IllegalArgumentException("destructuring requires a tuple or array/list value");
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() instanceof Ast.LambdaExpr lambda && expectedReturn instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }
            Type actual = ret.value() == null ? Primitive.VOID : typeOf(ret.value(), env, generics, self);
            requireAssignableExpression(ret.value(), actual, expectedReturn, "return value");
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) { typeOf(expression.expression(), env, generics, self); return; }
        if (stmt instanceof Ast.DeferStmt defer) { typeOf(defer.expression(), env, generics, self); return; }
        if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                requireAssignable(typeOf(branch.condition(), env, generics, self), Primitive.BOOL, "if condition");
                checkBlock(branch.body(), env, generics, expectedReturn, self);
            }
            checkBlock(conditional.elseBody(), env, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.TryStmt attempted) {
            checkBlock(attempted.body(), env, generics, expectedReturn, self);
            Env caught = new Env(env);
            caught.define(attempted.errorName(), Unknown.INSTANCE, Ast.BindingKind.VAL);
            checkBlock(attempted.catchBody(), caught, generics, expectedReturn, self);
            checkBlock(attempted.finallyBody(), env, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.ForOfStmt loop) {
            Type iterable = typeOf(loop.iterable(), env, generics, self);
            Type element = iterableElementType(iterable);
            Env loopEnv = new Env(env);
            loopEnv.define(loop.bindingName(), element, loop.bindingKind());
            checkBlock(loop.body(), loopEnv, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.ForStmt loop) {
            Env loopEnv = new Env(env);
            if (loop.initializer() != null) checkStatement(loop.initializer(), loopEnv, generics, expectedReturn, self);
            if (loop.condition() != null) requireAssignable(typeOf(loop.condition(), loopEnv, generics, self), Primitive.BOOL, "for condition");
            if (loop.update() != null) typeOf(loop.update(), loopEnv, generics, self);
            checkBlock(loop.body(), loopEnv, generics, expectedReturn, self);
        }
    }

    private Type typeOf(Ast.Expr expr, Env env, Set<String> generics, Type self) {
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value == null) throw new IllegalArgumentException("standalone null values are forbidden; use Option<T>");
            if (value instanceof Long) return Primitive.INT;
            if (value instanceof Double) return Primitive.FLOAT;
            if (value instanceof Boolean) return Primitive.BOOL;
            if (value instanceof String s) return new StringLiteral(s);
            if (value instanceof Ast.Imaginary) return Primitive.COMPLEX;
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.NameExpr name) {
            Env.Binding local = env.lookup(name.name());
            if (local != null) return local.type();
            if (name.name().equals("stdio") || name.name().equals("process")) return new Named(name.name(), List.of());
            if (name.name().equals("print")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            if (name.name().equals("None")) return new Named("Option", List.of(Unknown.INSTANCE));
            Ast.ModuleDecl moduleNamespace = modules.get(name.name());
            if (moduleNamespace != null) return moduleShape(moduleNamespace);
            Ast.ClassDecl classNamespace = findClass(name.name());
            if (classNamespace != null) {
                String ownerName = classOwners.get(classNamespace);
                Ast.ModuleDecl owner = ownerName == null ? null : modules.get(ownerName);
                if (owner != null && owner.singleton() && !owner.name().equals(env.moduleName)) {
                    throw new IllegalArgumentException("class '" + classNamespace.name()
                            + "' is actor-private inside singleton module '" + owner.name() + "'");
                }
                return new ClassNamespace(qualifiedClassName(classNamespace));
            }
            if (importedValues.contains(name.name())) return Unknown.INSTANCE;
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) {
                if (!fn.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException("generic callable '" + fn.name()
                            + "' must be invoked directly; polymorphic function values are not supported yet");
                }
                String ownerName = functionOwners.get(fn);
                Ast.ModuleDecl owner = ownerName == null ? null : modules.get(ownerName);
                if (owner != null && owner.singleton() && !owner.name().equals(env.moduleName)) {
                    if (fn.visibility() != Ast.Visibility.PUBLIC) {
                        throw new IllegalArgumentException("private singleton callable '" + owner.name() + "."
                                + fn.name() + "' is actor-private");
                    }
                    return singletonTransportType(fn);
                }
                return declaredFunctionType(fn);
            }
            throw new IllegalArgumentException("unknown name '" + name.name() + "'");
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            Type targetType;
            String where;
            if (assignment.target() instanceof Ast.NameExpr name) {
                Env.Binding binding = env.lookup(name.name());
                if (binding == null) throw new IllegalArgumentException("cannot assign unknown name '" + name.name() + "'");
                if (binding.kind() != Ast.BindingKind.LET) throw new IllegalArgumentException("cannot reassign " + binding.kind().name().toLowerCase() + " binding '" + name.name() + "'");
                targetType = binding.type();
                where = name.name();
            } else if (assignment.target() instanceof Ast.MemberExpr member) {
                targetType = memberType(member, env, generics, self);
                where = member.member();
            } else if (assignment.target() instanceof Ast.IndexExpr indexed) {
                Type receiver = deref(typeOf(indexed.receiver(), env, generics, self));
                Type index = typeOf(indexed.index(), env, generics, self);
                requireAssignable(index, Primitive.INT, "array/list index");
                if (receiver instanceof ListType list) targetType = list.element();
                else if (receiver instanceof Tuple tuple) targetType = tuple.elements().stream().reduce(Unknown.INSTANCE, this::commonType);
                else throw new IllegalArgumentException("indexed assignment requires an array/list or tuple");
                where = "index";
            } else throw new IllegalArgumentException("unsupported assignment target");
            Type value = typeOf(assignment.value(), env, generics, self);
            requireAssignable(value, targetType, "assignment to " + where);
            return targetType;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            requireAssignable(typeOf(conditional.condition(), env, generics, self), Primitive.BOOL, "ternary condition");
            Type left = typeOf(conditional.whenTrue(), env, generics, self);
            Type right = typeOf(conditional.whenFalse(), env, generics, self);
            return commonType(left, right);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            Type operand = typeOf(unary.operand(), env, generics, self);
            if (unary.operator().equals("&")) return new Borrow(operand, false);
            if (unary.operator().equals("&mut")) return new Borrow(operand, true);
            if (unary.operator().equals("!")) {
                requireAssignable(operand, Primitive.BOOL, "! operand");
                return Primitive.BOOL;
            }
            if (!Types.isNumeric(operand)) throw new IllegalArgumentException("unary " + unary.operator() + " needs a numeric operand");
            return operand;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            Type left = typeOf(binary.left(), env, generics, self);
            Type right = typeOf(binary.right(), env, generics, self);
            return switch (binary.operator()) {
                case ",", "|" -> {
                    requireAssignable(left, Primitive.BOOL, "boolean operand");
                    requireAssignable(right, Primitive.BOOL, "boolean operand");
                    yield Primitive.BOOL;
                }
                case "==", "!=" -> Primitive.BOOL;
                case "<", "<=", ">", ">=" -> {
                    if (!(Types.isNumeric(left) && Types.isNumeric(right)) && !(isStringLike(left) && isStringLike(right))) {
                        throw new IllegalArgumentException("comparison operands must both be numeric or both strings");
                    }
                    yield Primitive.BOOL;
                }
                case "+" -> isStringLike(left) && isStringLike(right) ? Primitive.STRING : numericJoin(left, right, "+");
                case "-", "*", "/", "%" -> numericJoin(left, right, binary.operator());
                default -> Unknown.INSTANCE;
            };
        }
        if (expr instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.NameExpr name && name.name().equals("Some")) {
                if (call.arguments().size() != 1) throw new IllegalArgumentException("Some expects exactly one value");
                return new Named("Option", List.of(typeOf(call.arguments().getFirst(), env, generics, self)));
            }
            if (call.callee() instanceof Ast.NameExpr name && env.lookup(name.name()) == null) {
                Ast.FunctionDecl target = findFunction(name.name());
                if (target != null && !target.genericParameters().isEmpty()) {
                    List<Type> actualTypes = call.arguments().stream()
                            .map(argument -> typeOf(argument, env, generics, self))
                            .toList();
                    Function signature = instantiateCallableGenerics(
                            declaredFunctionType(target),
                            target.genericParameters(),
                            actualTypes,
                            "call to " + target.name());
                    validateCallArguments(call.arguments(), actualTypes, signature, env, generics, self, "argument");
                    return signature.result();
                }
            }
            if (call.callee() instanceof Ast.MemberExpr member) {
                if (member.receiver() instanceof Ast.NameExpr namespace
                        && env.lookup(namespace.name()) == null
                        && modules.containsKey(namespace.name())) {
                    Ast.ModuleDecl module = modules.get(namespace.name());
                    Ast.FunctionDecl target = module.declarations().stream()
                            .filter(Ast.FunctionDecl.class::isInstance)
                            .map(Ast.FunctionDecl.class::cast)
                            .filter(fn -> fn.visibility() == Ast.Visibility.PUBLIC
                                    && fn.name().equals(member.member())
                                    && fn.parameters().size() == call.arguments().size())
                            .findFirst().orElse(null);
                    if (target != null) {
                        List<Type> actualTypes = call.arguments().stream()
                                .map(argument -> typeOf(argument, env, generics, self))
                                .toList();
                        Function signature = target.genericParameters().isEmpty()
                                ? declaredFunctionType(target)
                                : instantiateCallableGenerics(
                                        declaredFunctionType(target),
                                        target.genericParameters(),
                                        actualTypes,
                                        "call to " + module.name() + "." + target.name());
                        validateCallArguments(call.arguments(), actualTypes, signature, env, generics, self, "argument");

                        Type result = signature.result();
                        if (module.singleton() && !module.name().equals(env.moduleName)) {
                            return new Named("Future", List.of(result));
                        }
                        return result;
                    }
                }

                Type receiver = deref(typeOf(member.receiver(), env, generics, self));
                if (receiver instanceof ClassNamespace classNamespace) {
                    Ast.ClassDecl klass = findClass(classNamespace.className());
                    if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                    Ast.MethodDecl fn = findStaticFunction(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                    if (fn == null) throw new IllegalArgumentException("no static function '" + member.member() + "' with arity " + call.arguments().size() + " on " + klass.name());
                    List<Type> actualTypes = call.arguments().stream()
                            .map(argument -> typeOf(argument, env, generics, self))
                            .toList();
                    Function signature = instantiateCallableGenerics(
                            methodFunctionType(fn, klass, null),
                            fn.genericParameters(),
                            actualTypes,
                            "call to " + klass.name() + "." + fn.name());
                    validateCallArguments(call.arguments(), actualTypes, signature, env, generics, self, "argument");
                    return signature.result();
                }
                if (receiver instanceof SingletonProxy proxy) {
                    Ast.ClassDecl klass = findClass(proxy.target().name());
                    if (klass == null) throw new IllegalArgumentException("unknown singleton proxy class '" + proxy.target().name() + "'");
                    Ast.MethodDecl method = findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                    if (method == null || method.visibility() != Ast.Visibility.PUBLIC) {
                        throw new IllegalArgumentException("no public singleton proxy method '" + member.member()
                                + "' with arity " + call.arguments().size() + " on " + proxy.target().name());
                    }
                    List<Type> actualTypes = call.arguments().stream()
                            .map(argument -> typeOf(argument, env, generics, self))
                            .toList();
                    Function signature = instantiateCallableGenerics(
                            methodFunctionTypeForReceiver(method, klass, proxy.target()),
                            method.genericParameters(),
                            actualTypes,
                            "singleton proxy call " + klass.name() + "." + method.name());
                    validateCallArguments(call.arguments(), actualTypes, signature, env, generics, self, "singleton proxy argument");
                    return new Named("Future", List.of(signature.result()));
                }
                if (receiver instanceof Record record) {
                    Type candidate = record.members().get(methodKey(member.member(), call.arguments().size()));
                    if (!(candidate instanceof Function fn)) {
                        throw new IllegalArgumentException("no structural method '" + member.member()
                                + "' with arity " + call.arguments().size());
                    }
                    List<Type> actualTypes = call.arguments().stream()
                            .map(argument -> typeOf(argument, env, generics, self))
                            .toList();
                    LinkedHashSet<String> inferable = collectGenericNames(fn);
                    inferable.removeAll(generics);
                    Function signature = instantiateCallableGenerics(
                            fn,
                            List.copyOf(inferable),
                            actualTypes,
                            "structural call " + member.member());
                    validateCallArguments(call.arguments(), actualTypes, signature, env, generics, self, "structural argument");
                    return signature.result();
                }
                if (receiver instanceof Named named) {
                    Ast.ClassDecl klass = findClass(named.name());
                    if (klass != null) {
                        Ast.MethodDecl method = findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                        if (method == null) throw new IllegalArgumentException("no method '" + member.member() + "' with arity " + call.arguments().size() + " on " + named.name());
                        requireTraitMethodAccessible(method, named.name() + "." + member.member());
                        List<Type> actualTypes = call.arguments().stream()
                                .map(argument -> typeOf(argument, env, generics, self))
                                .toList();
                        Function signature = instantiateCallableGenerics(
                                methodFunctionTypeForReceiver(method, klass, named),
                                method.genericParameters(),
                                actualTypes,
                                "call to " + named.name() + "." + method.name());
                        validateCallArguments(call.arguments(), actualTypes, signature, env, generics, self, "argument");
                        return signature.result();
                    }

                    Ast.InterfaceDecl iface = findInterface(named.name());
                    if (iface != null) {
                        Record shape = interfaceShapeForNamed(iface, named);
                        Type candidate = shape.members().get(methodKey(member.member(), call.arguments().size()));
                        if (!(candidate instanceof Function signature)) {
                            throw new IllegalArgumentException("no interface method '" + member.member()
                                    + "' with arity " + call.arguments().size() + " on " + named.name());
                        }
                        for (int i = 0; i < signature.parameters().size(); i++) {
                            Type expected = signature.parameters().get(i);
                            validateLambdaArgument(call.arguments().get(i), expected, env, generics, self);
                            requireAssignable(typeOf(call.arguments().get(i), env, generics, self),
                                    expected, "interface argument " + (i + 1));
                        }
                        return signature.result();
                    }
                }
            }
            Type callee = typeOf(call.callee(), env, generics, self);
            if (!(callee instanceof Function fn)) return Unknown.INSTANCE;
            if (fn.parameters().size() != call.arguments().size()) throw new IllegalArgumentException("call arity mismatch");
            for (int i = 0; i < fn.parameters().size(); i++) {
                validateLambdaArgument(call.arguments().get(i), fn.parameters().get(i), env, generics, self);
                requireAssignable(typeOf(call.arguments().get(i), env, generics, self), fn.parameters().get(i), "argument " + (i + 1));
            }
            return fn.result();
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr namespace && modules.containsKey(namespace.name())) {
                Ast.ModuleDecl module = modules.get(namespace.name());
                Ast.ClassDecl memberClass = classes.get(namespace.name() + "." + member.member());
                if (memberClass != null) {
                    if (module.singleton() && !module.name().equals(env.moduleName)) {
                        throw new IllegalArgumentException("classes inside singleton module '" + module.name()
                                + "' are actor-private and cannot be accessed through its external proxy");
                    }
                    return new ClassNamespace(qualifiedClassName(memberClass));
                }
                if (module.singleton()) {
                    for (Ast.Decl decl : module.declarations()) {
                        if (decl instanceof Ast.FunctionDecl fn
                                && fn.visibility() == Ast.Visibility.PUBLIC
                                && fn.name().equals(member.member())) {
                            return module.name().equals(env.moduleName)
                                    ? declaredFunctionType(fn)
                                    : singletonTransportType(fn);
                        }
                        if (decl instanceof Ast.FieldDecl field
                                && field.visibility() == Ast.Visibility.PUBLIC
                                && field.name().equals(member.member())) {
                            Type declared = resolve(field.type(), Set.of(), null);
                            if (!(declared instanceof Named named) || findClass(named.name()) == null) {
                                throw new IllegalArgumentException("singleton module field '" + module.name() + "."
                                        + field.name() + "' is not an exported class-instance proxy");
                            }
                            return module.name().equals(env.moduleName)
                                    ? named
                                    : new SingletonProxy(module.name(), field.name(), named);
                        }
                    }
                }
            }
            if (member.receiver() instanceof Ast.NameExpr namespace && modules.containsKey(namespace.name())) {
                Ast.ModuleDecl module = modules.get(namespace.name());
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.FunctionDecl fn
                            && fn.visibility() == Ast.Visibility.PUBLIC
                            && fn.name().equals(member.member())
                            && !fn.genericParameters().isEmpty()) {
                        throw new IllegalArgumentException("generic callable '" + module.name() + "." + fn.name()
                                + "' must be invoked directly; polymorphic function values are not supported yet");
                    }
                }
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("stdio")) {
                if (member.member().equals("print") || member.member().equals("println")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
                if (member.member().equals("stdout")) return new Named("stdio.stdout", List.of());
            }
            Type receiver = typeOf(member.receiver(), env, generics, self);
            if (receiver instanceof Named named && named.name().equals("stdio.stdout") && member.member().equals("write")) {
                return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("process")) return Unknown.INSTANCE;
            if (member.receiver() instanceof Ast.NameExpr name && importedValues.contains(name.name())) return Unknown.INSTANCE;

            if (receiver instanceof SingletonProxy proxy) {
                Ast.ClassDecl klass = findClass(proxy.target().name());
                if (klass == null) throw new IllegalArgumentException("unknown singleton proxy class '" + proxy.target().name() + "'");
                if (findFieldType(klass, member.member(), proxy.target(), new LinkedHashSet<>(), false) != null) {
                    throw new IllegalArgumentException("singleton object fields are actor-private; invoke a public method on "
                            + proxy.moduleName() + "." + proxy.fieldName());
                }
                if (!findMethodsByName(klass, member.member(), new LinkedHashSet<>()).isEmpty()) {
                    throw new IllegalArgumentException("singleton proxy method values cannot be extracted; call and await "
                            + proxy.moduleName() + "." + proxy.fieldName() + "." + member.member() + "(...) directly");
                }
                throw new IllegalArgumentException("unknown singleton proxy member '" + member.member() + "'");
            }

            if (receiver instanceof ClassNamespace classNamespace) {
                Ast.ClassDecl klass = findClass(classNamespace.className());
                if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                List<Ast.MethodDecl> functions = findStaticFunctionsByName(klass, member.member(), new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    if (!fn.genericParameters().isEmpty()) {
                        throw new IllegalArgumentException("generic static function '" + klass.name() + "." + fn.name()
                                + "' must be invoked directly; polymorphic function values are not supported yet");
                    }
                    return methodFunctionType(fn, klass, null);
                }
                if (functions.size() > 1) throw new IllegalArgumentException("overloaded static function '" + member.member() + "' must be called so arity can select the overload");
                throw new IllegalArgumentException("unknown static member '" + member.member() + "' on " + klass.name());
            }

            if (receiver instanceof Record record) {
                Type result = record.members().get(member.member());
                if (result == null) throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
                return result;
            }
            if (receiver instanceof Named named) {
                Ast.ClassDecl klass = findClass(named.name());
                if (klass != null) {
                    Type field = findFieldType(klass, member.member(), named, new LinkedHashSet<>());
                    if (field != null) return field;
                    List<Ast.MethodDecl> methods = findMethodsByName(klass, member.member(), new LinkedHashSet<>());
                    if (methods.size() == 1) {
                        Ast.MethodDecl method = methods.getFirst();
                        requireTraitMethodAccessible(method, named.name() + "." + member.member());
                        if (!method.genericParameters().isEmpty()) {
                            throw new IllegalArgumentException("generic method '" + named.name() + "." + method.name()
                                    + "' must be invoked directly; polymorphic method values are not supported yet");
                        }
                        return methodFunctionTypeForReceiver(method, klass, named);
                    }
                    if (methods.size() > 1) throw new IllegalArgumentException("overloaded method '" + member.member() + "' must be called so arity can select the overload");
                }

                Ast.InterfaceDecl iface = findInterface(named.name());
                if (iface != null) {
                    Record shape = interfaceShapeForNamed(iface, named);
                    List<Function> candidates = shape.members().entrySet().stream()
                            .filter(entry -> entry.getKey().startsWith(member.member() + "$arity"))
                            .map(Map.Entry::getValue)
                            .filter(Function.class::isInstance)
                            .map(Function.class::cast)
                            .toList();
                    if (candidates.size() == 1) return candidates.getFirst();
                    if (candidates.size() > 1) {
                        throw new IllegalArgumentException("overloaded interface method '" + member.member()
                                + "' must be called so arity can select the overload");
                    }
                    throw new IllegalArgumentException("unknown interface member '" + member.member()
                            + "' on " + named.name());
                }
            }
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            Type receiver = deref(typeOf(indexed.receiver(), env, generics, self));
            Type index = typeOf(indexed.index(), env, generics, self);
            requireAssignable(index, Primitive.INT, "array/list index");
            if (receiver instanceof ListType list) return list.element();
            if (receiver instanceof Tuple tuple) return tuple.elements().stream().reduce(Unknown.INSTANCE, this::commonType);
            throw new IllegalArgumentException("indexing requires an array/list or tuple");
        }
        if (expr instanceof Ast.NewExpr created) {
            Ast.ClassDecl klass = findClass(created.type().name());
            if (klass == null) {
                if (created.type().inferArguments()) {
                    throw new IllegalArgumentException("cannot infer generic arguments for unknown/imported class '"
                            + created.type().name() + "' without its declaration; provide explicit type arguments");
                }
                return new Named(created.type().name(),
                        created.type().arguments().stream().map(a -> resolve(a, generics, self)).toList());
            }
            if (klass.isStruct()) throw new IllegalArgumentException("struct '" + klass.name() + "' is a value type; initialize it with " + klass.name() + " { ... }, not new");
            String ownerName = classOwners.get(klass);
            Ast.ModuleDecl owner = ownerName == null ? null : modules.get(ownerName);
            if (owner != null && owner.singleton() && !owner.name().equals(env.moduleName)) {
                throw new IllegalArgumentException("class '" + klass.name()
                        + "' is actor-private inside singleton module '" + owner.name() + "'");
            }
            List<Ast.FieldDecl> fields = effectiveFields(klass, new LinkedHashSet<>());
            List<Ast.FieldDecl> constructorFields = fields.stream().filter(field -> !field.composed()).toList();
            if (created.arguments().size() > constructorFields.size()) throw new IllegalArgumentException("constructor for " + klass.name() + " received too many positional fields");

            List<Type> argumentTypes = created.arguments().stream()
                    .map(argument -> typeOf(argument, env, generics, self))
                    .toList();
            Type nominal;
            if (created.type().inferArguments()) {
                Type genericSelf = nominalClassType(klass);
                List<Type> declaredInputs = new ArrayList<>();
                for (Ast.FieldDecl field : constructorFields.subList(0, argumentTypes.size())) {
                    Type pattern = findFieldType(klass, field.name(), genericSelf, new LinkedHashSet<>(), false);
                    if (pattern == null) throw new IllegalArgumentException("internal constructor field lookup failed for " + field.name());
                    declaredInputs.add(pattern);
                }
                nominal = inferClassTypeFromPatterns(klass, declaredInputs, argumentTypes, "constructor for " + klass.name());
            } else {
                nominal = concreteClassType(klass, created.type(), generics, self);
            }

            int argumentIndex = 0;
            for (Ast.FieldDecl field : fields) {
                if (!field.composed() && argumentIndex < argumentTypes.size()) {
                    Type expectedField = findFieldType(klass, field.name(), nominal, new LinkedHashSet<>(), false);
                    if (expectedField == null) throw new IllegalArgumentException("internal constructor field lookup failed for " + field.name());
                    requireAssignable(argumentTypes.get(argumentIndex++), expectedField, "constructor field " + field.name());
                } else if (field.initializer() == null) {
                    throw new IllegalArgumentException("constructor for " + klass.name() + " is missing field '" + field.name() + "'");
                }
            }
            return nominal;
        }
        if (expr instanceof Ast.StructInitExpr created) {
            if (created.anonymous()) {
                Map<String, Type> members = new LinkedHashMap<>();
                for (Ast.ObjectField field : created.fields()) {
                    if (members.putIfAbsent(field.name(), typeOf(field.value(), env, generics, self)) != null) {
                        throw new IllegalArgumentException("duplicate anonymous struct field '" + field.name() + "'");
                    }
                }
                return new Record(members);
            }

            Ast.ClassDecl struct = findClass(created.type().name());
            if (struct == null || !struct.isStruct()) {
                throw new IllegalArgumentException("'" + created.type().name() + "' is not a struct type");
            }

            LinkedHashMap<String, Ast.FieldDecl> callerFields = new LinkedHashMap<>();
            for (Ast.FieldDecl field : struct.fields()) {
                if (!field.composed()) callerFields.put(field.name(), field);
            }

            LinkedHashMap<String, Ast.Expr> supplied = new LinkedHashMap<>();
            for (Ast.ObjectField field : created.fields()) {
                if (!callerFields.containsKey(field.name())) {
                    throw new IllegalArgumentException("unknown or trait-owned struct field '" + struct.name() + "." + field.name() + "'");
                }
                if (supplied.putIfAbsent(field.name(), field.value()) != null) {
                    throw new IllegalArgumentException("duplicate struct field '" + struct.name() + "." + field.name() + "'");
                }
            }

            LinkedHashMap<String, Type> suppliedTypes = new LinkedHashMap<>();
            for (Map.Entry<String, Ast.Expr> entry : supplied.entrySet()) {
                suppliedTypes.put(entry.getKey(), typeOf(entry.getValue(), env, generics, self));
            }

            Type nominal;
            if (created.type().inferArguments()) {
                List<Ast.TypeRef> declaredInputs = new ArrayList<>();
                List<Type> actualInputs = new ArrayList<>();
                for (Map.Entry<String, Type> entry : suppliedTypes.entrySet()) {
                    declaredInputs.add(callerFields.get(entry.getKey()).type());
                    actualInputs.add(entry.getValue());
                }
                nominal = inferClassType(struct, declaredInputs, actualInputs, "struct initializer for " + struct.name());
            } else {
                nominal = concreteClassType(struct, created.type(), generics, self);
            }

            Map<String, Type> substitutions = classGenericSubstitutions(struct, nominal);
            for (Ast.FieldDecl field : struct.fields()) {
                if (field.composed()) {
                    if (field.initializer() == null) {
                        throw new IllegalArgumentException("composed trait state '" + struct.name() + "." + field.name() + "' requires an initializer");
                    }
                    continue;
                }
                Type value = suppliedTypes.get(field.name());
                if (value != null) {
                    requireAssignable(value,
                            instantiateClassType(field.type(), struct, substitutions, nominal),
                            "struct field " + struct.name() + "." + field.name());
                } else if (field.initializer() == null) {
                    throw new IllegalArgumentException("struct initializer for " + struct.name() + " is missing field '" + field.name() + "'");
                }
            }
            return nominal;
        }

        if (expr instanceof Ast.AwaitExpr awaited) {
            Type awaitedType = typeOf(awaited.expression(), env, generics, self);
            if (awaitedType instanceof Named named && named.name().equals("Future") && named.arguments().size() == 1) return named.arguments().getFirst();
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.ListExpr list) {
            if (list.elements().isEmpty()) return new ListType(Unknown.INSTANCE);
            Type element = typeOf(list.elements().getFirst(), env, generics, self);
            for (int i = 1; i < list.elements().size(); i++) element = commonType(element, typeOf(list.elements().get(i), env, generics, self));
            return new ListType(element);
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            return new Tuple(tuple.elements().stream().map(item -> typeOf(item, env, generics, self)).toList());
        }
        if (expr instanceof Ast.ObjectExpr object) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Ast.ObjectField field : object.fields()) {
                if (members.putIfAbsent(field.name(), typeOf(field.value(), env, generics, self)) != null) {
                    throw new IllegalArgumentException("duplicate obj field '" + field.name() + "'");
                }
            }
            return new Record(members);
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            Env lambdaEnv = new Env(env);
            List<Type> parameters = new ArrayList<>();
            for (Ast.Param param : lambda.parameters()) {
                Type type = resolveParam(param, generics, self);
                parameters.add(type);
                lambdaEnv.define(param.name(), type, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            if (lambda.expressionBody() != null) {
                throw new IllegalArgumentException("expression-body lambdas are not supported; lambdas require braces and explicit return");
            }
            checkBlock(lambda.blockBody(), lambdaEnv, generics, Unknown.INSTANCE, self);
            return new Function(parameters, Unknown.INSTANCE);
        }
        return Unknown.INSTANCE;
    }

    private void validateLambdaArgument(Ast.Expr argument, Type expected, Env env, Set<String> generics, Type self) {
        if (argument instanceof Ast.LambdaExpr lambda && expected instanceof Function fn) {
            validateLambdaAgainstExpected(lambda, fn, env, generics, self);
        }
    }

    private void validateLambdaAgainstExpected(Ast.LambdaExpr lambda, Function expected, Env parent, Set<String> generics, Type self) {
        if (lambda.expressionBody() != null) {
            throw new IllegalArgumentException("lambdas always require a block body and explicit return for non-void results");
        }
        if (lambda.parameters().size() != expected.parameters().size()) {
            throw new IllegalArgumentException("lambda arity " + lambda.parameters().size() + " does not match expected function arity " + expected.parameters().size());
        }
        Env lambdaEnv = new Env(parent);
        for (int i = 0; i < lambda.parameters().size(); i++) {
            Ast.Param param = lambda.parameters().get(i);
            Type expectedParam = expected.parameters().get(i);
            Type declared = param.type().name().equals("$infer$") ? expectedParam : resolveParam(param, generics, self);
            requireAssignable(expectedParam, declared, "lambda parameter " + param.name());
            requireAssignable(declared, expectedParam, "lambda parameter " + param.name());
            lambdaEnv.define(param.name(), declared, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        }
        checkBlock(lambda.blockBody(), lambdaEnv, generics, expected.result(), self);
        if (expected.result() != Primitive.VOID && !definitelyReturns(lambda.blockBody())) {
            throw new IllegalArgumentException("non-void lambda must explicitly return on every path");
        }
    }

    private void validateSingletonTransportStatements(List<Ast.Stmt> statements, String currentModule) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) validateSingletonTransportExpr(binding.initializer(), currentModule);
            else if (stmt instanceof Ast.DestructureStmt destructure) validateSingletonTransportExpr(destructure.initializer(), currentModule);
            else if (stmt instanceof Ast.ReturnStmt ret && ret.value() != null) validateSingletonTransportExpr(ret.value(), currentModule);
            else if (stmt instanceof Ast.ExprStmt expression) validateSingletonTransportExpr(expression.expression(), currentModule);
            else if (stmt instanceof Ast.DeferStmt defer) validateSingletonTransportExpr(defer.expression(), currentModule);
            else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    validateSingletonTransportExpr(branch.condition(), currentModule);
                    validateSingletonTransportStatements(branch.body(), currentModule);
                }
                validateSingletonTransportStatements(conditional.elseBody(), currentModule);
            } else if (stmt instanceof Ast.TryStmt attempted) {
                validateSingletonTransportStatements(attempted.body(), currentModule);
                validateSingletonTransportStatements(attempted.catchBody(), currentModule);
                validateSingletonTransportStatements(attempted.finallyBody(), currentModule);
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                validateSingletonTransportExpr(loop.iterable(), currentModule);
                validateSingletonTransportStatements(loop.body(), currentModule);
            } else if (stmt instanceof Ast.ForStmt loop) {
                if (loop.initializer() != null) validateSingletonTransportStatements(List.of(loop.initializer()), currentModule);
                if (loop.condition() != null) validateSingletonTransportExpr(loop.condition(), currentModule);
                if (loop.update() != null) validateSingletonTransportExpr(loop.update(), currentModule);
                validateSingletonTransportStatements(loop.body(), currentModule);
            }
        }
    }

    private void validateSingletonTransportExpr(Ast.Expr expr, String currentModule) {
        if (expr instanceof Ast.NameExpr name) {
            Ast.ModuleDecl module = modules.get(name.name());
            if (module != null && module.singleton() && !module.name().equals(currentModule)) {
                throw new IllegalArgumentException("singleton module handles cannot be extracted; access and await "
                        + module.name() + " members directly");
            }

            Ast.FunctionDecl function = findFunction(name.name());
            if (function != null) {
                String ownerName = functionOwners.get(function);
                Ast.ModuleDecl owner = ownerName == null ? null : modules.get(ownerName);
                if (owner != null && owner.singleton() && !owner.name().equals(currentModule)
                        && function.visibility() == Ast.Visibility.PUBLIC) {
                    throw new IllegalArgumentException("singleton service function values cannot be extracted; call and await "
                            + owner.name() + "." + function.name() + "(...) directly");
                }
            }
            return;
        }

        if (expr instanceof Ast.AwaitExpr awaited) {
            if (awaited.expression() instanceof Ast.CallExpr call && isExternalSingletonCall(call, currentModule)) {
                validateSingletonTransportCallChildren(call, currentModule);
                return;
            }
            validateSingletonTransportExpr(awaited.expression(), currentModule);
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            if (isExternalSingletonCall(call, currentModule)) {
                throw new IllegalArgumentException("cross-singleton calls must be immediately awaited so they cannot outlive"
                        + " the caller context or leave an untracked mailbox wait");
            }
            validateSingletonTransportCallChildren(call, currentModule);
            return;
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            validateSingletonTransportExpr(assignment.target(), currentModule);
            validateSingletonTransportExpr(assignment.value(), currentModule);
        } else if (expr instanceof Ast.BinaryExpr binary) {
            validateSingletonTransportExpr(binary.left(), currentModule);
            validateSingletonTransportExpr(binary.right(), currentModule);
        } else if (expr instanceof Ast.UnaryExpr unary) {
            validateSingletonTransportExpr(unary.operand(), currentModule);
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            validateSingletonTransportExpr(conditional.condition(), currentModule);
            validateSingletonTransportExpr(conditional.whenTrue(), currentModule);
            validateSingletonTransportExpr(conditional.whenFalse(), currentModule);
        } else if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.MemberExpr exported
                    && isExternalSingletonProxyFieldMember(exported, currentModule)) {
                Ast.NameExpr namespace = (Ast.NameExpr) exported.receiver();
                throw new IllegalArgumentException("singleton object fields are actor-private; invoke a public method on "
                        + namespace.name() + "." + exported.member());
            }
            if (isExternalSingletonFunctionMember(member, currentModule)) {
                throw new IllegalArgumentException("singleton service function values cannot be extracted; call and await "
                        + ((Ast.NameExpr) member.receiver()).name() + "." + member.member() + "(...) directly");
            }
            if (isExternalSingletonProxyFieldMember(member, currentModule)) {
                throw new IllegalArgumentException("singleton object proxy handles cannot be extracted; call and await a method on "
                        + ((Ast.NameExpr) member.receiver()).name() + "." + member.member() + " directly");
            }
            validateSingletonTransportExpr(member.receiver(), currentModule);
        } else if (expr instanceof Ast.IndexExpr indexed) {
            validateSingletonTransportExpr(indexed.receiver(), currentModule);
            validateSingletonTransportExpr(indexed.index(), currentModule);
        } else if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr argument : created.arguments()) validateSingletonTransportExpr(argument, currentModule);
        } else if (expr instanceof Ast.StructInitExpr created) {
            for (Ast.ObjectField field : created.fields()) validateSingletonTransportExpr(field.value(), currentModule);
        } else if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) validateSingletonTransportExpr(item, currentModule);
        } else if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) validateSingletonTransportExpr(item, currentModule);
        } else if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) validateSingletonTransportExpr(field.value(), currentModule);
        } else if (expr instanceof Ast.LambdaExpr lambda) {
            if (lambda.expressionBody() != null) validateSingletonTransportExpr(lambda.expressionBody(), currentModule);
            if (lambda.blockBody() != null) validateSingletonTransportStatements(lambda.blockBody(), currentModule);
        }
    }

    private void validateSingletonTransportCallChildren(Ast.CallExpr call, String currentModule) {
        // A recognized external singleton call is allowed only because the
        // surrounding validator proved it is immediately awaited. Do not
        // reinterpret its callee member as a first-class function extraction.
        if (!isExternalSingletonCall(call, currentModule)) {
            validateSingletonTransportExpr(call.callee(), currentModule);
        }
        for (Ast.Expr argument : call.arguments()) validateSingletonTransportExpr(argument, currentModule);
    }

    private boolean isExternalSingletonProxyFieldMember(Ast.MemberExpr member, String currentModule) {
        if (!(member.receiver() instanceof Ast.NameExpr namespace)) return false;
        Ast.ModuleDecl owner = modules.get(namespace.name());
        if (owner == null || !owner.singleton() || owner.name().equals(currentModule)) return false;
        for (Ast.Decl decl : owner.declarations()) {
            if (decl instanceof Ast.FieldDecl field
                    && field.visibility() == Ast.Visibility.PUBLIC
                    && field.name().equals(member.member())
                    && singletonProxyClass(field) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean isExternalSingletonFunctionMember(Ast.MemberExpr member, String currentModule) {
        if (!(member.receiver() instanceof Ast.NameExpr namespace)) return false;
        Ast.ModuleDecl owner = modules.get(namespace.name());
        if (owner == null || !owner.singleton() || owner.name().equals(currentModule)) return false;
        for (Ast.Decl decl : owner.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn
                    && fn.visibility() == Ast.Visibility.PUBLIC
                    && fn.name().equals(member.member())) return true;
        }
        return false;
    }

    private boolean isExternalSingletonCall(Ast.CallExpr call, String currentModule) {
        if (call.callee() instanceof Ast.NameExpr name) {
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn == null) return false;
            String ownerName = functionOwners.get(fn);
            Ast.ModuleDecl owner = ownerName == null ? null : modules.get(ownerName);
            return owner != null && owner.singleton() && !owner.name().equals(currentModule);
        }
        if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace) {
            Ast.ModuleDecl owner = modules.get(namespace.name());
            if (owner == null || !owner.singleton() || owner.name().equals(currentModule)) return false;
            for (Ast.Decl decl : owner.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn
                        && fn.visibility() == Ast.Visibility.PUBLIC
                        && fn.name().equals(member.member())) return true;
            }
        }
        if (call.callee() instanceof Ast.MemberExpr method
                && method.receiver() instanceof Ast.MemberExpr exported
                && exported.receiver() instanceof Ast.NameExpr namespace) {
            Ast.ModuleDecl owner = modules.get(namespace.name());
            if (owner == null || !owner.singleton() || owner.name().equals(currentModule)) return false;
            for (Ast.Decl decl : owner.declarations()) {
                if (decl instanceof Ast.FieldDecl field
                        && field.visibility() == Ast.Visibility.PUBLIC
                        && field.name().equals(exported.member())
                        && singletonProxyClass(field) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private Type deref(Type type) {
        return type instanceof Borrow borrow ? borrow.target() : type;
    }

    private Type memberType(Ast.MemberExpr member, Env env, Set<String> generics, Type self) {
        Type receiver = deref(typeOf(member.receiver(), env, generics, self));
        if (receiver instanceof Record record) {
            Type result = record.members().get(member.member());
            if (result == null) throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
            return result;
        }
        if (receiver instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                Ast.FieldDecl declaration = findFieldDecl(klass, member.member(), new LinkedHashSet<>());
                if (declaration != null) {
                    if (declaration.bindingKind() != Ast.BindingKind.LET) {
                        throw new IllegalArgumentException("field '" + klass.name() + "." + member.member()
                                + "' is immutable (" + declaration.bindingKind().name().toLowerCase()
                                + ") and cannot be assigned; declare the field with let to permit mutation");
                    }
                    Type field = findFieldType(klass, member.member(), named, new LinkedHashSet<>());
                    if (field != null) return field;
                }
            }
        }
        throw new IllegalArgumentException("assignment target '" + member.member() + "' is not a mutable data field");
    }

    private Type iterableElementType(Type iterable) {
        if (iterable instanceof ListType list) return list.element();
        if (iterable instanceof Tuple tuple) {
            Type result = Unknown.INSTANCE;
            for (Type element : tuple.elements()) result = result == Unknown.INSTANCE ? element : commonType(result, element);
            return result;
        }
        if (iterable instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                Ast.MethodDecl iterator = findMethod(klass, "Symbol.iterator", 0, new LinkedHashSet<>());
                if (iterator != null) {
                    Type result = methodFunctionTypeForReceiver(iterator, klass, named).result();
                    if (result instanceof ListType list) return list.element();
                    if (result instanceof Tuple tuple) return iterableElementType(tuple);
                    throw new IllegalArgumentException("[Symbol.iterator]() must return Array<T>, List<T>, or a tuple in v0");
                }
            }
        }
        throw new IllegalArgumentException("for-of requires an array/list, tuple, or a class with [Symbol.iterator]()");
    }

    private Type commonType(Type a, Type b) {
        if (assignable(b, a) && assignable(a, b)) return a;
        if (Types.isNumeric(a) && Types.isNumeric(b)) return Types.numericJoin(a, b);
        if (isStringLike(a) && isStringLike(b)) return Primitive.STRING;
        return Unknown.INSTANCE;
    }

    private Type numericJoin(Type left, Type right, String op) {
        Type result = Types.numericJoin(left, right);
        if (result == Unknown.INSTANCE) throw new IllegalArgumentException("operator '" + op + "' needs numeric operands");
        return result;
    }

    private boolean isStringLike(Type type) {
        return type == Primitive.STRING || type instanceof StringLiteral;
    }

    private Record classShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        Record cached = classShapeCache.get(klass);
        if (cached != null) return cached;
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        Set<String> generics = Set.copyOf(klass.genericParameters());
        Type self = nominalClassType(klass);
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                Record inheritedShape = instantiateParentShape(classShape(parent, stack), parent, parentRef, generics, self);
                for (Map.Entry<String, Type> inherited : inheritedShape.members().entrySet()) {
                    mergeMember(members, inherited.getKey(), inherited.getValue(), "multiple inheritance of " + klass.name());
                }
            }
        }
        for (Ast.FieldDecl field : klass.fields()) mergeMember(members, field.name(), resolve(field.type(), generics, self), "class " + klass.name());
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic()) continue;
            mergeMember(members, methodKey(method.name(), method.arity()),
                    methodFunctionType(method, klass, self), "class " + klass.name());
        }
        stack.remove(klass);
        Record result = new Record(members);
        classShapeCache.put(klass, result);
        return result;
    }

    private Record publicClassShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        Set<String> generics = Set.copyOf(klass.genericParameters());
        Type self = nominalClassType(klass);
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                Record inheritedShape = instantiateParentShape(publicClassShape(parent, stack), parent, parentRef, generics, self);
                for (Map.Entry<String, Type> inherited : inheritedShape.members().entrySet()) {
                    mergeMember(members, inherited.getKey(), inherited.getValue(), "public inheritance of " + klass.name());
                }
            }
        }
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.visibility() == Ast.Visibility.PUBLIC) mergeMember(members, field.name(), resolve(field.type(), generics, self), "class " + klass.name());
        }
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.visibility() == Ast.Visibility.PUBLIC) {
                mergeMember(members, methodKey(method.name(), method.arity()),
                        methodFunctionType(method, klass, self), "class " + klass.name());
            }
        }
        stack.remove(klass);
        return new Record(members);
    }

    private Record instantiateParentShape(
            Record raw,
            Ast.ClassDecl parent,
            Ast.TypeRef parentRef,
            Set<String> childGenerics,
            Type childSelf) {
        if (parent.genericParameters().isEmpty()) return raw;
        Type resolvedParent = resolve(parentRef, childGenerics, childSelf);
        if (!(resolvedParent instanceof Named named)
                || named.arguments().size() != parent.genericParameters().size()) {
            throw new IllegalArgumentException("invalid generic parent instantiation for " + parent.name());
        }
        return (Record) substituteGenerics(raw, classGenericSubstitutions(parent, named));
    }

    private Record publicClassShapeForType(Ast.ClassDecl klass, Type receiverType) {
        Record raw = publicClassShape(klass, new LinkedHashSet<>());
        if (klass.genericParameters().isEmpty()) return raw;
        if (!(receiverType instanceof Named named)
                || named.arguments().size() != klass.genericParameters().size()) {
            throw new IllegalArgumentException("cannot instantiate public shape for generic " + klass.name()
                    + " from " + receiverType);
        }
        return (Record) substituteGenerics(raw, classGenericSubstitutions(klass, named));
    }

    private Record interfaceShapeForUse(
            Ast.InterfaceDecl iface,
            Ast.TypeRef use,
            Set<String> surroundingGenerics,
            Type self) {
        if (use.inferArguments() || use.arguments().size() != iface.genericParameters().size()) {
            throw new IllegalArgumentException("interface '" + iface.name() + "' expects "
                    + iface.genericParameters().size() + " type argument(s), got " + use.arguments().size());
        }
        Record raw = interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
        if (iface.genericParameters().isEmpty()) return raw;

        LinkedHashMap<String, Type> substitutions = new LinkedHashMap<>();
        for (int i = 0; i < iface.genericParameters().size(); i++) {
            substitutions.put(iface.genericParameters().get(i),
                    resolve(use.arguments().get(i), surroundingGenerics, self));
        }
        return (Record) substituteGenerics(raw, substitutions);
    }

    private Record interfaceShapeForNamed(Ast.InterfaceDecl iface, Named use) {
        if (use.arguments().size() != iface.genericParameters().size()) {
            throw new IllegalArgumentException("interface '" + iface.name() + "' expects "
                    + iface.genericParameters().size() + " type argument(s), got " + use.arguments().size());
        }
        Record raw = interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
        if (iface.genericParameters().isEmpty()) return raw;

        LinkedHashMap<String, Type> substitutions = new LinkedHashMap<>();
        for (int i = 0; i < iface.genericParameters().size(); i++) {
            substitutions.put(iface.genericParameters().get(i), use.arguments().get(i));
        }
        return (Record) substituteGenerics(raw, substitutions);
    }

    private Record interfaceShape(Ast.InterfaceDecl iface, Set<String> generics, Set<Ast.InterfaceDecl> stack) {
        if (!stack.add(iface)) throw new IllegalArgumentException("interface inheritance cycle involving '" + iface.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent == null) throw new IllegalArgumentException("unknown parent interface '" + parentRef.name() + "' for " + iface.name());
            if (parentRef.inferArguments() || parentRef.arguments().size() != parent.genericParameters().size()) {
                throw new IllegalArgumentException("interface '" + parent.name() + "' expects "
                        + parent.genericParameters().size() + " type argument(s), got " + parentRef.arguments().size());
            }
            Record inheritedShape = interfaceShape(parent, Set.copyOf(parent.genericParameters()), stack);
            if (!parent.genericParameters().isEmpty()) {
                LinkedHashMap<String, Type> substitutions = new LinkedHashMap<>();
                for (int i = 0; i < parent.genericParameters().size(); i++) {
                    substitutions.put(parent.genericParameters().get(i), resolve(parentRef.arguments().get(i), generics, null));
                }
                inheritedShape = (Record) substituteGenerics(inheritedShape, substitutions);
            }
            for (Map.Entry<String, Type> inherited : inheritedShape.members().entrySet()) {
                mergeMember(members, inherited.getKey(), inherited.getValue(), "interface inheritance of " + iface.name());
            }
        }
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                Set<String> all = new HashSet<>(generics);
                all.addAll(fn.genericParameters());
                mergeMember(members, methodKey(fn.name(), fn.parameters().size()),
                        functionType(fn.parameters(), fn.returnType(), all, null), "interface " + iface.name());
            }
        }
        stack.remove(iface);
        return new Record(members);
    }

    private List<Ast.FieldDecl> effectiveFields(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        LinkedHashMap<String, Ast.FieldDecl> fields = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) for (Ast.FieldDecl field : effectiveFields(parent, stack)) fields.putIfAbsent(field.name(), field);
        }
        for (Ast.FieldDecl field : klass.fields()) fields.put(field.name(), field);
        stack.remove(klass);
        return List.copyOf(fields.values());
    }

    private Ast.FieldDecl findFieldDecl(
            Ast.ClassDecl klass,
            String name,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                if (activeTraitOwner != null) {
                    if (!field.composed() || !activeTraitOwner.equals(field.compositionOwner())) {
                        throw new IllegalArgumentException(
                                "trait '" + activeTraitOwner + "' cannot access host or foreign trait state field '" + name + "'");
                    }
                } else if (field.composed() && field.visibility() != Ast.Visibility.PUBLIC) {
                    throw new IllegalArgumentException(
                            "trait state field '" + field.compositionOwner() + "." + name
                                    + "' is private to that trait");
                } else if (field.visibility() == Ast.Visibility.PRIVATE && activeClassOwner != klass) {
                    throw new IllegalArgumentException(
                            "field '" + klass.name() + "." + name + "' is private to " + klass.name());
                }
                seen.remove(klass);
                return field;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.FieldDecl found = findFieldDecl(parent, name, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Type findFieldType(
            Ast.ClassDecl klass,
            String name,
            Type receiverType,
            Set<Ast.ClassDecl> seen) {
        return findFieldType(klass, name, receiverType, seen, true);
    }

    private Type findFieldType(
            Ast.ClassDecl klass,
            String name,
            Type receiverType,
            Set<Ast.ClassDecl> seen,
            boolean enforceAccess) {
        if (!seen.add(klass)) return null;
        Type self = receiverType == null ? nominalClassType(klass) : receiverType;
        Map<String, Type> substitutions = self instanceof Named named
                && named.arguments().size() == klass.genericParameters().size()
                ? classGenericSubstitutions(klass, named)
                : Map.of();

        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                if (enforceAccess) {
                    if (activeTraitOwner != null) {
                        if (!field.composed() || !activeTraitOwner.equals(field.compositionOwner())) {
                            throw new IllegalArgumentException(
                                    "trait '" + activeTraitOwner + "' cannot access host or foreign trait state field '" + name + "'");
                        }
                    } else if (field.composed() && field.visibility() != Ast.Visibility.PUBLIC) {
                        throw new IllegalArgumentException(
                                "trait state field '" + field.compositionOwner() + "." + name
                                        + "' is private to that trait");
                    } else if (field.visibility() == Ast.Visibility.PRIVATE && activeClassOwner != klass) {
                        throw new IllegalArgumentException(
                                "field '" + klass.name() + "." + name + "' is private to " + klass.name());
                    }
                }
                seen.remove(klass);
                Type unresolved = resolve(field.type(), Set.copyOf(klass.genericParameters()), self);
                return substitutions.isEmpty() ? unresolved : substituteGenerics(unresolved, substitutions);
            }
        }

        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;

            Type parentType = resolve(parentRef, Set.copyOf(klass.genericParameters()), self);
            if (!substitutions.isEmpty()) parentType = substituteGenerics(parentType, substitutions);
            Type result = findFieldType(parent, name, parentType, seen, enforceAccess);
            if (result != null) {
                seen.remove(klass);
                return result;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.MethodDecl findMethod(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name) && method.arity() == arity) {
                if (!methodAccessible(klass, method)) {
                    seen.remove(klass);
                    throw inaccessibleMethod(klass, method);
                }
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.MethodDecl candidate = findMethod(parent, name, arity, seen);
            if (candidate != null) {
                seen.remove(klass);
                return candidate;
            }
        }
        seen.remove(klass);
        return null;
    }

    private List<Ast.MethodDecl> findMethodsByName(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<Integer, Ast.MethodDecl> methods = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name) && methodAccessible(klass, method)) {
                methods.put(method.arity(), method);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl method : findMethodsByName(parent, name, seen)) methods.putIfAbsent(method.arity(), method);
        }
        seen.remove(klass);
        return List.copyOf(methods.values());
    }

    private Ast.MethodDecl findStaticFunction(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() && method.name().equals(name) && method.arity() == arity) {
                if (!methodAccessible(klass, method)) {
                    seen.remove(klass);
                    throw inaccessibleMethod(klass, method);
                }
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.MethodDecl candidate = findStaticFunction(parent, name, arity, seen);
            if (candidate != null) {
                seen.remove(klass);
                return candidate;
            }
        }
        seen.remove(klass);
        return null;
    }

    private List<Ast.MethodDecl> findStaticFunctionsByName(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<Integer, Ast.MethodDecl> functions = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() && method.name().equals(name) && methodAccessible(klass, method)) {
                functions.put(method.arity(), method);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl fn : findStaticFunctionsByName(parent, name, seen)) functions.putIfAbsent(fn.arity(), fn);
        }
        seen.remove(klass);
        return List.copyOf(functions.values());
    }

    private void requireTraitMethodAccessible(Ast.MethodDecl method, String where) {
        if (!method.composed() || method.visibility() == Ast.Visibility.PUBLIC) return;
        if (activeTraitOwner != null && activeTraitOwner.equals(method.compositionOwner())) return;
        throw new IllegalArgumentException(
                "trait-private method '" + method.compositionOwner() + "." + method.name()
                        + "' is not accessible from " + where);
    }

    private boolean methodAccessible(Ast.ClassDecl owner, Ast.MethodDecl method) {
        if (method.visibility() == Ast.Visibility.PUBLIC) return true;
        if (method.composed()) {
            return activeTraitOwner != null && activeTraitOwner.equals(method.compositionOwner());
        }
        return activeClassOwner == owner;
    }

    private IllegalArgumentException inaccessibleMethod(Ast.ClassDecl owner, Ast.MethodDecl method) {
        if (method.composed()) {
            return new IllegalArgumentException(
                    "trait-private method '" + method.compositionOwner() + "." + method.name()
                            + "' is private to that trait");
        }
        String label = method.isStatic() ? "static function" : "method";
        return new IllegalArgumentException(
                label + " '" + owner.name() + "." + method.name() + "' is private to " + owner.name());
    }

    private Ast.ClassDecl resolveClassParent(Ast.TypeRef parentRef, Ast.ClassDecl child) {
        if (parentRef.name().equals("Object") || parentRef.name().equals("List")) return null;
        if (parentRef.name().equals("obj") || parentRef.name().equals("arr")) throw new IllegalArgumentException("inline obj/arr values cannot be subclassed; extend Object or List instead");
        Ast.ClassDecl parent = findClass(parentRef.name());
        if (parent == null) throw new IllegalArgumentException("unknown parent class '" + parentRef.name() + "' for " + child.name());
        if (parent.isStruct()) throw new IllegalArgumentException("struct '" + parent.name() + "' cannot be used as a base class");
        if (parent == child) throw new IllegalArgumentException("class '" + child.name() + "' cannot extend itself");

        String parentOwnerName = classOwners.get(parent);
        Ast.ModuleDecl parentOwner = parentOwnerName == null ? null : modules.get(parentOwnerName);
        String childOwnerName = classOwners.get(child);
        if (parentOwner != null && parentOwner.singleton()
                && !java.util.Objects.equals(parentOwnerName, childOwnerName)) {
            throw new IllegalArgumentException("class '" + parent.name()
                    + "' is actor-private inside singleton module '" + parentOwner.name()
                    + "' and cannot be inherited outside that module");
        }
        return parent;
    }

    private void mergeMember(Map<String, Type> members, String name, Type type, String owner) {
        Type existing = members.get(name);
        if (existing != null && (!assignable(existing, type) || !assignable(type, existing))) {
            throw new IllegalArgumentException("conflicting member '" + name + "' in " + owner + ": " + existing + " vs " + type);
        }
        members.put(name, type);
    }

    private Function functionType(List<Ast.Param> params, Ast.TypeRef returns, Set<String> generics, Type self) {
        return new Function(params.stream().map(p -> resolveParam(p, generics, self)).toList(), resolve(returns, generics, self));
    }

    private Function instantiateCallableGenerics(
            Function raw,
            List<String> genericParameters,
            List<Type> actualArguments,
            String where) {
        if (raw.parameters().size() != actualArguments.size()) {
            throw new IllegalArgumentException(where + " arity mismatch: expected "
                    + raw.parameters().size() + " argument(s), got " + actualArguments.size());
        }
        if (genericParameters.isEmpty()) return raw;

        LinkedHashSet<String> inferable = new LinkedHashSet<>(genericParameters);
        LinkedHashMap<String, Type> inferred = new LinkedHashMap<>();
        for (int i = 0; i < raw.parameters().size(); i++) {
            inferGenericBindingsRestricted(
                    raw.parameters().get(i),
                    actualArguments.get(i),
                    inferable,
                    inferred,
                    where);
        }

        for (String generic : genericParameters) {
            Type type = inferred.get(generic);
            if (type == null || type == Unknown.INSTANCE) {
                throw new IllegalArgumentException(where + " cannot infer generic '" + generic
                        + "' from call arguments");
            }
        }
        return (Function) substituteGenerics(raw, inferred);
    }

    private void inferGenericBindingsRestricted(
            Type pattern,
            Type actual,
            Set<String> inferable,
            Map<String, Type> inferred,
            String where) {
        actual = widenInferenceType(actual);
        if (actual == Unknown.INSTANCE) return;

        if (pattern instanceof Generic generic) {
            if (!inferable.contains(generic.name())) return;
            Type previous = inferred.get(generic.name());
            if (previous == null) {
                inferred.put(generic.name(), actual);
                return;
            }
            Type merged = commonType(previous, actual);
            if (merged == Unknown.INSTANCE && !previous.equals(actual)) {
                throw new IllegalArgumentException(where + " infers incompatible types for generic '"
                        + generic.name() + "': " + previous + " and " + actual);
            }
            inferred.put(generic.name(), merged == Unknown.INSTANCE ? actual : merged);
            return;
        }

        if (pattern instanceof Borrow p && actual instanceof Borrow a) {
            if (p.mutable() == a.mutable()) {
                inferGenericBindingsRestricted(p.target(), a.target(), inferable, inferred, where);
            }
            return;
        }
        if (pattern instanceof ListType p && actual instanceof ListType a) {
            inferGenericBindingsRestricted(p.element(), a.element(), inferable, inferred, where);
            return;
        }
        if (pattern instanceof Tuple p && actual instanceof Tuple a
                && p.elements().size() == a.elements().size()) {
            for (int i = 0; i < p.elements().size(); i++) {
                inferGenericBindingsRestricted(p.elements().get(i), a.elements().get(i), inferable, inferred, where);
            }
            return;
        }
        if (pattern instanceof Named p && actual instanceof Named a
                && p.name().equals(a.name())
                && p.arguments().size() == a.arguments().size()) {
            for (int i = 0; i < p.arguments().size(); i++) {
                inferGenericBindingsRestricted(p.arguments().get(i), a.arguments().get(i), inferable, inferred, where);
            }
            return;
        }
        if (pattern instanceof Function p && actual instanceof Function a
                && p.parameters().size() == a.parameters().size()) {
            for (int i = 0; i < p.parameters().size(); i++) {
                inferGenericBindingsRestricted(p.parameters().get(i), a.parameters().get(i), inferable, inferred, where);
            }
            inferGenericBindingsRestricted(p.result(), a.result(), inferable, inferred, where);
            return;
        }
        if (pattern instanceof Record p && actual instanceof Record a) {
            for (Map.Entry<String, Type> entry : p.members().entrySet()) {
                Type actualMember = a.members().get(entry.getKey());
                if (actualMember != null) {
                    inferGenericBindingsRestricted(entry.getValue(), actualMember, inferable, inferred, where);
                }
            }
        }
    }

    private LinkedHashSet<String> collectGenericNames(Type type) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectGenericNames(type, result);
        return result;
    }

    private void collectGenericNames(Type type, Set<String> result) {
        if (type instanceof Generic generic) {
            result.add(generic.name());
        } else if (type instanceof Borrow borrow) {
            collectGenericNames(borrow.target(), result);
        } else if (type instanceof ListType list) {
            collectGenericNames(list.element(), result);
        } else if (type instanceof Tuple tuple) {
            for (Type element : tuple.elements()) collectGenericNames(element, result);
        } else if (type instanceof Named named) {
            for (Type argument : named.arguments()) collectGenericNames(argument, result);
        } else if (type instanceof Function function) {
            for (Type parameter : function.parameters()) collectGenericNames(parameter, result);
            collectGenericNames(function.result(), result);
        } else if (type instanceof Record record) {
            for (Type member : record.members().values()) collectGenericNames(member, result);
        }
    }

    private void validateCallArguments(
            List<Ast.Expr> expressions,
            List<Type> actualTypes,
            Function signature,
            Env env,
            Set<String> generics,
            Type self,
            String label) {
        if (signature.parameters().size() != actualTypes.size()) {
            throw new IllegalArgumentException("call arity mismatch");
        }
        for (int i = 0; i < actualTypes.size(); i++) {
            Type expected = signature.parameters().get(i);
            validateLambdaArgument(expressions.get(i), expected, env, generics, self);
            requireAssignable(actualTypes.get(i), expected, label + " " + (i + 1));
        }
    }

    private record MethodOwner(Ast.ClassDecl klass, Type receiverType) { }

    private Function methodFunctionTypeForReceiver(
            Ast.MethodDecl method,
            Ast.ClassDecl receiverClass,
            Type receiverType) {
        MethodOwner owner = findMethodOwner(
                receiverClass,
                method,
                receiverType,
                new LinkedHashSet<>());
        if (owner == null) {
            throw new IllegalArgumentException(
                    "internal error: cannot determine declaring aggregate for method '"
                            + method.name() + "' on " + receiverClass.name());
        }
        return methodFunctionType(method, owner.klass(), owner.receiverType());
    }

    private MethodOwner findMethodOwner(
            Ast.ClassDecl current,
            Ast.MethodDecl target,
            Type currentType,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(current)) return null;

        for (Ast.MethodDecl candidate : current.methods()) {
            if (candidate == target) {
                seen.remove(current);
                return new MethodOwner(current, currentType);
            }
        }

        Type currentSelf = currentType == null ? nominalClassType(current) : currentType;
        Map<String, Type> substitutions = currentSelf instanceof Named named
                && named.arguments().size() == current.genericParameters().size()
                ? classGenericSubstitutions(current, named)
                : Map.of();

        for (Ast.TypeRef parentRef : current.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, current);
            if (parent == null) continue;

            Type parentType = resolve(
                    parentRef,
                    Set.copyOf(current.genericParameters()),
                    nominalClassType(current));
            if (!substitutions.isEmpty()) {
                parentType = substituteGenerics(parentType, substitutions);
            }

            MethodOwner found = findMethodOwner(parent, target, parentType, seen);
            if (found != null) {
                seen.remove(current);
                return found;
            }
        }

        seen.remove(current);
        return null;
    }

    private Function methodFunctionType(Ast.MethodDecl method, Ast.ClassDecl klass, Type self) {
        pushLocalTypeScope(method.body());
        try {
            Set<String> generics = new HashSet<>(klass.genericParameters());
            generics.addAll(method.genericParameters());
            rejectLocalTypeGenericCollisions(method.body(), generics);
            Function signature = functionType(method.parameters(), method.returnType(), generics, self);
            if (self instanceof Named named && named.arguments().size() == klass.genericParameters().size()) {
                signature = (Function) substituteGenerics(signature, classGenericSubstitutions(klass, named));
            }
            return signature;
        } finally {
            popLocalTypeScope();
        }
    }

    private Type resolveParam(Ast.Param param, Set<String> generics, Type self) {
        if (!param.structural()) return resolve(param.type(), generics, self);
        Ast.InterfaceDecl iface = findInterface(param.type().name());
        if (iface != null) return interfaceShapeForUse(iface, param.type(), generics, self);
        Ast.ClassDecl klass = findClass(param.type().name());
        if (klass != null) {
            Type concrete = resolve(param.type(), generics, self);
            return publicClassShapeForType(klass, concrete);
        }
        throw new IllegalArgumentException("@Structural requires a known class or interface type, got '" + param.type().name() + "'");
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self) {
        return resolve(ref, generics, self, false);
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self, boolean allowNullMarker) {
        if (ref == null) return Unknown.INSTANCE;
        if (ref.isBorrow()) return new Borrow(resolve(ref.borrowedTarget(), generics, self, allowNullMarker), ref.mutableBorrow());
        if (ref.isStringLiteral()) return new StringLiteral(ref.stringLiteralValue());
        if (ref.name().equals("$infer$")) return Unknown.INSTANCE;
        if (ref.name().equals("self")) return self == null ? Unknown.INSTANCE : self;
        if (ref.name().equals("null")) {
            if (!allowNullMarker) throw new IllegalArgumentException("null is not a standalone type; it is only legal as Option<null>");
            return Primitive.NULL;
        }
        if (generics.contains(ref.name())) return new Generic(ref.name());

        Ast.ClassDecl lexicalClass = findLexicalClass(ref.name());
        if (lexicalClass != null) return nominalLocalType(lexicalClass, ref, generics, self);

        Ast.InterfaceDecl lexicalInterface = findLexicalInterface(ref.name());
        if (lexicalInterface != null) {
            return interfaceShapeForUse(lexicalInterface, ref, generics, self);
        }

        Ast.TypeAliasDecl alias = findTypeAlias(ref.name());
        if (alias != null) {
            if (ref.inferArguments()) {
                throw new IllegalArgumentException("type alias '" + alias.name()
                        + "' requires explicit type arguments; diamond inference is construction-only");
            }
            if (alias.genericParameters().size() != ref.arguments().size()) {
                throw new IllegalArgumentException("type alias '" + alias.name() + "' expects " + alias.genericParameters().size()
                        + " type argument(s), got " + ref.arguments().size());
            }
            if (!resolvingAliases.add(alias)) throw new IllegalArgumentException("type alias cycle involving '" + alias.name() + "'");
            try {
                Map<String, Ast.TypeRef> substitutions = new HashMap<>();
                for (int i = 0; i < alias.genericParameters().size(); i++) {
                    substitutions.put(alias.genericParameters().get(i), ref.arguments().get(i));
                }
                return resolve(substituteAliasType(alias.target(), substitutions), generics, self, allowNullMarker);
            } finally {
                resolvingAliases.remove(alias);
            }
        }

        Ast.ClassDecl namedClass = findClass(ref.name());
        if (namedClass != null) {
            if (ref.inferArguments()) {
                throw new IllegalArgumentException((namedClass.isStruct() ? "struct '" : "class '") + namedClass.name()
                        + "' requires explicit type arguments in type positions; use <> only at construction");
            }
            if (ref.arguments().size() != namedClass.genericParameters().size()) {
                throw new IllegalArgumentException((namedClass.isStruct() ? "struct '" : "class '") + namedClass.name()
                        + "' expects " + namedClass.genericParameters().size() + " type argument(s), got "
                        + ref.arguments().size());
            }
            return new Named(qualifiedClassName(namedClass),
                    ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
        }

        Ast.InterfaceDecl namedInterface = findInterface(ref.name());
        if (namedInterface != null) {
            if (ref.inferArguments()) {
                throw new IllegalArgumentException("interface '" + namedInterface.name()
                        + "' requires explicit type arguments in type positions");
            }
            if (ref.arguments().size() != namedInterface.genericParameters().size()) {
                throw new IllegalArgumentException("interface '" + namedInterface.name() + "' expects "
                        + namedInterface.genericParameters().size() + " type argument(s), got "
                        + ref.arguments().size());
            }
            return new Named(qualifiedInterfaceName(namedInterface),
                    ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
        }

        if (ref.inferArguments()) {
            throw new IllegalArgumentException("diamond inference '<>' is only valid for a known class/struct construction");
        }

        return switch (ref.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "int", "uint", "bigint" -> Primitive.INT;
            case "f32", "f64", "float" -> Primitive.FLOAT;
            case "decimal" -> Primitive.DECIMAL;
            case "complex64", "complex128", "complex" -> Primitive.COMPLEX;
            case "bool", "Bool" -> Primitive.BOOL;
            case "string", "String" -> Primitive.STRING;
            case "void" -> Primitive.VOID;
            case "Array", "List" -> {
                if (ref.arguments().size() != 1) throw new IllegalArgumentException(ref.name() + " requires exactly one explicit type argument");
                yield new ListType(resolve(ref.arguments().getFirst(), generics, self));
            }
            case "Option" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Option requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self, true);
                if (element == Primitive.VOID) throw new IllegalArgumentException("Option<void> is invalid; use void for no return value");
                yield new Named("Option", List.of(element));
            }
            case "Fnc" -> {
                List<Type> args = ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList();
                if (args.isEmpty()) throw new IllegalArgumentException("Fnc requires at least a result type");
                yield new Function(args.subList(0, args.size() - 1), args.getLast());
            }
            default -> new Named(ref.name(), ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
        };
    }

    private Ast.TypeRef substituteAliasType(Ast.TypeRef ref, Map<String, Ast.TypeRef> substitutions) {
        Ast.TypeRef replacement = substitutions.get(ref.name());
        if (replacement != null && ref.arguments().isEmpty() && !ref.inferArguments()) return replacement;
        return new Ast.TypeRef(
                ref.name(),
                ref.arguments().stream().map(arg -> substituteAliasType(arg, substitutions)).toList(),
                ref.inferArguments());
    }

    private Type nominalClassType(Ast.ClassDecl klass) {
        return new Named(qualifiedClassName(klass), klass.genericParameters().stream().map(Generic::new).map(Type.class::cast).toList());
    }

    private Type concreteClassType(
            Ast.ClassDecl klass,
            Ast.TypeRef use,
            Set<String> surroundingGenerics,
            Type self) {
        if (use.inferArguments()) {
            throw new IllegalArgumentException("internal error: inferred aggregate type must be resolved from constructor inputs");
        }
        if (use.arguments().size() != klass.genericParameters().size()) {
            throw new IllegalArgumentException((klass.isStruct() ? "struct '" : "class '") + klass.name()
                    + "' expects " + klass.genericParameters().size() + " type argument(s), got "
                    + use.arguments().size() + "; use <> for inference");
        }
        List<Type> arguments = use.arguments().stream()
                .map(arg -> resolve(arg, surroundingGenerics, self))
                .toList();
        return new Named(qualifiedClassName(klass), arguments);
    }

    private Type inferClassType(
            Ast.ClassDecl klass,
            List<Ast.TypeRef> declaredInputs,
            List<Type> actualInputs,
            String where) {
        if (declaredInputs.size() != actualInputs.size()) {
            throw new IllegalArgumentException("internal generic inference arity mismatch in " + where);
        }
        if (klass.genericParameters().isEmpty()) {
            throw new IllegalArgumentException((klass.isStruct() ? "struct '" : "class '") + klass.name()
                    + "' is not generic; omit <>");
        }

        LinkedHashMap<String, Type> inferred = new LinkedHashMap<>();
        Set<String> genericNames = Set.copyOf(klass.genericParameters());
        Type genericSelf = nominalClassType(klass);
        for (int i = 0; i < declaredInputs.size(); i++) {
            Type pattern = resolve(declaredInputs.get(i), genericNames, genericSelf);
            inferGenericBindings(pattern, actualInputs.get(i), inferred, where);
        }

        List<Type> arguments = new ArrayList<>(klass.genericParameters().size());
        for (String generic : klass.genericParameters()) {
            Type inferredType = inferred.get(generic);
            if (inferredType == null || inferredType == Unknown.INSTANCE) {
                throw new IllegalArgumentException(where + " cannot infer generic '" + generic
                        + "'; provide explicit type arguments");
            }
            arguments.add(inferredType);
        }
        return new Named(qualifiedClassName(klass), arguments);
    }

    private Type inferClassTypeFromPatterns(
            Ast.ClassDecl klass,
            List<Type> declaredInputs,
            List<Type> actualInputs,
            String where) {
        if (declaredInputs.size() != actualInputs.size()) {
            throw new IllegalArgumentException("internal generic inference arity mismatch in " + where);
        }
        if (klass.genericParameters().isEmpty()) {
            throw new IllegalArgumentException((klass.isStruct() ? "struct '" : "class '") + klass.name()
                    + "' is not generic; omit <>");
        }

        LinkedHashMap<String, Type> inferred = new LinkedHashMap<>();
        for (int i = 0; i < declaredInputs.size(); i++) {
            inferGenericBindings(declaredInputs.get(i), actualInputs.get(i), inferred, where);
        }

        List<Type> arguments = new ArrayList<>(klass.genericParameters().size());
        for (String generic : klass.genericParameters()) {
            Type inferredType = inferred.get(generic);
            if (inferredType == null || inferredType == Unknown.INSTANCE) {
                throw new IllegalArgumentException(where + " cannot infer generic '" + generic
                        + "'; provide explicit type arguments");
            }
            arguments.add(inferredType);
        }
        return new Named(qualifiedClassName(klass), arguments);
    }

    private void inferGenericBindings(
            Type pattern,
            Type actual,
            Map<String, Type> inferred,
            String where) {
        actual = widenInferenceType(actual);
        if (actual == Unknown.INSTANCE) return;

        if (pattern instanceof Generic generic) {
            Type previous = inferred.get(generic.name());
            if (previous == null) {
                inferred.put(generic.name(), actual);
                return;
            }
            Type merged = commonType(previous, actual);
            if (merged == Unknown.INSTANCE && !previous.equals(actual)) {
                throw new IllegalArgumentException(where + " infers incompatible types for generic '"
                        + generic.name() + "': " + previous + " and " + actual);
            }
            inferred.put(generic.name(), merged == Unknown.INSTANCE ? actual : merged);
            return;
        }

        if (pattern instanceof Borrow expectedBorrow && actual instanceof Borrow actualBorrow) {
            if (expectedBorrow.mutable() != actualBorrow.mutable()) return;
            inferGenericBindings(expectedBorrow.target(), actualBorrow.target(), inferred, where);
            return;
        }
        if (pattern instanceof ListType expectedList && actual instanceof ListType actualList) {
            inferGenericBindings(expectedList.element(), actualList.element(), inferred, where);
            return;
        }
        if (pattern instanceof Tuple expectedTuple && actual instanceof Tuple actualTuple
                && expectedTuple.elements().size() == actualTuple.elements().size()) {
            for (int i = 0; i < expectedTuple.elements().size(); i++) {
                inferGenericBindings(expectedTuple.elements().get(i), actualTuple.elements().get(i), inferred, where);
            }
            return;
        }
        if (pattern instanceof Named expectedNamed && actual instanceof Named actualNamed
                && expectedNamed.name().equals(actualNamed.name())
                && expectedNamed.arguments().size() == actualNamed.arguments().size()) {
            for (int i = 0; i < expectedNamed.arguments().size(); i++) {
                inferGenericBindings(expectedNamed.arguments().get(i), actualNamed.arguments().get(i), inferred, where);
            }
            return;
        }
        if (pattern instanceof Function expectedFunction && actual instanceof Function actualFunction
                && expectedFunction.parameters().size() == actualFunction.parameters().size()) {
            for (int i = 0; i < expectedFunction.parameters().size(); i++) {
                inferGenericBindings(expectedFunction.parameters().get(i), actualFunction.parameters().get(i), inferred, where);
            }
            inferGenericBindings(expectedFunction.result(), actualFunction.result(), inferred, where);
        }
    }

    private Type widenInferenceType(Type type) {
        if (type instanceof StringLiteral) return Primitive.STRING;
        return type;
    }

    private Map<String, Type> classGenericSubstitutions(Ast.ClassDecl klass, Type nominal) {
        if (!(nominal instanceof Named named)) return Map.of();
        if (named.arguments().size() != klass.genericParameters().size()) {
            throw new IllegalArgumentException("internal generic arity mismatch for " + klass.name());
        }
        LinkedHashMap<String, Type> substitutions = new LinkedHashMap<>();
        for (int i = 0; i < klass.genericParameters().size(); i++) {
            substitutions.put(klass.genericParameters().get(i), named.arguments().get(i));
        }
        return Map.copyOf(substitutions);
    }

    private Type instantiateClassType(
            Ast.TypeRef ref,
            Ast.ClassDecl klass,
            Map<String, Type> substitutions,
            Type self) {
        Type unresolved = resolve(ref, Set.copyOf(klass.genericParameters()), self);
        return substituteGenerics(unresolved, substitutions);
    }

    private Type substituteGenerics(Type type, Map<String, Type> substitutions) {
        if (type instanceof Generic generic) return substitutions.getOrDefault(generic.name(), generic);
        if (type instanceof Named named) {
            return new Named(named.name(), named.arguments().stream()
                    .map(argument -> substituteGenerics(argument, substitutions)).toList());
        }
        if (type instanceof Borrow borrow) {
            return new Borrow(substituteGenerics(borrow.target(), substitutions), borrow.mutable());
        }
        if (type instanceof ListType list) {
            return new ListType(substituteGenerics(list.element(), substitutions));
        }
        if (type instanceof Tuple tuple) {
            return new Tuple(tuple.elements().stream().map(element -> substituteGenerics(element, substitutions)).toList());
        }
        if (type instanceof Function function) {
            return new Function(
                    function.parameters().stream().map(parameter -> substituteGenerics(parameter, substitutions)).toList(),
                    substituteGenerics(function.result(), substitutions));
        }
        if (type instanceof Record record) {
            LinkedHashMap<String, Type> members = new LinkedHashMap<>();
            for (Map.Entry<String, Type> entry : record.members().entrySet()) {
                members.put(entry.getKey(), substituteGenerics(entry.getValue(), substitutions));
            }
            return new Record(members);
        }
        return type;
    }

    private String qualifiedClassName(Ast.ClassDecl klass) {
        String local = localClassIdentities.get(klass);
        if (local != null) return local;
        String owner = classOwners.get(klass);
        return owner == null || owner.equals("__root__") ? klass.name() : owner + "." + klass.name();
    }

    private String qualifiedInterfaceName(Ast.InterfaceDecl iface) {
        String owner = interfaceOwners.get(iface);
        return owner == null || owner.equals("__root__") ? iface.name() : owner + "." + iface.name();
    }

    private boolean assignable(Type actual, Type expected) {
        if (Types.isAssignable(actual, expected)) return true;

        if (actual instanceof Named actualNamed && expected instanceof Record targetShape) {
            Ast.ClassDecl klass = findClass(actualNamed.name());
            return klass != null && Types.isAssignable(publicClassShapeForType(klass, actualNamed), targetShape);
        }

        if (actual instanceof Named actualNamed && expected instanceof Named expectedNamed) {
            Ast.ClassDecl actualClass = findClass(actualNamed.name());
            if (actualClass != null) {
                Ast.ClassDecl expectedClass = findClass(expectedNamed.name());
                if (expectedClass != null) {
                    Named projected = projectClassTypeToAncestor(
                            actualClass,
                            actualNamed,
                            expectedClass,
                            new LinkedHashSet<>());
                    if (projected != null && Types.isAssignable(projected, expectedNamed)) return true;
                }

                Ast.InterfaceDecl expectedInterface = findInterface(expectedNamed.name());
                if (expectedInterface != null) {
                    Named projected = projectClassTypeToInterface(
                            actualClass,
                            actualNamed,
                            expectedInterface,
                            new LinkedHashSet<>());
                    if (projected != null && Types.isAssignable(projected, expectedNamed)) return true;
                }
            }

            Ast.InterfaceDecl actualInterface = findInterface(actualNamed.name());
            Ast.InterfaceDecl expectedInterface = findInterface(expectedNamed.name());
            if (actualInterface != null && expectedInterface != null) {
                Named projected = projectInterfaceTypeToAncestor(
                        actualInterface,
                        actualNamed,
                        expectedInterface,
                        new LinkedHashSet<>());
                if (projected != null && Types.isAssignable(projected, expectedNamed)) return true;
            }
        }
        return false;
    }

    private Named projectClassTypeToAncestor(
            Ast.ClassDecl actual,
            Named actualType,
            Ast.ClassDecl expected,
            Set<Ast.ClassDecl> seen) {
        if (actual == expected) return actualType;
        if (!seen.add(actual)) return null;

        Map<String, Type> substitutions = actualType.arguments().size() == actual.genericParameters().size()
                ? classGenericSubstitutions(actual, actualType)
                : Map.of();

        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, actual);
            if (parent == null) continue;

            Type parentType = resolve(
                    parentRef,
                    Set.copyOf(actual.genericParameters()),
                    nominalClassType(actual));
            if (!substitutions.isEmpty()) parentType = substituteGenerics(parentType, substitutions);
            if (!(parentType instanceof Named namedParent)) continue;

            Named projected = projectClassTypeToAncestor(parent, namedParent, expected, seen);
            if (projected != null) {
                seen.remove(actual);
                return projected;
            }
        }

        seen.remove(actual);
        return null;
    }

    private Named projectClassTypeToInterface(
            Ast.ClassDecl actual,
            Named actualType,
            Ast.InterfaceDecl expected,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(actual)) return null;

        Map<String, Type> substitutions = actualType.arguments().size() == actual.genericParameters().size()
                ? classGenericSubstitutions(actual, actualType)
                : Map.of();

        for (Ast.TypeRef ifaceRef : actual.interfaces()) {
            Ast.InterfaceDecl iface = findInterface(ifaceRef.name());
            if (iface == null) continue;

            Type ifaceType = resolve(
                    ifaceRef,
                    Set.copyOf(actual.genericParameters()),
                    nominalClassType(actual));
            if (!substitutions.isEmpty()) ifaceType = substituteGenerics(ifaceType, substitutions);
            if (!(ifaceType instanceof Named namedInterface)) continue;

            Named projected = projectInterfaceTypeToAncestor(
                    iface,
                    namedInterface,
                    expected,
                    new LinkedHashSet<>());
            if (projected != null) {
                seen.remove(actual);
                return projected;
            }
        }

        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, actual);
            if (parent == null) continue;

            Type parentType = resolve(
                    parentRef,
                    Set.copyOf(actual.genericParameters()),
                    nominalClassType(actual));
            if (!substitutions.isEmpty()) parentType = substituteGenerics(parentType, substitutions);
            if (!(parentType instanceof Named namedParent)) continue;

            Named projected = projectClassTypeToInterface(parent, namedParent, expected, seen);
            if (projected != null) {
                seen.remove(actual);
                return projected;
            }
        }

        seen.remove(actual);
        return null;
    }

    private Named projectInterfaceTypeToAncestor(
            Ast.InterfaceDecl actual,
            Named actualType,
            Ast.InterfaceDecl expected,
            Set<Ast.InterfaceDecl> seen) {
        if (actual == expected) return actualType;
        if (!seen.add(actual)) return null;

        LinkedHashMap<String, Type> substitutions = new LinkedHashMap<>();
        if (actualType.arguments().size() == actual.genericParameters().size()) {
            for (int i = 0; i < actual.genericParameters().size(); i++) {
                substitutions.put(actual.genericParameters().get(i), actualType.arguments().get(i));
            }
        }

        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent == null) continue;

            Type parentType = resolve(
                    parentRef,
                    Set.copyOf(actual.genericParameters()),
                    null);
            if (!substitutions.isEmpty()) parentType = substituteGenerics(parentType, substitutions);
            if (!(parentType instanceof Named namedParent)) continue;

            Named projected = projectInterfaceTypeToAncestor(parent, namedParent, expected, seen);
            if (projected != null) {
                seen.remove(actual);
                return projected;
            }
        }

        seen.remove(actual);
        return null;
    }

    private Ast.FunctionDecl findFunction(String name) {
        if (ambiguousFunctions.contains(name)) throw new IllegalArgumentException("ambiguous function/routine name '" + name + "'; qualify it with its module");
        return functions.get(name);
    }

    private Ast.ClassDecl findClass(String name) {
        Ast.ClassDecl lexical = findLexicalClass(name);
        if (lexical != null) return lexical;
        Ast.ClassDecl escaped = escapedLocalClasses.get(name);
        if (escaped != null) return escaped;
        if (ambiguousClasses.contains(name)) throw new IllegalArgumentException("ambiguous class/struct name '" + name + "'; qualify it with its module");
        return classes.get(name);
    }

    private Ast.ClassDecl findLexicalClass(String name) {
        if (name.contains(".")) return null;
        for (Map<String, Ast.ClassDecl> scope : localClassScopes) {
            Ast.ClassDecl local = scope.get(name);
            if (local != null) return local;
        }
        return null;
    }

    private Ast.InterfaceDecl findInterface(String name) {
        Ast.InterfaceDecl lexical = findLexicalInterface(name);
        if (lexical != null) return lexical;
        if (ambiguousInterfaces.contains(name)) throw new IllegalArgumentException("ambiguous interface name '" + name + "'; qualify it with its module");
        return interfaces.get(name);
    }

    private Ast.InterfaceDecl findLexicalInterface(String name) {
        if (name.contains(".")) return null;
        for (Map<String, Ast.InterfaceDecl> scope : localInterfaceScopes) {
            Ast.InterfaceDecl local = scope.get(name);
            if (local != null) return local;
        }
        return null;
    }

    private Ast.TypeAliasDecl findTypeAlias(String name) {
        Ast.TypeAliasDecl lexical = findLexicalTypeAlias(name);
        if (lexical != null) return lexical;
        if (ambiguousTypeAliases.contains(name)) throw new IllegalArgumentException("ambiguous type alias '" + name + "'; qualify it with its module");
        return typeAliases.get(name);
    }

    private Ast.TypeAliasDecl findLexicalTypeAlias(String name) {
        if (name.contains(".")) return null;
        for (Map<String, Ast.TypeAliasDecl> scope : localTypeAliasScopes) {
            Ast.TypeAliasDecl local = scope.get(name);
            if (local != null) return local;
        }
        return null;
    }

    private void rejectLocalTypeGenericCollisions(List<Ast.Stmt> body, Set<String> generics) {
        if (generics.isEmpty()) return;
        for (Ast.Stmt stmt : body) {
            if (!(stmt instanceof Ast.TypeDeclStmt local)) continue;
            String name = switch (local.declaration()) {
                case Ast.ClassDecl struct -> struct.name();
                case Ast.InterfaceDecl iface -> iface.name();
                case Ast.TypeAliasDecl alias -> alias.name();
                default -> null;
            };
            if (name != null && generics.contains(name)) {
                throw new IllegalArgumentException("callable-local type '" + name
                        + "' collides with an in-scope generic type parameter");
            }
        }
    }

    private void pushLocalTypeScope(List<Ast.Stmt> body) {
        Map<String, Ast.ClassDecl> classesHere = new LinkedHashMap<>();
        Map<String, Ast.InterfaceDecl> interfacesHere = new LinkedHashMap<>();
        Map<String, Ast.TypeAliasDecl> aliasesHere = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();

        for (Ast.Stmt stmt : body) {
            if (!(stmt instanceof Ast.TypeDeclStmt local)) continue;
            if (local.declaration() instanceof Ast.ClassDecl struct) {
                if (!names.add(struct.name())) throw new IllegalArgumentException("duplicate callable-local type '" + struct.name() + "'");
                classesHere.put(struct.name(), struct);
                String identity = localClassIdentities.computeIfAbsent(struct,
                        ignored -> "$local$" + (nextLocalTypeIdentity++) + "." + struct.name());
                escapedLocalClasses.put(identity, struct);
            } else if (local.declaration() instanceof Ast.InterfaceDecl iface) {
                if (!names.add(iface.name())) throw new IllegalArgumentException("duplicate callable-local type '" + iface.name() + "'");
                interfacesHere.put(iface.name(), iface);
            } else if (local.declaration() instanceof Ast.TypeAliasDecl alias) {
                if (!names.add(alias.name())) throw new IllegalArgumentException("duplicate callable-local type '" + alias.name() + "'");
                aliasesHere.put(alias.name(), alias);
            }
        }

        localClassScopes.push(classesHere);
        localInterfaceScopes.push(interfacesHere);
        localTypeAliasScopes.push(aliasesHere);
    }

    private void popLocalTypeScope() {
        localClassScopes.pop();
        localInterfaceScopes.pop();
        localTypeAliasScopes.pop();
    }

    private Function declaredFunctionType(Ast.FunctionDecl fn) {
        Set<String> generics = Set.copyOf(fn.genericParameters());
        rejectLocalTypeGenericCollisions(fn.body(), generics);
        pushLocalTypeScope(fn.body());
        try {
            return functionType(fn.parameters(), fn.returnType(), generics, null);
        } finally {
            popLocalTypeScope();
        }
    }

    private Type nominalLocalType(Ast.ClassDecl klass, Ast.TypeRef ref, Set<String> generics, Type self) {
        if (!klass.isStruct()) throw new IllegalArgumentException("callable-local classes are not supported");
        if (ref.inferArguments()) {
            throw new IllegalArgumentException("struct '" + klass.name()
                    + "' requires explicit type arguments in type positions; use <> only at construction");
        }
        if (ref.arguments().size() != klass.genericParameters().size()) {
            throw new IllegalArgumentException("struct '" + klass.name() + "' expects "
                    + klass.genericParameters().size() + " type argument(s), got " + ref.arguments().size());
        }
        return new Named(qualifiedClassName(klass),
                ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
    }

    private void requireAssignableExpression(Ast.Expr expression, Type actual, Type expected, String where) {
        if (expression instanceof Ast.ObjectExpr object && actual instanceof Record record && expected instanceof Named named) {
            Ast.ClassDecl struct = findClass(named.name());
            if (struct != null && struct.isStruct()) {
                LinkedHashMap<String, Ast.FieldDecl> expectedFields = new LinkedHashMap<>();
                for (Ast.FieldDecl field : effectiveFields(struct, new LinkedHashSet<>())) {
                    expectedFields.put(field.name(), field);
                }
                LinkedHashSet<String> callerFields = new LinkedHashSet<>();
                for (Ast.FieldDecl field : expectedFields.values()) if (!field.composed()) callerFields.add(field.name());

                LinkedHashSet<String> extras = new LinkedHashSet<>(record.members().keySet());
                extras.removeAll(callerFields);
                if (!extras.isEmpty()) {
                    throw new IllegalArgumentException(where + " has unknown or trait-owned fields for struct '" + struct.name()
                            + "': " + extras);
                }

                Type nominal = named;
                Map<String, Type> substitutions = classGenericSubstitutions(struct, nominal);
                for (Ast.FieldDecl field : expectedFields.values()) {
                    if (field.composed()) {
                        if (field.initializer() == null) {
                            throw new IllegalArgumentException("composed trait state '" + struct.name() + "." + field.name()
                                    + "' requires an initializer");
                        }
                        continue;
                    }
                    Type supplied = record.members().get(field.name());
                    if (supplied == null) {
                        if (field.initializer() == null) {
                            throw new IllegalArgumentException(where + " is missing required struct field '" + field.name() + "'");
                        }
                        continue;
                    }
                    Type required = instantiateClassType(field.type(), struct, substitutions, nominal);
                    if (!assignable(supplied, required)) {
                        throw new IllegalArgumentException(where + " field '" + field.name() + "' expects "
                                + required + " but got " + supplied);
                    }
                }
                return;
            }
        }
        requireAssignable(actual, expected, where);
    }

    private Set<String> uniqueGenerics(List<String> names, String owner) {
        Set<String> result = new HashSet<>();
        for (String name : names) if (!result.add(name)) throw new IllegalArgumentException("duplicate generic '" + name + "' in " + owner);
        return result;
    }

    private boolean constant(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr) return true;
        if (expr instanceof Ast.UnaryExpr unary) return constant(unary.operand());
        if (expr instanceof Ast.BinaryExpr binary) return constant(binary.left()) && constant(binary.right());
        if (expr instanceof Ast.ConditionalExpr conditional) return constant(conditional.condition()) && constant(conditional.whenTrue()) && constant(conditional.whenFalse());
        if (expr instanceof Ast.ListExpr list) return list.elements().stream().allMatch(this::constant);
        if (expr instanceof Ast.TupleExpr tuple) return tuple.elements().stream().allMatch(this::constant);
        if (expr instanceof Ast.ObjectExpr object) return object.fields().stream().allMatch(field -> constant(field.value()));
        return false;
    }

    private boolean definitelyReturns(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt) return true;
            if (stmt instanceof Ast.IfStmt conditional) {
                boolean allBranches = !conditional.branches().isEmpty()
                        && conditional.branches().stream().allMatch(branch -> definitelyReturns(branch.body()))
                        && !conditional.elseBody().isEmpty()
                        && definitelyReturns(conditional.elseBody());
                if (allBranches) return true;
            }
            if (stmt instanceof Ast.TryStmt attempted) {
                if (definitelyReturns(attempted.finallyBody())) return true;
                if (definitelyReturns(attempted.body()) && definitelyReturns(attempted.catchBody())) return true;
            }
        }
        return false;
    }

    private boolean methodUsesTypeNames(Ast.MethodDecl method, Set<String> names) {
        if (typeRefUsesAnyName(method.explicitReceiverType(), names)
                || typeRefUsesAnyName(method.returnType(), names)) return true;
        for (Ast.Param param : method.parameters()) {
            if (typeRefUsesAnyName(param.type(), names)) return true;
        }
        for (Ast.Annotation annotation : method.annotations()) {
            for (Ast.TypeRef argument : annotation.arguments()) {
                if (typeRefUsesAnyName(argument, names)) return true;
            }
        }
        return statementsUseAnyTypeName(method.body(), names);
    }

    private boolean declarationUsesAnyTypeName(Ast.Decl declaration, Set<String> names) {
        if (declaration instanceof Ast.ClassDecl aggregate) {
            for (Ast.TypeRef parent : aggregate.parents()) if (typeRefUsesAnyName(parent, names)) return true;
            for (Ast.TypeRef iface : aggregate.interfaces()) if (typeRefUsesAnyName(iface, names)) return true;
            for (Ast.TypeRef trait : aggregate.traits()) if (typeRefUsesAnyName(trait, names)) return true;
            for (Ast.FieldDecl field : aggregate.fields()) {
                if (typeRefUsesAnyName(field.type(), names) || exprUsesAnyTypeName(field.initializer(), names)) return true;
            }
            for (Ast.MethodDecl method : aggregate.methods()) {
                if (methodUsesTypeNames(method, names)) return true;
            }
            return false;
        }
        if (declaration instanceof Ast.InterfaceDecl iface) {
            for (Ast.TypeRef parent : iface.parents()) if (typeRefUsesAnyName(parent, names)) return true;
            for (Ast.InterfaceMember member : iface.members()) {
                Ast.InterfaceFunctionDecl fn = (Ast.InterfaceFunctionDecl) member;
                if (typeRefUsesAnyName(fn.returnType(), names)) return true;
                for (Ast.Param param : fn.parameters()) if (typeRefUsesAnyName(param.type(), names)) return true;
            }
            return false;
        }
        if (declaration instanceof Ast.TraitDecl trait) {
            for (Ast.TypeRef iface : trait.interfaces()) if (typeRefUsesAnyName(iface, names)) return true;
            for (Ast.TypeRef nested : trait.traits()) if (typeRefUsesAnyName(nested, names)) return true;
            for (Ast.FieldDecl field : trait.fields()) {
                if (typeRefUsesAnyName(field.type(), names) || exprUsesAnyTypeName(field.initializer(), names)) return true;
            }
            for (Ast.MethodDecl method : trait.methods()) if (methodUsesTypeNames(method, names)) return true;
            return false;
        }
        if (declaration instanceof Ast.TypeAliasDecl alias) return typeRefUsesAnyName(alias.target(), names);
        if (declaration instanceof Ast.FieldDecl field) {
            return typeRefUsesAnyName(field.type(), names) || exprUsesAnyTypeName(field.initializer(), names);
        }
        return false;
    }

    private boolean statementsUseAnyTypeName(List<Ast.Stmt> statements, Set<String> names) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.TypeDeclStmt local) {
                if (declarationUsesAnyTypeName(local.declaration(), names)) return true;
            } else if (stmt instanceof Ast.BindingStmt binding) {
                if (typeRefUsesAnyName(binding.declaredType(), names)
                        || exprUsesAnyTypeName(binding.initializer(), names)) return true;
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                if (exprUsesAnyTypeName(destructure.initializer(), names)) return true;
            } else if (stmt instanceof Ast.ReturnStmt returned) {
                if (exprUsesAnyTypeName(returned.value(), names)) return true;
            } else if (stmt instanceof Ast.ExprStmt expression) {
                if (exprUsesAnyTypeName(expression.expression(), names)) return true;
            } else if (stmt instanceof Ast.DeferStmt deferred) {
                if (exprUsesAnyTypeName(deferred.expression(), names)) return true;
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    if (exprUsesAnyTypeName(branch.condition(), names)
                            || statementsUseAnyTypeName(branch.body(), names)) return true;
                }
                if (statementsUseAnyTypeName(conditional.elseBody(), names)) return true;
            } else if (stmt instanceof Ast.TryStmt attempted) {
                if (statementsUseAnyTypeName(attempted.body(), names)
                        || statementsUseAnyTypeName(attempted.catchBody(), names)
                        || statementsUseAnyTypeName(attempted.finallyBody(), names)) return true;
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                if (exprUsesAnyTypeName(loop.iterable(), names)
                        || statementsUseAnyTypeName(loop.body(), names)) return true;
            } else if (stmt instanceof Ast.ForStmt loop) {
                if ((loop.initializer() != null && statementsUseAnyTypeName(List.of(loop.initializer()), names))
                        || exprUsesAnyTypeName(loop.condition(), names)
                        || exprUsesAnyTypeName(loop.update(), names)
                        || statementsUseAnyTypeName(loop.body(), names)) return true;
            }
        }
        return false;
    }

    private boolean exprUsesAnyTypeName(Ast.Expr expr, Set<String> names) {
        if (expr == null || expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return false;
        if (expr instanceof Ast.NewExpr created) {
            if (typeRefUsesAnyName(created.type(), names)) return true;
            for (Ast.Expr argument : created.arguments()) if (exprUsesAnyTypeName(argument, names)) return true;
            return false;
        }
        if (expr instanceof Ast.StructInitExpr created) {
            if (typeRefUsesAnyName(created.type(), names)) return true;
            for (Ast.ObjectField field : created.fields()) if (exprUsesAnyTypeName(field.value(), names)) return true;
            return false;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            return exprUsesAnyTypeName(binary.left(), names) || exprUsesAnyTypeName(binary.right(), names);
        }
        if (expr instanceof Ast.UnaryExpr unary) return exprUsesAnyTypeName(unary.operand(), names);
        if (expr instanceof Ast.AssignExpr assignment) {
            return exprUsesAnyTypeName(assignment.target(), names) || exprUsesAnyTypeName(assignment.value(), names);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return exprUsesAnyTypeName(conditional.condition(), names)
                    || exprUsesAnyTypeName(conditional.whenTrue(), names)
                    || exprUsesAnyTypeName(conditional.whenFalse(), names);
        }
        if (expr instanceof Ast.CallExpr call) {
            if (exprUsesAnyTypeName(call.callee(), names)) return true;
            for (Ast.Expr argument : call.arguments()) if (exprUsesAnyTypeName(argument, names)) return true;
            return false;
        }
        if (expr instanceof Ast.MemberExpr member) return exprUsesAnyTypeName(member.receiver(), names);
        if (expr instanceof Ast.IndexExpr indexed) {
            return exprUsesAnyTypeName(indexed.receiver(), names) || exprUsesAnyTypeName(indexed.index(), names);
        }
        if (expr instanceof Ast.AwaitExpr awaited) return exprUsesAnyTypeName(awaited.expression(), names);
        if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) if (exprUsesAnyTypeName(item, names)) return true;
            return false;
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) if (exprUsesAnyTypeName(item, names)) return true;
            return false;
        }
        if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) if (exprUsesAnyTypeName(field.value(), names)) return true;
            return false;
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            for (Ast.Param param : lambda.parameters()) if (typeRefUsesAnyName(param.type(), names)) return true;
            return exprUsesAnyTypeName(lambda.expressionBody(), names)
                    || (lambda.blockBody() != null && statementsUseAnyTypeName(lambda.blockBody(), names));
        }
        return false;
    }

    private boolean typeRefUsesAnyName(Ast.TypeRef ref, Set<String> names) {
        if (ref == null) return false;
        if (ref.isBorrow()) return typeRefUsesAnyName(ref.borrowedTarget(), names);
        if (names.contains(ref.name())) return true;
        for (Ast.TypeRef argument : ref.arguments()) {
            if (typeRefUsesAnyName(argument, names)) return true;
        }
        return false;
    }

    private boolean hasInferredArgs(Ast.TypeRef ref) {
        return ref.inferArguments() || ref.arguments().stream().anyMatch(this::hasInferredArgs);
    }

    private void requireAssignable(Type actual, Type expected, String where) {
        if (!assignable(actual, expected)) throw new IllegalArgumentException(where + " has type " + actual + " but expected " + expected);
    }

    private String methodKey(String name, int arity) {
        return name + "$arity" + arity;
    }

    private void validateRoutineRecursion() {
        for (Ast.FunctionDecl fn : functionOwners.keySet()) {
            if (fn.kind() != Ast.CallableKind.ROUTINE) continue;
            if (reaches(fn, fn, new LinkedHashSet<>())) {
                throw new IllegalArgumentException("routine '" + functionOwners.get(fn) + "." + fn.name()
                        + "' participates in recursion; routines are non-recursive by contract (use fnc or a lambda)");
            }
        }
    }

    private boolean reaches(Ast.FunctionDecl current, Ast.FunctionDecl target, Set<Ast.FunctionDecl> path) {
        if (!path.add(current)) return false;
        String module = functionOwners.get(current);
        for (Ast.FunctionDecl callee : directCallees(current.body(), module)) {
            if (callee == target) return true;
            if (reaches(callee, target, path)) return true;
        }
        path.remove(current);
        return false;
    }

    private Set<Ast.FunctionDecl> directCallees(List<Ast.Stmt> body, String module) {
        Set<Ast.FunctionDecl> result = new LinkedHashSet<>();
        for (Ast.Stmt stmt : body) collectCalls(stmt, module, result);
        return result;
    }

    private void collectCalls(Ast.Stmt stmt, String module, Set<Ast.FunctionDecl> out) {
        if (stmt instanceof Ast.BindingStmt s) collectCalls(s.initializer(), module, out);
        else if (stmt instanceof Ast.DestructureStmt s) collectCalls(s.initializer(), module, out);
        else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) collectCalls(s.value(), module, out);
        else if (stmt instanceof Ast.ExprStmt s) collectCalls(s.expression(), module, out);
        else if (stmt instanceof Ast.DeferStmt s) collectCalls(s.expression(), module, out);
        else if (stmt instanceof Ast.IfStmt s) {
            for (Ast.IfBranch b : s.branches()) {
                collectCalls(b.condition(), module, out);
                for (Ast.Stmt nested : b.body()) collectCalls(nested, module, out);
            }
            for (Ast.Stmt nested : s.elseBody()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.TryStmt s) {
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
            for (Ast.Stmt nested : s.catchBody()) collectCalls(nested, module, out);
            for (Ast.Stmt nested : s.finallyBody()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.ForOfStmt s) {
            collectCalls(s.iterable(), module, out);
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.ForStmt s) {
            if (s.initializer() != null) collectCalls(s.initializer(), module, out);
            if (s.condition() != null) collectCalls(s.condition(), module, out);
            if (s.update() != null) collectCalls(s.update(), module, out);
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
        }
    }

    private void collectCalls(Ast.Expr expr, String module, Set<Ast.FunctionDecl> out) {
        if (expr instanceof Ast.CallExpr call) {
            Ast.FunctionDecl callee = resolveStaticCall(call.callee(), module);
            if (callee != null) out.add(callee);
            collectCalls(call.callee(), module, out);
            for (Ast.Expr arg : call.arguments()) collectCalls(arg, module, out);
        } else if (expr instanceof Ast.BinaryExpr e) {
            collectCalls(e.left(), module, out); collectCalls(e.right(), module, out);
        } else if (expr instanceof Ast.UnaryExpr e) collectCalls(e.operand(), module, out);
        else if (expr instanceof Ast.AssignExpr e) collectCalls(e.value(), module, out);
        else if (expr instanceof Ast.ConditionalExpr e) {
            collectCalls(e.condition(), module, out); collectCalls(e.whenTrue(), module, out); collectCalls(e.whenFalse(), module, out);
        } else if (expr instanceof Ast.MemberExpr e) collectCalls(e.receiver(), module, out);
        else if (expr instanceof Ast.IndexExpr e) { collectCalls(e.receiver(), module, out); collectCalls(e.index(), module, out); }
        else if (expr instanceof Ast.NewExpr e) for (Ast.Expr arg : e.arguments()) collectCalls(arg, module, out);
        else if (expr instanceof Ast.StructInitExpr e) for (Ast.ObjectField f : e.fields()) collectCalls(f.value(), module, out);
        else if (expr instanceof Ast.AwaitExpr e) collectCalls(e.expression(), module, out);
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr item : e.elements()) collectCalls(item, module, out);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr item : e.elements()) collectCalls(item, module, out);
        else if (expr instanceof Ast.ObjectExpr e) for (Ast.ObjectField f : e.fields()) collectCalls(f.value(), module, out);
        else if (expr instanceof Ast.LambdaExpr e) {
            if (e.expressionBody() != null) collectCalls(e.expressionBody(), module, out);
            if (e.blockBody() != null) for (Ast.Stmt nested : e.blockBody()) collectCalls(nested, module, out);
        }
    }

    private Ast.FunctionDecl resolveStaticCall(Ast.Expr callee, String module) {
        if (callee instanceof Ast.NameExpr name) {
            Ast.FunctionDecl local = functions.get(module + "." + name.name());
            if (local != null) return local;
            return ambiguousFunctions.contains(name.name()) ? null : functions.get(name.name());
        }
        if (callee instanceof Ast.MemberExpr member && member.receiver() instanceof Ast.NameExpr namespace) {
            return functions.get(namespace.name() + "." + member.member());
        }
        return null;
    }

    private static final class Env {
        private final Env parent;
        private final String moduleName;
        private final Map<String, Binding> bindings = new HashMap<>();

        private Env(Env parent) {
            this(parent, parent == null ? null : parent.moduleName);
        }

        private Env(Env parent, String moduleName) {
            this.parent = parent;
            this.moduleName = moduleName;
        }
        private void define(String name, Type type, Ast.BindingKind kind) {
            if (bindings.putIfAbsent(name, new Binding(type, kind)) != null) throw new IllegalArgumentException("duplicate binding '" + name + "'");
        }
        private Binding lookup(String name) {
            Binding binding = bindings.get(name);
            return binding != null ? binding : parent == null ? null : parent.lookup(name);
        }
        private void replace(String name, Type type, Ast.BindingKind kind) {
            if (!bindings.containsKey(name)) throw new IllegalArgumentException("unknown binding '" + name + "'");
            bindings.put(name, new Binding(type, kind));
        }
        private record Binding(Type type, Ast.BindingKind kind) { }
    }
}
