package dev.oreslang.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresRuntimeException;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;

import java.math.BigDecimal;
import java.math.BigInteger;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/** Executable Truffle root. Parsing and static checks happen before this node is created. */
public final class OresEvalRootNode extends RootNode {
    private final Ast.Program program;

    public OresEvalRootNode(OresLanguage language, Ast.Program program) {
        super(language);
        this.program = program;
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
        return new Evaluator(program, context).execute(arguments);
    }

    private static final class Evaluator {
        private final Ast.Program program;
        private final OresContext context;
        private final Map<String, Ast.FunctionDecl> functions = new LinkedHashMap<>();
        private final Map<String, Ast.ClassDecl> classes = new LinkedHashMap<>();
        private final Map<String, Ast.ModuleDecl> modules = new LinkedHashMap<>();
        private static final int MAX_GUEST_CALL_DEPTH = 128;
        private final Set<String> ambiguousFunctions = new LinkedHashSet<>();
        private final Set<String> ambiguousClasses = new LinkedHashSet<>();
        private final ThreadLocal<Integer> guestCallDepth = ThreadLocal.withInitial(() -> 0);

        private Evaluator(Ast.Program program, OresContext context) {
            this.program = program;
            this.context = context;
            indexDeclarations();
        }

        private void indexDeclarations() {
            for (Ast.ModuleDecl module : program.modules()) {
                modules.put(module.name(), module);
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                    else if (decl instanceof Ast.ClassDecl klass) index(classes, ambiguousClasses, module.name(), klass.name(), klass);
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
            Ast.FunctionDecl main = findFunction("main");
            if (main == null) return null;
            Object result = callFunction(main, List.of(arguments));
            if (main.async()) {
                if (!(result instanceof CompletionStage<?> stage)) {
                    throw new IllegalStateException("async main did not produce a Future");
                }
                return awaitStage(stage);
            }
            return result;
        }

        private Object callFunction(Ast.FunctionDecl fn, List<?> args) {
            List<?> normalized = args;
            if (normalized.size() != fn.parameters().size()) {
                if (fn.parameters().isEmpty()
                        && normalized.size() == 1
                        && normalized.getFirst() instanceof Object[] array
                        && array.length == 0) {
                    normalized = List.of();
                } else {
                    throw new IllegalArgumentException(
                            "function " + fn.name() + " expects " + fn.parameters().size()
                                    + " arguments, got " + normalized.size());
                }
            }
            List<?> capturedArgs = List.copyOf(normalized);
            if (fn.async()) {
                return startAsync(() -> callFunctionSync(fn, capturedArgs));
            }
            return callFunctionSync(fn, capturedArgs);
        }

        private Object callFunctionSync(Ast.FunctionDecl fn, List<?> args) {
            enterGuestCall("function " + fn.name());
            Env env = null;
            try {
                env = new Env(null, fn.nonLexical());
                for (int i = 0; i < fn.parameters().size(); i++) {
                    Ast.Param param = fn.parameters().get(i);
                    env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
                }
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return signal.value;
            } finally {
                if (env != null) env.release();
                exitGuestCall();
            }
        }

        private Object callMethod(OresObject receiver, Ast.MethodDecl method, List<?> args) {
            if (args.size() != method.parameters().size()) {
                throw new IllegalArgumentException("method " + method.name() + " arity mismatch");
            }
            List<?> capturedArgs = List.copyOf(args);
            if (method.async()) {
                return startAsync(() -> callMethodSync(receiver, method, capturedArgs));
            }
            return callMethodSync(receiver, method, capturedArgs);
        }

        private Object callMethodSync(OresObject receiver, Ast.MethodDecl method, List<?> args) {
            enterGuestCall("method " + method.name());
            Env env = null;
            try {
                env = new Env(null);
                if (!method.isStatic()) env.define("self", receiver, Ast.BindingKind.VAL);
                for (int i = 0; i < method.parameters().size(); i++) {
                    Ast.Param param = method.parameters().get(i);
                    env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
                }
                executeBlock(method.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return signal.value;
            } finally {
                if (env != null) env.release();
                exitGuestCall();
            }
        }

        private void enterGuestCall(String label) {
            context.schedulerSafepoint();
            int depth = guestCallDepth.get();
            if (depth >= MAX_GUEST_CALL_DEPTH) {
                throw new OresRuntimeException(
                        "Oreslang guest call-depth limit exceeded (" + MAX_GUEST_CALL_DEPTH + ") at " + label
                                + "; use iteration/tail-call lowering instead of relying on the JVM stack");
            }
            guestCallDepth.set(depth + 1);
        }

        private void exitGuestCall() {
            int depth = guestCallDepth.get() - 1;
            if (depth <= 0) guestCallDepth.remove();
            else guestCallDepth.set(depth);
        }

        private CompletionStage<Object> startAsync(java.util.concurrent.Callable<Object> task) {
            return context.submitAsync(task);
        }

        private void executeBlock(List<Ast.Stmt> statements, Env parent) {
            Env env = new Env(parent);
            ArrayDeque<DeferredAction> deferred = new ArrayDeque<>();
            RuntimeException bodyOutcome = null;
            try {
                for (Ast.Stmt stmt : statements) executeStatement(stmt, env, deferred);
            } catch (RuntimeException outcome) {
                bodyOutcome = outcome;
            }

            RuntimeException cleanupFailure = null;
            boolean forcedStop = bodyOutcome instanceof CancellationException
                    || bodyOutcome instanceof SecurityException;

            try {
                while (!deferred.isEmpty()) {
                    DeferredAction action = deferred.pop();
                    try {
                        if (!forcedStop) {
                            context.schedulerSafepoint();
                            eval(action.expression(), action.environment());
                        }
                    } catch (RuntimeException failure) {
                        if (cleanupFailure == null) cleanupFailure = failure;
                        else if (cleanupFailure != failure) cleanupFailure.addSuppressed(failure);
                        if (failure instanceof CancellationException || failure instanceof SecurityException) {
                            forcedStop = true;
                        }
                    } finally {
                        action.environment().release();
                    }
                }
            } finally {
                env.release();
            }

            if (bodyOutcome != null) {
                if (bodyOutcome instanceof ReturnSignal && cleanupFailure != null) {
                    throw cleanupFailure;
                }
                if (cleanupFailure != null && cleanupFailure != bodyOutcome) {
                    bodyOutcome.addSuppressed(cleanupFailure);
                }
                throw bodyOutcome;
            }
            if (cleanupFailure != null) throw cleanupFailure;
        }

        private void executeStatement(Ast.Stmt stmt, Env env, ArrayDeque<DeferredAction> deferred) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (binding.initializer() instanceof Ast.LambdaExpr) {
                    env.reserve(binding.name(), binding.kind());
                    Object value = eval(binding.initializer(), env);
                    env.initialize(binding.name(), value);
                    if (value instanceof LambdaValue lambda) {
                        lambda.bindRecursiveSelf(binding.name(), value);
                    }
                } else {
                    env.define(binding.name(), eval(binding.initializer(), env), binding.kind());
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
            if (stmt instanceof Ast.DeferStmt defer) {
                deferred.push(new DeferredAction(defer.expression(), env.snapshot()));
                return;
            }
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
                catch (OresRuntimeException failure) {
                    Env catchEnv = new Env(env);
                    try {
                        catchEnv.define(tried.errorName(), failure, Ast.BindingKind.VAL);
                        executeBlock(tried.catchBody(), catchEnv);
                    } finally {
                        catchEnv.release();
                    }
                } finally { executeBlock(tried.finallyBody(), env); }
                return;
            }
            if (stmt instanceof Ast.ForOfStmt loop) {
                Object iterable = eval(loop.iterable(), env);
                for (Object item : iterableValues(iterable)) {
                    context.schedulerSafepoint();
                    Env iteration = new Env(env);
                    try {
                        iteration.define(loop.bindingName(), item, loop.bindingKind());
                        executeBlock(loop.body(), iteration);
                    } finally {
                        iteration.release();
                    }
                }
                return;
            }
            if (stmt instanceof Ast.ForStmt loop) {
                Env loopEnv = new Env(env);
                try {
                    if (loop.initializer() != null) executeStatement(loop.initializer(), loopEnv, new ArrayDeque<DeferredAction>());
                    while (loop.condition() == null || truth(eval(loop.condition(), loopEnv))) {
                        context.schedulerSafepoint();
                        executeBlock(loop.body(), loopEnv);
                        if (loop.update() != null) eval(loop.update(), loopEnv);
                    }
                } finally {
                    loopEnv.release();
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
                if (name.name().equals("actor")) return new ActorFacade();
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
                if (module != null) return new ModuleFacade(module);
                Ast.ClassDecl klass = findClass(name.name());
                if (klass != null) return new ClassFacade(klass);
                Ast.FunctionDecl fn = findFunction(name.name());
                if (fn != null) return new NamedFunctionValue(fn);
                if (isOwnershipIntrinsic(name.name())) return ownershipIntrinsic(name.name());
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
                        if (object.readOnlyShared) {
                            throw new OresRuntimeException("cannot mutate a process.share_readonly class value");
                        }
                        if (!object.fields.containsKey(target.member())) throw new IllegalArgumentException("unknown field " + target.member());
                        object.fields.put(target.member(), value);
                        return value;
                    }
                    throw new IllegalArgumentException("member assignment requires a class instance");
                }
                if (assignment.target() instanceof Ast.IndexExpr target) {
                    Object receiver = eval(target.receiver(), env);
                    int index = checkedIndex(eval(target.index(), env));
                    if (receiver instanceof List<?> raw) {
                        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) raw;
                        try {
                            list.set(index, value);
                            return value;
                        } catch (IndexOutOfBoundsException bounds) {
                            throw new OresRuntimeException(
                                    "list index out of bounds: " + index,
                                    bounds);
                        } catch (UnsupportedOperationException readonly) {
                            throw new OresRuntimeException(
                                    "cannot mutate a read-only/shared list",
                                    readonly);
                        }
                    }
                    throw new OresRuntimeException("indexed assignment requires a mutable array/list");
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
                if (call.callee() instanceof Ast.MemberExpr methodCall) {
                    Object receiver = eval(methodCall.receiver(), env);
                    List<Object> args = call.arguments().stream().map(arg -> eval(arg, env)).toList();
                    if (receiver instanceof OresObject object) {
                        return invokeMethod(object, methodCall.member(), args);
                    }
                    if (receiver instanceof ClassFacade klass) {
                        return invokeStaticFunction(klass.klass(), methodCall.member(), args);
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
                int index = checkedIndex(eval(indexed.index(), env));
                try {
                    if (receiver instanceof List<?> list) return list.get(index);
                    if (receiver instanceof Object[] array) return array[index];
                } catch (IndexOutOfBoundsException bounds) {
                    throw new OresRuntimeException(
                            "index out of bounds: " + index,
                            bounds);
                }
                throw new OresRuntimeException("value is not indexable");
            }
            if (expr instanceof Ast.NewExpr created) {
                Ast.ClassDecl klass = findClass(created.type().name());
                if (klass == null) throw new IllegalArgumentException("unknown class " + created.type().name());
                List<Object> args = created.arguments().stream().map(arg -> eval(arg, env)).toList();
                List<Ast.FieldDecl> classFields = effectiveFields(klass, new LinkedHashSet<>());
                if (args.size() > classFields.size()) throw new IllegalArgumentException("too many constructor arguments for " + klass.name());
                LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                for (int i = 0; i < classFields.size(); i++) {
                    Ast.FieldDecl field = classFields.get(i);
                    Object value;
                    if (i < args.size()) value = args.get(i);
                    else if (field.initializer() != null) value = eval(field.initializer(), env);
                    else throw new IllegalArgumentException("missing constructor field " + klass.name() + "." + field.name());
                    fields.put(field.name(), value);
                }
                return new OresObject(klass, fields);
            }
            if (expr instanceof Ast.AwaitExpr awaited) {
                Object value = eval(awaited.expression(), env);
                if (!(value instanceof CompletionStage<?> stage)) {
                    throw new IllegalArgumentException(
                            "await requires an async/Future value, got "
                                    + (value == null ? "null" : value.getClass().getName()));
                }
                return awaitStage(stage);
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
                return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
            }
            if (expr instanceof Ast.LambdaExpr lambda) {
                boolean nonLexical = lambda.nonLexical() || env.descendantsNonLexical();
                Env captured = nonLexical ? null : env.snapshot();
                return new LambdaValue(lambda, captured, nonLexical);
            }
            throw new IllegalArgumentException("unsupported expression " + expr);
        }

        private int checkedIndex(Object value) {
            if (!(value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long)) {
                throw new OresRuntimeException("index must be an integer");
            }
            long raw = ((Number) value).longValue();
            try {
                return Math.toIntExact(raw);
            } catch (ArithmeticException outOfRange) {
                throw new OresRuntimeException(
                        "index is outside the supported collection range: " + raw,
                        outOfRange);
            }
        }

        private <T> T guestRuntimeBoundary(
                String api,
                java.util.function.Supplier<T> operation) {
            try {
                return operation.get();
            } catch (OresRuntimeException | SecurityException | CancellationException failure) {
                throw failure;
            } catch (IllegalArgumentException | IllegalStateException failure) {
                String detail = failure.getMessage();
                throw new OresRuntimeException(
                        detail == null || detail.isBlank() ? api + " failed" : api + ": " + detail,
                        failure);
            }
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
                    case "spawn" -> (Invokable) actor::spawn;
                    case "singleton" -> (Invokable) actor::singleton;
                    case "send" -> (Invokable) actor::send;
                    case "stop" -> (Invokable) actor::stop;
                    case "join" -> (Invokable) actor::join;
                    case "status" -> (Invokable) actor::status;
                    case "gc" -> (Invokable) actor::gc;
                    case "monitor" -> (Invokable) actor::monitor;
                    case "demonitor" -> (Invokable) actor::demonitor;
                    case "self" -> actor.self();
                    default -> throw new IllegalArgumentException("unknown actor member " + name);
                };
            }
            if (receiver instanceof GuestActorRef ref && name.equals("id")) {
                return ref.delegate().id().toString();
            }
            if (receiver instanceof ActorRuntime.ActorRef<?> ref && name.equals("id")) {
                return ref.id().toString();
            }
            if (receiver instanceof GuestMonitorRef ref) {
                return switch (name) {
                    case "id" -> ref.delegate().value().toString();
                    case "target_id" -> ref.delegate().target().toString();
                    default -> throw new IllegalArgumentException("unknown monitor member " + name);
                };
            }
            if (receiver instanceof ActorRuntime.MonitorRef ref) {
                return switch (name) {
                    case "id" -> ref.value().toString();
                    case "target_id" -> ref.target().toString();
                    default -> throw new IllegalArgumentException("unknown monitor member " + name);
                };
            }
            if (receiver instanceof ModuleFacade namespace) return moduleMember(namespace.module, name);
            if (receiver instanceof ClassFacade klass) {
                List<Ast.MethodDecl> functions = findStaticFunctionsByName(klass.klass(), name, new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    return (Invokable) args -> callStaticFunction(klass.klass(), fn, args);
                }
                if (functions.size() > 1) throw new IllegalArgumentException("overloaded static function " + klass.klass().name() + "." + name + " must be called so arity can select it");
                throw new IllegalArgumentException("unknown static member " + klass.klass().name() + "." + name);
            }
            if (receiver instanceof OresObject object) {
                if (object.fields.containsKey(name)) return object.fields.get(name);
                return new BoundMethod(object, name);
            }
            if (receiver instanceof Map<?, ?> map) {
                if (!map.containsKey(name)) throw new IllegalArgumentException("unknown obj member " + name);
                return map.get(name);
            }
            throw new IllegalArgumentException("cannot access member '" + name + "' on " + receiver);
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
            if (args.size() != fn.parameters().size()) throw new IllegalArgumentException("static function " + fn.name() + " arity mismatch");
            Env env = new Env(null);
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return signal.value;
            } finally {
                env.release();
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

            private BoundMethod(OresObject receiver, String methodName) {
                this.receiver = receiver;
                this.methodName = methodName;
            }

            @Override public Object call(List<Object> arguments) {
                return invokeMethod(receiver, methodName, arguments);
            }
        }

