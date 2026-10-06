package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ParserTest {
    @Test
    void supportsMultipleModulesAndComplexNumbers() {
        String source = """
                define module math
                  fnc z(): complex {
                    return 3 + 4i;
                  }
                end

                define module app
                  pub fnc main(): void {
                    const answer = 40 + 2;
                    [const first, let second] = [1, 2];
                    stdio.println("oreslang");
                    return;
                  }
                end
                """;

        Ast.Program program = TypeChecker.check(Parser.parse(source));
        assertEquals(2, program.modules().size());
        assertEquals("math", program.modules().getFirst().name());
    }

    @Test
    void parsesIfDoFiWithCommaAndPipeConditions() {
        String source = """
                define module app
                  fnc choose(bool a, bool b): int {
                    if a, b | false; do
                      return 1;
                    else
                      return 0;
                    fi
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void methodReceiverIsImplicitOrExplicitSelf() {
        String source = """
                define module model
                  define class x as
                    @Ret<self>
                    find() {
                      return self;
                    }

                    @Ret<self>
                    find_with_arg(self x)(int foo) {
                      return self;
                    }
                  end
                end
                """;
        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertNull(klass.methods().getFirst().explicitReceiverType());
        assertEquals("x", klass.methods().get(1).explicitReceiverType().name());
    }

    @Test
    void lexerRecognizesExecutableAndTypeArrows() {
        var tokens = new Lexer("|| -> { return; }; type F = () => void;").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ARROW));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.FAT_ARROW));
    }

    @Test
    void callableDeclarationSyntaxSeparatesCodeFromFunctionTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc run(): (() => void) {
                  return || -> {
                    return;
                  };
                }

                pub routine helper = || -> {
                  return;
                }

                pub fnc no_result = || -> {
                  helper();
                  return;
                }

                pub routine main = || -> void {
                  val (() => void) callback = run();
                  callback();
                  no_result();
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc bad_implicit_void = || -> {
                  return 1;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc bad() => void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                type Bad = () -> void;
                """));
    }
    @Test
    void namedExecutableCallablesAcceptColonOrSlimArrowReturns() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc by_colon(int value): int {
                  return value;
                }

                routine by_arrow(int value) -> int {
                  return value;
                }

                define class Box as
                  pub value() -> int {
                    return 1;
                  }

                  pub static fnc twice(int value) -> int {
                    return value * 2;
                  }
                end

                actor Worker {
                  pub handle(int value) -> int {
                    return value;
                  }
                }

                pub routine main() -> void {
                  val box = new Box();
                  stdio.stdout.write(by_colon(1));
                  stdio.stdout.write(by_arrow(2));
                  stdio.stdout.write(box.value());
                  stdio.stdout.write(Box.twice(2));
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc still_type_only() => int {
                  return 1;
                }
                """));
    }

    @Test
    void parsesSharedActorAndAllowsMailboxOwnedStateMutation() {
        String source = """
                shared actor Account {
                  let balance = 100;

                  pub fnc withdraw(int amount): void {
                    self.balance = self.balance - amount;
                    return;
                  }
                }
                """;

        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl actor = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();

        assertEquals(Ast.ActorKind.SHARED, actor.actorKind());
        assertEquals("Account", actor.name());
        assertEquals("withdraw", actor.methods().getFirst().name());

        Ast.Program typed = TypeChecker.check(program);
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void actorFncDefaultsSharedAndIsoactorIsPrivate() {
        Ast.Program sharedProgram = Parser.parse("""
                pub actor fnc worker(int value): int {
                  return value;
                }
                """);
        Ast.FunctionDecl sharedActor = (Ast.FunctionDecl) sharedProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.SHARED, sharedActor.actorKind());

        Ast.Program explicitSharedProgram = Parser.parse("""
                pub shared actor fnc worker(int value): int {
                  return value;
                }
                """);
        Ast.FunctionDecl explicitShared = (Ast.FunctionDecl) explicitSharedProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.SHARED, explicitShared.actorKind());

        Ast.Program privateProgram = Parser.parse("""
                pub isoactor routine worker(int value): int {
                  return value;
                }
                """);
        Ast.FunctionDecl privateActor = (Ast.FunctionDecl) privateProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.PRIVATE, privateActor.actorKind());
        assertEquals(Ast.CallableKind.ROUTINE, privateActor.kind());
    }

    @Test
    void sharedWithoutActorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared fnc nope(): void {
                  return;
                }
                """));
    }

    @Test
    void actorCallablesMayBeCalledButActorClassesCannotBeConstructedOrdinarily() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor fnc worker(int value): int {
                  return value;
                }

                pub fnc good(): int {
                  return worker(1);
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor Account {
                  let int balance = 100;

                  pub fnc read(): int {
                    return self.balance;
                  }
                }

                pub fnc bad(): Account {
                  return new Account();
                }
                """)));
    }

    @Test
    void rejectsDuplicateAndConflictingActorModifiers() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared shared actor Account {
                  let balance = 1;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub private actor fnc worker(): void {
                  return;
                }
                """));
    }

    @Test
    void actorFunctionCannotBeProgramMain() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc main(): void {
                  return;
                }
                """)));
    }


    @Test
    void actorSelfAndMutableActorStateCannotEscapeMailboxTurn() {
        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc leak(): Account {
                        return self;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc leak(): &mut Account {
                        return &mut self;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc leak(): &mut Account {
                        val alias = &mut self;
                        return alias;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let Array<int> items = [1, 2, 3];

                      pub fnc leak(): Array<int> {
                        return self.items;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void actorMayReturnCopyLikeStateButCannotPassSelfBorrowToOrdinaryFunction() {
        assertDoesNotThrow(() -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc current(): int {
                        return self.balance;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    fnc inspect(&Account account): int {
                      return 1;
                    }

                    shared actor Account {
                      let balance = 100;

                      pub fnc inspectSelf(): int {
                        return inspect(&self);
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }


    @Test
    void actorInheritanceMustPreserveIsolationKind() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                shared actor Parent {
                }

                shared actor Child extends Parent {
                  pub fnc current(): int {
                    return 1;
                  }
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                isoactor Parent {
                }

                shared actor Child extends Parent {
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor Child extends Object {
                  let value = 1;
                }
                """)));
    }



