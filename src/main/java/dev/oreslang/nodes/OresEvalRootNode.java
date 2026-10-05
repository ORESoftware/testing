package dev.oreslang.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresMutex;
import dev.oreslang.runtime.OresRwLock;
import dev.oreslang.runtime.OresFutures;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.ActorRuntime;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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
        Evaluator current = evaluator(context);
        if (isControl(arguments, LINK_ONLY_COMMAND)) {
            current.link();
            return null;
        }
        if (isControl(arguments, INIT_ONLY_COMMAND)) {
            // Legacy host-control spelling retained as a link-only no-op.
            // Oreslang has no implicit file/module/class init lifecycle.
            current.link();
            return null;
        }
        if (isControl(arguments, MAIN_ONLY_COMMAND)) {
            current.link();
            return current.executeMain(new Object[0]);
        }

        // RootNode is already executing inside an entered Graal context. It
        // must not hop to another carrier here. Official launchers place the
        // entire Context lifecycle on the process SHARED/root carrier before
        // entering Graal; direct embedders retain ownership of their entry
        // thread unless they opt into ActorRuntime.executeProcessRoot(...).
        current.link();
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
        private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
        private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();
        private final Map<String, Ast.ImportDecl> namedImports = new HashMap<>();
        private final Map<String, Ast.ImportDecl> namespaceImports = new HashMap<>();
        private final Set<String> ambiguousFunctions = new LinkedHashSet<>();
        private final Set<String> ambiguousClasses = new LinkedHashSet<>();
        private final Set<String> ambiguousTypeAliases = new LinkedHashSet<>();

        private Evaluator(Ast.Program program, OresContext context, String codeUnitId) {
            this.program = program;
            this.context = context;
            this.codeUnitId = normalizeUnitId(codeUnitId);
            indexImports();
            indexDeclarations();
        }

        private void indexImports() {
            for (Ast.ImportDecl imported : program.imports()) {
                if (imported.wildcard()) {
                    namespaceImports.put(imported.namespace(), imported);
                } else {
                    for (String importedName : imported.names()) {
                        namedImports.put(imported.localName(importedName), imported);
                    }
                }
            }
        }

        private void indexDeclarations() {
            for (Ast.ModuleDecl module : program.modules()) {
                modules.put(module.name(), module);
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                    else if (decl instanceof Ast.ClassDecl klass) index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                    else if (decl instanceof Ast.TypeAliasDecl alias) index(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias);
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

        private Ast.TypeAliasDecl findTypeAlias(String name) {
            if (ambiguousTypeAliases.contains(name)) throw new IllegalArgumentException("ambiguous type alias " + name + "; qualify it with its module");
            return typeAliases.get(name);
        }

        private synchronized void link() {
            context.registerLinkedCodeUnit(codeUnitId, this);
        }


        private Object executeMain(Object[] arguments) {
            Ast.FunctionDecl main = functions.get(Parser.ROOT_MODULE + ".main");
            if (main == null) main = findFunction("main");
            if (main == null) return null;
            return callFunction(main, List.of(arguments));
        }

        private Object callFunction(Ast.FunctionDecl fn, List<?> args) {
            List<?> normalized = normalizeFunctionArguments(fn, args);
            if (fn.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalStateException(
                        "actor callable '" + fn.name()
                                + "' cannot be invoked directly; use spawn " + fn.name() + "(...)");
            }
            return callFunctionBody(fn, normalized);
        }

        private Object spawnFunction(Ast.CallExpr call, Env env) {
            Ast.FunctionDecl fn = null;
            Ast.ClassDecl klass = null;
            Evaluator owner = this;

            if (call.callee() instanceof Ast.NameExpr name) {
                fn = findFunction(name.name());
                klass = findClass(name.name());
                if (fn == null && klass == null) {
                    Object imported = importedValue(name.name());
                    if (imported instanceof ClassFacade externalClass) {
                        owner = externalClass.owner();
                        klass = externalClass.klass();
                    }
                }
            } else if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr namespace) {
                fn = functions.get(namespace.name() + "." + member.member());
                klass = classes.get(namespace.name() + "." + member.member());
                if (fn == null && klass == null) {
                    Object candidate = eval(call.callee(), env);
                    if (candidate instanceof ClassFacade externalClass) {
                        owner = externalClass.owner();
                        klass = externalClass.klass();
                    }
                }
            } else {
                throw new IllegalArgumentException(
                        "spawn requires a direct actor class or actor fnc/routine call");
            }

            if (fn != null && klass != null) {
                throw new IllegalArgumentException("spawn target is ambiguous between actor class and actor callable");
            }

            List<Object> evaluated = call.arguments().stream().map(arg -> eval(arg, env)).toList();

            if (klass != null) {
                if (klass.actorKind() == Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException(
                            "spawn target class '" + klass.name() + "' is not an actor class");
                }
                return owner.spawnActorClass(klass, evaluated);
            }

            if (fn == null) throw new IllegalArgumentException("unknown spawn target");
            if (fn.actorKind() == Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "spawn target '" + fn.name() + "' is not an actor callable");
            }

            ActorRuntime.ActorKind runtimeKind = switch (fn.actorKind()) {
                case NONE -> throw new AssertionError("non-actor callable reached spawn lowering");
                case PRIVATE -> ActorRuntime.ActorKind.PRIVATE;
                case SHARED -> ActorRuntime.ActorKind.SHARED;
                case UNTRUSTED -> throw new SecurityException(
                        "untrusted actor callables require isolation-aware lowering; "
                                + "the ordinary JVM interpreter must not execute hostile guest code");
            };

            Ast.FunctionDecl actorFn = fn;
            List<?> normalized = normalizeFunctionArguments(actorFn, evaluated);
            return context.actors().spawnInvocation(
                    runtimeKind,
                    normalized,
                    (delivered, actorContext) -> callFunctionBody(actorFn, delivered));
        }

        private ActorRuntime.ActorRef<Object> spawnActorClass(
                Ast.ClassDecl klass,
                List<Object> constructorArguments) {
            if (klass.actorKind() != Ast.ActorKind.SHARED) {
                throw new SecurityException(
                        "the reference evaluator executes only SHARED source actor classes; "
                                + klass.actorKind()
                                + " actor classes require their isolation-aware OresVM lowering");
            }

            List<Object> prepared = constructorArguments.stream()
                    .map(context.actors()::prepareSourceSharedActorInput)
                    .toList();

            return context.actors().spawnSourceSharedProtocolActor(actorContext -> {
                OresObject actor = instantiateActorState(klass, prepared);
                return (methodName, arguments, turnContext) -> {
                    Ast.MethodDecl endpoint = findMethod(
                            klass,
                            methodName,
                            arguments.size(),
                            new LinkedHashSet<>());
                    if (endpoint == null
                            || endpoint.isStatic()
                            || endpoint.visibility() != Ast.Visibility.PUBLIC
                            || endpoint.name().equals("constructor")) {
                        throw new IllegalArgumentException(
                                "actor class '" + klass.name()
                                        + "' has no public protocol method '"
                                        + methodName + "' with arity " + arguments.size());
                    }
                    return callMethod(actor, endpoint, arguments);
                };
            });
        }

        private List<?> normalizeFunctionArguments(Ast.FunctionDecl fn, List<?> args) {
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
            return args;
        }

        private Object callFunctionBody(Ast.FunctionDecl fn, List<?> args) {
            Env env = new Env(null, fn.nonLexical());
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return shapeReturnedValue(
                        fn.returnType(),
                        signal.value,
                        "function " + fn.name());
            }
        }

        private Object callMethod(OresObject receiver, Ast.MethodDecl method, List<?> args) {
            if (args.size() != method.parameters().size()) throw new IllegalArgumentException("method " + method.name() + " arity mismatch");
            Env env = new Env(null);
            if (!method.isStatic()) env.define("self", receiver, Ast.BindingKind.VAL);
            for (int i = 0; i < method.parameters().size(); i++) {
                Ast.Param param = method.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(method.body(), env);
                return null;
            } catch (ReturnSignal signal) { return shapeReturnedValue(method.returnType(), signal.value, "method " + method.name()); }
        }

        private void executeBlock(List<Ast.Stmt> statements, Env parent) {
            Env env = new Env(parent);
            ArrayDeque<Ast.Expr> deferred = new ArrayDeque<>();
            boolean abnormalExit = false;
            try {
                for (Ast.Stmt stmt : statements) executeStatement(stmt, env, deferred);
            } catch (ReturnSignal signal) {
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

        private void executeStatement(Ast.Stmt stmt, Env env, ArrayDeque<Ast.Expr> deferred) {
            // Every actor kind observes stop/turn-overrun signals at statement
            // boundaries. UNTRUSTED actors additionally consume fuel here and
            // at expression boundaries below.
            if (ActorRuntime.inActorExecution() || ActorRuntime.inRootExecution()) {
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
                            env.define(binding.name(), destructureMember(value, binding.name()), binding.kind());
                        }
                    }
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
                catch (OresPanic panic) { throw panic; }
                catch (RuntimeException failure) {
                    if (ActorRuntime.isActorControlAbort(failure)) throw failure;
                    Env catchEnv = new Env(env);
                    catchEnv.define(tried.errorName(), failure, Ast.BindingKind.VAL);
                    executeBlock(tried.catchBody(), catchEnv);
                } finally { executeBlock(tried.finallyBody(), env); }
                return;
            }
            if (stmt instanceof Ast.ForOfStmt loop) {
                Object iterable = eval(loop.iterable(), env);
                for (Object item : iterableValues(iterable)) {
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
            if (ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.UNTRUSTED) {
                context.schedulerSafepoint();
            }
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
                if (name.name().equals("Futures")) return new FuturesFacade();
                if (name.name().equals("Mutex")) return new MutexFactory(false, context);
                if (name.name().equals("SharedMutex")) return new MutexFactory(true, context);
                if (name.name().equals("RwLock")) return new RwLockFactory(context);
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
                Ast.ModuleDecl module = modules.get(name.name());
                if (module != null) return new ModuleFacade(this, module);
                Ast.ClassDecl klass = findClass(name.name());
                if (klass != null) return new ClassFacade(this, klass);
                Object imported = importedValue(name.name());
                if (imported != Env.MISSING) return imported;
                Ast.FunctionDecl fn = findFunction(name.name());
                if (fn != null) return (Invokable) args -> callFunction(fn, args);
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
                        Ast.FieldDecl field = effectiveFields(object.klass, new LinkedHashSet<>()).stream()
                                .filter(candidate -> candidate.name().equals(target.member()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("unknown field " + target.member()));
                        if (field.bindingKind() != Ast.BindingKind.LET) {
                            throw new IllegalArgumentException("field '" + object.klass.name() + "."
                                    + target.member() + "' is immutable");
                        }
                        object.fields.put(target.member(), value);
                        return value;
                    }
                    throw new IllegalArgumentException("member assignment requires a class instance or mutex guard over a class instance");
                }
                if (assignment.target() instanceof Ast.IndexExpr target) {
                    Object receiver = eval(target.receiver(), env);
                    Object index = eval(target.index(), env);
                    if (!(index instanceof Number number)) throw new IllegalArgumentException("index must be an integer");
                    int i = Math.toIntExact(number.longValue());
                    if (receiver instanceof List<?> raw) {
                        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) raw;
                        list.set(i, value);
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
            if (expr instanceof Ast.CallExpr call) {
                if (call.callee() instanceof Ast.MemberExpr methodCall) {
                    Object receiver = eval(methodCall.receiver(), env);
                    List<Object> args = call.arguments().stream().map(arg -> eval(arg, env)).toList();
                    if (receiver instanceof OresObject object) {
                        return object.owner.invokeMethod(object, methodCall.member(), args);
                    }
                    if (receiver instanceof ClassFacade klass) {
                        return klass.owner().invokeStaticFunction(klass.klass(), methodCall.member(), args);
                    }
                    if (receiver instanceof ActorRuntime.ActorRef<?> ref) {
                        return switch (methodCall.member()) {
                            case "id" -> throw new IllegalArgumentException(
                                    "ActorRef.id is a value, not a callable");
                            case "is_alive" -> {
                                requireZero(args, "ActorRef.is_alive");
                                yield ref.isAlive();
                            }
                            case "send", "receive", "mailbox" -> throw new IllegalArgumentException(
                                    "raw ActorRef mailbox operations are runtime-private; "
                                            + "invoke a declared typed actor protocol method instead");
                            default -> context.actors().invokeSourceProtocol(
                                    ref,
                                    methodCall.member(),
                                    args);
                        };
                    }
                    Object callee = member(receiver, methodCall.member());
                    if (!(callee instanceof Invokable invokable)) throw new IllegalArgumentException("value is not callable: " + callee);
                    return invokable.call(args);
                }
                Object callee = eval(call.callee(), env);
                List<Object> args = call.arguments().stream().map(arg -> eval(arg, env)).toList();
                if (!(callee instanceof Invokable invokable)) throw new IllegalArgumentException("value is not callable: " + callee);
                return invokable.call(args);
            }
            if (expr instanceof Ast.MemberExpr member) return member(eval(member.receiver(), env), member.member());
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
            if (expr instanceof Ast.SpawnExpr spawned) {
                return spawnFunction(spawned.call(), env);
            }
            if (expr instanceof Ast.AwaitExpr awaited) {
                Object value = eval(awaited.expression(), env);
                if (value instanceof ActorRuntime.ActorSpawn<?, ?> spawn) {
                    value = spawn.ready();
                }

                if (value instanceof OresFuture<?> future) {
                    // Source actor await must never resume inline, including for
                    // an already-settled Future. The stackless source-frame
                    // lowerer replaces this recursive-evaluator path with
                    // ActorContext.suspendOn(...).
                    if (ActorRuntime.inActorExecution()) {
                        throw new IllegalStateException(
                                "source await inside an actor requires continuation lowering; "
                                        + "the recursive evaluator must not block or inline-resume an actor carrier");
                    }
                    if (ActorRuntime.currentRootIsAdversarial() && !future.isDone()) {
                        throw new IllegalStateException(
                                "await would block an adversarial serialized root context; "
                                        + "continuation lowering must suspend/resume before awaiting readiness/result");
                    }
                    return future.join();
                }

                // Compatibility boundary for host/legacy async primitives such
                // as the current mutex implementation. Normalize their
                // completion policy before exposing them as an Ores Future.
                if (value instanceof CompletionStage<?> stage) {
                    OresFuture<?> future = OresFuture.from(stage);
                    if (ActorRuntime.inActorExecution()) {
                        throw new IllegalStateException(
                                "source await inside an actor requires continuation lowering; "
                                        + "host stages are normalized to OresFuture before suspension");
                    }
                    if (ActorRuntime.currentRootIsAdversarial() && !future.isDone()) {
                        throw new IllegalStateException(
                                "await would block an adversarial serialized root context; "
                                        + "continuation lowering must suspend/resume before awaiting a host stage");
                    }
                    return future.join();
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
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                for (Ast.ObjectField field : object.fields()) {
                    if (result.putIfAbsent(field.name(), eval(field.value(), env)) != null) throw new IllegalArgumentException("duplicate obj field " + field.name());
                }
                return Map.copyOf(result);
            }
            if (expr instanceof Ast.LambdaExpr lambda) {
                boolean nonLexical = lambda.nonLexical() || env.descendantsNonLexical();
                Env captured = nonLexical ? null : env.snapshot();
                return (Invokable) args -> {
                    if (args.size() != lambda.parameters().size()) throw new IllegalArgumentException("lambda arity mismatch");
                    Env local = new Env(captured, nonLexical);
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

        private Object member(Object receiver, String name) {
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
            if (receiver instanceof FuturesFacade futures) {
                return switch (name) {
                    case "all" -> (Invokable) futures::all;
                    case "race" -> (Invokable) futures::race;
                    default -> throw new IllegalArgumentException("unknown Futures member " + name);
                };
            }
            if (receiver instanceof ActorRuntime.ActorSpawn<?, ?> spawn) {
                return switch (name) {
                    case "id" -> spawn.id();
                    case "ready" -> spawn.ready();
                    case "done" -> spawn.done();
                    case "result" -> spawn.result();
                    default -> throw new IllegalArgumentException("unknown ActorSpawn member " + name);
                };
            }
            if (receiver instanceof ActorRuntime.ActorRef<?> ref) {
                return switch (name) {
                    case "id" -> ref.id();
                    case "is_alive" -> (Invokable) args -> {
                        requireZero(args, "ActorRef.is_alive");
                        return ref.isAlive();
                    };
                    case "send", "receive", "mailbox" -> throw new IllegalArgumentException(
                            "raw ActorRef mailbox operations are runtime-private; "
                                    + "invoke a declared typed actor protocol method instead");
                    default -> throw new IllegalArgumentException(
                            "actor protocol methods are not first-class values; invoke '"
                                    + name + "(...)' directly through the ActorRef");
                };
            }
            if (receiver instanceof OresFuture<?> future) {
                return switch (name) {
                    case "is_done" -> (Invokable) args -> {
                        requireZero(args, "Future.is_done");
                        return future.isDone();
                    };
                    case "is_cancelled" -> (Invokable) args -> {
                        requireZero(args, "Future.is_cancelled");
                        return future.isCancelled();
                    };
                    case "cancel" -> (Invokable) args -> {
                        requireZero(args, "Future.cancel");
                        return future.cancel(true);
                    };
                    default -> throw new IllegalArgumentException(
                            "unknown Future member " + name + "; use await to obtain its value");
                };
            }
            if (receiver instanceof CompletionStage<?> stage) {
                var future = stage.toCompletableFuture();
                return switch (name) {
                    case "is_done" -> (Invokable) args -> {
                        requireZero(args, "Future.is_done");
                        return future.isDone();
                    };
                    case "is_cancelled" -> (Invokable) args -> {
                        requireZero(args, "Future.is_cancelled");
                        return future.isCancelled();
                    };
                    case "cancel" -> (Invokable) args -> {
                        requireZero(args, "Future.cancel");
                        return future.cancel(true);
                    };
                    default -> throw new IllegalArgumentException(
                            "unknown Future member " + name + "; use await to obtain its value");
                };
            }
            if (receiver instanceof MutexFactory factory) {
                if (!name.equals("new")) throw new IllegalArgumentException("unknown mutex factory member " + name);
                return (Invokable) factory::create;
            }
            if (receiver instanceof RwLockFactory factory) {
                if (!name.equals("new")) throw new IllegalArgumentException("unknown RwLock factory member " + name);
                return (Invokable) factory::create;
            }
            if (receiver instanceof OptionValue option) return optionMember(option, name);
            if (receiver instanceof ResultValue result) return resultMember(result, name);
            if (receiver instanceof OresRwLock<?> rwLock) return rwLockMember(rwLock, name);
            if (receiver instanceof OresRwLock.ReadGuard<?> guard) {
                return switch (name) {
                    case "value" -> (Invokable) args -> {
                        requireZero(args, "RwReadGuard.value");
                        return guard.value();
                    };
                    case "release" -> (Invokable) args -> {
                        requireZero(args, "RwReadGuard.release");
                        guard.close();
                        return null;
                    };
                    case "is_released" -> (Invokable) args -> {
                        requireZero(args, "RwReadGuard.is_released");
                        return guard.closed();
                    };
                    default -> throw new IllegalArgumentException(
                            "unknown RwReadGuard member " + name);
                };
            }
            if (receiver instanceof OresRwLock.WriteGuard<?> guard) {
                return switch (name) {
                    case "value" -> (Invokable) args -> {
                        requireZero(args, "RwWriteGuard.value");
                        return guard.value();
                    };
                    case "replace" -> (Invokable) args -> {
                        requireOne(args, "RwWriteGuard.replace");
                        @SuppressWarnings("unchecked")
                        OresRwLock.WriteGuard<Object> writable =
                                (OresRwLock.WriteGuard<Object>) guard;
                        writable.replace(args.getFirst());
                        return null;
                    };
                    case "release" -> (Invokable) args -> {
                        requireZero(args, "RwWriteGuard.release");
                        guard.close();
                        return null;
                    };
                    case "is_released" -> (Invokable) args -> {
                        requireZero(args, "RwWriteGuard.is_released");
                        return guard.closed();
                    };
                    default -> throw new IllegalArgumentException(
                            "unknown RwWriteGuard member " + name);
                };
            }
            if (receiver instanceof OresMutex.Lock<?> lock) return mutexMember(lock, name);
            if (receiver instanceof OresMutex.Guard<?> guard) {
                return switch (name) {
                    case "release" -> (Invokable) args -> { requireZero(args, "MutexGuard.release"); guard.release(); return null; };
                    case "is_released" -> (Invokable) args -> { requireZero(args, "MutexGuard.is_released"); return guard.released(); };
                    default -> member(guard.value(), name);
                };
            }
            if (receiver instanceof ImportedNamespace namespace) return namespace.owner().exportValue(namespace.kind(), name);
            if (receiver instanceof ModuleFacade namespace) return namespace.owner().moduleMember(namespace.module(), name);
            if (receiver instanceof ClassFacade klass) {
                List<Ast.MethodDecl> functions = klass.owner().findStaticFunctionsByName(klass.klass(), name, new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    return (Invokable) args -> klass.owner().callStaticFunction(klass.klass(), fn, args);
                }
                if (functions.size() > 1) throw new IllegalArgumentException("overloaded static function " + klass.klass().name() + "." + name + " must be called so arity can select it");
                throw new IllegalArgumentException("unknown static member " + klass.klass().name() + "." + name);
            }
            if (receiver instanceof OresObject object) {
                if (object.fields.containsKey(name)) {
                    Object value = object.fields.get(name);
                    if (value == UninitializedActorField.INSTANCE) {
                        throw new IllegalStateException(
                                "actor/class field '" + object.klass.name() + "." + name
                                        + "' was read before initialization");
                    }
                    return value;
                }
                if (hasInstanceMethodNamed(object.klass, name, new LinkedHashSet<>())) {
                    if (object.klass.actorKind() != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException(
                                "actor method '" + object.klass.name() + "." + name
                                        + "' is not a first-class value and cannot escape an actor turn");
                    }
                    return new BoundMethod(object.owner, object, name);
                }
                throw new IllegalArgumentException("unknown member " + object.klass.name() + "." + name);
            }
            if (receiver instanceof Map<?, ?> map) {
                if (!map.containsKey(name)) throw new IllegalArgumentException("unknown obj member " + name);
                return map.get(name);
            }
            throw new IllegalArgumentException("cannot access member '" + name + "' on " + receiver);
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
        private Object rwLockMember(OresRwLock<?> rawLock, String name) {
            context.requireCapability(IsolatePolicy.Capability.SHARED_MEMORY, "RwLock." + name);
            OresRwLock<Object> lock = (OresRwLock<Object>) rawLock;
            return switch (name) {
                case "read_lock" -> (Invokable) args -> {
                    requireZero(args, "RwLock.read_lock");
                    return lock.readLock();
                };
                case "try_read_lock" -> (Invokable) args -> {
                    requireZero(args, "RwLock.try_read_lock");
                    var guard = lock.tryReadLock();
                    return guard.isPresent()
                            ? new OptionValue(true, guard.get())
                            : new OptionValue(false, null);
                };
                case "write_lock" -> (Invokable) args -> {
                    requireZero(args, "RwLock.write_lock");
                    return lock.writeLock();
                };
                case "try_write_lock" -> (Invokable) args -> {
                    requireZero(args, "RwLock.try_write_lock");
                    var guard = lock.tryWriteLock();
                    return guard.isPresent()
                            ? new OptionValue(true, guard.get())
                            : new OptionValue(false, null);
                };
                default -> throw new IllegalArgumentException("unknown RwLock member " + name);
            };
        }

        @SuppressWarnings("unchecked")
        private Object mutexMember(OresMutex.Lock<?> rawLock, String name) {
            if (rawLock instanceof OresMutex.Shared<?>) {
                context.requireCapability(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex." + name);
                if (ActorRuntime.inActorExecution()
                        && (name.equals("lock")
                            || name.equals("try_lock")
                            || name.equals("lock_async")
                            || name.equals("with_lock")
                            || name.equals("recover"))) {
                    throw new SecurityException(
                            "actor code cannot acquire writable SharedMutex state; external shared state is read-only");
                }
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
                        Object result = callback.call(List.of(value));
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
                            Object result = callback.call(List.of(value));
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
            if (receiver.klass.actorKind() != Ast.ActorKind.NONE && method.name().equals("constructor")) {
                throw new IllegalStateException(
                        "actor constructor '" + receiver.klass.name()
                                + "' is runtime-only and cannot be invoked as an ordinary method");
            }
            return callMethod(receiver, method, args);
        }

        private Object invokeStaticFunction(Ast.ClassDecl klass, String name, List<Object> args) {
            if (klass.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalStateException(
                        "actor class '" + klass.name() + "' cannot expose static executable code");
            }
            Ast.MethodDecl fn = findStaticFunction(klass, name, args.size(), new LinkedHashSet<>());
            if (fn == null) throw new IllegalArgumentException("no static function " + klass.name() + "." + name + " with arity " + args.size());
            return callStaticFunction(klass, fn, args);
        }

        private Object callStaticFunction(Ast.ClassDecl klass, Ast.MethodDecl fn, List<?> args) {
            if (!fn.isStatic()) throw new IllegalArgumentException("not a static class function: " + klass.name() + "." + fn.name());
            if (args.size() != fn.parameters().size()) throw new IllegalArgumentException("static function " + fn.name() + " arity mismatch");
            Env env = new Env(null);
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) { return shapeReturnedValue(fn.returnType(), signal.value, "static function " + fn.name()); }
        }

        /**
         * Ordinary classes may expose first-class bound method values. Actor
         * methods are rejected before this object can be constructed because
         * an actor receiver must never escape its serialized mailbox turn.
         */
        private static final class BoundMethod implements Invokable {
            private final Evaluator owner;
            private final OresObject receiver;
            private final String methodName;

            private BoundMethod(Evaluator owner, OresObject receiver, String methodName) {
                this.owner = owner;
                this.receiver = receiver;
                this.methodName = methodName;
            }

            @Override public Object call(List<Object> arguments) {
                return owner.invokeMethod(receiver, methodName, arguments);
            }
        }

        private Object importedValue(String name) {
            Ast.ImportDecl direct = namedImports.get(name);
            if (direct != null) {
                String importedName = direct.importedName(name);
                if (importedName == null) {
                    throw new IllegalStateException(
                            "import alias table has no source name for local binding '" + name + "'");
                }
                return importedTarget(direct).exportValue(direct.kind(), importedName);
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
                                + "' is not linked yet; all members of an import cycle must be linked before cross-unit use");
            }
            return evaluator;
        }

        private String resolveImportUnitId(String rawPath) {
            String raw = rawPath.replace('\\', '/');
            Path parent = Path.of(codeUnitId).getParent();
            Path candidatePath = raw.startsWith(".")
                    ? (parent == null ? Path.of(raw) : parent.resolve(raw)).normalize()
                    : Path.of(raw).normalize();
            String candidate = normalizeUnitId(candidatePath.toString());
            if (!context.hasLinkedCodeUnit(candidate)
                    && !candidate.endsWith(".ores")
                    && context.hasLinkedCodeUnit(candidate + ".ores")) {
                candidate += ".ores";
            }
            return candidate;
        }

        private Object exportValue(Ast.ImportKind kind, String name) {
            return switch (kind) {
                case FUNCTION -> {
                    Ast.FunctionDecl fn = findFunction(name);
                    if (fn == null || fn.visibility() != Ast.Visibility.PUBLIC) {
                        throw new IllegalArgumentException(
                                "code unit '" + codeUnitId
                                        + "' does not export function '" + name + "'");
                    }
                    yield (Invokable) args -> callFunction(fn, args);
                }
                case CLASS -> {
                    Ast.ClassDecl klass = findClass(name);
                    if (klass == null || klass.actorKind() != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException(
                                "code unit '" + codeUnitId
                                        + "' does not export ordinary class '" + name + "'");
                    }
                    yield new ClassFacade(this, klass);
                }
                case ACTOR -> {
                    Ast.ClassDecl klass = findClass(name);
                    if (klass == null || klass.actorKind() == Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException(
                                "code unit '" + codeUnitId
                                        + "' does not export actor class '" + name + "'");
                    }
                    yield new ClassFacade(this, klass);
                }
                case MODULE -> {
                    Ast.ModuleDecl module = modules.get(name);
                    if (module == null || module.name().equals(Parser.ROOT_MODULE)) {
                        throw new IllegalArgumentException(
                                "code unit '" + codeUnitId
                                        + "' does not export named module '" + name + "'");
                    }
                    yield new ModuleFacade(this, module);
                }
                case ENTRY -> exportEntry();
                case ALL -> exportAny(name);
            };
        }

        private Object exportEntry() {
            Ast.EntryExportDecl entry = null;
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl decl : module.declarations()) {
                    if (!(decl instanceof Ast.EntryExportDecl candidate)) continue;
                    if (entry != null) {
                        throw new IllegalStateException(
                                "code unit '" + codeUnitId
                                        + "' has multiple entry exports");
                    }
                    entry = candidate;
                }
            }
            if (entry == null) {
                throw new IllegalArgumentException(
                        "code unit '" + codeUnitId + "' has no 'export entry'");
            }
            return exportAny(entry.name());
        }

        private Object exportAny(String name) {
            Ast.ModuleDecl module = modules.get(name);
            if (module != null && !module.name().equals(Parser.ROOT_MODULE)) return new ModuleFacade(this, module);
            Ast.ClassDecl klass = findClass(name);
            if (klass != null) return new ClassFacade(this, klass);
            Ast.FunctionDecl fn = findFunction(name);
            if (fn != null && fn.visibility() == Ast.Visibility.PUBLIC) return (Invokable) args -> callFunction(fn, args);
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
            Env env = new Env(null);
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

        private OresObject instantiateActorState(
                Ast.ClassDecl klass,
                List<Object> constructorArguments) {
            if (klass.actorKind() == Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "instantiateActorState requires an actor class");
            }

            List<Ast.FieldDecl> classFields =
                    effectiveActorFields(klass, new LinkedHashSet<>());
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            for (Ast.FieldDecl field : classFields) {
                fields.put(field.name(), UninitializedActorField.INSTANCE);
            }

            OresObject actor = new OresObject(this, klass, fields);
            Env initializerEnv = new Env(null);
            initializerEnv.define("self", actor, Ast.BindingKind.VAL);
            for (Ast.FieldDecl field : classFields) {
                if (field.initializer() != null) {
                    fields.put(field.name(), eval(field.initializer(), initializerEnv));
                }
            }

            List<Ast.MethodDecl> constructors = klass.methods().stream()
                    .filter(method -> !method.isStatic()
                            && method.name().equals("constructor")
                            && method.arity() == constructorArguments.size())
                    .toList();
            if (constructors.size() > 1) {
                throw new IllegalStateException(
                        "actor class '" + klass.name()
                                + "' has multiple local constructors with arity "
                                + constructorArguments.size());
            }
            if (constructors.isEmpty()) {
                if (!constructorArguments.isEmpty()) {
                    throw new IllegalArgumentException(
                            "actor class '" + klass.name()
                                    + "' has no local constructor with arity "
                                    + constructorArguments.size()
                                    + "; actor constructors are not inherited");
                }
            } else {
                callMethod(actor, constructors.getFirst(), constructorArguments);
            }

            for (Map.Entry<String, Object> field : fields.entrySet()) {
                if (field.getValue() == UninitializedActorField.INSTANCE) {
                    throw new IllegalStateException(
                            "actor field '" + klass.name() + "." + field.getKey()
                                    + "' was not initialized by a field initializer or constructor");
                }
            }
            return actor;
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
                    return (Invokable) args -> callFunction(fn, args);
                }
                if (decl instanceof Ast.FieldDecl field && field.name().equals(name) && field.visibility() == Ast.Visibility.PUBLIC) {
                    if (field.initializer() == null) throw new IllegalArgumentException("module field has no initializer: " + module.name() + "." + name);
                    return eval(field.initializer(), new Env(null));
                }
            }
            throw new IllegalArgumentException("module '" + module.name() + "' does not export '" + name + "'");
        }

        private List<Ast.FieldDecl> effectiveActorFields(
                Ast.ClassDecl klass,
                Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) {
                throw new IllegalArgumentException(
                        "inheritance cycle involving actor " + klass.name());
            }
            LinkedHashMap<String, Ast.FieldDecl> result = new LinkedHashMap<>();
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) {
                    continue;
                }
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent == null) {
                    throw new IllegalArgumentException(
                            "unknown parent class " + parentRef.name());
                }
                for (Ast.FieldDecl field : effectiveActorFields(parent, seen)) {
                    Ast.FieldDecl previous = result.putIfAbsent(field.name(), field);
                    if (previous != null && previous != field) {
                        throw new IllegalStateException(
                                "actor '" + klass.name()
                                        + "' has conflicting inherited state field '"
                                        + field.name() + "'");
                    }
                }
            }
            for (Ast.FieldDecl field : klass.fields()) {
                Ast.FieldDecl previous = result.putIfAbsent(field.name(), field);
                if (previous != null && previous != field) {
                    throw new IllegalStateException(
                            "actor '" + klass.name()
                                    + "' cannot shadow inherited state field '"
                                    + field.name() + "'");
                }
            }
            seen.remove(klass);
            return List.copyOf(result.values());
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

        private boolean hasInstanceMethodNamed(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
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

        private List<?> iterableValues(Object value) {
            if (value instanceof List<?> list) return list;
            if (value instanceof Object[] array) return List.of(array);
            if (value instanceof OresObject object) {
                Ast.MethodDecl iterator = findMethod(object.klass, "Symbol.iterator", 0, new LinkedHashSet<>());
                if (iterator == null) throw new IllegalArgumentException("value has no [Symbol.iterator]()");
                Object produced = callMethod(object, iterator, List.of());
                return iterableValues(produced);
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

        private long integral(Object value, String operator) {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return ((Number) value).longValue();
            }
            throw new IllegalArgumentException(operator + " requires integer operands");
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
        private Object destructureMember(Object value, String name) {
            if (value instanceof Map<?, ?> map) {
                if (!map.containsKey(name)) throw new IllegalArgumentException("object destructure missing member " + name);
                return map.get(name);
            }
            if (value instanceof OresObject object) {
                if (!object.fields.containsKey(name)) {
                    throw new IllegalArgumentException(
                            "object destructure missing field " + name);
                }
                Object field = object.fields.get(name);
                if (field == UninitializedActorField.INSTANCE) {
                    throw new IllegalStateException(
                            "actor/class field '" + object.klass.name() + "." + name
                                    + "' was destructured before initialization");
                }
                return field;
            }
            throw new IllegalArgumentException("value is not object-destructurable");
        }
        private String display(Object value) { return value instanceof Complex c ? c.toString() : String.valueOf(value); }
    }

    @FunctionalInterface private interface Invokable { Object call(List<Object> arguments); }

    private static final class Env {
        private static final Object MISSING = new Object();
        private final Env parent;
        private final boolean descendantsNonLexical;
        private final Map<String, Slot> slots = new HashMap<>();
        private Env(Env parent) { this(parent, parent != null && parent.descendantsNonLexical); }
        private Env(Env parent, boolean descendantsNonLexical) {
            this.parent = parent;
            this.descendantsNonLexical = descendantsNonLexical;
        }
        private boolean descendantsNonLexical() { return descendantsNonLexical; }
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
            Env cp = new Env(parent == null ? null : parent.snapshot(), descendantsNonLexical);
            cp.slots.putAll(slots);
            return cp;
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
            if (value instanceof OresRwLock.ReadGuard<?> guard) {
                if (!guard.closed()) guard.close();
                return;
            }
            if (value instanceof OresRwLock.WriteGuard<?> guard) {
                if (!guard.closed()) guard.close();
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

    private record Complex(double real, double imaginary) implements OresMutex.SharedState {
        @Override public Iterable<?> sharedStateChildren(){return List.of();}
        private Complex add(Complex o){return new Complex(real+o.real,imaginary+o.imaginary);}
        private Complex sub(Complex o){return new Complex(real-o.real,imaginary-o.imaginary);}
        private Complex mul(Complex o){return new Complex(real*o.real-imaginary*o.imaginary,real*o.imaginary+imaginary*o.real);}
        private Complex div(Complex o){double d=o.real*o.real+o.imaginary*o.imaginary;return new Complex((real*o.real+imaginary*o.imaginary)/d,(imaginary*o.real-real*o.imaginary)/d);}
        @Override public String toString(){return real+(imaginary<0?"":"+")+imaginary+"i";}
    }

    private enum UninitializedActorField { INSTANCE }

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

    private record ImportedNamespace(Evaluator owner, Ast.ImportKind kind) { }
    private record ModuleFacade(Evaluator owner, Ast.ModuleDecl module) { }
    private record ClassFacade(Evaluator owner, Ast.ClassDecl klass) { }
    private record RwLockFactory(OresContext context) {
        private Object create(List<Object> args) {
            requireOne(args, "RwLock.new");
            context.requireCapability(IsolatePolicy.Capability.SHARED_MEMORY, "RwLock.new");
            Object value = args.getFirst();
            if (!MutexFactory.runtimeSharedSafe(
                    value,
                    java.util.Collections.newSetFromMap(
                            new java.util.IdentityHashMap<>()))) {
                throw new IllegalArgumentException(
                        "RwLock<T> runtime admission rejected non-shared-safe state");
            }
            return new OresRwLock<>(value);
        }
    }

    private record MutexFactory(boolean shared, OresContext context) {
        private Object create(List<Object> args) {
            requireOne(args, shared ? "SharedMutex.new" : "Mutex.new");
            if (shared) {
                if (ActorRuntime.inActorExecution()) {
                    throw new SecurityException(
                            "actor code cannot create writable SharedMutex state; actor mutation is limited to self-owned state");
                }
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
                    || value instanceof OresFuture<?> || value instanceof CompletionStage<?>
                    || value instanceof Invokable) {
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
    private record FuturesFacade() {
        private Object all(List<Object> args) {
            return OresFutures.all(requireFutures(args, "Futures.all"));
        }

        private Object race(List<Object> args) {
            return OresFutures.race(requireFutures(args, "Futures.race"));
        }

        private static List<Object> requireFutures(
                List<Object> args,
                String operation) {
            requireOne(args, operation);
            if (!(args.getFirst() instanceof List<?> values)) {
                throw new IllegalArgumentException(operation + " expects a list of Future values");
            }
            ArrayList<Object> futures = new ArrayList<>(values.size());
            for (Object value : values) {
                if (!(value instanceof OresFuture<?>)
                        && !(value instanceof CompletionStage<?>)) {
                    throw new IllegalArgumentException(
                            operation + " expects every list element to be a Future");
                }
                futures.add(value);
            }
            return List.copyOf(futures);
        }
    }
    private static void requireZero(List<Object> args,String name){if(!args.isEmpty())throw new IllegalArgumentException(name+" expects no arguments");}
    private static void requireOne(List<Object> args,String name){if(args.size()!=1)throw new IllegalArgumentException(name+" expects one argument");}
    private static String requireStringArg(List<Object> args,String name){
        requireOne(args,name);
        if(!(args.getFirst() instanceof String message)) throw new IllegalArgumentException(name+" expects a String message");
        return message;
    }
}