        private Object moduleMember(Ast.ModuleDecl module, String name) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass && klass.name().equals(name)) {
                    return new ClassFacade(klass);
                }
                if (decl instanceof Ast.FunctionDecl fn && fn.name().equals(name) && fn.visibility() == Ast.Visibility.PUBLIC) {
                    return new NamedFunctionValue(fn);
                }
                if (decl instanceof Ast.FieldDecl field && field.name().equals(name) && field.visibility() == Ast.Visibility.PUBLIC) {
                    if (field.initializer() == null) throw new IllegalArgumentException("module field has no initializer: " + module.name() + "." + name);
                    return eval(field.initializer(), new Env(null));
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

        private String actorParameterContract(List<Ast.Param> parameters) {
            List<String> parts = new ArrayList<>(parameters.size());
            for (Ast.Param parameter : parameters) {
                parts.add((parameter.structural() ? "structural:" : "nominal:")
                        + parameter.type());
            }
            return parts.toString();
        }

        private final class NamedFunctionValue implements Invokable {
            private final Ast.FunctionDecl function;

            private NamedFunctionValue(Ast.FunctionDecl function) {
                this.function = function;
            }

            @Override
            public Object call(List<Object> arguments) {
                return callFunction(function, arguments);
            }

            @Override
            public boolean actorEntrySafe() {
                return true;
            }

            @Override
            public String actorContractKey() {
                return actorParameterContract(function.parameters()) + "=>" + function.returnType();
            }

            @Override
            public Ast.TypeRef actorMessageType() {
                return function.parameters().isEmpty() ? null : function.parameters().getFirst().type();
            }
        }

        private final class LambdaValue implements Invokable {
            private final Ast.LambdaExpr lambda;
            private final Env captured;
            private final boolean nonLexical;

            private LambdaValue(Ast.LambdaExpr lambda, Env captured, boolean nonLexical) {
                this.lambda = lambda;
                this.captured = captured;
                this.nonLexical = nonLexical;
            }

            @Override
            public Object call(List<Object> arguments) {
                if (arguments.size() != lambda.parameters().size()) {
                    throw new IllegalArgumentException("lambda arity mismatch");
                }
                enterGuestCall("lambda");
                Env local = null;
                try {
                    local = new Env(captured, nonLexical);
                    for (int i = 0; i < lambda.parameters().size(); i++) {
                        Ast.Param param = lambda.parameters().get(i);
                        local.define(param.name(), arguments.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
                    }
                    if (lambda.expressionBody() != null) return eval(lambda.expressionBody(), local);
                    executeBlock(lambda.blockBody(), local);
                    return null;
                } catch (ReturnSignal signal) {
                    return signal.value;
                } finally {
                    if (local != null) local.release();
                    exitGuestCall();
                }
            }

            private void bindRecursiveSelf(String name, Object value) {
                if (captured != null) captured.initializeReservedCapture(name, value);
            }

            @Override
            public boolean actorEntrySafe() {
                return nonLexical;
            }

            @Override
            public String actorContractKey() {
                return actorParameterContract(lambda.parameters());
            }

            @Override
            public Ast.TypeRef actorMessageType() {
                return lambda.parameters().isEmpty() ? null : lambda.parameters().getFirst().type();
            }
        }

        private final class ActorFacade {
            private Object spawn(List<Object> args) {
                context.requireCapability(IsolatePolicy.Capability.ACTOR_SPAWN, "actor.spawn");
                if (args.size() != 1 && args.size() != 2) {
                    throw new IllegalArgumentException("actor.spawn expects handler or handler, initial_state");
                }
                Invokable handler = actorHandler(args.getFirst());
                boolean stateful = args.size() == 2;
                Object initialArgument = stateful ? args.get(1) : null;
                long initialStateBytes = stateful ? estimateActorStateBytes(initialArgument) : 0L;
                Object initialState = stateful ? actorOwnedInitialState(initialArgument) : null;
                ActorRuntime.ActorRef<Object> ref = guestRuntimeBoundary(
                        "actor.spawn",
                        () -> context.actors().spawnOwned(
                                context.isolatePolicy(),
                                initialStateBytes,
                                actorProtocol(handler),
                                () -> statefulBehavior(
                                        handler,
                                        stateful,
                                        initialState,
                                        initialStateBytes,
                                        initialArgument instanceof ActorRuntime.Shared<?>)));
                return new GuestActorRef(ref);
            }

            private Object singleton(List<Object> args) {
                context.requireCapability(IsolatePolicy.Capability.ACTOR_SPAWN, "actor.singleton");
                if (args.size() != 2 && args.size() != 3) {
                    throw new IllegalArgumentException("actor.singleton expects name, handler or name, handler, initial_state");
                }
                if (!(args.getFirst() instanceof String name)) {
                    throw new IllegalArgumentException("actor.singleton first argument must be a string name");
                }
                Invokable handler = actorHandler(args.get(1));
                boolean stateful = args.size() == 3;
                Object initialArgument = stateful ? args.get(2) : null;
                long initialStateBytes = stateful ? estimateActorStateBytes(initialArgument) : 0L;
                Object initialState = stateful ? actorOwnedInitialState(initialArgument) : null;
                ActorRuntime.ActorRef<Object> ref = guestRuntimeBoundary(
                        "actor.singleton",
                        () -> context.actors().spawnSingletonOwned(
                                name,
                                initialStateBytes,
                                singletonContract(handler, stateful, initialArgument),
                                actorProtocol(handler),
                                () -> statefulBehavior(
                                        handler,
                                        stateful,
                                        initialState,
                                        initialStateBytes,
                                        initialArgument instanceof ActorRuntime.Shared<?>)));
                return new GuestActorRef(ref);
            }

            private ActorRuntime.Protocol<Object> actorProtocol(Invokable handler) {
                Ast.TypeRef messageType = handler.actorMessageType();
                String contract = messageType == null ? "actor-message:dynamic" : "actor-message:" + messageType;
                if (messageType == null) return ActorRuntime.Protocol.any(contract);
                return new ActorRuntime.Protocol<>(
                        contract,
                        value -> runtimeActorMessageMatches(messageType, value));
            }

            private boolean runtimeActorMessageMatches(Ast.TypeRef type, Object value) {
                if (type == null || type.inferArguments() || type.name().equals("$infer$")) return true;
                if (type.isBorrow()) return runtimeActorMessageMatches(type.borrowedTarget(), value);
                if (type.isStringLiteral()) {
                    return value instanceof String text && text.equals(type.stringLiteralValue());
                }

                return switch (type.name()) {
                    case "i64", "int" -> value instanceof Byte
                            || value instanceof Short
                            || value instanceof Integer
                            || value instanceof Long;
                    case "f64", "float" -> value instanceof Byte
                            || value instanceof Short
                            || value instanceof Integer
                            || value instanceof Long
                            || value instanceof Float
                            || value instanceof Double;
                    case "complex128", "complex" -> value instanceof Number || value instanceof Complex;
                    case "i8", "i16", "i32", "u8", "u16", "u32", "u64",
                            "uint", "bigint", "f32", "decimal", "complex64" -> false;
                    case "bool", "Bool" -> value instanceof Boolean;
                    case "str", "string", "String" -> value instanceof String;
                    case "Array", "List" -> {
                        if (!(value instanceof List<?> list)) yield false;
                        if (type.arguments().size() != 1 || type.arguments().getFirst().inferArguments()) yield true;
                        Ast.TypeRef element = type.arguments().getFirst();
                        boolean matches = true;
                        for (Object item : list) {
                            if (!runtimeActorMessageMatches(element, item)) {
                                matches = false;
                                break;
                            }
                        }
                        yield matches;
                    }
                    case "Option" -> {
                        if (!(value instanceof OptionValue option)) yield false;
                        if (!option.present()) yield true;
                        if (type.arguments().size() != 1) yield true;
                        yield runtimeActorMessageMatches(type.arguments().getFirst(), option.value());
                    }
                    case "ActorRef" -> value instanceof ActorRuntime.ActorRef<?> || value instanceof GuestActorRef;
                    case "MonitorRef" -> value instanceof ActorRuntime.MonitorRef || value instanceof GuestMonitorRef;
                    case "Shared" -> value instanceof ActorRuntime.Shared<?>;
                    default -> {
                        if (value instanceof OresObject object) {
                            Ast.ClassDecl declared = findClass(type.name());
                            if (declared != null) {
                                yield runtimeClassAssignable(object.klass, declared.name(), new LinkedHashSet<>());
                            }
                        }
                        // Interfaces, structs, aliases, generics, and imported
                        // runtime values remain statically checked. The runtime
                        // protocol must not invent a stricter rule than the
                        // language type checker can justify.
                        yield true;
                    }
                };
            }

            private boolean runtimeClassAssignable(
                    Ast.ClassDecl actual,
                    String expectedName,
                    Set<Ast.ClassDecl> seen) {
                if (!seen.add(actual)) return false;
                if (actual.name().equals(expectedName)) return true;
                for (Ast.TypeRef parentRef : actual.parents()) {
                    if (parentRef.name().equals(expectedName)) return true;
                    Ast.ClassDecl parent = findClass(parentRef.name());
                    if (parent != null && runtimeClassAssignable(parent, expectedName, seen)) return true;
                }
                return false;
            }

            private ActorRuntime.Behavior<Object> statefulBehavior(
                    Invokable handler,
                    boolean stateful,
                    Object initialState,
                    long initialStateBytes,
                    boolean initialStateIsSharedBacking) {
                return new ActorRuntime.Behavior<>() {
                    private Object state = initialState;
                    private long stateCharge = initialStateBytes;
                    private boolean stateIsSharedBacking = initialStateIsSharedBacking;

                    @Override
                    public void onMessage(Object message, ActorRuntime.ActorContext<Object> actorContext) {
                        if (stateful) {
                            Object previousState = state;
                            Object nextState = handler.call(List.of(message, previousState));
                            boolean preservesSharedBacking =
                                    stateIsSharedBacking && nextState == previousState;
                            long nextCharge = preservesSharedBacking
                                    ? stateCharge
                                    : estimateActorStateBytes(nextState);
                            actorContext.replaceOwnedStateBytes(nextCharge);
                            state = nextState;
                            stateCharge = nextCharge;
                            stateIsSharedBacking = preservesSharedBacking;
                        } else {
                            handler.call(List.of(message));
                        }
                    }
                };
            }

            private Object actorOwnedInitialState(Object value) {
                if (value instanceof ActorRuntime.Shared<?> shared) {
                    return context.actors().sharedValueForActorState(shared);
                }
                return context.actors().freezeForActorState(value);
            }

            private long estimateActorStateBytes(Object value) {
                return estimateActorStateBytes(value, new IdentityHashMap<>(), 0);
            }

            private long estimateActorStateBytes(
                    Object value,
                    IdentityHashMap<Object, Boolean> seen,
                    int depth) {
                if (depth > 256) return Long.MAX_VALUE;
                if (value == null) return 8L;
                if (value instanceof String || value instanceof Number || value instanceof Boolean
                        || value instanceof Character || value instanceof Enum<?> || value instanceof java.util.UUID
                        || value instanceof ActorRuntime.ActorId || value instanceof ActorRuntime.MonitorRef
                        || value instanceof ActorRuntime.Shared<?> || value instanceof ActorRuntime.ActorRef<?>
                        || value instanceof GuestActorRef || value instanceof GuestMonitorRef
                        || value instanceof Complex) {
                    return ActorRuntime.estimatedFrozenBytes(value);
                }
                if (seen.put(value, Boolean.TRUE) != null) return 16L;

                long total = 32L;
                if (value instanceof OresObject object) {
                    total = 64L;
                    for (Map.Entry<String, Object> field : object.fields.entrySet()) {
                        total = safeActorStateAdd(total, ActorRuntime.estimatedFrozenBytes(field.getKey()));
                        total = safeActorStateAdd(
                                total,
                                estimateActorStateBytes(field.getValue(), seen, depth + 1));
                    }
                    return total;
                }
                if (value instanceof OptionValue option) {
                    return safeActorStateAdd(
                            24L,
                            estimateActorStateBytes(option.value(), seen, depth + 1));
                }
                if (value instanceof List<?> list) {
                    total = 24L;
                    for (Object item : list) {
                        total = safeActorStateAdd(
                                total,
                                estimateActorStateBytes(item, seen, depth + 1));
                    }
                    return total;
                }
                if (value instanceof Set<?> set) {
                    total = 48L;
                    for (Object item : set) {
                        total = safeActorStateAdd(
                                total,
                                estimateActorStateBytes(item, seen, depth + 1));
                    }
                    return total;
                }
                if (value instanceof Map<?, ?> map) {
                    total = 64L;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        total = safeActorStateAdd(
                                total,
                                estimateActorStateBytes(entry.getKey(), seen, depth + 1));
                        total = safeActorStateAdd(
                                total,
                                estimateActorStateBytes(entry.getValue(), seen, depth + 1));
                    }
                    return total;
                }
                if (value instanceof Object[] array) {
                    total = 24L;
                    for (Object item : array) {
                        total = safeActorStateAdd(
                                total,
                                estimateActorStateBytes(item, seen, depth + 1));
                    }
                    return total;
                }
                return ActorRuntime.estimatedFrozenBytes(value);
            }

            private long safeActorStateAdd(long left, long right) {
                if (left == Long.MAX_VALUE || right == Long.MAX_VALUE || right > Long.MAX_VALUE - left) {
                    return Long.MAX_VALUE;
                }
                return left + right;
            }

            private Object send(List<Object> args) {
                context.requireCapability(IsolatePolicy.Capability.ACTOR_SEND, "actor.send");
                requireTwo(args, "actor.send");
                return guestRuntimeBoundary("actor.send", () -> {
                    context.actors().send(actorRef(args.getFirst()), args.get(1));
                    return null;
                });
            }

            private Object stop(List<Object> args) {
                context.requireCapability(IsolatePolicy.Capability.ACTOR_CONTROL, "actor.stop");
                requireOne(args, "actor.stop");
                return guestRuntimeBoundary(
                        "actor.stop",
                        () -> context.actors().stop(actorRef(args.getFirst())));
            }

            private Object join(List<Object> args) {
                context.requireCapability(IsolatePolicy.Capability.ACTOR_CONTROL, "actor.join");
                requireOne(args, "actor.join");
                return guestRuntimeBoundary(
                        "actor.join",
                        () -> context.actors().join(
                                actorRef(args.getFirst()),
                                context.isolatePolicy().maxWallTime()));
            }

            private Object status(List<Object> args) {
                context.requireCapability(IsolatePolicy.Capability.ACTOR_CONTROL, "actor.status");
                requireOne(args, "actor.status");
                return guestRuntimeBoundary(
                        "actor.status",
                        () -> context.actors().snapshot(actorRef(args.getFirst())).asMap());
            }

            private Object gc(List<Object> args) {
                context.requireCapability(IsolatePolicy.Capability.GC_CONTROL, "actor.gc");
                requireZero(args, "actor.gc");
                return context.gc().collectActor();
            }

            private Object monitor(List<Object> args) {
                context.requireCapability(IsolatePolicy.Capability.ACTOR_CONTROL, "actor.monitor");
                if (args.size() != 1 && args.size() != 2) {
                    throw new IllegalArgumentException("actor.monitor expects target or target, watcher");
                }
                ActorRuntime.ActorRef<Object> target = actorRef(args.getFirst());
                ActorRuntime.ActorRef<Object> watcher = args.size() == 2
                        ? actorRef(args.get(1))
                        : context.actors().<Object>currentActorRef()
                            .orElseThrow(() -> new OresRuntimeException(
                                    "actor.monitor(target) must run inside an actor; use actor.monitor(target, watcher) from control code"));
                return guestRuntimeBoundary(
                        "actor.monitor",
                        () -> new GuestMonitorRef(context.actors().monitor(watcher, target)));
            }

            private Object demonitor(List<Object> args) {
                context.requireCapability(IsolatePolicy.Capability.ACTOR_CONTROL, "actor.demonitor");
                if (args.size() != 1 && args.size() != 2) {
                    throw new IllegalArgumentException("actor.demonitor expects monitor or monitor, watcher");
                }
                ActorRuntime.MonitorRef monitor = monitorRef(args.getFirst());
                ActorRuntime.ActorRef<Object> watcher = args.size() == 2
                        ? actorRef(args.get(1))
                        : context.actors().<Object>currentActorRef()
                            .orElseThrow(() -> new OresRuntimeException(
                                    "actor.demonitor(monitor) must run inside an actor; use actor.demonitor(monitor, watcher) from control code"));
                return guestRuntimeBoundary(
                        "actor.demonitor",
                        () -> context.actors().demonitor(watcher, monitor));
            }

            private Object self() {
                context.requireCapability(IsolatePolicy.Capability.ACTOR_SEND, "actor.self");
                ActorRuntime.ActorRef<Object> ref = context.actors().<Object>currentActorRef()
                        .orElseThrow(() -> new OresRuntimeException(
                                "actor.self is only available while handling an actor message"));
                return new GuestActorRef(ref);
            }

            private String singletonContract(
                    Invokable handler,
                    boolean stateful,
                    Object initialArgument) {
                String protocol = handler.actorContractKey();
                if (protocol == null || protocol.isBlank()) {
                    throw new IllegalArgumentException("singleton actor handler has no stable runtime contract");
                }
                return protocol
                        + "|mode=" + (stateful ? "stateful" : "stateless")
                        + (stateful ? "|state=" + runtimeStateTag(initialArgument) : "");
            }

            private String runtimeStateTag(Object value) {
                if (value instanceof ActorRuntime.Shared<?> shared) {
                    return "shared:" + runtimeStateTag(shared.value());
                }
                if (value == null) return "null";
                if (value instanceof OresObject object) return "class:" + object.klass.name();
                if (value instanceof List<?>) return "list";
                if (value instanceof Map<?, ?>) return "map";
                if (value instanceof Set<?>) return "set";
                if (value.getClass().isArray()) return "array:" + value.getClass().getComponentType().getTypeName();
                return value.getClass().getName();
            }

            private Invokable actorHandler(Object value) {
                if (!(value instanceof Invokable invokable)) {
                    throw new IllegalArgumentException("actor entrypoint must be a named function or nlex lambda");
                }
                if (!invokable.actorEntrySafe()) {
                    throw new IllegalArgumentException("actor lambda entrypoints must be nlex so actor heaps cannot capture caller-local mutable state");
                }
                return invokable;
            }

            @SuppressWarnings("unchecked")
            private ActorRuntime.ActorRef<Object> actorRef(Object value) {
                if (value instanceof GuestActorRef ref) return ref.delegate();
                if (value instanceof ActorRuntime.ActorRef<?> ref) {
                    return (ActorRuntime.ActorRef<Object>) ref;
                }
                throw new IllegalArgumentException("expected ActorRef");
            }

            private ActorRuntime.MonitorRef monitorRef(Object value) {
                if (value instanceof GuestMonitorRef ref) return ref.delegate();
                if (value instanceof ActorRuntime.MonitorRef ref) return ref;
                throw new IllegalArgumentException("expected MonitorRef");
            }
        }

        private record GuestActorRef(ActorRuntime.ActorRef<Object> delegate) implements ActorRuntime.Sendable {
            @Override
            public Object freezeForSend(ActorRuntime.SendFreezer freezer, boolean readOnlyShared) {
                return delegate;
            }

            @Override
            public String toString() {
                return delegate.toString();
            }
        }

        private record GuestMonitorRef(ActorRuntime.MonitorRef delegate) implements ActorRuntime.Sendable {
            @Override
            public Object freezeForSend(ActorRuntime.SendFreezer freezer, boolean readOnlyShared) {
                return delegate;
            }

            @Override
            public String toString() {
                return "MonitorRef[" + delegate.value() + "]";
            }
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

        private boolean isOwnershipIntrinsic(String name) {
            return name.equals("borrow") || name.equals("borrow_mut")
                    || name.equals("take") || name.equals("copy") || name.equals("share");
        }

        private Invokable ownershipIntrinsic(String name) {
            return args -> {
                requireOne(args, name);
                Object value = args.getFirst();
                return switch (name) {
                    case "borrow", "borrow_mut", "take", "copy" -> value;
                    case "share" -> {
                        context.requireCapability(IsolatePolicy.Capability.ACTOR_SHARE_READONLY, "share");
                        yield guestRuntimeBoundary(
                                "share",
                                () -> context.actors().shareReadonly(value));
                    }
                    default -> throw new IllegalArgumentException("unknown ownership intrinsic " + name);
                };
            };
        }

        private Object awaitStage(CompletionStage<?> stage) {
            try {
                return stage.toCompletableFuture().get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancellation = new CancellationException("await interrupted");
                cancellation.initCause(interrupted);
                throw cancellation;
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("awaited operation failed", cause);
            }
        }

        private Object binary(String op, Object left, Object right) {
            return switch (op) {
                case "+" -> add(left, right); case "-" -> numeric(left, right, '-'); case "*" -> numeric(left, right, '*');
                case "/" -> numeric(left, right, '/'); case "%" -> numeric(left, right, '%');
                case "==" -> equalValues(left, right); case "!=" -> !equalValues(left, right);
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
            boolean integral = isIntegral(a) && isIntegral(b);
            if (integral) {
                long x = a.longValue(), y = b.longValue();
                return switch (op) {
                    case '+' -> {
                        try {
                            yield Math.addExact(x, y);
                        } catch (ArithmeticException overflow) {
                            throw new OresRuntimeException(
                                    "Oreslang integer overflow for " + x + " + " + y,
                                    overflow);
                        }
                    }
                    case '-' -> {
                        try {
                            yield Math.subtractExact(x, y);
                        } catch (ArithmeticException overflow) {
                            throw new OresRuntimeException(
                                    "Oreslang integer overflow for " + x + " - " + y,
                                    overflow);
                        }
                    }
                    case '*' -> {
                        try {
                            yield Math.multiplyExact(x, y);
                        } catch (ArithmeticException overflow) {
                            throw new OresRuntimeException(
                                    "Oreslang integer overflow for " + x + " * " + y,
                                    overflow);
                        }
                    }
                    case '/' -> {
                        if (y == 0L) throw new OresRuntimeException("Oreslang integer division by zero");
                        if (x == Long.MIN_VALUE && y == -1L) {
                            throw new OresRuntimeException("Oreslang integer overflow for " + x + " / " + y);
                        }
                        yield x / y;
                    }
                    case '%' -> {
                        if (y == 0L) throw new OresRuntimeException("Oreslang integer remainder by zero");
                        yield x % y;
                    }
                    default -> throw new IllegalArgumentException("bad numeric operator");
                };
            }
            double x = a.doubleValue(), y = b.doubleValue();
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new OresRuntimeException("non-finite floating-point operands are not valid ordinary Oreslang numbers");
            }
            if (op == '/' && y == 0.0d) {
                throw new OresRuntimeException("Oreslang floating-point division by zero");
            }
            if (op == '%' && y == 0.0d) {
                throw new OresRuntimeException("Oreslang floating-point remainder by zero");
            }
            double result = switch (op) {
                case '+' -> x + y;
                case '-' -> x - y;
                case '*' -> x * y;
                case '/' -> x / y;
                case '%' -> x % y;
                default -> throw new IllegalArgumentException("bad numeric operator");
            };
            if (!Double.isFinite(result)) {
                throw new OresRuntimeException(
                        "Oreslang floating-point operation produced a non-finite result: "
                                + x + " " + op + " " + y);
            }
            return result;
        }

        private Object negate(Object value) {
            if (value instanceof Complex c) return new Complex(-c.real, -c.imaginary);
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                long integer = ((Number) value).longValue();
                try {
                    return Math.negateExact(integer);
                } catch (ArithmeticException overflow) {
                    throw new OresRuntimeException(
                            "Oreslang integer overflow for unary - on " + integer,
                            overflow);
                }
            }
            if (value instanceof Number number) {
                double result = -number.doubleValue();
                if (!Double.isFinite(result)) {
                    throw new OresRuntimeException("non-finite floating-point values are not valid ordinary Oreslang numbers");
                }
                return result;
            }
            throw new IllegalArgumentException("unary - requires a number");
        }

        private boolean equalValues(Object left, Object right) {
            if (left == right) return true;
            if (left == null || right == null) return false;

            if ((left instanceof Number || left instanceof Complex)
                    && (right instanceof Number || right instanceof Complex)) {
                if (left instanceof Complex || right instanceof Complex) {
                    Complex a = asComplex(left);
                    Complex b = asComplex(right);
                    return Double.compare(a.real, b.real) == 0
                            && Double.compare(a.imaginary, b.imaginary) == 0;
                }
                return compareNumbers((Number) left, (Number) right) == 0;
            }

            if (left instanceof String a && right instanceof String b) return a.equals(b);
            if (left instanceof Boolean a && right instanceof Boolean b) return a.equals(b);
            if (left instanceof Character a && right instanceof Character b) return a.equals(b);

            if (left instanceof OptionValue a && right instanceof OptionValue b) {
                if (a.present != b.present) return false;
                return !a.present || equalValues(a.value, b.value);
            }

            if (left instanceof List<?> a && right instanceof List<?> b) {
                if (a.size() != b.size()) return false;
                for (int i = 0; i < a.size(); i++) {
                    if (!equalValues(a.get(i), b.get(i))) return false;
                }
                return true;
            }

            if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
                if (!a.keySet().equals(b.keySet())) return false;
                for (Object key : a.keySet()) {
                    if (!equalValues(a.get(key), b.get(key))) return false;
                }
                return true;
            }

            // Reference/capability-like guest values require an explicit
            // identity/equality operation and therefore never fall through to
            // JVM Object.equals() semantics.
            return false;
        }