@Test
    void parsesReusableUnderscoreDestructureDiscards() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main(): void {
                    [const foo, _, let bar] = (1, 2, 3);
                    [_, _, const tail] = (4, 5, 6);
                    [const z, _, let y] = (7, 8, 9);
                    stdio.println(foo);
                    stdio.println(bar);
                    stdio.println(tail);
                    stdio.println(z);
                    stdio.println(y);
                    return;
                  }
                end
                """));

        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.DestructureStmt first = (Ast.DestructureStmt) main.body().getFirst();
        assertFalse(first.bindings().getFirst().isDiscard());
        assertTrue(first.bindings().get(1).isDiscard());

        Ast.DestructureStmt second = (Ast.DestructureStmt) main.body().get(1);
        assertTrue(second.bindings().getFirst().isDiscard());
        assertTrue(second.bindings().get(1).isDiscard());
        assertFalse(second.bindings().get(2).isDiscard());
    }

@Test
    void reservedWordsMayNameMembersButRemainReservedLexically() {
        assertDoesNotThrow(() -> Parser.parse("""
                define module app
                  fnc main(): void {
                    val mutex = SharedMutex.new(arr[1, 2, 3]);
                    return;
                  }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc main(): void {
                    val new = 1;
                    return;
                  }
                end
                """));
    }

    @Test
    void stopDoAndDoneAreReservedButMayNameCallables() {
        var tokens = new Lexer("stop do done").scan();
        assertEquals(Token.Type.STOP, tokens.get(0).type());
        assertEquals(Token.Type.DO, tokens.get(1).type());
        assertEquals(Token.Type.DONE, tokens.get(2).type());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc stop(): int { return 1; }
                  fnc do(): int { return 2; }
                  routine done(): int { return 3; }

                  fnc total(): int {
                    return stop() + do() + done();
                  }
                end
                """)));

        assertDoesNotThrow(() -> Parser.parse("""
                import fnc {stop, do, done} from './flow';

                define module app
                  fnc main(): void { return; }
                end
                """));

        assertDoesNotThrow(() -> Parser.parse("""
                define class Flow as
                  pub stop(): int { return 1; }
                  pub do(): int { return 2; }
                  pub done(): int { return 3; }
                end

                define interface FlowApi
                  fnc stop() => int;
                  fnc do() => int;
                  fnc done() => int;
                end
                """));
    }

    @Test
    void stopDoAndDoneCannotBeUsedAsOrdinaryIdentifiers() {
        for (String keyword : List.of("stop", "do", "done")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define module app
                      fnc main(): void {
                        val %s = 1;
                        return;
                      }
                    end
                    """.formatted(keyword)));

            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define module app
                      fnc take(int %s): void { return; }
                    end
                    """.formatted(keyword)));

            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define class %s as
                    end
                    """.formatted(keyword)));

            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define module app
                      fnc %s(): int { return 1; }
                      fnc main(): void {
                        val callback = %s;
                        return;
                      }
                    end
                    """.formatted(keyword, keyword)));

            if (!keyword.equals("done")) assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define class Flow as
                      pub %s(): int { return 1; }
                    end
                    define module app
                      fnc main(): void {
                        val flow = new Flow();
                        val callback = flow.%s;
                        return;
                      }
                    end
                    """.formatted(keyword, keyword)));
        }
    }

    @Test
    void doneIsReadableAsAProtocolMemberButIsNotALexicalIdentifier() {
        assertDoesNotThrow(() -> Parser.parse("fnc check(IteratorResult<int> result): bool { return result.done; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("fnc bad(): void { val done = true; return; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("fnc bad(): bool { return done; }"));
    }

    @Test
    void classDeclarationsRequireAsDelimiter() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class CounterState as
                  pub let int value = 10;
                end
                """));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class CounterState
                          pub let int value = 10;
                        end
                        """));

        assertTrue(failure.getMessage().contains("expected 'as' after class header"));
    }

}
