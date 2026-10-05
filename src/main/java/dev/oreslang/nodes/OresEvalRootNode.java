package dev.oreslang.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.ast.CallableSelector;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresMutex;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.AsyncRuntime;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresFutures;
import dev.oreslang.runtime.GeneratorRuntime;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Executable Truffle root. Parsing and static checks happen before this node is created. */
public final class OresEvalRootNode extends RootNode {
    public static final String LINK_ONLY_COMMAND = "__ores_internal_link_only__";
    public static final String INIT_ONLY_COMMAND = "__ores_internal_init_only__";
    public static final String MAIN_ONLY_COMMAND = "__ores_internal_main_only__";
    public static final String INVOKE_PUBLIC_COMMAND = "__ores_internal_invoke_public__";
    public static final String REGISTER_IMPORT_COMMAND = "__ores_internal_register_import__";

    private final Ast.Program program;
    private final String codeUnitId;
    private volatile Evaluator evaluator;

    public OresEvalRootNode(OresLanguage language, Ast.Program program) {
        this(language, program, "<anonymous>");
    }

    public OresEvalRootNode(OresLanguage language, Ast.Program program, String codeUnitId) {
        super(language);
        this.program = program;
        this.codeUnitId = codeUnitId;
    }

    @Override public String getName() { return "ores-eval"; }
    @Override public boolean isInternal() { return true; }

    @Override
    public Object execute(VirtualFrame frame) {
        return executeBoundary(OresContext.get(this), frame.getArguments());
    }

    @TruffleBoundary
    private Object executeBoundary(OresContext context, Object[] arguments) {
        CapabilityChecker.check(program, context.isolatePolicy());
        if (arguments.length == 3
                && REGISTER_IMPORT_COMMAND.equals(arguments[0])
                && arguments[1] instanceof String importPath
                && arguments[2] instanceof String targetCodeUnitId) {
            context.registerLinkedImportResolution(codeUnitId, importPath, targetCodeUnitId);
            return null;
        }

        Evaluator current = evaluator(context);
        if (isControl(arguments, LINK_ONLY_COMMAND)) {
            current.link();
            return null;
        }
        if (isControl(arguments, INIT_ONLY_COMMAND)) {
            current.link();
            return current.initialize();
        }
        if (isControl(arguments, MAIN_ONLY_COMMAND)) {
            current.link();
            return current.executeMain(new Object[0]);
        }
        if (arguments.length >= 2
                && INVOKE_PUBLIC_COMMAND.equals(arguments[0])
                && arguments[1] instanceof String functionName) {
            current.link();
            return current.invokePublic(functionName, java.util.Arrays.copyOfRange(arguments, 2, arguments.length));
        }

        // Backward-compatible single-source execution. Multi-file hosts use
        // link/init/main commands to install a full import graph before init.
        current.link();
        current.initialize();
        return current.executeMain(arguments);
    }

    private Evaluator evaluator(OresContext context) {
        Evaluator current = evaluator;
        if (current != null) return current;
        synchronized (this) {
            current = evaluator;
            if (current == null) evaluator = current = new Evaluator(program, context, codeUnitId);
            return current;
        }
    }

    private static boolean isControl(Object[] arguments, String command) {
        return arguments.length == 1 && command.equals(arguments[0]);
    }

    private static final class Evaluator {
        private final Ast.Program program;
        private final OresContext context;
        private final String codeUnitId;
        private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
        private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
        private final Map<String, Ast.InterfaceDecl> interfaces = new HashMap<>();
        private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
        private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();
        private final IdentityHashMap<Ast.MethodDecl, Ast.ClassDecl> methodOwners = new IdentityHashMap<>();
        private final Map<String, ImportedBinding> namedImports = new HashMap<>();
        private final Map<String, Ast.ImportDecl> namespaceImports = new HashMap<>();
        private final Map<String, HostClassFacade> hostClasses = new HashMap<>();
        private final Map<String, Invokable> hostFunctions = new HashMap<>();
        private final Map<String, HostClassFacade> hostSymbols = new HashMap<>();
        private final Set<String> ambiguousFunctions = new LinkedHashSet<>();
        private final Set<String> ambiguousClasses = new LinkedHashSet<>();
        private final Set<String> ambiguousInterfaces = new LinkedHashSet<>();
        private final Set<String> ambiguousTypeAliases = new LinkedHashSet<>();
        private StartupPhase startupPhase = StartupPhase.CREATED;
        private final ThreadLocal<GeneratorRuntime.Emitter<Object>> activeGeneratorEmitter = new ThreadLocal<>();
        private static final int TAIL_SAFEPOINT_INTERVAL = 64;

        private enum InvocationKind {
            FUNCTION,
            FUNCTION_BODY,
            METHOD,
            STATIC_FUNCTION,
            STATIC_FUNCTION_BODY,
            INVOKABLE
        }

        private record Invocation(
                Evaluator owner,
                InvocationKind kind,
                Object receiver,
                Object target,
                List<Object> arguments) { }

        private record TailCall(Invocation invocation) { }

        private static final class TailCallSignal extends RuntimeException {
            private final Invocation invocation;
            private TailCallSignal(Invocation invocation) {
                super(null, null, false, false);
                this.invocation = invocation;
            }
        }


        private Evaluator(Ast.Program program, OresContext context, String codeUnitId) {
            this.program = program;
            this.context = context;
            this.codeUnitId = normalizeUnitId(codeUnitId);
            indexImports();
            indexDeclarations();
        }

        private void indexImports() {
            for (Ast.ImportDecl imported : program.imports()) {
                ImportRules.validate(imported);
                if (ImportRules.isJavaPath(imported.path())) {
                    indexHostImport(imported);
                    continue;
                }

                if (imported.wildcard()) {
                    namespaceImports.put(imported.namespace(), imported);
                } else {
                    for (String sourceName : imported.names()) {
                        String localName = ImportRules.localName(imported, sourceName);
                        ImportedBinding previous = namedImports.putIfAbsent(
                                localName,
                                new ImportedBinding(imported, sourceName));
                        if (previous != null) {
                            throw new IllegalArgumentException("duplicate import binding " + localName);
                        }
                    }
                }
            }
        }

        private void indexHostImport(Ast.ImportDecl imported) {
            String className = ImportRules.javaClassName(imported.path());
            HostClassFacade symbol = hostSymbols.computeIfAbsent(
                    className,
                    ignored -> new HostClassFacade(className, context.lookupHostSymbol(className), true));

            if (imported.kind() == Ast.ImportKind.CLASS) {
                String sourceName = imported.names().getFirst();
                putHostClass(ImportRules.localName(imported, sourceName), symbol);
                return;
            }

            if (imported.kind() == Ast.ImportKind.FUNCTION && imported.wildcard()) {
                putHostClass(imported.namespace(), new HostClassFacade(className, symbol.symbol(), false));
                return;
            }

            if (imported.kind() == Ast.ImportKind.FUNCTION) {
                InteropLibrary interop = InteropLibrary.getUncached(symbol.symbol());
                for (String sourceName : imported.names()) {
                    if (!interop.isMemberInvocable(symbol.symbol(), sourceName)) {
                        throw new IllegalArgumentException("Java host class '" + className
                                + "' does not export invocable static member '" + sourceName + "'");
                    }
                    String localName = ImportRules.localName(imported, sourceName);
                    putHostFunction(localName, args -> {
                        context.requireCapability(
                                IsolatePolicy.Capability.JAVA_INTEROP,
                                "Java host method " + className + "." + sourceName);
                        return invokeHostMember(symbol.symbol(), sourceName, args);
                    });
                }
                return;
            }

            if (imported.kind() == Ast.ImportKind.ALL) {
                putHostClass(imported.namespace(), symbol);
                return;
            }

            throw new IllegalArgumentException("unsupported Java host import kind: " + imported.kind());
        }

        private void putHostClass(String name, HostClassFacade value) {
            if (hostClasses.putIfAbsent(name, value) != null || hostFunctions.containsKey(name)) {
                throw new IllegalArgumentException("duplicate Java host import binding " + name);
            }
        }

        private void putHostFunction(String name, Invokable value) {
            if (hostFunctions.putIfAbsent(name, value) != null || hostClasses.containsKey(name)) {
                throw new IllegalArgumentException("duplicate Java host import binding " + name);
            }
        }

        private void indexDeclarations() {
            for (Ast.ModuleDecl module : program.modules()) {
                modules.put(module.name(), module);
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                    else if (decl instanceof Ast.ClassDecl klass) {
                        index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                        for (Ast.MethodDecl method : klass.methods()) methodOwners.put(method, klass);
                    } else if (decl instanceof Ast.InterfaceDecl iface) {
                        index(interfaces, ambiguousInterfaces, module.name(), iface.name(), iface);
                    } else if (decl instanceof Ast.TypeAliasDecl alias) index(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias);
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

        private Ast.FunctionDecl findFunction(String name) {
            if (ambiguousFunctions.contains(name)) throw new IllegalArgumentException("ambiguous function " + name + "; qualify it with its module");
            return functions.get(name);
        }

        private Ast.ClassDecl findClass(String name) {
            if (ambiguousClasses.contains(name)) throw new IllegalArgumentException("ambiguous class " + name + "; qualify it with its module");
            return classes.get(name);
        }

        private Ast.InterfaceDecl findInterface(String name) {
            if (ambiguousInterfaces.contains(name)) throw new IllegalArgumentException("ambiguous interface " + name + "; qualify it with its module");
            return interfaces.get(name);
        }

        private Ast.TypeAliasDecl findTypeAlias(String name) {
            if (ambiguousTypeAliases.contains(name)) throw new IllegalArgumentException("ambiguous type alias " + name + "; qualify it with its module");
            return typeAliases.get(name);
        }

        private synchronized void link() {
            if (startupPhase == StartupPhase.FAILED) {
                throw new IllegalStateException("cannot relink failed code unit " + codeUnitId);
            }
            context.registerLinkedCodeUnit(codeUnitId, this);
            if (startupPhase == StartupPhase.CREATED) startupPhase = StartupPhase.LINKED;
        }

        private synchronized Object initialize() {
            if (startupPhase == StartupPhase.READY) return null;
            if (startupPhase == StartupPhase.INITIALIZING) {
                throw new IllegalStateException("recursive initialization of code unit " + codeUnitId);
            }
            if (startupPhase == StartupPhase.FAILED) {
                throw new IllegalStateException("initialization previously failed for code unit " + codeUnitId);
            }
            if (startupPhase == StartupPhase.CREATED) link();

            startupPhase = StartupPhase.INITIALIZING;
            Object last = null;
            try {
                for (Ast.ModuleDecl module : program.modules()) {
                    for (Ast.Decl decl : module.declarations()) {
                        if (decl instanceof Ast.FunctionDecl fn && fn.name().equals("init")) {
                            last = invoke(functionBodyInvocation(fn, List.of()));
                        }
                    }
                }
                startupPhase = StartupPhase.READY;
                return last;
            } catch (RuntimeException | Error failure) {
                startupPhase = StartupPhase.FAILED;
                throw failure;
            }
        }

        private Object executeMain(Object[] arguments) {
            if (startupPhase != StartupPhase.READY) {
                throw new IllegalStateException(
                        "main cannot run before successful initialization of code unit "
                                + codeUnitId + "; current phase=" + startupPhase);
            }
            Ast.FunctionDecl main = functions.get(Parser.ROOT_MODULE + ".main");
            if (main == null) main = findFunction("main");
            if (main == null) return null;
            Object result = callFunction(main, List.of(arguments));
            if (result instanceof OresFuture<?> future) {
                return AsyncRuntime.await(future);
            }
            if (result instanceof CompletionStage<?> stage) {
                return AsyncRuntime.await(stage);
            }
            return result;
        }

        private Object invokePublic(String name, Object[] arguments) {
            Ast.FunctionDecl fn = findFunction(name);
            if (fn == null || fn.visibility() != Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException("code unit '" + codeUnitId
                        + "' does not export public function '" + name + "'");
            }
            Object result = callFunction(fn, java.util.Arrays.asList(arguments));
            return result instanceof HostObjectFacade host ? host.value() : result;
        }

        private Object invoke(Invocation initial) {
            Invocation current = initial;
            int tailHops = 0;
            while (true) {
                Object result = current.owner().executeRaw(current);
                if (!(result instanceof TailCall tail)) return result;
                current = tail.invocation();
                tailHops++;
                if (tailHops % TAIL_SAFEPOINT_INTERVAL == 0) {
                    current.owner().context.schedulerSafepoint();
                }
            }
        }

        private Object executeRaw(Invocation invocation) {
            return switch (invocation.kind()) {
                case FUNCTION -> callFunctionRaw(
                        (Ast.FunctionDecl) invocation.target(),
                        invocation.arguments());
                case FUNCTION_BODY -> callFunctionBodyRaw(
                        (Ast.FunctionDecl) invocation.target(),
                        invocation.arguments());
                case METHOD -> callMethodRaw(
                        (OresObject) invocation.receiver(),
                        (Ast.MethodDecl) invocation.target(),
                        invocation.arguments());
                case STATIC_FUNCTION -> callStaticFunctionRaw(
                        (Ast.MethodDecl) invocation.target(),
                        invocation.arguments());
                case STATIC_FUNCTION_BODY -> callStaticFunctionBodyRaw(
                        (Ast.MethodDecl) invocation.target(),
                        invocation.arguments());
                case INVOKABLE -> ((Invokable) invocation.target()).call(invocation.arguments());
            };
        }

        private Invocation functionInvocation(Ast.FunctionDecl fn, List<?> args) {
            return new Invocation(this, InvocationKind.FUNCTION, null, fn, objectArguments(args));
        }

        private Invocation functionBodyInvocation(Ast.FunctionDecl fn, List<?> args) {
            return new Invocation(this, InvocationKind.FUNCTION_BODY, null, fn, objectArguments(args));
        }

        private Invocation methodInvocation(OresObject receiver, Ast.MethodDecl method, List<?> args) {
            return new Invocation(this, InvocationKind.METHOD, receiver, method, objectArguments(args));
        }

        private Invocation staticFunctionInvocation(Ast.MethodDecl fn, List<?> args) {
            return new Invocation(this, InvocationKind.STATIC_FUNCTION, null, fn, objectArguments(args));
        }

        private Invocation staticFunctionBodyInvocation(Ast.MethodDecl fn, List<?> args) {
            return new Invocation(this, InvocationKind.STATIC_FUNCTION_BODY, null, fn, objectArguments(args));
        }

        private Invocation invokableInvocation(Invokable callable, List<?> args) {
            // Extracted method/static values must enter the same trampoline
            // slots as direct calls, not recursively start nested trampolines.
            if (callable instanceof BoundMethod bound) {
                return bound.receiver.owner.prepareBoundMethodInvocation(
                        bound.receiver, bound.methodName, objectArguments(args), bound.accessClass);
            }
            if (callable instanceof StaticFunctionValue function) {
                return function.owner.prepareStaticValueInvocation(
                        function.klass, function.functionName, objectArguments(args), function.accessClass);
            }
            return new Invocation(this, InvocationKind.INVOKABLE, null, callable, objectArguments(args));
        }

        private TailInvokable tailCallable(Invokable callable) {
            return new TailCallable(this, callable);
        }

        @SuppressWarnings("unchecked")
        private static List<Object> objectArguments(List<?> args) {
            return (List<Object>) args;
        }

        private Object callFunction(Ast.FunctionDecl fn, List<?> args) {
            return invoke(functionInvocation(fn, args));
        }

        private Object callFunctionRaw(Ast.FunctionDecl fn, List<Object> args) {
            if (fn.name().equals("init")) {
                throw new IllegalStateException(
                        "init is a lifecycle hook and cannot be invoked directly; startup runs it exactly once");
            }
            List<Object> normalized = normalizeFunctionArguments(fn, args);
            if (fn.generator()) {
                if (fn.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalStateException(
                            "actor callables cannot expose generator activations across mailbox turns");
                }

                List<?> captured = fn.async()
                        ? detachAsyncArguments(normalized)
                        : List.copyOf(normalized);
                GeneratorRuntime.Producer<Object> producer = emitter -> {
                    GeneratorRuntime.Emitter<Object> boundaryEmitter = fn.async()
                            ? value -> emitter.emit(detachAsyncValue(value, new IdentityHashMap<>()))
                            : emitter;
                    callGeneratorBodyRaw(fn, captured, boundaryEmitter);
                };
                return fn.async()
                        ? GeneratorRuntime.asyncGenerator(context.asyncRuntime(), producer)
                        : GeneratorRuntime.generator(context.asyncRuntime(), producer);
            }

            if (fn.actorKind() == Ast.ActorKind.NONE) {
                if (!fn.async()) return callFunctionBodyRaw(fn, normalized);

                List<?> detached = detachAsyncArguments(normalized);
                return context.asyncRuntime().submit(() ->
                        detachAsyncValue(
                                invoke(functionBodyInvocation(fn, detached)),
                                new IdentityHashMap<>()));
            }

            if (fn.async()) {
                throw new IllegalStateException(
                        "async actor callables require mailbox continuation lowering and are not executed synchronously");
            }

            if (ActorRuntime.inActorExecution()) {
                throw new IllegalArgumentException(
                        "actor callable '" + fn.name()
                                + "' cannot be synchronously invoked from another actor turn; "
                                + "use mailbox-oriented actor composition");
            }

            ActorRuntime.ActorKind runtimeKind = switch (fn.actorKind()) {
                case NONE -> throw new AssertionError("non-actor callable reached actor lowering");
                case PRIVATE -> ActorRuntime.ActorKind.PRIVATE;
                case SHARED -> ActorRuntime.ActorKind.SHARED;
            };

            return context.actors().invoke(
                    runtimeKind,
                    normalized,
                    (delivered, actorContext) ->
                            invoke(functionBodyInvocation(fn, delivered)));
        }

        private List<Object> normalizeFunctionArguments(Ast.FunctionDecl fn, List<?> args) {
            if (args.size() != fn.parameters().size()) {
                if (fn.parameters().isEmpty()
                        && args.size() == 1
                        && args.getFirst() instanceof Object[] array
                        && array.length == 0) {
                    return List.of();
                }
                throw new IllegalArgumentException(
                        "function " + fn.name() + " expects " + fn.parameters().size()
                                + " arguments, got " + args.size());
            }
            return objectArguments(args);
        }

        private List<?> detachAsyncArguments(List<?> args) {
            ArrayList<Object> detached = new ArrayList<>(args.size());
            for (Object arg : args) {
                detached.add(detachAsyncValue(arg, new IdentityHashMap<>()));
            }
            return List.copyOf(detached);
        }

        private Object detachAsyncValue(
                Object value,
                IdentityHashMap<Object, Boolean> visiting) {
            if (value == null
                    || value instanceof String
                    || value instanceof Boolean
                    || value instanceof Character
                    || value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof Float
                    || value instanceof Double
                    || value instanceof java.math.BigInteger
                    || value instanceof java.math.BigDecimal
                    || value instanceof Enum<?>
                    || value instanceof java.util.UUID
                    || value instanceof Complex
                    || value instanceof OptionUnwrapError) {
                return value;
            }

            if (visiting.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException(
                        "cyclic mutable values cannot cross an async task boundary");
            }
            try {
                if (value instanceof OresObject object) {
                    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : object.fields.entrySet()) {
                        fields.put(entry.getKey(), detachAsyncValue(entry.getValue(), visiting));
                    }
                    return new OresObject(object.owner, object.klass, fields);
                }
                if (value instanceof DynamicStructValue dynamic) {
                    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : dynamic.fields.entrySet()) {
                        fields.put(entry.getKey(), detachAsyncValue(entry.getValue(), visiting));
                    }
                    return new DynamicStructValue(fields);
                }
                if (value instanceof OptionValue option) {
                    return option.present()
                            ? new OptionValue(true, detachAsyncValue(option.value(), visiting))
                            : option;
                }
                if (value instanceof ResultValue result) {
                    return new ResultValue(result.ok(), detachAsyncValue(result.value(), visiting));
                }
                if (value instanceof GeneratorRuntime.Step<?> step) {
                    return step.done() ? GeneratorRuntime.Step.doneStep()
                            : GeneratorRuntime.Step.yielded(detachAsyncValue(step.value(), visiting));
                }
                if (value instanceof List<?> list) {
                    ArrayList<Object> copy = new ArrayList<>(list.size());
                    for (Object item : list) copy.add(detachAsyncValue(item, visiting));
                    return copy;
                }
                if (value instanceof Map<?, ?> map) {
                    LinkedHashMap<Object, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        copy.put(
                                detachAsyncValue(entry.getKey(), visiting),
                                detachAsyncValue(entry.getValue(), visiting));
                    }
                    return copy;
                }
                if (value instanceof Set<?> set) {
                    LinkedHashSet<Object> copy = new LinkedHashSet<>();
                    for (Object item : set) copy.add(detachAsyncValue(item, visiting));
                    return copy;
                }
                if (value.getClass().isArray()) {
                    int length = java.lang.reflect.Array.getLength(value);
                    ArrayList<Object> copy = new ArrayList<>(length);
                    for (int i = 0; i < length; i++) {
                        copy.add(detachAsyncValue(
                                java.lang.reflect.Array.get(value, i),
                                visiting));
                    }
                    return copy;
                }

                throw new IllegalArgumentException(
                        "value of type " + value.getClass().getName()
                                + " cannot cross an async task boundary; use owned data");
            } finally {
                visiting.remove(value);
            }
        }