        private int compareNumbers(Number left, Number right) {
            return exactDecimal(left).compareTo(exactDecimal(right));
        }

        private BigDecimal exactDecimal(Number value) {
            if (value instanceof BigDecimal decimal) return decimal;
            if (value instanceof BigInteger integer) return new BigDecimal(integer);
            if (isIntegral(value)) return BigDecimal.valueOf(value.longValue());
            double floating = value.doubleValue();
            if (!Double.isFinite(floating)) {
                throw new OresRuntimeException("non-finite floating-point values are not valid ordinary Oreslang numbers");
            }
            return BigDecimal.valueOf(floating);
        }

        private int compare(Object left, Object right) {
            if (left instanceof Number a && right instanceof Number b) return compareNumbers(a, b);
            if (left instanceof String a && right instanceof String b) return a.compareTo(b);
            throw new IllegalArgumentException("values are not comparable");
        }

        private boolean truth(Object value) { if (value instanceof Boolean b) return b; throw new IllegalArgumentException("condition must be bool"); }
        private boolean isIntegral(Number value) { return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long; }
        private Complex asComplex(Object value) { if (value instanceof Complex c) return c; if (value instanceof Number n) return new Complex(n.doubleValue(),0); throw new IllegalArgumentException("value is not numeric"); }
        private List<?> asSequence(Object value) { if (value instanceof List<?> l) return l; if (value instanceof Object[] a) return List.of(a); throw new IllegalArgumentException("value is not destructurable"); }
        private String display(Object value) { return value instanceof Complex c ? c.toString() : String.valueOf(value); }
    }

    private record DeferredAction(Ast.Expr expression, Env environment) { }

    @FunctionalInterface
    private interface Invokable {
        Object call(List<Object> arguments);
        default boolean actorEntrySafe() { return false; }
        default String actorContractKey() { return null; }
        default Ast.TypeRef actorMessageType() { return null; }
    }

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
        private boolean initializeReservedCapture(String name, Object value) {
            Slot slot = slots.get(name);
            if (slot != null && slot.value == MISSING) {
                slot.value = value;
                return true;
            }
            return parent != null && parent.initializeReservedCapture(name, value);
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
            for (Map.Entry<String, Slot> entry : slots.entrySet()) {
                Slot slot = entry.getValue();
                cp.slots.put(entry.getKey(), new Slot(slot.value, slot.kind));
            }
            return cp;
        }
        private void release() {
            for (Slot slot : slots.values()) slot.value = MISSING;
            slots.clear();
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

    private record Complex(double real, double imaginary) implements ActorRuntime.Sendable {
        private Complex {
            if (!Double.isFinite(real) || !Double.isFinite(imaginary)) {
                throw new OresRuntimeException("complex values must have finite real and imaginary components");
            }
        }
        private Complex add(Complex o){return new Complex(real+o.real,imaginary+o.imaginary);}
        private Complex sub(Complex o){return new Complex(real-o.real,imaginary-o.imaginary);}
        private Complex mul(Complex o){return new Complex(real*o.real-imaginary*o.imaginary,real*o.imaginary+imaginary*o.real);}
        private Complex div(Complex o){
            double d=o.real*o.real+o.imaginary*o.imaginary;
            if (d == 0.0d || !Double.isFinite(d)) {
                throw new OresRuntimeException("complex division requires a finite non-zero divisor");
            }
            return new Complex((real*o.real+imaginary*o.imaginary)/d,(imaginary*o.real-real*o.imaginary)/d);
        }
        @Override public Object freezeForSend(ActorRuntime.SendFreezer freezer, boolean readOnlyShared){return new Complex(real,imaginary);}
        @Override public long estimatedSendBytes(ActorRuntime.SendSizer sizer){return 32L;}
        @Override public String toString(){return real+(imaginary<0?"":"+")+imaginary+"i";}
    }

    private static final class OresObject implements ActorRuntime.Sendable {
        private final Ast.ClassDecl klass;
        private final Map<String,Object> fields;
        private final boolean readOnlyShared;

        private OresObject(Ast.ClassDecl klass, Map<String,Object> fields){
            this(klass, fields, false);
        }

        private OresObject(Ast.ClassDecl klass, Map<String,Object> fields, boolean readOnlyShared){
            this.klass=klass;
            this.fields=fields;
            this.readOnlyShared=readOnlyShared;
        }

        @Override
        public Object freezeForSend(ActorRuntime.SendFreezer freezer, boolean readOnlyShared) {
            LinkedHashMap<String,Object> copied = new LinkedHashMap<>();
            for (Map.Entry<String,Object> field : fields.entrySet()) {
                copied.put(field.getKey(), freezer.freeze(field.getValue()));
            }
            Map<String,Object> destination = readOnlyShared
                    ? java.util.Collections.unmodifiableMap(new LinkedHashMap<>(copied))
                    : copied;
            return new OresObject(klass, destination, readOnlyShared);
        }

        @Override
        public long estimatedSendBytes(ActorRuntime.SendSizer sizer) {
            long total = 64L;
            for (Map.Entry<String,Object> field : fields.entrySet()) {
                total = saturatingAdd(total, sizer.estimatedBytes(field.getKey()));
                total = saturatingAdd(total, sizer.estimatedBytes(field.getValue()));
            }
            return total;
        }

        @Override public String toString(){return klass.name()+fields;}
    }

    private record ModuleFacade(Ast.ModuleDecl module) { }
    private record ClassFacade(Ast.ClassDecl klass) { }

    private record OptionValue(boolean present, Object value) implements ActorRuntime.Sendable {
        @Override
        public Object freezeForSend(ActorRuntime.SendFreezer freezer, boolean readOnlyShared) {
            return present ? new OptionValue(true, freezer.freeze(value)) : new OptionValue(false, null);
        }

        @Override
        public long estimatedSendBytes(ActorRuntime.SendSizer sizer) {
            return present ? saturatingAdd(24L, sizer.estimatedBytes(value)) : 24L;
        }

        @Override public String toString(){return present ? "Some(" + value + ")" : "None";}
    }
    private record StdioFacade(OresContext context) {
        private Object print(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.print");requireOne(args,"stdio.print");context.output().print(String.valueOf(args.getFirst()));context.output().flush();return null;}
        private Object println(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.println");requireOne(args,"stdio.println");context.output().println(String.valueOf(args.getFirst()));return null;}
    }
    private record StdoutFacade(OresContext context) {
        private Object write(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.stdout.write");requireOne(args,"stdio.stdout.write");context.output().print(String.valueOf(args.getFirst()));context.output().flush();return null;}
        private Object println(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.stdout.println");requireOne(args,"stdio.stdout.println");context.output().println(String.valueOf(args.getFirst()));return null;}
    }
    private final class ProcessFacade {
        private final OresContext context;
        private ProcessFacade(OresContext context){this.context=context;}
        private String contextId(){context.requireCapability(IsolatePolicy.Capability.PROCESS_INFO,"process.context_id");return context.contextId().toString();}
        private Map<String,Object> descriptor(){context.requireCapability(IsolatePolicy.Capability.PROCESS_INFO,"process.descriptor");return context.processDescriptor();}
        private Object shareReadonly(List<Object> args){
            context.requireCapability(IsolatePolicy.Capability.ACTOR_SHARE_READONLY,"process.share_readonly");
            requireOne(args,"process.share_readonly");
            return guestRuntimeBoundary(
                    "process.share_readonly",
                    () -> context.actors().shareReadonly(args.getFirst()));
        }
        private Object gc(List<Object> args){context.requireCapability(IsolatePolicy.Capability.GC_CONTROL,"process.gc");requireZero(args,"process.gc");return context.gc().collect();}
    }
    private static long saturatingAdd(long left, long right) {
        if (left == Long.MAX_VALUE || right == Long.MAX_VALUE || right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static void requireZero(List<Object> args,String name){if(!args.isEmpty())throw new IllegalArgumentException(name+" expects no arguments");}
    private static void requireOne(List<Object> args,String name){if(args.size()!=1)throw new IllegalArgumentException(name+" expects one argument");}
    private static void requireTwo(List<Object> args,String name){if(args.size()!=2)throw new IllegalArgumentException(name+" expects two arguments");}
}
