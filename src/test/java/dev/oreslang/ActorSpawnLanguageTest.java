package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSpawnLanguageTest {

    @Test
    void parsesSpawnAsDedicatedExpression() {
        Ast.Program program = Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }

                pub routine main() => void {
                  val pending = spawn worker(1);
                  val ready = await spawn worker(2);
                  return;
                }
                """);

        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        Ast.BindingStmt pending = (Ast.BindingStmt) main.body().getFirst();
        assertInstanceOf(Ast.SpawnExpr.class, pending.initializer());

        Ast.BindingStmt ready = (Ast.BindingStmt) main.body().get(1);
        Ast.AwaitExpr awaited = assertInstanceOf(Ast.AwaitExpr.class, ready.initializer());
        assertInstanceOf(Ast.SpawnExpr.class, awaited.expression());

        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void actorCallableRequiresSpawn() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc worker(int value) => int {
                          return value;
                        }

                        pub routine main() => void {
                          val nope = worker(1);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("spawn"));
    }

    @Test
    void spawnRejectsOrdinaryCallable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc ordinary(int value) => int {
                          return value;
                        }

                        pub routine main() => void {
                          val nope = spawn ordinary(1);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("not declared with the actor keyword"));
    }

    @Test
    void voidActorCallableDoesNotExposeResultFuture() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor routine worker() => void {
                          return;
                        }

                        pub routine main() => void {
                          val pending = spawn worker();
                          val nope = pending.result;
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("void actor callable has no result"));
    }

    @Test
    void readyRefIsIdentityControlOnlyForOneShotCallable() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }

                pub routine main() => void {
                  val pending = spawn worker(1);
                  val ready = await pending;
                  val id = ready.id;
                  val alive = ready.is_alive();
                  return;
                }
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc worker(int value) => int {
                          return value;
                        }

                        pub routine main() => void {
                          val ready = await spawn worker(1);
                          val denied = ready.mailbox;
                          return;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("mailbox")
                        || failure.getMessage().contains("ActorRef"),
                failure.getMessage());
    }

    @Test
    void nonVoidActorRoutineExposesResultFuture() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor routine compute(int value) => int {
                  return value + 1;
                }

                pub routine main() => void {
                  val pending = spawn compute(41);
                  val answer = await pending.result;
                  return;
                }
                """)));
    }

    @Test
    void spawnReturnsImmediatelyAndResultIsAwaitedSeparately() throws Exception {
        String program = """
                pub actor fnc add_one(int value) => int {
                  return value + 1;
                }

                pub routine main() => void {
                  val pending = spawn add_one(41);
                  val ready = await pending.ready;
                  val answer = await pending.result;
                  stdio.println(answer);
                  stdio.println(ready.id);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "actor-spawn.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("42"));
        assertTrue(rendered.contains("ActorId"));
    }

    @Test
    void sharedActorClassUsesMailboxSendSurface() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Counter as
                  let int value = 0;

                  constructor(initial: int) {
                    self.value = initial;
                  }

                  pub receive(delta: int): void {
                    self.value = self.value + delta;
                    return;
                  }
                end

                pub routine main() => void {
                  val counter = spawn Counter(40);
                  counter.send(2);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Counter as
                  pub receive(delta: int): void { return; }
                end

                fnc bad() -> void {
                  val counter = spawn Counter();
                  counter.receive(2);
                  return;
                }
                """)));
    }

    @Test
    void untrustedActorCannotSpawnChildren() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc child() => int {
                          return 1;
                        }

                        pub untrusted actor fnc sandbox() => void {
                          val denied = spawn child();
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("untrusted actors cannot spawn child actors"));
    }

    @Test
    void isoactorCannotEscalateBySpawningSharedActorCallable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc shared_child() => int {
                          return 1;
                        }

                        pub isoactor routine private_parent() => void {
                          val denied = spawn shared_child();
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("SHARED_MEMORY"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub isoactor fnc private_child() => int {
                  return 1;
                }

                pub isoactor routine private_parent() => void {
                  val child = spawn private_child();
                  return;
                }
                """)));
    }

    @Test
    void untrustedActorCannotInspectForeignActorLiveness() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        shared actor Worker {
                          pub receive(value: int): void { return; }
                        }

                        pub untrusted actor fnc probe(ActorRef<Worker> target) => bool {
                          return target.is_alive();
                        }
                        """)));
        assertTrue(failure.getMessage().contains("lifecycle"), failure.getMessage());
    }

    @Test
    void trustedActorMaySpawnWithoutSynchronouslyWaiting() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor fnc child() => int {
                  return 1;
                }

                pub actor routine parent() => void {
                  val pending = spawn child();
                  val id = pending.id;
                  return;
                }
                """)));
    }

}
