package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed-world build optimizer.
 *
 * The pass deliberately runs after semantic checking and before backend
 * lowering. It folds build-time constants and branches, then computes
 * declaration reachability from executable entry points (or the public API for
 * library builds). Function references remain symbolic in the AST, so
 * references such as A.foo can keep A.foo alive without retaining B.foo from a
 * folded conditional.
 */
public final class TreeShaker {
    private static final Object UNKNOWN = new Object();

    private TreeShaker() { }

    public static Result shake(Ast.Program checkedProgram, BuildOptions options) {
        Objects.requireNonNull(checkedProgram, "checkedProgram");
        Objects.requireNonNull(options, "options");
        return new Engine(checkedProgram, options).run();
    }

    public record Result(
            Ast.Program program,
            Set<String> retainedSymbols,
            Set<String> removedSymbols,
            Map<String, Object> compileTimeConstants) {
        public Result {
            retainedSymbols = Set.copyOf(retainedSymbols);
            removedSymbols = Set.copyOf(removedSymbols);
            compileTimeConstants = Map.copyOf(compileTimeConstants);
        }

        public boolean retained(String symbol) {
            return retainedSymbols.contains(symbol);
        }

        public boolean removed(String symbol) {
            return removedSymbols.contains(symbol);
        }
    }

    private record Symbol(String id, String module, Ast.Decl declaration) { }

    private static final class Engine {
        private final Ast.Program input;
        private final BuildOptions options;

        private final Map<String, Ast.FieldDecl> constants = new LinkedHashMap<>();
        private final Map<String, String> uniqueConstants = new LinkedHashMap<>();
        private final Set<String> ambiguousConstants = new LinkedHashSet<>();
        private final Map<String, Object> constantValues = new LinkedHashMap<>();
        private final Set<String> constantsBeingEvaluated = new LinkedHashSet<>();

        private final Map<String, Ast.FunctionDecl> inlineFunctions = new LinkedHashMap<>();
        private final Map<String, String> uniqueInlineFunctions = new LinkedHashMap<>();
        private final Set<String> ambiguousInlineFunctions = new LinkedHashSet<>();
        private final Set<String> functionsBeingInlined = new LinkedHashSet<>();

        private final Map<String, Symbol> symbols = new LinkedHashMap<>();
        private final Map<String, String> uniqueSymbols = new LinkedHashMap<>();
        private final Set<String> ambiguousSymbols = new LinkedHashSet<>();
        private final Set<String> moduleNames = new LinkedHashSet<>();

        private final LinkedHashSet<String> retained = new LinkedHashSet<>();
        private final LinkedHashSet<String> externalBindings = new LinkedHashSet<>();
        private final ArrayDeque<String> work = new ArrayDeque<>();

        private Ast.Program rewritten;

        Engine(Ast.Program input, BuildOptions options) {
            this.input = input;
            this.options = options;
        }

        Result run() {
            collectConstants();
            collectInlineFunctions();
            applyBuildDefines();
            materializeConstantValues();
            rewritten = rewriteProgram(input);
            indexSymbols(rewritten);
            seedRoots();

            while (!work.isEmpty()) {
                Symbol symbol = symbols.get(work.removeFirst());
                if (symbol != null) scanDeclaration(symbol.module(), symbol.declaration());
            }

            Ast.Program pruned = pruneProgram(rewritten);
            LinkedHashSet<String> removed = new LinkedHashSet<>(symbols.keySet());
            removed.removeAll(retained);
            return new Result(pruned, retained, removed, constantValues);
        }

