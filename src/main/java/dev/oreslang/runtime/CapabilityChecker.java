package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.List;

/**
 * Language-level capability admission pass. This executes before guest
 * statements and complements Graal/OS isolation rather than replacing it.
 */
public final class CapabilityChecker {
    private CapabilityChecker() { }

    public static void check(Ast.Program program, IsolatePolicy policy) {
        for (Ast.ModuleDecl module : program.modules()) {
            if (module.singleton()) {
                require(policy, IsolatePolicy.Capability.PROCESS_SINGLETON,
                        "singleton module " + module.name());
            }
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) checkStatements(fn.body(), policy);
                else if (declaration instanceof Ast.InitDecl init) checkStatements(init.body(), policy);
                else if (declaration instanceof Ast.ClassDecl klass) {
                    checkAggregate(klass, policy);
                } else if (declaration instanceof Ast.FieldDecl field && field.initializer() != null) {
                    checkExpr(field.initializer(), policy);
                }
            }
        }
    }

    private static void checkStatements(List<Ast.Stmt> statements, IsolatePolicy policy) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.TypeDeclStmt local) {
                if (local.declaration() instanceof Ast.ClassDecl klass) checkAggregate(klass, policy);
            } else if (stmt instanceof Ast.BindingStmt s) checkExpr(s.initializer(), policy);
            else if (stmt instanceof Ast.DestructureStmt s) checkExpr(s.initializer(), policy);
            else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) checkExpr(s.value(), policy);
            else if (stmt instanceof Ast.ExprStmt s) checkExpr(s.expression(), policy);
            else if (stmt instanceof Ast.DeferStmt s) checkExpr(s.expression(), policy);
            else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch b : s.branches()) {
                    checkExpr(b.condition(), policy);
                    checkStatements(b.body(), policy);
                }
                checkStatements(s.elseBody(), policy);
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

    private static void checkAggregate(Ast.ClassDecl klass, IsolatePolicy policy) {
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.initializer() != null) checkExpr(field.initializer(), policy);
        }
        for (Ast.MethodDecl method : klass.methods()) checkStatements(method.body(), policy);
    }

    private static void checkExpr(Ast.Expr expr, IsolatePolicy policy) {
        if (expr instanceof Ast.NameExpr n && n.name().equals("print")) require(policy, IsolatePolicy.Capability.STDOUT, "print");
        else if (expr instanceof Ast.CallExpr c) {
            checkExpr(c.callee(), policy);
            for (Ast.Expr arg : c.arguments()) checkExpr(arg, policy);
        } else if (expr instanceof Ast.MemberExpr m) {
            String path = memberPath(m);
            if (path != null) {
                if (path.startsWith("stdio.") || path.equals("stdio")) require(policy, IsolatePolicy.Capability.STDOUT, path);
                if (path.startsWith("process.descriptor") || path.equals("process.context_id")) require(policy, IsolatePolicy.Capability.PROCESS_INFO, path);
                if (path.startsWith("process.share_readonly")) require(policy, IsolatePolicy.Capability.ACTOR_SHARE_READONLY, path);
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
        else if (expr instanceof Ast.AssignExpr e) checkExpr(e.value(), policy);
        else if (expr instanceof Ast.ConditionalExpr e) { checkExpr(e.condition(), policy); checkExpr(e.whenTrue(), policy); checkExpr(e.whenFalse(), policy); }
        else if (expr instanceof Ast.IndexExpr e) { checkExpr(e.receiver(), policy); checkExpr(e.index(), policy); }
        else if (expr instanceof Ast.NewExpr e) for (Ast.Expr a : e.arguments()) checkExpr(a, policy);
        else if (expr instanceof Ast.StructInitExpr e) for (Ast.ObjectField f : e.fields()) checkExpr(f.value(), policy);
        else if (expr instanceof Ast.AwaitExpr e) checkExpr(e.expression(), policy);
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr a : e.elements()) checkExpr(a, policy);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr a : e.elements()) checkExpr(a, policy);
        else if (expr instanceof Ast.ObjectExpr e) for (Ast.ObjectField f : e.fields()) checkExpr(f.value(), policy);
        else if (expr instanceof Ast.LambdaExpr e) {
            if (e.expressionBody() != null) checkExpr(e.expressionBody(), policy);
            if (e.blockBody() != null) checkStatements(e.blockBody(), policy);
        }
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
        if (!policy.allows(capability)) throw new SecurityException("Oreslang isolate denies capability " + capability + " required by " + api);
    }
}
