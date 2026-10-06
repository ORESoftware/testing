package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

final class ChannelSelectSyntaxTest {
    @Test
    void parsesBlockingAndNonBlockingStaticSelect() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc blocking(
                    Channel<string> incoming,
                    Channel<string> payload,
                    Channel<bool> done
                ): void {
                  select {
                  case readch incoming: let msg
                    stdio.println(msg);
                  case readch payload: const body
                    stdio.println(body);
                  case readch done:
                    return;
                  }
                  return;
                }

                actor fnc nonblocking(): void {
                  val Channel<string> incoming = Channel.new<string>(1);
                  val Channel<string> payload = Channel.new<string>(1);
                  val Channel<bool> done = Channel.new<bool>(1);

                  nb select {
                  case readch incoming: let msg
                    stdio.println(msg);
                  case readch payload: const body
                    stdio.println(body);
                  case readch done: const signal
                    return;
                  }
                  stdio.println("continued immediately");
                  return;
                }
                """));

        Ast.FunctionDecl blocking =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(0);
        Ast.SelectStmt blockingSelect =
                assertInstanceOf(Ast.SelectStmt.class, blocking.body().getFirst());
        assertEquals(Ast.WaitMode.BLOCKING, blockingSelect.mode());
        assertEquals(Ast.SelectPolicy.FAIR, blockingSelect.policy());
        assertEquals(Ast.BindingKind.LET, blockingSelect.arms().getFirst().bindingKind());
        assertEquals(Ast.BindingKind.CONST, blockingSelect.arms().get(1).bindingKind());

        Ast.FunctionDecl nonblocking =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        Ast.SelectStmt nb =
                assertInstanceOf(Ast.SelectStmt.class, nonblocking.body().getFirst());
        assertEquals(Ast.WaitMode.NONBLOCKING, nb.mode());

        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    @Test
    void staticNbSelectRequiresActorExecutionDomain() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc wrong(Channel<int> input): void {
                  nb select {
                  case readch input: val value
                    stdio.println(value);
                  }
                  return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc right(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  nb select {
                  case readch input: val value
                    stdio.println(value);
                  }
                  return;
                }
                """)));
    }

    @Test
    void selectPolicyIsFairByDefaultAndPriorityOrRandomOnlyWhenExplicit() {
        Ast.Program program = Parser.parse("""
                fnc policies(Channel<int> a, Channel<int> b): void {
                  select {
                  case readch a: val x
                    stdio.println(x);
                  case readch b: val y
                    stdio.println(y);
                  }

                  select first {
                  case readch a: val x
                    stdio.println(x);
                  case readch b: val y
                    stdio.println(y);
                  }

                  select random {
                  case readch a: val x
                    stdio.println(x);
                  case readch b: val y
                    stdio.println(y);
                  }
                  return;
                }
                """);

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(
                Ast.SelectPolicy.FAIR,
                ((Ast.SelectStmt) fn.body().get(0)).policy());
        assertEquals(
                Ast.SelectPolicy.PRIORITY,
                ((Ast.SelectStmt) fn.body().get(1)).policy());
        assertEquals(
                Ast.SelectPolicy.RANDOM,
                ((Ast.SelectStmt) fn.body().get(2)).policy());
    }

    @Test
    void parsesImmediateChannelProbesWithoutConfusingTryCatch() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc probe(Channel<int> input, Channel<int> output): void {
                  val Option<int> read = try readch input;
                  val bool wrote = try writech output, 42;

                  try {
                    stdio.println("ordinary try still works");
                  } catch err {
                    stdio.println(err);
                  }
                  return;
                }
                """));

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt read = (Ast.BindingStmt) fn.body().get(0);
        Ast.ChannelOpExpr readOp =
                assertInstanceOf(Ast.ChannelOpExpr.class, read.initializer());
        assertEquals(Ast.WaitMode.IMMEDIATE, readOp.mode());
        assertEquals(Ast.ChannelOperation.READ, readOp.operation());

        Ast.BindingStmt wrote = (Ast.BindingStmt) fn.body().get(1);
        Ast.ChannelOpExpr writeOp =
                assertInstanceOf(Ast.ChannelOpExpr.class, wrote.initializer());
        assertEquals(Ast.WaitMode.IMMEDIATE, writeOp.mode());
        assertEquals(Ast.ChannelOperation.WRITE, writeOp.operation());

        assertInstanceOf(Ast.TryStmt.class, fn.body().get(2));
    }

    @Test
    void parsesDynamicSelectFromRuntimeCollections() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc choose(Array<SelectCase> cases): SelectResult {
                  return select from cases;
                }

                fnc arm(Array<SelectCase> cases): Future<SelectResult> {
                  return nb select first from cases;
                }

                fnc probe(Array<SelectCase> cases): Option<SelectResult> {
                  return try select from cases;
                }
                """));

        Ast.FunctionDecl choose =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(0);
        Ast.DynamicSelectExpr blocking =
                assertInstanceOf(
                        Ast.DynamicSelectExpr.class,
                        ((Ast.ReturnStmt) choose.body().getFirst()).value());
        assertEquals(Ast.WaitMode.BLOCKING, blocking.mode());
        assertEquals(Ast.SelectPolicy.FAIR, blocking.policy());

        Ast.FunctionDecl arm =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        Ast.DynamicSelectExpr nb =
                assertInstanceOf(
                        Ast.DynamicSelectExpr.class,
                        ((Ast.ReturnStmt) arm.body().getFirst()).value());
        assertEquals(Ast.WaitMode.NONBLOCKING, nb.mode());
        assertEquals(Ast.SelectPolicy.PRIORITY, nb.policy());
    }

    @Test
    void staticSelectSupportsWriteAndDefaultArms() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc send(Channel<string> output, string payload): void {
                  try select first {
                  case writech output, payload:
                    stdio.println("sent");
                  default:
                    stdio.println("busy");
                  }
                  return;
                }
                """)));
    }

    @Test
    void channelAndDynamicCaseFactoriesTypeCheckEndToEnd() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc make(): Channel<int> {
                  return Channel.new<int>(16);
                }

                fnc arm(): Future<SelectResult> {
                  val Channel<int> input = Channel.new<int>(4);
                  val Channel<int> output = Channel.new<int>(4);
                  val Array<SelectCase> cases = [
                    SelectCase.read(input),
                    SelectCase.write(output, 42)
                  ];
                  return nb select from cases;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): Channel<void> {
                  return Channel.new<void>(1);
                }
                """)));
    }

    @Test
    void nonblockingWriteHasRepresentableFutureVoidSurface() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc write(Channel<int> output): Future<void> {
                  return nb writech output, 42;
                }
                """));

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class, fn.body().getFirst());
        Ast.ChannelOpExpr write =
                assertInstanceOf(Ast.ChannelOpExpr.class, returned.value());
        assertEquals(Ast.WaitMode.NONBLOCKING, write.mode());
        assertEquals(Ast.ChannelOperation.WRITE, write.operation());
        assertEquals(false, write.callback());
    }

    @Test
    void nonblockingWriteCallbackUsesTheSameChannelOperationSurface() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                actor fnc write(Channel<int> output): void {
                  nb cb writech output, 42 || -> {
                    stdio.println("write complete");
                  };
                  return;
                }
                """));

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.ExprStmt statement =
                assertInstanceOf(Ast.ExprStmt.class, fn.body().getFirst());
        Ast.ChannelOpExpr write =
                assertInstanceOf(Ast.ChannelOpExpr.class, statement.expression());
        assertEquals(Ast.WaitMode.NONBLOCKING, write.mode());
        assertEquals(Ast.ChannelOperation.WRITE, write.operation());
        assertTrue(write.callback());
        assertEquals(1, write.callbackBody().size());
    }

    @Test
    void nonblockingWriteCallbackRequiresActorExecutionDomain() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc wrong(Channel<int> output): void {
                  nb cb writech output, 42 || -> {
                    stdio.println("wrong");
                  };
                  return;
                }
                """)));
    }

    @Test
    void rejectsMalformedChannelAndSelectForms() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Channel<int> ch): void {
                  nb select;
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Channel<int> ch): void {
                  select {
                  case readch ch
                    return;
                  }
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Channel<int> ch): void {
                  select {
                  default:
                    return;
                  default:
                    return;
                  }
                  return;
                }
                """));
    }
}
