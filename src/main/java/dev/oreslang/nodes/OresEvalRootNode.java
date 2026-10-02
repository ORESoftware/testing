package dev.oreslang.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresValues.Complex;
import dev.oreslang.runtime.OresValues.OptionValue;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.ProcessSingletonRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
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
    private final Ast.Program program;
    private final String codeUnitId;
    private final String codeUnitDigest;

    public OresEvalRootNode(OresLanguage language, Ast.Program program) {
        this(language, program, "<anonymous>", digestText(program.toString()));
    }

    public OresEvalRootNode(OresLanguage language, Ast.Program program, String codeUnitId) {
        this(language, program, codeUnitId, digestText(program.toString()));
    }

    public OresEvalRootNode(
            OresLanguage language,
            Ast.Program program,
            String codeUnitId,
            String codeUnitDigest) {
        super(language);
        this.program = program;
        this.codeUnitId = codeUnitId == null || codeUnitId.isBlank() ? "<anonymous>" : codeUnitId;
        this.codeUnitDigest = codeUnitDigest == null || codeUnitDigest.isBlank()
                ? digestText(program.toString())
                : codeUnitDigest;
    }

    private static String digestText(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
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
        return new Evaluator(program, context, codeUnitId, codeUnitDigest).execute(arguments);
    }

    private static final class Evaluator {
        private final Ast.Program program;
        private final OresContext context;
        private final String codeUnitId;
        private final String codeUnitDigest;
        private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
        private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
        private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();
        private final IdentityHashMap<Ast.FunctionDecl, String> functionOwners = new IdentityHashMap<>();
        private final IdentityHashMap<Ast.ClassDecl, String> classOwners = new IdentityHashMap<>();
        private final IdentityHashMap<Ast.ClassDecl, String> localClassOwners = new IdentityHashMap<>();
        private final IdentityHashMap<Ast.ModuleDecl, String> singletonSchemas = new IdentityHashMap<>();
        private final Set<String> ambiguousFunctions = new LinkedHashSet<>();
        private final Set<String> ambiguousClasses = new LinkedHashSet<>();
        private final IdentityHashMap<Object, Boolean> copyPath = new IdentityHashMap<>();

        private Evaluator(
                Ast.Program program,
                OresContext context,
                String codeUnitId,
                String codeUnitDigest) {
            this.program = program;
            this.context = context;
            this.codeUnitId = codeUnitId;
            this.codeUnitDigest = codeUnitDigest;
            indexDeclarations();
        }

        private void indexDeclarations() {
            for (Ast.ModuleDecl module : program.modules()) {
                modules.put(module.name(), module);
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) {
                        index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                        functionOwners.put(fn, module.name());
                    } else if (decl instanceof Ast.ClassDecl klass) {
                        index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                        classOwners.put(klass, module.name());
                    }
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

        private Object execute(Object[] arguments) {
            Ast.ModuleDecl root = modules.get(dev.oreslang.parser.Parser.ROOT_MODULE);
            if (root != null && !root.singleton()) actorModuleState(root);

            Ast.FunctionDecl main = findFunction("main");
            if (main == null) return null;
            Object result = callFunction(main, List.of(arguments));
            if (result instanceof CompletionStage<?> stage) return stage.toCompletableFuture().join();
            return result;
        }

        private Object callFunction(Ast.FunctionDecl fn, List<?> args) {
            Ast.ModuleDecl owner = ownerModule(fn);
            if (owner != null && owner.singleton()) return callSingleton(owner, fn, normalizeArgs(fn, args));
            return callFunctionDirect(fn, normalizeArgs(fn, args));
        }

        private List<?> normalizeArgs(Ast.FunctionDecl fn, List<?> args) {
            if (args.size() != fn.parameters().size()) {
                if (fn.parameters().isEmpty() && args.size() == 1 && args.getFirst() instanceof Object[] array && array.length == 0) {
                    return List.of();
                }
                throw new IllegalArgumentException("function " + fn.name() + " expects " + fn.parameters().size() + " arguments, got " + args.size());
            }
            return args;
        }

        private Object callFunctionDirect(Ast.FunctionDecl fn, List<?> args) {
            Ast.ModuleDecl owner = ownerModule(fn);
            Env moduleState = owner == null || owner.singleton() ? null : actorModuleState(owner);
            Env env = new Env(moduleState);
            return executeFunctionBody(fn, args, env);
        }

        private Env actorModuleState(Ast.ModuleDecl module) {
            if (module.singleton()) throw new IllegalArgumentException("singleton modules use process-owned state");

            ActorModuleStateKey actorKey = new ActorModuleStateKey(
                    codeUnitId,
                    program.namespace() == null ? "<default>" : program.namespace(),
                    module.name(),
                    codeUnitDigest);
            Env actorState = context.actors().currentActorLocal(
                    actorKey,
                    () -> initializeOrdinaryModuleState(module));
            if (actorState != null) return actorState;

            // Main/non-actor evaluation persists for the Graal context
            // lifetime, even if the same call target is invoked repeatedly.
            return context.contextLocal(actorKey, () -> initializeOrdinaryModuleState(module));
        }

        private Env initializeOrdinaryModuleState(Ast.ModuleDecl module) {
            Env state = new Env(null);
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FieldDecl field)) continue;
                if (field.initializer() == null) {
                    throw new IllegalArgumentException("module field has no initializer: " + module.name() + "." + field.name());
                }
                state.define(field.name(), eval(field.initializer(), state), field.bindingKind());
            }
            Ast.InitDecl init = findInit(module);
            if (init != null) executeInitializer(init, state);
            return state;
        }

        private Ast.InitDecl findInit(Ast.ModuleDecl module) {
            Ast.InitDecl found = null;
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.InitDecl init)) continue;
                if (found != null) throw new IllegalStateException("multiple init routines survived static checking in " + module.name());
                found = init;
            }
            return found;
        }

        private void executeInitializer(Ast.InitDecl init, Env state) {
            try {
                executeBlock(init.body(), state);
            } catch (ReturnSignal signal) {
                if (signal.value != null) throw new IllegalStateException("init routine returned a value after static checking");
            }
        }

        private Object callSingletonFunction(SingletonState state, Ast.FunctionDecl fn, List<?> args) {
            Ast.ModuleDecl owner = ownerModule(fn);
            if (!state.key.equals(singletonKey(owner))) {
                throw new IllegalStateException("singleton function dispatched to wrong actor state");
            }
            String currentSchema = singletonSchema(owner);
            if (!state.schema.equals(currentSchema)) {
                throw new IllegalStateException("singleton module state schema changed for " + owner.name()
                        + "; process-lifetime state cannot be reinterpreted without an explicit migration");
            }
            authorizeSingletonCodeGeneration(state, owner);
            Env env = new Env(state.fields, state);
            return executeFunctionBody(fn, args, env);
        }

        private Object executeFunctionBody(Ast.FunctionDecl fn, List<?> args, Env env) {
            if (env.singletonState != null) ProcessSingletonRegistry.checkExecutionBudget();
            if (args.size() != fn.parameters().size()) {
                throw new IllegalArgumentException("function " + fn.name() + " expects " + fn.parameters().size() + " arguments, got " + args.size());
            }
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }

            Ast.ModuleDecl owner = ownerModule(fn);
            predeclareLocalTypes(fn.body(), env, owner == null ? null : owner.name());
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return coerceDeclaredAggregate(fn.returnType(), signal.value, env);
            }
        }

        private CompletionStage<Object> callSingleton(Ast.ModuleDecl module, Ast.FunctionDecl fn, List<?> args) {
            ProcessSingletonRegistry.Handle<SingletonState> handle = singletonHandle(module);
            return handle.call(
                    args,
                    context.isolatePolicy().maxMailboxMessages(),
                    context.isolatePolicy().maxWallTime(),
                    (state, frozenArgs) -> transactionalSingletonCall(
                            state,
                            working -> callSingletonFunction(working, fn, frozenArgs)));
        }

        private Object transactionalSingletonCall(
                SingletonState canonical,
                SingletonWork work) {
            SingletonState working = canonical.transactionalCopy();
            Object result = work.apply(working);
            // The commit itself is the linearization point. If the caller's
            // deadline elapsed while executing, discard the working state.
            ProcessSingletonRegistry.checkExecutionBudget();
            working.validateStorageGraph();
            canonical.commitFrom(working);
            return result;
        }

        private ProcessSingletonRegistry.Handle<SingletonState> singletonHandle(Ast.ModuleDecl module) {
            ProcessSingletonRegistry.requireBackendFor(context.graalIsolated());
            String key = singletonKey(module);
            // Do not cache handles per evaluator. A retryable initialization
            // failure replaces the registry cell; every access must resolve the
            // current process cell rather than pinning a stale failed handle.
            return ProcessSingletonRegistry.getOrCreate(key, () -> initializeSingleton(module));
        }

        private SingletonState initializeSingleton(Ast.ModuleDecl module) {
            SingletonState state = new SingletonState(
                    singletonKey(module),
                    singletonSchema(module),
                    singletonCodeDigest(module),
                    context.codeGeneration());
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FieldDecl field)) continue;
                if (field.initializer() == null) {
                    throw new IllegalArgumentException("singleton field has no initializer: " + module.name() + "." + field.name());
                }
                Object value = eval(field.initializer(), state.fields);
                state.fields.define(field.name(), value, field.bindingKind());
            }
            Ast.InitDecl init = findInit(module);
            if (init != null) executeInitializer(init, state.fields);
            return state;
        }

        private String singletonKey(Ast.ModuleDecl module) {
            String namespace = program.namespace();
            String namespaceIdentity = namespace == null || namespace.isBlank() ? "<default>" : namespace;
            return "ores:" + codeUnitId + ":" + namespaceIdentity + ":" + module.name();
        }

        private String singletonSchema(Ast.ModuleDecl module) {
            return singletonSchemas.computeIfAbsent(module, ignored -> {
                StringBuilder schema = new StringBuilder();
                for (Ast.Decl decl : module.declarations()) {
                    if (!(decl instanceof Ast.FieldDecl field)) continue;
                    schema.append(field.name())
                            .append(':').append(field.bindingKind())
                            .append(':').append(field.type());
                    Ast.ClassDecl stateClass = field.type() == null ? null : findClass(field.type().name());
                    if (stateClass != null) {
                        schema.append(":class{");
                        for (Ast.FieldDecl classField : effectiveFields(stateClass, new LinkedHashSet<>())) {
                            schema.append(classField.name())
                                    .append(':').append(classField.bindingKind())
                                    .append(':').append(classField.type())
                                    .append(';');
                        }
                        schema.append('}');
                    }
                    schema.append(';');
                }
                return schema.toString();
            });
        }

        private String singletonCodeDigest(Ast.ModuleDecl module) {
            // The whole checked code unit is the behavior provenance boundary:
            // singleton code may call helpers declared outside the module.
            return codeUnitDigest;
        }

        private void authorizeSingletonCodeGeneration(SingletonState state, Ast.ModuleDecl module) {
            String digest = singletonCodeDigest(module);
            long generation = context.codeGeneration();

            if (state.activeCodeDigest.equals(digest)) {
                if (generation > state.activeGeneration) state.activeGeneration = generation;
                return;
            }

            context.requireCapability(IsolatePolicy.Capability.HOT_CODE_LOAD,
                    "singleton hot reload for " + module.name());

            if (state.activeGeneration > 0) {
                if (generation == 0) {
                    throw new SecurityException("unversioned context cannot replace managed singleton code for "
                            + module.name());
                }
                if (generation < state.activeGeneration) {
                    throw new SecurityException("stale singleton generation " + generation
                            + " cannot replace active generation " + state.activeGeneration
                            + " for " + module.name());
                }
                if (generation == state.activeGeneration) {
                    throw new SecurityException("conflicting singleton code digest at generation " + generation
                            + " for " + module.name());
                }
            }

            state.activeCodeDigest = digest;
            if (generation > 0) state.activeGeneration = generation;
        }

        private Ast.ModuleDecl ownerModule(Ast.FunctionDecl fn) {
            String owner = functionOwners.get(fn);
            return owner == null ? null : modules.get(owner);
        }

        private Ast.ModuleDecl ownerModule(Ast.ClassDecl klass) {
            String owner = classOwners.get(klass);
            if (owner == null) owner = localClassOwners.get(klass);
            return owner == null ? null : modules.get(owner);
        }

        private Object callMethod(
                OresObject receiver,
                Ast.ClassDecl dispatchClass,
                Ast.MethodDecl method,
                List<?> args,
                SingletonState singletonState) {
            if (singletonState != null) ProcessSingletonRegistry.checkExecutionBudget();
            if (args.size() != method.parameters().size()) throw new IllegalArgumentException("method " + method.name() + " arity mismatch");
            Env lexical = classLexicalModuleState(dispatchClass, singletonState);
            Env env = new Env(lexical, singletonState);
            if (!method.isStatic()) env.define("self", receiver, Ast.BindingKind.VAL);
            for (int i = 0; i < method.parameters().size(); i++) {
                Ast.Param param = method.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            Ast.ModuleDecl owner = ownerModule(receiver.klass);
            predeclareLocalTypes(method.body(), env, owner == null ? null : owner.name());
            try {
                executeBlock(method.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return coerceDeclaredAggregate(method.returnType(), signal.value, env);
            }
        }

        private void executeBlock(List<Ast.Stmt> statements, Env parent) {
            Env env = new Env(parent);
            predeclareLocalTypes(statements, env, null);
            ArrayDeque<Ast.Expr> deferred = new ArrayDeque<>();
            try {
                for (Ast.Stmt stmt : statements) executeStatement(stmt, env, deferred);
            } finally {
                while (!deferred.isEmpty()) eval(deferred.pop(), env);
            }
        }

        private void executeStatement(Ast.Stmt stmt, Env env, ArrayDeque<Ast.Expr> deferred) {
            if (stmt instanceof Ast.TypeDeclStmt) return;
            if (stmt instanceof Ast.BindingStmt binding) {
                if (binding.initializer() instanceof Ast.LambdaExpr) {
                    env.reserve(binding.name(), binding.kind());
                    Object value = coerceDeclaredAggregate(
                            binding.declaredType(),
                            eval(binding.initializer(), env),
                            env);
                    env.initialize(binding.name(), value);
                } else {
                    Object value = coerceDeclaredAggregate(
                            binding.declaredType(),
                            eval(binding.initializer(), env),
                            env);
                    env.define(binding.name(), value, binding.kind());
                }
                return;
            }
            if (stmt instanceof Ast.DestructureStmt destructure) {
                Object value = eval(destructure.initializer(), env);
                List<?> items = asSequence(value);
                if (items.size() != destructure.bindings().size()) throw new IllegalArgumentException("destructure arity mismatch");
                for (int i = 0; i < items.size(); i++) {
                    Ast.DestructureBinding binding = destructure.bindings().get(i);
                    env.define(binding.name(), items.get(i), binding.kind());
                }
                return;
            }
            if (stmt instanceof Ast.ReturnStmt ret) throw new ReturnSignal(ret.value() == null ? null : eval(ret.value(), env));
            if (stmt instanceof Ast.ExprStmt expression) { eval(expression.expression(), env); return; }
            if (stmt instanceof Ast.DeferStmt defer) { deferred.push(defer.expression()); return; }
            if (stmt instanceof Ast.IfStmt ifStmt) {
                for (Ast.IfBranch branch : ifStmt.branches()) {
                    if (truth(eval(branch.condition(), env))) { executeBlock(branch.body(), env); return; }
                }
                executeBlock(ifStmt.elseBody(), env);
                return;
            }
            if (stmt instanceof Ast.TryStmt tried) {
                try { executeBlock(tried.body(), env); }
                catch (ReturnSignal signal) { throw signal; }
                catch (RuntimeException failure) {
                    Env catchEnv = new Env(env);
                    catchEnv.define(tried.errorName(), failure, Ast.BindingKind.VAL);
                    executeBlock(tried.catchBody(), catchEnv);
                } finally { executeBlock(tried.finallyBody(), env); }
                return;
            }
            if (stmt instanceof Ast.ForOfStmt loop) {
                Object iterable = eval(loop.iterable(), env);
                for (Object item : iterableValues(iterable, env.singletonState)) {
                    context.schedulerSafepoint();
                    Env iteration = new Env(env);
                    iteration.define(loop.bindingName(), item, loop.bindingKind());
                    executeBlock(loop.body(), iteration);
                }
                return;
            }
            if (stmt instanceof Ast.ForStmt loop) {
                Env loopEnv = new Env(env);
                if (loop.initializer() != null) executeStatement(loop.initializer(), loopEnv, new ArrayDeque<>());
                while (loop.condition() == null || truth(eval(loop.condition(), loopEnv))) {
                    context.schedulerSafepoint();
                    executeBlock(loop.body(), loopEnv);
                    if (loop.update() != null) eval(loop.update(), loopEnv);
                }
            }
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
                if (name.name().equals("print")) return (Invokable) args -> {
                    context.requireCapability(IsolatePolicy.Capability.STDOUT, "print");
                    requireOne(args, "print"); context.output().print(display(args.getFirst())); context.output().flush(); return null;
                };
                if (name.name().equals("Some")) return (Invokable) args -> {
                    requireOne(args, "Some");
                    return new OptionValue(true, args.getFirst());
                };
                if (name.name().equals("None")) return new OptionValue(false, null);
                Ast.ModuleDecl module = modules.get(name.name());
                if (module != null) {
                    SingletonState localState = env.singletonState != null
                            && env.singletonState.key.equals(singletonKey(module))
                            ? env.singletonState
                            : null;
                    return new ModuleFacade(module, localState);
                }
                Ast.ClassDecl klass = env.lookupType(name.name());
                if (klass == null) klass = findClass(name.name());
                if (klass != null) {
                    Ast.ModuleDecl owner = ownerModule(klass);
                    if (owner != null && owner.singleton()
                            && (env.singletonState == null
                            || !env.singletonState.key.equals(singletonKey(owner)))) {
                        throw new IllegalArgumentException("class '" + klass.name()
                                + "' is actor-private inside singleton module '" + owner.name() + "'");
                    }
                    return new ClassFacade(klass, env.singletonState);
                }
                Ast.FunctionDecl fn = findFunction(name.name());
                if (fn != null) {
                    Ast.ModuleDecl owner = ownerModule(fn);
                    if (owner != null && owner.singleton()) {
                        if (env.singletonState != null && env.singletonState.key.equals(singletonKey(owner))) {
                            return (Invokable) args -> callSingletonFunction(env.singletonState, fn, normalizeArgs(fn, args));
                        }
                        if (fn.visibility() != Ast.Visibility.PUBLIC) {
                            throw new IllegalArgumentException("private singleton callable '" + owner.name() + "."
                                    + fn.name() + "' is actor-private");
                        }
                        return (Invokable) args -> callSingleton(owner, fn, normalizeArgs(fn, args));
                    }
                    return (Invokable) args -> callFunctionDirect(fn, normalizeArgs(fn, args));
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
                    if (receiver instanceof OresObject object) {
                        Ast.FieldDecl field = runtimeField(object.klass, target.member());
                        if (field == null || !object.fields.containsKey(target.member())) {
                            throw new IllegalArgumentException("unknown field " + target.member());
                        }
                        if (field.bindingKind() != Ast.BindingKind.LET) {
                            throw new IllegalArgumentException("field '" + object.klass.name() + "."
                                    + target.member() + "' is immutable");
                        }
                        Object previous = object.fields.get(target.member());
                        object.fields.put(target.member(), value);
                        try {
                            if (env.singletonState != null) env.singletonState.validateStorageGraph();
                        } catch (RuntimeException | Error failure) {
                            object.fields.put(target.member(), previous);
                            throw failure;
                        }
                        return value;
                    }
                    throw new IllegalArgumentException("member assignment requires a class instance");
                }
                if (assignment.target() instanceof Ast.IndexExpr target) {
                    Object receiver = eval(target.receiver(), env);
                    Object index = eval(target.index(), env);
                    if (!(index instanceof Number number)) throw new IllegalArgumentException("index must be an integer");
                    int i = Math.toIntExact(number.longValue());
                    if (receiver instanceof List<?> raw) {
                        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) raw;
                        Object previous = list.set(i, value);
                        try {
                            if (env.singletonState != null) env.singletonState.validateStorageGraph();
                        } catch (RuntimeException | Error failure) {
                            list.set(i, previous);
                            throw failure;
                        }
                        return value;
                    }
                    throw new IllegalArgumentException("indexed assignment requires a mutable array/list");
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
                    case "!" -> !truth(value); case "+" -> value; case "-" -> negate(value);
                    default -> throw new IllegalArgumentException("unsupported unary operator " + unary.operator());
                };
            }
            if (expr instanceof Ast.BinaryExpr binary) {
                if (binary.operator().equals(",")) return truth(eval(binary.left(), env)) && truth(eval(binary.right(), env));
                if (binary.operator().equals("|")) return truth(eval(binary.left(), env)) || truth(eval(binary.right(), env));
                return binary(binary.operator(), eval(binary.left(), env), eval(binary.right(), env));
            }
            if (expr instanceof Ast.CallExpr call) {
                if (call.callee() instanceof Ast.NameExpr intrinsic
                        && (intrinsic.name().equals("borrow")
                        || intrinsic.name().equals("copy")
                        || intrinsic.name().equals("take")
                        || intrinsic.name().equals("share"))) {
                    if (call.arguments().size() != 1) {
                        throw new IllegalArgumentException(intrinsic.name() + " expects exactly one value");
                    }
                    Object value = eval(call.arguments().getFirst(), env);
                    return switch (intrinsic.name()) {
                        case "borrow", "take" -> value;
                        case "copy" -> copyValue(value, env.singletonState);
                        case "share" -> freezeGuestValue(copyValue(value, env.singletonState),
                                new IdentityHashMap<>());
                        default -> throw new IllegalStateException("unknown ownership intrinsic " + intrinsic.name());
                    };
                }
                if (call.callee() instanceof Ast.MemberExpr methodCall) {
                    Object receiver = eval(methodCall.receiver(), env);
                    List<Object> args = call.arguments().stream().map(arg -> eval(arg, env)).toList();
                    if (receiver instanceof OresObject object) {
                        return invokeMethod(object, methodCall.member(), args, env.singletonState);
                    }
                    if (receiver instanceof SingletonObjectProxy proxy) {
                        return invokeSingletonProxy(proxy, methodCall.member(), args);
                    }
                    if (receiver instanceof ClassFacade klass) {
                        return invokeStaticFunction(klass.klass(), methodCall.member(), args, klass.localState());
                    }
                    if (receiver instanceof ModuleFacade moduleFacade) {
                        Ast.FunctionDecl function = publicModuleFunction(
                                moduleFacade.module(), methodCall.member(), args.size());
                        if (function != null) {
                            if (moduleFacade.module().singleton()) {
                                if (moduleFacade.localState() != null) {
                                    return callSingletonFunction(moduleFacade.localState(), function, normalizeArgs(function, args));
                                }
                                return callSingleton(moduleFacade.module(), function, normalizeArgs(function, args));
                            }
                            return callFunctionDirect(function, normalizeArgs(function, args));
                        }
                    }
                    Object callee = member(receiver, methodCall.member(), env.singletonState);
                    if (!(callee instanceof Invokable invokable)) throw new IllegalArgumentException("value is not callable: " + callee);
                    return invokable.call(args);
                }
                Object callee = eval(call.callee(), env);
                List<Object> args = call.arguments().stream().map(arg -> eval(arg, env)).toList();
                if (!(callee instanceof Invokable invokable)) throw new IllegalArgumentException("value is not callable: " + callee);
                return invokable.call(args);
            }
            if (expr instanceof Ast.MemberExpr member) return member(eval(member.receiver(), env), member.member(), env.singletonState);
            if (expr instanceof Ast.IndexExpr indexed) {
                Object receiver = eval(indexed.receiver(), env);
                Object index = eval(indexed.index(), env);
                if (!(index instanceof Number number)) throw new IllegalArgumentException("index must be an integer");
                int i = Math.toIntExact(number.longValue());
                if (receiver instanceof List<?> list) return list.get(i);
                if (receiver instanceof Object[] array) return array[i];
                throw new IllegalArgumentException("value is not indexable: " + receiver);
            }
            if (expr instanceof Ast.NewExpr created) {
                Ast.ClassDecl klass = runtimeClass(created.type(), env);
                if (klass == null) throw new IllegalArgumentException("unknown class " + created.type().name());
                if (klass.isStruct()) throw new IllegalArgumentException("struct " + klass.name() + " is a value type; use " + klass.name() + " { ... }");
                Ast.ModuleDecl owner = ownerModule(klass);
                if (owner != null && owner.singleton()
                        && (env.singletonState == null
                        || !env.singletonState.key.equals(singletonKey(owner)))) {
                    throw new IllegalArgumentException("class '" + klass.name()
                            + "' is actor-private inside singleton module '" + owner.name() + "'");
                }
                List<Object> args = created.arguments().stream().map(arg -> eval(arg, env)).toList();
                List<Ast.FieldDecl> classFields = effectiveFields(klass, new LinkedHashSet<>());
                long constructorFieldCount = classFields.stream().filter(field -> !field.composed()).count();
                if (args.size() > constructorFieldCount) throw new IllegalArgumentException("too many constructor arguments for " + klass.name());
                LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                int argumentIndex = 0;
                for (Ast.FieldDecl field : classFields) {
                    Object value;
                    if (!field.composed() && argumentIndex < args.size()) value = args.get(argumentIndex++);
                    else if (field.initializer() != null) value = eval(field.initializer(), env);
                    else throw new IllegalArgumentException("missing constructor field " + klass.name() + "." + field.name());
                    fields.put(field.name(), value);
                }
                return new OresObject(klass, fields);
            }
            if (expr instanceof Ast.StructInitExpr created) {
                if (created.anonymous()) {
                    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                    for (Ast.ObjectField field : created.fields()) {
                        if (fields.putIfAbsent(field.name(), eval(field.value(), env)) != null) {
                            throw new IllegalArgumentException("duplicate anonymous struct field " + field.name());
                        }
                    }
                    return Map.copyOf(fields);
                }

                Ast.ClassDecl struct = runtimeClass(created.type(), env);
                if (struct == null || !struct.isStruct()) {
                    throw new IllegalArgumentException(created.type().name() + " is not a struct type");
                }

                LinkedHashMap<String, Ast.Expr> supplied = new LinkedHashMap<>();
                Set<String> callerFieldNames = new LinkedHashSet<>();
                for (Ast.FieldDecl field : struct.fields()) {
                    if (!field.composed()) callerFieldNames.add(field.name());
                }
                for (Ast.ObjectField field : created.fields()) {
                    if (!callerFieldNames.contains(field.name())) {
                        throw new IllegalArgumentException("unknown or trait-owned struct field " + struct.name() + "." + field.name());
                    }
                    if (supplied.putIfAbsent(field.name(), field.value()) != null) {
                        throw new IllegalArgumentException("duplicate struct field " + struct.name() + "." + field.name());
                    }
                }

                LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                for (Ast.FieldDecl field : struct.fields()) {
                    if (field.composed()) {
                        if (field.initializer() == null) {
                            throw new IllegalArgumentException("composed trait state has no initializer " + struct.name() + "." + field.name());
                        }
                        fields.put(field.name(), eval(field.initializer(), env));
                        continue;
                    }
                    Ast.Expr value = supplied.get(field.name());
                    if (value != null) fields.put(field.name(), eval(value, env));
                    else if (field.initializer() != null) fields.put(field.name(), eval(field.initializer(), env));
                    else throw new IllegalArgumentException("missing struct field " + struct.name() + "." + field.name());
                }
                return new OresObject(struct, fields);
            }
            if (expr instanceof Ast.AwaitExpr awaited) {
                Object value = eval(awaited.expression(), env);
                if (value instanceof CompletionStage<?> stage) return stage.toCompletableFuture().join();
                return value;
            }
            if (expr instanceof Ast.ListExpr list) {
                ArrayList<Object> result = new ArrayList<>(list.elements().size());
                for (Ast.Expr item : list.elements()) result.add(eval(item, env));
                return result;
            }
            if (expr instanceof Ast.TupleExpr tuple) return tuple.elements().stream().map(item -> eval(item, env)).toList();
            if (expr instanceof Ast.ObjectExpr object) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                for (Ast.ObjectField field : object.fields()) {
                    if (result.putIfAbsent(field.name(), eval(field.value(), env)) != null) throw new IllegalArgumentException("duplicate obj field " + field.name());
                }
                return Map.copyOf(result);
            }
            if (expr instanceof Ast.LambdaExpr lambda) {
                Env captured = env.snapshot();
                return (Invokable) args -> {
                    if (args.size() != lambda.parameters().size()) throw new IllegalArgumentException("lambda arity mismatch");
                    Env local = new Env(captured);
                    for (int i = 0; i < lambda.parameters().size(); i++) {
                        Ast.Param param = lambda.parameters().get(i);
                        local.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
                    }
                    if (lambda.expressionBody() != null) return eval(lambda.expressionBody(), local);
                    try { executeBlock(lambda.blockBody(), local); return null; }
                    catch (ReturnSignal signal) { return signal.value; }
                };
            }
            throw new IllegalArgumentException("unsupported expression " + expr);
        }

        private void predeclareLocalTypes(List<Ast.Stmt> statements, Env env, String moduleName) {
            for (Ast.Stmt stmt : statements) {
                if (!(stmt instanceof Ast.TypeDeclStmt local)) continue;
                if (local.declaration() instanceof Ast.ClassDecl struct) {
                    env.defineType(struct.name(), struct);
                    if (moduleName != null) localClassOwners.putIfAbsent(struct, moduleName);
                }
            }
        }

        private Ast.ClassDecl runtimeClass(Ast.TypeRef type, Env env) {
            if (type == null) return null;
            Ast.ClassDecl local = env.lookupType(type.name());
            return local != null ? local : findClass(type.name());
        }

        private Object coerceDeclaredAggregate(Ast.TypeRef declaredType, Object value, Env env) {
            if (declaredType == null || !(value instanceof Map<?, ?> map)) return value;
            Ast.ClassDecl struct = runtimeClass(declaredType, env);
            if (struct == null || !struct.isStruct()) return value;

            Set<String> callerFieldNames = new LinkedHashSet<>();
            for (Ast.FieldDecl field : struct.fields()) if (!field.composed()) callerFieldNames.add(field.name());
            for (Object key : map.keySet()) {
                if (!(key instanceof String name) || !callerFieldNames.contains(name)) {
                    throw new IllegalArgumentException("object value has unknown or trait-owned field for struct "
                            + struct.name() + ": " + key);
                }
            }

            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            for (Ast.FieldDecl field : struct.fields()) {
                if (field.composed()) {
                    if (field.initializer() == null) {
                        throw new IllegalArgumentException("composed trait state has no initializer " + struct.name() + "." + field.name());
                    }
                    fields.put(field.name(), eval(field.initializer(), env));
                } else if (map.containsKey(field.name())) {
                    fields.put(field.name(), map.get(field.name()));
                } else if (field.initializer() != null) {
                    fields.put(field.name(), eval(field.initializer(), env));
                } else {
                    throw new IllegalArgumentException("object value is missing required struct field "
                            + struct.name() + "." + field.name());
                }
            }
            return new OresObject(struct, fields);
        }

        private Object member(Object receiver, String name, SingletonState singletonState) {
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
                    default -> throw new IllegalArgumentException("unknown process member " + name);
                };
            }
            if (receiver instanceof ModuleFacade namespace) return moduleMember(namespace, name);
            if (receiver instanceof ClassFacade klass) {
                List<Ast.MethodDecl> functions = findStaticFunctionsByName(klass.klass(), name, new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    return (Invokable) args -> callStaticFunction(klass.klass(), fn, args, klass.localState());
                }
                if (functions.size() > 1) throw new IllegalArgumentException("overloaded static function " + klass.klass().name() + "." + name + " must be called so arity can select it");
                throw new IllegalArgumentException("unknown static member " + klass.klass().name() + "." + name);
            }
            if (receiver instanceof SingletonObjectProxy proxy) {
                throw new IllegalArgumentException("singleton object proxy members must be invoked directly and awaited: "
                        + proxy.module().name() + "." + proxy.fieldName() + "." + name + "(...)");
            }
            if (receiver instanceof OresObject object) {
                if (object.fields.containsKey(name)) return object.fields.get(name);
                return new BoundMethod(object, name, singletonState);
            }
            if (receiver instanceof Map<?, ?> map) {
                if (!map.containsKey(name)) throw new IllegalArgumentException("unknown obj member " + name);
                return map.get(name);
            }
            throw new IllegalArgumentException("cannot access member '" + name + "' on " + receiver);
        }

        private Object invokeMethod(OresObject receiver, String name, List<Object> args, SingletonState singletonState) {
            Ast.ClassDecl dispatchClass = runtimeDispatchClass(receiver, singletonState);
            Ast.MethodDecl method = findMethod(dispatchClass, name, args.size(), new LinkedHashSet<>());
            if (method == null) throw new IllegalArgumentException("no method " + dispatchClass.name() + "." + name + " with arity " + args.size());
            return callMethod(receiver, dispatchClass, method, args, singletonState);
        }

        private Ast.ClassDecl runtimeDispatchClass(OresObject receiver, SingletonState singletonState) {
            if (singletonState == null) return receiver.klass;
            Ast.ClassDecl current = findClass(receiver.klass.name());
            if (current == null) {
                throw new IllegalStateException("singleton-owned class '" + receiver.klass.name()
                        + "' no longer exists in the active code generation");
            }
            return current;
        }

        private CompletionStage<Object> invokeSingletonProxy(
                SingletonObjectProxy proxy,
                String methodName,
                List<Object> args) {
            ProcessSingletonRegistry.Handle<SingletonState> handle = singletonHandle(proxy.module());
            return handle.call(
                    args,
                    context.isolatePolicy().maxMailboxMessages(),
                    context.isolatePolicy().maxWallTime(),
                    (state, frozenArgs) -> transactionalSingletonCall(state, working -> {
                        String currentSchema = singletonSchema(proxy.module());
                        if (!working.schema.equals(currentSchema)) {
                            throw new IllegalStateException("singleton module state schema changed for "
                                    + proxy.module().name()
                                    + "; process-lifetime state cannot be reinterpreted without an explicit migration");
                        }
                        authorizeSingletonCodeGeneration(working, proxy.module());
                        Object value = working.fields.lookup(proxy.fieldName());
                        if (!(value instanceof OresObject object)
                                || !object.klass.name().equals(proxy.klass().name())) {
                            throw new IllegalStateException("singleton object proxy target changed for "
                                    + proxy.module().name() + "." + proxy.fieldName());
                        }

                        // The object state survives hot reload, but behavior is
                        // selected from the currently authorized code generation.
                        Ast.MethodDecl method = findMethod(proxy.klass(), methodName, frozenArgs.size(), new LinkedHashSet<>());
                        if (method == null || method.visibility() != Ast.Visibility.PUBLIC) {
                            throw new IllegalArgumentException("no public singleton proxy method "
                                    + proxy.klass().name() + "." + methodName + " with arity " + frozenArgs.size());
                        }
                        return callMethod(object, proxy.klass(), method, frozenArgs, working);
                    }));
        }

        private Env classLexicalModuleState(Ast.ClassDecl klass, SingletonState singletonState) {
            Ast.ModuleDecl owner = ownerModule(klass);
            if (owner == null) return null;
            if (owner.singleton()) {
                if (singletonState == null || !singletonState.key.equals(singletonKey(owner))) {
                    throw new IllegalArgumentException("class '" + klass.name()
                            + "' is actor-private inside singleton module '" + owner.name() + "'");
                }
                return singletonState.fields;
            }
            // A class used as process-singleton state but declared outside that
            // singleton must not capture the caller actor's module state.
            if (singletonState != null) return null;
            return actorModuleState(owner);
        }

        private Object invokeStaticFunction(Ast.ClassDecl klass, String name, List<Object> args, SingletonState singletonState) {
            Ast.MethodDecl fn = findStaticFunction(klass, name, args.size(), new LinkedHashSet<>());
            if (fn == null) throw new IllegalArgumentException("no static function " + klass.name() + "." + name + " with arity " + args.size());
            return callStaticFunction(klass, fn, args, singletonState);
        }

        private Object callStaticFunction(Ast.ClassDecl klass, Ast.MethodDecl fn, List<?> args, SingletonState singletonState) {
            if (singletonState != null) ProcessSingletonRegistry.checkExecutionBudget();
            if (!fn.isStatic()) throw new IllegalArgumentException("not a static class function: " + klass.name() + "." + fn.name());
            if (args.size() != fn.parameters().size()) throw new IllegalArgumentException("static function " + fn.name() + " arity mismatch");
            Env lexical = classLexicalModuleState(klass, singletonState);
            Env env = new Env(lexical, singletonState);
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            Ast.ModuleDecl owner = ownerModule(klass);
            predeclareLocalTypes(fn.body(), env, owner == null ? null : owner.name());
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return coerceDeclaredAggregate(fn.returnType(), signal.value, env);
            }
        }

        /**
         * Go-style method value: one shared method definition per class plus a
         * tiny (receiver, method-name) pair only when a method is extracted as
         * a first-class callback. Direct receiver.method(...) calls allocate no
         * bound-method object.
         */
        private final class BoundMethod implements Invokable {
            private final OresObject receiver;
            private final String methodName;
            private final SingletonState singletonState;

            private BoundMethod(OresObject receiver, String methodName, SingletonState singletonState) {
                this.receiver = receiver;
                this.methodName = methodName;
                this.singletonState = singletonState;
            }

            @Override public Object call(List<Object> arguments) {
                return invokeMethod(receiver, methodName, arguments, singletonState);
            }
        }

        private Ast.FunctionDecl publicModuleFunction(Ast.ModuleDecl module, String name, int arity) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn
                        && fn.visibility() == Ast.Visibility.PUBLIC
                        && fn.name().equals(name)
                        && fn.parameters().size() == arity) {
                    return fn;
                }
            }
            return null;
        }

        private Ast.FieldDecl runtimeField(Ast.ClassDecl klass, String name) {
            for (Ast.FieldDecl field : effectiveFields(klass, new LinkedHashSet<>())) {
                if (field.name().equals(name)) return field;
            }
            return null;
        }

        private Object moduleMember(ModuleFacade facade, String name) {
            Ast.ModuleDecl module = facade.module();
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass && klass.name().equals(name)) {
                    if (module.singleton() && facade.localState() == null) {
                        throw new IllegalArgumentException("classes inside singleton module '" + module.name()
                                + "' are actor-private and cannot be accessed through its external proxy");
                    }
                    return new ClassFacade(klass, facade.localState());
                }
                if (decl instanceof Ast.FunctionDecl fn && fn.name().equals(name) && fn.visibility() == Ast.Visibility.PUBLIC) {
                    if (module.singleton()) {
                        if (facade.localState() != null) {
                            return (Invokable) args -> callSingletonFunction(
                                    facade.localState(), fn, normalizeArgs(fn, args));
                        }
                        throw new IllegalArgumentException("singleton service function values cannot be extracted; call and await "
                                + module.name() + "." + name + "(...) directly");
                    }
                    return (Invokable) args -> callFunctionDirect(fn, normalizeArgs(fn, args));
                }
                if (decl instanceof Ast.FieldDecl field && field.name().equals(name) && field.visibility() == Ast.Visibility.PUBLIC) {
                    if (module.singleton()) {
                        if (facade.localState() != null) {
                            Object local = facade.localState().fields.lookup(name);
                            if (local == Env.MISSING) throw new IllegalStateException("missing singleton field " + module.name() + "." + name);
                            return local;
                        }
                        Ast.ClassDecl klass = field.type() == null ? null : findClass(field.type().name());
                        if (klass == null) {
                            throw new IllegalArgumentException("singleton module exports only class-instance proxy fields: "
                                    + module.name() + "." + name);
                        }
                        return new SingletonObjectProxy(module, field.name(), klass);
                    }
                    Env state = actorModuleState(module);
                    Object value = state.lookup(name);
                    if (value == Env.MISSING) throw new IllegalStateException("missing actor-local module field " + module.name() + "." + name);
                    return value;
                }
            }
            throw new IllegalArgumentException("module '" + module.name() + "' does not export '" + name + "'");
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

        private Object copyValue(Object value, SingletonState singletonState) {
            if (value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean || value instanceof Character
                    || value instanceof Complex) {
                return value;
            }
            if (value instanceof OptionValue option) {
                return option.present()
                        ? new OptionValue(true, copyValue(option.value(), singletonState))
                        : option;
            }
            if (copyPath.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("copy does not support cyclic mutable graphs; define an acyclic copy boundary");
            }
            try {
                if (value instanceof OresObject object) {
                    if (object.klass.isStruct()) {
                        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> entry : object.fields.entrySet()) {
                            fields.put(entry.getKey(), copyValue(entry.getValue(), singletonState));
                        }
                        return new OresObject(object.klass, fields);
                    }

                    Ast.ClassDecl dispatchClass = runtimeDispatchClass(object, singletonState);
                    Ast.MethodDecl copyMethod = findMethod(dispatchClass, "copy", 0, new LinkedHashSet<>());
                    if (copyMethod == null || copyMethod.isStatic()
                            || copyMethod.visibility() != Ast.Visibility.PUBLIC
                            || copyMethod.isAbstract() || copyMethod.async()
                            || !copyMethod.genericParameters().isEmpty()
                            || (copyMethod.explicitReceiverType() != null
                            && copyMethod.explicitReceiverType().isBorrow()
                            && copyMethod.explicitReceiverType().mutableBorrow())) {
                        throw new IllegalArgumentException("class '" + object.klass.name()
                                + "' is not copyable: copy() must be concrete, synchronous, non-generic, "
                                + "public, instance-bound, and use an immutable receiver");
                    }
                    Object copied = callMethod(object, dispatchClass, copyMethod, List.of(), singletonState);
                    if (!(copied instanceof OresObject result) || result.klass != object.klass) {
                        throw new IllegalArgumentException("class '" + object.klass.name()
                                + "' copy() must return a fresh instance of the same concrete class");
                    }
                    assertNoMutableAliases(object, result);
                    return result;
                }
                if (value instanceof List<?> list) {
                    ArrayList<Object> copied = new ArrayList<>(list.size());
                    for (Object item : list) copied.add(copyValue(item, singletonState));
                    return copied;
                }
                if (value instanceof Map<?, ?> map) {
                    LinkedHashMap<Object, Object> copied = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        copied.put(copyValue(entry.getKey(), singletonState),
                                copyValue(entry.getValue(), singletonState));
                    }
                    return copied;
                }
                if (value instanceof Object[] array) {
                    Object[] copied = new Object[array.length];
                    for (int i = 0; i < array.length; i++) copied[i] = copyValue(array[i], singletonState);
                    return copied;
                }
                throw new IllegalArgumentException("value has no runtime copy semantics: " + value.getClass().getName());
            } finally {
                copyPath.remove(value);
            }
        }

        private Object freezeGuestValue(Object value, IdentityHashMap<Object, Boolean> path) {
            if (value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean || value instanceof Character
                    || value instanceof Complex) {
                return value;
            }
            if (value instanceof OptionValue option) {
                return option.present()
                        ? new OptionValue(true, freezeGuestValue(option.value(), path))
                        : option;
            }
            if (path.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("share does not support cyclic mutable graphs");
            }
            try {
                if (value instanceof OresObject object) {
                    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : object.fields.entrySet()) {
                        fields.put(entry.getKey(), freezeGuestValue(entry.getValue(), path));
                    }
                    return new OresObject(object.klass, java.util.Collections.unmodifiableMap(fields));
                }
                if (value instanceof List<?> list) {
                    ArrayList<Object> frozen = new ArrayList<>(list.size());
                    for (Object item : list) frozen.add(freezeGuestValue(item, path));
                    return java.util.Collections.unmodifiableList(frozen);
                }
                if (value instanceof Map<?, ?> map) {
                    LinkedHashMap<Object, Object> frozen = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        frozen.put(freezeGuestValue(entry.getKey(), path),
                                freezeGuestValue(entry.getValue(), path));
                    }
                    return java.util.Collections.unmodifiableMap(frozen);
                }
                if (value instanceof Object[] array) {
                    ArrayList<Object> frozen = new ArrayList<>(array.length);
                    for (Object item : array) frozen.add(freezeGuestValue(item, path));
                    return java.util.Collections.unmodifiableList(frozen);
                }
                return ActorRuntime.freeze(value);
            } finally {
                path.remove(value);
            }
        }

        private void assertNoMutableAliases(Object original, Object copied) {
            IdentityHashMap<Object, Boolean> originalGraph = new IdentityHashMap<>();
            collectMutableGraph(original, originalGraph, new IdentityHashMap<>());
            rejectMutableAliases(copied, originalGraph, new IdentityHashMap<>());
        }

        private void collectMutableGraph(
                Object value,
                IdentityHashMap<Object, Boolean> graph,
                IdentityHashMap<Object, Boolean> seen) {
            if (!isMutableComposite(value) || seen.put(value, Boolean.TRUE) != null) return;
            graph.put(value, Boolean.TRUE);
            if (value instanceof OresObject object) {
                for (Object field : object.fields.values()) collectMutableGraph(field, graph, seen);
            } else if (value instanceof List<?> list) {
                for (Object item : list) collectMutableGraph(item, graph, seen);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    collectMutableGraph(entry.getKey(), graph, seen);
                    collectMutableGraph(entry.getValue(), graph, seen);
                }
            } else if (value instanceof Object[] array) {
                for (Object item : array) collectMutableGraph(item, graph, seen);
            }
        }

        private void rejectMutableAliases(
                Object value,
                IdentityHashMap<Object, Boolean> originalGraph,
                IdentityHashMap<Object, Boolean> seen) {
            if (!isMutableComposite(value) || seen.put(value, Boolean.TRUE) != null) return;
            if (originalGraph.containsKey(value)) {
                throw new IllegalArgumentException("copy() violated the Copy contract by retaining mutable storage from the source");
            }
            if (value instanceof OresObject object) {
                for (Object field : object.fields.values()) rejectMutableAliases(field, originalGraph, seen);
            } else if (value instanceof List<?> list) {
                for (Object item : list) rejectMutableAliases(item, originalGraph, seen);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    rejectMutableAliases(entry.getKey(), originalGraph, seen);
                    rejectMutableAliases(entry.getValue(), originalGraph, seen);
                }
            } else if (value instanceof Object[] array) {
                for (Object item : array) rejectMutableAliases(item, originalGraph, seen);
            }
        }

        private boolean isMutableComposite(Object value) {
            return value instanceof OresObject || value instanceof List<?>
                    || value instanceof Map<?, ?> || value instanceof Object[];
        }

        private List<?> iterableValues(Object value, SingletonState singletonState) {
            if (value instanceof List<?> list) return list;
            if (value instanceof Object[] array) return List.of(array);
            if (value instanceof OresObject object) {
                Ast.ClassDecl dispatchClass = runtimeDispatchClass(object, singletonState);
                Ast.MethodDecl iterator = findMethod(dispatchClass, "Symbol.iterator", 0, new LinkedHashSet<>());
                if (iterator == null) throw new IllegalArgumentException("value has no [Symbol.iterator]()");
                Object produced = callMethod(object, dispatchClass, iterator, List.of(), singletonState);
                return iterableValues(produced, singletonState);
            }
            throw new IllegalArgumentException("value is not iterable");
        }

        private Object binary(String op, Object left, Object right) {
            return switch (op) {
                case "+" -> add(left, right); case "-" -> numeric(left, right, '-'); case "*" -> numeric(left, right, '*');
                case "/" -> numeric(left, right, '/'); case "%" -> numeric(left, right, '%');
                case "==" -> Objects.equals(left, right); case "!=" -> !Objects.equals(left, right);
                case "<" -> compare(left, right) < 0; case "<=" -> compare(left, right) <= 0;
                case ">" -> compare(left, right) > 0; case ">=" -> compare(left, right) >= 0;
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

        private Object negate(Object value) {
            if (value instanceof Complex c) return c.negate();
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
        private List<?> asSequence(Object value) { if (value instanceof List<?> l) return l; if (value instanceof Object[] a) return List.of(a); throw new IllegalArgumentException("value is not destructurable"); }
        private String display(Object value) { return value instanceof Complex c ? c.toString() : String.valueOf(value); }
    }

    @FunctionalInterface private interface Invokable { Object call(List<Object> arguments); }
    @FunctionalInterface private interface SingletonWork { Object apply(SingletonState state); }

    private static final class SingletonState {
        private final String key;
        private final String schema;
        private final Env fields;
        private String activeCodeDigest;
        private long activeGeneration;

        private SingletonState(String key, String schema, String activeCodeDigest, long activeGeneration) {
            this.key = key;
            this.schema = schema;
            this.activeCodeDigest = activeCodeDigest;
            this.activeGeneration = activeGeneration;
            this.fields = new Env(null, this, true);
        }

        private SingletonState transactionalCopy() {
            SingletonState copy = new SingletonState(
                    key, schema, activeCodeDigest, activeGeneration);
            IdentityHashMap<Object, Object> copied = new IdentityHashMap<>();
            for (Map.Entry<String, Slot> entry : fields.slots.entrySet()) {
                Slot slot = entry.getValue();
                Object value = slot.value == Env.MISSING
                        ? Env.MISSING
                        : copySingletonValue(slot.value, copied);
                copy.fields.slots.put(entry.getKey(), new Slot(value, slot.kind));
            }
            return copy;
        }

        private void commitFrom(SingletonState working) {
            if (!key.equals(working.key) || !schema.equals(working.schema)) {
                throw new IllegalStateException("cannot commit singleton transaction across identity/schema boundary");
            }
            working.validateStorageGraph();
            fields.slots.clear();
            fields.slots.putAll(working.fields.slots);
            activeCodeDigest = working.activeCodeDigest;
            activeGeneration = working.activeGeneration;
        }

        private void validateStorageGraph() {
            LinkedHashMap<String, Object> graph = new LinkedHashMap<>();
            IdentityHashMap<Object, Boolean> path = new IdentityHashMap<>();
            for (Map.Entry<String, Slot> entry : fields.slots.entrySet()) {
                if (entry.getValue().value == Env.MISSING) continue;
                graph.put(entry.getKey(), singletonValidationValue(entry.getValue().value, path));
            }
            // One freeze budget applies to the whole process-owned state cell.
            ActorRuntime.freeze(graph);
        }
    }

    private static Object copySingletonValue(
            Object value,
            IdentityHashMap<Object, Object> copies) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Complex) {
            return value;
        }
        Object existing = copies.get(value);
        if (existing != null) return existing;

        if (value instanceof OresObject object) {
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            OresObject copy = new OresObject(object.klass, fields);
            copies.put(value, copy);
            for (Map.Entry<String, Object> entry : object.fields.entrySet()) {
                fields.put(entry.getKey(), copySingletonValue(entry.getValue(), copies));
            }
            return copy;
        }
        if (value instanceof OptionValue option) {
            return option.present()
                    ? new OptionValue(true, copySingletonValue(option.value(), copies))
                    : option;
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            copies.put(value, copy);
            for (Object item : list) copy.add(copySingletonValue(item, copies));
            return copy;
        }
        if (value instanceof Set<?> set) {
            LinkedHashSet<Object> copy = new LinkedHashSet<>();
            copies.put(value, copy);
            for (Object item : set) copy.add(copySingletonValue(item, copies));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> copy = new LinkedHashMap<>();
            copies.put(value, copy);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(
                        copySingletonValue(entry.getKey(), copies),
                        copySingletonValue(entry.getValue(), copies));
            }
            return copy;
        }
        if (value instanceof Object[] array) {
            Object[] copy = new Object[array.length];
            copies.put(value, copy);
            for (int i = 0; i < array.length; i++) {
                copy[i] = copySingletonValue(array[i], copies);
            }
            return copy;
        }
        // Validation will reject unsupported host values before commit.
        return value;
    }

    private static Object singletonValidationValue(
            Object value,
            IdentityHashMap<Object, Boolean> path) {
        if (value instanceof OresObject object) {
            if (path.put(object, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("singleton state contains a cyclic class-instance graph");
            }
            try {
                LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                fields.put("@class", object.klass.name());
                for (Map.Entry<String, Object> entry : object.fields.entrySet()) {
                    fields.put(entry.getKey(), singletonValidationValue(entry.getValue(), path));
                }
                return fields;
            } finally {
                path.remove(object);
            }
        }
        if (value instanceof OptionValue option && option.present()) {
            return new OptionValue(true, singletonValidationValue(option.value(), path));
        }
        if (value instanceof List<?> list) {
            if (path.put(list, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("singleton state contains a cyclic list graph");
            }
            try {
                ArrayList<Object> copy = new ArrayList<>(list.size());
                for (Object item : list) copy.add(singletonValidationValue(item, path));
                return copy;
            } finally {
                path.remove(list);
            }
        }
        if (value instanceof Set<?> set) {
            if (path.put(set, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("singleton state contains a cyclic set graph");
            }
            try {
                LinkedHashSet<Object> copy = new LinkedHashSet<>();
                for (Object item : set) copy.add(singletonValidationValue(item, path));
                return copy;
            } finally {
                path.remove(set);
            }
        }
        if (value instanceof Map<?, ?> map) {
            if (path.put(map, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("singleton state contains a cyclic map graph");
            }
            try {
                LinkedHashMap<Object, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = singletonValidationValue(entry.getKey(), path);
                    Object mapped = singletonValidationValue(entry.getValue(), path);
                    if (copy.putIfAbsent(key, mapped) != null) {
                        throw new IllegalArgumentException("singleton state map keys collide after validation");
                    }
                }
                return copy;
            } finally {
                path.remove(map);
            }
        }
        if (value instanceof Object[] array) {
            if (path.put(array, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("singleton state contains a cyclic array graph");
            }
            try {
                ArrayList<Object> copy = new ArrayList<>(array.length);
                for (Object item : array) copy.add(singletonValidationValue(item, path));
                return copy;
            } finally {
                path.remove(array);
            }
        }
        return value;
    }

    private static final class Env {
        private static final Object MISSING = new Object();
        private final Env parent;
        private final SingletonState singletonState;
        private final boolean singletonStorage;
        private final Map<String, Slot> slots = new LinkedHashMap<>();
        private final Map<String, Ast.ClassDecl> localTypes = new HashMap<>();

        private Env(Env parent) {
            this(parent, parent == null ? null : parent.singletonState, false);
        }

        private Env(Env parent, SingletonState singletonState) {
            this(parent, singletonState, false);
        }

        private Env(Env parent, SingletonState singletonState, boolean singletonStorage) {
            this.parent = parent;
            this.singletonState = singletonState;
            this.singletonStorage = singletonStorage;
        }

        private void define(String name, Object value, Ast.BindingKind kind) {
            validateSingletonStorageValue(value);
            if (slots.putIfAbsent(name, new Slot(value, kind)) != null) throw new IllegalArgumentException("duplicate binding " + name);
            if (singletonStorage) {
                try {
                    singletonState.validateStorageGraph();
                } catch (RuntimeException | Error failure) {
                    slots.remove(name);
                    throw failure;
                }
            }
        }
        private void reserve(String name, Ast.BindingKind kind) {
            if (slots.putIfAbsent(name, new Slot(MISSING, kind)) != null) throw new IllegalArgumentException("duplicate binding " + name);
        }
        private void initialize(String name, Object value) {
            Slot slot = slots.get(name);
            if (slot == null) throw new IllegalArgumentException("unknown binding " + name);
            validateSingletonStorageValue(value);
            Object previous = slot.value;
            slot.value = value;
            if (singletonStorage) {
                try {
                    singletonState.validateStorageGraph();
                } catch (RuntimeException | Error failure) {
                    slot.value = previous;
                    throw failure;
                }
            }
        }
        private Object lookup(String name) { Slot s=slots.get(name); return s!=null?s.value:parent==null?MISSING:parent.lookup(name); }
        private void defineType(String name, Ast.ClassDecl type) {
            Ast.ClassDecl previous = localTypes.putIfAbsent(name, type);
            if (previous != null && previous != type) throw new IllegalArgumentException("duplicate local type " + name);
        }
        private Ast.ClassDecl lookupType(String name) {
            Ast.ClassDecl type = localTypes.get(name);
            return type != null ? type : parent == null ? null : parent.lookupType(name);
        }
        private void assign(String name, Object value) {
            Slot slot = slots.get(name);
            if (slot != null) {
                if (slot.kind != Ast.BindingKind.LET) throw new IllegalArgumentException("cannot reassign " + slot.kind.name().toLowerCase() + " binding " + name);
                validateSingletonStorageValue(value);
                Object previous = slot.value;
                slot.value = value;
                if (singletonStorage) {
                    try {
                        singletonState.validateStorageGraph();
                    } catch (RuntimeException | Error failure) {
                        slot.value = previous;
                        throw failure;
                    }
                }
                return;
            }
            if (parent != null) { parent.assign(name, value); return; }
            throw new IllegalArgumentException("unknown binding " + name);
        }
        private void validateSingletonStorageValue(Object value) {
            if (singletonStorage && value != MISSING) {
                // Validate a detached representation; the actor keeps ownership
                // of the mutable state itself.
                ActorRuntime.freeze(singletonValidationValue(value, new IdentityHashMap<>()));
            }
        }

        private Env snapshot() {
            Env cp = new Env(parent == null ? null : parent.snapshot(), singletonState, singletonStorage);
            cp.slots.putAll(slots);
            cp.localTypes.putAll(localTypes);
            return cp;
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

    private static final class OresObject {
        private final Ast.ClassDecl klass; private final Map<String,Object> fields;
        private OresObject(Ast.ClassDecl klass, Map<String,Object> fields){this.klass=klass;this.fields=fields;}
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!klass.isStruct() || !(other instanceof OresObject value) || !value.klass.isStruct()) return false;
            return klass == value.klass && fields.equals(value.fields);
        }
        @Override public int hashCode() {
            return klass.isStruct() ? 31 * System.identityHashCode(klass) + fields.hashCode() : System.identityHashCode(this);
        }
        @Override public String toString(){return klass.name()+fields;}
    }

    private record ActorModuleStateKey(
            String codeUnitId,
            String namespace,
            String moduleName,
            String codeDigest) { }
    private record ModuleFacade(Ast.ModuleDecl module, SingletonState localState) { }
    private record ClassFacade(Ast.ClassDecl klass, SingletonState localState) { }
    private record SingletonObjectProxy(Ast.ModuleDecl module, String fieldName, Ast.ClassDecl klass) { }
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
    }
    private static void requireOne(List<Object> args,String name){if(args.size()!=1)throw new IllegalArgumentException(name+" expects one argument");}
}
