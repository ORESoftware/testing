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
import dev.oreslang.types.Types.StringLiteral;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.Union;
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
    private record ResolvedMethod(Ast.ClassDecl owner, Named ownerType, Ast.MethodDecl method) { }
    private record ResolvedField(Ast.ClassDecl owner, Named ownerType, Ast.FieldDecl field) { }
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.InterfaceDecl> interfaces = new HashMap<>();
    private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
    private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();

    private final IdentityHashMap<Ast.FunctionDecl, String> functionOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, String> classOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.InterfaceDecl, String> interfaceOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, Record> classShapeCache = new IdentityHashMap<>();

    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousInterfaces = new HashSet<>();
    private final Set<String> ambiguousTypeAliases = new HashSet<>();
    private final Set<String> importedValues = new HashSet<>();
    private final Set<Ast.TypeAliasDecl> resolvingAliases = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private Ast.ActorKind currentActorKind = Ast.ActorKind.NONE;
    private boolean currentActorConstructor;

    private record ActorEffectContext(Ast.ActorKind kind, boolean constructor) { }

    private final IdentityHashMap<Ast.FunctionDecl, Set<ActorEffectContext>> actorEffectCheckedFunctions =
            new IdentityHashMap<>();
    private final IdentityHashMap<Ast.FunctionDecl, Set<ActorEffectContext>> actorEffectCheckingFunctions =
            new IdentityHashMap<>();
    private final IdentityHashMap<Ast.MethodDecl, Set<ActorEffectContext>> actorEffectCheckedMethods =
            new IdentityHashMap<>();
    private final IdentityHashMap<Ast.MethodDecl, Set<ActorEffectContext>> actorEffectCheckingMethods =
            new IdentityHashMap<>();

    public static Ast.Program check(Ast.Program program) {
        TypeChecker checker = new TypeChecker();
        checker.validateImports(program);
        checker.collect(program);
        checker.validateEntryExport(program);
        checker.validateRoutineRecursion();
        checker.validate(program);
        OwnershipChecker.check(program);
        return program;
    }

    private void validateImports(Ast.Program program) {
        Set<String> exposed = new HashSet<>();
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.path() == null || imported.path().isBlank()) {
                throw new IllegalArgumentException("import path cannot be empty");
            }
            if (imported.wildcard()) {
                if (imported.kind() == Ast.ImportKind.ENTRY) {
                    throw new IllegalArgumentException("entry imports cannot be wildcard imports");
                }
                if (imported.namespace() == null || imported.namespace().isBlank()) {
                    throw new IllegalArgumentException("wildcard imports require a namespace alias");
                }
                if (!exposed.add(imported.namespace())) {
                    throw new IllegalArgumentException("duplicate imported name '" + imported.namespace() + "'");
                }
                importedValues.add(imported.namespace());
                continue;
            }
            if (imported.names().isEmpty()) {
                throw new IllegalArgumentException("named import must select at least one name");
            }
            for (String importedName : imported.names()) {
                String localName = imported.localName(importedName);
                if (localName == null || localName.isBlank()) {
                    throw new IllegalArgumentException("import alias cannot be blank");
                }
                if (!exposed.add(localName)) {
                    throw new IllegalArgumentException("duplicate imported name '" + localName + "'");
                }
                if (imported.kind() != Ast.ImportKind.CLASS
                        && imported.kind() != Ast.ImportKind.ACTOR) {
                    importedValues.add(localName);
                }
            }
        }
    }

    private void validateEntryExport(Ast.Program program) {
        List<Ast.EntryExportDecl> entries = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.EntryExportDecl entry) entries.add(entry);
            }
        }
        if (entries.size() > 1) {
            throw new IllegalArgumentException(
                    "a code unit may declare at most one 'export entry <name>'; ordinary default exports are not supported");
        }
        if (entries.isEmpty()) return;

        String name = entries.getFirst().name();
        if (findClass(name) != null) return;

        Ast.FunctionDecl fn = findFunction(name);
        if (fn != null) {
            if (fn.visibility() != Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException("entry function '" + name + "' must be public");
            }
            return;
        }

        Ast.ModuleDecl module = modules.get(name);
        if (module != null && !module.name().equals(Parser.ROOT_MODULE)) return;

        throw new IllegalArgumentException(
                "export entry '" + name + "' must name a class/actor, public fnc/routine, or named module in this code unit");
    }

    private void collect(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            if (modules.putIfAbsent(module.name(), module) != null) throw new IllegalArgumentException("duplicate module '" + module.name() + "'");
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    putQualified(functions, ambiguousFunctions, module.name(), fn.name(), fn, fn.kind() == Ast.CallableKind.ROUTINE ? "routine" : "function");
                    functionOwners.put(fn, module.name());
                } else if (decl instanceof Ast.ClassDecl klass) {
                    rejectReservedActorTypeName(klass.name(), "class");
                    putQualified(classes, ambiguousClasses, module.name(), klass.name(), klass, "class");
                    classOwners.put(klass, module.name());
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    rejectReservedActorTypeName(iface.name(), "interface");
                    putQualified(interfaces, ambiguousInterfaces, module.name(), iface.name(), iface, "interface");
                    interfaceOwners.put(iface, module.name());
                } else if (decl instanceof Ast.TypeAliasDecl alias) {
                    rejectReservedActorTypeName(alias.name(), "type alias");
                    putQualified(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias, "type alias");
                }
            }
        }
    }

    private static void rejectReservedActorTypeName(String name, String kind) {
        if (name.equals("Actor") || name.equals("IsoActor") || name.equals("UntrustedActor")) {
            throw new IllegalArgumentException(
                    kind + " '" + name + "' uses a compiler-intrinsic actor base name");
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
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(module.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(module.name(), klass);
                else if (decl instanceof Ast.InterfaceDecl iface) checkInterface(iface);
                else if (decl instanceof Ast.TypeAliasDecl alias) {
                    Set<String> aliasGenerics = uniqueGenerics(alias.genericParameters(), "type alias " + alias.name());
                    resolve(alias.target(), aliasGenerics, null);
                }
                else if (decl instanceof Ast.FieldDecl field) checkModuleBinding(field);
            }
        }
    }

    private void checkModuleAdherence(Ast.ModuleDecl module) {
        for (Ast.Annotation annotation : module.annotations()) {
            if (!annotation.name().equals("AdheresTo")) continue;
            if (annotation.arguments().isEmpty()) throw new IllegalArgumentException("@AdheresTo requires at least one interface");
            Record actual = moduleShape(module);
            for (Ast.TypeRef ref : annotation.arguments()) {
                Ast.InterfaceDecl iface = findInterface(ref.name());
                if (iface == null) throw new IllegalArgumentException("unknown module interface '" + ref.name() + "'");
                Type resolvedRef = resolve(ref, Set.of(), null);
                if (!(resolvedRef instanceof Named namedRef)) throw new IllegalArgumentException("@AdheresTo target must be a named interface type");
                Record expectedTemplate = interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
                Record expected = (Record) substituteGenerics(expectedTemplate,
                        genericBindings(iface.genericParameters(), namedRef.arguments(), "interface " + iface.name()));
                if (!assignable(actual, expected)) {
                    throw new IllegalArgumentException("module '" + module.name() + "' does not adhere to interface '" + ref.name() + "': expected " + expected + " but got " + actual);
                }
            }
        }
    }

    private Record moduleShape(Ast.ModuleDecl module) {
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC
                    && fn.actorKind() == Ast.ActorKind.NONE) {
                Type signature = callableContractType(
                        fn.genericParameters(), fn.parameters(), fn.returnType(), Set.of(), null);
                mergeMember(members, fn.name(), signature, "module " + module.name());
                mergeMember(members,
                        methodContractKey(fn.name(), fn.parameters().size(), fn.genericParameters().size()),
                        signature,
                        "module " + module.name());
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
            resolve(parentRef, generics, null);
        }

        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                String key = methodKey(fn.name(), fn.parameters().size());
                if (!memberKeys.add(key)) throw new IllegalArgumentException("duplicate interface method '" + iface.name() + "." + fn.name() + "' with arity " + fn.parameters().size());
                Set<String> all = new HashSet<>(generics);
                for (String generic : fn.genericParameters()) {
                    if (!all.add(generic)) throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in interface " + iface.name() + "." + fn.name());
                }
                functionType(fn.parameters(), fn.returnType(), all, null);
            } else {
                Ast.InterfaceFieldDecl field = (Ast.InterfaceFieldDecl) member;
                if (!memberKeys.add(field.name())) throw new IllegalArgumentException("duplicate interface member '" + iface.name() + "." + field.name() + "'");
                resolve(field.type(), generics, null);
            }
        }
    }

    private void checkFunction(String module, Ast.FunctionDecl fn) {
        if (fn.name().equals("main") && fn.actorKind() != Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    "program entrypoint 'main' cannot be an actor fnc; main must run synchronously and explicitly launch actors");
        }
        Set<String> generics = uniqueGenerics(fn.genericParameters(), (fn.kind() == Ast.CallableKind.ROUTINE ? "routine " : "function ") + fn.name());
        Env env = new Env(null, fn.nonLexical());
        for (Ast.Param param : fn.parameters()) {
            Type parameterType = resolveParam(param, generics, null);
            if (fn.actorKind() != Ast.ActorKind.NONE) {
                validateActorCallableBoundaryType(
                        parameterType,
                        fn.actorKind(),
                        false,
                        "parameter '" + param.name() + "' of actor callable '" + module + "." + fn.name() + "'");
            }
            env.define(
                    param.name(),
                    parameterType,
                    param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        }
        Type returns = resolve(fn.returnType(), generics, null);
        if (fn.actorKind() != Ast.ActorKind.NONE) {
            validateActorCallableBoundaryType(
                    returns,
                    fn.actorKind(),
                    true,
                    "return type of actor callable '" + module + "." + fn.name() + "'");
        }
        Ast.ActorKind previousActorKind = currentActorKind;
        currentActorKind = fn.actorKind();
        try {
            checkBlock(fn.body(), env, generics, returns, null);
        } finally {
            currentActorKind = previousActorKind;
        }
        if (returns != Primitive.VOID && !definitelyReturns(fn.body())) {
            throw new IllegalArgumentException("non-void " + fn.kind().name().toLowerCase() + " '" + module + "." + fn.name() + "' must explicitly return on every path");
        }
        if (fn.visibility() == Ast.Visibility.PUBLIC && hasInferredArgs(fn.returnType())) {
            throw new IllegalArgumentException("public callable '" + fn.name() + "' cannot export unresolved <> type arguments");
        }
    }

    private void checkClass(String module, Ast.ClassDecl klass) {
        Set<String> classGenerics = uniqueGenerics(klass.genericParameters(), "class " + klass.name());
        Type self = nominalClassType(klass);

        Set<String> parentNames = new HashSet<>();
        for (Ast.TypeRef parent : klass.parents()) {
            if (!parentNames.add(parent.name())) throw new IllegalArgumentException("duplicate parent class '" + parent.name() + "' on " + klass.name());
            if (parent.name().equals("Object") || parent.name().equals("List")) {
                if (parent.inferArguments() || !parent.arguments().isEmpty()) {
                    throw new IllegalArgumentException("built-in inheritance marker '" + parent.name() + "' does not take type arguments");
                }
                resolveClassParent(parent, klass);
                if (klass.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException("actor '" + klass.name()
                            + "' cannot extend built-in class '" + parent.name()
                            + "'; actor inheritance must preserve the actor isolation domain");
                }
                continue;
            }
            resolve(parent, classGenerics, self);
            Ast.ClassDecl resolvedParent = resolveClassParent(parent, klass);
            if (resolvedParent != null && klass.actorKind() != resolvedParent.actorKind()) {
                throw new IllegalArgumentException("actor isolation kind must be preserved across inheritance: "
                        + klass.name() + " is " + klass.actorKind() + " but parent "
                        + resolvedParent.name() + " is " + resolvedParent.actorKind());
            }
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

        if (klass.actorKind() != Ast.ActorKind.NONE) {
            validateActorProtocolContract(klass);
        }

        for (Ast.FieldDecl field : klass.fields()) {
            if (klass.actorKind() != Ast.ActorKind.NONE && field.visibility() == Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException("actor state field '" + klass.name() + "." + field.name()
                        + "' cannot be public; expose state through mailbox-dispatched methods");
            }
            Type fieldType = classFieldType(klass, field);
            if (field.initializer() != null && field.type() != null) {
                Type actual = typeOf(field.initializer(), new Env(null), classGenerics, self);
                requireAssignable(actual, fieldType, "field initializer " + klass.name() + "." + field.name());
            }
            if (field.bindingKind() == Ast.BindingKind.CONST && field.initializer() != null && !constant(field.initializer())) {
                throw new IllegalArgumentException("const field '" + field.name() + "' needs a compile-time constant initializer");
            }
        }

        for (Ast.MethodDecl method : klass.methods()) {
            Set<String> generics = new HashSet<>();
            if (!method.isStatic()) generics.addAll(classGenerics);
            for (String generic : method.genericParameters()) {
                if (classGenerics.contains(generic) || !generics.add(generic)) {
                    throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in " + klass.name() + "." + method.name());
                }
            }

            if (method.isStatic()) {
                for (Ast.Param param : method.parameters()) {
                    rejectStaticClassGenericReference(param.type(), classGenerics, klass, method);
                }
                rejectStaticClassGenericReference(method.returnType(), classGenerics, klass, method);
                for (Ast.Annotation annotation : method.annotations()) {
                    for (Ast.TypeRef argument : annotation.arguments()) {
                        rejectStaticClassGenericReference(argument, classGenerics, klass, method);
                    }
                }
                rejectStaticClassGenericReferences(method.body(), classGenerics, klass, method);
            }

            if (method.explicitReceiverType() != null) {
                Ast.TypeRef receiverRef = method.explicitReceiverType();
                boolean namesEnclosingClass = receiverRef.name().equals(klass.name()) || receiverRef.name().equals(qualifiedClassName(klass)) || receiverRef.name().equals("self");
                if (!namesEnclosingClass) {
                    Type receiver = resolve(receiverRef, generics, self);
                    requireAssignable(self, receiver, "explicit self receiver in " + klass.name() + "." + method.name());
                }
            }

            Type callableSelf = method.isStatic() ? null : self;
            Env env = new Env(null);
            if (!method.isStatic()) env.define("self", self, Ast.BindingKind.VAL);

            boolean actorEndpoint = klass.actorKind() != Ast.ActorKind.NONE
                    && !method.isStatic()
                    && method.visibility() == Ast.Visibility.PUBLIC
                    && !method.name().equals("constructor");
            boolean actorIngress = klass.actorKind() != Ast.ActorKind.NONE
                    && !method.isStatic()
                    && (actorEndpoint || method.name().equals("constructor"));
            if (klass.actorKind() != Ast.ActorKind.NONE && method.isStatic()) {
                throw new IllegalArgumentException(
                        "actor class '" + klass.name()
                                + "' cannot declare static function '" + method.name()
                                + "'; actor code must remain inside the actor ownership/effect domain");
            }
            if (actorEndpoint && !method.genericParameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "public actor protocol method '" + klass.name() + "." + method.name() + "' cannot declare generic parameters");
            }
            if (actorEndpoint && method.explicitReceiverType() != null) {
                throw new IllegalArgumentException(
                        "public actor protocol method '" + klass.name() + "." + method.name() + "' must use implicit self");
            }

            for (Ast.Param param : method.parameters()) {
                if (actorIngress && param.mutable()) {
                    throw new IllegalArgumentException(
                            "actor boundary parameter '" + param.name()
                                    + "' on " + klass.name() + "." + method.name()
                                    + " cannot be 'mut'; mutable caller authority cannot cross the actor boundary");
                }
                Type parameterType = resolveParam(param, generics, callableSelf);
                if (actorIngress) {
                    validateActorCallableBoundaryType(
                            parameterType,
                            klass.actorKind(),
                            false,
                            "parameter '" + param.name() + "' of actor "
                                    + (method.name().equals("constructor") ? "constructor '" : "protocol method '")
                                    + module + "." + klass.name() + "." + method.name() + "'");
                }
                env.define(
                        param.name(),
                        parameterType,
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            Type returns = resolve(method.returnType(), generics, callableSelf);
            if (actorEndpoint) {
                validateActorCallableBoundaryType(
                        returns,
                        klass.actorKind(),
                        true,
                        "return type of actor protocol method '"
                                + module + "." + klass.name() + "." + method.name() + "'");
            }
            Ast.ActorKind previousActorKind = currentActorKind;
            boolean previousActorConstructor = currentActorConstructor;
            currentActorKind = method.isStatic() ? Ast.ActorKind.NONE : klass.actorKind();
            currentActorConstructor = klass.actorKind() != Ast.ActorKind.NONE
                    && method.name().equals("constructor");
            try {
                checkBlock(method.body(), env, generics, returns, callableSelf);
            } finally {
                currentActorKind = previousActorKind;
                currentActorConstructor = previousActorConstructor;
            }
            if (!method.isAbstract() && returns != Primitive.VOID && !definitelyReturns(method.body())) {
                String label = method.isStatic() ? "static function" : "method";
                throw new IllegalArgumentException("non-void " + label + " '" + module + "." + klass.name() + "." + method.name() + "' must explicitly return on every path");
            }
        }

        Set<String> implemented = new HashSet<>();
        for (Ast.TypeRef interfaceRef : klass.interfaces()) {
            if (!implemented.add(interfaceRef.name())) throw new IllegalArgumentException("duplicate implemented interface '" + interfaceRef.name() + "' on " + klass.name());
            Ast.InterfaceDecl iface = findInterface(interfaceRef.name());
            if (iface == null) throw new IllegalArgumentException("unknown interface '" + interfaceRef.name() + "' implemented by " + klass.name());
            Type resolvedInterface = resolve(interfaceRef, classGenerics, self);
            if (!(resolvedInterface instanceof Named interfaceType)) throw new IllegalArgumentException("implemented interface must resolve to a named type");
            Record expectedTemplate = interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
            Record expected = (Record) substituteGenerics(expectedTemplate,
                    genericBindings(iface.genericParameters(), interfaceType.arguments(), "interface " + iface.name()));
            Record actual = publicClassShape(klass, new LinkedHashSet<>());
            if (!assignable(actual, expected)) {
                throw new IllegalArgumentException("class '" + klass.name() + "' does not implement interface '" + interfaceRef.name() + "': expected " + expected + " but got " + actual);
            }
        }
    }

    private void validateActorProtocolContract(Ast.ClassDecl klass) {
        List<Ast.MethodDecl> publicInstance = klass.methods().stream()
                .filter(method -> !method.isStatic()
                        && method.visibility() == Ast.Visibility.PUBLIC
                        && !method.name().equals("constructor"))
                .toList();

        for (Ast.MethodDecl endpoint : publicInstance) {
            if (!endpoint.genericParameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "public actor protocol method '" + klass.name() + "." + endpoint.name()
                                + "' cannot declare method generic parameters");
            }
            if (endpoint.explicitReceiverType() != null) {
                throw new IllegalArgumentException(
                        "public actor protocol method '" + klass.name() + "." + endpoint.name()
                                + "' must use implicit self");
            }
            if (endpoint.parameters().stream().anyMatch(Ast.Param::mutable)) {
                throw new IllegalArgumentException(
                        "public actor protocol method '" + klass.name() + "." + endpoint.name()
                                + "' cannot accept 'mut' parameters; mutable authority cannot cross the mailbox boundary");
            }
        }

        Record effectiveProtocol = publicClassShape(klass, new LinkedHashSet<>());
        if (effectiveProtocol.members().isEmpty()) {
            throw new IllegalArgumentException(
                    "actor '" + klass.name()
                            + "' must expose at least one public protocol method, locally or through actor inheritance");
        }

        Named selfType = nominalClassType(klass);
        for (Ast.MethodDecl local : klass.methods()) {
            if (local.isStatic()
                    || local.visibility() == Ast.Visibility.PUBLIC
                    || local.name().equals("constructor")) continue;
            for (Ast.TypeRef parentRef : klass.parents()) {
                Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
                if (parent == null) continue;
                Named parentType = concreteClassReference(parentRef, klass, selfType);
                ResolvedMethod inherited = findMethodTarget(
                        parent,
                        parentType,
                        local.name(),
                        local.arity(),
                        new LinkedHashSet<>());
                if (inherited != null
                        && inherited.method().visibility() == Ast.Visibility.PUBLIC) {
                    throw new IllegalArgumentException(
                            "actor '" + klass.name()
                                    + "' cannot narrow inherited public protocol method '"
                                    + local.name() + "'/" + local.arity() + " to private");
                }
            }
        }
    }

    private void validateOrdinaryFunctionForActorContext(Ast.FunctionDecl fn) {
        if (currentActorKind == Ast.ActorKind.NONE) return;
        ActorEffectContext effect = new ActorEffectContext(currentActorKind, currentActorConstructor);
        Set<ActorEffectContext> checked =
                actorEffectCheckedFunctions.computeIfAbsent(fn, ignored -> new HashSet<>());
        if (checked.contains(effect)) return;
        Set<ActorEffectContext> checking =
                actorEffectCheckingFunctions.computeIfAbsent(fn, ignored -> new HashSet<>());
        if (!checking.add(effect)) return; // recursive cycle is validated through its first active frame

        Ast.ActorKind previousKind = currentActorKind;
        boolean previousConstructor = currentActorConstructor;
        try {
            Set<String> generics = uniqueGenerics(
                    fn.genericParameters(),
                    "actor-context helper " + fn.name());
            Env env = new Env(null);
            for (Ast.Param param : fn.parameters()) {
                Type parameterType = resolveParam(param, generics, null);
                env.define(
                        param.name(),
                        parameterType,
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            Type returns = resolve(fn.returnType(), generics, null);
            // Preserve actor/constructor context while recursively checking the
            // helper body. Nested helper calls inherit it too.
            currentActorKind = effect.kind();
            currentActorConstructor = effect.constructor();
            checkBlock(fn.body(), env, generics, returns, null);
            checked.add(effect);
        } finally {
            currentActorKind = previousKind;
            currentActorConstructor = previousConstructor;
            checking.remove(effect);
        }
    }

    private void validateOrdinaryMethodForActorContext(
            Ast.ClassDecl owner,
            Named ownerType,
            Ast.MethodDecl method) {
        if (currentActorKind == Ast.ActorKind.NONE) return;
        if (owner.actorKind() != Ast.ActorKind.NONE && !currentActorConstructor) {
            // Actor methods are already checked under their actor domain during
            // class validation. Re-check only when constructor context must
            // propagate the stricter no-suspension rule.
            return;
        }
        ActorEffectContext effect = new ActorEffectContext(currentActorKind, currentActorConstructor);
        Set<ActorEffectContext> checked =
                actorEffectCheckedMethods.computeIfAbsent(method, ignored -> new HashSet<>());
        if (checked.contains(effect)) return;
        Set<ActorEffectContext> checking =
                actorEffectCheckingMethods.computeIfAbsent(method, ignored -> new HashSet<>());
        if (!checking.add(effect)) return;

        Ast.ActorKind previousKind = currentActorKind;
        boolean previousConstructor = currentActorConstructor;
        try {
            Set<String> generics = new HashSet<>(owner.genericParameters());
            for (String generic : method.genericParameters()) {
                if (!generics.add(generic)) {
                    throw new IllegalArgumentException(
                            "duplicate/shadowed generic '" + generic
                                    + "' in actor-context helper " + owner.name() + "." + method.name());
                }
            }
            Type callableSelf = method.isStatic() ? null : ownerType;
            Env env = new Env(null);
            if (!method.isStatic()) {
                env.define("self", ownerType, Ast.BindingKind.VAL);
            }
            for (Ast.Param param : method.parameters()) {
                Type parameterType = resolveParam(param, generics, callableSelf);
                env.define(
                        param.name(),
                        parameterType,
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            Type returns = resolve(method.returnType(), generics, callableSelf);
            currentActorKind = effect.kind();
            currentActorConstructor = effect.constructor();
            checkBlock(method.body(), env, generics, returns, callableSelf);
            checked.add(effect);
        } finally {
            currentActorKind = previousKind;
            currentActorConstructor = previousConstructor;
            checking.remove(effect);
        }
    }

    private void checkModuleBinding(Ast.FieldDecl field) {
        if (field.initializer() == null) throw new IllegalArgumentException("module binding '" + field.name() + "' requires an initializer");
        Type actual = typeOf(field.initializer(), new Env(null), Set.of(), null);
        if (field.type() != null) requireAssignable(actual, resolve(field.type(), Set.of(), null), "initializer for " + field.name());
        if (field.bindingKind() == Ast.BindingKind.CONST && !constant(field.initializer())) {
            throw new IllegalArgumentException("const '" + field.name() + "' needs a compile-time constant initializer");
        }
    }

    private void checkBlock(List<Ast.Stmt> body, Env parent, Set<String> generics, Type expectedReturn, Type self) {
        Env env = new Env(parent);
        for (Ast.Stmt stmt : body) checkStatement(stmt, env, generics, expectedReturn, self);
    }

    private void checkStatement(Ast.Stmt stmt, Env env, Set<String> generics, Type expectedReturn, Type self) {
        if (stmt instanceof Ast.BindingStmt binding) {
            Type declaredAhead = binding.declaredType() == null ? null : resolve(binding.declaredType(), generics, self);
            boolean recursiveLambda = binding.initializer() instanceof Ast.LambdaExpr;
            if (recursiveLambda && declaredAhead instanceof Function) {
                env.define(binding.name(), declaredAhead, binding.kind());
            }
            if (binding.initializer() instanceof Ast.LambdaExpr lambda && declaredAhead instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }
            Type actual = typeOfAgainstExpected(binding.initializer(), declaredAhead, env, generics, self);
            Type declared = declaredAhead == null ? actual : declaredAhead;
            requireAssignable(actual, declared, "initializer for " + binding.name());
            if (binding.kind() == Ast.BindingKind.CONST && !constant(binding.initializer())) {
                throw new IllegalArgumentException("const '" + binding.name() + "' needs a compile-time constant initializer");
            }
            if (recursiveLambda && declaredAhead instanceof Function) env.replace(binding.name(), declared, binding.kind());
            else env.define(binding.name(), declared, binding.kind());
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            Type source = deref(typeOf(destructure.initializer(), env, generics, self));
            if (destructure.kind() == Ast.DestructureKind.SEQUENCE) {
                List<Type> elementTypes = sequenceDestructureTypes(source, destructure.bindings().size());
                for (int i = 0; i < destructure.bindings().size(); i++) {
                    defineDestructureBinding(env, destructure.bindings().get(i), elementTypes.get(i));
                }
            } else {
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (binding.isDiscard()) continue;
                    env.define(binding.name(), objectDestructureMemberType(source, binding.name()), binding.kind());
                }
            }
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() instanceof Ast.LambdaExpr lambda && expectedReturn instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }
            Type actual = ret.value() == null
                    ? Primitive.VOID
                    : typeOfAgainstExpected(ret.value(), expectedReturn, env, generics, self);
            requireAssignable(actual, expectedReturn, "return value");
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
            if (name.name().equals("stdio") || name.name().equals("process") || name.name().equals("actor")) return new Named(name.name(), List.of());
            if (name.name().equals("Futures")) return new Named("$FuturesFactory", List.of());
            if (name.name().equals("Mutex") || name.name().equals("SharedMutex") || name.name().equals("RwLock")) {
                return new Named("$" + name.name() + "Factory", List.of());
            }
            if (name.name().equals("print")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            if (name.name().equals("None")) return new Named("Option", List.of(Unknown.INSTANCE));
            Ast.ModuleDecl moduleNamespace = modules.get(name.name());
            if (moduleNamespace != null) return moduleShape(moduleNamespace);
            Ast.ClassDecl classNamespace = findClass(name.name());
            if (classNamespace != null) return new ClassNamespace(qualifiedClassName(classNamespace));
            if (importedValues.contains(name.name())) return Unknown.INSTANCE;
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) {
                if (fn.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException("actor fnc '" + fn.name()
                            + "' is an actor entry point, not an ordinary function value; spawn it through the actor runtime");
                }
                if (!fn.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException(
                            "generic callable '" + fn.name()
                                    + "' must be specialized by a direct call; polymorphic function values are not supported yet");
                }
                return functionType(fn.parameters(), fn.returnType(), Set.of(), null);
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
                Type rawReceiver = typeOf(member.receiver(), env, generics, self);
                if (rawReceiver instanceof Borrow borrow && !borrow.mutable()) {
                    throw new IllegalArgumentException(
                            "cannot mutate a field through an immutable borrow/read guard");
                }
                targetType = memberType(member, env, generics, self);
                where = member.member();
            } else if (assignment.target() instanceof Ast.IndexExpr indexed) {
                Type rawReceiver = typeOf(indexed.receiver(), env, generics, self);
                if (rawReceiver instanceof Borrow borrow && !borrow.mutable()) {
                    throw new IllegalArgumentException(
                            "cannot mutate indexed state through an immutable borrow/read guard");
                }
                Type receiver = deref(rawReceiver);
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
            if (unary.operator().equals("~")) {
                requireInteger(operand, "~ operand");
                return Primitive.INT;
            }
            if (!Types.isNumeric(operand)) throw new IllegalArgumentException("unary " + unary.operator() + " needs a numeric operand");
            return operand;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            Type left = typeOf(binary.left(), env, generics, self);
            Type right = typeOf(binary.right(), env, generics, self);
            return switch (binary.operator()) {
                case "&&", "||", "^^" -> {
                    requireAssignable(left, Primitive.BOOL, "logical operand");
                    requireAssignable(right, Primitive.BOOL, "logical operand");
                    yield Primitive.BOOL;
                }
                case "&", "|", "^", "<<", ">>", ">>>" -> {
                    requireInteger(left, "bitwise left operand");
                    requireInteger(right, "bitwise right operand");
                    yield Primitive.INT;
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
            if (call.callee() instanceof Ast.NameExpr functionName) {
                Ast.FunctionDecl target = findFunction(functionName.name());
                if (target != null) {
                    if (target.actorKind() != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException(
                                "actor callable '" + target.name()
                                        + "' is an actor entry point; invoke it with 'spawn " + target.name() + "(...)'");
                    }
                    String label = "function " + functionName.name();
                    validateCallTypeArgumentMarker(call, target.genericParameters(), label);
                    validateOrdinaryFunctionForActorContext(target);
                    return checkGenericCallable(
                            target.genericParameters(),
                            target.parameters(),
                            target.returnType(),
                            call.arguments(),
                            env, generics, self,
                            null,
                            explicitGenericBindings(target.genericParameters(), call.typeArguments(), generics, self, label),
                            label);
                }
            }
            if (call.callee() instanceof Ast.MemberExpr qualifiedCall
                    && qualifiedCall.receiver() instanceof Ast.NameExpr namespace
                    && modules.containsKey(namespace.name())) {
                Ast.FunctionDecl target = functions.get(namespace.name() + "." + qualifiedCall.member());
                if (target != null) {
                    if (target.actorKind() != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException(
                                "actor callable '" + namespace.name() + "." + target.name()
                                        + "' is an actor entry point; invoke it with 'spawn "
                                        + namespace.name() + "." + target.name() + "(...)'");
                    }
                    String label = "function " + namespace.name() + "." + qualifiedCall.member();
                    validateCallTypeArgumentMarker(call, target.genericParameters(), label);
                    validateOrdinaryFunctionForActorContext(target);
                    return checkGenericCallable(
                            target.genericParameters(),
                            target.parameters(),
                            target.returnType(),
                            call.arguments(),
                            env, generics, self,
                            null,
                            explicitGenericBindings(target.genericParameters(), call.typeArguments(), generics, self, label),
                            label);
                }
            }
            if (currentActorKind != Ast.ActorKind.NONE) {
                if (call.callee() instanceof Ast.NameExpr imported
                        && importedValues.contains(imported.name())) {
                    throw new IllegalArgumentException(
                            "actor code cannot invoke imported/effect-unknown callable '"
                                    + imported.name()
                                    + "' without a statically checked actor-safe effect contract");
                }
                if (call.callee() instanceof Ast.MemberExpr importedMember
                        && importedMember.receiver() instanceof Ast.NameExpr importedNamespace
                        && importedValues.contains(importedNamespace.name())) {
                    throw new IllegalArgumentException(
                            "actor code cannot invoke effect-unknown imported member '"
                                    + importedNamespace.name() + "." + importedMember.member()
                                    + "' without a statically checked actor-safe effect contract");
                }
            }

            if (call.callee() instanceof Ast.MemberExpr futuresCall
                    && futuresCall.receiver() instanceof Ast.NameExpr futures
                    && futures.name().equals("Futures")) {
                if (call.typeArgumentsPresent()) {
                    throw new IllegalArgumentException(
                            "Futures." + futuresCall.member() + " does not accept call-site type arguments");
                }
                if (call.arguments().size() != 1) {
                    throw new IllegalArgumentException(
                            "Futures." + futuresCall.member() + " expects exactly one list of Future values");
                }
                Type collection = deref(typeOf(call.arguments().getFirst(), env, generics, self));
                Type payload = futureCollectionPayload(
                        collection,
                        "Futures." + futuresCall.member());
                return switch (futuresCall.member()) {
                    case "all" -> new Named(
                            "Future",
                            List.of(new ListType(payload)));
                    case "race" -> new Named("Future", List.of(payload));
                    default -> throw new IllegalArgumentException(
                            "unknown Futures member '" + futuresCall.member() + "'");
                };
            }
            if (call.callee() instanceof Ast.MemberExpr factoryCall
                    && factoryCall.receiver() instanceof Ast.NameExpr factory
                    && (factory.name().equals("Mutex")
                            || factory.name().equals("SharedMutex")
                            || factory.name().equals("RwLock"))
                    && factoryCall.member().equals("new")) {
                if ((factory.name().equals("SharedMutex") || factory.name().equals("RwLock"))
                        && currentActorKind != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException(
                            "actors cannot create external shared mutable state; "
                                    + "use actor-owned state for writes and receive an explicit RwLock<T> read capability");
                }
                if (call.typeArgumentsPresent()) throw new IllegalArgumentException(factory.name() + ".new does not accept call-site type arguments");
                if (call.arguments().size() != 1) throw new IllegalArgumentException(factory.name() + ".new expects exactly one value");
                Type element = typeOf(call.arguments().getFirst(), env, generics, self);
                if (element instanceof Borrow) {
                    throw new IllegalArgumentException(
                            factory.name() + "<T> requires owned data; borrowed values cannot become lock state");
                }
                if ((factory.name().equals("SharedMutex") || factory.name().equals("RwLock"))
                        && !isSharedSafe(element, new LinkedHashSet<>(), Map.of())) {
                    throw new IllegalArgumentException(
                            factory.name() + "<T> requires shared-safe owned data; "
                                    + "borrows, locks/guards, Future, closures, and unresolved generic/dynamic values are not shareable");
                }
                return new Named(factory.name(), List.of(element));
            }
            if (call.callee() instanceof Ast.NameExpr name
                    && (name.name().equals("Some") || name.name().equals("Ok") || name.name().equals("Err"))) {
                if (call.typeArgumentsPresent()) {
                    throw new IllegalArgumentException(name.name() + " does not accept call-site type arguments");
                }
                if (call.arguments().size() != 1) {
                    throw new IllegalArgumentException(name.name() + " expects exactly one value");
                }
                Type value = widenCollectionElement(typeOf(call.arguments().getFirst(), env, generics, self));
                return switch (name.name()) {
                    case "Some" -> new Named("Option", List.of(value));
                    case "Ok" -> new Named("Result", List.of(value, Unknown.INSTANCE));
                    case "Err" -> new Named("Result", List.of(Unknown.INSTANCE, value));
                    default -> throw new IllegalStateException("unreachable sum constructor");
                };
            }
            if (call.callee() instanceof Ast.MemberExpr member) {
                Type rawReceiver = typeOf(member.receiver(), env, generics, self);
                Type receiver = deref(rawReceiver);
                if (receiver instanceof ClassNamespace classNamespace) {
                    Ast.ClassDecl klass = findClass(classNamespace.className());
                    if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                    Ast.MethodDecl fn = findStaticFunction(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                    if (fn == null) throw new IllegalArgumentException("no static function '" + member.member() + "' with arity " + call.arguments().size() + " on " + klass.name());
                    validateCallTypeArgumentMarker(call, fn.genericParameters(), "static function " + klass.name() + "." + fn.name());
                    validateOrdinaryMethodForActorContext(klass, nominalClassType(klass), fn);
                    List<String> callableGenerics = new ArrayList<>(fn.genericParameters());
                    String label = "static function " + klass.name() + "." + fn.name();
                    return checkGenericCallable(
                            callableGenerics,
                            fn.parameters(),
                            fn.returnType(),
                            call.arguments(),
                            env, generics, self,
                            null,
                            explicitGenericBindings(fn.genericParameters(), call.typeArguments(), generics, self, label),
                            label);
                }
                if (receiver instanceof Named named) {
                    if (named.name().equals("ActorRef") && named.arguments().size() == 1) {
                        Type protocolType = named.arguments().getFirst();
                        if (!(protocolType instanceof Named actorType)) {
                            throw new IllegalArgumentException("ActorRef protocol must be a named actor/interface type");
                        }
                        if (call.typeArgumentsPresent()) {
                            throw new IllegalArgumentException(
                                    "actor protocol calls do not accept method type arguments");
                        }
                        if (member.member().equals("is_alive")) {
                            if (!call.arguments().isEmpty()) {
                                throw new IllegalArgumentException("ActorRef.is_alive expects no arguments");
                            }
                            if (currentActorKind == Ast.ActorKind.UNTRUSTED) {
                                throw new IllegalArgumentException(
                                        "untrusted actors cannot inspect ActorRef lifecycle state");
                            }
                            return Primitive.BOOL;
                        }
                        if (member.member().equals("send")
                                || member.member().equals("receive")
                                || member.member().equals("mailbox")) {
                            throw new IllegalArgumentException(
                                    "raw ActorRef mailbox operations are runtime-private; "
                                            + "invoke a declared typed actor protocol method instead");
                        }

                        Ast.ClassDecl actorClass = findClass(actorType.name());
                        if (actorClass != null && actorClass.actorKind() != Ast.ActorKind.NONE) {
                            ResolvedMethod target = findMethodTarget(
                                    actorClass,
                                    actorType,
                                    member.member(),
                                    call.arguments().size(),
                                    new LinkedHashSet<>());
                            if (target == null
                                    || target.method().visibility() != Ast.Visibility.PUBLIC
                                    || target.method().name().equals("constructor")) {
                                throw new IllegalArgumentException(
                                        "no public actor protocol method '" + member.member()
                                                + "' with arity " + call.arguments().size()
                                                + " on " + actorClass.name());
                            }
                            Ast.MethodDecl method = target.method();
                            if (!method.genericParameters().isEmpty()) {
                                throw new IllegalArgumentException(
                                        "actor protocol methods cannot have method-level generics");
                            }
                            Map<String, Type> bindings = classGenericBindings(target.owner(), target.ownerType());
                            Type result = checkGenericCallable(
                                    target.owner().genericParameters(),
                                    method.parameters(),
                                    method.returnType(),
                                    call.arguments(),
                                    env,
                                    generics,
                                    self,
                                    target.ownerType(),
                                    bindings,
                                    "actor protocol method " + target.owner().name() + "." + method.name());
                            validateActorProtocolReplyForCaller(
                                    result,
                                    "actor protocol reply from "
                                            + target.owner().name() + "." + method.name());
                            return new Named("Future", List.of(result));
                        }

                        Ast.InterfaceDecl protocol = findInterface(actorType.name());
                        if (protocol == null) {
                            throw new IllegalArgumentException(
                                    "ActorRef<" + actorType.name()
                                            + "> must name an actor class or actor protocol interface");
                        }
                        Map<String, Type> bindings = genericBindings(
                                protocol.genericParameters(),
                                actorType.arguments(),
                                "actor protocol interface " + protocol.name());
                        Record shape = (Record) substituteGenerics(
                                interfaceShape(
                                        protocol,
                                        Set.copyOf(protocol.genericParameters()),
                                        new LinkedHashSet<>()),
                                bindings);
                        Type endpoint = shape.members().get(
                                methodContractKey(member.member(), call.arguments().size(), 0));
                        if (!(endpoint instanceof Function fn)) {
                            throw new IllegalArgumentException(
                                    "no monomorphic actor protocol method '" + member.member()
                                            + "' with arity " + call.arguments().size()
                                            + " on interface " + protocol.name());
                        }
                        for (int i = 0; i < fn.parameters().size(); i++) {
                            validateLambdaArgument(
                                    call.arguments().get(i), fn.parameters().get(i), env, generics, self);
                            requireAssignable(
                                    typeOf(call.arguments().get(i), env, generics, self),
                                    fn.parameters().get(i),
                                    "argument " + (i + 1) + " to actor protocol "
                                            + protocol.name() + "." + member.member());
                        }
                        validateActorProtocolReplyForCaller(
                                fn.result(),
                                "actor protocol reply from "
                                        + protocol.name() + "." + member.member());
                        return new Named("Future", List.of(fn.result()));
                    }
                    if (named.name().equals("SharedMutex")
                            && currentActorKind != Ast.ActorKind.NONE
                            && (member.member().equals("lock")
                                || member.member().equals("try_lock")
                                || member.member().equals("lock_async")
                                || member.member().equals("with_lock")
                                || member.member().equals("recover"))) {
                        throw new IllegalArgumentException(
                                "actor code cannot acquire writable SharedMutex state; external shared state is read-only from actor code");
                    }
                    if ((named.name().equals("Mutex") || named.name().equals("SharedMutex"))
                            && named.arguments().size() == 1
                            && (member.member().equals("with_lock") || member.member().equals("recover"))) {
                        if (member.member().equals("recover") && !named.name().equals("SharedMutex")) {
                            throw new IllegalArgumentException("recover is only available on SharedMutex<T>");
                        }
                        if (call.arguments().size() != 1) {
                            throw new IllegalArgumentException(member.member() + " expects exactly one callback");
                        }
                        Ast.Expr callback = call.arguments().getFirst();
                        if (!(callback instanceof Ast.LambdaExpr lambda)) {
                            throw new IllegalArgumentException(
                                    member.member() + " requires an inline one-argument lambda so the protected borrow cannot escape");
                        }
                        validateMutexCallback(lambda, named.arguments().getFirst(), env, generics, self);
                        return Primitive.VOID;
                    }
                    Ast.ClassDecl klass = findClass(named.name());
                    if (klass != null) {
                        ResolvedMethod target = findMethodTarget(klass, named, member.member(), call.arguments().size(), new LinkedHashSet<>());
                        if (target == null) throw new IllegalArgumentException("no method '" + member.member() + "' with arity " + call.arguments().size() + " on " + named.name());
                        if (rawReceiver instanceof Borrow borrow && !borrow.mutable()) {
                            throw new IllegalArgumentException(
                                    "cannot invoke a potentially mutating method through an immutable borrow/read guard");
                        }
                        Ast.MethodDecl method = target.method();
                        Ast.ClassDecl owner = target.owner();
                        Named ownerType = target.ownerType();
                        if (owner.actorKind() == Ast.ActorKind.NONE || currentActorConstructor) {
                            validateOrdinaryMethodForActorContext(owner, ownerType, method);
                        }
                        if (owner.actorKind() != Ast.ActorKind.NONE && method.name().equals("constructor")) {
                            throw new IllegalArgumentException(
                                    "actor constructor '" + owner.name()
                                            + "' is runtime-only and cannot be invoked as an ordinary method");
                        }
                        validateCallTypeArgumentMarker(call, method.genericParameters(), "method " + owner.name() + "." + method.name());
                        List<String> callableGenerics = new ArrayList<>(owner.genericParameters());
                        callableGenerics.addAll(method.genericParameters());
                        String label = "method " + owner.name() + "." + method.name();
                        Map<String, Type> bindings = new HashMap<>(classGenericBindings(owner, ownerType));
                        bindings.putAll(explicitGenericBindings(method.genericParameters(), call.typeArguments(), generics, self, label));
                        return checkGenericCallable(
                                callableGenerics,
                                method.parameters(),
                                method.returnType(),
                                call.arguments(),
                                env, generics, self,
                                ownerType,
                                bindings,
                                label);
                    }
                }
            }
            if (call.typeArgumentsPresent()) {
                throw new IllegalArgumentException("call-site type arguments require a declared generic function or method");
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
                Ast.FunctionDecl moduleFunction = functions.get(namespace.name() + "." + member.member());
                if (moduleFunction != null) {
                    if (moduleFunction.actorKind() != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException(
                                "actor callable '" + namespace.name() + "." + member.member()
                                        + "' is not an ordinary function value; invoke it with spawn");
                    }
                    if (!moduleFunction.genericParameters().isEmpty()) {
                        throw new IllegalArgumentException(
                                "generic callable '" + namespace.name() + "." + member.member()
                                        + "' must be specialized by a direct call; polymorphic function values are not supported yet");
                    }
                    return functionType(moduleFunction.parameters(), moduleFunction.returnType(), Set.of(), null);
                }
                Ast.ClassDecl memberClass = classes.get(namespace.name() + "." + member.member());
                if (memberClass != null) return new ClassNamespace(qualifiedClassName(memberClass));
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("stdio")) {
                if (member.member().equals("print") || member.member().equals("println")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
                if (member.member().equals("stdout")) return new Named("stdio.stdout", List.of());
            }
            Type receiver = typeOf(member.receiver(), env, generics, self);
            Type sumReceiver = deref(receiver);
            Type sumMember = builtinOptionResultMember(sumReceiver, member.member());
            if (sumMember != null) return sumMember;
            if (sumReceiver instanceof Named sumNamed
                    && (sumNamed.name().equals("Option") || sumNamed.name().equals("Result"))) {
                throw new IllegalArgumentException(
                        "unknown " + sumNamed.name() + " member '" + member.member() + "'");
            }
            Type futureMember = builtinFutureMember(sumReceiver, member.member());
            if (futureMember != null) return futureMember;
            Type actorHandleMember = builtinActorHandleMember(sumReceiver, member.member());
            if (actorHandleMember != null) return actorHandleMember;
            Type rwLockMember = builtinRwLockMember(receiver, member.member());
            if (rwLockMember != null) return rwLockMember;
            Type mutexMember = builtinMutexMember(receiver, member.member());
            if (mutexMember != null) return mutexMember;
            receiver = unwrapMutexGuard(receiver);
            if (receiver instanceof Named named && named.name().equals("stdio.stdout") && member.member().equals("write")) {
                return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            }
            if (member.receiver() instanceof Ast.NameExpr name
                    && (name.name().equals("process") || name.name().equals("actor"))) return Unknown.INSTANCE;
            if (member.receiver() instanceof Ast.NameExpr name && importedValues.contains(name.name())) return Unknown.INSTANCE;

            if (receiver instanceof ClassNamespace classNamespace) {
                Ast.ClassDecl klass = findClass(classNamespace.className());
                if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                List<Ast.MethodDecl> functions = findStaticFunctionsByName(klass, member.member(), new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    if (!fn.genericParameters().isEmpty()) {
                        throw new IllegalArgumentException(
                                "generic static function '" + klass.name() + "." + fn.name()
                                        + "' must be specialized by a direct call; polymorphic function values are not supported yet");
                    }
                    return functionType(fn.parameters(), fn.returnType(), Set.of(), null);
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
                    ResolvedField field = findFieldTarget(klass, named, member.member(), new LinkedHashSet<>());
                    if (field != null) {
                        Type pattern = classFieldType(field.owner(), field.field());
                        return substituteGenerics(pattern, classGenericBindings(field.owner(), field.ownerType()));
                    }
                    List<Ast.MethodDecl> methods = findMethodsByName(klass, member.member(), new LinkedHashSet<>());
                    if (!methods.isEmpty() && klass.actorKind() != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException(
                                "actor method '" + klass.name() + "." + member.member()
                                        + "' is not a first-class value and cannot escape an actor turn");
                    }
                    if (methods.size() == 1) {
                        Ast.MethodDecl method = methods.getFirst();
                        if (!method.genericParameters().isEmpty()) {
                            throw new IllegalArgumentException(
                                    "generic method '" + klass.name() + "." + method.name()
                                            + "' must be specialized by a direct call; polymorphic bound-method values are not supported yet");
                        }
                        ResolvedMethod target = findMethodTarget(
                                klass, named, member.member(), method.arity(), new LinkedHashSet<>());
                        if (target == null) {
                            throw new IllegalArgumentException(
                                    "cannot resolve method owner for '" + member.member() + "'");
                        }
                        Set<String> memberGenerics = new HashSet<>(target.owner().genericParameters());
                        memberGenerics.addAll(method.genericParameters());
                        Type signature = functionType(
                                method.parameters(),
                                method.returnType(),
                                memberGenerics,
                                target.ownerType());
                        return substituteGenerics(
                                signature,
                                classGenericBindings(target.owner(), target.ownerType()));
                    }
                    if (methods.size() > 1) {
                        throw new IllegalArgumentException(
                                "overloaded method '" + member.member()
                                        + "' must be called so arity can select the overload");
                    }
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
            if (klass == null) return resolve(created.type(), generics, self);
            if (klass.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalArgumentException("actor '" + klass.name()
                        + "' cannot be constructed with new; actor instances must be created through the actor runtime");
            }

            Named nominal;
            if (created.type().inferArguments()) {
                if (!created.type().arguments().isEmpty()) {
                    throw new IllegalArgumentException("inferred constructor type arguments must use an empty <> marker");
                }
                nominal = inferConstructedClassType(klass, created.arguments(), env, generics, self);
            } else {
                Type resolvedCreated = resolve(created.type(), generics, self);
                if (!(resolvedCreated instanceof Named named)) {
                    throw new IllegalArgumentException("constructor target must resolve to a named class type");
                }
                nominal = named;
            }

            List<ResolvedField> fields = effectiveFieldTargets(klass, nominal, new LinkedHashSet<>());
            if (created.arguments().size() > fields.size()) throw new IllegalArgumentException("constructor for " + klass.name() + " received too many positional fields");
            for (int i = 0; i < fields.size(); i++) {
                ResolvedField resolvedField = fields.get(i);
                Ast.FieldDecl field = resolvedField.field();
                if (i < created.arguments().size()) {
                    Type fieldPattern = classFieldType(resolvedField.owner(), field);
                    Type expected = substituteGenerics(fieldPattern, classGenericBindings(resolvedField.owner(), resolvedField.ownerType()));
                    requireAssignable(typeOf(created.arguments().get(i), env, generics, self),
                            expected, "constructor field " + field.name());
                } else if (field.initializer() == null) {
                    throw new IllegalArgumentException("constructor for " + klass.name() + " is missing field '" + field.name() + "'");
                }
            }
            return nominal;
        }
        if (expr instanceof Ast.SpawnExpr spawned) {
            if (currentActorKind == Ast.ActorKind.UNTRUSTED) {
                throw new IllegalArgumentException(
                        "untrusted actors cannot spawn child actors; use bounded async I/O/Futures instead");
            }
            Ast.CallExpr call = spawned.call();
            Ast.FunctionDecl callableTarget = null;
            Ast.ClassDecl classTarget = null;
            String label;
            if (call.callee() instanceof Ast.NameExpr targetName) {
                callableTarget = findFunction(targetName.name());
                classTarget = findClass(targetName.name());
                label = "actor " + targetName.name();
            } else if (call.callee() instanceof Ast.MemberExpr qualified
                    && qualified.receiver() instanceof Ast.NameExpr namespace
                    && modules.containsKey(namespace.name())) {
                callableTarget = functions.get(namespace.name() + "." + qualified.member());
                classTarget = classes.get(namespace.name() + "." + qualified.member());
                label = "actor " + namespace.name() + "." + qualified.member();
            } else {
                throw new IllegalArgumentException(
                        "spawn requires a direct actor class or actor fnc/routine call, optionally module-qualified");
            }

            if (callableTarget != null && classTarget != null) {
                throw new IllegalArgumentException(label + " is ambiguous between a class and callable");
            }

            if (classTarget != null) {
                if (classTarget.actorKind() == Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException(
                            "spawn target class '" + classTarget.name() + "' is not an actor class");
                }
                rejectActorDomainEscalation(currentActorKind, classTarget.actorKind(), classTarget.name());

                Map<String, Type> classBindings;
                if (classTarget.genericParameters().isEmpty()) {
                    validateCallTypeArgumentMarker(call, List.of(), label);
                    classBindings = Map.of();
                } else {
                    if (!call.typeArgumentsPresent() || call.typeArguments().isEmpty()) {
                        throw new IllegalArgumentException(
                                label + " requires explicit actor class type arguments when spawned");
                    }
                    classBindings = explicitGenericBindings(
                            classTarget.genericParameters(),
                            call.typeArguments(),
                            generics,
                            self,
                            label);
                    if (classBindings.size() != classTarget.genericParameters().size()) {
                        throw new IllegalArgumentException(label + " requires all actor class type arguments");
                    }
                }

                Named actorType = new Named(
                        qualifiedClassName(classTarget),
                        classTarget.genericParameters().stream().map(classBindings::get).toList());

                List<Ast.MethodDecl> constructors = classTarget.methods().stream()
                        .filter(method -> !method.isStatic()
                                && method.name().equals("constructor")
                                && method.arity() == call.arguments().size())
                        .toList();
                if (constructors.size() > 1) {
                    throw new IllegalArgumentException(
                            "actor class '" + classTarget.name()
                                    + "' has multiple constructors with arity " + call.arguments().size());
                }
                if (constructors.isEmpty()) {
                    if (!call.arguments().isEmpty()) {
                        throw new IllegalArgumentException(
                                "actor class '" + classTarget.name()
                                        + "' has no constructor with arity " + call.arguments().size());
                    }
                } else {
                    Ast.MethodDecl constructor = constructors.getFirst();
                    checkGenericCallable(
                            classTarget.genericParameters(),
                            constructor.parameters(),
                            constructor.returnType(),
                            call.arguments(),
                            env,
                            generics,
                            self,
                            actorType,
                            classBindings,
                            "actor constructor " + classTarget.name());
                }
                return new Named("ActorRef", List.of(actorType));
            }

            if (callableTarget == null) {
                throw new IllegalArgumentException("spawn target is not a known actor class or callable");
            }
            if (callableTarget.actorKind() == Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "spawn target '" + callableTarget.name() + "' is not declared with the actor keyword");
            }
            rejectActorDomainEscalation(currentActorKind, callableTarget.actorKind(), callableTarget.name());
            label = "actor callable " + callableTarget.name();
            validateCallTypeArgumentMarker(call, callableTarget.genericParameters(), label);
            Type result = checkGenericCallable(
                    callableTarget.genericParameters(),
                    callableTarget.parameters(),
                    callableTarget.returnType(),
                    call.arguments(),
                    env, generics, self,
                    null,
                    explicitGenericBindings(
                            callableTarget.genericParameters(),
                            call.typeArguments(),
                            generics,
                            self,
                            label),
                    label);
            return new Named("ActorSpawn", List.of(result));
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            if (currentActorConstructor) {
                throw new IllegalArgumentException("actor constructors cannot await or suspend");
            }
            Type awaitedType = typeOf(awaited.expression(), env, generics, self);
            if (awaitedType instanceof Named named && named.name().equals("Future") && named.arguments().size() == 1) {
                return named.arguments().getFirst();
            }
            if (awaitedType instanceof Named named && named.name().equals("ActorSpawn")
                    && named.arguments().size() == 1) {
                return new Named("ActorRef", List.of());
            }
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.ListExpr list) {
            if (list.elements().isEmpty()) return new ListType(Unknown.INSTANCE);
            Type element = widenCollectionElement(typeOf(list.elements().getFirst(), env, generics, self));
            for (int i = 1; i < list.elements().size(); i++) {
                element = collectionElementJoin(element, widenCollectionElement(typeOf(list.elements().get(i), env, generics, self)));
            }
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
            boolean nonLexical = lambda.nonLexical() || env.descendantsNonLexical();
            Env lambdaEnv = new Env(nonLexical ? null : env, nonLexical);
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

    private Type typeOfAgainstExpected(Ast.Expr expr, Type expected, Env env, Set<String> generics, Type self) {
        if (expr instanceof Ast.LambdaExpr lambda && expected instanceof Function fn) {
            validateLambdaAgainstExpected(lambda, fn, env, generics, self);
            return fn;
        }
        if (expr instanceof Ast.ListExpr list) {
            if (expected instanceof Tuple) {
                return new Tuple(list.elements().stream().map(item -> typeOf(item, env, generics, self)).toList());
            }
            if (expected instanceof Union union
                    && union.options().stream().allMatch(option -> option instanceof Tuple)) {
                return new Tuple(list.elements().stream().map(item -> typeOf(item, env, generics, self)).toList());
            }
        }
        return typeOf(expr, env, generics, self);
    }

    private void defineDestructureBinding(Env env, Ast.DestructureBinding binding, Type type) {
        if (!binding.isDiscard()) env.define(binding.name(), type, binding.kind());
    }

    private List<Type> sequenceDestructureTypes(Type source, int arity) {
        source = deref(source);
        if (source instanceof Tuple tuple) {
            if (tuple.elements().size() != arity) {
                throw new IllegalArgumentException("destructure arity mismatch: tuple has " + tuple.elements().size()
                        + " element(s), pattern has " + arity);
            }
            return tuple.elements();
        }
        if (source instanceof ListType list) {
            return java.util.Collections.nCopies(arity, list.element());
        }
        if (source instanceof Union union) {
            List<List<Type>> alternatives = new ArrayList<>(union.options().size());
            for (Type option : union.options()) {
                alternatives.add(sequenceDestructureTypes(option, arity));
            }
            List<Type> joined = new ArrayList<>(arity);
            for (int i = 0; i < arity; i++) {
                final int slot = i;
                joined.add(Types.unionOf(alternatives.stream().map(items -> items.get(slot)).toList()));
            }
            return List.copyOf(joined);
        }
        throw new IllegalArgumentException("sequence destructuring requires a tuple or array/list value");
    }

    private Type objectDestructureMemberType(Type source, String memberName) {
        source = deref(source);
        if (source instanceof Record record) {
            Type member = record.members().get(memberName);
            if (member == null) throw new IllegalArgumentException("object destructure requires member '" + memberName + "'");
            return member;
        }
        if (source instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass == null) throw new IllegalArgumentException("object destructuring requires a record/map-like value");
            Record shape = (Record) substituteGenerics(
                    publicClassShape(klass, new LinkedHashSet<>()),
                    classGenericBindings(klass, named));
            Type member = shape.members().get(memberName);
            if (member == null) throw new IllegalArgumentException("object destructure requires member '" + memberName + "'");
            return member;
        }
        if (source instanceof Union union) {
            List<Type> alternatives = new ArrayList<>(union.options().size());
            for (Type option : union.options()) alternatives.add(objectDestructureMemberType(option, memberName));
            return Types.unionOf(alternatives);
        }
        if (source == Unknown.INSTANCE) return Unknown.INSTANCE;
        throw new IllegalArgumentException("object destructuring requires a record/map-like value");
    }

    private void validateMutexCallback(Ast.LambdaExpr lambda, Type expectedParameter, Env parent, Set<String> generics, Type self) {
        if (lambda.expressionBody() != null) {
            throw new IllegalArgumentException("mutex callbacks require a block body");
        }
        if (lambda.parameters().size() != 1) {
            throw new IllegalArgumentException("mutex callback must accept exactly one protected-value parameter");
        }
        Ast.Param param = lambda.parameters().getFirst();
        Type declared = param.type().name().equals("$infer$") ? expectedParameter : resolveParam(param, generics, self);
        requireAssignable(expectedParameter, declared, "mutex callback parameter " + param.name());
        requireAssignable(declared, expectedParameter, "mutex callback parameter " + param.name());
        Env lambdaEnv = new Env(parent);
        lambdaEnv.define(param.name(), declared, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        checkBlock(lambda.blockBody(), lambdaEnv, generics, Primitive.VOID, self);
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
        boolean nonLexical = lambda.nonLexical() || parent.descendantsNonLexical();
        Env lambdaEnv = new Env(nonLexical ? null : parent, nonLexical);
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

    private Type deref(Type type) {
        return type instanceof Borrow borrow ? borrow.target() : type;
    }

    private Type memberType(Ast.MemberExpr member, Env env, Set<String> generics, Type self) {
        Type receiver = unwrapMutexGuard(deref(typeOf(member.receiver(), env, generics, self)));
        if (receiver instanceof Record record) {
            Type result = record.members().get(member.member());
            if (result == null) throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
            return result;
        }
        if (receiver instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                ResolvedField field = findFieldTarget(klass, named, member.member(), new LinkedHashSet<>());
                if (field != null) {
                    Type pattern = classFieldType(field.owner(), field.field());
                    return substituteGenerics(pattern, classGenericBindings(field.owner(), field.ownerType()));
                }
            }
        }
        throw new IllegalArgumentException("assignment target '" + member.member() + "' is not a mutable data field");
    }

    private void validateActorProtocolReplyForCaller(Type type, String where) {
        if (currentActorKind == Ast.ActorKind.NONE) return;
        validateActorCallableBoundaryType(
                type,
                currentActorKind,
                true,
                where + " into " + currentActorKind + " caller");
    }

    private void validateActorCallableBoundaryType(
            Type type,
            Ast.ActorKind actorKind,
            boolean returnPosition,
            String where) {
        if (returnPosition && type == Primitive.VOID) return;
        if (type == Primitive.VOID) {
            throw new IllegalArgumentException(where + " cannot be void");
        }
        if (type == Unknown.INSTANCE || type instanceof Generic || type instanceof Borrow
                || type instanceof Function || type instanceof ClassNamespace) {
            throw new IllegalArgumentException(
                    where + " must be a concrete owned/sendable data type; borrows, functions, and unresolved types cannot cross an actor boundary");
        }
        if (type instanceof Primitive || type instanceof StringLiteral) return;
        if (type instanceof ListType list) {
            validateActorCallableBoundaryType(list.element(), actorKind, false, where + " element");
            return;
        }
        if (type instanceof Tuple tuple) {
            for (int i = 0; i < tuple.elements().size(); i++) {
                validateActorCallableBoundaryType(tuple.elements().get(i), actorKind, false, where + " tuple element " + i);
            }
            return;
        }
        if (type instanceof Union union) {
            for (Type option : union.options()) {
                validateActorCallableBoundaryType(option, actorKind, false, where + " union member");
            }
            return;
        }
        if (type instanceof Record record) {
            for (Map.Entry<String, Type> member : record.members().entrySet()) {
                validateActorCallableBoundaryType(member.getValue(), actorKind, false, where + " field '" + member.getKey() + "'");
            }
            return;
        }
        if (!(type instanceof Named named)) {
            throw new IllegalArgumentException(where + " is not actor-boundary sendable: " + type);
        }

        if (named.name().equals("Mutex") || named.name().equals("MutexGuard")
                || named.name().equals("Future") || named.name().equals("ActorSpawn")) {
            throw new IllegalArgumentException(
                    where + " cannot use " + named.name() + " across an actor boundary");
        }
        if (named.name().equals("SharedMutex")) {
            throw new IllegalArgumentException(
                    where + " cannot use writable SharedMutex<T>; actor boundaries admit data, ActorRef capabilities, and explicit read-only external-state capabilities only");
        }
        if (named.name().equals("RwLock")) {
            if (actorKind != Ast.ActorKind.SHARED) {
                throw new IllegalArgumentException(
                        where + " can use RwLock<T> only with shared actors; "
                                + "iso/private and untrusted actors cannot access shared external memory");
            }
            if (named.arguments().size() != 1
                    || !isSharedSafe(named.arguments().getFirst(), new LinkedHashSet<>(), Map.of())) {
                throw new IllegalArgumentException(
                        where + " requires RwLock<T> to contain shared-safe owned data");
            }
            return;
        }
        if (named.name().equals("ActorRef")) {
            if (named.arguments().size() != 1) {
                throw new IllegalArgumentException(where + " requires ActorRef<ActorOrProtocol>");
            }
            Type protocol = named.arguments().getFirst();
            if (!(protocol instanceof Named protocolNamed)) {
                throw new IllegalArgumentException(where + " has an invalid ActorRef protocol type");
            }
            Ast.ClassDecl actorClass = findClass(protocolNamed.name());
            Ast.InterfaceDecl actorInterface = findInterface(protocolNamed.name());
            if ((actorClass == null || actorClass.actorKind() == Ast.ActorKind.NONE)
                    && actorInterface == null) {
                throw new IllegalArgumentException(
                        where + " ActorRef protocol must name an actor class or interface");
            }
            return;
        }

        for (Type argument : named.arguments()) {
            validateActorCallableBoundaryType(argument, actorKind, false, where + " type argument");
        }

        // Built-in sum/container values are data-only when their arguments pass.
        if (named.name().equals("Option") || named.name().equals("Result")
                || named.name().equals("OptionUnwrapError")) {
            return;
        }

        Ast.ClassDecl klass = findClass(named.name());
        if (klass == null) {
            // Runtime capability types (for example ActorId/ActorRef) are
            // validated again by transport. Do not silently admit known
            // mutable/suspending primitives above, but avoid rejecting opaque
            // capability types solely because they have no source class body.
            return;
        }
        if (klass.actorKind() != Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    where + " cannot transport an actor instance by value; pass an actor capability/reference");
        }

        if (!isSharedSafe(type, new LinkedHashSet<>(), Map.of())) {
            throw new IllegalArgumentException(
                    where + " contains state that is not safe to transport across an actor boundary");
        }
    }

    private boolean isSharedSafe(Type type, Set<Ast.ClassDecl> seen, Map<String, Type> genericBindings) {
        if (type == Unknown.INSTANCE || type instanceof Borrow || type instanceof Function || type instanceof ClassNamespace) return false;
        if (type instanceof Primitive primitive) return primitive != Primitive.VOID;
        if (type instanceof StringLiteral) return true;
        if (type instanceof Generic generic) {
            Type bound = genericBindings.get(generic.name());
            return bound != null && bound != type && isSharedSafe(bound, seen, genericBindings);
        }
        if (type instanceof ListType list) return isSharedSafe(list.element(), seen, genericBindings);
        if (type instanceof Union union) {
            for (Type option : union.options()) if (!isSharedSafe(option, seen, genericBindings)) return false;
            return true;
        }
        if (type instanceof Tuple tuple) {
            for (Type element : tuple.elements()) if (!isSharedSafe(element, seen, genericBindings)) return false;
            return true;
        }
        if (type instanceof Record record) {
            for (Type member : record.members().values()) if (!isSharedSafe(member, seen, genericBindings)) return false;
            return true;
        }
        if (!(type instanceof Named named)) return false;

        if (named.name().equals("Mutex") || named.name().equals("MutexGuard")
                || named.name().equals("RwLock") || named.name().equals("RwReadGuard")
                || named.name().equals("RwWriteGuard")
                || named.name().equals("Future") || named.name().equals("ActorSpawn")
                || named.name().equals("SharedMutex")) return false;
        if (named.name().equals("OptionUnwrapError")) return named.arguments().isEmpty();
        if (named.name().equals("Option")) {
            return named.arguments().size() == 1 && isSharedSafe(named.arguments().getFirst(), seen, genericBindings);
        }
        if (named.name().equals("Result")) {
            return named.arguments().size() == 2
                    && isSharedSafe(named.arguments().get(0), seen, genericBindings)
                    && isSharedSafe(named.arguments().get(1), seen, genericBindings);
        }

        for (Type argument : named.arguments()) {
            if (!isSharedSafe(argument, seen, genericBindings)) return false;
        }

        Ast.ClassDecl klass = findClass(named.name());
        if (klass == null) return false;
        if (klass.actorKind() != Ast.ActorKind.NONE) return false;
        if (!seen.add(klass)) return true;

        try {
            Map<String, Type> classBindings = new HashMap<>(genericBindings);
            if (named.arguments().size() != klass.genericParameters().size()) return false;
            for (int i = 0; i < klass.genericParameters().size(); i++) {
                classBindings.put(klass.genericParameters().get(i),
                        resolveSharedGeneric(named.arguments().get(i), genericBindings));
            }

            Type nominal = nominalClassType(klass);
            Set<String> classGenerics = Set.copyOf(klass.genericParameters());

            // Check only fields declared by this class under this class's own
            // generic environment. Flattening inherited fields here is unsafe:
            // a parent T must never be resolved as an unrelated child T.
            for (Ast.FieldDecl field : klass.fields()) {
                Type fieldType = resolveSharedGeneric(
                        classFieldType(klass, field),
                        classBindings);
                if (!isSharedSafe(fieldType, seen, classBindings)) return false;
            }

            // Recurse into each parent after substituting the child's generic
            // bindings into the parent's concrete type arguments.
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Type parentType = resolveSharedGeneric(resolve(parentRef, classGenerics, nominal), classBindings);
                if (!isSharedSafe(parentType, seen, classBindings)) return false;
            }

            return true;
        } finally {
            seen.remove(klass);
        }
    }

    private Type resolveSharedGeneric(Type type, Map<String, Type> bindings) {
        if (type instanceof Generic generic) {
            Type bound = bindings.get(generic.name());
            return bound == null || bound == type ? type : resolveSharedGeneric(bound, bindings);
        }
        if (type instanceof Named named) {
            return new Named(named.name(), named.arguments().stream()
                    .map(argument -> resolveSharedGeneric(argument, bindings)).toList());
        }
        if (type instanceof ListType list) return new ListType(resolveSharedGeneric(list.element(), bindings));
        if (type instanceof Tuple tuple) {
            return new Tuple(tuple.elements().stream().map(element -> resolveSharedGeneric(element, bindings)).toList());
        }
        if (type instanceof Union union) {
            return Types.unionOf(union.options().stream()
                    .map(option -> resolveSharedGeneric(option, bindings))
                    .toList());
        }
        if (type instanceof Record record) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Map.Entry<String, Type> entry : record.members().entrySet()) {
                members.put(entry.getKey(), resolveSharedGeneric(entry.getValue(), bindings));
            }
            return new Record(members);
        }
        return type;
    }

    private Type builtinOptionResultMember(Type receiver, String member) {
        if (!(receiver instanceof Named named)) return null;
        if (named.name().equals("Option") && named.arguments().size() == 1) {
            Type element = named.arguments().getFirst();
            return switch (member) {
                case "is_some", "is_none" -> new Function(List.of(), Primitive.BOOL);
                case "unwrap" -> new Function(List.of(), element);
                case "unwrap_safe" -> new Function(
                        List.of(),
                        new Named("Result", List.of(element, new Named("OptionUnwrapError", List.of()))));
                case "expect" -> new Function(List.of(Primitive.STRING), element);
                case "unwrap_or" -> new Function(List.of(element), element);
                default -> null;
            };
        }
        if (named.name().equals("Result") && named.arguments().size() == 2) {
            Type ok = named.arguments().get(0);
            return switch (member) {
                case "is_ok", "is_err" -> new Function(List.of(), Primitive.BOOL);
                case "unwrap" -> new Function(List.of(), ok);
                case "unwrap_safe" -> new Function(List.of(), named);
                case "expect" -> new Function(List.of(Primitive.STRING), ok);
                case "unwrap_or" -> new Function(List.of(ok), ok);
                default -> null;
            };
        }
        return null;
    }

    private static void rejectActorDomainEscalation(
            Ast.ActorKind caller,
            Ast.ActorKind target,
            String targetName) {
        if (caller == Ast.ActorKind.PRIVATE && target == Ast.ActorKind.SHARED) {
            throw new IllegalArgumentException(
                    "isoactor/private actor code cannot spawn shared actor '" + targetName
                            + "' because that would escalate into the SHARED_MEMORY domain");
        }
        if (caller == Ast.ActorKind.UNTRUSTED) {
            throw new IllegalArgumentException("untrusted actors cannot spawn child actors");
        }
    }

    private Type builtinActorHandleMember(Type receiver, String member) {
        if (!(receiver instanceof Named named)) return null;
        if (named.name().equals("ActorSpawn") && named.arguments().size() == 1) {
            Type result = named.arguments().getFirst();
            return switch (member) {
                case "id" -> new Named("ActorId", List.of());
                case "ready" -> new Named(
                        "Future",
                        List.of(new Named("ActorRef", List.of())));
                case "done" -> new Named("Future", List.of(Primitive.BOOL));
                case "result" -> {
                    if (result == Primitive.VOID) {
                        throw new IllegalArgumentException(
                                "void actor callable has no result value; await spawn.done for completion");
                    }
                    yield new Named("Future", List.of(result));
                }
                default -> throw new IllegalArgumentException(
                        "unknown ActorSpawn member '" + member + "'");
            };
        }
        if (named.name().equals("ActorRef")
                && (named.arguments().isEmpty() || named.arguments().size() == 1)) {
            return switch (member) {
                case "id" -> new Named("ActorId", List.of());
                case "is_alive" -> {
                    if (currentActorKind == Ast.ActorKind.UNTRUSTED) {
                        throw new IllegalArgumentException(
                                "untrusted actors cannot inspect ActorRef lifecycle state");
                    }
                    yield new Function(List.of(), Primitive.BOOL);
                }
                case "send", "receive", "mailbox" -> throw new IllegalArgumentException(
                        "raw ActorRef mailbox operations are runtime-private; "
                                + "invoke a declared typed actor protocol method instead");
                default -> {
                    if (named.arguments().size() == 1) {
                        throw new IllegalArgumentException(
                                "actor protocol methods are not first-class values; invoke '"
                                        + member + "(...)' directly through the ActorRef");
                    }
                    throw new IllegalArgumentException(
                            "unknown ActorRef member '" + member + "'");
                }
            };
        }
        return null;
    }

    private Type builtinFutureMember(Type receiver, String member) {
        if (!(receiver instanceof Named named)
                || !named.name().equals("Future")
                || named.arguments().size() != 1) {
            return null;
        }
        return switch (member) {
            case "is_done", "is_cancelled", "cancel" ->
                    new Function(List.of(), Primitive.BOOL);
            default -> null;
        };
    }

    private Type futureCollectionPayload(Type collection, String operation) {
        collection = deref(collection);
        if (collection instanceof ListType list) {
            return futurePayload(list.element(), operation);
        }
        if (collection instanceof Tuple tuple) {
            if (tuple.elements().isEmpty()) return Unknown.INSTANCE;
            Type payload = Unknown.INSTANCE;
            for (Type element : tuple.elements()) {
                Type next = futurePayload(element, operation);
                payload = payload == Unknown.INSTANCE
                        ? next
                        : Types.unionOf(payload, next);
            }
            return payload;
        }
        throw new IllegalArgumentException(
                operation + " expects a list/tuple of Future<T> values");
    }

    private Type futurePayload(Type type, String operation) {
        type = deref(type);
        if (type == Unknown.INSTANCE) return Unknown.INSTANCE;
        if (type instanceof Named named
                && named.name().equals("Future")
                && named.arguments().size() == 1) {
            return named.arguments().getFirst();
        }
        if (type instanceof Union union) {
            Type payload = Unknown.INSTANCE;
            for (Type option : union.options()) {
                Type next = futurePayload(option, operation);
                payload = payload == Unknown.INSTANCE
                        ? next
                        : Types.unionOf(payload, next);
            }
            return payload;
        }
        throw new IllegalArgumentException(
                operation + " expects only Future<T> elements, found " + type);
    }

    private static Type rwReadView(Type element) {
        if (element instanceof Primitive primitive && primitive != Primitive.VOID) {
            return element;
        }
        if (element instanceof StringLiteral) return Primitive.STRING;
        return new Borrow(element, false);
    }

    private Type builtinRwLockMember(Type receiver, String member) {
        if (!(receiver instanceof Named named) || named.arguments().size() != 1) return null;
        Type element = named.arguments().getFirst();

        if (named.name().equals("RwLock")) {
            if (currentActorKind != Ast.ActorKind.NONE
                    && (member.equals("write_lock") || member.equals("try_write_lock"))) {
                throw new IllegalArgumentException(
                        "actors cannot acquire an RwLock write guard; "
                                + "use actor-owned state for writes and RwLock<T> only for external reads");
            }

            Type readGuard = new Named("RwReadGuard", List.of(element));
            Type writeGuard = new Named("RwWriteGuard", List.of(element));
            return switch (member) {
                case "read_lock" -> new Function(List.of(), readGuard);
                case "try_read_lock" -> new Function(
                        List.of(), new Named("Option", List.of(readGuard)));
                case "write_lock" -> new Function(List.of(), writeGuard);
                case "try_write_lock" -> new Function(
                        List.of(), new Named("Option", List.of(writeGuard)));
                default -> null;
            };
        }

        if (named.name().equals("RwReadGuard")) {
            return switch (member) {
                case "value" -> new Function(List.of(), rwReadView(element));
                case "release" -> new Function(List.of(), Primitive.VOID);
                case "is_released" -> new Function(List.of(), Primitive.BOOL);
                default -> null;
            };
        }

        if (named.name().equals("RwWriteGuard")) {
            return switch (member) {
                case "value" -> new Function(List.of(), rwReadView(element));
                case "replace" -> new Function(List.of(element), Primitive.VOID);
                case "release" -> new Function(List.of(), Primitive.VOID);
                case "is_released" -> new Function(List.of(), Primitive.BOOL);
                default -> null;
            };
        }

        return null;
    }

    private Type builtinMutexMember(Type receiver, String member) {
        if (!(receiver instanceof Named named) || named.arguments().size() != 1) return null;
        Type element = named.arguments().getFirst();
        if (named.name().equals("Mutex") || named.name().equals("SharedMutex")) {
            if (named.name().equals("SharedMutex")
                    && currentActorKind != Ast.ActorKind.NONE
                    && (member.equals("lock")
                        || member.equals("try_lock")
                        || member.equals("lock_async")
                        || member.equals("with_lock")
                        || member.equals("recover"))) {
                throw new IllegalArgumentException(
                        "actor code cannot acquire writable SharedMutex state; external shared state is read-only from actor code");
            }
            Type guard = new Named("MutexGuard", List.of(element));
            return switch (member) {
                case "lock" -> new Function(List.of(), guard);
                case "try_lock" -> new Function(List.of(), new Named("Option", List.of(guard)));
                case "lock_async" -> new Function(List.of(), new Named("Future", List.of(guard)));
                case "with_lock" -> new Function(List.of(new Function(List.of(element), Primitive.VOID)), Primitive.VOID);
                case "is_poisoned" -> new Function(List.of(), Primitive.BOOL);
                case "recover" -> named.name().equals("SharedMutex")
                        ? new Function(List.of(new Function(List.of(element), Primitive.VOID)), Primitive.VOID)
                        : null;
                default -> null;
            };
        }
        if (named.name().equals("MutexGuard")) {
            return switch (member) {
                case "release" -> new Function(List.of(), Primitive.VOID);
                case "is_released" -> new Function(List.of(), Primitive.BOOL);
                default -> null;
            };
        }
        return null;
    }

    private Type unwrapMutexGuard(Type type) {
        if (type instanceof Named named && named.name().equals("MutexGuard") && named.arguments().size() == 1) {
            return named.arguments().getFirst();
        }
        return type;
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
                ResolvedMethod iteratorTarget = findMethodTarget(klass, named, "Symbol.iterator", 0, new LinkedHashSet<>());
                if (iteratorTarget != null) {
                    Ast.MethodDecl iterator = iteratorTarget.method();
                    Set<String> iteratorGenerics = new HashSet<>(iteratorTarget.owner().genericParameters());
                    iteratorGenerics.addAll(iterator.genericParameters());
                    Type result = resolve(iterator.returnType(), iteratorGenerics, iteratorTarget.ownerType());
                    result = substituteGenerics(result, classGenericBindings(iteratorTarget.owner(), iteratorTarget.ownerType()));
                    if (result instanceof ListType list) return list.element();
                    if (result instanceof Tuple tuple) return iterableElementType(tuple);
                    throw new IllegalArgumentException("[Symbol.iterator]() must return Array<T>, List<T>, or a tuple in v0");
                }
            }
        }
        throw new IllegalArgumentException("for-of requires an array/list, tuple, or a class with [Symbol.iterator]()");
    }

    private Type widenCollectionElement(Type type) {
        return type instanceof StringLiteral ? Primitive.STRING : type;
    }

    private Type collectionElementJoin(Type a, Type b) {
        if (assignable(b, a) && assignable(a, b)) return a;
        if (Types.isNumeric(a) && Types.isNumeric(b)) return Types.numericJoin(a, b);
        if (isStringLike(a) && isStringLike(b)) return Primitive.STRING;
        return Types.unionOf(a, b);
    }

    private Type commonType(Type a, Type b) {
        if (assignable(b, a) && assignable(a, b)) return a;
        if (Types.isNumeric(a) && Types.isNumeric(b)) return Types.numericJoin(a, b);
        if (isStringLike(a) && isStringLike(b)) return Primitive.STRING;
        return Unknown.INSTANCE;
    }

    private void requireInteger(Type type, String where) {
        if (type != Primitive.INT) throw new IllegalArgumentException(where + " must be an integer type");
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
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                Map<String, Type> parentBindings = parentBindings(parentRef, klass, parent);
                for (Map.Entry<String, Type> inherited : classShape(parent, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), substituteGenerics(inherited.getValue(), parentBindings),
                            "multiple inheritance of " + klass.name());
                }
            }
        }
        Set<String> generics = Set.copyOf(klass.genericParameters());
        Type self = nominalClassType(klass);
        for (Ast.FieldDecl field : klass.fields()) mergeMember(members, field.name(), classFieldType(klass, field), "class " + klass.name());
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic()) continue;
            mergeMember(members,
                    methodContractKey(method.name(), method.arity(), method.genericParameters().size()),
                    callableContractType(method.genericParameters(), method.parameters(), method.returnType(), generics, self),
                    "class " + klass.name());
        }
        stack.remove(klass);
        Record result = new Record(members);
        classShapeCache.put(klass, result);
        return result;
    }

    private Record publicClassShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                Map<String, Type> parentBindings = parentBindings(parentRef, klass, parent);
                for (Map.Entry<String, Type> inherited : publicClassShape(parent, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), substituteGenerics(inherited.getValue(), parentBindings),
                            "public inheritance of " + klass.name());
                }
            }
        }
        Set<String> generics = Set.copyOf(klass.genericParameters());
        Type self = nominalClassType(klass);
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.visibility() == Ast.Visibility.PUBLIC) mergeMember(members, field.name(), classFieldType(klass, field), "class " + klass.name());
        }
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.visibility() == Ast.Visibility.PUBLIC) {
                mergeMember(members,
                        methodContractKey(method.name(), method.arity(), method.genericParameters().size()),
                        callableContractType(method.genericParameters(), method.parameters(), method.returnType(), generics, self),
                        "class " + klass.name());
            }
        }
        stack.remove(klass);
        return new Record(members);
    }

    private Record interfaceShape(Ast.InterfaceDecl iface, Set<String> generics, Set<Ast.InterfaceDecl> stack) {
        if (!stack.add(iface)) throw new IllegalArgumentException("interface inheritance cycle involving '" + iface.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent == null) throw new IllegalArgumentException("unknown parent interface '" + parentRef.name() + "' for " + iface.name());
            Type resolvedParent = resolve(parentRef, generics, null);
            if (!(resolvedParent instanceof Named parentType)) {
                throw new IllegalArgumentException("parent interface must resolve to a named type");
            }
            Map<String, Type> parentBindings = genericBindings(parent.genericParameters(), parentType.arguments(),
                    "interface " + parent.name());
            for (Map.Entry<String, Type> inherited : interfaceShape(parent, Set.copyOf(parent.genericParameters()), stack).members().entrySet()) {
                mergeMember(members, inherited.getKey(), substituteGenerics(inherited.getValue(), parentBindings),
                        "interface inheritance of " + iface.name());
            }
        }
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                mergeMember(members,
                        methodContractKey(fn.name(), fn.parameters().size(), fn.genericParameters().size()),
                        callableContractType(fn.genericParameters(), fn.parameters(), fn.returnType(), generics, null),
                        "interface " + iface.name());
            } else if (member instanceof Ast.InterfaceFieldDecl field) {
                mergeMember(members, field.name(), resolve(field.type(), generics, null), "interface " + iface.name());
            }
        }
        stack.remove(iface);
        return new Record(members);
    }

    private Named inferConstructedClassType(
            Ast.ClassDecl klass,
            List<Ast.Expr> arguments,
            Env env,
            Set<String> callerGenerics,
            Type callerSelf) {
        if (klass.genericParameters().isEmpty()) {
            throw new IllegalArgumentException("class " + klass.name() + " is not generic; remove <>");
        }

        Named patternType = nominalClassType(klass);
        List<ResolvedField> fields = effectiveFieldTargets(klass, patternType, new LinkedHashSet<>());
        if (arguments.size() > fields.size()) {
            throw new IllegalArgumentException("constructor for " + klass.name() + " received too many positional fields");
        }

        Map<String, Type> bindings = new HashMap<>();
        Set<String> classGenericNames = Set.copyOf(klass.genericParameters());
        for (int i = 0; i < arguments.size(); i++) {
            ResolvedField resolvedField = fields.get(i);
            Type fieldPattern = classFieldType(resolvedField.owner(), resolvedField.field());
            fieldPattern = substituteGenerics(
                    fieldPattern,
                    classGenericBindings(resolvedField.owner(), resolvedField.ownerType()));
            Type actual = typeOf(arguments.get(i), env, callerGenerics, callerSelf);
            inferGenericBindings(
                    fieldPattern,
                    actual,
                    bindings,
                    Set.of(),
                    "constructor " + klass.name());
        }

        List<Type> inferred = new ArrayList<>(klass.genericParameters().size());
        for (String generic : klass.genericParameters()) {
            Type bound = bindings.get(generic);
            if (bound == null || containsGenericNamed(bound, classGenericNames)) {
                throw new IllegalArgumentException(
                        "cannot infer class generic '" + generic + "' for constructor "
                                + klass.name() + "<>; provide explicit type arguments");
            }
            inferred.add(bound);
        }
        return new Named(qualifiedClassName(klass), inferred);
    }

    private Map<String, Type> genericBindings(List<String> names, List<Type> arguments, String owner) {
        if (names.size() != arguments.size()) {
            throw new IllegalArgumentException(owner + " expects " + names.size() + " type argument(s), got " + arguments.size());
        }
        Map<String, Type> result = new HashMap<>();
        for (int i = 0; i < names.size(); i++) result.put(names.get(i), arguments.get(i));
        return result;
    }

    private Map<String, Type> parentBindings(Ast.TypeRef parentRef, Ast.ClassDecl child, Ast.ClassDecl parent) {
        Type resolved = resolve(parentRef, Set.copyOf(child.genericParameters()), nominalClassType(child));
        if (!(resolved instanceof Named named)) throw new IllegalArgumentException("parent class must resolve to a named type");
        return genericBindings(parent.genericParameters(), named.arguments(), "class " + parent.name());
    }

    private Named concreteParentType(Ast.TypeRef parentRef, Ast.ClassDecl child, Named childType) {
        Type parentPattern = resolve(parentRef, Set.copyOf(child.genericParameters()), nominalClassType(child));
        Type concrete = substituteGenerics(parentPattern, classGenericBindings(child, childType));
        if (!(concrete instanceof Named named)) throw new IllegalArgumentException("parent class must resolve to a named type");
        return named;
    }

    private Type classFieldType(Ast.ClassDecl klass, Ast.FieldDecl field) {
        Set<String> generics = Set.copyOf(klass.genericParameters());
        Type self = nominalClassType(klass);
        if (field.type() != null) return resolve(field.type(), generics, self);
        if (field.initializer() == null) {
            throw new IllegalArgumentException("inferred field '" + klass.name() + "." + field.name()
                    + "' requires an initializer");
        }
        return typeOf(field.initializer(), new Env(null), generics, self);
    }

    private List<ResolvedField> effectiveFieldTargets(Ast.ClassDecl klass, Named concreteType, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        LinkedHashMap<String, ResolvedField> fields = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Named parentType = concreteParentType(parentRef, klass, concreteType);
            for (ResolvedField field : effectiveFieldTargets(parent, parentType, stack)) fields.putIfAbsent(field.field().name(), field);
        }
        for (Ast.FieldDecl field : klass.fields()) fields.put(field.name(), new ResolvedField(klass, concreteType, field));
        stack.remove(klass);
        return List.copyOf(fields.values());
    }

    private ResolvedField findFieldTarget(Ast.ClassDecl klass, Named concreteType, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                seen.remove(klass);
                return new ResolvedField(klass, concreteType, field);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Named parentType = concreteParentType(parentRef, klass, concreteType);
            ResolvedField result = findFieldTarget(parent, parentType, name, seen);
            if (result != null) {
                seen.remove(klass);
                return result;
            }
        }
        seen.remove(klass);
        return null;
    }

    private ResolvedMethod findMethodTarget(Ast.ClassDecl klass, Named concreteType, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name) && method.arity() == arity) {
                seen.remove(klass);
                return new ResolvedMethod(klass, concreteType, method);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Named parentType = concreteParentType(parentRef, klass, concreteType);
            ResolvedMethod result = findMethodTarget(parent, parentType, name, arity, seen);
            if (result != null) {
                seen.remove(klass);
                return result;
            }
        }
        seen.remove(klass);
        return null;
    }

    private List<Ast.MethodDecl> findMethodsByName(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<Integer, Ast.MethodDecl> methods = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) if (!method.isStatic() && method.name().equals(name)) methods.put(method.arity(), method);
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
        for (Ast.MethodDecl method : klass.methods()) if (method.isStatic() && method.name().equals(name)) functions.put(method.arity(), method);
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl fn : findStaticFunctionsByName(parent, name, seen)) functions.putIfAbsent(fn.arity(), fn);
        }
        seen.remove(klass);
        return List.copyOf(functions.values());
    }

    private Ast.ClassDecl resolveClassParent(Ast.TypeRef parentRef, Ast.ClassDecl child) {
        if (parentRef.name().equals("Object") || parentRef.name().equals("List")) return null;
        if (parentRef.name().equals("obj") || parentRef.name().equals("arr")) throw new IllegalArgumentException("inline obj/arr values cannot be subclassed; extend Object or List instead");
        Ast.ClassDecl parent = findClass(parentRef.name());
        if (parent == null) throw new IllegalArgumentException("unknown parent class '" + parentRef.name() + "' for " + child.name());
        if (parent == child) throw new IllegalArgumentException("class '" + child.name() + "' cannot extend itself");
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

    private Function callableContractType(
            List<String> callableGenerics,
            List<Ast.Param> params,
            Ast.TypeRef returns,
            Set<String> ownerGenerics,
            Type self) {
        Set<String> all = new HashSet<>(ownerGenerics);
        all.addAll(callableGenerics);
        Function raw = functionType(params, returns, all, self);
        if (callableGenerics.isEmpty()) return raw;

        Map<String, Type> canonical = new HashMap<>();
        for (int i = 0; i < callableGenerics.size(); i++) {
            canonical.put(callableGenerics.get(i), new Generic("$callable" + i));
        }
        return (Function) substituteGenerics(raw, canonical);
    }

    private Type resolveParam(Ast.Param param, Set<String> generics, Type self) {
        if (!param.structural()) return resolve(param.type(), generics, self);
        Type concrete = resolve(param.type(), generics, self);
        Ast.InterfaceDecl iface = findInterface(param.type().name());
        if (iface != null) {
            if (!(concrete instanceof Named named)) throw new IllegalArgumentException("@Structural interface must resolve to a named type");
            Type shape = interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
            return substituteGenerics(shape, genericBindings(iface.genericParameters(), named.arguments(), "interface " + iface.name()));
        }
        Ast.ClassDecl klass = findClass(param.type().name());
        if (klass != null) {
            if (!(concrete instanceof Named named)) throw new IllegalArgumentException("@Structural class must resolve to a named type");
            Type shape = publicClassShape(klass, new LinkedHashSet<>());
            return substituteGenerics(shape, classGenericBindings(klass, named));
        }
        throw new IllegalArgumentException("@Structural requires a known class or interface type, got '" + param.type().name() + "'");
    }

    private void rejectStaticClassGenericReferences(
            List<Ast.Stmt> statements,
            Set<String> classGenerics,
            Ast.ClassDecl klass,
            Ast.MethodDecl method) {
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.BindingStmt binding) {
                rejectStaticClassGenericReference(binding.declaredType(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(binding.initializer(), classGenerics, klass, method);
            } else if (statement instanceof Ast.DestructureStmt destructure) {
                rejectStaticClassGenericReferences(destructure.initializer(), classGenerics, klass, method);
            } else if (statement instanceof Ast.ReturnStmt returned) {
                rejectStaticClassGenericReferences(returned.value(), classGenerics, klass, method);
            } else if (statement instanceof Ast.ExprStmt expression) {
                rejectStaticClassGenericReferences(expression.expression(), classGenerics, klass, method);
            } else if (statement instanceof Ast.DeferStmt defer) {
                rejectStaticClassGenericReferences(defer.expression(), classGenerics, klass, method);
            } else if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    rejectStaticClassGenericReferences(branch.condition(), classGenerics, klass, method);
                    rejectStaticClassGenericReferences(branch.body(), classGenerics, klass, method);
                }
                rejectStaticClassGenericReferences(conditional.elseBody(), classGenerics, klass, method);
            } else if (statement instanceof Ast.TryStmt attempted) {
                rejectStaticClassGenericReferences(attempted.body(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(attempted.catchBody(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(attempted.finallyBody(), classGenerics, klass, method);
            } else if (statement instanceof Ast.ForOfStmt loop) {
                rejectStaticClassGenericReferences(loop.iterable(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(loop.body(), classGenerics, klass, method);
            } else if (statement instanceof Ast.ForStmt loop) {
                if (loop.initializer() != null) {
                    rejectStaticClassGenericReferences(List.of(loop.initializer()), classGenerics, klass, method);
                }
                rejectStaticClassGenericReferences(loop.condition(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(loop.update(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(loop.body(), classGenerics, klass, method);
            }
        }
    }

    private void rejectStaticClassGenericReferences(
            Ast.Expr expression,
            Set<String> classGenerics,
            Ast.ClassDecl klass,
            Ast.MethodDecl method) {
        if (expression == null) return;
        if (expression instanceof Ast.AssignExpr assignment) {
            rejectStaticClassGenericReferences(assignment.target(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(assignment.value(), classGenerics, klass, method);
        } else if (expression instanceof Ast.BinaryExpr binary) {
            rejectStaticClassGenericReferences(binary.left(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(binary.right(), classGenerics, klass, method);
        } else if (expression instanceof Ast.UnaryExpr unary) {
            rejectStaticClassGenericReferences(unary.operand(), classGenerics, klass, method);
        } else if (expression instanceof Ast.ConditionalExpr conditional) {
            rejectStaticClassGenericReferences(conditional.condition(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(conditional.whenTrue(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(conditional.whenFalse(), classGenerics, klass, method);
        } else if (expression instanceof Ast.CallExpr call) {
            for (Ast.TypeRef argument : call.typeArguments()) {
                rejectStaticClassGenericReference(argument, classGenerics, klass, method);
            }
            rejectStaticClassGenericReferences(call.callee(), classGenerics, klass, method);
            for (Ast.Expr argument : call.arguments()) {
                rejectStaticClassGenericReferences(argument, classGenerics, klass, method);
            }
        } else if (expression instanceof Ast.MemberExpr member) {
            rejectStaticClassGenericReferences(member.receiver(), classGenerics, klass, method);
        } else if (expression instanceof Ast.IndexExpr indexed) {
            rejectStaticClassGenericReferences(indexed.receiver(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(indexed.index(), classGenerics, klass, method);
        } else if (expression instanceof Ast.NewExpr created) {
            rejectStaticClassGenericReference(created.type(), classGenerics, klass, method);
            for (Ast.Expr argument : created.arguments()) {
                rejectStaticClassGenericReferences(argument, classGenerics, klass, method);
            }
        } else if (expression instanceof Ast.AwaitExpr awaited) {
            rejectStaticClassGenericReferences(awaited.expression(), classGenerics, klass, method);
        } else if (expression instanceof Ast.SpawnExpr spawned) {
            rejectStaticClassGenericReferences(spawned.call(), classGenerics, klass, method);
        } else if (expression instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) {
                rejectStaticClassGenericReferences(item, classGenerics, klass, method);
            }
        } else if (expression instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) {
                rejectStaticClassGenericReferences(item, classGenerics, klass, method);
            }
        } else if (expression instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                rejectStaticClassGenericReferences(field.value(), classGenerics, klass, method);
            }
        } else if (expression instanceof Ast.LambdaExpr lambda) {
            for (Ast.Param param : lambda.parameters()) {
                rejectStaticClassGenericReference(param.type(), classGenerics, klass, method);
            }
            rejectStaticClassGenericReferences(lambda.expressionBody(), classGenerics, klass, method);
            if (lambda.blockBody() != null) {
                rejectStaticClassGenericReferences(lambda.blockBody(), classGenerics, klass, method);
            }
        }
    }

    private void rejectStaticClassGenericReference(
            Ast.TypeRef ref,
            Set<String> classGenerics,
            Ast.ClassDecl klass,
            Ast.MethodDecl method) {
        if (ref == null || classGenerics.isEmpty()) return;
        if (ref.isBorrow()) {
            rejectStaticClassGenericReference(ref.borrowedTarget(), classGenerics, klass, method);
            return;
        }
        if (classGenerics.contains(ref.name())) {
            throw new IllegalArgumentException(
                    "static function " + klass.name() + "." + method.name()
                            + " cannot reference enclosing class generic '" + ref.name()
                            + "'; declare a static-function generic parameter instead");
        }
        for (Ast.TypeRef argument : ref.arguments()) {
            rejectStaticClassGenericReference(argument, classGenerics, klass, method);
        }
    }

    private void validateGenericArity(Ast.TypeRef ref, List<String> parameters, String owner) {
        if (ref.inferArguments()) {
            throw new IllegalArgumentException(owner + " does not permit unresolved <> here; provide explicit type arguments");
        }
        if (ref.arguments().size() != parameters.size()) {
            throw new IllegalArgumentException(owner + " expects " + parameters.size()
                    + " type argument(s), got " + ref.arguments().size());
        }
    }

    private Map<String, Type> classGenericBindings(Ast.ClassDecl klass, Named actual) {
        if (actual.arguments().size() != klass.genericParameters().size()) {
            throw new IllegalArgumentException("class " + klass.name() + " expects " + klass.genericParameters().size()
                    + " type argument(s), got " + actual.arguments().size());
        }
        Map<String, Type> bindings = new HashMap<>();
        for (int i = 0; i < klass.genericParameters().size(); i++) {
            bindings.put(klass.genericParameters().get(i), actual.arguments().get(i));
        }
        return bindings;
    }

    private void validateCallTypeArgumentMarker(Ast.CallExpr call, List<String> genericNames, String label) {
        if (call.typeArgumentsPresent() && genericNames.isEmpty()) {
            throw new IllegalArgumentException(label + " is not generic and cannot be called with <...> or <>");
        }
    }

    private Map<String, Type> explicitGenericBindings(
            List<String> genericNames,
            List<Ast.TypeRef> typeArguments,
            Set<String> callerGenerics,
            Type callerSelf,
            String label) {
        if (typeArguments.isEmpty()) return Map.of();
        if (typeArguments.size() != genericNames.size()) {
            throw new IllegalArgumentException(label + " expects " + genericNames.size()
                    + " explicit type argument(s), got " + typeArguments.size());
        }
        Map<String, Type> bindings = new HashMap<>();
        for (int i = 0; i < genericNames.size(); i++) {
            bindings.put(genericNames.get(i), resolve(typeArguments.get(i), callerGenerics, callerSelf));
        }
        return bindings;
    }

    private Type checkGenericCallable(
            List<String> genericNames,
            List<Ast.Param> params,
            Ast.TypeRef returnRef,
            List<Ast.Expr> arguments,
            Env env,
            Set<String> callerGenerics,
            Type callerSelf,
            Type callableSelf,
            Map<String, Type> initialBindings,
            String label) {
        if (params.size() != arguments.size()) throw new IllegalArgumentException(label + " arity mismatch");
        Set<String> unique = uniqueGenerics(genericNames, label);
        Map<String, Type> bindings = new HashMap<>(initialBindings);
        Set<String> fixedBindings = new HashSet<>(initialBindings.keySet());
        List<Type> patterns = params.stream().map(p -> resolveParam(p, unique, callableSelf)).toList();

        for (int i = 0; i < arguments.size(); i++) {
            Type actual = typeOf(arguments.get(i), env, callerGenerics, callerSelf);
            inferGenericBindings(patterns.get(i), actual, bindings, fixedBindings, label);
        }
        Set<String> unbound = new HashSet<>(unique);
        unbound.removeAll(bindings.keySet());

        for (int i = 0; i < arguments.size(); i++) {
            Type expected = substituteGenerics(patterns.get(i), bindings);
            if (containsGenericNamed(expected, unbound)) {
                throw new IllegalArgumentException("cannot infer all generic parameters for " + label + " from argument " + (i + 1));
            }
            validateLambdaArgument(arguments.get(i), expected, env, callerGenerics, callerSelf);
            requireAssignable(typeOf(arguments.get(i), env, callerGenerics, callerSelf), expected, "argument " + (i + 1));
        }

        Type result = substituteGenerics(resolve(returnRef, unique, callableSelf), bindings);
        if (containsGenericNamed(result, unbound)) {
            throw new IllegalArgumentException("cannot infer generic return type for " + label + "; add an inferable value parameter");
        }
        return result;
    }

    private void inferGenericBindings(Type pattern, Type actual, Map<String, Type> bindings, Set<String> fixedBindings, String label) {
        if (containsUnknown(actual)) return;
        if (pattern instanceof Generic generic) {
            if (fixedBindings.contains(generic.name())) return;
            Type previous = bindings.putIfAbsent(generic.name(), actual);
            if (previous != null) {
                Type joined = commonType(previous, actual);
                if (joined == Unknown.INSTANCE) {
                    throw new IllegalArgumentException("conflicting inference for generic '" + generic.name() + "' in " + label
                            + ": " + previous + " vs " + actual);
                }
                bindings.put(generic.name(), joined);
            }
            return;
        }
        if (pattern instanceof Borrow p && actual instanceof Borrow a) {
            inferGenericBindings(p.target(), a.target(), bindings, fixedBindings, label);
            return;
        }
        if (pattern instanceof ListType p && actual instanceof ListType a) {
            inferGenericBindings(p.element(), a.element(), bindings, fixedBindings, label);
            return;
        }
        if (pattern instanceof Tuple p && actual instanceof Tuple a && p.elements().size() == a.elements().size()) {
            for (int i = 0; i < p.elements().size(); i++) inferGenericBindings(p.elements().get(i), a.elements().get(i), bindings, fixedBindings, label);
            return;
        }
        if (pattern instanceof Named p && actual instanceof Named a
                && p.name().equals(a.name()) && p.arguments().size() == a.arguments().size()) {
            for (int i = 0; i < p.arguments().size(); i++) inferGenericBindings(p.arguments().get(i), a.arguments().get(i), bindings, fixedBindings, label);
            return;
        }
        if (pattern instanceof Function p && actual instanceof Function a
                && p.parameters().size() == a.parameters().size()) {
            for (int i = 0; i < p.parameters().size(); i++) inferGenericBindings(p.parameters().get(i), a.parameters().get(i), bindings, fixedBindings, label);
            inferGenericBindings(p.result(), a.result(), bindings, fixedBindings, label);
        }
    }

    private Type substituteGenerics(Type type, Map<String, Type> bindings) {
        if (type instanceof Generic generic) return bindings.getOrDefault(generic.name(), type);
        if (type instanceof Borrow borrow) return new Borrow(substituteGenerics(borrow.target(), bindings), borrow.mutable());
        if (type instanceof ListType list) return new ListType(substituteGenerics(list.element(), bindings));
        if (type instanceof Tuple tuple) return new Tuple(tuple.elements().stream().map(t -> substituteGenerics(t, bindings)).toList());
        if (type instanceof Union union) return Types.unionOf(union.options().stream().map(t -> substituteGenerics(t, bindings)).toList());
        if (type instanceof Named named) return new Named(named.name(), named.arguments().stream().map(t -> substituteGenerics(t, bindings)).toList());
        if (type instanceof Function fn) return new Function(
                fn.parameters().stream().map(t -> substituteGenerics(t, bindings)).toList(),
                substituteGenerics(fn.result(), bindings));
        if (type instanceof Record record) {
            Map<String, Type> members = new LinkedHashMap<>();
            record.members().forEach((name, member) -> members.put(name, substituteGenerics(member, bindings)));
            return new Record(members);
        }
        return type;
    }

    private boolean containsUnknown(Type type) {
        if (type == Unknown.INSTANCE) return true;
        if (type instanceof Borrow borrow) return containsUnknown(borrow.target());
        if (type instanceof ListType list) return containsUnknown(list.element());
        if (type instanceof Tuple tuple) return tuple.elements().stream().anyMatch(this::containsUnknown);
        if (type instanceof Union union) return union.options().stream().anyMatch(this::containsUnknown);
        if (type instanceof Named named) return named.arguments().stream().anyMatch(this::containsUnknown);
        if (type instanceof Function fn) return fn.parameters().stream().anyMatch(this::containsUnknown)
                || containsUnknown(fn.result());
        if (type instanceof Record record) return record.members().values().stream().anyMatch(this::containsUnknown);
        return false;
    }

    private boolean containsGenericNamed(Type type, Set<String> names) {
        if (names.isEmpty()) return false;
        if (type instanceof Generic generic) return names.contains(generic.name());
        if (type instanceof Borrow borrow) return containsGenericNamed(borrow.target(), names);
        if (type instanceof ListType list) return containsGenericNamed(list.element(), names);
        if (type instanceof Tuple tuple) return tuple.elements().stream().anyMatch(t -> containsGenericNamed(t, names));
        if (type instanceof Union union) return union.options().stream().anyMatch(t -> containsGenericNamed(t, names));
        if (type instanceof Named named) return named.arguments().stream().anyMatch(t -> containsGenericNamed(t, names));
        if (type instanceof Function fn) return fn.parameters().stream().anyMatch(t -> containsGenericNamed(t, names))
                || containsGenericNamed(fn.result(), names);
        if (type instanceof Record record) return record.members().values().stream().anyMatch(t -> containsGenericNamed(t, names));
        return false;
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
        if (ref.isUnion()) {
            return Types.unionOf(ref.arguments().stream().map(option -> resolve(option, generics, self, allowNullMarker)).toList());
        }
        if (ref.isTupleType()) {
            return new Tuple(ref.arguments().stream().map(element -> resolve(element, generics, self, allowNullMarker)).toList());
        }
        if (ref.isRecordType()) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Map.Entry<String, Ast.TypeRef> member : ref.recordMembers().entrySet()) {
                members.put(member.getKey(), resolve(member.getValue(), generics, self, allowNullMarker));
            }
            return new Record(members);
        }
        if (generics.contains(ref.name())) return new Generic(ref.name());

        Ast.TypeAliasDecl alias = findTypeAlias(ref.name());
        if (alias != null) {
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

        return switch (ref.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "int", "uint", "bigint" -> Primitive.INT;
            case "f32", "f64", "float" -> Primitive.FLOAT;
            case "decimal" -> Primitive.DECIMAL;
            case "complex64", "complex128", "complex" -> Primitive.COMPLEX;
            case "bool", "Bool" -> Primitive.BOOL;
            case "string", "String" -> Primitive.STRING;
            case "void" -> Primitive.VOID;
            case "Array", "List" -> {
                if (!ref.inferArguments() && ref.arguments().size() != 1) throw new IllegalArgumentException(ref.name() + " requires exactly one type argument");
                yield new ListType(ref.arguments().isEmpty() ? Unknown.INSTANCE : resolve(ref.arguments().getFirst(), generics, self));
            }
            case "Option" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Option requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self, true);
                if (element == Primitive.VOID) throw new IllegalArgumentException("Option<void> is invalid; use void for no return value");
                yield new Named("Option", List.of(element));
            }
            case "Result" -> {
                if (ref.inferArguments() || ref.arguments().size() != 2) {
                    throw new IllegalArgumentException("Result requires exactly two explicit type arguments");
                }
                yield new Named("Result", List.of(
                        resolve(ref.arguments().get(0), generics, self),
                        resolve(ref.arguments().get(1), generics, self)));
            }
            case "MutexGuard" -> throw new IllegalArgumentException(
                    "MutexGuard<T> is compiler-managed and cannot be named in source declarations; acquire it from lock()/try_lock()/lock_async()");
            case "RwReadGuard", "RwWriteGuard" -> throw new IllegalArgumentException(
                    ref.name() + "<T> is compiler-managed and cannot be named in source declarations; acquire it from RwLock<T>");
            case "Mutex" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Mutex requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                if (element == Primitive.VOID) throw new IllegalArgumentException("Mutex<void> is invalid");
                if (element instanceof Borrow) {
                    throw new IllegalArgumentException("Mutex<T> requires an owned value type; borrowed payload types are invalid");
                }
                yield new Named("Mutex", List.of(element));
            }
            case "Future" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Future requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                yield new Named("Future", List.of(element));
            }
            case "ActorRef" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) {
                    throw new IllegalArgumentException("ActorRef requires exactly one explicit actor/protocol type argument");
                }
                Type protocol = resolve(ref.arguments().getFirst(), generics, self);
                if (!(protocol instanceof Named namedProtocol)) {
                    throw new IllegalArgumentException("ActorRef protocol must resolve to a named actor/interface type");
                }
                Ast.ClassDecl actorClass = findClass(namedProtocol.name());
                Ast.InterfaceDecl actorInterface = findInterface(namedProtocol.name());
                if ((actorClass == null || actorClass.actorKind() == Ast.ActorKind.NONE)
                        && actorInterface == null) {
                    throw new IllegalArgumentException(
                            "ActorRef protocol must name an actor class or interface, got " + namedProtocol.name());
                }
                yield new Named("ActorRef", List.of(protocol));
            }
            case "SharedMutex" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("SharedMutex requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                if (element == Primitive.VOID) throw new IllegalArgumentException("SharedMutex<void> is invalid");
                if (!isSharedSafe(element, new LinkedHashSet<>(), Map.of())) {
                    throw new IllegalArgumentException(
                            "SharedMutex<T> requires a concrete shared-safe type; borrows, Mutex, MutexGuard, Future, closures, and unresolved generic/dynamic values are not shareable");
                }
                yield new Named("SharedMutex", List.of(element));
            }
            case "RwLock" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) {
                    throw new IllegalArgumentException("RwLock requires exactly one explicit type argument");
                }
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                if (element == Primitive.VOID) throw new IllegalArgumentException("RwLock<void> is invalid");
                if (!isSharedSafe(element, new LinkedHashSet<>(), Map.of())) {
                    throw new IllegalArgumentException(
                            "RwLock<T> requires a concrete shared-safe type");
                }
                yield new Named("RwLock", List.of(element));
            }
            case "Fnc" -> {
                List<Type> args = ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList();
                if (args.isEmpty()) throw new IllegalArgumentException("Fnc requires at least a result type");
                yield new Function(args.subList(0, args.size() - 1), args.getLast());
            }
            default -> {
                Ast.ClassDecl knownClass = findClass(ref.name());
                if (knownClass != null) {
                    validateGenericArity(ref, knownClass.genericParameters(), "class " + knownClass.name());
                    yield new Named(qualifiedClassName(knownClass),
                            ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
                }
                Ast.InterfaceDecl knownInterface = findInterface(ref.name());
                if (knownInterface != null) {
                    validateGenericArity(ref, knownInterface.genericParameters(), "interface " + knownInterface.name());
                    yield new Named(qualifiedInterfaceName(knownInterface),
                            ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
                }
                if (ref.inferArguments()) {
                    throw new IllegalArgumentException("cannot infer type arguments for unknown type '" + ref.name() + "<>'");
                }
                yield new Named(ref.name(), ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
            }
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

    private Named nominalClassType(Ast.ClassDecl klass) {
        return new Named(qualifiedClassName(klass), klass.genericParameters().stream().map(Generic::new).map(Type.class::cast).toList());
    }

    private String qualifiedClassName(Ast.ClassDecl klass) {
        String owner = classOwners.get(klass);
        return owner == null || owner.equals("__root__") ? klass.name() : owner + "." + klass.name();
    }

    private String qualifiedInterfaceName(Ast.InterfaceDecl iface) {
        String owner = interfaceOwners.get(iface);
        return owner == null || owner.equals("__root__") ? iface.name() : owner + "." + iface.name();
    }

    private boolean assignable(Type actual, Type expected) {
        if (expected instanceof Generic expectedGeneric) {
            return actual instanceof Generic actualGeneric
                    && actualGeneric.name().equals(expectedGeneric.name());
        }
        if (actual instanceof Generic actualGeneric) {
            return expected instanceof Generic expectedGeneric
                    && actualGeneric.name().equals(expectedGeneric.name());
        }
        if (actual instanceof Union source) {
            return source.options().stream().allMatch(option -> assignable(option, expected));
        }
        if (expected instanceof Union target) {
            return target.options().stream().anyMatch(option -> assignable(actual, option));
        }
        if (Types.isAssignable(actual, expected)) return true;

        if (actual instanceof Named actualNamed && expected instanceof Record targetShape) {
            Ast.ClassDecl klass = findClass(actualNamed.name());
            if (klass == null) return false;
            Type specializedShape = substituteGenerics(
                    publicClassShape(klass, new LinkedHashSet<>()),
                    classGenericBindings(klass, actualNamed));
            return Types.isAssignable(specializedShape, targetShape);
        }

        if (actual instanceof Named actualNamed && expected instanceof Named expectedNamed) {
            if (actualNamed.name().equals("ActorRef")
                    && expectedNamed.name().equals("ActorRef")
                    && actualNamed.arguments().size() == 1
                    && expectedNamed.arguments().size() == 1
                    && actualNamed.arguments().getFirst() instanceof Named actualProtocol
                    && expectedNamed.arguments().getFirst() instanceof Named expectedProtocol) {
                if (Types.isAssignable(actualProtocol, expectedProtocol)) return true;
                Ast.ClassDecl concreteActor = findClass(actualProtocol.name());
                if (concreteActor != null
                        && concreteActor.actorKind() != Ast.ActorKind.NONE
                        && findInterface(expectedProtocol.name()) != null
                        && classTypeImplements(
                                actualProtocol,
                                expectedProtocol,
                                new LinkedHashSet<>())) {
                    return true;
                }
                if (findInterface(actualProtocol.name()) != null
                        && findInterface(expectedProtocol.name()) != null
                        && interfaceTypeExtends(
                                actualProtocol,
                                expectedProtocol,
                                new LinkedHashSet<>())) {
                    return true;
                }
            }
            if (findClass(actualNamed.name()) != null) {
                if (findClass(expectedNamed.name()) != null
                        && classTypeExtends(actualNamed, expectedNamed, new LinkedHashSet<>())) {
                    return true;
                }
                if (findInterface(expectedNamed.name()) != null
                        && classTypeImplements(actualNamed, expectedNamed, new LinkedHashSet<>())) {
                    return true;
                }
            }
            if (findInterface(actualNamed.name()) != null
                    && findInterface(expectedNamed.name()) != null
                    && interfaceTypeExtends(actualNamed, expectedNamed, new LinkedHashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private Named concreteClassReference(Ast.TypeRef ref, Ast.ClassDecl owner, Named ownerType) {
        Type pattern = resolve(ref, Set.copyOf(owner.genericParameters()), nominalClassType(owner));
        Type concrete = substituteGenerics(pattern, classGenericBindings(owner, ownerType));
        if (!(concrete instanceof Named named)) {
            throw new IllegalArgumentException("class relationship must resolve to a named type");
        }
        return named;
    }

    private boolean classTypeExtends(Named actualType, Named expectedType, Set<Named> seen) {
        if (Types.isAssignable(actualType, expectedType)) return true;
        if (!seen.add(actualType)) return false;

        Ast.ClassDecl actual = findClass(actualType.name());
        if (actual == null) return false;
        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, actual);
            if (parent == null) continue;
            Named parentType = concreteClassReference(parentRef, actual, actualType);
            if (Types.isAssignable(parentType, expectedType)
                    || classTypeExtends(parentType, expectedType, seen)) {
                return true;
            }
        }
        return false;
    }

    private boolean classTypeImplements(Named actualType, Named expectedInterfaceType, Set<Named> seen) {
        if (!seen.add(actualType)) return false;
        Ast.ClassDecl actual = findClass(actualType.name());
        if (actual == null) return false;

        for (Ast.TypeRef ifaceRef : actual.interfaces()) {
            Named ifaceType = concreteClassReference(ifaceRef, actual, actualType);
            if (interfaceTypeExtends(ifaceType, expectedInterfaceType, new LinkedHashSet<>())) {
                return true;
            }
        }

        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, actual);
            if (parent == null) continue;
            Named parentType = concreteClassReference(parentRef, actual, actualType);
            if (classTypeImplements(parentType, expectedInterfaceType, seen)) return true;
        }
        return false;
    }

    private boolean interfaceTypeExtends(Named actualType, Named expectedType, Set<Named> seen) {
        if (Types.isAssignable(actualType, expectedType)) return true;
        if (!seen.add(actualType)) return false;

        Ast.InterfaceDecl actual = findInterface(actualType.name());
        if (actual == null) return false;
        Map<String, Type> bindings = genericBindings(
                actual.genericParameters(),
                actualType.arguments(),
                "interface " + actual.name());
        Set<String> generics = Set.copyOf(actual.genericParameters());

        for (Ast.TypeRef parentRef : actual.parents()) {
            Type pattern = resolve(parentRef, generics, null);
            Type concrete = substituteGenerics(pattern, bindings);
            if (!(concrete instanceof Named parentType)) {
                throw new IllegalArgumentException("interface relationship must resolve to a named type");
            }
            if (Types.isAssignable(parentType, expectedType)
                    || interfaceTypeExtends(parentType, expectedType, seen)) {
                return true;
            }
        }
        return false;
    }

    private Ast.FunctionDecl findFunction(String name) {
        if (ambiguousFunctions.contains(name)) throw new IllegalArgumentException("ambiguous function/routine name '" + name + "'; qualify it with its module");
        return functions.get(name);
    }

    private Ast.ClassDecl findClass(String name) {
        if (ambiguousClasses.contains(name)) throw new IllegalArgumentException("ambiguous class name '" + name + "'; qualify it with its module");
        return classes.get(name);
    }

    private Ast.InterfaceDecl findInterface(String name) {
        if (ambiguousInterfaces.contains(name)) throw new IllegalArgumentException("ambiguous interface name '" + name + "'; qualify it with its module");
        return interfaces.get(name);
    }

    private Ast.TypeAliasDecl findTypeAlias(String name) {
        if (ambiguousTypeAliases.contains(name)) throw new IllegalArgumentException("ambiguous type alias '" + name + "'; qualify it with its module");
        return typeAliases.get(name);
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

    private boolean hasInferredArgs(Ast.TypeRef ref) {
        return ref.inferArguments() || ref.arguments().stream().anyMatch(this::hasInferredArgs);
    }

    private void requireAssignable(Type actual, Type expected, String where) {
        if (!assignable(actual, expected)) throw new IllegalArgumentException(where + " has type " + actual + " but expected " + expected);
    }

    private String methodKey(String name, int arity) {
        return name + "$arity" + arity;
    }

    private String methodContractKey(String name, int arity, int genericArity) {
        return methodKey(name, arity) + "$generics" + genericArity;
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
        else if (expr instanceof Ast.AwaitExpr e) collectCalls(e.expression(), module, out);
        else if (expr instanceof Ast.SpawnExpr e) collectCalls(e.call(), module, out);
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
        private final boolean descendantsNonLexical;
        private final Map<String, Binding> bindings = new HashMap<>();
        private Env(Env parent) { this(parent, parent != null && parent.descendantsNonLexical); }
        private Env(Env parent, boolean descendantsNonLexical) {
            this.parent = parent;
            this.descendantsNonLexical = descendantsNonLexical;
        }
        private boolean descendantsNonLexical() { return descendantsNonLexical; }
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
