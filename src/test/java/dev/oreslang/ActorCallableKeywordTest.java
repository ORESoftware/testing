package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCallableKeywordTest {

    @Test
    void actorIsSharedAndIsoactorIsPrivateForFunctionsRoutinesAndClasses() {
        List<Token> tokens = new Lexer("actor isoactor").scan();
        assertEquals(Token.Type.ACTOR, tokens.get(0).type());
        assertEquals(Token.Type.ISOACTOR, tokens.get(1).type());

        Ast.Program program = Parser.parse("""
                actor fnc shared_fnc() => void { return; }
                actor routine shared_routine() => void { return; }
                isoactor fnc private_fnc() => void { return; }
                isoactor routine private_routine() => void { return; }

                actor SharedBox {
                  let int value = 1;
                  pub receive(int message): void { return; }
                }

                isoactor PrivateBox {
                  let int value = 1;
                  pub receive(int message): void { return; }
                }
                """);

        List<Ast.Decl> declarations = program.modules().getFirst().declarations();

        assertEquals(Ast.ActorKind.SHARED, ((Ast.FunctionDecl) declarations.get(0)).actorKind());
        assertEquals(Ast.CallableKind.FNC, ((Ast.FunctionDecl) declarations.get(0)).kind());

        assertEquals(Ast.ActorKind.SHARED, ((Ast.FunctionDecl) declarations.get(1)).actorKind());
        assertEquals(Ast.CallableKind.ROUTINE, ((Ast.FunctionDecl) declarations.get(1)).kind());

        assertEquals(Ast.ActorKind.PRIVATE, ((Ast.FunctionDecl) declarations.get(2)).actorKind());
        assertEquals(Ast.CallableKind.FNC, ((Ast.FunctionDecl) declarations.get(2)).kind());

        assertEquals(Ast.ActorKind.PRIVATE, ((Ast.FunctionDecl) declarations.get(3)).actorKind());
        assertEquals(Ast.CallableKind.ROUTINE, ((Ast.FunctionDecl) declarations.get(3)).kind());

        assertEquals(Ast.ActorKind.SHARED, ((Ast.ClassDecl) declarations.get(4)).actorKind());
        assertEquals(Ast.ActorKind.PRIVATE, ((Ast.ClassDecl) declarations.get(5)).actorKind());
    }

    @Test
    void actorCallablesSpawnFreshActorsAndPreserveDeclaredResults() throws Exception {
        String output = run("""
                actor fnc add_one(int value) => int {
                  return value + 1;
                }

                actor routine shared_emit(String value) => void {
                  stdio.stdout.write(value);
                  return;
                }

                isoactor fnc double_it(int value) => int {
                  return value * 2;
                }

                isoactor routine private_emit(String value) => void {
                  stdio.stdout.write(value);
                  return;
                }

                pub routine main() => void {
                  val add = spawn add_one(41);
                  stdio.stdout.write(await add.result);
                  stdio.stdout.write(":");

                  val shared = spawn shared_emit("shared");
                  await shared.done;
                  stdio.stdout.write(":");

                  val doubled = spawn double_it(21);
                  stdio.stdout.write(await doubled.result);
                  stdio.stdout.write(":");

                  val private_spawn = spawn private_emit("private");
                  await private_spawn.done;
                  return;
                }
                """);

        assertEquals("42:shared:42:private", output);
    }

    @Test
    void actorKeywordsRemainReservedButActorBuiltinNamespaceStillParses() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main() => void {
                  val int isoactor = 1;
                  return;
                }
                """));

        assertDoesNotThrow(() -> Parser.parse("""
                pub routine main() => void {
                  actor.gc();
                  return;
                }
                """));
    }

    @Test
    void actorMainRemainsForbidden() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub isoactor routine main() => void { return; }
                """)));
    }

    @Test
    void actorBoundaryTypesAreCheckedStatically() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(Mutex<int> value) => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(MutexGuard<int> value) => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                isoactor fnc invalid(SharedMutex<int> value) => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(Future<int> value) => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(&int value) => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(SharedMutex<int> value) => void { return; }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc valid(RwLock<int> value) => void {
                  val reader = value.read_lock();
                  val snapshot = reader.value();
                  reader.release();
                  return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                isoactor fnc valid(int value) => int { return value; }
                """)));
    }

    @Test
    void actorTurnsCannotSynchronouslyInvokeAnotherActorCallable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc child(int value) => int {
                          return value + 1;
                        }

                        actor fnc parent(int value) => int {
                          return child(value);
                        }
                        """)));

        assertTrue(failure.getMessage().contains("spawn"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "actor-callables.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
