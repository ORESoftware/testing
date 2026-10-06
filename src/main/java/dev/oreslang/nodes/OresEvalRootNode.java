package dev.oreslang.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresMutex;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.AsyncRuntime;
import dev.oreslang.runtime.ChannelRuntime;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.Awaitable;
import dev.oreslang.runtime.OresScheduler;

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
import java.util.concurrent.atomic.AtomicLong;

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
            return context.runRootMain(() -> current.executeMain(new Object[0]));
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
        private final IdentityHashMap<Ast.SelectStmt, AtomicLong> staticSelectCursors =
                new IdentityHashMap<>();
        private StartupPhase startupPhase = StartupPhase.CREATED;
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
                            Object initialized =
                                    invoke(functionBodyInvocation(fn, List.of()));
                            if (initialized instanceof OresFuture<?> future) {
                                if (OresScheduler.current() != null) {
                                    throw new IllegalStateException(
                                            "module init cannot suspend from inside a scheduler-owned startup turn");
                                }
                                initialized = future.join();
                            }
                            last = initialized;
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
            // MAIN_ONLY is already executing inside OresVM's ROOT_TASK.
            // Never park the CONTROL carrier on an async result here; the
            // enclosing root Task owns the wait and fresh-resume transition.
            return callFunction(main, List.of(arguments));
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


        @FunctionalInterface
        private interface SourceValueCont {
            void accept(SourceTask task, Object value, Throwable failure);
        }

        @FunctionalInterface
        private interface SourceArgsCont {
            void accept(SourceTask task, List<Object> values, Throwable failure);
        }

        @FunctionalInterface
        private interface SourceObjectCont {
            void accept(SourceTask task, Map<String, Object> value, Throwable failure);
        }

        @FunctionalInterface
        private interface SourceFlowCont {
            void accept(SourceTask task, SourceFlow flow);
        }

        private enum SourceFlowKind {
            NORMAL, RETURN, BREAK, CONTINUE, THROW
        }

        private record SourceFlow(
                SourceFlowKind kind,
                Object value,
                Throwable failure) {
            private static SourceFlow normal() {
                return new SourceFlow(SourceFlowKind.NORMAL, null, null);
            }

            private static SourceFlow returning(Object value) {
                return new SourceFlow(SourceFlowKind.RETURN, value, null);
            }

            private static SourceFlow breaking() {
                return new SourceFlow(SourceFlowKind.BREAK, null, null);
            }

            private static SourceFlow continuing() {
                return new SourceFlow(SourceFlowKind.CONTINUE, null, null);
            }

            private static SourceFlow throwing(Throwable failure) {
                return new SourceFlow(
                        SourceFlowKind.THROW,
                        null,
                        Objects.requireNonNull(failure, "failure"));
            }
        }

        /**
         * A heap-owned source execution frame. It contains no suspended Java
         * interpreter stack: when a source operation becomes pending, the
         * current continuation is stored here and OresScheduler.Task returns
         * Await to unwind the carrier completely.
         */
        private final class SourceTask implements OresScheduler.Task<Object> {
            private final Ast.FunctionDecl function;
            private final Ast.MethodDecl method;
            private final OresObject receiver;
            private final List<Ast.Stmt> blockBody;
            private final List<?> arguments;
            private Env initialBlockEnv;

            private SourceValueCont awaitingContinuation;
            private OresScheduler.Step<Object> nextStep;

            private SourceTask(
                    Ast.FunctionDecl function,
                    List<?> arguments) {
                this.function = Objects.requireNonNull(function, "function");
                this.method = null;
                this.receiver = null;
                this.blockBody = null;
                this.arguments = List.copyOf(arguments);
            }

            private SourceTask(
                    Ast.MethodDecl method,
                    OresObject receiver,
                    List<?> arguments) {
                this.function = null;
                this.method = Objects.requireNonNull(method, "method");
                this.receiver = receiver;
                this.blockBody = null;
                this.arguments = List.copyOf(arguments);
            }

            private SourceTask(List<Ast.Stmt> blockBody) {
                this.function = null;
                this.method = null;
                this.receiver = null;
                this.blockBody = List.copyOf(
                        Objects.requireNonNull(blockBody, "blockBody"));
                this.arguments = List.of();
            }

            @Override
            public OresScheduler.Step<Object> resume(
                    OresScheduler.Resume resume)
                    throws Exception {
                nextStep = null;

                if (resume.initial()) {
                    Env params;
                    if (blockBody != null) {
                        params = initialBlockEnv != null
                                ? initialBlockEnv
                                : new Env(null);
                    } else {
                        params = new Env(
                                null,
                                function != null && function.nonLexical(),
                                function != null
                                        ? null
                                        : declaringClass(method));

                        if (receiver != null) {
                            params.define(
                                    "self",
                                    receiver,
                                    Ast.BindingKind.VAL);
                        }

                        List<Ast.Param> parameters =
                                function != null
                                        ? function.parameters()
                                        : method.parameters();
                        if (parameters.size() != arguments.size()) {
                            throw new IllegalArgumentException(
                                    "source task argument arity mismatch");
                        }

                        for (int i = 0; i < parameters.size(); i++) {
                            Ast.Param param = parameters.get(i);
                            params.define(
                                    param.name(),
                                    arguments.get(i),
                                    param.mutable()
                                            ? Ast.BindingKind.LET
                                            : Ast.BindingKind.VAL);
                        }
                    }

                    List<Ast.Stmt> body =
                            blockBody != null
                                    ? blockBody
                                    : function != null
                                            ? function.body()
                                            : method.body();

                    runBlock(
                            this,
                            body,
                            params,
                            this::finishSourceFlow);
                } else {
                    SourceValueCont continuation = awaitingContinuation;
                    awaitingContinuation = null;
                    if (continuation == null) {
                        throw new IllegalStateException(
                                "source task resumed without a pending source continuation");
                    }
                    continuation.accept(
                            this,
                            resume.value(),
                            resume.failure());
                }

                if (nextStep == null) {
                    throw new IllegalStateException(
                            "source continuation produced neither Await nor Done");
                }

                OresScheduler.Step<Object> result = nextStep;
                nextStep = null;
                return result;
            }

            private void suspend(
                    OresFuture<?> future,
                    SourceValueCont continuation) {
                Objects.requireNonNull(future, "future");
                Objects.requireNonNull(continuation, "continuation");
                if (nextStep != null) {
                    throw new IllegalStateException(
                            "source continuation attempted two terminal steps in one turn");
                }
                awaitingContinuation = continuation;
                nextStep = OresScheduler.await(future);
            }

            private void done(Object value) {
                if (nextStep != null) {
                    throw new IllegalStateException(
                            "source continuation attempted two terminal steps in one turn");
                }
                nextStep = OresScheduler.done(value);
            }

            private void finishSourceFlow(
                    SourceTask task,
                    SourceFlow flow) {
                if (blockBody != null) {
                    switch (flow.kind()) {
                        case NORMAL -> done(null);
                        case RETURN -> {
                            if (flow.value() != null) {
                                throw new IllegalStateException(
                                        "detached source continuation cannot return a value");
                            }
                            done(null);
                        }
                        case THROW -> throw sourceFailure(flow.failure());
                        case BREAK, CONTINUE ->
                                throw new IllegalStateException(
                                        "loop control crossed a source continuation boundary");
                    }
                    return;
                }

                switch (flow.kind()) {
                    case NORMAL -> done(shapeSourceReturn(null, null));
                    case RETURN -> done(shapeSourceReturn(flow.value(), null));
                    case THROW -> throw sourceFailure(flow.failure());
                    case BREAK, CONTINUE ->
                            throw new IllegalStateException(
                                    "loop control crossed a source function boundary");
                }
            }

            private Object shapeSourceReturn(
                    Object value,
                    Throwable ignored) {
                Ast.TypeRef returnType =
                        function != null ? function.returnType() : method.returnType();
                String label =
                        function != null
                                ? "function " + function.name()
                                : "method " + method.name();
                return shapeReturnedValue(returnType, value, label);
            }
        }

        private RuntimeException sourceFailure(Throwable failure) {
            Throwable unwrapped = OresFuture.unwrap(
                    Objects.requireNonNull(failure, "failure"));
            if (unwrapped instanceof RuntimeException runtime) {
                return runtime;
            }
            if (unwrapped instanceof Error error) {
                throw error;
            }
            return new RuntimeException(unwrapped);
        }

        private OresFuture<Object> startSourceFunctionTask(
                Ast.FunctionDecl function,
                List<?> arguments) {
            SourceTask task = new SourceTask(function, arguments);
            OresFuture<Object> future;
            if (ActorRuntime.inActorExecution()) {
                future = context.actors().startActorTask(task);
                context.actors().ownCurrentActorFuture(future);
            } else {
                OresScheduler current = OresScheduler.current();
                future = (current != null
                        ? current
                        : context.vm().rootScheduler()).start(task);
            }
            return future;
        }

        private OresFuture<Object> startSourceBlockTask(
                List<Ast.Stmt> body,
                Env env) {
            SourceTask task = new SourceTask(body);
            if (!ActorRuntime.inActorExecution()) {
                throw new IllegalStateException(
                        "detached source block task requires actor execution");
            }
            task.initialBlockEnv = env.snapshot();
            OresFuture<Object> future = context.actors().startActorTask(task);
            context.actors().ownCurrentActorFuture(future);
            return future;
        }

        private OresFuture<Object> startSourceMethodTask(
                Ast.MethodDecl method,
                OresObject receiver,
                List<?> arguments) {
            SourceTask task = new SourceTask(method, receiver, arguments);
            OresFuture<Object> future;
            if (ActorRuntime.inActorExecution()) {
                future = context.actors().startActorTask(task);
                context.actors().ownCurrentActorFuture(future);
            } else {
                OresScheduler current = OresScheduler.current();
                future = (current != null
                        ? current
                        : context.vm().rootScheduler()).start(task);
            }
            return future;
        }

        private boolean functionContainsPotentialSuspension(
                Ast.FunctionDecl function) {
            return functionContainsPotentialSuspension(
                    function,
                    java.util.Collections.newSetFromMap(
                            new IdentityHashMap<>()));
        }

        private boolean functionContainsPotentialSuspension(
                Ast.FunctionDecl function,
                Set<Ast.FunctionDecl> seen) {
            if (!seen.add(function)) return false;
            for (Ast.Stmt stmt : function.body()) {
                if (statementContainsPotentialSuspension(stmt, seen)) {
                    return true;
                }
            }
            return false;
        }

        private boolean statementContainsPotentialSuspension(
                Ast.Stmt stmt,
                Set<Ast.FunctionDecl> seen) {
            if (stmt instanceof Ast.SelectStmt select) {
                return select.mode() != Ast.WaitMode.IMMEDIATE
                        || select.arms().stream()
                                .anyMatch(arm ->
                                        arm.body().stream().anyMatch(
                                                nested -> statementContainsPotentialSuspension(
                                                        nested,
                                                        seen)));
            }
            if (stmt instanceof Ast.BindingStmt binding) {
                return expressionContainsPotentialSuspension(
                        binding.initializer(), seen);
            }
            if (stmt instanceof Ast.DestructureStmt destructure) {
                return expressionContainsPotentialSuspension(
                        destructure.initializer(), seen);
            }
            if (stmt instanceof Ast.ReturnStmt ret) {
                return ret.value() != null
                        && expressionContainsPotentialSuspension(
                                ret.value(), seen);
            }
            if (stmt instanceof Ast.ExprStmt expr) {
                return expressionContainsPotentialSuspension(
                        expr.expression(), seen);
            }
            if (stmt instanceof Ast.DeferStmt defer) {
                return expressionContainsPotentialSuspension(
                        defer.expression(), seen);
            }
            if (stmt instanceof Ast.BlockStmt block) {
                return block.body().stream().anyMatch(
                        nested -> statementContainsPotentialSuspension(nested, seen));
            }
            if (stmt instanceof Ast.IfStmt conditional) {
                return conditional.branches().stream().anyMatch(
                                branch ->
                                        expressionContainsPotentialSuspension(
                                                branch.condition(), seen)
                                                || branch.body().stream().anyMatch(
                                                        nested ->
                                                                statementContainsPotentialSuspension(
                                                                        nested,
                                                                        seen)))
                        || conditional.elseBody().stream().anyMatch(
                                nested ->
                                        statementContainsPotentialSuspension(nested, seen));
            }
            if (stmt instanceof Ast.MatchStmt matched) {
                if (expressionContainsPotentialSuspension(
                        matched.subject(), seen)) return true;
                return matched.arms().stream().anyMatch(
                        arm ->
                                (arm.guard() != null
                                        && expressionContainsPotentialSuspension(
                                                arm.guard(), seen))
                                        || arm.body().stream().anyMatch(
                                                nested ->
                                                        statementContainsPotentialSuspension(
                                                                nested,
                                                                seen)));
            }
            if (stmt instanceof Ast.SwitchStmt switched) {
                if (expressionContainsPotentialSuspension(
                        switched.subject(), seen)) return true;
                return switched.cases().stream().anyMatch(
                        arm ->
                                arm.body().stream().anyMatch(
                                        nested ->
                                                statementContainsPotentialSuspension(
                                                        nested, seen)))
                        || switched.defaultBody().stream().anyMatch(
                                nested ->
                                        statementContainsPotentialSuspension(
                                                nested, seen));
            }
            if (stmt instanceof Ast.TryStmt attempted) {
                return attempted.body().stream().anyMatch(
                                nested ->
                                        statementContainsPotentialSuspension(nested, seen))
                        || attempted.catchBody().stream().anyMatch(
                                nested ->
                                        statementContainsPotentialSuspension(nested, seen))
                        || attempted.finallyBody().stream().anyMatch(
                                nested ->
                                        statementContainsPotentialSuspension(nested, seen));
            }
            if (stmt instanceof Ast.ForOfStmt loop) {
                return expressionContainsPotentialSuspension(loop.iterable(), seen)
                        || loop.body().stream().anyMatch(
                                nested ->
                                        statementContainsPotentialSuspension(nested, seen));
            }
            if (stmt instanceof Ast.ForOfDestructureStmt loop) {
                return expressionContainsPotentialSuspension(loop.iterable(), seen)
                        || loop.body().stream().anyMatch(
                                nested ->
                                        statementContainsPotentialSuspension(nested, seen));
            }
            if (stmt instanceof Ast.ForStmt loop) {
                return (loop.initializer() != null
                                && statementContainsPotentialSuspension(
                                        loop.initializer(), seen))
                        || (loop.condition() != null
                                && expressionContainsPotentialSuspension(
                                        loop.condition(), seen))
                        || (loop.update() != null
                                && expressionContainsPotentialSuspension(
                                        loop.update(), seen))
                        || loop.body().stream().anyMatch(
                                nested ->
                                        statementContainsPotentialSuspension(
                                                nested, seen));
            }
            if (stmt instanceof Ast.LoopStmt loop) {
                return loop.body().stream().anyMatch(
                        nested ->
                                statementContainsPotentialSuspension(nested, seen));
            }
            return false;
        }

        private boolean expressionContainsPotentialSuspension(
                Ast.Expr expr,
                Set<Ast.FunctionDecl> seen) {
            if (expr == null) return false;
            if (expr instanceof Ast.AwaitExpr
                    || expr instanceof Ast.ChannelOpExpr channel
                        && channel.mode() != Ast.WaitMode.IMMEDIATE
                    || expr instanceof Ast.DynamicSelectExpr selected
                        && selected.mode() != Ast.WaitMode.IMMEDIATE) {
                return true;
            }
            if (expr instanceof Ast.CallExpr call) {
                if (call.callee() instanceof Ast.NameExpr name
                        && !functionNameBoundLocally(name.name())) {
                    Ast.FunctionDecl target = findFunction(name.name());
                    if (target != null) {
                        return target.async()
                                || target.actorKind() != Ast.ActorKind.NONE
                                || (!target.async()
                                        && functionContainsPotentialSuspension(
                                                target,
                                                seen));
                    }
                }
                return false;
            }
            if (expr instanceof Ast.UnaryExpr unary) {
                return expressionContainsPotentialSuspension(
                        unary.operand(), seen);
            }
            if (expr instanceof Ast.BinaryExpr binary) {
                return expressionContainsPotentialSuspension(
                                binary.left(), seen)
                        || expressionContainsPotentialSuspension(
                                binary.right(), seen);
            }
            if (expr instanceof Ast.AssignExpr assignment) {
                return expressionContainsPotentialSuspension(
                                assignment.target(), seen)
                        || expressionContainsPotentialSuspension(
                                assignment.value(), seen);
            }
            if (expr instanceof Ast.ConditionalExpr conditional) {
                return expressionContainsPotentialSuspension(
                                conditional.condition(), seen)
                        || expressionContainsPotentialSuspension(
                                conditional.whenTrue(), seen)
                        || expressionContainsPotentialSuspension(
                                conditional.whenFalse(), seen);
            }
            if (expr instanceof Ast.TypeTestExpr test) {
                return expressionContainsPotentialSuspension(
                        test.value(), seen);
            }
            if (expr instanceof Ast.PatternTestExpr test) {
                return expressionContainsPotentialSuspension(
                        test.value(), seen);
            }
            if (expr instanceof Ast.CastExpr cast) {
                return expressionContainsPotentialSuspension(
                        cast.value(), seen);
            }
            if (expr instanceof Ast.MemberExpr member) {
                return expressionContainsPotentialSuspension(
                        member.receiver(), seen);
            }
            if (expr instanceof Ast.IndexExpr index) {
                return expressionContainsPotentialSuspension(
                                index.receiver(), seen)
                        || expressionContainsPotentialSuspension(
                                index.index(), seen);
            }
            if (expr instanceof Ast.NewExpr created) {
                return created.arguments().stream().anyMatch(
                        arg -> expressionContainsPotentialSuspension(
                                arg, seen));
            }
            if (expr instanceof Ast.ListExpr list) {
                return list.elements().stream().anyMatch(
                        item -> expressionContainsPotentialSuspension(item, seen));
            }
            if (expr instanceof Ast.TupleExpr tuple) {
                return tuple.elements().stream().anyMatch(
                        item -> expressionContainsPotentialSuspension(item, seen));
            }
            if (expr instanceof Ast.ObjectExpr object) {
                return object.fields().stream().anyMatch(
                        field ->
                                (field.isDynamic()
                                        && expressionContainsPotentialSuspension(
                                                field.dynamicName(), seen))
                                        || expressionContainsPotentialSuspension(
                                                field.value(), seen));
            }
            return false;
        }

        private boolean functionNameBoundLocally(String name) {
            return false;
        }

        private void runBlock(
                SourceTask task,
                List<Ast.Stmt> statements,
                Env parent,
                SourceFlowCont continuation) {
            Env env = new Env(parent);
            ArrayDeque<Ast.Expr> defers = new ArrayDeque<>();
            runStatements(
                    task,
                    statements,
                    env,
                    defers,
                    0,
                    flow -> finishBlock(task, env, defers, flow, continuation));
        }

        private void runStatements(
                SourceTask task,
                List<Ast.Stmt> statements,
                Env env,
                ArrayDeque<Ast.Expr> defers,
                int index,
                SourceFlowCont continuation) {
            if (index >= statements.size()) {
                finishBlock(
                        task,
                        env,
                        defers,
                        SourceFlow.normal(),
                        continuation);
                return;
            }

            Ast.Stmt stmt = statements.get(index);
            if (!statementContainsPotentialSuspension(
                    stmt,
                    java.util.Collections.newSetFromMap(
                            new IdentityHashMap<>()))) {
                try {
                    executeStatement(stmt, env, defers, false);
                    runStatements(
                            task,
                            statements,
                            env,
                            defers,
                            index + 1,
                            continuation);
                    return;
                } catch (ReturnSignal returned) {
                    finishBlock(
                            task,
                            env,
                            defers,
                            SourceFlow.returning(returned.value),
                            continuation);
                    return;
                } catch (BreakSignal ignored) {
                    finishBlock(
                            task,
                            env,
                            defers,
                            SourceFlow.breaking(),
                            continuation);
                    return;
                } catch (ContinueSignal ignored) {
                    finishBlock(
                            task,
                            env,
                            defers,
                            SourceFlow.continuing(),
                            continuation);
                    return;
                } catch (RuntimeException | Error failure) {
                    finishBlock(
                            task,
                            env,
                            defers,
                            SourceFlow.throwing(failure),
                            continuation);
                    return;
                }
            }

            runSuspendableStatement(
                    task,
                    stmt,
                    env,
                    defers,
                    flow -> {
                        if (flow.kind() == SourceFlowKind.NORMAL) {
                            runStatements(
                                    task,
                                    statements,
                                    env,
                                    defers,
                                    index + 1,
                                    continuation);
                        } else {
                            finishBlock(
                                    task,
                                    env,
                                    defers,
                                    flow,
                                    continuation);
                        }
                    });
        }

        private void finishBlock(
                SourceTask task,
                Env env,
                ArrayDeque<Ast.Expr> defers,
                SourceFlow initialFlow,
                SourceFlowCont continuation) {
            if (defers.isEmpty()) {
                try {
                    env.releaseMutexGuards(
                            initialFlow.kind() != SourceFlowKind.NORMAL);
                    continuation.accept(task, initialFlow);
                } catch (RuntimeException | Error failure) {
                    continuation.accept(
                            task,
                            SourceFlow.throwing(failure));
                }
                return;
            }

            runOneDefer(
                    task,
                    env,
                    defers,
                    initialFlow,
                    continuation);
        }

        private void runOneDefer(
                SourceTask task,
                Env env,
                ArrayDeque<Ast.Expr> defers,
                SourceFlow currentFlow,
                SourceFlowCont continuation) {
            Ast.Expr defer = defers.pollFirst();
            if (defer == null) {
                try {
                    env.releaseMutexGuards(
                            currentFlow.kind() != SourceFlowKind.NORMAL);
                    continuation.accept(task, currentFlow);
                } catch (RuntimeException | Error failure) {
                    continuation.accept(
                            task,
                            SourceFlow.throwing(failure));
                }
                return;
            }

            evalSuspendableExpr(
                    task,
                    defer,
                    env,
                    (ignored, ignoredValue, failure) -> {
                        if (failure != null) {
                            if (currentFlow.failure() != null) {
                                failure.addSuppressed(currentFlow.failure());
                            }
                            runOneDefer(
                                    task,
                                    env,
                                    defers,
                                    SourceFlow.throwing(failure),
                                    continuation);
                        } else {
                            runOneDefer(
                                    task,
                                    env,
                                    defers,
                                    currentFlow,
                                    continuation);
                        }
                    });
        }

        private void runSuspendableStatement(
                SourceTask task,
                Ast.Stmt stmt,
                Env env,
                ArrayDeque<Ast.Expr> defers,
                SourceFlowCont continuation) {
            try {
                if (stmt instanceof Ast.BindingStmt binding) {
                    evalSuspendableExpr(
                            task,
                            binding.initializer(),
                            env,
                            (t, value, failure) -> {
                                if (failure != null) {
                                    continuation.accept(
                                            t,
                                            SourceFlow.throwing(failure));
                                    return;
                                }
                                env.define(binding.name(), value, binding.kind());
                                continuation.accept(t, SourceFlow.normal());
                            });
                    return;
                }

                if (stmt instanceof Ast.DestructureStmt destructure) {
                    evalSuspendableExpr(
                            task,
                            destructure.initializer(),
                            env,
                            (t, value, failure) -> {
                                if (failure != null) {
                                    continuation.accept(
                                            t,
                                            SourceFlow.throwing(failure));
                                    return;
                                }
                                try {
                                    if (destructure.kind()
                                            == Ast.DestructureKind.SEQUENCE) {
                                        List<?> items = asSequence(value);
                                        if (items.size()
                                                != destructure.bindings().size()) {
                                            throw new IllegalArgumentException(
                                                    "destructure arity mismatch");
                                        }
                                        for (int i = 0; i < items.size(); i++) {
                                            Ast.DestructureBinding binding =
                                                    destructure.bindings().get(i);
                                            if (!binding.isDiscard()) {
                                                env.define(
                                                        binding.name(),
                                                        items.get(i),
                                                        binding.kind());
                                            }
                                        }
                                    } else {
                                        for (Ast.DestructureBinding binding :
                                                destructure.bindings()) {
                                            if (!binding.isDiscard()) {
                                                env.define(
                                                        binding.name(),
                                                        destructureMember(
                                                                value,
                                                                binding.name(),
                                                                env),
                                                        binding.kind());
                                            }
                                        }
                                    }
                                    continuation.accept(
                                            t,
                                            SourceFlow.normal());
                                } catch (RuntimeException | Error failure2) {
                                    continuation.accept(
                                            t,
                                            SourceFlow.throwing(failure2));
                                }
                            });
                    return;
                }

                if (stmt instanceof Ast.ReturnStmt returned) {
                    if (returned.value() == null) {
                        continuation.accept(task, SourceFlow.returning(null));
                    } else {
                        evalSuspendableExpr(
                                task,
                                returned.value(),
                                env,
                                (t, value, failure) ->
                                        continuation.accept(
                                                t,
                                                failure == null
                                                        ? SourceFlow.returning(value)
                                                        : SourceFlow.throwing(failure)));
                    }
                    return;
                }

                if (stmt instanceof Ast.ExprStmt expr) {
                    evalSuspendableExpr(
                            task,
                            expr.expression(),
                            env,
                            (t, ignored, failure) ->
                                    continuation.accept(
                                            t,
                                            failure == null
                                                    ? SourceFlow.normal()
                                                    : SourceFlow.throwing(failure)));
                    return;
                }

                if (stmt instanceof Ast.DeferStmt defer) {
                    defers.push(defer.expression());
                    continuation.accept(task, SourceFlow.normal());
                    return;
                }

                if (stmt instanceof Ast.BlockStmt block) {
                    runBlock(
                            task,
                            block.body(),
                            env,
                            continuation);
                    return;
                }

                if (stmt instanceof Ast.BreakStmt) {
                    continuation.accept(task, SourceFlow.breaking());
                    return;
                }

                if (stmt instanceof Ast.ContinueStmt) {
                    continuation.accept(task, SourceFlow.continuing());
                    return;
                }

                if (stmt instanceof Ast.IfStmt conditional) {
                    runIfBranches(
                            task,
                            conditional,
                            env,
                            0,
                            continuation);
                    return;
                }

                if (stmt instanceof Ast.MatchStmt matched) {
                    evalSuspendableExpr(
                            task,
                            matched.subject(),
                            env,
                            (t, subject, failure) -> {
                                if (failure != null) {
                                    continuation.accept(
                                            t,
                                            SourceFlow.throwing(failure));
                                    return;
                                }
                                runMatchArms(
                                        t,
                                        matched,
                                        subject,
                                        env,
                                        0,
                                        null,
                                        continuation);
                            });
                    return;
                }

                if (stmt instanceof Ast.SwitchStmt switched) {
                    evalSuspendableExpr(
                            task,
                            switched.subject(),
                            env,
                            (t, subject, failure) -> {
                                if (failure != null) {
                                    continuation.accept(
                                            t,
                                            SourceFlow.throwing(failure));
                                    return;
                                }
                                Ast.SelectArm selected = null;
                                for (Ast.SwitchCase arm : switched.cases()) {
                                    boolean matchedCase = false;
                                    for (Ast.Expr constant : arm.constants()) {
                                        if (Objects.equals(subject, eval(constant, env))) {
                                            matchedCase = true;
                                            break;
                                        }
                                    }
                                    if (matchedCase) {
                                        runBlock(
                                                t,
                                                arm.body(),
                                                env,
                                                continuation);
                                        return;
                                    }
                                }
                                runBlock(
                                        t,
                                        switched.defaultBody(),
                                        env,
                                        continuation);
                            });
                    return;
                }

                if (stmt instanceof Ast.TryStmt attempted) {
                    runBlock(
                            task,
                            attempted.body(),
                            env,
                            (t, flow) -> {
                                if (flow.kind() == SourceFlowKind.THROW) {
                                    Env caught = new Env(env);
                                    caught.define(
                                            attempted.errorName(),
                                            flow.failure(),
                                            Ast.BindingKind.VAL);
                                    runBlock(
                                            t,
                                            attempted.catchBody(),
                                            caught,
                                            (t2, catchFlow) ->
                                                    runBlock(
                                                            t2,
                                                            attempted.finallyBody(),
                                                            env,
                                                            (t3, finallyFlow) ->
                                                                    continuation.accept(
                                                                            t3,
                                                                            finallyFlow.kind()
                                                                                            != SourceFlowKind.NORMAL
                                                                                    ? finallyFlow
                                                                                    : catchFlow)));
                                } else {
                                    runBlock(
                                            t,
                                            attempted.finallyBody(),
                                            env,
                                            (t2, finallyFlow) ->
                                                    continuation.accept(
                                                            t2,
                                                            finallyFlow.kind()
                                                                            != SourceFlowKind.NORMAL
                                                                    ? finallyFlow
                                                                    : flow));
                                }
                            });
                    return;
                }

                if (stmt instanceof Ast.SelectStmt select) {
                    runSelectSuspendable(
                            task,
                            select,
                            env,
                            continuation);
                    return;
                }

                if (stmt instanceof Ast.ForStmt loop) {
                    runForSuspendable(
                            task,
                            loop,
                            env,
                            continuation);
                    return;
                }

                if (stmt instanceof Ast.LoopStmt loop) {
                    runLoopSuspendable(
                            task,
                            loop,
                            env,
                            continuation);
                    return;
                }

                if (stmt instanceof Ast.ForOfStmt loop) {
                    evalSuspendableExpr(
                            task,
                            loop.iterable(),
                            env,
                            (t, iterable, failure) -> {
                                if (failure != null) {
                                    continuation.accept(
                                            t,
                                            SourceFlow.throwing(failure));
                                    return;
                                }
                                runForOfIteration(
                                        t,
                                        loop,
                                        env,
                                        iterableValues(iterable, env),
                                        0,
                                        continuation);
                            });
                    return;
                }

                if (stmt instanceof Ast.ForOfDestructureStmt loop) {
                    evalSuspendableExpr(
                            task,
                            loop.iterable(),
                            env,
                            (t, iterable, failure) -> {
                                if (failure != null) {
                                    continuation.accept(
                                            t,
                                            SourceFlow.throwing(failure));
                                    return;
                                }
                                runForOfDestructureIteration(
                                        t,
                                        loop,
                                        env,
                                        iterableValues(iterable, env),
                                        0,
                                        continuation);
                            });
                    return;
                }

                throw new IllegalArgumentException(
                        "unsupported suspendable statement "
                                + stmt.getClass().getName());
            } catch (RuntimeException | Error failure) {
                continuation.accept(
                        task,
                        SourceFlow.throwing(failure));
            }
        }

        private void runIfBranches(
                SourceTask task,
                Ast.IfStmt conditional,
                Env env,
                int index,
                SourceFlowCont continuation) {
            if (index >= conditional.branches().size()) {
                runBlock(
                        task,
                        conditional.elseBody(),
                        env,
                        continuation);
                return;
            }

            Ast.IfBranch branch = conditional.branches().get(index);
            evalSuspendableCondition(
                    task,
                    branch.condition(),
                    env,
                    (t, condition) -> {
                        if (condition.matched()) {
                            Env branchEnv = new Env(env);
                            condition.bindings().forEach(
                                    (name, value) ->
                                            branchEnv.define(
                                                    name,
                                                    value,
                                                    Ast.BindingKind.VAL));
                            runBlock(
                                    t,
                                    branch.body(),
                                    branchEnv,
                                    continuation);
                        } else {
                            runIfBranches(
                                    t,
                                    conditional,
                                    env,
                                    index + 1,
                                    continuation);
                        }
                    });
        }

        private void runMatchArms(
                SourceTask task,
                Ast.MatchStmt matched,
                Object subject,
                Env env,
                int index,
                Ast.MatchArm fallback,
                SourceFlowCont continuation) {
            if (index >= matched.arms().size()) {
                if (fallback == null) {
                    continuation.accept(
                            task,
                            SourceFlow.throwing(
                                    new IllegalStateException(
                                            "exhaustive match invariant violated")));
                    return;
                }
                LinkedHashMap<String, Object> bindings = new LinkedHashMap<>();
                if (!patternMatches(
                        fallback.pattern(),
                        subject,
                        bindings)) {
                    continuation.accept(
                            task,
                            SourceFlow.throwing(
                                    new IllegalStateException(
                                            "match fallback did not accept subject")));
                    return;
                }
                Env selectedEnv = new Env(env);
                bindings.forEach(
                        (name, value) ->
                                selectedEnv.define(
                                        name,
                                        value,
                                        Ast.BindingKind.VAL));
                runBlock(
                        task,
                        fallback.body(),
                        selectedEnv,
                        continuation);
                return;
            }

            Ast.MatchArm arm = matched.arms().get(index);
            boolean catchAll =
                    arm.guard() == null
                            && (arm.pattern() instanceof Ast.WildcardPattern
                                    || arm.pattern() instanceof Ast.BindingPattern);
            if (!matched.ordered() && catchAll) {
                runMatchArms(
                        task,
                        matched,
                        subject,
                        env,
                        index + 1,
                        arm,
                        continuation);
                return;
            }

            LinkedHashMap<String, Object> bindings = new LinkedHashMap<>();
            if (!patternMatches(
                    arm.pattern(),
                    subject,
                    bindings)) {
                runMatchArms(
                        task,
                        matched,
                        subject,
                        env,
                        index + 1,
                        fallback,
                        continuation);
                return;
            }

            Env armEnv = new Env(env);
            bindings.forEach(
                    (name, value) ->
                            armEnv.define(
                                    name,
                                    value,
                                    Ast.BindingKind.VAL));

            if (arm.guard() == null) {
                runBlock(
                        task,
                        arm.body(),
                        armEnv,
                        continuation);
                return;
            }

            evalSuspendableExpr(
                    task,
                    arm.guard(),
                    armEnv,
                    (t, guard, failure) -> {
                        if (failure != null) {
                            continuation.accept(
                                    t,
                                    SourceFlow.throwing(failure));
                        } else if (truth(guard)) {
                            runBlock(
                                    t,
                                    arm.body(),
                                    armEnv,
                                    continuation);
                        } else {
                            runMatchArms(
                                    t,
                                    matched,
                                    subject,
                                    env,
                                    index + 1,
                                    fallback,
                                    continuation);
                        }
                    });
        }

        private void runSelectSuspendable(
                SourceTask task,
                Ast.SelectStmt select,
                Env env,
                SourceFlowCont continuation) {
            ChannelRuntime.SelectSet set =
                    buildStaticSelectSet(select, env);
            ChannelRuntime.SelectPolicy policy =
                    runtimeSelectPolicy(select.policy());

            if (select.mode() == Ast.WaitMode.IMMEDIATE) {
                try {
                    java.util.Optional<ChannelRuntime.SelectResult> result =
                            set.trySelect(policy);
                    if (result.isEmpty()) {
                        continuation.accept(task, SourceFlow.normal());
                    } else {
                        executeSelectedArm(
                                select,
                                result.get(),
                                env,
                                false,
                                false);
                        continuation.accept(task, SourceFlow.normal());
                    }
                } catch (RuntimeException | Error failure) {
                    continuation.accept(
                            task,
                            SourceFlow.throwing(failure));
                }
                return;
            }

            OresFuture<ChannelRuntime.SelectResult> future =
                    set.selectAsync(policy);
            if (select.mode() == Ast.WaitMode.NONBLOCKING) {
                if (!ActorRuntime.inActorExecution()) {
                    future.cancel(false);
                    continuation.accept(
                            task,
                            SourceFlow.throwing(
                                    new IllegalStateException(
                                            "nonblocking static select requires actor execution")));
                    return;
                }
                ActorRuntime.ContinuationTarget target =
                        context.actors().captureCurrentContinuationTarget();
                context.actors().enqueueOnCompletion(
                        future,
                        target,
                        (result, failure) -> {
                            if (failure != null) {
                                throw sourceFailure(failure);
                            }
                            try {
                                executeSelectedArm(
                                        select,
                                        result,
                                        env.snapshot(),
                                        true,
                                        true);
                            } catch (RuntimeException | Error callbackFailure) {
                                throw callbackFailure;
                            }
                        });
                continuation.accept(task, SourceFlow.normal());
                return;
            }

            if (future.isDone()) {
                try {
                    executeSelectedArm(
                            select,
                            (ChannelRuntime.SelectResult) future.join(),
                            env,
                            false,
                            false);
                    continuation.accept(task, SourceFlow.normal());
                } catch (RuntimeException | Error failure) {
                    continuation.accept(
                            task,
                            SourceFlow.throwing(failure));
                }
                return;
            }

            task.suspend(
                    future,
                    (t, value, failure) -> {
                        if (failure != null) {
                            continuation.accept(
                                    t,
                                    SourceFlow.throwing(
                                            OresFuture.unwrap(failure)));
                            return;
                        }
                        try {
                            executeSelectedArm(
                                    select,
                                    (ChannelRuntime.SelectResult) value,
                                    env,
                                    false,
                                    false);
                            continuation.accept(
                                    t,
                                    SourceFlow.normal());
                        } catch (RuntimeException | Error selectFailure) {
                            continuation.accept(
                                    t,
                                    SourceFlow.throwing(selectFailure));
                        }
                    });
        }

        private void runForSuspendable(
                SourceTask task,
                Ast.ForStmt loop,
                Env parent,
                SourceFlowCont continuation) {
            Env loopEnv = new Env(parent);
            if (loop.initializer() == null) {
                runForIteration(
                        task,
                        loop,
                        loopEnv,
                        continuation);
                return;
            }

            runSuspendableStatement(
                    task,
                    loop.initializer(),
                    loopEnv,
                    new ArrayDeque<>(),
                    (t, flow) -> {
                        if (flow.kind() == SourceFlowKind.NORMAL) {
                            runForIteration(
                                    t,
                                    loop,
                                    loopEnv,
                                    continuation);
                        } else {
                            continuation.accept(t, flow);
                        }
                    });
        }

        private void runForIteration(
                SourceTask task,
                Ast.ForStmt loop,
                Env loopEnv,
                SourceFlowCont continuation) {
            if (loop.condition() == null) {
                runForBody(
                        task,
                        loop,
                        loopEnv,
                        continuation);
                return;
            }

            evalSuspendableExpr(
                    task,
                    loop.condition(),
                    loopEnv,
                    (t, condition, failure) -> {
                        if (failure != null) {
                            continuation.accept(
                                    t,
                                    SourceFlow.throwing(failure));
                        } else if (!truth(condition)) {
                            continuation.accept(
                                    t,
                                    SourceFlow.normal());
                        } else {
                            runForBody(
                                    t,
                                    loop,
                                    loopEnv,
                                    continuation);
                        }
                    });
        }

        private void runForBody(
                SourceTask task,
                Ast.ForStmt loop,
                Env loopEnv,
                SourceFlowCont continuation) {
            runBlock(
                    task,
                    loop.body(),
                    loopEnv,
                    (t, flow) -> {
                        if (flow.kind() == SourceFlowKind.BREAK) {
                            continuation.accept(
                                    t,
                                    SourceFlow.normal());
                        } else if (flow.kind() == SourceFlowKind.RETURN
                                || flow.kind() == SourceFlowKind.THROW) {
                            continuation.accept(t, flow);
                        } else {
                            if (loop.update() == null) {
                                runForIteration(
                                        t,
                                        loop,
                                        loopEnv,
                                        continuation);
                            } else {
                                evalSuspendableExpr(
                                        t,
                                        loop.update(),
                                        loopEnv,
                                        (t2, ignored, failure) -> {
                                            if (failure != null) {
                                                continuation.accept(
                                                        t2,
                                                        SourceFlow.throwing(
                                                                failure));
                                            } else {
                                                runForIteration(
                                                        t2,
                                                        loop,
                                                        loopEnv,
                                                        continuation);
                                            }
                                        });
                            }
                        }
                    });
        }

        private void runLoopSuspendable(
                SourceTask task,
                Ast.LoopStmt loop,
                Env env,
                SourceFlowCont continuation) {
            runBlock(
                    task,
                    loop.body(),
                    env,
                    (t, flow) -> {
                        if (flow.kind() == SourceFlowKind.BREAK) {
                            continuation.accept(
                                    t,
                                    SourceFlow.normal());
                        } else if (flow.kind() == SourceFlowKind.RETURN
                                || flow.kind() == SourceFlowKind.THROW) {
                            continuation.accept(t, flow);
                        } else {
                            runLoopSuspendable(
                                    t,
                                    loop,
                                    env,
                                    continuation);
                        }
                    });
        }

        private void runForOfIteration(
                SourceTask task,
                Ast.ForOfStmt loop,
                Env env,
                List<?> items,
                int index,
                SourceFlowCont continuation) {
            if (index >= items.size()) {
                continuation.accept(task, SourceFlow.normal());
                return;
            }

            Env iteration = new Env(env);
            iteration.define(
                    loop.bindingName(),
                    items.get(index),
                    loop.bindingKind());

            runBlock(
                    task,
                    loop.body(),
                    iteration,
                    (t, flow) -> {
                        if (flow.kind() == SourceFlowKind.BREAK) {
                            continuation.accept(t, SourceFlow.normal());
                        } else if (flow.kind() == SourceFlowKind.RETURN
                                || flow.kind() == SourceFlowKind.THROW) {
                            continuation.accept(t, flow);
                        } else {
                            runForOfIteration(
                                    t,
                                    loop,
                                    env,
                                    items,
                                    index + 1,
                                    continuation);
                        }
                    });
        }

        private void runForOfDestructureIteration(
                SourceTask task,
                Ast.ForOfDestructureStmt loop,
                Env env,
                List<?> items,
                int index,
                SourceFlowCont continuation) {
            if (index >= items.size()) {
                continuation.accept(task, SourceFlow.normal());
                return;
            }

            List<?> parts = asSequence(items.get(index));
            if (parts.size() != loop.bindings().size()) {
                continuation.accept(
                        task,
                        SourceFlow.throwing(
                                new IllegalArgumentException(
                                        "for-of destructure arity mismatch")));
                return;
            }

            Env iteration = new Env(env);
            for (int i = 0; i < parts.size(); i++) {
                Ast.DestructureBinding binding = loop.bindings().get(i);
                if (!binding.isDiscard()) {
                    iteration.define(
                            binding.name(),
                            parts.get(i),
                            binding.kind());
                }
            }

            runBlock(
                    task,
                    loop.body(),
                    iteration,
                    (t, flow) -> {
                        if (flow.kind() == SourceFlowKind.BREAK) {
                            continuation.accept(t, SourceFlow.normal());
                        } else if (flow.kind() == SourceFlowKind.RETURN
                                || flow.kind() == SourceFlowKind.THROW) {
                            continuation.accept(t, flow);
                        } else {
                            runForOfDestructureIteration(
                                    t,
                                    loop,
                                    env,
                                    items,
                                    index + 1,
                                    continuation);
                        }
                    });
        }

        private void evalSuspendableCondition(
                SourceTask task,
                Ast.Expr condition,
                Env env,
                java.util.function.BiConsumer<SourceTask, ConditionResult> continuation) {
            if (condition instanceof Ast.BinaryExpr binary
                    && binary.operator().equals("&&")) {
                evalSuspendableCondition(
                        task,
                        binary.left(),
                        env,
                        (t, left) -> {
                            if (!left.matched()) {
                                continuation.accept(t, left);
                                return;
                            }
                            evalSuspendableCondition(
                                    t,
                                    binary.right(),
                                    env,
                                    (t2, right) ->
                                            continuation.accept(
                                                    t2,
                                                    mergeConditionBindings(
                                                            left,
                                                            right)));
                        });
                return;
            }

            if (condition instanceof Ast.TypeTestExpr test) {
                evalSuspendableExpr(
                        task,
                        test.value(),
                        env,
                        (t, value, failure) -> {
                            if (failure != null) {
                                throw sourceFailure(failure);
                            }
                            boolean matched =
                                    oresTypeMatches(
                                            value,
                                            test.targetType());
                            if (matched && test.binding() != null) {
                                continuation.accept(
                                        t,
                                        new ConditionResult(
                                                true,
                                                Map.of(
                                                        test.binding(),
                                                        value)));
                            } else {
                                continuation.accept(
                                        t,
                                        matched
                                                ? ConditionResult.match()
                                                : ConditionResult.noMatch());
                            }
                        });
                return;
            }

            if (condition instanceof Ast.PatternTestExpr test) {
                evalSuspendableExpr(
                        task,
                        test.value(),
                        env,
                        (t, value, failure) -> {
                            if (failure != null) {
                                throw sourceFailure(failure);
                            }
                            LinkedHashMap<String, Object> bindings =
                                    new LinkedHashMap<>();
                            boolean matched =
                                    patternMatches(
                                            test.pattern(),
                                            value,
                                            bindings);
                            continuation.accept(
                                    t,
                                    matched
                                            ? new ConditionResult(
                                                    true,
                                                    Map.copyOf(bindings))
                                            : ConditionResult.noMatch());
                        });
                return;
            }

            evalSuspendableExpr(
                    task,
                    condition,
                    env,
                    (t, value, failure) -> {
                        if (failure != null) {
                            throw sourceFailure(failure);
                        }
                        continuation.accept(
                                t,
                                truth(value)
                                        ? ConditionResult.match()
                                        : ConditionResult.noMatch());
                    });
        }

        private ConditionResult mergeConditionBindings(
                ConditionResult left,
                ConditionResult right) {
            if (!left.matched() || !right.matched()) {
                return ConditionResult.noMatch();
            }
            LinkedHashMap<String, Object> bindings =
                    new LinkedHashMap<>(left.bindings());
            bindings.putAll(right.bindings());
            return new ConditionResult(
                    true,
                    Map.copyOf(bindings));
        }

        private void evalSuspendableExpr(
                SourceTask task,
                Ast.Expr expr,
                Env env,
                SourceValueCont continuation) {
            try {
                if (!expressionContainsPotentialSuspension(
                        expr,
                        java.util.Collections.newSetFromMap(
                                new IdentityHashMap<>()))) {
                    continuation.accept(
                            task,
                            eval(expr, env),
                            null);
                    return;
                }

                if (expr instanceof Ast.AwaitExpr awaited) {
                    evalSuspendableExpr(
                            task,
                            awaited.expression(),
                            env,
                            (t, value, failure) -> {
                                if (failure != null) {
                                    continuation.accept(
                                            t,
                                            null,
                                            failure);
                                } else {
                                    suspendOnAwaitable(
                                            t,
                                            value,
                                            continuation);
                                }
                            });
                    return;
                }

                if (expr instanceof Ast.ChannelOpExpr operation) {
                    evalSuspendableChannel(
                            task,
                            operation,
                            env,
                            continuation);
                    return;
                }

                if (expr instanceof Ast.DynamicSelectExpr selected) {
                    evalSuspendableExpr(
                            task,
                            selected.cases(),
                            env,
                            (t, cases, failure) -> {
                                if (failure != null) {
                                    continuation.accept(t, null, failure);
                                    return;
                                }
                                try {
                                    ChannelRuntime.SelectSet set =
                                            asSelectSet(cases);
                                    ChannelRuntime.SelectPolicy policy =
                                            runtimeSelectPolicy(
                                                    selected.policy());
                                    if (selected.mode()
                                            == Ast.WaitMode.IMMEDIATE) {
                                        java.util.Optional<
                                                ChannelRuntime.SelectResult> result =
                                                set.trySelect(policy);
                                        continuation.accept(
                                                t,
                                                result.isPresent()
                                                        ? new OptionValue(
                                                                true,
                                                                result.get())
                                                        : new OptionValue(
                                                                false,
                                                                null),
                                                null);
                                        return;
                                    }
                                    OresFuture<
                                            ChannelRuntime.SelectResult> future =
                                            set.selectAsync(policy);
                                    if (selected.mode()
                                            == Ast.WaitMode.NONBLOCKING) {
                                        if (ActorRuntime.inActorExecution()) {
                                            context.actors().ownCurrentActorFuture(
                                                    future);
                                        }
                                        continuation.accept(
                                                t,
                                                future,
                                                null);
                                        return;
                                    }
                                    if (future.isDone()) {
                                        continuation.accept(
                                                t,
                                                future.join(),
                                                null);
                                        return;
                                    }
                                    t.suspend(
                                            future,
                                            (t2, value, failure2) ->
                                                    continuation.accept(
                                                            t2,
                                                            value,
                                                            failure2));
                                } catch (RuntimeException | Error failure2) {
                                    continuation.accept(
                                            t,
                                            null,
                                            failure2);
                                }
                            });
                    return;
                }

                if (expr instanceof Ast.UnaryExpr unary) {
                    evalSuspendableExpr(
                            task,
                            unary.operand(),
                            env,
                            (t, value, failure) ->
                                    continuation.accept(
                                            t,
                                            failure == null
                                                    ? unaryValue(
                                                            unary.operator(),
                                                            value)
                                                    : null,
                                            failure));
                    return;
                }

                if (expr instanceof Ast.BinaryExpr binary) {
                    if (binary.operator().equals("&&")) {
                        evalSuspendableExpr(
                                task,
                                binary.left(),
                                env,
                                (t, left, failure) -> {
                                    if (failure != null) {
                                        continuation.accept(t, null, failure);
                                    } else if (!truth(left)) {
                                        continuation.accept(t, false, null);
                                    } else {
                                        evalSuspendableExpr(
                                                t,
                                                binary.right(),
                                                env,
                                                continuation);
                                    }
                                });
                        return;
                    }

                    if (binary.operator().equals("||")) {
                        evalSuspendableExpr(
                                task,
                                binary.left(),
                                env,
                                (t, left, failure) -> {
                                    if (failure != null) {
                                        continuation.accept(t, null, failure);
                                    } else if (truth(left)) {
                                        continuation.accept(t, true, null);
                                    } else {
                                        evalSuspendableExpr(
                                                t,
                                                binary.right(),
                                                env,
                                                continuation);
                                    }
                                });
                        return;
                    }

                    evalSuspendableExpr(
                            task,
                            binary.left(),
                            env,
                            (t, left, failure) -> {
                                if (failure != null) {
                                    continuation.accept(t, null, failure);
                                    return;
                                }
                                evalSuspendableExpr(
                                        t,
                                        binary.right(),
                                        env,
                                        (t2, right, failure2) -> {
                                            if (failure2 != null) {
                                                continuation.accept(
                                                        t2,
                                                        null,
                                                        failure2);
                                            } else {
                                                try {
                                                    Object result =
                                                            binary(
                                                                    binary.operator(),
                                                                    left,
                                                                    right);
                                                    continuation.accept(
                                                            t2,
                                                            result,
                                                            null);
                                                } catch (RuntimeException | Error failure3) {
                                                    continuation.accept(
                                                            t2,
                                                            null,
                                                            failure3);
                                                }
                                            }
                                        });
                            });
                    return;
                }

                if (expr instanceof Ast.ConditionalExpr conditional) {
                    evalSuspendableExpr(
                            task,
                            conditional.condition(),
                            env,
                            (t, value, failure) -> {
                                if (failure != null) {
                                    continuation.accept(t, null, failure);
                                } else {
                                    evalSuspendableExpr(
                                            t,
                                            truth(value)
                                                    ? conditional.whenTrue()
                                                    : conditional.whenFalse(),
                                            env,
                                            continuation);
                                }
                            });
                    return;
                }

                if (expr instanceof Ast.TypeTestExpr test) {
                    evalSuspendableExpr(
                            task,
                            test.value(),
                            env,
                            (t, value, failure) ->
                                    continuation.accept(
                                            t,
                                            failure == null
                                                    ? oresTypeMatches(
                                                            value,
                                                            test.targetType())
                                                    : null,
                                            failure));
                    return;
                }

                if (expr instanceof Ast.PatternTestExpr test) {
                    evalSuspendableExpr(
                            task,
                            test.value(),
                            env,
                            (t, value, failure) -> {
                                if (failure != null) {
                                    continuation.accept(t, null, failure);
                                    return;
                                }
                                continuation.accept(
                                        t,
                                        patternMatches(
                                                test.pattern(),
                                                value,
                                                new LinkedHashMap<>()),
                                        null);
                            });
                    return;
                }

                if (expr instanceof Ast.CastExpr cast) {
                    evalSuspendableExpr(
                            task,
                            cast.value(),
                            env,
                            (t, value, failure) -> {
                                if (failure != null) {
                                    continuation.accept(t, null, failure);
                                    return;
                                }
                                try {
                                    boolean matches =
                                            oresTypeMatches(
                                                    value,
                                                    cast.targetType());
                                    if (cast.mode()
                                            == Ast.CastMode.OPTIONAL) {
                                        continuation.accept(
                                                t,
                                                new OptionValue(
                                                        matches,
                                                        matches ? value : null),
                                                null);
                                    } else if (!matches) {
                                        continuation.accept(
                                                t,
                                                null,
                                                new OresCastError(
                                                        "cannot cast runtime type "
                                                                + oresRuntimeTypeName(value)
                                                                + " to "
                                                                + cast.targetType().name()));
                                    } else {
                                        continuation.accept(t, value, null);
                                    }
                                } catch (RuntimeException | Error failure2) {
                                    continuation.accept(t, null, failure2);
                                }
                            });
                    return;
                }

                if (expr instanceof Ast.MemberExpr member) {
                    evalSuspendableExpr(
                            task,
                            member.receiver(),
                            env,
                            (t, receiver, failure) -> {
                                if (failure != null) {
                                    continuation.accept(t, null, failure);
                                    return;
                                }
                                try {
                                    continuation.accept(
                                            t,
                                            member(
                                                    receiver,
                                                    member.member(),
                                                    env),
                                            null);
                                } catch (RuntimeException | Error failure2) {
                                    continuation.accept(t, null, failure2);
                                }
                            });
                    return;
                }

                if (expr instanceof Ast.IndexExpr index) {
                    evalSuspendableExpr(
                            task,
                            index.receiver(),
                            env,
                            (t, receiver, failure) -> {
                                if (failure != null) {
                                    continuation.accept(t, null, failure);
                                    return;
                                }
                                evalSuspendableExpr(
                                        t,
                                        index.index(),
                                        env,
                                        (t2, key, failure2) -> {
                                            if (failure2 != null) {
                                                continuation.accept(
                                                        t2,
                                                        null,
                                                        failure2);
                                                return;
                                            }
                                            try {
                                                continuation.accept(
                                                        t2,
                                                        indexValue(
                                                                receiver,
                                                                key),
                                                        null);
                                            } catch (RuntimeException | Error failure3) {
                                                continuation.accept(
                                                        t2,
                                                        null,
                                                        failure3);
                                            }
                                        });
                            });
                    return;
                }

                if (expr instanceof Ast.AssignExpr assignment) {
                    evalSuspendableExpr(
                            task,
                            assignment.value(),
                            env,
                            (t, value, failure) -> {
                                if (failure != null) {
                                    continuation.accept(t, null, failure);
                                    return;
                                }
                                try {
                                    continuation.accept(
                                            t,
                                            assignEvaluated(
                                                    assignment.target(),
                                                    value,
                                                    env),
                                            null);
                                } catch (RuntimeException | Error failure2) {
                                    continuation.accept(t, null, failure2);
                                }
                            });
                    return;
                }

                if (expr instanceof Ast.CallExpr call) {
                    evalSuspendableCall(
                            task,
                            call,
                            env,
                            continuation);
                    return;
                }

                if (expr instanceof Ast.NewExpr created) {
                    evalSuspendableArguments(
                            task,
                            created.arguments(),
                            env,
                            (t, values, failure) -> {
                                if (failure != null) {
                                    continuation.accept(t, null, failure);
                                    return;
                                }
                                try {
                                    continuation.accept(
                                            t,
                                            instantiateEvaluated(
                                                    created,
                                                    values,
                                                    env),
                                            null);
                                } catch (RuntimeException | Error failure2) {
                                    continuation.accept(t, null, failure2);
                                }
                            });
                    return;
                }

                if (expr instanceof Ast.ListExpr list) {
                    evalSuspendableArguments(
                            task,
                            list.elements(),
                            env,
                            (t, values, failure) ->
                                    continuation.accept(
                                            t,
                                            failure == null
                                                    ? List.copyOf(values)
                                                    : null,
                                            failure));
                    return;
                }

                if (expr instanceof Ast.TupleExpr tuple) {
                    evalSuspendableArguments(
                            task,
                            tuple.elements(),
                            env,
                            (t, values, failure) ->
                                    continuation.accept(
                                            t,
                                            failure == null
                                                    ? List.copyOf(values)
                                                    : null,
                                            failure));
                    return;
                }

                if (expr instanceof Ast.ObjectExpr object) {
                    evalSuspendableObject(
                            task,
                            object,
                            env,
                            continuation);
                    return;
                }

                continuation.accept(task, eval(expr, env), null);
            } catch (RuntimeException | Error failure) {
                continuation.accept(task, null, failure);
            }
        }

        private void evalSuspendableArguments(
                SourceTask task,
                List<Ast.Expr> expressions,
                Env env,
                SourceArgsCont continuation) {
            ArrayList<Object> values = new ArrayList<>(expressions.size());
            evalSuspendableArgumentAt(
                    task,
                    expressions,
                    env,
                    values,
                    0,
                    continuation);
        }

        private void evalSuspendableArgumentAt(
                SourceTask task,
                List<Ast.Expr> expressions,
                Env env,
                ArrayList<Object> values,
                int index,
                SourceArgsCont continuation) {
            if (index >= expressions.size()) {
                continuation.accept(task, List.copyOf(values), null);
                return;
            }
            evalSuspendableExpr(
                    task,
                    expressions.get(index),
                    env,
                    (t, value, failure) -> {
                        if (failure != null) {
                            continuation.accept(t, List.of(), failure);
                        } else {
                            values.add(value);
                            evalSuspendableArgumentAt(
                                    t,
                                    expressions,
                                    env,
                                    values,
                                    index + 1,
                                    continuation);
                        }
                    });
        }

        private void evalSuspendableObject(
                SourceTask task,
                Ast.ObjectExpr object,
                Env env,
                SourceValueCont continuation) {
            ArrayList<Object> values = new ArrayList<>();
            evalSuspendableObjectField(
                    task,
                    object,
                    env,
                    values,
                    0,
                    (t, built, failure) ->
                            continuation.accept(
                                    t,
                                    built,
                                    failure));
        }

        private void evalSuspendableObjectField(
                SourceTask task,
                Ast.ObjectExpr object,
                Env env,
                ArrayList<Object> values,
                int index,
                SourceObjectCont continuation) {
            if (index >= object.fields().size()) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                for (Object value : values) {
                    @SuppressWarnings("unchecked")
                    Map.Entry<String, Object> entry =
                            (Map.Entry<String, Object>) value;
                    if (result.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                        throw new IllegalArgumentException(
                                "duplicate obj field " + entry.getKey());
                    }
                }
                continuation.accept(task, result, null);
                return;
            }

            Ast.ObjectField field = object.fields().get(index);
            SourceValueCont afterKey =
                    (t, key, failure) -> {
                        if (failure != null) {
                            continuation.accept(t, null, failure);
                            return;
                        }
                        String actualKey = field.name();
                        if (field.isDynamic()) {
                            if (!(key instanceof String stringKey)) {
                                continuation.accept(
                                        t,
                                        null,
                                        new IllegalArgumentException(
                                                "dynamic obj key must evaluate to a string"));
                                return;
                            }
                            actualKey = stringKey;
                        }
                        evalSuspendableExpr(
                                t,
                                field.value(),
                                env,
                                (t2, value, failure2) -> {
                                    if (failure2 != null) {
                                        continuation.accept(t2, null, failure2);
                                        return;
                                    }
                                    values.add(
                                            Map.entry(actualKey, value));
                                    evalSuspendableObjectField(
                                            t2,
                                            object,
                                            env,
                                            values,
                                            index + 1,
                                            continuation);
                                });
                    };

            if (field.isDynamic()) {
                evalSuspendableExpr(
                        task,
                        field.dynamicName(),
                        env,
                        afterKey);
            } else {
                afterKey.accept(task, field.name(), null);
            }
        }

        private void suspendOnAwaitable(
                SourceTask task,
                Object value,
                SourceValueCont continuation) {
            OresFuture<?> future;
            if (value instanceof OresFuture<?> oresFuture) {
                future = oresFuture;
            } else if (value instanceof Awaitable<?> awaitable) {
                future = awaitable.getAwaited();
            } else if (value instanceof CompletionStage<?> stage) {
                future = OresFuture.from(stage);
            } else {
                continuation.accept(
                        task,
                        value,
                        null);
                return;
            }

            if (future == null) {
                continuation.accept(
                        task,
                        null,
                        new IllegalStateException(
                                "awaitable projected a null Future"));
                return;
            }

            if (future.isDone()) {
                try {
                    continuation.accept(
                            task,
                            future.join(),
                            null);
                } catch (RuntimeException | Error failure) {
                    continuation.accept(task, null, failure);
                }
                return;
            }

            task.suspend(
                    future,
                    (t, result, failure) ->
                            continuation.accept(
                                    t,
                                    result,
                                    failure));
        }

        private void evalSuspendableChannel(
                SourceTask task,
                Ast.ChannelOpExpr operation,
                Env env,
                SourceValueCont continuation) {
            evalSuspendableExpr(
                    task,
                    operation.channel(),
                    env,
                    (t, channelValue, channelFailure) -> {
                        if (channelFailure != null) {
                            continuation.accept(
                                    t,
                                    null,
                                    channelFailure);
                            return;
                        }

                        ChannelRuntime.Channel<Object> channel;
                        try {
                            channel = requireChannel(
                                    channelValue,
                                    operation.operation() == Ast.ChannelOperation.READ
                                            ? "readch"
                                            : "writech");
                        } catch (RuntimeException | Error failure) {
                            continuation.accept(t, null, failure);
                            return;
                        }

                        if (operation.operation()
                                == Ast.ChannelOperation.READ) {
                            if (operation.mode()
                                    == Ast.WaitMode.IMMEDIATE) {
                                try {
                                    java.util.Optional<Object> result =
                                            channel.tryRead();
                                    continuation.accept(
                                            t,
                                            result.isPresent()
                                                    ? new OptionValue(
                                                            true,
                                                            result.get())
                                                    : new OptionValue(
                                                            false,
                                                            null),
                                            null);
                                } catch (RuntimeException | Error failure) {
                                    continuation.accept(t, null, failure);
                                }
                                return;
                            }

                            OresFuture<Object> future =
                                    channel.readAsync();
                            if (operation.mode()
                                    == Ast.WaitMode.NONBLOCKING) {
                                if (ActorRuntime.inActorExecution()) {
                                    context.actors().ownCurrentActorFuture(
                                            future);
                                }
                                continuation.accept(
                                        t,
                                        future,
                                        null);
                                return;
                            }

                            if (future.isDone()) {
                                try {
                                    continuation.accept(
                                            t,
                                            future.join(),
                                            null);
                                } catch (RuntimeException | Error failure) {
                                    continuation.accept(t, null, failure);
                                }
                            } else {
                                t.suspend(
                                        future,
                                        (t2, value, failure) ->
                                                continuation.accept(
                                                        t2,
                                                        value,
                                                        failure));
                            }
                            return;
                        }

                        evalSuspendableExpr(
                                t,
                                operation.value(),
                                env,
                                (t2, value, valueFailure) -> {
                                    if (valueFailure != null) {
                                        continuation.accept(
                                                t2,
                                                null,
                                                valueFailure);
                                        return;
                                    }

                                    if (operation.callback()) {
                                        try {
                                            if (!ActorRuntime.inActorExecution()) {
                                                throw new IllegalStateException(
                                                        "nb cb writech requires actor execution");
                                            }
                                            OresFuture<Void> completion =
                                                    channel.writeAsync(value);
                                            ActorRuntime.ContinuationTarget target =
                                                    context.actors()
                                                            .captureCurrentContinuationTarget();
                                            Env callbackEnv = env.snapshot();
                                            context.actors().enqueueOnCompletion(
                                                    completion,
                                                    target,
                                                    (ignored, failure) -> {
                                                        if (failure != null) {
                                                            throw sourceFailure(failure);
                                                        }
                                                        try {
                                                            executeBlock(
                                                                    operation.callbackBody(),
                                                                    callbackEnv,
                                                                    true);
                                                        } catch (ReturnSignal returned) {
                                                            if (returned.value != null) {
                                                                throw new IllegalStateException(
                                                                        "channel write callback cannot return a value");
                                                            }
                                                        }
                                                    });
                                            continuation.accept(
                                                    t2,
                                                    null,
                                                    null);
                                        } catch (RuntimeException | Error failure) {
                                            continuation.accept(t2, null, failure);
                                        }
                                        return;
                                    }

                                    if (operation.mode()
                                            == Ast.WaitMode.IMMEDIATE) {
                                        try {
                                            continuation.accept(
                                                    t2,
                                                    channel.tryWrite(value),
                                                    null);
                                        } catch (RuntimeException | Error failure) {
                                            continuation.accept(
                                                    t2,
                                                    null,
                                                    failure);
                                        }
                                        return;
                                    }

                                    OresFuture<Void> future =
                                            channel.writeAsync(value);
                                    if (operation.mode()
                                            == Ast.WaitMode.NONBLOCKING) {
                                        if (ActorRuntime.inActorExecution()) {
                                            context.actors().ownCurrentActorFuture(
                                                    future);
                                        }
                                        continuation.accept(
                                                t2,
                                                future,
                                                null);
                                        return;
                                    }

                                    if (future.isDone()) {
                                        try {
                                            future.join();
                                            continuation.accept(
                                                    t2,
                                                    null,
                                                    null);
                                        } catch (RuntimeException | Error failure) {
                                            continuation.accept(
                                                    t2,
                                                    null,
                                                    failure);
                                        }
                                    } else {
                                        t2.suspend(
                                                future,
                                                (t3, ignored, failure) ->
                                                        continuation.accept(
                                                                t3,
                                                                null,
                                                                failure));
                                    }
                                });
                    });
        }

        private void evalSuspendableCall(
                SourceTask task,
                Ast.CallExpr call,
                Env env,
                SourceValueCont continuation) {
            if (call.callee() instanceof Ast.NameExpr name
                    && env.lookup(name.name()) == Env.MISSING) {
                Ast.FunctionDecl direct = findFunction(name.name());
                if (direct != null) {
                    evalSuspendableArguments(
                            task,
                            call.arguments(),
                            env,
                            (t, values, failure) -> {
                                if (failure != null) {
                                    continuation.accept(t, null, failure);
                                    return;
                                }
                                try {
                                    Invocation invocation =
                                            functionInvocation(direct, values);
                                    completeSourceInvocation(
                                            t,
                                            invocation,
                                            direct.async()
                                                    || direct.actorKind()
                                                    == Ast.ActorKind.NONE
                                                            ? direct.async()
                                                            : false,
                                            continuation);
                                } catch (RuntimeException | Error failure2) {
                                    continuation.accept(t, null, failure2);
                                }
                            });
                    return;
                }
            }

            if (call.callee() instanceof Ast.MemberExpr member) {
                evalSuspendableExpr(
                        task,
                        member.receiver(),
                        env,
                        (t, receiver, failure) -> {
                            if (failure != null) {
                                continuation.accept(t, null, failure);
                                return;
                            }
                            evalSuspendableArguments(
                                    t,
                                    call.arguments(),
                                    env,
                                    (t2, values, failure2) -> {
                                        if (failure2 != null) {
                                            continuation.accept(t2, null, failure2);
                                            return;
                                        }
                                        try {
                                            Invocation invocation =
                                                    prepareInvocationEvaluated(
                                                            call,
                                                            receiver,
                                                            values,
                                                            env);
                                            boolean declaredFuture =
                                                    invocationDeclaresAsync(
                                                            invocation);
                                            completeSourceInvocation(
                                                    t2,
                                                    invocation,
                                                    declaredFuture,
                                                    continuation);
                                        } catch (RuntimeException | Error failure3) {
                                            continuation.accept(t2, null, failure3);
                                        }
                                    });
                        });
                return;
            }

            evalSuspendableExpr(
                    task,
                    call.callee(),
                    env,
                    (t, callee, failure) -> {
                        if (failure != null) {
                            continuation.accept(t, null, failure);
                            return;
                        }
                        evalSuspendableArguments(
                                t,
                                call.arguments(),
                                env,
                                (t2, values, failure2) -> {
                                    if (failure2 != null) {
                                        continuation.accept(t2, null, failure2);
                                        return;
                                    }
                                    if (!(callee instanceof Invokable invokable)) {
                                        continuation.accept(
                                                t2,
                                                null,
                                                new IllegalArgumentException(
                                                        "value is not callable"));
                                        return;
                                    }
                                    Invocation invocation =
                                            invokableInvocation(invokable, values);
                                    completeSourceInvocation(
                                            t2,
                                            invocation,
                                            false,
                                            continuation);
                                });
                    });
        }

        private void completeSourceInvocation(
                SourceTask task,
                Invocation invocation,
                boolean declaredFuture,
                SourceValueCont continuation) {
            try {
                Object result = invoke(invocation);
                if (result instanceof OresFuture<?> future
                        && !declaredFuture) {
                    task.suspend(
                            future,
                            (t, value, failure) ->
                                    continuation.accept(
                                            t,
                                            value,
                                            failure));
                } else {
                    continuation.accept(
                            task,
                            result,
                            null);
                }
            } catch (RuntimeException | Error failure) {
                continuation.accept(task, null, failure);
            }
        }

        private boolean invocationDeclaresAsync(Invocation invocation) {
            return switch (invocation.kind()) {
                case FUNCTION, FUNCTION_BODY ->
                        ((Ast.FunctionDecl) invocation.target()).async();
                case METHOD, STATIC_FUNCTION, STATIC_FUNCTION_BODY ->
                        ((Ast.MethodDecl) invocation.target()).async();
                case INVOKABLE -> false;
            };
        }

        private Invocation prepareInvocationEvaluated(
                Ast.CallExpr call,
                Object receiver,
                List<Object> args,
                Env env) {
            Ast.MemberExpr member =
                    (Ast.MemberExpr) call.callee();

            if (receiver instanceof OresObject object) {
                Ast.MethodDecl method =
                        object.owner.findMethod(
                                object.klass,
                                member.member(),
                                args.size(),
                                new LinkedHashSet<>());
                if (method != null) {
                    object.owner.requireClassMemberVisible(
                            method.visibility(),
                            object.owner.declaringClass(method),
                            env.accessClass(),
                            "method",
                            method.name());
                    return object.owner.methodInvocation(
                            object,
                            method,
                            args);
                }

                Object fieldValue = object.fields.get(member.member());
                if (fieldValue instanceof Invokable invokable) {
                    return object.owner.invokableInvocation(
                            invokable,
                            args);
                }
            }

            if (receiver instanceof ClassFacade klass) {
                Ast.MethodDecl method =
                        klass.owner().findStaticFunction(
                                klass.klass(),
                                member.member(),
                                args.size(),
                                new LinkedHashSet<>());
                if (method == null) {
                    throw new IllegalArgumentException(
                            "no static function " + klass.klass().name()
                                    + "." + member.member());
                }
                return klass.owner().staticFunctionInvocation(
                        method,
                        args);
            }

            if (receiver instanceof ModuleFacade module) {
                return module.owner().prepareModuleInvocation(
                        module.module(),
                        member.member(),
                        args);
            }

            if (receiver instanceof ImportedNamespace namespace) {
                return namespace.owner().prepareImportedInvocation(
                        namespace.kind(),
                        member.member(),
                        args);
            }

            Object callee = member(receiver, member.member(), env);
            if (!(callee instanceof Invokable invokable)) {
                throw new IllegalArgumentException(
                        "member is not callable: " + member.member());
            }
            return invokableInvocation(invokable, args);
        }

        private Object indexValue(Object receiver, Object index) {
            if (receiver instanceof DynamicStructValue dynamic) {
                if (!(index instanceof String key)) {
                    throw new IllegalArgumentException(
                            "DynamicStruct key must be a string");
                }
                if (!dynamic.fields.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "unknown DynamicStruct key " + key);
                }
                return dynamic.fields.get(key);
            }
            if (receiver instanceof Map<?, ?> map) {
                if (!(index instanceof String key)) {
                    throw new IllegalArgumentException(
                            "object/map key must be a string");
                }
                if (!map.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "unknown object/map key " + key);
                }
                return map.get(key);
            }
            if (!(index instanceof Number number)) {
                throw new IllegalArgumentException(
                        "array/list index must be an integer");
            }
            int i = Math.toIntExact(number.longValue());
            if (receiver instanceof List<?> list) return list.get(i);
            if (receiver instanceof Object[] array) return array[i];
            throw new IllegalArgumentException(
                    "value is not indexable");
        }

        private Object unaryValue(String operator, Object value) {
            return switch (operator) {
                case "&", "&mut", "+" -> value;
                case "!" -> !truth(value);
                case "~" -> ~integralLong(value);
                case "-" -> negate(value);
                default -> throw new IllegalArgumentException(
                        "unsupported unary operator " + operator);
            };
        }

        private Object assignEvaluated(
                Ast.Expr target,
                Object value,
                Env env) {
            if (target instanceof Ast.NameExpr name) {
                env.assign(name.name(), value);
                return value;
            }
            if (target instanceof Ast.MemberExpr member) {
                Object receiver = eval(member.receiver(), env);
                if (receiver instanceof OresObject object) {
                    OwnedField targetField =
                            object.owner.findField(
                                    object.klass,
                                    member.member(),
                                    new LinkedHashSet<>());
                    if (targetField == null) {
                        throw new IllegalArgumentException(
                                "unknown field " + member.member());
                    }
                    if (targetField.field().bindingKind() != Ast.BindingKind.LET) {
                        throw new IllegalArgumentException(
                                "field '" + object.klass.name()
                                        + "." + member.member()
                                        + "' is immutable");
                    }
                    object.fields.put(member.member(), value);
                    return value;
                }
                if (receiver instanceof DynamicStructValue dynamic) {
                    dynamic.fields.put(member.member(), value);
                    return value;
                }
                throw new IllegalArgumentException(
                        "member assignment requires mutable object state");
            }
            if (target instanceof Ast.IndexExpr index) {
                Object receiver = eval(index.receiver(), env);
                Object key = eval(index.index(), env);
                if (receiver instanceof DynamicStructValue dynamic) {
                    if (!(key instanceof String string)) {
                        throw new IllegalArgumentException(
                                "DynamicStruct key must be a string");
                    }
                    dynamic.fields.put(string, value);
                    return value;
                }
                if (receiver instanceof List<?> raw
                        && key instanceof Number number) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) raw;
                    list.set(Math.toIntExact(number.longValue()), value);
                    return value;
                }
                throw new IllegalArgumentException(
                        "indexed assignment requires mutable array/list or DynamicStruct");
            }
            throw new IllegalArgumentException(
                    "unsupported assignment target");
        }

        private Object instantiateEvaluated(
                Ast.NewExpr created,
                List<Object> args,
                Env env) {
            if (created.type().name().equals("DynamicStruct")) {
                if (!args.isEmpty()) {
                    throw new IllegalArgumentException(
                            "DynamicStruct<T> constructor takes no positional arguments");
                }
                return new DynamicStructValue();
            }
            HostClassFacade hostClass =
                    hostClasses.get(created.type().name());
            if (hostClass != null) {
                context.requireCapability(
                        IsolatePolicy.Capability.JAVA_INTEROP,
                        "Java host constructor " + hostClass.className());
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
            if (klass == null) {
                throw new IllegalArgumentException(
                        "unknown class " + created.type().name());
            }
            return owner.instantiate(klass, args);
        }

        private Object callFunctionRaw(Ast.FunctionDecl fn, List<Object> args) {
            if (fn.name().equals("init")) {
                throw new IllegalStateException(
                        "init is a lifecycle hook and cannot be invoked directly; startup runs it exactly once");
            }

            List<Object> normalized = normalizeFunctionArguments(fn, args);

            if (fn.actorKind() == Ast.ActorKind.NONE) {
                if (fn.async() || functionContainsPotentialSuspension(fn)) {
                    OresFuture<Object> future =
                            startSourceFunctionTask(
                                    fn,
                                    fn.async()
                                            ? detachAsyncArguments(normalized)
                                            : normalized);

                    if (fn.async()) {
                        return future;
                    }

                    return returnSourceTaskResult(future, false);
                }

                return callFunctionBodyRaw(fn, normalized);
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
                case UNTRUSTED -> ActorRuntime.ActorKind.UNTRUSTED;
            };

            if (fn.async() || functionContainsPotentialSuspension(fn)) {
                OresFuture<Object> completion =
                        context.actors().invokeAsync(
                                runtimeKind,
                                normalized,
                                (delivered, actorContext) ->
                                        invoke(
                                                functionBodyInvocation(
                                                        fn,
                                                        delivered)));

                return fn.async()
                        ? completion
                        : returnSourceTaskResult(completion, false);
            }

            return context.actors().invoke(
                    runtimeKind,
                    normalized,
                    (delivered, actorContext) ->
                            invoke(functionBodyInvocation(fn, delivered)));
        }

        private Object returnSourceTaskResult(
                OresFuture<?> future,
                boolean declaredFuture) {
            if (declaredFuture
                    || ActorRuntime.inActorExecution()
                    || OresScheduler.current() != null) {
                return future;
            }
            return future.join();
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
            if (fn.async() || functionContainsPotentialSuspension(fn)) {
                OresFuture<Object> future =
                        startSourceFunctionTask(
                                fn,
                                fn.async()
                                        ? detachAsyncArguments(args)
                                        : args);
                return returnSourceTaskResult(future, fn.async());
            }

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

        private boolean methodContainsPotentialSuspension(
                Ast.MethodDecl method) {
            for (Ast.Stmt stmt : method.body()) {
                if (statementContainsPotentialSuspension(
                        stmt,
                        java.util.Collections.newSetFromMap(
                                new IdentityHashMap<>()))) {
                    return true;
                }
            }
            return false;
        }

        private Object callMethod(OresObject receiver, Ast.MethodDecl method, List<?> args) {
            return invoke(methodInvocation(receiver, method, args));
        }

        private Object callMethodRaw(OresObject receiver, Ast.MethodDecl method, List<?> args) {
            if (args.size() != method.parameters().size()) {
                throw new IllegalArgumentException(
                        "method " + method.name() + " arity mismatch");
            }
            if (method.async() || methodContainsPotentialSuspension(method)) {
                OresFuture<Object> future =
                        startSourceMethodTask(method, receiver, args);
                return returnSourceTaskResult(future, method.async());
            }
            Env env = new Env(null, false, declaringClass(method));
            if (!method.isStatic()) env.define("self", receiver, Ast.BindingKind.VAL);
            for (int i = 0; i < method.parameters().size(); i++) {
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
            if (ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.UNTRUSTED) {
                context.schedulerSafepoint();
            }
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
            if (stmt instanceof Ast.SelectStmt selected) {
                executeSelectStatement(
                        selected,
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
                for (Object item : iterableValues(iterable, env)) {
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
                return;
            }
            if (stmt instanceof Ast.ForOfStmt loop) {
                Object iterable = eval(loop.iterable(), env);
                for (Object item : iterableValues(iterable, env)) {
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

        private void executeSelectStatement(
                Ast.SelectStmt selected,
                Env env,
                boolean tailBarrier) {
            ChannelRuntime.SelectSet set = buildStaticSelectSet(selected, env);
            ChannelRuntime.SelectPolicy policy = runtimeSelectPolicy(selected.policy());

            if (selected.mode() == Ast.WaitMode.IMMEDIATE) {
                java.util.Optional<ChannelRuntime.SelectResult> result =
                        set.trySelect(policy);
                if (result.isPresent()) {
                    executeSelectedArm(
                            selected,
                            result.get(),
                            env,
                            tailBarrier,
                            false);
                }
                return;
            }

            OresFuture<ChannelRuntime.SelectResult> future =
                    set.selectAsync(policy);

            if (selected.mode() == Ast.WaitMode.NONBLOCKING) {
                if (!ActorRuntime.inActorExecution()) {
                    future.cancel(false);
                    throw new IllegalStateException(
                            "static nb select branches require an actor execution context; "
                                    + "use 'nb select from cases' when you only need a Future");
                }

                ActorRuntime.ContinuationTarget target =
                        context.actors().captureCurrentContinuationTarget();
                Env captured = env.snapshot();
                context.actors().enqueueOnCompletion(
                        future,
                        target,
                        (result, failure) -> {
                            if (failure != null) throw propagateAsyncFailure(failure);
                            executeSelectedArm(
                                    selected,
                                    result,
                                    captured,
                                    true,
                                    true);
                        });
                return;
            }

            ChannelRuntime.SelectResult result =
                    (ChannelRuntime.SelectResult)
                            awaitBlockingChannelFuture(future, "select");
            executeSelectedArm(selected, result, env, tailBarrier, false);
        }

        private ChannelRuntime.SelectSet buildStaticSelectSet(
                Ast.SelectStmt selected,
                Env env) {
            ArrayList<ChannelRuntime.SelectCase> cases =
                    new ArrayList<>(selected.arms().size());
            for (Ast.SelectArm arm : selected.arms()) {
                switch (arm.operation()) {
                    case DEFAULT -> cases.add(ChannelRuntime.defaultCase());
                    case READ -> cases.add(ChannelRuntime.read(
                            requireChannel(eval(arm.channel(), env), "readch select case")));
                    case WRITE -> cases.add(ChannelRuntime.write(
                            requireChannel(eval(arm.channel(), env), "writech select case"),
                            eval(arm.value(), env)));
                }
            }
            return new ChannelRuntime.SelectSet(
                    cases,
                    staticSelectTicket(selected));
        }

        private long staticSelectTicket(Ast.SelectStmt selected) {
            synchronized (staticSelectCursors) {
                return staticSelectCursors
                        .computeIfAbsent(selected, ignored -> new AtomicLong())
                        .getAndIncrement();
            }
        }

        private void executeSelectedArm(
                Ast.SelectStmt selected,
                ChannelRuntime.SelectResult result,
                Env parent,
                boolean tailBarrier,
                boolean detached) {
            if (result.index() < 0 || result.index() >= selected.arms().size()) {
                throw new IllegalStateException(
                        "select result index is outside its source arm set: "
                                + result.index());
            }

            Ast.SelectArm arm = selected.arms().get(result.index());
            Env armEnv = new Env(parent);
            if (arm.operation() == Ast.ChannelOperation.READ
                    && arm.bindingName() != null) {
                armEnv.define(
                        arm.bindingName(),
                        result.value(),
                        arm.bindingKind());
            }

            if (!detached) {
                executeBlock(arm.body(), armEnv, tailBarrier);
                return;
            }

            try {
                executeBlock(arm.body(), armEnv, true);
            } catch (ReturnSignal returned) {
                if (returned.value != null) {
                    throw new IllegalStateException(
                            "nb select continuation cannot return a value");
                }
                // return; exits only this detached arm.
            } catch (BreakSignal | ContinueSignal escapedLoopControl) {
                throw new IllegalStateException(
                        "nb select continuation cannot break/continue an enclosing loop",
                        escapedLoopControl);
            }
        }

        private Object evalChannelOperation(
                Ast.ChannelOpExpr operation,
                Env env) {
            ChannelRuntime.Channel<Object> channel = requireChannel(
                    eval(operation.channel(), env),
                    operation.operation() == Ast.ChannelOperation.READ
                            ? "readch"
                            : "writech");

            if (operation.operation() == Ast.ChannelOperation.READ) {
                return switch (operation.mode()) {
                    case IMMEDIATE -> {
                        java.util.Optional<Object> value = channel.tryRead();
                        yield value.isPresent()
                                ? new OptionValue(true, value.get())
                                : new OptionValue(false, null);
                    }
                    case NONBLOCKING -> channel.readAsync();
                    case BLOCKING -> awaitBlockingChannelFuture(
                            channel.readAsync(),
                            "readch");
                };
            }

            Object value = eval(operation.value(), env);
            if (operation.callback()) {
                if (!ActorRuntime.inActorExecution()) {
                    throw new IllegalStateException(
                            "nb cb writech can only execute inside an actor turn");
                }
                OresFuture<Void> completion = channel.writeAsync(value);
                ActorRuntime.ContinuationTarget target =
                        context.actors().captureCurrentContinuationTarget();
                Env callbackEnv = env.snapshot();
                context.actors().enqueueOnCompletion(
                        completion,
                        target,
                        (ignored, failure) -> {
                            if (failure != null) {
                                throw propagateAsyncFailure(OresFuture.unwrap(failure));
                            }
                            try {
                                executeBlock(operation.callbackBody(), callbackEnv, true);
                            } catch (ReturnSignal returned) {
                                if (returned.value != null) {
                                    throw new IllegalStateException(
                                            "nb cb writech callback cannot return a value");
                                }
                            } catch (BreakSignal | ContinueSignal control) {
                                throw new IllegalStateException(
                                        "nb cb writech callback cannot break/continue an enclosing loop",
                                        control);
                            }
                        });
                return null;
            }

            return switch (operation.mode()) {
                case IMMEDIATE -> channel.tryWrite(value);
                case NONBLOCKING -> channel.writeAsync(value);
                case BLOCKING -> {
                    awaitBlockingChannelFuture(
                            channel.writeAsync(value),
                            "writech");
                    yield null;
                }
            };
        }

        private Object awaitBlockingChannelFuture(
                OresFuture<?> future,
                String operation) {
            if (future.isDone()) return future.join();
            if (ActorRuntime.inActorExecution()
                    || context.vm().isRootSchedulerCurrent()) {
                // This registration belongs only to this attempted blocking
                // operation. Remove it before failing closed so no waiter leaks.
                future.cancel(false);
                throw new IllegalStateException(
                        operation
                                + " would suspend a scheduler-owned actor/root turn, but the current "
                                + "interpreter has not yet lowered this call stack to the OresScheduler "
                                + "resumable-task ABI; the runtime refuses to park a scheduler carrier. "
                                + "Use nb " + operation
                                + " or a ready/immediate case until continuation lowering is active.");
            }
            // Transitional host/embedder path. Scheduler carriers never reach here.
            return future.join();
        }

        @SuppressWarnings("unchecked")
        private ChannelRuntime.Channel<Object> requireChannel(
                Object value,
                String where) {
            if (!(value instanceof ChannelRuntime.Channel<?> channel)) {
                throw new IllegalArgumentException(
                        where + " requires Channel<T>; got " + value);
            }
            return (ChannelRuntime.Channel<Object>) channel;
        }

        private ChannelRuntime.SelectSet asSelectSet(Object value) {
            if (value instanceof ChannelRuntime.SelectSet set) return set;

            ArrayList<ChannelRuntime.SelectCase> cases = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) cases.add(requireSelectCase(item));
                return new ChannelRuntime.SelectSet(cases);
            }
            if (value instanceof Map<?, ?> map) {
                for (Object item : map.values()) cases.add(requireSelectCase(item));
                return new ChannelRuntime.SelectSet(cases);
            }
            if (value instanceof DynamicStructValue dynamic) {
                for (Object item : dynamic.fields.values()) {
                    cases.add(requireSelectCase(item));
                }
                return new ChannelRuntime.SelectSet(cases);
            }
            throw new IllegalArgumentException(
                    "dynamic select requires SelectSet or list/map of SelectCase values");
        }

        private ChannelRuntime.SelectCase requireSelectCase(Object value) {
            if (value instanceof ChannelRuntime.SelectCase selectCase) {
                return selectCase;
            }
            throw new IllegalArgumentException(
                    "dynamic select collection contains non-SelectCase value: "
                            + value);
        }

        private ChannelRuntime.SelectPolicy runtimeSelectPolicy(
                Ast.SelectPolicy policy) {
            return switch (policy) {
                case FAIR -> ChannelRuntime.SelectPolicy.FAIR;
                case PRIORITY -> ChannelRuntime.SelectPolicy.PRIORITY;
                case RANDOM -> ChannelRuntime.SelectPolicy.RANDOM;
            };
        }

        private RuntimeException propagateAsyncFailure(Throwable failure) {
            if (failure instanceof RuntimeException runtime) return runtime;
            if (failure instanceof Error error) throw error;
            return new RuntimeException(failure);
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
                            object.klass, methodCall.member(), args.size(), new LinkedHashSet<>());
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
                            klass.klass(), methodCall.member(), args.size(), new LinkedHashSet<>());
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
                            object.klass, methodCall.member(), args.size(), new LinkedHashSet<>());
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
                if (name.name().equals("Channel")) return new ChannelFactory();
                if (name.name().equals("SelectCase")) return new SelectCaseFactory();
                if (name.name().equals("SelectSet")) return new SelectSetFactory(this);
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
                    if (future.isDone()) return future.join();
                    if (ActorRuntime.inActorExecution()) {
                        throw new IllegalStateException(
                                "pending await inside an actor requires resumable actor continuation lowering; "
                                        + "the runtime will not park an actor carrier");
                    }
                    return future.join();
                }
                if (value instanceof CompletionStage<?> stage) {
                    return AsyncRuntime.await(stage);
                }
                return value;
            }
            if (expr instanceof Ast.ChannelOpExpr channelOp) {
                return evalChannelOperation(channelOp, env);
            }
            if (expr instanceof Ast.DynamicSelectExpr selected) {
                ChannelRuntime.SelectSet set = asSelectSet(eval(selected.cases(), env));
                ChannelRuntime.SelectPolicy policy = runtimeSelectPolicy(selected.policy());
                if (selected.mode() == Ast.WaitMode.IMMEDIATE) {
                    java.util.Optional<ChannelRuntime.SelectResult> result =
                            set.trySelect(policy);
                    return result.isPresent()
                            ? new OptionValue(true, result.get())
                            : new OptionValue(false, null);
                }

                OresFuture<ChannelRuntime.SelectResult> future =
                        set.selectAsync(policy);
                if (selected.mode() == Ast.WaitMode.NONBLOCKING) return future;
                return awaitBlockingChannelFuture(future, "dynamic select");
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
            if (receiver instanceof ChannelFactory factory) {
                if (!name.equals("new")) {
                    throw new IllegalArgumentException("unknown Channel factory member " + name);
                }
                return (Invokable) factory::create;
            }
            if (receiver instanceof SelectCaseFactory factory) {
                return switch (name) {
                    case "read" -> (Invokable) factory::read;
                    case "write" -> (Invokable) factory::write;
                    case "default" -> (Invokable) factory::defaultCase;
                    default -> throw new IllegalArgumentException(
                            "unknown SelectCase factory member " + name);
                };
            }
            if (receiver instanceof SelectSetFactory factory) {
                if (!name.equals("new")) {
                    throw new IllegalArgumentException("unknown SelectSet factory member " + name);
                }
                return (Invokable) factory::create;
            }
            if (receiver instanceof SelectResultValue selected) {
                return switch (name) {
                    case "index" -> (long) selected.index();
                    case "operation" -> selected.operation();
                    case "value" -> selected.value();
                    default -> throw new IllegalArgumentException(
                            "unknown SelectResult member " + name);
                };
            }
            if (receiver instanceof ChannelRuntime.SelectResult selected) {
                return switch (name) {
                    case "index" -> (long) selected.index();
                    case "operation" -> selected.operation().name().toLowerCase(java.util.Locale.ROOT);
                    case "value" -> selected.value();
                    default -> throw new IllegalArgumentException(
                            "unknown SelectResult member " + name);
                };
            }
            if (receiver instanceof MutexFactory factory) {
                if (!name.equals("new")) throw new IllegalArgumentException("unknown mutex factory member " + name);
                return (Invokable) factory::create;
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
                    return klass.owner().tailCallable(
                            args -> klass.owner().callStaticFunctionRaw(fn, objectArguments(args)));
                }
                if (functions.size() > 1) throw new IllegalArgumentException("overloaded static function " + klass.klass().name() + "." + name + " must be called so arity can select it");
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
                    throw new IllegalArgumentException("instance method " + object.klass.name() + "." + name
                            + " is direct-call-only and cannot be used as a first-class callable value; "
                            + "wrap receiver." + name + "(...) in an explicit lambda when a callback is required");
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
            if (value instanceof Number || value instanceof Boolean || value instanceof String
                    || value instanceof Character || value instanceof CompletionStage<?>) {
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

        private Object invokeMethod(OresObject receiver, String name, List<Object> args) {
            Ast.MethodDecl method = findMethod(receiver.klass, name, args.size(), new LinkedHashSet<>());
            if (method == null) throw new IllegalArgumentException("no method " + receiver.klass.name() + "." + name + " with arity " + args.size());
            return callMethod(receiver, method, args);
        }

        private Object invokeStaticFunction(Ast.ClassDecl klass, String name, List<Object> args) {
            Ast.MethodDecl fn = findStaticFunction(klass, name, args.size(), new LinkedHashSet<>());
            if (fn == null) throw new IllegalArgumentException("no static function " + klass.name() + "." + name + " with arity " + args.size());
            return callStaticFunction(klass, fn, args);
        }

        private Object callStaticFunction(Ast.ClassDecl klass, Ast.MethodDecl fn, List<?> args) {
            if (!fn.isStatic()) throw new IllegalArgumentException("not a static class function: " + klass.name() + "." + fn.name());
            return invoke(staticFunctionInvocation(fn, args));
        }

        private Object callStaticFunctionRaw(Ast.MethodDecl fn, List<?> args) {
            if (!fn.isStatic()) {
                throw new IllegalArgumentException(
                        "not a static class function: " + fn.name());
            }
            if (args.size() != fn.parameters().size()) {
                throw new IllegalArgumentException(
                        "static function " + fn.name() + " arity mismatch");
            }
            if (fn.async() || methodContainsPotentialSuspension(fn)) {
                OresFuture<Object> future =
                        startSourceMethodTask(
                                fn,
                                null,
                                fn.async()
                                        ? detachAsyncArguments(args)
                                        : args);
                return returnSourceTaskResult(future, fn.async());
            }
            return callStaticFunctionBodyRaw(fn, args);
        }

        private Object callStaticFunctionBodyRaw(Ast.MethodDecl fn, List<?> args) {
            Env env = new Env(null, false, declaringClass(fn));
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
                return shapeReturnedValue(fn.returnType(), signal.value, "static function " + fn.name());
            } catch (BreakSignal | ContinueSignal signal) {
                throw new IllegalStateException("loop control cannot cross a static function boundary", signal);
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

        private Ast.MethodDecl findMethod(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            for (Ast.MethodDecl method : klass.methods()) {
                if (!method.isStatic() && method.name().equals(name) && method.parameters().size() == arity) {
                    seen.remove(klass);
                    return method;
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
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

        private Ast.MethodDecl findStaticFunction(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            for (Ast.MethodDecl fn : klass.methods()) {
                if (fn.isStatic() && fn.name().equals(name) && fn.parameters().size() == arity) {
                    seen.remove(klass);
                    return fn;
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
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
            LinkedHashMap<Integer, Ast.MethodDecl> result = new LinkedHashMap<>();
            for (Ast.MethodDecl fn : klass.methods()) {
                if (fn.isStatic() && fn.name().equals(name)) result.put(fn.parameters().size(), fn);
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent == null) continue;
                for (Ast.MethodDecl fn : findStaticFunctionsByName(parent, name, seen)) result.putIfAbsent(fn.parameters().size(), fn);
            }
            seen.remove(klass);
            return List.copyOf(result.values());
        }

        private List<?> iterableValues(Object value, Env env) {
            if (value instanceof List<?> list) return list;
            if (value instanceof Object[] array) return List.of(array);
            if (value instanceof OresObject object) {
                Ast.MethodDecl iterator = findMethod(object.klass, "Symbol.iterator", 0, new LinkedHashSet<>());
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
            throw new IllegalArgumentException("value is not iterable");
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
    private static final class ChannelFactory {
        private Object create(List<Object> args) {
            requireOne(args, "Channel.new<T>");
            if (!(args.getFirst() instanceof Number number)) {
                throw new IllegalArgumentException("Channel.new<T> capacity must be an integer");
            }
            int capacity = Math.toIntExact(number.longValue());
            return new ChannelRuntime.Channel<>(capacity);
        }
    }

    private static final class SelectCaseFactory {
        @SuppressWarnings({"rawtypes", "unchecked"})
        private Object read(List<Object> args) {
            requireOne(args, "SelectCase.read");
            if (!(args.getFirst() instanceof ChannelRuntime.Channel<?> channel)) {
                throw new IllegalArgumentException("SelectCase.read expects Channel<T>");
            }
            return ChannelRuntime.read((ChannelRuntime.Channel) channel);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private Object write(List<Object> args) {
            requireTwo(args, "SelectCase.write");
            if (!(args.getFirst() instanceof ChannelRuntime.Channel<?> channel)) {
                throw new IllegalArgumentException("SelectCase.write expects Channel<T> as first argument");
            }
            return ChannelRuntime.write(
                    (ChannelRuntime.Channel) channel,
                    args.get(1));
        }

        private Object defaultCase(List<Object> args) {
            requireZero(args, "SelectCase.default");
            return ChannelRuntime.defaultCase();
        }
    }

    private record SelectSetFactory(Evaluator owner) {
        private Object create(List<Object> args) {
            requireOne(args, "SelectSet.new");
            return owner.asSelectSet(args.getFirst());
        }
    }

    private record SelectResultValue(
            int index,
            String operation,
            Object value) implements OresMutex.SharedState {
        @Override
        public Iterable<?> sharedStateChildren() {
            return value == null ? List.of() : List.of(value);
        }
    }

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
    private static void requireTwo(List<Object> args,String name){if(args.size()!=2)throw new IllegalArgumentException(name+" expects two arguments");}
    private static String requireStringArg(List<Object> args,String name){
        requireOne(args,name);
        if(!(args.getFirst() instanceof String message)) throw new IllegalArgumentException(name+" expects a String message");
        return message;
    }
}
