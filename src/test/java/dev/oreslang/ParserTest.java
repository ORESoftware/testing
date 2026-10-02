package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ParserTest {
    @Test
    void supportsMultipleModulesAndComplexNumbers() {
        String source = """
                define module math
                  fnc z() => complex {
                    return 3 + 4i;
                  }
                end

                define module app
                  pub fnc main() => void {
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
                  fnc choose(bool a, bool b) => int {
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
                  define class x
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
    void lexerRecognizesLambdaAndFatReturnArrows() {
        var tokens = new Lexer("(int x) -> x + 1; fnc f() => int { return 1; }").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ARROW));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.FAT_ARROW));
    }
    @Test
    void parsesSharedActorAndAllowsMailboxOwnedStateMutation() {
        String source = """
                shared actor Account {
                  let balance = 100;

                  pub fnc withdraw(int amount) => void {
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
    void actorFncDefaultsPrivateAndCanBeExplicitlyShared() {
        Ast.Program privateProgram = Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }
                """);
        Ast.FunctionDecl privateActor = (Ast.FunctionDecl) privateProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.PRIVATE, privateActor.actorKind());

        Ast.Program sharedProgram = Parser.parse("""
                pub shared actor fnc worker(int value) => int {
                  return value;
                }
                """);
        Ast.FunctionDecl sharedActor = (Ast.FunctionDecl) sharedProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.SHARED, sharedActor.actorKind());
    }

    @Test
    void sharedWithoutActorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared fnc nope() => void {
                  return;
                }
                """));
    }

    @Test
    void actorEntrypointsCannotBeCalledOrConstructedAsOrdinaryValues() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }

                pub fnc bad() => int {
                  return worker(1);
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor Account {
                  let int balance = 100;

                  pub fnc read() => int {
                    return self.balance;
                  }
                }

                pub fnc bad() => Account {
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
                pub private actor fnc worker() => void {
                  return;
                }
                """));
    }

    @Test
    void actorFunctionCannotBeProgramMain() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc main() => void {
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

                      pub fnc leak() => Account {
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

                      pub fnc leak() => &mut Account {
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

                      pub fnc leak() => &mut Account {
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

                      pub fnc leak() => Array<int> {
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

                      pub fnc current() => int {
                        return self.balance;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    fnc inspect(&Account account) => int {
                      return 1;
                    }

                    shared actor Account {
                      let balance = 100;

                      pub fnc inspectSelf() => int {
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
                  let value = 1;
                }

                shared actor Child extends Parent {
                  pub fnc current() => int {
                    return self.value;
                  }
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor Parent {
                  let value = 1;
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
                  pub fnc main() => void {
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
                  fnc main() => void {
                    val mutex = SharedMutex.new(arr[1, 2, 3]);
                    return;
                  }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc main() => void {
                    val new = 1;
                    return;
                  }
                end
                """));
    }

}