        private Object callFunctionBodyRaw(Ast.FunctionDecl fn, List<?> args) {
            Env env = new Env(null, fn.nonLexical());
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (TailCallSignal signal) {
                return new TailCall(signal.invocation);
            } catch (ReturnSignal signal) {
                return shapeReturnedValue(
                        fn.returnType(),
                        signal.value,
                        "function " + fn.name());
            } catch (BreakSignal | ContinueSignal signal) {
                throw new IllegalStateException("loop control cannot cross a function boundary", signal);
            }
        }

        private void callGeneratorBodyRaw(
                Ast.FunctionDecl fn,
                List<?> args,
                GeneratorRuntime.Emitter<Object> emitter) {
            Env env = new Env(null, fn.nonLexical());
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }

            GeneratorRuntime.Emitter<Object> previous = activeGeneratorEmitter.get();
            activeGeneratorEmitter.set(emitter);
            try {
                executeBlock(fn.body(), env);
            } catch (ReturnSignal signal) {
                if (signal.value != null) {
                    throw new IllegalStateException(
                            "generator return cannot carry a value; use yield for sequence elements");
                }
            } catch (TailCallSignal signal) {
                throw new IllegalStateException(
                        "generator completion cannot be tail-call lowered; use a bare return to finish", signal);
            } catch (BreakSignal | ContinueSignal signal) {
                throw new IllegalStateException(
                        "loop control cannot cross a generator function boundary", signal);
            } finally {
                if (previous == null) activeGeneratorEmitter.remove();
                else activeGeneratorEmitter.set(previous);
            }
        }

        private Object callMethod(OresObject receiver, Ast.MethodDecl method, List<?> args) {
            return invoke(methodInvocation(receiver, method, args));
        }

        private Object callMethodRaw(OresObject receiver, Ast.MethodDecl method, List<?> args) {
            if (method.async()) {
                throw new IllegalStateException(
                        "async instance methods are not admitted until receiver ownership can be moved into the task");
            }
            if (args.size() != method.arity()) throw new IllegalArgumentException("method " + method.name() + " arity mismatch");
            Env env = new Env(null, false, declaringClass(method));
            if (!method.isStatic()) env.define("self", receiver, Ast.BindingKind.VAL);
            for (int i = 0; i < method.arity(); i++) {
                Ast.Param param = method.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(method.body(), env);
                return null;
            } catch (TailCallSignal signal) {
                return new TailCall(signal.invocation);
            } catch (ReturnSignal signal) {
                return shapeReturnedValue(method.returnType(), signal.value, "method " + method.name());
            } catch (BreakSignal | ContinueSignal signal) {
                throw new IllegalStateException("loop control cannot cross a method boundary", signal);
            }
        }

        private void executeBlock(List<Ast.Stmt> statements, Env parent) {
            executeBlock(statements, parent, false);
        }

        private void executeBlock(List<Ast.Stmt> statements, Env parent, boolean inheritedTailBarrier) {
            Env env = new Env(parent);
            ArrayDeque<Ast.Expr> deferred = new ArrayDeque<>();
            boolean abnormalExit = false;
            try {
                for (Ast.Stmt stmt : statements) executeStatement(stmt, env, deferred, inheritedTailBarrier);
            } catch (TailCallSignal signal) {
                throw signal;
            } catch (ReturnSignal | BreakSignal | ContinueSignal signal) {
                throw signal;
            } catch (RuntimeException | Error failure) {
                abnormalExit = true;
                throw failure;
            } finally {
                boolean deferredFailure = false;
                try {
                    while (!deferred.isEmpty()) eval(deferred.pop(), env);
                } catch (RuntimeException | Error failure) {
                    deferredFailure = true;
                    throw failure;
                } finally {
                    env.releaseMutexGuards(abnormalExit || deferredFailure);
                }
            }
        }

        private void executeStatement(
                Ast.Stmt stmt,
                Env env,
                ArrayDeque<Ast.Expr> deferred,
                boolean inheritedTailBarrier) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (binding.initializer() instanceof Ast.LambdaExpr) {
                    env.reserve(binding.name(), binding.kind());
                    env.initialize(binding.name(), eval(binding.initializer(), env));
                } else {
                    env.define(binding.name(), eval(binding.initializer(), env), binding.kind());
                }
                return;
            }
            if (stmt instanceof Ast.DestructureStmt destructure) {
                Object value = eval(destructure.initializer(), env);
                if (destructure.kind() == Ast.DestructureKind.SEQUENCE) {
                    List<?> items = asSequence(value);
                    if (items.size() != destructure.bindings().size()) {
                        throw new IllegalArgumentException("destructure arity mismatch: value has " + items.size()
                                + " element(s), pattern has " + destructure.bindings().size());
                    }
                    for (int i = 0; i < items.size(); i++) {
                        Ast.DestructureBinding binding = destructure.bindings().get(i);
                        if (!binding.isDiscard()) env.define(binding.name(), items.get(i), binding.kind());
                    }
                } else {
                    for (Ast.DestructureBinding binding : destructure.bindings()) {
                        if (!binding.isDiscard()) {
                            env.define(
                                    binding.name(),
                                    destructureMember(value, binding.name(), env),
                                    binding.kind());
                        }
                    }
                }
                return;
            }
            if (stmt instanceof Ast.ReturnStmt ret) {
                returnFrom(
                        ret.value(),
                        env,
                        inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
            }
            if (stmt instanceof Ast.YieldStmt yielded) {
                if (env.hasLiveMutexGuards()) {
                    throw new IllegalStateException("cannot yield while holding a live MutexGuard");
                }
                GeneratorRuntime.Emitter<Object> emitter = activeGeneratorEmitter.get();
                if (emitter == null) {
                    throw new IllegalStateException("yield executed outside a generator activation");
                }
                Object value = eval(yielded.value(), env);
                context.schedulerSafepoint();
                emitter.emit(value);
                return;
            }
            if (stmt instanceof Ast.ExprStmt expression) { eval(expression.expression(), env); return; }
            if (stmt instanceof Ast.DeferStmt defer) { deferred.push(defer.expression()); return; }
            if (stmt instanceof Ast.BlockStmt block) {
                executeBlock(
                        block.body(),
                        env,
                        inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                return;
            }
            if (stmt instanceof Ast.BreakStmt) throw new BreakSignal();
            if (stmt instanceof Ast.ContinueStmt) throw new ContinueSignal();
            if (stmt instanceof Ast.IfStmt ifStmt) {
                for (Ast.IfBranch branch : ifStmt.branches()) {
                    ConditionResult condition = evalCondition(branch.condition(), env);
                    if (condition.matched()) {
                        Env branchEnv = new Env(env);
                        condition.bindings().forEach((name, value) ->
                                branchEnv.define(name, value, Ast.BindingKind.VAL));
                        executeBlock(
                                branch.body(),
                                branchEnv,
                                inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                        return;
                    }
                }
                executeBlock(
                        ifStmt.elseBody(),
                        env,
                        inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                return;
            }
            if (stmt instanceof Ast.MatchStmt matched) {
                Object subject = eval(matched.subject(), env);
                Ast.MatchArm selected = null;
                Map<String, Object> selectedBindings = Map.of();
                Ast.MatchArm fallback = null;

                for (Ast.MatchArm arm : matched.arms()) {
                    boolean catchAll = arm.guard() == null
                            && (arm.pattern() instanceof Ast.WildcardPattern
                                || arm.pattern() instanceof Ast.BindingPattern);
                    if (!matched.ordered() && catchAll) {
                        fallback = arm;
                        continue;
                    }

                    LinkedHashMap<String, Object> bindings = new LinkedHashMap<>();
                    if (!patternMatches(arm.pattern(), subject, bindings)) continue;
                    Env armEnv = new Env(env);
                    bindings.forEach((name, value) ->
                            armEnv.define(name, value, Ast.BindingKind.VAL));
                    if (arm.guard() != null && !truth(eval(arm.guard(), armEnv))) continue;

                    if (matched.ordered()) {
                        executeBlock(
                                arm.body(),
                                armEnv,
                                inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                        return;
                    }
                    if (selected != null) {
                        throw new IllegalStateException(
                                "exclusive match invariant violated at runtime: more than one explicit arm matched; "
                                        + "the static pattern proof and runtime type metadata disagree");
                    }
                    selected = arm;
                    selectedBindings = Map.copyOf(bindings);
                }

                if (selected == null && fallback != null) {
                    LinkedHashMap<String, Object> bindings = new LinkedHashMap<>();
                    if (!patternMatches(fallback.pattern(), subject, bindings)) {
                        throw new IllegalStateException("match fallback did not accept the unmatched subject");
                    }
                    selected = fallback;
                    selectedBindings = Map.copyOf(bindings);
                }

                if (selected == null) {
                    throw new IllegalStateException(
                            "exhaustive match invariant violated at runtime: no arm matched");
                }
                Env selectedEnv = new Env(env);
                selectedBindings.forEach((name, value) ->
                        selectedEnv.define(name, value, Ast.BindingKind.VAL));
                executeBlock(
                        selected.body(),
                        selectedEnv,
                        inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                return;
            }
            if (stmt instanceof Ast.SwitchStmt switched) {
                Object subject = eval(switched.subject(), env);
                for (Ast.SwitchCase arm : switched.cases()) {
                    boolean selected = false;
                    for (Ast.Expr constant : arm.constants()) {
                        if (Objects.equals(subject, eval(constant, env))) {
                            selected = true;
                            break;
                        }
                    }
                    if (selected) {
                        executeBlock(
                                arm.body(),
                                env,
                                inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                        return;
                    }
                }
                executeBlock(
                        switched.defaultBody(),
                        env,
                        inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                return;
            }
            if (stmt instanceof Ast.TryStmt tried) {
                // A call under catch/finally is not a proper tail call: the
                // caller still owns exception/cleanup semantics after the call.
                try { executeBlock(tried.body(), env, true); }
                catch (TailCallSignal signal) { throw signal; }
                catch (ReturnSignal | BreakSignal | ContinueSignal signal) { throw signal; }
                catch (OresPanic panic) { throw panic; }
                catch (RuntimeException failure) {
                    Env catchEnv = new Env(env);
                    catchEnv.define(tried.errorName(), failure, Ast.BindingKind.VAL);
                    executeBlock(tried.catchBody(), catchEnv, true);
                } finally { executeBlock(tried.finallyBody(), env, true); }
                return;
            }
            if (stmt instanceof Ast.ForOfDestructureStmt loop) {
                Object iterable = eval(loop.iterable(), env);
                Iterable<?> values = loop.asyncIteration()
                        ? asyncIterableValues(iterable, env)
                        : iterableValues(iterable, env);
                try {
                    for (Object item : values) {
                        context.schedulerSafepoint();
                        List<?> items = asSequence(item);
                        if (items.size() != loop.bindings().size()) {
                            throw new IllegalArgumentException(
                                    "for-of destructure arity mismatch: value has " + items.size()
                                            + " element(s), pattern has " + loop.bindings().size());
                        }
                        Env iteration = new Env(env);
                        for (int i = 0; i < items.size(); i++) {
                            Ast.DestructureBinding binding = loop.bindings().get(i);
                            if (!binding.isDiscard()) {
                                iteration.define(binding.name(), items.get(i), binding.kind());
                            }
                        }
                        try {
                            executeBlock(
                                    loop.body(),
                                    iteration,
                                    inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                        } catch (ContinueSignal ignored) {
                            continue;
                        } catch (BreakSignal ignored) {
                            break;
                        }
                    }
                } finally {
                    closeIterable(values);
                }
                return;
            }
            if (stmt instanceof Ast.ForOfStmt loop) {
                Object iterable = eval(loop.iterable(), env);
                Iterable<?> values = loop.asyncIteration()
                        ? asyncIterableValues(iterable, env)
                        : iterableValues(iterable, env);
                try {
                    for (Object item : values) {
                        context.schedulerSafepoint();
                        Env iteration = new Env(env);
                        iteration.define(loop.bindingName(), item, loop.bindingKind());
                        try {
                            executeBlock(
                                    loop.body(),
                                    iteration,
                                    inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                        } catch (ContinueSignal ignored) {
                            continue;
                        } catch (BreakSignal ignored) {
                            break;
                        }
                    }
                } finally {
                    closeIterable(values);
                }
                return;
            }
            if (stmt instanceof Ast.ForStmt loop) {
                Env loopEnv = new Env(env);
                if (loop.initializer() != null) {
                    executeStatement(
                            loop.initializer(),
                            loopEnv,
                            new ArrayDeque<>(),
                            inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                }
                while (loop.condition() == null || truth(eval(loop.condition(), loopEnv))) {
                    context.schedulerSafepoint();
                    try {
                        executeBlock(
                                loop.body(),
                                loopEnv,
                                inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                    } catch (ContinueSignal ignored) {
                        // Conventional for-loops still execute their update on continue.
                    } catch (BreakSignal ignored) {
                        break;
                    }
                    if (loop.update() != null) eval(loop.update(), loopEnv);
                }
                return;
            }
            if (stmt instanceof Ast.LoopStmt loop) {
                while (true) {
                    context.schedulerSafepoint();
                    try {
                        executeBlock(
                                loop.body(),
                                env,
                                inheritedTailBarrier || !deferred.isEmpty() || env.hasLiveMutexGuards());
                    } catch (ContinueSignal ignored) {
                        continue;
                    } catch (BreakSignal ignored) {
                        break;
                    }
                }
            }
        }

        private void returnFrom(Ast.Expr value, Env env, boolean tailBarrier) {
            if (value == null) throw new ReturnSignal(null);

            if (!tailBarrier) {
                if (value instanceof Ast.CallExpr call) {
                    Invocation invocation = prepareInvocation(call, env);
                    boolean localTailTarget = invocation.kind() == InvocationKind.INVOKABLE
                            ? invocation.target() instanceof TailCallable callable
                                    && callable.owner() == this
                            : invocation.owner() == this;
                    if (localTailTarget) {
                        throw new TailCallSignal(invocation);
                    }
                    // Crossing an untyped linked-code-unit boundary keeps this
                    // activation until the imported call returns so the caller's
                    // declared return-shape check still runs.
                    throw new ReturnSignal(invoke(invocation));
                }
                if (value instanceof Ast.ConditionalExpr conditional) {
                    Ast.Expr selected = truth(eval(conditional.condition(), env))
                            ? conditional.whenTrue()
                            : conditional.whenFalse();
                    returnFrom(selected, env, false);
                    return;
                }
            }

            throw new ReturnSignal(eval(value, env));
        }

        private Invocation prepareInvocation(Ast.CallExpr call, Env env) {
            if (call.callee() instanceof Ast.NameExpr directName
                    && env.lookup(directName.name()) == Env.MISSING) {
                Ast.FunctionDecl direct = findFunction(directName.name());
                if (direct != null) {
                    List<Object> args = evaluateArguments(call.arguments(), env);
                    return functionInvocation(direct, args);
                }
            }

            if (call.callee() instanceof Ast.MemberExpr methodCall) {
                Object receiver = eval(methodCall.receiver(), env);
                List<Object> args = evaluateArguments(call.arguments(), env);

                if (receiver instanceof OresObject object) {
                    Ast.MethodDecl method = object.owner.findMethod(
                            object.klass, CallableSelector.instance(methodCall.member(), args.size()), new LinkedHashSet<>());
                    if (method != null) {
                        object.owner.requireClassMemberVisible(
                                method.visibility(),
                                object.owner.declaringClass(method),
                                env.accessClass(),
                                "method",
                                method.name());
                        return object.owner.methodInvocation(object, method, args);
                    }

                    OwnedField ownedField = object.owner.findField(
                            object.klass, methodCall.member(), new LinkedHashSet<>());
                    if (ownedField != null) {
                        object.owner.requireClassMemberVisible(
                                ownedField.field().visibility(),
                                ownedField.owner(),
                                env.accessClass(),
                                "field",
                                ownedField.field().name());
                    }
                    Object fieldValue = object.fields.get(methodCall.member());
                    if (fieldValue instanceof Invokable invokable) {
                        return object.owner.invokableInvocation(invokable, args);
                    }
                    if (object.fields.containsKey(methodCall.member())) {
                        throw new IllegalArgumentException(
                                "field " + object.klass.name() + "." + methodCall.member()
                                        + " is not callable");
                    }
                    throw new IllegalArgumentException(
                            "no method or callable field " + object.klass.name() + "."
                                    + methodCall.member() + " with arity " + args.size());
                }

                if (receiver instanceof ClassFacade klass) {
                    Ast.MethodDecl fn = klass.owner().findStaticFunction(
                            klass.klass(), CallableSelector.staticFunction(methodCall.member(), args.size()), new LinkedHashSet<>());
                    if (fn == null) {
                        throw new IllegalArgumentException(
                                "no static function " + klass.klass().name() + "." + methodCall.member()
                                        + " with arity " + args.size());
                    }
                    klass.owner().requireClassMemberVisible(
                            fn.visibility(),
                            klass.owner().declaringClass(fn),
                            env.accessClass(),
                            "static function",
                            fn.name());
                    return klass.owner().staticFunctionInvocation(fn, args);
                }

                if (receiver instanceof ModuleFacade module) {
                    return module.owner().prepareModuleInvocation(
                            module.module(), methodCall.member(), args);
                }

                if (receiver instanceof OresMutex.Guard<?> guard
                        && !methodCall.member().equals("release")
                        && !methodCall.member().equals("is_released")
                        && guard.value() instanceof OresObject object) {
                    Ast.MethodDecl method = object.owner.findMethod(
                            object.klass, CallableSelector.instance(methodCall.member(), args.size()), new LinkedHashSet<>());
                    if (method != null) {
                        object.owner.requireClassMemberVisible(
                                method.visibility(),
                                object.owner.declaringClass(method),
                                env.accessClass(),
                                "method",
                                method.name());
                        return object.owner.methodInvocation(object, method, args);
                    }
                }

                if (receiver instanceof ImportedNamespace namespace) {
                    return namespace.owner().prepareImportedInvocation(
                            namespace.kind(), methodCall.member(), args);
                }

                Object callee = member(receiver, methodCall.member(), env);
                if (!(callee instanceof Invokable invokable)) {
                    throw new IllegalArgumentException("value is not callable: " + callee);
                }
                return invokableInvocation(invokable, args);
            }

            Object callee = eval(call.callee(), env);
            List<Object> args = evaluateArguments(call.arguments(), env);
            if (!(callee instanceof Invokable invokable)) {
                throw new IllegalArgumentException("value is not callable: " + callee);
            }
            return invokableInvocation(invokable, args);
        }

        private List<Object> evaluateArguments(List<Ast.Expr> arguments, Env env) {
            ArrayList<Object> values = new ArrayList<>(arguments.size());
            for (Ast.Expr argument : arguments) values.add(eval(argument, env));
            return List.copyOf(values);
        }

        private Object eval(Ast.Expr expr, Env env) {
            if (expr instanceof Ast.LiteralExpr literal) {
                if (literal.value() == null) throw new IllegalArgumentException("standalone null values are forbidden");
                if (literal.value() instanceof Ast.Imaginary imaginary) return new Complex(0.0, imaginary.coefficient());
                return literal.value();
            }
            if (expr instanceof Ast.NameExpr name) {
                Object local = env.lookup(name.name());
                if (local != Env.MISSING) return local;
                if (name.name().equals("stdio")) return new StdioFacade(context);
                if (name.name().equals("process")) return new ProcessFacade(context);
                if (name.name().equals("actor")) return new ActorFacade(context);
                if (name.name().equals("Mutex")) return new MutexFactory(false, context);
                if (name.name().equals("SharedMutex")) return new MutexFactory(true, context);
                if (name.name().equals("Future")) return FutureFactory.INSTANCE;
                if (name.name().equals("print")) return (Invokable) args -> {
                    context.requireCapability(IsolatePolicy.Capability.STDOUT, "print");
                    requireOne(args, "print"); context.output().print(display(args.getFirst())); context.output().flush(); return null;
                };
                if (name.name().equals("Some")) return (Invokable) args -> {
                    requireOne(args, "Some");
                    return new OptionValue(true, args.getFirst());
                };
                if (name.name().equals("None")) return new OptionValue(false, null);
                if (name.name().equals("Ok")) return (Invokable) args -> {
                    requireOne(args, "Ok");
                    return new ResultValue(true, args.getFirst());
                };
                if (name.name().equals("Err")) return (Invokable) args -> {
                    requireOne(args, "Err");
                    return new ResultValue(false, args.getFirst());
                };
                HostClassFacade hostClass = hostClasses.get(name.name());
                if (hostClass != null) {
                    context.requireCapability(IsolatePolicy.Capability.JAVA_INTEROP,
                            "Java host class " + hostClass.className());
                    return hostClass;
                }
                Invokable hostFunction = hostFunctions.get(name.name());
                if (hostFunction != null) return hostFunction;
                Ast.ModuleDecl module = modules.get(name.name());
                if (module != null) return new ModuleFacade(this, module);
                Ast.ClassDecl klass = findClass(name.name());
                if (klass != null) return new ClassFacade(this, klass);
                Object imported = importedValue(name.name());
                if (imported != Env.MISSING) return imported;
                Ast.FunctionDecl fn = findFunction(name.name());
                if (fn != null) {
                    if (fn.actorKind() != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException("actor callable " + fn.name()
                                + " is an actor entry point, not a first-class callable value");
                    }
                    if (fn.kind() == Ast.CallableKind.ROUTINE) {
                        throw new IllegalArgumentException("routine " + fn.name()
                                + " is direct-call-only and cannot be used as a first-class callable value");
                    }
                    if (!fn.genericParameters().isEmpty()) {
                        throw new IllegalArgumentException(
                                "generic fnc '" + fn.name()
                                        + "' must be specialized by a direct call; "
                                        + "polymorphic function values are not supported yet");
                    }
                    return tailCallable(args -> callFunctionRaw(fn, objectArguments(args)));
                }
                throw new IllegalArgumentException("unknown name " + name.name());
            }
            if (expr instanceof Ast.AssignExpr assignment) {
                Object value = eval(assignment.value(), env);
                if (assignment.target() instanceof Ast.NameExpr target) {
                    env.assign(target.name(), value);
                    return value;
                }
                if (assignment.target() instanceof Ast.MemberExpr target) {
                    Object receiver = eval(target.receiver(), env);
                    if (receiver instanceof OresMutex.Guard<?> guard) receiver = guard.value();
                    if (receiver instanceof OresObject object) {
                        if (!object.fields.containsKey(target.member())) {
                            throw new IllegalArgumentException("unknown field " + target.member());
                        }
                        OwnedField ownedField = object.owner.findField(
                                object.klass, target.member(), new LinkedHashSet<>());
                        if (ownedField == null) {
                            throw new IllegalArgumentException("unknown field " + target.member());
                        }
                        Ast.FieldDecl field = ownedField.field();
                        object.owner.requireClassMemberVisible(
                                field.visibility(),
                                ownedField.owner(),
                                env.accessClass(),
                                "field",
                                field.name());
                        if (field.bindingKind() != Ast.BindingKind.LET) {
                            throw new IllegalArgumentException("field '" + object.klass.name() + "."
                                    + target.member() + "' is immutable");
                        }
                        object.fields.put(target.member(), value);
                        return value;
                    }
                    if (receiver instanceof DynamicStructValue dynamic) {
                        dynamic.fields.put(target.member(), value);
                        return value;
                    }
                    throw new IllegalArgumentException("member assignment requires a class instance, DynamicStruct, or mutex guard");
                }
                if (assignment.target() instanceof Ast.IndexExpr target) {
                    Object receiver = eval(target.receiver(), env);
                    Object index = eval(target.index(), env);
                    if (receiver instanceof DynamicStructValue dynamic) {
                        if (!(index instanceof String key)) {
                            throw new IllegalArgumentException("DynamicStruct key must be a string");
                        }
                        dynamic.fields.put(key, value);
                        return value;
                    }
                    if (!(index instanceof Number number)) throw new IllegalArgumentException("array/list index must be an integer");
                    int i = Math.toIntExact(number.longValue());
                    if (receiver instanceof List<?> raw) {
                        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) raw;
                        list.set(i, value);
                        return value;
                    }
                    throw new IllegalArgumentException("indexed assignment requires a mutable array/list or DynamicStruct");
                }
                throw new IllegalArgumentException("unsupported assignment target");
            }
            if (expr instanceof Ast.ConditionalExpr conditional) {
                return truth(eval(conditional.condition(), env))
                        ? eval(conditional.whenTrue(), env)
                        : eval(conditional.whenFalse(), env);
            }
            if (expr instanceof Ast.UnaryExpr unary) {
                Object value = eval(unary.operand(), env);
                return switch (unary.operator()) {
                    case "&", "&mut" -> value;
                    case "!" -> !truth(value);
                    case "~" -> ~integralLong(value);
                    case "+" -> value;
                    case "-" -> negate(value);
                    default -> throw new IllegalArgumentException("unsupported unary operator " + unary.operator());
                };
            }
            if (expr instanceof Ast.BinaryExpr binary) {
                if (binary.operator().equals("&&")) {
                    Object left = eval(binary.left(), env);
                    return truth(left) && truth(eval(binary.right(), env));
                }
                if (binary.operator().equals("||")) {
                    Object left = eval(binary.left(), env);
                    return truth(left) || truth(eval(binary.right(), env));
                }
                if (binary.operator().equals("^^")) {
                    return truth(eval(binary.left(), env)) ^ truth(eval(binary.right(), env));
                }
                Object left = eval(binary.left(), env);
                Object right = eval(binary.right(), env);
                if (binary.operator().equals("|") && left instanceof Boolean lb && right instanceof Boolean rb) {
                    return lb || rb;
                }
                return binary(binary.operator(), left, right);
            }
            if (expr instanceof Ast.TypeTestExpr test) {
                Object value = eval(test.value(), env);
                return oresTypeMatches(value, test.targetType());
            }
            if (expr instanceof Ast.PatternTestExpr test) {
                Object value = eval(test.value(), env);
                return patternMatches(test.pattern(), value, new LinkedHashMap<>());
            }
            if (expr instanceof Ast.CastExpr cast) {
                Object value = eval(cast.value(), env);
                boolean matches = oresTypeMatches(value, cast.targetType());
                if (cast.mode() == Ast.CastMode.OPTIONAL) {
                    return new OptionValue(matches, matches ? value : null);
                }
                if (!matches) {
                    throw new OresCastError("cannot cast runtime type " + oresRuntimeTypeName(value)
                            + " to " + cast.targetType().name());
                }
                return value;
            }
            if (expr instanceof Ast.CallExpr call) {
                return invoke(prepareInvocation(call, env));
            }
            if (expr instanceof Ast.MemberExpr member) return member(eval(member.receiver(), env), member.member(), env);
            if (expr instanceof Ast.IndexExpr indexed) {
                Object receiver = eval(indexed.receiver(), env);
                Object index = eval(indexed.index(), env);
                if (receiver instanceof DynamicStructValue dynamic) {
                    if (!(index instanceof String key)) {
                        throw new IllegalArgumentException("DynamicStruct key must be a string");
                    }
                    if (!dynamic.fields.containsKey(key)) {
                        throw new IllegalArgumentException("unknown DynamicStruct key " + key);
                    }
                    return dynamic.fields.get(key);
                }
                if (receiver instanceof Map<?, ?> map) {
                    if (!(index instanceof String key)) {
                        throw new IllegalArgumentException("object/map key must be a string");
                    }
                    if (!map.containsKey(key)) {
                        throw new IllegalArgumentException("unknown object/map key " + key);
                    }
                    return map.get(key);
                }
                if (!(index instanceof Number number)) throw new IllegalArgumentException("array/list index must be an integer");
                int i = Math.toIntExact(number.longValue());
                if (receiver instanceof List<?> list) return list.get(i);
                if (receiver instanceof Object[] array) return array[i];
                throw new IllegalArgumentException("value is not indexable: " + receiver);
            }
            if (expr instanceof Ast.NewExpr created) {
                if (created.type().name().equals("DynamicStruct")) {
                    if (!created.arguments().isEmpty()) {
                        throw new IllegalArgumentException("DynamicStruct<T> constructor takes no positional arguments");
                    }
                    return new DynamicStructValue();
                }
                HostClassFacade hostClass = hostClasses.get(created.type().name());
                if (hostClass != null) {
                    context.requireCapability(IsolatePolicy.Capability.JAVA_INTEROP,
                            "Java host constructor " + hostClass.className());
                    if (!hostClass.constructible()) {
                        throw new IllegalArgumentException(
                                "Java function namespace '" + created.type().name() + "' is not constructible");
                    }
                    List<Object> args = created.arguments().stream().map(arg -> eval(arg, env)).toList();
                    return instantiateHost(hostClass, args);
                }

                Ast.ClassDecl klass = findClass(created.type().name());
                Evaluator owner = this;
                if (klass == null) {
                    Object imported = importedValue(created.type().name());
                    if (imported instanceof ClassFacade externalClass) {
                        owner = externalClass.owner();
                        klass = externalClass.klass();
                    }
                }
                if (klass == null) throw new IllegalArgumentException("unknown class " + created.type().name());
                List<Object> args = created.arguments().stream().map(arg -> eval(arg, env)).toList();
                return owner.instantiate(klass, args);
            }
            if (expr instanceof Ast.AwaitExpr awaited) {
                Object value = eval(awaited.expression(), env);
                if (value instanceof OresFuture<?> future) {
                    return AsyncRuntime.await(future);
                }
                if (value instanceof CompletionStage<?> stage) {
                    return AsyncRuntime.await(stage);
                }
                return value;
            }
            if (expr instanceof Ast.ListExpr list) {
                ArrayList<Object> result = new ArrayList<>(list.elements().size());
                for (Ast.Expr item : list.elements()) result.add(eval(item, env));
                return result;
            }
            if (expr instanceof Ast.TupleExpr tuple) return tuple.elements().stream().map(item -> eval(item, env)).toList();
            if (expr instanceof Ast.ObjectExpr object) {
                boolean dynamicKeys = object.fields().stream().anyMatch(Ast.ObjectField::isDynamic);
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                for (Ast.ObjectField field : object.fields()) {
                    String key;
                    if (field.isDynamic()) {
                        Object evaluatedKey = eval(field.dynamicName(), env);
                        if (!(evaluatedKey instanceof String stringKey)) {
                            throw new IllegalArgumentException("dynamic obj key must evaluate to a string");
                        }
                        key = stringKey;
                    } else {
                        key = field.name();
                    }
                    if (result.putIfAbsent(key, eval(field.value(), env)) != null) {
                        throw new IllegalArgumentException("duplicate obj field " + key);
                    }
                }
                return dynamicKeys ? new DynamicStructValue(result) : Map.copyOf(result);
            }
            if (expr instanceof Ast.LambdaExpr lambda) {
                boolean nonLexical = lambda.nonLexical() || env.descendantsNonLexical();
                Env captured = nonLexical ? null : env.snapshot();
                return tailCallable(args -> {
                    if (args.size() != lambda.parameters().size()) throw new IllegalArgumentException("lambda arity mismatch");
                    Env local = new Env(captured, nonLexical);
                    for (int i = 0; i < lambda.parameters().size(); i++) {
                        Ast.Param param = lambda.parameters().get(i);
                        local.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
                    }
                    try {
                        if (lambda.expressionBody() != null) {
                            // An expression-bodied lambda's sole expression is
                            // inherently in tail position. Route it through the
                            // same tail-return lowering as an explicit
                            // `return expr;` in a block-bodied lambda.
                            returnFrom(lambda.expressionBody(), local, false);
                            throw new AssertionError("lambda expression return did not transfer control");
                        }
                        executeBlock(lambda.blockBody(), local);
                        return null;
                    } catch (TailCallSignal signal) {
                        return new TailCall(signal.invocation);
                    } catch (ReturnSignal signal) {
                        return signal.value;
                    } catch (BreakSignal | ContinueSignal signal) {
                        throw new IllegalStateException("loop control cannot cross a lambda boundary", signal);
                    }
                });
            }
            throw new IllegalArgumentException("unsupported expression " + expr);
        }

        private Object member(Object receiver, String name, Env env) {
            if (receiver instanceof HostClassFacade host) {
                context.requireCapability(IsolatePolicy.Capability.JAVA_INTEROP,
                        "Java host class " + host.className());
                return hostMember(host.symbol(), host.className(), name);
            }
            if (receiver instanceof HostObjectFacade host) {
                context.requireCapability(IsolatePolicy.Capability.JAVA_INTEROP,
                        "Java host object member " + name);
                return hostMember(host.value(), "host object", name);
            }
            if (receiver instanceof StdioFacade stdio) {
                return switch (name) {
                    case "print" -> (Invokable) stdio::print;
                    case "println" -> (Invokable) stdio::println;
                    case "stdout" -> new StdoutFacade(stdio.context());
                    default -> throw new IllegalArgumentException("unknown stdio member " + name);
                };
            }
            if (receiver instanceof StdoutFacade stdout) {
                return switch (name) {
                    case "write" -> (Invokable) stdout::write;
                    case "println" -> (Invokable) stdout::println;
                    default -> throw new IllegalArgumentException("unknown stdout member " + name);
                };
            }
            if (receiver instanceof ProcessFacade process) {
                return switch (name) {
                    case "context_id" -> process.contextId();
                    case "descriptor" -> process.descriptor();
                    case "share_readonly" -> (Invokable) process::shareReadonly;
                    case "gc" -> (Invokable) process::gc;
                    default -> throw new IllegalArgumentException("unknown process member " + name);
                };
            }
            if (receiver instanceof ActorFacade actor) {
                return switch (name) {
                    case "gc" -> (Invokable) actor::gc;
                    default -> throw new IllegalArgumentException("unknown actor member " + name);
                };
            }
            if (receiver instanceof MutexFactory factory) {
                if (!name.equals("new")) throw new IllegalArgumentException("unknown mutex factory member " + name);
                return (Invokable) factory::create;
            }
            if (receiver instanceof FutureFactory) {
                return switch (name) {
                    case "all" -> (Invokable) args -> {
                        requireOne(args, "Future.all");
                        return OresFutures.all(asSequence(args.getFirst()));
                    };
                    case "race" -> (Invokable) args -> {
                        requireOne(args, "Future.race");
                        return OresFutures.race(asSequence(args.getFirst()));
                    };
                    default -> throw new IllegalArgumentException("unknown Future member " + name);
                };
            }
            if (receiver instanceof GeneratorRuntime.Generator<?> generator) {
                return switch (name) {
                    case "next" -> (Invokable) args -> { requireZero(args, "Iterator.next"); return generator.nextStep(); };
                    case "close" -> (Invokable) args -> { requireZero(args, "Iterator.close"); generator.close(); return null; };
                    default -> throw new IllegalArgumentException("unknown Iterator member " + name);
                };
            }
            if (receiver instanceof GeneratorRuntime.AsyncGenerator<?> generator) {
                return switch (name) {
                    case "next" -> (Invokable) args -> { requireZero(args, "AsyncIterator.next"); return generator.nextStep(); };
                    case "close" -> (Invokable) args -> { requireZero(args, "AsyncIterator.close"); generator.close(); return null; };
                    default -> throw new IllegalArgumentException("unknown AsyncIterator member " + name);
                };
            }
            if (receiver instanceof GeneratorRuntime.Step<?> step) {
                return switch (name) {
                    case "done" -> step.done();
                    case "value" -> new OptionValue(!step.done(), step.value());
                    default -> throw new IllegalArgumentException("unknown IteratorResult member " + name);
                };
            }
            if (receiver instanceof OptionValue option) return optionMember(option, name);
            if (receiver instanceof ResultValue result) return resultMember(result, name);
            if (receiver instanceof OresMutex.Lock<?> lock) return mutexMember(lock, name);
            if (receiver instanceof OresMutex.Guard<?> guard) {
                return switch (name) {
                    case "release" -> (Invokable) args -> { requireZero(args, "MutexGuard.release"); guard.release(); return null; };
                    case "is_released" -> (Invokable) args -> { requireZero(args, "MutexGuard.is_released"); return guard.released(); };
                    default -> member(guard.value(), name, env);
                };
            }
            if (receiver instanceof ImportedNamespace namespace) return namespace.owner().exportValue(namespace.kind(), name);
            if (receiver instanceof ModuleFacade namespace) return namespace.owner().moduleMember(namespace.module(), name);
            if (receiver instanceof ClassFacade klass) {
                List<Ast.MethodDecl> functions = klass.owner().findStaticFunctionsByName(klass.klass(), name, new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    klass.owner().requireClassMemberVisible(
                            fn.visibility(),
                            klass.owner().declaringClass(fn),
                            env == null ? null : env.accessClass(),
                            "static function",
                            fn.name());
                    if (!fn.genericParameters().isEmpty()) {
                        throw new IllegalArgumentException(
                                "generic static fnc '" + klass.klass().name() + "." + name
                                        + "' must be specialized by a direct call; "
                                        + "polymorphic function values are not supported yet");
                    }
                }
                if (!functions.isEmpty()) {
                    return new StaticFunctionValue(klass.owner(), klass.klass(), name, env == null ? null : env.accessClass());
                }
                throw new IllegalArgumentException("unknown static member " + klass.klass().name() + "." + name);
            }
            if (receiver instanceof OresObject object) {
                if (object.fields.containsKey(name)) {
                    OwnedField ownedField = object.owner.findField(
                            object.klass, name, new LinkedHashSet<>());
                    if (ownedField != null) {
                        object.owner.requireClassMemberVisible(
                                ownedField.field().visibility(),
                                ownedField.owner(),
                                env == null ? null : env.accessClass(),
                                "field",
                                ownedField.field().name());
                    }
                    return object.fields.get(name);
                }
                if (object.owner.hasInstanceMethodNamed(object.klass, name, new LinkedHashSet<>())) {
                    return new BoundMethod(object, name, env == null ? null : env.accessClass());
                }
                throw new IllegalArgumentException("unknown member " + object.klass.name() + "." + name);
            }
            if (receiver instanceof DynamicStructValue dynamic) {
                if (!dynamic.fields.containsKey(name)) {
                    throw new IllegalArgumentException("unknown DynamicStruct member " + name);
                }
                return dynamic.fields.get(name);
            }
            if (receiver instanceof Map<?, ?> map) {
                if (!map.containsKey(name)) throw new IllegalArgumentException("unknown obj member " + name);
                return map.get(name);
            }
            InteropLibrary foreign = InteropLibrary.getUncached(receiver);
            if (foreign.hasMembers(receiver)) {
                context.requireCapability(IsolatePolicy.Capability.JAVA_INTEROP,
                        "Java host object member " + name);
                return hostMember(receiver, "host object", name);
            }
            throw new IllegalArgumentException("cannot access member '" + name + "' on " + receiver);
        }

        private Object hostMember(Object receiver, String ownerName, String name) {
            InteropLibrary interop = InteropLibrary.getUncached(receiver);
            if (interop.isMemberInvocable(receiver, name)) {
                return (Invokable) args -> {
                    context.requireCapability(
                            IsolatePolicy.Capability.JAVA_INTEROP,
                            "Java host member " + ownerName + "." + name);
                    return invokeHostMember(receiver, name, args);
                };
            }
            if (interop.isMemberReadable(receiver, name)) {
                try {
                    return normalizeHostResult(interop.readMember(receiver, name));
                } catch (Exception failure) {
                    throw hostInteropError("read Java member " + ownerName + "." + name, failure);
                }
            }
            throw new IllegalArgumentException(
                    "Java member is not exported by HostAccess: " + ownerName + "." + name);
        }

        private Object invokeHostMember(Object receiver, String name, List<Object> args) {
            try {
                Object[] unwrapped = args.stream().map(this::unwrapHostArgument).toArray();
                Object result = InteropLibrary.getUncached(receiver).invokeMember(receiver, name, unwrapped);
                return normalizeHostResult(result);
            } catch (Exception failure) {
                throw hostInteropError("invoke Java member " + name, failure);
            }
        }

        private Object instantiateHost(HostClassFacade hostClass, List<Object> args) {
            InteropLibrary interop = InteropLibrary.getUncached(hostClass.symbol());
            if (!interop.isInstantiable(hostClass.symbol())) {
                throw new IllegalArgumentException(
                        "allowlisted Java host class is not constructible: " + hostClass.className());
            }
            try {
                Object[] unwrapped = args.stream().map(this::unwrapHostArgument).toArray();
                Object value = interop.instantiate(hostClass.symbol(), unwrapped);
                return new HostObjectFacade(value);
            } catch (Exception failure) {
                throw hostInteropError("construct Java host class " + hostClass.className(), failure);
            }
        }

        private Object normalizeHostResult(Object value) {
            if (value == null) return new OptionValue(false, null);
            if (value instanceof CompletionStage<?> stage) {
                return OresFuture.from(stage);
            }
            if (value instanceof Number || value instanceof Boolean || value instanceof String
                    || value instanceof Character || value instanceof OresFuture<?>) {
                return value;
            }
            return new HostObjectFacade(value);
        }

        private Object unwrapHostArgument(Object value) {
            return value instanceof HostObjectFacade host ? host.value() : value;
        }

        private RuntimeException hostInteropError(String operation, Exception failure) {
            return new IllegalArgumentException(operation + " failed: " + failure.getMessage(), failure);
        }

        private Object optionMember(OptionValue option, String name) {
            return switch (name) {
                case "is_some" -> (Invokable) args -> { requireZero(args, "Option.is_some"); return option.present(); };
                case "is_none" -> (Invokable) args -> { requireZero(args, "Option.is_none"); return !option.present(); };
                case "unwrap" -> (Invokable) args -> {
                    requireZero(args, "Option.unwrap");
                    if (!option.present()) throw new OresPanic("called Option::unwrap() on a None value");
                    return option.value();
                };
                case "unwrap_safe" -> (Invokable) args -> {
                    requireZero(args, "Option.unwrap_safe");
                    return option.present()
                            ? new ResultValue(true, option.value())
                            : new ResultValue(false, new OptionUnwrapError("None"));
                };
                case "expect" -> (Invokable) args -> {
                    String message = requireStringArg(args, "Option.expect");
                    if (!option.present()) throw new OresPanic(message);
                    return option.value();
                };
                case "unwrap_or" -> (Invokable) args -> {
                    requireOne(args, "Option.unwrap_or");
                    return option.present() ? option.value() : args.getFirst();
                };
                default -> throw new IllegalArgumentException("unknown Option member " + name);
            };
        }

        private Object resultMember(ResultValue result, String name) {
            return switch (name) {
                case "is_ok" -> (Invokable) args -> { requireZero(args, "Result.is_ok"); return result.ok(); };
                case "is_err" -> (Invokable) args -> { requireZero(args, "Result.is_err"); return !result.ok(); };
                case "unwrap" -> (Invokable) args -> {
                    requireZero(args, "Result.unwrap");
                    if (!result.ok()) {
                        throw new OresPanic("called Result::unwrap() on an Err value: " + display(result.value()));
                    }
                    return result.value();
                };
                case "unwrap_safe" -> (Invokable) args -> {
                    requireZero(args, "Result.unwrap_safe");
                    return result;
                };
                case "expect" -> (Invokable) args -> {
                    String message = requireStringArg(args, "Result.expect");
                    if (!result.ok()) throw new OresPanic(message + ": " + display(result.value()));
                    return result.value();
                };
                case "unwrap_or" -> (Invokable) args -> {
                    requireOne(args, "Result.unwrap_or");
                    return result.ok() ? result.value() : args.getFirst();
                };
                default -> throw new IllegalArgumentException("unknown Result member " + name);
            };
        }

        @SuppressWarnings("unchecked")
        private Object mutexMember(OresMutex.Lock<?> rawLock, String name) {
            if (rawLock instanceof OresMutex.Shared<?>) {
                context.requireCapability(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex." + name);
            }
            OresMutex.Lock<Object> lock = (OresMutex.Lock<Object>) rawLock;
            return switch (name) {
                case "lock" -> (Invokable) args -> { requireZero(args, "Mutex.lock"); return lock.lock(); };
                case "try_lock" -> (Invokable) args -> {
                    requireZero(args, "Mutex.try_lock");
                    var guard = lock.tryLock();
                    return guard.isPresent() ? new OptionValue(true, guard.get()) : new OptionValue(false, null);
                };
                case "lock_async" -> (Invokable) args -> { requireZero(args, "Mutex.lock_async"); return lock.lockAsync(); };
                case "with_lock" -> (Invokable) args -> {
                    requireOne(args, "Mutex.with_lock");
                    if (!(args.getFirst() instanceof Invokable callback)) {
                        throw new IllegalArgumentException("Mutex.with_lock expects a one-argument lambda/function");
                    }
                    return lock.withLock(value -> {
                        Object result = invoke(invokableInvocation(callback, List.of(value)));
                        if (result != null) {
                            throw new IllegalArgumentException(
                                    "Mutex.with_lock callback must return void");
                        }
                        return null;
                    });
                };
                case "is_poisoned" -> (Invokable) args -> { requireZero(args, "Mutex.is_poisoned"); return lock.isPoisoned(); };
                case "recover" -> {
                    if (!(lock instanceof OresMutex.Shared<?> sharedRaw)) {
                        throw new IllegalArgumentException("recover is only available on SharedMutex<T>");
                    }
                    OresMutex.Shared<Object> shared = (OresMutex.Shared<Object>) sharedRaw;
                    yield (Invokable) args -> {
                        requireOne(args, "SharedMutex.recover");
                        if (!(args.getFirst() instanceof Invokable callback)) {
                            throw new IllegalArgumentException("SharedMutex.recover expects a one-argument lambda/function");
                        }
                        return shared.recover(value -> {
                            Object result = invoke(invokableInvocation(callback, List.of(value)));
                            if (result != null) {
                                throw new IllegalArgumentException(
                                        "SharedMutex.recover callback must return void");
                            }
                            return null;
                        });
                    };
                }
                default -> throw new IllegalArgumentException("unknown mutex member " + name);
            };
        }

        private Invocation prepareBoundMethodInvocation(OresObject receiver, String name, List<Object> args, Ast.ClassDecl accessClass) {
            CallableSelector selector = CallableSelector.instance(name, args.size());
            Ast.MethodDecl method = findMethod(receiver.klass, selector, new LinkedHashSet<>());
            if (method == null) throw new IllegalArgumentException("no method " + receiver.klass.name() + "." + name + " with arity " + args.size());
            requireClassMemberVisible(method.visibility(), declaringClass(method), accessClass, "method", name);
            if (!method.genericParameters().isEmpty()) throw new IllegalArgumentException("generic method values require direct-call specialization");
            return methodInvocation(receiver, method, args);
        }

        private Invocation prepareStaticValueInvocation(Ast.ClassDecl klass, String name, List<Object> args, Ast.ClassDecl accessClass) {
            CallableSelector selector = CallableSelector.staticFunction(name, args.size());
            Ast.MethodDecl fn = findStaticFunction(klass, selector, new LinkedHashSet<>());
            if (fn == null) throw new IllegalArgumentException("no static function " + klass.name() + "." + name + " with arity " + args.size());
            requireClassMemberVisible(fn.visibility(), declaringClass(fn), accessClass, "static function", name);
            if (!fn.genericParameters().isEmpty()) throw new IllegalArgumentException("generic static function values require direct-call specialization");
            return staticFunctionInvocation(fn, args);
        }

        private Object callStaticFunction(Ast.ClassDecl klass, Ast.MethodDecl fn, List<?> args) {
            if (!fn.isStatic()) throw new IllegalArgumentException("not a static class function: " + klass.name() + "." + fn.name());
            return invoke(staticFunctionInvocation(fn, args));
        }

        private Object callStaticFunctionRaw(Ast.MethodDecl fn, List<?> args) {
            if (!fn.isStatic()) throw new IllegalArgumentException("not a static class function: " + fn.name());
            if (args.size() != fn.arity()) throw new IllegalArgumentException("static function " + fn.name() + " arity mismatch");
            if (!fn.async()) return callStaticFunctionBodyRaw(fn, args);

            List<?> detached = detachAsyncArguments(args);
            return context.asyncRuntime().submit(() ->
                    detachAsyncValue(
                            invoke(staticFunctionBodyInvocation(fn, detached)),
                            new IdentityHashMap<>()));
        }

        private Object callStaticFunctionBodyRaw(Ast.MethodDecl fn, List<?> args) {
            Env env = new Env(null, false, declaringClass(fn));
            for (int i = 0; i < fn.arity(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (TailCallSignal signal) {
                return new TailCall(signal.invocation);
            } catch (ReturnSignal signal) {
                return shapeReturnedValue(fn.returnType(), signal.value, "static function " + fn.name());
            } catch (BreakSignal | ContinueSignal signal) {
                throw new IllegalStateException("loop control cannot cross a static function boundary", signal);
            }
        }

        /**
         * Go-style method value: method code remains shared in the class method
         * table.  Extraction materializes only the receiver identity plus the
         * statically known method-name family; callback arity selects the same
         * closed-world CallableSelector slot used by direct calls.
         *
         * <p>Direct receiver.method(...) calls bypass this object entirely, so
         * ordinary method invocation has no bound-method allocation.  An AOT
         * backend may lower a non-escaping value to a register/stack "fat
         * pointer" (receiver + resolved slot) instead of heap allocating it.
         */
        /**
         * First-class static callable counterpart to BoundMethod.  No receiver
         * is captured; only the owning class and shared static slot family are
         * retained.  Invocation arity selects the closed-world static selector.
         */
        private static final class StaticFunctionValue implements Invokable {
            private final Evaluator owner;
            private final Ast.ClassDecl klass;
            private final String functionName;
            private final Ast.ClassDecl accessClass;

            private StaticFunctionValue(Evaluator owner, Ast.ClassDecl klass, String functionName, Ast.ClassDecl accessClass) {
                this.owner = owner;
                this.klass = klass;
                this.functionName = functionName;
                this.accessClass = accessClass;
            }

            @Override public Object call(List<Object> arguments) {
                return owner.invoke(owner.prepareStaticValueInvocation(klass, functionName, arguments, accessClass));
            }
        }

        private static final class BoundMethod implements Invokable {
            private final OresObject receiver;
            private final String methodName;
            private final Ast.ClassDecl accessClass;

            private BoundMethod(OresObject receiver, String methodName, Ast.ClassDecl accessClass) {
                this.receiver = receiver;
                this.methodName = methodName;
                this.accessClass = accessClass;
            }

            @Override public Object call(List<Object> arguments) {
                return receiver.owner.invoke(receiver.owner.prepareBoundMethodInvocation(receiver, methodName, arguments, accessClass));
            }
        }

        private Object importedValue(String name) {
            ImportedBinding direct = namedImports.get(name);
            if (direct != null) {
                return importedTarget(direct.declaration())
                        .exportValue(direct.declaration().kind(), direct.sourceName());
            }
            Ast.ImportDecl namespace = namespaceImports.get(name);
            if (namespace != null) return new ImportedNamespace(importedTarget(namespace), namespace.kind());
            return Env.MISSING;
        }

        private Evaluator importedTarget(Ast.ImportDecl imported) {
            String targetId = resolveImportUnitId(imported.path());
            Object target = context.linkedCodeUnit(targetId);
            if (!(target instanceof Evaluator evaluator)) {
                throw new IllegalStateException(
                        "import target '" + imported.path() + "' for '" + codeUnitId
                                + "' is not linked yet; all members of an import cycle must be linked before init");
            }
            return evaluator;
        }

        private String resolveImportUnitId(String rawPath) {
            String hostResolved = context.resolvedLinkedImport(codeUnitId, rawPath);
            if (hostResolved != null) return hostResolved;

            String raw = rawPath.replace('\\', '/');
            Path parent = Path.of(codeUnitId).getParent();
            Path candidatePath = raw.startsWith(".")
                    ? (parent == null ? Path.of(raw) : parent.resolve(raw)).normalize()
                    : Path.of(raw).normalize();
            String candidate = normalizeUnitId(candidatePath.toString());
            if (!context.hasLinkedCodeUnit(candidate)
                    && !candidate.endsWith(".ores")
                    && !candidate.endsWith(".java")) {
                if (context.hasLinkedCodeUnit(candidate + ".ores")) candidate += ".ores";
                else if (context.hasLinkedCodeUnit(candidate + ".java")) candidate += ".java";
            }
            return candidate;
        }

        private Invocation prepareImportedInvocation(Ast.ImportKind kind, String name, List<Object> args) {
            Ast.FunctionDecl fn = findFunction(name);
            if (fn == null || fn.visibility() != Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException("code unit '" + codeUnitId
                        + "' does not export callable '" + name + "'");
            }
            if (kind == Ast.ImportKind.FUNCTION) {
                if (fn.kind() != Ast.CallableKind.FNC || fn.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException("import fnc requires a reifiable non-actor fnc; '" + name
                            + "' is direct-call-only or actor-scheduled");
                }
                if (!fn.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException(
                            "import fnc requires a reifiable non-generic fnc; '" + name
                                    + "' requires direct-call specialization");
                }
                return functionInvocation(fn, args);
            }
            if (kind == Ast.ImportKind.ALL) {
                return functionInvocation(fn, args);
            }
            throw new IllegalArgumentException("import namespace kind " + kind
                    + " does not expose direct callable '" + name + "'");
        }

        private Object invokeImportedCallable(Ast.ImportKind kind, String name, List<Object> args) {
            return invoke(prepareImportedInvocation(kind, name, args));
        }

        private Object exportValue(Ast.ImportKind kind, String name) {
            return switch (kind) {
                case FUNCTION -> {
                    Ast.FunctionDecl fn = findFunction(name);
                    if (fn == null || fn.visibility() != Ast.Visibility.PUBLIC) {
                        throw new IllegalArgumentException("code unit '" + codeUnitId + "' does not export function '" + name + "'");
                    }
                    if (fn.kind() != Ast.CallableKind.FNC || fn.actorKind() != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException("import fnc requires a reifiable non-actor fnc; '" + name
                                + "' is direct-call-only or actor-scheduled");
                    }
                    if (!fn.genericParameters().isEmpty()) {
                        throw new IllegalArgumentException(
                                "generic fnc '" + name
                                        + "' must be specialized by a direct call; "
                                        + "polymorphic function values are not supported yet");
                    }
                    yield tailCallable(args -> callFunctionRaw(fn, objectArguments(args)));
                }
                case CLASS -> {
                    Ast.ClassDecl klass = findClass(name);
                    if (klass == null || klass.actorKind() != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException("code unit '" + codeUnitId + "' does not export ordinary class '" + name + "'");
                    }
                    yield new ClassFacade(this, klass);
                }
                case ACTOR, INTERFACE, TRAIT, STRUCT, TYPE, TYPES ->
                        throw new IllegalArgumentException(
                                "import " + kind.name().toLowerCase()
                                        + " is a type-only selector and cannot be evaluated as a runtime value");
                case MODULE -> {
                    Ast.ModuleDecl module = modules.get(name);
                    if (module == null) throw new IllegalArgumentException("code unit '" + codeUnitId + "' does not export module '" + name + "'");
                    yield new ModuleFacade(this, module);
                }
                case ALL -> exportAny(name);
            };
        }

        private Object exportAny(String name) {
            Ast.ModuleDecl module = modules.get(name);
            if (module != null && !module.name().equals(Parser.ROOT_MODULE)) return new ModuleFacade(this, module);
            Ast.ClassDecl klass = findClass(name);
            if (klass != null) return new ClassFacade(this, klass);
            Ast.FunctionDecl fn = findFunction(name);
            if (fn != null && fn.visibility() == Ast.Visibility.PUBLIC) {
                if (fn.kind() == Ast.CallableKind.ROUTINE) {
                    throw new IllegalArgumentException(
                            "routine '" + name
                                    + "' is direct-call-only and cannot be extracted through a wildcard import namespace");
                }
                if (fn.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException(
                            "actor callable '" + name
                                    + "' is scheduler-dispatched and cannot be extracted as a first-class callable value");
                }
                if (!fn.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException(
                            "generic fnc '" + name
                                    + "' must be specialized by a direct call; "
                                    + "polymorphic function values are not supported yet");
                }
                return tailCallable(args -> callFunctionRaw(fn, objectArguments(args)));
            }
            for (Ast.ModuleDecl candidate : program.modules()) {
                for (Ast.Decl decl : candidate.declarations()) {
                    if (decl instanceof Ast.FieldDecl field
                            && field.visibility() == Ast.Visibility.PUBLIC
                            && field.name().equals(name)) {
                        if (field.initializer() == null) throw new IllegalArgumentException("exported binding has no initializer: " + name);
                        return eval(field.initializer(), new Env(null));
                    }
                }
            }
            throw new IllegalArgumentException("code unit '" + codeUnitId + "' does not export '" + name + "'");
        }

        private OresObject instantiate(Ast.ClassDecl klass, List<Object> args) {
            if (klass.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalStateException("actor '" + klass.name()
                        + "' cannot be constructed with new; actor state must be initialized inside ActorRuntime");
            }
            List<Ast.FieldDecl> classFields = effectiveFields(klass, new LinkedHashSet<>());
            if (args.size() > classFields.size()) throw new IllegalArgumentException("too many constructor arguments for " + klass.name());
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            Env env = new Env(null, false, klass);
            for (int i = 0; i < classFields.size(); i++) {
                Ast.FieldDecl field = classFields.get(i);
                Object value;
                if (i < args.size()) value = args.get(i);
                else if (field.initializer() != null) value = eval(field.initializer(), env);
                else throw new IllegalArgumentException("missing constructor field " + klass.name() + "." + field.name());
                fields.put(field.name(), value);
            }
            return new OresObject(this, klass, fields);
        }

        private static String normalizeUnitId(String id) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("code unit id cannot be blank");
            return Path.of(id).normalize().toString().replace('\\', '/');
        }

        private Object moduleMember(Ast.ModuleDecl module, String name) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass && klass.name().equals(name)) {
                    return new ClassFacade(this, klass);
                }
                if (decl instanceof Ast.FunctionDecl fn && fn.name().equals(name) && fn.visibility() == Ast.Visibility.PUBLIC) {
                    if (fn.kind() == Ast.CallableKind.ROUTINE || fn.actorKind() != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException("callable '" + module.name() + "." + name
                                + "' is direct-call-only and cannot be extracted as a value");
                    }
                    if (!fn.genericParameters().isEmpty()) {
                        throw new IllegalArgumentException(
                                "generic fnc '" + module.name() + "." + name
                                        + "' must be specialized by a direct call; "
                                        + "polymorphic function values are not supported yet");
                    }
                    return tailCallable(args -> callFunctionRaw(fn, objectArguments(args)));
                }
                if (decl instanceof Ast.FieldDecl field && field.name().equals(name) && field.visibility() == Ast.Visibility.PUBLIC) {
                    if (field.initializer() == null) throw new IllegalArgumentException("module field has no initializer: " + module.name() + "." + name);
                    return eval(field.initializer(), new Env(null));
                }
            }
            throw new IllegalArgumentException("module '" + module.name() + "' does not export '" + name + "'");
        }

        private Invocation prepareModuleInvocation(Ast.ModuleDecl module, String name, List<Object> args) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn
                        && fn.name().equals(name)
                        && fn.visibility() == Ast.Visibility.PUBLIC) {
                    return functionInvocation(fn, args);
                }
            }
            throw new IllegalArgumentException("module '" + module.name()
                    + "' does not export callable '" + name + "'");
        }

        private Object invokeModuleFunction(Ast.ModuleDecl module, String name, List<Object> args) {
            return invoke(prepareModuleInvocation(module, name, args));
        }

        private boolean hasInstanceMethodNamed(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) return false;
            for (Ast.MethodDecl method : klass.methods()) {
                if (!method.isStatic() && method.name().equals(name)) {
                    seen.remove(klass);
                    return true;
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent != null && hasInstanceMethodNamed(parent, name, seen)) {
                    seen.remove(klass);
                    return true;
                }
            }
            seen.remove(klass);
            return false;
        }

        private List<Ast.FieldDecl> effectiveFields(Ast.ClassDecl klass, Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            LinkedHashMap<String, Ast.FieldDecl> result = new LinkedHashMap<>();
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent == null) throw new IllegalArgumentException("unknown parent class " + parentRef.name());
                for (Ast.FieldDecl field : effectiveFields(parent, seen)) result.putIfAbsent(field.name(), field);
            }
            for (Ast.FieldDecl field : klass.fields()) result.put(field.name(), field);
            seen.remove(klass);
            return List.copyOf(result.values());
        }

        private record OwnedField(Ast.ClassDecl owner, Ast.FieldDecl field) { }

        private Ast.ClassDecl declaringClass(Ast.MethodDecl method) {
            Ast.ClassDecl owner = methodOwners.get(method);
            if (owner == null) {
                throw new IllegalStateException(
                        "cannot find declaring class for method '" + method.name() + "'");
            }
            return owner;
        }

        private void requireClassMemberVisible(
                Ast.Visibility visibility,
                Ast.ClassDecl owner,
                Ast.ClassDecl accessClass,
                String kind,
                String name) {
            if (visibility == Ast.Visibility.PRIVATE && accessClass != owner) {
                throw new IllegalArgumentException(
                        "private " + kind + " '" + owner.name() + "." + name
                                + "' is accessible only from code declared in class " + owner.name());
            }
        }

        private OwnedField findField(
                Ast.ClassDecl klass,
                String name,
                Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) {
                throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            }
            for (Ast.FieldDecl field : klass.fields()) {
                if (field.name().equals(name)) {
                    seen.remove(klass);
                    return new OwnedField(klass, field);
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent == null) continue;
                OwnedField candidate = findField(parent, name, seen);
                if (candidate != null) {
                    seen.remove(klass);
                    return candidate;
                }
            }
            seen.remove(klass);
            return null;
        }

        private Ast.MethodDecl findMethod(
                Ast.ClassDecl klass,
                CallableSelector selector,
                Set<Ast.ClassDecl> seen) {
            if (selector.kind() != CallableSelector.Kind.INSTANCE) {
                throw new IllegalArgumentException("instance dispatch requires an INSTANCE selector");
            }
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            for (Ast.MethodDecl method : klass.methods()) {
                if (selector.matches(method)) {
                    seen.remove(klass);
                    return method;
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent == null) continue;
                Ast.MethodDecl candidate = findMethod(parent, selector, seen);
                if (candidate != null) {
                    seen.remove(klass);
                    return candidate;
                }
            }
            seen.remove(klass);
            return null;
        }

        private Ast.MethodDecl findStaticFunction(
                Ast.ClassDecl klass,
                CallableSelector selector,
                Set<Ast.ClassDecl> seen) {
            if (selector.kind() != CallableSelector.Kind.STATIC) {
                throw new IllegalArgumentException("static dispatch requires a STATIC selector");
            }
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            for (Ast.MethodDecl fn : klass.methods()) {
                if (selector.matches(fn)) {
                    seen.remove(klass);
                    return fn;
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent == null) continue;
                Ast.MethodDecl candidate = findStaticFunction(parent, selector, seen);
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
            LinkedHashMap<Integer, Ast.MethodDecl> result = new LinkedHashMap<>();
            for (Ast.MethodDecl fn : klass.methods()) {
                if (fn.isStatic() && fn.name().equals(name)) result.put(fn.arity(), fn);
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent == null) continue;
                for (Ast.MethodDecl fn : findStaticFunctionsByName(parent, name, seen)) result.putIfAbsent(fn.arity(), fn);
            }
            seen.remove(klass);
            return List.copyOf(result.values());
        }

        private Iterable<?> iterableValues(Object value, Env env) {
            if (value instanceof List<?> list) return list;
            if (value instanceof Object[] array) return List.of(array);
            if (value instanceof GeneratorRuntime.Generator<?> generator) return generator;
            if (value instanceof GeneratorRuntime.AsyncGenerator<?>) {
                throw new IllegalArgumentException(
                        "AsyncGenerator values require 'for await ... of ...'");
            }
            if (value instanceof OresObject object) {
                Ast.MethodDecl iterator = findMethod(
                        object.klass,
                        CallableSelector.instance("Symbol.iterator", 0),
                        new LinkedHashSet<>());
                if (iterator == null) throw new IllegalArgumentException("value has no [Symbol.iterator]()");
                object.owner.requireClassMemberVisible(
                        iterator.visibility(),
                        object.owner.declaringClass(iterator),
                        env == null ? null : env.accessClass(),
                        "method",
                        iterator.name());
                Object produced = callMethod(object, iterator, List.of());
                return iterableValues(produced, env);
            }
            throw new IllegalArgumentException("value is not synchronously iterable");
        }

        private Iterable<?> asyncIterableValues(Object value, Env env) {
            if (value instanceof OresObject object) {
                Ast.MethodDecl iterator =
                        findMethod(object.klass, CallableSelector.instance("Symbol.asyncIterator", 0), new LinkedHashSet<>());
                if (iterator == null) {
                    return asyncSynchronousIterableValues(value, env);
                }
                object.owner.requireClassMemberVisible(
                        iterator.visibility(),
                        object.owner.declaringClass(iterator),
                        env == null ? null : env.accessClass(),
                        "method",
                        iterator.name());
                return asyncIterableValues(callMethod(object, iterator, List.of()), env);
            }
            if (!(value instanceof GeneratorRuntime.AsyncGenerator<?> generator)) {
                return asyncSynchronousIterableValues(value, env);
            }

            final class AsyncPullIterable implements Iterable<Object>, AutoCloseable {
                @Override
                public java.util.Iterator<Object> iterator() {
                    return new java.util.Iterator<>() {
                        private GeneratorRuntime.Step<?> buffered;

                        @Override
                        public boolean hasNext() {
                            if (buffered == null) {
                                buffered = AsyncRuntime.await(generator.nextStep());
                            }
                            return !buffered.done();
                        }

                        @Override
                        public Object next() {
                            if (!hasNext()) throw new java.util.NoSuchElementException();
                            Object result = buffered.value();
                            buffered = null;
                            return result;
                        }
                    };
                }

                @Override
                public void close() {
                    generator.close();
                }
            }
            return new AsyncPullIterable();
        }

        private Iterable<?> asyncSynchronousIterableValues(Object value, Env env) {
            Iterable<?> synchronous = iterableValues(value, env);
            GeneratorRuntime.AsyncGenerator<Object> adapter = GeneratorRuntime.asyncGenerator(
                    context.asyncRuntime(), emitter -> {
                        try {
                            for (Object item : synchronous) emitter.emit(item);
                        } finally {
                            closeIterable(synchronous);
                        }
                    });
            return asyncIterableValues(adapter, env);
        }

        private void closeIterable(Iterable<?> iterable) {
            if (!(iterable instanceof AutoCloseable closeable)) return;
            try {
                closeable.close();
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("failed to close generator activation", failure);
            }
        }

        private ConditionResult evalCondition(Ast.Expr condition, Env env) {
            if (condition instanceof Ast.BinaryExpr binary && binary.operator().equals("&&")) {
                ConditionResult left = evalCondition(binary.left(), env);
                if (!left.matched()) return ConditionResult.noMatch();
                Env rightEnv = new Env(env);
                left.bindings().forEach((name, value) ->
                        rightEnv.define(name, value, Ast.BindingKind.VAL));
                ConditionResult right = evalCondition(binary.right(), rightEnv);
                if (!right.matched()) return ConditionResult.noMatch();
                LinkedHashMap<String, Object> merged = new LinkedHashMap<>(left.bindings());
                for (Map.Entry<String, Object> entry : right.bindings().entrySet()) {
                    Object previous = merged.putIfAbsent(entry.getKey(), entry.getValue());
                    if (previous != null && previous != entry.getValue()) {
                        throw new IllegalStateException("condition pattern binds '" + entry.getKey() + "' more than once");
                    }
                }
                return new ConditionResult(true, Map.copyOf(merged));
            }
            if (condition instanceof Ast.TypeTestExpr test) {
                Object value = eval(test.value(), env);
                if (!oresTypeMatches(value, test.targetType())) return ConditionResult.noMatch();
                if (test.binding() == null) return ConditionResult.match();
                return new ConditionResult(true, Map.of(test.binding(), value));
            }
            if (condition instanceof Ast.PatternTestExpr test) {
                Object value = eval(test.value(), env);
                LinkedHashMap<String, Object> bindings = new LinkedHashMap<>();
                return patternMatches(test.pattern(), value, bindings)
                        ? new ConditionResult(true, Map.copyOf(bindings))
                        : ConditionResult.noMatch();
            }
            return truth(eval(condition, env)) ? ConditionResult.match() : ConditionResult.noMatch();
        }

        private boolean patternMatches(Ast.Pattern pattern, Object value, Map<String, Object> bindings) {
            if (pattern instanceof Ast.WildcardPattern) return true;
            if (pattern instanceof Ast.BindingPattern binding) {
                if (bindings.putIfAbsent(binding.name(), value) != null) {
                    throw new IllegalStateException("pattern binds '" + binding.name() + "' more than once");
                }
                return true;
            }
            if (pattern instanceof Ast.LiteralPattern literal) {
                return Objects.equals(literal.value(), value);
            }
            if (pattern instanceof Ast.TypePattern typed) {
                if (!oresTypeMatches(value, typed.type())) return false;
                if (typed.binding() != null && bindings.putIfAbsent(typed.binding(), value) != null) {
                    throw new IllegalStateException("pattern binds '" + typed.binding() + "' more than once");
                }
                return true;
            }
            if (pattern instanceof Ast.ConstructorPattern constructor) {
                String name = constructor.constructor();
                if (name.equals("Some")) {
                    if (!(value instanceof OptionValue option) || !option.present() || constructor.arguments().size() != 1) return false;
                    return patternMatches(constructor.arguments().getFirst(), option.value(), bindings);
                }
                if (name.equals("None")) {
                    return value instanceof OptionValue option && !option.present() && constructor.arguments().isEmpty();
                }
                if (name.equals("Ok")) {
                    if (!(value instanceof ResultValue result) || !result.ok() || constructor.arguments().size() != 1) return false;
                    return patternMatches(constructor.arguments().getFirst(), result.value(), bindings);
                }
                if (name.equals("Err")) {
                    if (!(value instanceof ResultValue result) || result.ok() || constructor.arguments().size() != 1) return false;
                    return patternMatches(constructor.arguments().getFirst(), result.value(), bindings);
                }
                return false;
            }
            return false;
        }

        /**
         * Host-neutral Oreslang type relation. The Truffle bootstrap evaluator reads Oreslang
         * metadata here; native lowering must use the same relation against native type tags
         * (directly or through the narrow JNI bridge), never JVM Class.isInstance/instanceof.
         */
        private boolean oresTypeMatches(Object value, Ast.TypeRef target) {
            if (target.isUnion()) {
                for (Ast.TypeRef option : target.arguments()) {
                    if (oresTypeMatches(value, option)) return true;
                }
                return false;
            }
            String name = target.name();
            if (name.equals("int") || name.equals("i8") || name.equals("i16") || name.equals("i32")
                    || name.equals("i64") || name.equals("u8") || name.equals("u16")
                    || name.equals("u32") || name.equals("u64") || name.equals("uint")
                    || name.equals("bigint")) return value instanceof Byte || value instanceof Short
                            || value instanceof Integer || value instanceof Long;
            if (name.equals("float") || name.equals("f32") || name.equals("f64")
                    || name.equals("decimal")) return value instanceof Float || value instanceof Double;
            if (name.equals("bool") || name.equals("Bool")) return value instanceof Boolean;
            if (name.equals("string") || name.equals("String")) return value instanceof String;
            if (name.equals("complex") || name.equals("complex64") || name.equals("complex128")) return value instanceof Complex;
            if (name.equals("Option")) return value instanceof OptionValue;
            if (name.equals("Result")) return value instanceof ResultValue;
            if (name.equals("DynamicStruct")) return value instanceof DynamicStructValue;
            if (name.equals("Array") || name.equals("List")) return value instanceof List<?>;
            if (name.equals("Generator") || name.equals("Iterator")) return value instanceof GeneratorRuntime.Generator<?>;
            if (name.equals("AsyncGenerator") || name.equals("AsyncIterator")) return value instanceof GeneratorRuntime.AsyncGenerator<?>;
            if (name.equals("IteratorResult")) return value instanceof GeneratorRuntime.Step<?>;

            if (value instanceof OresObject object) {
                Ast.ClassDecl targetClass = findClass(name);
                if (targetClass != null) return classIsA(object.klass, targetClass, new LinkedHashSet<>());
                Ast.InterfaceDecl targetInterface = findInterface(name);
                if (targetInterface != null) {
                    return classImplements(object.klass, targetInterface, new LinkedHashSet<>(), new LinkedHashSet<>());
                }
            }

            Ast.TypeAliasDecl alias = findTypeAlias(name);
            if (alias != null && alias.genericParameters().isEmpty()) {
                return oresTypeMatches(value, alias.target());
            }

            // Java host objects are intentionally not part of Oreslang nominal type identity.
            // Interop must cross an explicit capability/adapter boundary.
            return false;
        }

        private String oresRuntimeTypeName(Object value) {
            if (value instanceof OresObject object) return object.klass.name();
            if (value instanceof OptionValue) return "Option";
            if (value instanceof ResultValue) return "Result";
            if (value instanceof GeneratorRuntime.Generator<?>) return "Iterator";
            if (value instanceof GeneratorRuntime.AsyncGenerator<?>) return "AsyncIterator";
            if (value instanceof GeneratorRuntime.Step<?>) return "IteratorResult";
            if (value instanceof DynamicStructValue) return "DynamicStruct";
            if (value instanceof List<?>) return "List";
            if (value instanceof String) return "string";
            if (value instanceof Boolean) return "bool";
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return "int";
            if (value instanceof Float || value instanceof Double) return "float";
            if (value instanceof Complex) return "complex";
            if (value instanceof HostObjectFacade) return "<host-object>";
            return "<unknown>";
        }

        private boolean classIsA(Ast.ClassDecl actual, Ast.ClassDecl target, Set<Ast.ClassDecl> seen) {
            if (actual == target || actual.name().equals(target.name())) return true;
            if (!seen.add(actual)) return false;
            for (Ast.TypeRef parentRef : actual.parents()) {
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent != null && classIsA(parent, target, seen)) return true;
            }
            return false;
        }

        private boolean classImplements(
                Ast.ClassDecl actual,
                Ast.InterfaceDecl target,
                Set<Ast.ClassDecl> seenClasses,
                Set<Ast.InterfaceDecl> seenInterfaces) {
            if (!seenClasses.add(actual)) return false;
            for (Ast.TypeRef interfaceRef : actual.interfaces()) {
                Ast.InterfaceDecl iface = findInterface(interfaceRef.name());
                if (iface != null && (iface == target || iface.name().equals(target.name())
                        || interfaceExtends(iface, target, seenInterfaces))) return true;
            }
            for (Ast.TypeRef parentRef : actual.parents()) {
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent != null && classImplements(parent, target, seenClasses, seenInterfaces)) return true;
            }
            return false;
        }

        private boolean interfaceExtends(
                Ast.InterfaceDecl actual, Ast.InterfaceDecl target, Set<Ast.InterfaceDecl> seen) {
            if (actual == target || actual.name().equals(target.name())) return true;
            if (!seen.add(actual)) return false;
            for (Ast.TypeRef parentRef : actual.parents()) {
                Ast.InterfaceDecl parent = findInterface(parentRef.name());
                if (parent != null && interfaceExtends(parent, target, seen)) return true;
            }
            return false;
        }

        private record ConditionResult(boolean matched, Map<String, Object> bindings) {
            private static ConditionResult match() { return new ConditionResult(true, Map.of()); }
            private static ConditionResult noMatch() { return new ConditionResult(false, Map.of()); }
        }

        private Object binary(String op, Object left, Object right) {
            return switch (op) {
                case "+" -> add(left, right); case "-" -> numeric(left, right, '-'); case "*" -> numeric(left, right, '*');
                case "/" -> numeric(left, right, '/'); case "%" -> numeric(left, right, '%');
                case "==" -> Objects.equals(left, right); case "!=" -> !Objects.equals(left, right);
                case "<" -> compare(left, right) < 0; case "<=" -> compare(left, right) <= 0;
                case ">" -> compare(left, right) > 0; case ">=" -> compare(left, right) >= 0;
                case "&" -> integralLong(left) & integralLong(right);
                case "|" -> integralLong(left) | integralLong(right);
                case "^" -> integralLong(left) ^ integralLong(right);
                case "<<" -> integralLong(left) << shiftDistance(right);
                case ">>" -> integralLong(left) >> shiftDistance(right);
                case ">>>" -> integralLong(left) >>> shiftDistance(right);
                default -> throw new IllegalArgumentException("unsupported operator " + op);
            };
        }

        private Object add(Object left, Object right) {
            if (left instanceof String && right instanceof String) return ((String) left) + right;
            return numeric(left, right, '+');
        }

        private Object numeric(Object left, Object right, char op) {
            if (left instanceof Complex || right instanceof Complex) {
                Complex a = asComplex(left), b = asComplex(right);
                return switch (op) {
                    case '+' -> a.add(b); case '-' -> a.sub(b); case '*' -> a.mul(b); case '/' -> a.div(b);
                    default -> throw new IllegalArgumentException("operator " + op + " is not supported for complex numbers");
                };
            }
            if (!(left instanceof Number a) || !(right instanceof Number b)) throw new IllegalArgumentException("numeric operator requires numbers");
            boolean integral = isIntegral(a) && isIntegral(b) && op != '/';
            if (integral) {
                long x = a.longValue(), y = b.longValue();
                return switch (op) { case '+' -> x + y; case '-' -> x - y; case '*' -> x * y; case '%' -> x % y; default -> throw new IllegalArgumentException("bad numeric operator"); };
            }
            double x = a.doubleValue(), y = b.doubleValue();
            return switch (op) { case '+' -> x + y; case '-' -> x - y; case '*' -> x * y; case '/' -> x / y; case '%' -> x % y; default -> throw new IllegalArgumentException("bad numeric operator"); };
        }

        private long integralLong(Object value) {
            if (!(value instanceof Number number) || !isIntegral(number)) {
                throw new IllegalArgumentException("bitwise operator requires integer operands");
            }
            return number.longValue();
        }

        private int shiftDistance(Object value) {
            long distance = integralLong(value);
            if (distance < 0 || distance > 63) {
                throw new IllegalArgumentException("shift distance must be between 0 and 63");
            }
            return (int) distance;
        }

        private Object negate(Object value) {
            if (value instanceof Complex c) return new Complex(-c.real, -c.imaginary);
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return -((Number) value).longValue();
            if (value instanceof Number number) return -number.doubleValue();
            throw new IllegalArgumentException("unary - requires a number");
        }

        private int compare(Object left, Object right) {
            if (left instanceof Number a && right instanceof Number b) return Double.compare(a.doubleValue(), b.doubleValue());
            if (left instanceof String a && right instanceof String b) return a.compareTo(b);
            throw new IllegalArgumentException("values are not comparable");
        }

        private boolean truth(Object value) { if (value instanceof Boolean b) return b; throw new IllegalArgumentException("condition must be bool"); }
        private boolean isIntegral(Number value) { return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long; }
        private Complex asComplex(Object value) { if (value instanceof Complex c) return c; if (value instanceof Number n) return new Complex(n.doubleValue(),0); throw new IllegalArgumentException("value is not numeric"); }
        private Object shapeReturnedValue(Ast.TypeRef declared, Object value, String callable) {
            return shapeReturnedValue(declared, value, callable, new LinkedHashSet<>());
        }

        private Object shapeReturnedValue(Ast.TypeRef declared, Object value, String callable, Set<Ast.TypeAliasDecl> resolving) {
            if (declared == null) return value;

            Ast.TypeAliasDecl alias = findTypeAlias(declared.name());
            if (alias != null) {
                if (alias.genericParameters().size() != declared.arguments().size()) {
                    throw new IllegalArgumentException("type alias '" + alias.name() + "' expects "
                            + alias.genericParameters().size() + " type argument(s), got " + declared.arguments().size());
                }
                if (!resolving.add(alias)) throw new IllegalArgumentException("type alias cycle involving '" + alias.name() + "'");
                try {
                    Map<String, Ast.TypeRef> substitutions = new HashMap<>();
                    for (int i = 0; i < alias.genericParameters().size(); i++) {
                        substitutions.put(alias.genericParameters().get(i), declared.arguments().get(i));
                    }
                    return shapeReturnedValue(substituteReturnType(alias.target(), substitutions), value, callable, resolving);
                } finally {
                    resolving.remove(alias);
                }
            }

            if (declared.isUnion()) {
                List<String> failures = new ArrayList<>();
                for (Ast.TypeRef option : declared.arguments()) {
                    try {
                        return shapeReturnedValue(option, value, callable, new LinkedHashSet<>(resolving));
                    } catch (IllegalArgumentException error) {
                        failures.add(error.getMessage());
                    }
                }
                throw new IllegalArgumentException(callable + " return value does not match any union alternative: " + failures);
            }

            if (declared.isTupleType()) {
                List<?> items = asSequence(value);
                if (items.size() != declared.arguments().size()) {
                    throw new IllegalArgumentException(callable + " returned " + items.size()
                            + " tuple element(s), expected " + declared.arguments().size());
                }
                Object[] fixed = items.toArray();
                for (int i = 0; i < fixed.length; i++) {
                    fixed[i] = shapeReturnedValue(declared.arguments().get(i), fixed[i], callable + " tuple[" + i + "]", resolving);
                }
                return java.util.Arrays.asList(fixed);
            }

            if (declared.isRecordType()) {
                for (Map.Entry<String, Ast.TypeRef> member : declared.recordMembers().entrySet()) {
                    Object nested = destructureMember(value, member.getKey());
                    shapeReturnedValue(member.getValue(), nested, callable + "." + member.getKey(), resolving);
                }
                return value;
            }

            if (declared.name().equals("Array") || declared.name().equals("List")) {
                if (declared.arguments().size() != 1) return value;
                List<?> items = asSequence(value);
                ArrayList<Object> shaped = new ArrayList<>(items.size());
                for (int i = 0; i < items.size(); i++) {
                    shaped.add(shapeReturnedValue(declared.arguments().getFirst(), items.get(i), callable + "[" + i + "]", resolving));
                }
                return shaped;
            }

            if (declared.name().equals("DynamicStruct")) {
                if (declared.arguments().size() != 1 || !(value instanceof DynamicStructValue dynamic)) {
                    throw returnTypeMismatch(callable, declared, value);
                }
                for (Map.Entry<String, Object> entry : dynamic.fields.entrySet()) {
                    shapeReturnedValue(
                            declared.arguments().getFirst(),
                            entry.getValue(),
                            callable + "[" + entry.getKey() + "]",
                            resolving);
                }
                return value;
            }

            if (declared.name().equals("bool") || declared.name().equals("Bool")) {
                if (!(value instanceof Boolean)) throw returnTypeMismatch(callable, declared, value);
                return value;
            }
            if (declared.name().equals("string") || declared.name().equals("String")) {
                if (!(value instanceof String)) throw returnTypeMismatch(callable, declared, value);
                return value;
            }
            if (java.util.Set.of("i8","i16","i32","i64","u8","u16","u32","u64","int","uint","bigint").contains(declared.name())) {
                if (!(value instanceof Number number) || !isIntegral(number)) throw returnTypeMismatch(callable, declared, value);
                return value;
            }
            if (java.util.Set.of("f32","f64","float","decimal").contains(declared.name())) {
                if (!(value instanceof Number)) throw returnTypeMismatch(callable, declared, value);
                return value;
            }
            if (java.util.Set.of("complex64","complex128","complex").contains(declared.name())) {
                if (!(value instanceof Number) && !(value instanceof Complex)) throw returnTypeMismatch(callable, declared, value);
                return value;
            }
            if (declared.name().equals("void")) {
                if (value != null) throw returnTypeMismatch(callable, declared, value);
                return null;
            }

            return value;
        }

        private Ast.TypeRef substituteReturnType(Ast.TypeRef ref, Map<String, Ast.TypeRef> substitutions) {
            Ast.TypeRef replacement = substitutions.get(ref.name());
            if (replacement != null && ref.arguments().isEmpty() && !ref.inferArguments()) return replacement;
            return new Ast.TypeRef(
                    ref.name(),
                    ref.arguments().stream().map(arg -> substituteReturnType(arg, substitutions)).toList(),
                    ref.inferArguments());
        }

        private IllegalArgumentException returnTypeMismatch(String callable, Ast.TypeRef declared, Object value) {
            return new IllegalArgumentException(callable + " returned " + (value == null ? "null" : value.getClass().getSimpleName())
                    + " but declared " + declared);
        }

        private List<?> asSequence(Object value) { if (value instanceof List<?> l) return l; if (value instanceof Object[] a) return List.of(a); throw new IllegalArgumentException("value is not sequence-destructurable"); }

        private Object destructureMember(Object value, String name, Env env) {
            if (value instanceof OresObject object) {
                OwnedField ownedField = object.owner.findField(
                        object.klass, name, new LinkedHashSet<>());
                if (ownedField != null) {
                    object.owner.requireClassMemberVisible(
                            ownedField.field().visibility(),
                            ownedField.owner(),
                            env == null ? null : env.accessClass(),
                            "field",
                            ownedField.field().name());
                }
            }
            return destructureMember(value, name);
        }

        private Object destructureMember(Object value, String name) {
            if (value instanceof DynamicStructValue dynamic) {
                if (!dynamic.fields.containsKey(name)) {
                    throw new IllegalArgumentException("object destructure missing member " + name);
                }
                return dynamic.fields.get(name);
            }
            if (value instanceof Map<?, ?> map) {
                if (!map.containsKey(name)) throw new IllegalArgumentException("object destructure missing member " + name);
                return map.get(name);
            }
            if (value instanceof OresObject object) {
                if (!object.fields.containsKey(name)) throw new IllegalArgumentException("object destructure missing field " + name);
                return object.fields.get(name);
            }
            throw new IllegalArgumentException("value is not object-destructurable");
        }
        private String display(Object value) { return value instanceof Complex c ? c.toString() : String.valueOf(value); }
    }

    @FunctionalInterface private interface Invokable { Object call(List<Object> arguments); }
    private interface TailInvokable extends Invokable { }

    private record TailCallable(Evaluator owner, Invokable delegate) implements TailInvokable {
        @Override public Object call(List<Object> arguments) {
            return delegate.call(arguments);
        }
    }

    private static final class Env {
        private static final Object MISSING = new Object();
        private final Env parent;
        private final boolean descendantsNonLexical;
        private final Ast.ClassDecl accessClass;
        private final Map<String, Slot> slots = new HashMap<>();
        private Env(Env parent) {
            this(
                    parent,
                    parent != null && parent.descendantsNonLexical,
                    parent == null ? null : parent.accessClass);
        }
        private Env(Env parent, boolean descendantsNonLexical) {
            this(
                    parent,
                    descendantsNonLexical,
                    parent == null || descendantsNonLexical ? null : parent.accessClass);
        }
        private Env(Env parent, boolean descendantsNonLexical, Ast.ClassDecl accessClass) {
            this.parent = parent;
            this.descendantsNonLexical = descendantsNonLexical;
            this.accessClass = accessClass;
        }
        private boolean descendantsNonLexical() { return descendantsNonLexical; }
        private Ast.ClassDecl accessClass() { return accessClass; }
        private void define(String name, Object value, Ast.BindingKind kind) {
            if (slots.putIfAbsent(name, new Slot(value, kind)) != null) throw new IllegalArgumentException("duplicate binding " + name);
        }
        private void reserve(String name, Ast.BindingKind kind) {
            if (slots.putIfAbsent(name, new Slot(MISSING, kind)) != null) throw new IllegalArgumentException("duplicate binding " + name);
        }
        private void initialize(String name, Object value) {
            Slot slot = slots.get(name);
            if (slot == null) throw new IllegalArgumentException("unknown binding " + name);
            slot.value = value;
        }
        private Object lookup(String name) { Slot s=slots.get(name); return s!=null?s.value:parent==null?MISSING:parent.lookup(name); }
        private void assign(String name, Object value) {
            Slot slot = slots.get(name);
            if (slot != null) {
                if (slot.kind != Ast.BindingKind.LET) throw new IllegalArgumentException("cannot reassign " + slot.kind.name().toLowerCase() + " binding " + name);
                slot.value = value;
                return;
            }
            if (parent != null) { parent.assign(name, value); return; }
            throw new IllegalArgumentException("unknown binding " + name);
        }
        private Env snapshot() {
            Env cp = new Env(
                    parent == null ? null : parent.snapshot(),
                    descendantsNonLexical,
                    accessClass);
            cp.slots.putAll(slots);
            return cp;
        }
        private boolean hasLiveMutexGuards() {
            Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Slot slot : slots.values()) {
                if (containsLiveMutexGuard(slot.value, seen)) return true;
            }
            return parent != null && parent.hasLiveMutexGuards();
        }

        private static boolean containsLiveMutexGuard(Object value, Set<Object> seen) {
            if (value == null) return false;
            if (value instanceof OresMutex.Guard<?> guard) return !guard.released();
            if (!seen.add(value)) return false;

            if (value instanceof OptionValue option) {
                return option.present() && containsLiveMutexGuard(option.value(), seen);
            }
            if (value instanceof ResultValue result) {
                return containsLiveMutexGuard(result.value(), seen);
            }
            if (value instanceof GeneratorRuntime.Step<?> step) {
                return !step.done() && containsLiveMutexGuard(step.value(), seen);
            }
            if (value instanceof OresMutex.GuardFuture<?> future) {
                return future.isDone()
                        && !future.isCancelled()
                        && !future.isCompletedExceptionally()
                        && containsLiveMutexGuard(future.getNow(null), seen);
            }
            if (value instanceof OresObject object) {
                for (Object field : object.fields.values()) {
                    if (containsLiveMutexGuard(field, seen)) return true;
                }
                return false;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) if (containsLiveMutexGuard(item, seen)) return true;
                return false;
            }
            if (value instanceof Set<?> set) {
                for (Object item : set) if (containsLiveMutexGuard(item, seen)) return true;
                return false;
            }
            if (value instanceof DynamicStructValue dynamic) {
                for (Map.Entry<String, Object> entry : dynamic.fields.entrySet()) {
                    if (containsLiveMutexGuard(entry.getKey(), seen)
                            || containsLiveMutexGuard(entry.getValue(), seen)) return true;
                }
                return false;
            }
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (containsLiveMutexGuard(entry.getKey(), seen)
                            || containsLiveMutexGuard(entry.getValue(), seen)) return true;
                }
                return false;
            }
            if (value instanceof Object[] array) {
                for (Object item : array) if (containsLiveMutexGuard(item, seen)) return true;
            }
            return false;
        }

        private void releaseMutexGuards(boolean failed) {
            Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Slot slot : slots.values()) releaseMutexGuardsInValue(slot.value, failed, seen);
        }

        private static void releaseMutexGuardsInValue(Object value, boolean failed, Set<Object> seen) {
            if (value == null) return;
            if (value instanceof OresMutex.Guard<?> guard) {
                if (!guard.released()) {
                    if (failed) guard.fail();
                    else guard.release();
                }
                return;
            }
            if (!seen.add(value)) return;

            if (value instanceof OptionValue option) {
                if (option.present()) releaseMutexGuardsInValue(option.value(), failed, seen);
                return;
            }
            if (value instanceof ResultValue result) {
                releaseMutexGuardsInValue(result.value(), failed, seen);
                return;
            }
            if (value instanceof GeneratorRuntime.Step<?> step) {
                if (!step.done()) releaseMutexGuardsInValue(step.value(), failed, seen);
                return;
            }
            if (value instanceof OresMutex.GuardFuture<?> future) {
                if (!future.isDone()) {
                    future.cancel(true);
                    return;
                }
                if (!future.isCancelled() && !future.isCompletedExceptionally()) {
                    releaseMutexGuardsInValue(future.getNow(null), failed, seen);
                }
                return;
            }
            if (value instanceof OresObject object) {
                for (Object field : object.fields.values()) {
                    releaseMutexGuardsInValue(field, failed, seen);
                }
                return;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) releaseMutexGuardsInValue(item, failed, seen);
                return;
            }
            if (value instanceof Set<?> set) {
                for (Object item : set) releaseMutexGuardsInValue(item, failed, seen);
                return;
            }
            if (value instanceof DynamicStructValue dynamic) {
                for (Map.Entry<String, Object> entry : dynamic.fields.entrySet()) {
                    releaseMutexGuardsInValue(entry.getKey(), failed, seen);
                    releaseMutexGuardsInValue(entry.getValue(), failed, seen);
                }
                return;
            }
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    releaseMutexGuardsInValue(entry.getKey(), failed, seen);
                    releaseMutexGuardsInValue(entry.getValue(), failed, seen);
                }
                return;
            }
            if (value instanceof Object[] array) {
                for (Object item : array) releaseMutexGuardsInValue(item, failed, seen);
            }
        }
    }

    private static final class Slot {
        private Object value;
        private final Ast.BindingKind kind;
        private Slot(Object value, Ast.BindingKind kind) { this.value=value; this.kind=kind; }
    }

    private static final class ReturnSignal extends RuntimeException {
        private final Object value;
        private ReturnSignal(Object value) { super(null,null,false,false); this.value=value; }
    }

    private static final class BreakSignal extends RuntimeException {
        private BreakSignal() { super(null, null, false, false); }
    }

    private static final class ContinueSignal extends RuntimeException {
        private ContinueSignal() { super(null, null, false, false); }
    }

    private enum StartupPhase {
        CREATED,
        LINKED,
        INITIALIZING,
        READY,
        FAILED
    }

    private record Complex(double real, double imaginary) implements OresMutex.SharedState {
        @Override public Iterable<?> sharedStateChildren(){return List.of();}
        private Complex add(Complex o){return new Complex(real+o.real,imaginary+o.imaginary);}
        private Complex sub(Complex o){return new Complex(real-o.real,imaginary-o.imaginary);}
        private Complex mul(Complex o){return new Complex(real*o.real-imaginary*o.imaginary,real*o.imaginary+imaginary*o.real);}
        private Complex div(Complex o){double d=o.real*o.real+o.imaginary*o.imaginary;return new Complex((real*o.real+imaginary*o.imaginary)/d,(imaginary*o.real-real*o.imaginary)/d);}
        @Override public String toString(){return real+(imaginary<0?"":"+")+imaginary+"i";}
    }

    private static final class DynamicStructValue implements OresMutex.SharedState {
        private final LinkedHashMap<String, Object> fields;
        private DynamicStructValue() { this.fields = new LinkedHashMap<>(); }
        private DynamicStructValue(Map<String, Object> initial) { this.fields = new LinkedHashMap<>(initial); }
        @Override public Iterable<?> sharedStateChildren() { return fields.values(); }
        @Override public String toString() { return "DynamicStruct" + fields; }
    }

    private static final class OresObject implements OresMutex.SharedState {
        private final Evaluator owner;
        private final Ast.ClassDecl klass;
        private final Map<String,Object> fields;
        private OresObject(Evaluator owner, Ast.ClassDecl klass, Map<String,Object> fields) {
            this.owner = owner;
            this.klass = klass;
            this.fields = fields;
        }
        @Override public Iterable<?> sharedStateChildren(){return fields.values();}
        @Override public String toString(){return klass.name()+fields;}
    }

    private record ImportedBinding(Ast.ImportDecl declaration, String sourceName) { }
    private record ImportedNamespace(Evaluator owner, Ast.ImportKind kind) { }
    private record ModuleFacade(Evaluator owner, Ast.ModuleDecl module) { }
    private record ClassFacade(Evaluator owner, Ast.ClassDecl klass) { }
    private record HostClassFacade(String className, Object symbol, boolean constructible) { }
    private record HostObjectFacade(Object value) {
        @Override public String toString() { return String.valueOf(value); }
    }
    private enum FutureFactory { INSTANCE }

    private record MutexFactory(boolean shared, OresContext context) {
        private Object create(List<Object> args) {
            requireOne(args, shared ? "SharedMutex.new" : "Mutex.new");
            if (shared) {
                context.requireCapability(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex.new");
                Object value = args.getFirst();
                if (!runtimeSharedSafe(value, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()))) {
                    throw new IllegalArgumentException(
                            "SharedMutex<T> runtime admission rejected non-shared-safe state");
                }
                return OresMutex.shared(value);
            }
            return OresMutex.local(args.getFirst());
        }

        private static boolean runtimeSharedSafe(Object value, Set<Object> seen) {
            if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                    || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double || value instanceof java.math.BigInteger
                    || value instanceof java.math.BigDecimal || value instanceof Enum<?> || value instanceof java.util.UUID
                    || value instanceof Complex || value instanceof ActorRuntime.ActorId || value instanceof ActorRuntime.ActorRef<?>) {
                return true;
            }

            if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>
                    || value instanceof CompletionStage<?> || value instanceof Invokable
                    || value instanceof HostClassFacade || value instanceof HostObjectFacade) {
                return false;
            }

            // Nested shared locks require recursive publication and lock-order
            // semantics that are intentionally not part of the current model.
            if (value instanceof OresMutex.Shared<?>) return false;

            if (!seen.add(value)) return true;

            if (value instanceof OptionValue option) {
                return !option.present() || runtimeSharedSafe(option.value(), seen);
            }
            if (value instanceof GeneratorRuntime.Step<?> step) {
                return step.done() || runtimeSharedSafe(step.value(), seen);
            }
            if (value instanceof ResultValue result) {
                return runtimeSharedSafe(result.value(), seen);
            }
            if (value instanceof OptionUnwrapError) return true;
            if (value instanceof ActorRuntime.Shared<?> readonly) {
                return runtimeSharedSafe(readonly.value(), seen);
            }
            if (value instanceof OresObject object) {
                for (Object field : object.fields.values()) {
                    if (!runtimeSharedSafe(field, seen)) return false;
                }
                return true;
            }
            if (value instanceof DynamicStructValue dynamic) {
                for (Map.Entry<String, Object> entry : dynamic.fields.entrySet()) {
                    if (!runtimeSharedSafe(entry.getKey(), seen)
                            || !runtimeSharedSafe(entry.getValue(), seen)) {
                        return false;
                    }
                }
                return true;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) if (!runtimeSharedSafe(item, seen)) return false;
                return true;
            }
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!runtimeSharedSafe(entry.getKey(), seen) || !runtimeSharedSafe(entry.getValue(), seen)) return false;
                }
                return true;
            }
            if (value instanceof Object[] array) {
                for (Object item : array) if (!runtimeSharedSafe(item, seen)) return false;
                return true;
            }

            // Guest code has no unrestricted host access. Reject unknown host
            // values rather than silently turning SharedMutex into an escape
            // hatch for Java references.
            return false;
        }
    }
    private record OptionValue(boolean present, Object value) implements OresMutex.SharedState {
        @Override public Iterable<?> sharedStateChildren(){return present ? List.of(value) : List.of();}
        @Override public String toString(){return present ? "Some(" + value + ")" : "None";}
    }
    private record ResultValue(boolean ok, Object value) implements OresMutex.SharedState {
        @Override public Iterable<?> sharedStateChildren(){return List.of(value);}
        @Override public String toString(){return ok ? "Ok(" + value + ")" : "Err(" + value + ")";}
    }
    private record OptionUnwrapError(String reason) {
        @Override public String toString(){return "OptionUnwrapError(" + reason + ")";}
    }
    private static final class OresPanic extends RuntimeException {
        private OresPanic(String message) { super(message, null, false, false); }
    }
    private static final class OresCastError extends RuntimeException {
        private OresCastError(String message) { super(message, null, false, false); }
    }
    private record StdioFacade(OresContext context) {
        private Object print(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.print");requireOne(args,"stdio.print");context.output().print(String.valueOf(args.getFirst()));context.output().flush();return null;}
        private Object println(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.println");requireOne(args,"stdio.println");context.output().println(String.valueOf(args.getFirst()));return null;}
    }
    private record StdoutFacade(OresContext context) {
        private Object write(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.stdout.write");requireOne(args,"stdio.stdout.write");context.output().print(String.valueOf(args.getFirst()));context.output().flush();return null;}
        private Object println(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.stdout.println");requireOne(args,"stdio.stdout.println");context.output().println(String.valueOf(args.getFirst()));return null;}
    }
    private record ProcessFacade(OresContext context) {
        private String contextId(){context.requireCapability(IsolatePolicy.Capability.PROCESS_INFO,"process.context_id");return context.contextId().toString();}
        private Map<String,Object> descriptor(){context.requireCapability(IsolatePolicy.Capability.PROCESS_INFO,"process.descriptor");return context.processDescriptor();}
        private Object shareReadonly(List<Object> args){context.requireCapability(IsolatePolicy.Capability.ACTOR_SHARE_READONLY,"process.share_readonly");requireOne(args,"process.share_readonly");return context.actors().shareReadonly(args.getFirst());}
        private Map<String,Object> gc(List<Object> args){context.requireCapability(IsolatePolicy.Capability.GC_CONTROL,"process.gc");requireZero(args,"process.gc");return context.garbageCollector().collectProcess().asMap();}
    }
    private record ActorFacade(OresContext context) {
        private Map<String,Object> gc(List<Object> args){requireZero(args,"actor.gc");return context.garbageCollector().collectCurrentActor().asMap();}
    }
    private static void requireZero(List<Object> args,String name){if(!args.isEmpty())throw new IllegalArgumentException(name+" expects no arguments");}
    private static void requireOne(List<Object> args,String name){if(args.size()!=1)throw new IllegalArgumentException(name+" expects one argument");}
    private static String requireStringArg(List<Object> args,String name){
        requireOne(args,name);
        if(!(args.getFirst() instanceof String message)) throw new IllegalArgumentException(name+" expects a String message");
        return message;
    }
}