        private void collectConstants() {
            for (Ast.ModuleDecl module : input.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.FieldDecl field
                            && field.bindingKind() == Ast.BindingKind.CONST) {
                        String qualified = qualify(module.name(), field.name());
                        constants.put(qualified, field);
                        String previous = uniqueConstants.putIfAbsent(field.name(), qualified);
                        if (previous != null && !previous.equals(qualified)) {
                            uniqueConstants.remove(field.name());
                            ambiguousConstants.add(field.name());
                        }
                    }
                }
            }
        }

        private void collectInlineFunctions() {
            for (Ast.ModuleDecl module : input.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (!(declaration instanceof Ast.FunctionDecl function)) continue;
                    String qualified = qualify(module.name(), function.name());
                    inlineFunctions.put(qualified, function);
                    String previous = uniqueInlineFunctions.putIfAbsent(function.name(), qualified);
                    if (previous != null && !previous.equals(qualified)) {
                        uniqueInlineFunctions.remove(function.name());
                        ambiguousInlineFunctions.add(function.name());
                    }
                }
            }
        }

        private record InlineTarget(String id, Ast.FunctionDecl function) { }

        private InlineTarget resolveInlineTarget(
                Ast.Expr callee,
                String module,
                Map<String, Object> locals) {
            if (callee instanceof Ast.NameExpr name) {
                if (locals.containsKey(name.name())) return null;
                String local = qualify(module, name.name());
                if (inlineFunctions.containsKey(local)) {
                    return new InlineTarget(local, inlineFunctions.get(local));
                }
                if (ambiguousInlineFunctions.contains(name.name())) return null;
                String qualified = uniqueInlineFunctions.get(name.name());
                return qualified == null ? null : new InlineTarget(qualified, inlineFunctions.get(qualified));
            }
            if (callee instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr namespace
                    && !locals.containsKey(namespace.name())) {
                String qualified = qualify(namespace.name(), member.member());
                Ast.FunctionDecl function = inlineFunctions.get(qualified);
                return function == null ? null : new InlineTarget(qualified, function);
            }
            return null;
        }

        private boolean canInlineConstantCall(
                InlineTarget target,
                Ast.CallExpr call,
                List<Ast.Expr> arguments) {
            Ast.FunctionDecl function = target.function();
            if (call.typeArgumentsPresent()
                    || !function.genericParameters().isEmpty()
                    || function.kind() != Ast.CallableKind.FNC
                    || function.actorKind() != Ast.ActorKind.NONE
                    || function.async()
                    || function.parameters().size() != arguments.size()
                    || function.body().size() != 1
                    || !(function.body().getFirst() instanceof Ast.ReturnStmt returned)
                    || returned.value() == null
                    || containsLambda(returned.value())
                    || functionsBeingInlined.contains(target.id())) {
                return false;
            }
            return arguments.stream().allMatch(Ast.LiteralExpr.class::isInstance);
        }

        private boolean containsLambda(Ast.Expr expression) {
            if (expression == null) return false;
            if (expression instanceof Ast.LambdaExpr) return true;
            if (expression instanceof Ast.BinaryExpr binary) {
                return containsLambda(binary.left()) || containsLambda(binary.right());
            }
            if (expression instanceof Ast.UnaryExpr unary) return containsLambda(unary.operand());
            if (expression instanceof Ast.AssignExpr assignment) {
                return containsLambda(assignment.target()) || containsLambda(assignment.value());
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                return containsLambda(conditional.condition())
                        || containsLambda(conditional.whenTrue())
                        || containsLambda(conditional.whenFalse());
            }
            if (expression instanceof Ast.CallExpr call) {
                if (containsLambda(call.callee())) return true;
                return call.arguments().stream().anyMatch(this::containsLambda);
            }
            if (expression instanceof Ast.MemberExpr member) return containsLambda(member.receiver());
            if (expression instanceof Ast.IndexExpr indexed) {
                return containsLambda(indexed.receiver()) || containsLambda(indexed.index());
            }
            if (expression instanceof Ast.NewExpr created) {
                return created.arguments().stream().anyMatch(this::containsLambda);
            }
            if (expression instanceof Ast.AwaitExpr awaited) return containsLambda(awaited.expression());
            if (expression instanceof Ast.ListExpr list) {
                return list.elements().stream().anyMatch(this::containsLambda);
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                return tuple.elements().stream().anyMatch(this::containsLambda);
            }
            if (expression instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) {
                    if (field.isDynamic() && containsLambda(field.dynamicName())) return true;
                    if (containsLambda(field.value())) return true;
                }
            }
            return false;
        }

        private Ast.Expr inlineConstantCall(
                InlineTarget target,
                List<Ast.Expr> arguments) {
            Ast.FunctionDecl function = target.function();
            Ast.ReturnStmt returned = (Ast.ReturnStmt) function.body().getFirst();
            LinkedHashMap<String, Ast.Expr> substitutions = new LinkedHashMap<>();
            for (int i = 0; i < function.parameters().size(); i++) {
                substitutions.put(function.parameters().get(i).name(), arguments.get(i));
            }

            functionsBeingInlined.add(target.id());
            try {
                Ast.Expr substituted = substitute(returned.value(), substitutions, Set.of());
                return rewriteExpression(substituted, moduleOf(target.id()), Map.of());
            } finally {
                functionsBeingInlined.remove(target.id());
            }
        }

        private Ast.Expr substitute(
                Ast.Expr expression,
                Map<String, Ast.Expr> substitutions,
                Set<String> shadowed) {
            if (expression == null || expression instanceof Ast.LiteralExpr) return expression;
            if (expression instanceof Ast.NameExpr name) {
                if (!shadowed.contains(name.name()) && substitutions.containsKey(name.name())) {
                    return substitutions.get(name.name());
                }
                return expression;
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                return new Ast.BinaryExpr(
                        binary.operator(),
                        substitute(binary.left(), substitutions, shadowed),
                        substitute(binary.right(), substitutions, shadowed));
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                return new Ast.UnaryExpr(
                        unary.operator(),
                        substitute(unary.operand(), substitutions, shadowed));
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                return new Ast.AssignExpr(
                        substitute(assignment.target(), substitutions, shadowed),
                        substitute(assignment.value(), substitutions, shadowed));
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                return new Ast.ConditionalExpr(
                        substitute(conditional.condition(), substitutions, shadowed),
                        substitute(conditional.whenTrue(), substitutions, shadowed),
                        substitute(conditional.whenFalse(), substitutions, shadowed));
            }
            if (expression instanceof Ast.CallExpr call) {
                List<Ast.Expr> arguments = new ArrayList<>();
                for (Ast.Expr argument : call.arguments()) {
                    arguments.add(substitute(argument, substitutions, shadowed));
                }
                return new Ast.CallExpr(
                        substitute(call.callee(), substitutions, shadowed),
                        call.typeArguments(),
                        call.typeArgumentsPresent(),
                        arguments);
            }
            if (expression instanceof Ast.MemberExpr member) {
                return new Ast.MemberExpr(
                        substitute(member.receiver(), substitutions, shadowed),
                        member.member());
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                return new Ast.IndexExpr(
                        substitute(indexed.receiver(), substitutions, shadowed),
                        substitute(indexed.index(), substitutions, shadowed));
            }
            if (expression instanceof Ast.NewExpr created) {
                List<Ast.Expr> arguments = new ArrayList<>();
                for (Ast.Expr argument : created.arguments()) {
                    arguments.add(substitute(argument, substitutions, shadowed));
                }
                return new Ast.NewExpr(created.type(), arguments);
            }
            if (expression instanceof Ast.AwaitExpr awaited) {
                return new Ast.AwaitExpr(substitute(awaited.expression(), substitutions, shadowed));
            }
            if (expression instanceof Ast.ListExpr list) {
                List<Ast.Expr> elements = new ArrayList<>();
                for (Ast.Expr element : list.elements()) {
                    elements.add(substitute(element, substitutions, shadowed));
                }
                return new Ast.ListExpr(elements);
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                List<Ast.Expr> elements = new ArrayList<>();
                for (Ast.Expr element : tuple.elements()) {
                    elements.add(substitute(element, substitutions, shadowed));
                }
                return new Ast.TupleExpr(elements);
            }
            if (expression instanceof Ast.ObjectExpr object) {
                List<Ast.ObjectField> fields = new ArrayList<>();
                for (Ast.ObjectField field : object.fields()) {
                    fields.add(field.isDynamic()
                            ? Ast.ObjectField.dynamic(
                                    substitute(field.dynamicName(), substitutions, shadowed),
                                    substitute(field.value(), substitutions, shadowed))
                            : Ast.ObjectField.named(
                                    field.name(),
                                    substitute(field.value(), substitutions, shadowed)));
                }
                return new Ast.ObjectExpr(fields);
            }
            if (expression instanceof Ast.LambdaExpr lambda) {
                LinkedHashSet<String> nestedShadowed = new LinkedHashSet<>(shadowed);
                for (Ast.Param parameter : lambda.parameters()) nestedShadowed.add(parameter.name());
                return new Ast.LambdaExpr(
                        lambda.parameters(),
                        substitute(lambda.expressionBody(), substitutions, nestedShadowed),
                        lambda.blockBody(),
                        lambda.nonLexical());
            }
            throw new IllegalStateException(
                    "unhandled expression during specialization " + expression.getClass().getSimpleName());
        }

        private void applyBuildDefines() {
            for (Map.Entry<String, String> entry : options.defines().entrySet()) {
                String qualified = resolveConstantOverride(entry.getKey());
                Ast.FieldDecl field = constants.get(qualified);
                Object original = field.initializer() == null
                        ? UNKNOWN
                        : evaluate(field.initializer(), moduleOf(qualified), Map.of());
                constantValues.put(
                        qualified,
                        parseDefine(entry.getValue(), field.type(), original, entry.getKey()));
            }
        }

        private void materializeConstantValues() {
            for (String qualified : constants.keySet()) constantValue(qualified);
        }

        private String resolveConstantOverride(String requested) {
            if (constants.containsKey(requested)) return requested;
            if (requested.contains(".")) {
                throw new IllegalArgumentException(
                        "build define '" + requested + "' does not name a const declaration");
            }
            if (ambiguousConstants.contains(requested)) {
                throw new IllegalArgumentException(
                        "build define '" + requested + "' is ambiguous; use Module." + requested);
            }
            String qualified = uniqueConstants.get(requested);
            if (qualified == null) {
                throw new IllegalArgumentException(
                        "build define '" + requested + "' does not name a const declaration");
            }
            return qualified;
        }

        private Object constantValue(String qualified) {
            Object existing = constantValues.get(qualified);
            if (existing != null) return existing;
            Ast.FieldDecl field = constants.get(qualified);
            if (field == null || field.initializer() == null) return UNKNOWN;
            if (!constantsBeingEvaluated.add(qualified)) {
                throw new IllegalArgumentException(
                        "cyclic compile-time const dependency involving '" + qualified + "'");
            }
            try {
                Object value = evaluate(field.initializer(), moduleOf(qualified), Map.of());
                if (value != UNKNOWN) constantValues.put(qualified, value);
                return value;
            } finally {
                constantsBeingEvaluated.remove(qualified);
            }
        }

        private Object evaluate(Ast.Expr expression, String module, Map<String, Object> locals) {
            if (expression == null) return UNKNOWN;
            if (expression instanceof Ast.LiteralExpr literal) return literal.value();
            if (expression instanceof Ast.NameExpr name) {
                if (locals.containsKey(name.name())) return locals.get(name.name());
                String key = resolveConstantReference(name.name(), module);
                return key == null ? UNKNOWN : constantValue(key);
            }
            if (expression instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr namespace) {
                String key = qualify(namespace.name(), member.member());
                if (constants.containsKey(key)) return constantValue(key);
                return UNKNOWN;
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                Object operand = evaluate(unary.operand(), module, locals);
                if (operand == UNKNOWN) return UNKNOWN;
                if (unary.operator().equals("!") && operand instanceof Boolean value) return !value;
                if (unary.operator().equals("+") && operand instanceof Number) return operand;
                if (unary.operator().equals("-") && operand instanceof Long value) return -value;
                if (unary.operator().equals("-") && operand instanceof Double value) return -value;
                return UNKNOWN;
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                Object left = evaluate(binary.left(), module, locals);
                if (binary.operator().equals("&&") && left instanceof Boolean value && !value) return false;
                if (binary.operator().equals("||") && left instanceof Boolean value && value) return true;
                Object right = evaluate(binary.right(), module, locals);
                if (left == UNKNOWN || right == UNKNOWN) return UNKNOWN;
                return switch (binary.operator()) {
                    case "&&" -> left instanceof Boolean l && right instanceof Boolean r ? l && r : UNKNOWN;
                    case "||" -> left instanceof Boolean l && right instanceof Boolean r ? l || r : UNKNOWN;
                    case "==" -> Objects.equals(left, right);
                    case "!=" -> !Objects.equals(left, right);
                    case "<" -> compare(left, right, c -> c < 0);
                    case "<=" -> compare(left, right, c -> c <= 0);
                    case ">" -> compare(left, right, c -> c > 0);
                    case ">=" -> compare(left, right, c -> c >= 0);
                    default -> UNKNOWN;
                };
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                Object condition = evaluate(conditional.condition(), module, locals);
                if (condition instanceof Boolean value) {
                    return evaluate(value ? conditional.whenTrue() : conditional.whenFalse(), module, locals);
                }
            }
            return UNKNOWN;
        }

        private Object compare(Object left, Object right, java.util.function.IntPredicate predicate) {
            if (left instanceof Number l && right instanceof Number r) {
                return predicate.test(Double.compare(l.doubleValue(), r.doubleValue()));
            }
            if (left instanceof String l && right instanceof String r) {
                return predicate.test(l.compareTo(r));
            }
            return UNKNOWN;
        }

        private Ast.Program rewriteProgram(Ast.Program program) {
            List<Ast.ModuleDecl> modules = new ArrayList<>();
            for (Ast.ModuleDecl module : program.modules()) {
                List<Ast.Decl> declarations = new ArrayList<>();
                for (Ast.Decl declaration : module.declarations()) {
                    declarations.add(rewriteDeclaration(module.name(), declaration));
                }
                modules.add(new Ast.ModuleDecl(module.name(), module.annotations(), declarations));
            }
            return new Ast.Program(program.namespace(), program.imports(), modules);
        }

        private Ast.Decl rewriteDeclaration(String module, Ast.Decl declaration) {
            if (declaration instanceof Ast.FunctionDecl function) {
                LinkedHashMap<String, Object> locals = new LinkedHashMap<>();
                for (Ast.Param parameter : function.parameters()) locals.put(parameter.name(), UNKNOWN);
                return new Ast.FunctionDecl(
                        function.name(),
                        function.kind(),
                        function.visibility(),
                        function.async(),
                        function.nonLexical(),
                        function.pure(),
                        function.trapped(),
                        function.actorKind(),
                        function.genericParameters(),
                        function.parameters(),
                        function.returnType(),
                        function.annotations(),
                        rewriteStatements(function.body(), module, locals));
            }
            if (declaration instanceof Ast.FieldDecl field) {
                Ast.Expr initializer = rewriteExpression(field.initializer(), module, Map.of());
                if (field.bindingKind() == Ast.BindingKind.CONST) {
                    String key = qualify(module, field.name());
                    Object value = constantValues.get(key);
                    if (value != null && value != UNKNOWN) initializer = new Ast.LiteralExpr(value);
                }
                return new Ast.FieldDecl(
                        field.name(),
                        field.visibility(),
                        field.bindingKind(),
                        field.type(),
                        initializer);
            }
            if (declaration instanceof Ast.ClassDecl klass) {
                List<Ast.FieldDecl> fields = new ArrayList<>();
                for (Ast.FieldDecl field : klass.fields()) {
                    fields.add((Ast.FieldDecl) rewriteDeclaration(module, field));
                }
                List<Ast.MethodDecl> methods = new ArrayList<>();
                for (Ast.MethodDecl method : klass.methods()) {
                    LinkedHashMap<String, Object> locals = new LinkedHashMap<>();
                    locals.put("self", UNKNOWN);
                    for (Ast.Param parameter : method.parameters()) locals.put(parameter.name(), UNKNOWN);
                    methods.add(new Ast.MethodDecl(
                            method.name(),
                            method.visibility(),
                            method.isStatic(),
                            method.isAbstract(),
                            method.async(),
                            method.explicitReceiverType(),
                            method.genericParameters(),
                            method.parameters(),
                            method.returnType(),
                            method.annotations(),
                            rewriteStatements(method.body(), module, locals)));
                }
                return new Ast.ClassDecl(
                        klass.name(),
                        klass.isAbstract(),
                        klass.actorKind(),
                        klass.genericParameters(),
                        klass.parents(),
                        klass.interfaces(),
                        fields,
                        methods);
            }
            return declaration;
        }

        private List<Ast.Stmt> rewriteStatements(
                List<Ast.Stmt> statements,
                String module,
                LinkedHashMap<String, Object> locals) {
            List<Ast.Stmt> output = new ArrayList<>();
            for (Ast.Stmt statement : statements) {
                output.addAll(rewriteStatement(statement, module, locals));
            }
            return List.copyOf(output);
        }

        private List<Ast.Stmt> rewriteStatement(
                Ast.Stmt statement,
                String module,
                LinkedHashMap<String, Object> locals) {
            if (statement instanceof Ast.BindingStmt binding) {
                Ast.Expr initializer = rewriteExpression(binding.initializer(), module, locals);
                Object value = binding.kind() == Ast.BindingKind.LET
                        ? UNKNOWN
                        : evaluate(initializer, module, locals);
                locals.put(binding.name(), value);
                return List.of(new Ast.BindingStmt(
                        binding.kind(), binding.declaredType(), binding.name(), initializer));
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                Ast.Expr initializer = rewriteExpression(destructure.initializer(), module, locals);
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard()) locals.put(binding.name(), UNKNOWN);
                }
                return List.of(new Ast.DestructureStmt(
                        destructure.kind(), destructure.bindings(), initializer));
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                return List.of(new Ast.ReturnStmt(
                        rewriteExpression(returned.value(), module, locals)));
            }
            if (statement instanceof Ast.ExprStmt expression) {
                return List.of(new Ast.ExprStmt(
                        rewriteExpression(expression.expression(), module, locals)));
            }
            if (statement instanceof Ast.DeferStmt deferred) {
                return List.of(new Ast.DeferStmt(
                        rewriteExpression(deferred.expression(), module, locals)));
            }
            if (statement instanceof Ast.IfStmt conditional) {
                List<Ast.IfBranch> branches = new ArrayList<>();
                List<Ast.Stmt> elseBody = rewriteStatements(
                        conditional.elseBody(), module, new LinkedHashMap<>(locals));
                for (Ast.IfBranch branch : conditional.branches()) {
                    Ast.Expr condition = rewriteExpression(branch.condition(), module, locals);
                    Object known = evaluate(condition, module, locals);
                    List<Ast.Stmt> body = rewriteStatements(
                            branch.body(), module, new LinkedHashMap<>(locals));
                    if (known instanceof Boolean value) {
                        if (!value) continue;
                        if (branches.isEmpty()) return body;
                        elseBody = body;
                        break;
                    }
                    branches.add(new Ast.IfBranch(condition, body));
                }
                if (branches.isEmpty()) return elseBody;
                return List.of(new Ast.IfStmt(branches, elseBody));
            }
            if (statement instanceof Ast.TryStmt tried) {
                LinkedHashMap<String, Object> catchLocals = new LinkedHashMap<>(locals);
                if (tried.errorName() != null) catchLocals.put(tried.errorName(), UNKNOWN);
                return List.of(new Ast.TryStmt(
                        rewriteStatements(tried.body(), module, new LinkedHashMap<>(locals)),
                        tried.errorName(),
                        rewriteStatements(tried.catchBody(), module, catchLocals),
                        rewriteStatements(tried.finallyBody(), module, new LinkedHashMap<>(locals))));
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                Ast.Expr iterable = rewriteExpression(loop.iterable(), module, locals);
                LinkedHashMap<String, Object> bodyLocals = new LinkedHashMap<>(locals);
                bodyLocals.put(loop.bindingName(), UNKNOWN);
                return List.of(new Ast.ForOfStmt(
                        loop.bindingKind(),
                        loop.bindingName(),
                        iterable,
                        rewriteStatements(loop.body(), module, bodyLocals)));
            }
            if (statement instanceof Ast.ForStmt loop) {
                LinkedHashMap<String, Object> loopLocals = new LinkedHashMap<>(locals);
                Ast.Stmt initializer = null;
                if (loop.initializer() != null) {
                    List<Ast.Stmt> initializers = rewriteStatement(loop.initializer(), module, loopLocals);
                    if (initializers.size() != 1) {
                        throw new IllegalStateException("for initializer cannot expand during tree shaking");
                    }
                    initializer = initializers.getFirst();
                }
                return List.of(new Ast.ForStmt(
                        initializer,
                        rewriteExpression(loop.condition(), module, loopLocals),
                        rewriteExpression(loop.update(), module, loopLocals),
                        rewriteStatements(loop.body(), module, new LinkedHashMap<>(loopLocals))));
            }
            throw new IllegalStateException("unhandled statement " + statement.getClass().getSimpleName());
        }

        private Ast.Expr rewriteExpression(
                Ast.Expr expression,
                String module,
                Map<String, Object> locals) {
            if (expression == null) return null;
            Object constant = evaluate(expression, module, locals);
            if (constant != UNKNOWN) return new Ast.LiteralExpr(constant);

            if (expression instanceof Ast.LiteralExpr || expression instanceof Ast.NameExpr) return expression;
            if (expression instanceof Ast.BinaryExpr binary) {
                Ast.Expr left = rewriteExpression(binary.left(), module, locals);
                Ast.Expr right = rewriteExpression(binary.right(), module, locals);
                Ast.BinaryExpr rewritten = new Ast.BinaryExpr(binary.operator(), left, right);
                Object value = evaluate(rewritten, module, locals);
                return value == UNKNOWN ? rewritten : new Ast.LiteralExpr(value);
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                Ast.UnaryExpr rewritten = new Ast.UnaryExpr(
                        unary.operator(), rewriteExpression(unary.operand(), module, locals));
                Object value = evaluate(rewritten, module, locals);
                return value == UNKNOWN ? rewritten : new Ast.LiteralExpr(value);
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                return new Ast.AssignExpr(
                        rewriteExpression(assignment.target(), module, locals),
                        rewriteExpression(assignment.value(), module, locals));
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                Ast.Expr condition = rewriteExpression(conditional.condition(), module, locals);
                Object known = evaluate(condition, module, locals);
                if (known instanceof Boolean value) {
                    return rewriteExpression(
                            value ? conditional.whenTrue() : conditional.whenFalse(),
                            module,
                            locals);
                }
                return new Ast.ConditionalExpr(
                        condition,
                        rewriteExpression(conditional.whenTrue(), module, locals),
                        rewriteExpression(conditional.whenFalse(), module, locals));
            }
            if (expression instanceof Ast.CallExpr call) {
                Ast.Expr callee = rewriteExpression(call.callee(), module, locals);
                List<Ast.Expr> arguments = new ArrayList<>();
                for (Ast.Expr argument : call.arguments()) {
                    arguments.add(rewriteExpression(argument, module, locals));
                }
                Ast.CallExpr rewritten = new Ast.CallExpr(
                        callee,
                        call.typeArguments(),
                        call.typeArgumentsPresent(),
                        arguments);

                InlineTarget target = resolveInlineTarget(callee, module, locals);
                if (target != null && canInlineConstantCall(target, rewritten, arguments)) {
                    return inlineConstantCall(target, arguments);
                }
                return rewritten;
            }
            if (expression instanceof Ast.RuntimeCallExpr runtime) {
                List<Ast.Expr> arguments = new ArrayList<>();
                for (Ast.Expr argument : runtime.arguments()) {
                    arguments.add(rewriteExpression(argument, module, locals));
                }
                return new Ast.RuntimeCallExpr(runtime.operation(), arguments);
            }
            if (expression instanceof Ast.MemberExpr member) {
                return new Ast.MemberExpr(
                        rewriteExpression(member.receiver(), module, locals),
                        member.member());
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                return new Ast.IndexExpr(
                        rewriteExpression(indexed.receiver(), module, locals),
                        rewriteExpression(indexed.index(), module, locals));
            }
            if (expression instanceof Ast.NewExpr created) {
                List<Ast.Expr> arguments = new ArrayList<>();
                for (Ast.Expr argument : created.arguments()) {
                    arguments.add(rewriteExpression(argument, module, locals));
                }
                return new Ast.NewExpr(created.type(), arguments);
            }
            if (expression instanceof Ast.AwaitExpr awaited) {
                return new Ast.AwaitExpr(rewriteExpression(awaited.expression(), module, locals));
            }
            if (expression instanceof Ast.ListExpr list) {
                List<Ast.Expr> elements = new ArrayList<>();
                for (Ast.Expr element : list.elements()) {
                    elements.add(rewriteExpression(element, module, locals));
                }
                return new Ast.ListExpr(elements);
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                List<Ast.Expr> elements = new ArrayList<>();
                for (Ast.Expr element : tuple.elements()) {
                    elements.add(rewriteExpression(element, module, locals));
                }
                return new Ast.TupleExpr(elements);
            }
            if (expression instanceof Ast.ObjectExpr object) {
                List<Ast.ObjectField> fields = new ArrayList<>();
                for (Ast.ObjectField field : object.fields()) {
                    fields.add(field.isDynamic()
                            ? Ast.ObjectField.dynamic(
                                    rewriteExpression(field.dynamicName(), module, locals),
                                    rewriteExpression(field.value(), module, locals))
                            : Ast.ObjectField.named(
                                    field.name(),
                                    rewriteExpression(field.value(), module, locals)));
                }
                return new Ast.ObjectExpr(fields);
            }
            if (expression instanceof Ast.LambdaExpr lambda) {
                LinkedHashMap<String, Object> lambdaLocals = new LinkedHashMap<>(locals);
                for (Ast.Param parameter : lambda.parameters()) lambdaLocals.put(parameter.name(), UNKNOWN);
                return new Ast.LambdaExpr(
                        lambda.parameters(),
                        rewriteExpression(lambda.expressionBody(), module, lambdaLocals),
                        lambda.blockBody() == null
                                ? null
                                : rewriteStatements(lambda.blockBody(), module, lambdaLocals),
                        lambda.nonLexical());
            }
            throw new IllegalStateException("unhandled expression " + expression.getClass().getSimpleName());
        }

        private void indexSymbols(Ast.Program program) {
            for (Ast.ModuleDecl module : program.modules()) {
                moduleNames.add(module.name());
                for (Ast.Decl declaration : module.declarations()) {
                    String name = declarationName(declaration);
                    if (name == null) continue;
                    String id = qualify(module.name(), name);
                    Symbol symbol = new Symbol(id, module.name(), declaration);
                    symbols.put(id, symbol);
                    String previous = uniqueSymbols.putIfAbsent(name, id);
                    if (previous != null && !previous.equals(id)) {
                        uniqueSymbols.remove(name);
                        ambiguousSymbols.add(name);
                    }
                }
            }
        }

        private void seedRoots() {
            if (options.preservePublicApi()) {
                for (Symbol symbol : symbols.values()) {
                    if (isPublicApi(symbol.declaration())) mark(symbol.id());
                }
            }

            for (String requested : options.entryPoints()) {
                String root = resolveSymbol(requested, true);
                if (root == null) {
                    throw new IllegalArgumentException(
                            "build entry point '" + requested + "' was not found");
                }
                mark(root);
            }
        }

        private boolean isPublicApi(Ast.Decl declaration) {
            if (declaration instanceof Ast.FunctionDecl fn) {
                return fn.visibility() == Ast.Visibility.PUBLIC;
            }
            if (declaration instanceof Ast.FieldDecl field) {
                return field.visibility() == Ast.Visibility.PUBLIC;
            }
            if (declaration instanceof Ast.InterfaceDecl iface) {
                return iface.visibility() == Ast.Visibility.PUBLIC;
            }
            // Classes and aliases currently have no declaration-level visibility.
            return declaration instanceof Ast.ClassDecl || declaration instanceof Ast.TypeAliasDecl;
        }

        private void mark(String id) {
            if (id != null && symbols.containsKey(id) && retained.add(id)) work.addLast(id);
        }

        private String resolveSymbol(String name, boolean preferRoot) {
            if (symbols.containsKey(name)) return name;
            if (!name.contains(".") && preferRoot) {
                String root = qualify(Parser.ROOT_MODULE, name);
                if (symbols.containsKey(root)) return root;
            }
            if (ambiguousSymbols.contains(name)) {
                if (preferRoot) {
                    String root = qualify(Parser.ROOT_MODULE, name);
                    if (symbols.containsKey(root)) return root;
                }
                return null;
            }
            return uniqueSymbols.get(name);
        }

        private void scanDeclaration(String module, Ast.Decl declaration) {
            if (declaration instanceof Ast.FunctionDecl function) {
                scanType(function.returnType());
                for (Ast.Param parameter : function.parameters()) scanType(parameter.type());
                for (Ast.Annotation annotation : function.annotations()) scanAnnotation(annotation);
                LinkedHashSet<String> locals = new LinkedHashSet<>();
                for (Ast.Param parameter : function.parameters()) locals.add(parameter.name());
                scanStatements(module, function.body(), locals);
                return;
            }
            if (declaration instanceof Ast.FieldDecl field) {
                scanType(field.type());
                scanExpression(module, field.initializer(), Set.of());
                return;
            }
            if (declaration instanceof Ast.ClassDecl klass) {
                for (Ast.TypeRef parent : klass.parents()) scanType(parent);
                for (Ast.TypeRef iface : klass.interfaces()) scanType(iface);
                for (Ast.FieldDecl field : klass.fields()) {
                    scanType(field.type());
                    scanExpression(module, field.initializer(), Set.of("self"));
                }
                for (Ast.MethodDecl method : klass.methods()) {
                    scanType(method.explicitReceiverType());
                    scanType(method.returnType());
                    for (Ast.Param parameter : method.parameters()) scanType(parameter.type());
                    for (Ast.Annotation annotation : method.annotations()) scanAnnotation(annotation);
                    LinkedHashSet<String> locals = new LinkedHashSet<>();
                    locals.add("self");
                    for (Ast.Param parameter : method.parameters()) locals.add(parameter.name());
                    scanStatements(module, method.body(), locals);
                }
                return;
            }
            if (declaration instanceof Ast.InterfaceDecl iface) {
                for (Ast.TypeRef parent : iface.parents()) scanType(parent);
                for (Ast.InterfaceMember member : iface.members()) {
                    if (member instanceof Ast.InterfaceFunctionDecl fn) {
                        scanType(fn.returnType());
                        for (Ast.Param parameter : fn.parameters()) scanType(parameter.type());
                    } else if (member instanceof Ast.InterfaceFieldDecl field) {
                        scanType(field.type());
                    }
                }
                return;
            }
            if (declaration instanceof Ast.TypeAliasDecl alias) {
                scanType(alias.target());
            }
        }

        private void scanAnnotation(Ast.Annotation annotation) {
            for (Ast.TypeRef argument : annotation.arguments()) scanType(argument);
        }

        private void scanType(Ast.TypeRef type) {
            if (type == null) return;
            if (!type.name().startsWith("$")) {
                String internal = resolveSymbol(type.name(), false);
                if (internal != null) mark(internal);
                else externalBindings.add(firstSegment(type.name()));
            }
            for (Ast.TypeRef argument : type.arguments()) scanType(argument);
        }

        private void scanStatements(String module, List<Ast.Stmt> statements, LinkedHashSet<String> locals) {
            for (Ast.Stmt statement : statements) {
                if (statement instanceof Ast.BindingStmt binding) {
                    scanType(binding.declaredType());
                    scanExpression(module, binding.initializer(), locals);
                    locals.add(binding.name());
                } else if (statement instanceof Ast.DestructureStmt destructure) {
                    scanExpression(module, destructure.initializer(), locals);
                    for (Ast.DestructureBinding binding : destructure.bindings()) {
                        if (!binding.isDiscard()) locals.add(binding.name());
                    }
                } else if (statement instanceof Ast.ReturnStmt returned) {
                    scanExpression(module, returned.value(), locals);
                } else if (statement instanceof Ast.ExprStmt expression) {
                    scanExpression(module, expression.expression(), locals);
                } else if (statement instanceof Ast.DeferStmt deferred) {
                    scanExpression(module, deferred.expression(), locals);
                } else if (statement instanceof Ast.IfStmt conditional) {
                    for (Ast.IfBranch branch : conditional.branches()) {
                        scanExpression(module, branch.condition(), locals);
                        scanStatements(module, branch.body(), new LinkedHashSet<>(locals));
                    }
                    scanStatements(module, conditional.elseBody(), new LinkedHashSet<>(locals));
                } else if (statement instanceof Ast.TryStmt tried) {
                    scanStatements(module, tried.body(), new LinkedHashSet<>(locals));
                    LinkedHashSet<String> catchLocals = new LinkedHashSet<>(locals);
                    if (tried.errorName() != null) catchLocals.add(tried.errorName());
                    scanStatements(module, tried.catchBody(), catchLocals);
                    scanStatements(module, tried.finallyBody(), new LinkedHashSet<>(locals));
                } else if (statement instanceof Ast.ForOfStmt loop) {
                    scanExpression(module, loop.iterable(), locals);
                    LinkedHashSet<String> bodyLocals = new LinkedHashSet<>(locals);
                    bodyLocals.add(loop.bindingName());
                    scanStatements(module, loop.body(), bodyLocals);
                } else if (statement instanceof Ast.ForStmt loop) {
                    LinkedHashSet<String> loopLocals = new LinkedHashSet<>(locals);
                    if (loop.initializer() != null) {
                        scanStatements(module, List.of(loop.initializer()), loopLocals);
                    }
                    scanExpression(module, loop.condition(), loopLocals);
                    scanExpression(module, loop.update(), loopLocals);
                    scanStatements(module, loop.body(), new LinkedHashSet<>(loopLocals));
                }
            }
        }

        private void scanExpression(String module, Ast.Expr expression, Set<String> locals) {
            if (expression == null || expression instanceof Ast.LiteralExpr) return;
            if (expression instanceof Ast.NameExpr name) {
                if (locals.contains(name.name())) return;
                String internal = resolveSymbol(name.name(), false);
                if (internal != null) mark(internal);
                else externalBindings.add(name.name());
                return;
            }
            if (expression instanceof Ast.MemberExpr member) {
                if (member.receiver() instanceof Ast.NameExpr namespace
                        && !locals.contains(namespace.name())) {
                    String qualified = qualify(namespace.name(), member.member());
                    if (symbols.containsKey(qualified)) {
                        mark(qualified);
                        return;
                    }
                    if (!moduleNames.contains(namespace.name())) {
                        externalBindings.add(namespace.name());
                    }
                }
                scanExpression(module, member.receiver(), locals);
                return;
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                scanExpression(module, binary.left(), locals);
                scanExpression(module, binary.right(), locals);
            } else if (expression instanceof Ast.UnaryExpr unary) {
                scanExpression(module, unary.operand(), locals);
            } else if (expression instanceof Ast.AssignExpr assignment) {
                scanExpression(module, assignment.target(), locals);
                scanExpression(module, assignment.value(), locals);
            } else if (expression instanceof Ast.ConditionalExpr conditional) {
                scanExpression(module, conditional.condition(), locals);
                scanExpression(module, conditional.whenTrue(), locals);
                scanExpression(module, conditional.whenFalse(), locals);
            } else if (expression instanceof Ast.CallExpr call) {
                scanExpression(module, call.callee(), locals);
                for (Ast.TypeRef type : call.typeArguments()) scanType(type);
                for (Ast.Expr argument : call.arguments()) scanExpression(module, argument, locals);
            } else if (expression instanceof Ast.RuntimeCallExpr runtime) {
                for (Ast.Expr argument : runtime.arguments()) scanExpression(module, argument, locals);
            } else if (expression instanceof Ast.IndexExpr indexed) {
                scanExpression(module, indexed.receiver(), locals);
                scanExpression(module, indexed.index(), locals);
            } else if (expression instanceof Ast.NewExpr created) {
                scanType(created.type());
                for (Ast.Expr argument : created.arguments()) scanExpression(module, argument, locals);
            } else if (expression instanceof Ast.AwaitExpr awaited) {
                scanExpression(module, awaited.expression(), locals);
            } else if (expression instanceof Ast.ListExpr list) {
                for (Ast.Expr element : list.elements()) scanExpression(module, element, locals);
            } else if (expression instanceof Ast.TupleExpr tuple) {
                for (Ast.Expr element : tuple.elements()) scanExpression(module, element, locals);
            } else if (expression instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) {
                    if (field.isDynamic()) scanExpression(module, field.dynamicName(), locals);
                    scanExpression(module, field.value(), locals);
                }
            } else if (expression instanceof Ast.LambdaExpr lambda) {
                LinkedHashSet<String> nested = new LinkedHashSet<>(locals);
                for (Ast.Param parameter : lambda.parameters()) {
                    scanType(parameter.type());
                    nested.add(parameter.name());
                }
                scanExpression(module, lambda.expressionBody(), nested);
                if (lambda.blockBody() != null) scanStatements(module, lambda.blockBody(), nested);
            }
        }

        private Ast.Program pruneProgram(Ast.Program program) {
            List<Ast.ModuleDecl> modules = new ArrayList<>();
            for (Ast.ModuleDecl module : program.modules()) {
                List<Ast.Decl> declarations = new ArrayList<>();
                for (Ast.Decl declaration : module.declarations()) {
                    String name = declarationName(declaration);
                    if (name != null && retained.contains(qualify(module.name(), name))) {
                        declarations.add(declaration);
                    }
                }
                if (!declarations.isEmpty()) {
                    modules.add(new Ast.ModuleDecl(module.name(), module.annotations(), declarations));
                }
            }

            List<Ast.ImportDecl> imports = new ArrayList<>();
            for (Ast.ImportDecl imported : program.imports()) {
                if (importIsUsed(imported)) imports.add(imported);
            }
            return new Ast.Program(program.namespace(), imports, modules);
        }

        private boolean importIsUsed(Ast.ImportDecl imported) {
            if (imported.namespace() != null && !imported.namespace().isBlank()) {
                return externalBindings.contains(imported.namespace());
            }
            for (String name : imported.names()) {
                if (externalBindings.contains(name)) return true;
            }
            return false;
        }

        private String resolveConstantReference(String name, String module) {
            String local = qualify(module, name);
            if (constants.containsKey(local)) return local;
            if (ambiguousConstants.contains(name)) return null;
            return uniqueConstants.get(name);
        }

        private Object parseDefine(
                String raw,
                Ast.TypeRef declaredType,
                Object original,
                String displayName) {
            String type = declaredType == null ? null : declaredType.name();
            try {
                if ("bool".equals(type) || original instanceof Boolean) {
                    if (raw.equalsIgnoreCase("true")) return Boolean.TRUE;
                    if (raw.equalsIgnoreCase("false")) return Boolean.FALSE;
                    throw new IllegalArgumentException("expected true or false");
                }
                if ("int".equals(type) || "uint".equals(type) || original instanceof Long) {
                    return Long.parseLong(raw.replace("_", ""));
                }
                if ("float".equals(type) || "decimal".equals(type) || original instanceof Double) {
                    return Double.parseDouble(raw.replace("_", ""));
                }
                if ("String".equals(type) || original instanceof String || type == null) {
                    if (raw.length() >= 2
                            && ((raw.startsWith("\"") && raw.endsWith("\""))
                            || (raw.startsWith("'") && raw.endsWith("'")))) {
                        return raw.substring(1, raw.length() - 1);
                    }
                    return raw;
                }
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "invalid value for build define '" + displayName + "': " + error.getMessage(),
                        error);
            }
            throw new IllegalArgumentException(
                    "build define '" + displayName + "' has unsupported const type '" + type + "'");
        }

        private static String declarationName(Ast.Decl declaration) {
            if (declaration instanceof Ast.FunctionDecl function) return function.name();
            if (declaration instanceof Ast.ClassDecl klass) return klass.name();
            if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
            if (declaration instanceof Ast.FieldDecl field) return field.name();
            if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
            return null;
        }

        private static String qualify(String module, String name) {
            return module + "." + name;
        }

        private static String moduleOf(String qualified) {
            int dot = qualified.indexOf('.');
            return dot < 0 ? Parser.ROOT_MODULE : qualified.substring(0, dot);
        }

        private static String firstSegment(String name) {
            int dot = name.indexOf('.');
            return dot < 0 ? name : name.substring(0, dot);
        }
    }
}
