package dev.oreslang;

import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class InitializationBarrierTest {
    @Test
    void moduleAndRootInitHooksRunBeforeMainAfterAllModulesAreIndexed() throws Exception {
        String output = run("""
                define module early
                  routine init() : void  {
                    stdio.stdout.write("early:");
                    later.ping();
                    stdio.stdout.write("|");
                    return;
                  }
                end

                define module later
                  pub fnc ping() : void  {
                    stdio.stdout.write("linked");
                    return;
                  }

                  fnc init() : void  {
                    stdio.stdout.write("later|");
                    return;
                  }
                end

                fnc init() : void  {
                  stdio.stdout.write("root|");
                  return;
                }

                pub routine main() : void  {
                  stdio.stdout.write("main");
                  return;
                }
                """);

        assertEquals("early:linked|later|root|main", output);
    }

    @Test
    void initMayBeFncOrRoutineButMustRemainClosedAndSynchronous() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module a
                  fnc init() : void  { return; }
                end

                define module b
                  routine init() : void  { return; }
                end

                fnc init() : void  { return; }
                pub routine main() : void  { return; }
                """)));

        assertInitRejected("fnc init(int value) : void  { return; }");
        assertInitRejected("fnc init<T>() : void  { return; }");
        assertInitRejected("fnc init() : int  { return 1; }");
        assertInitRejected("async fnc init() : void  { return; }");
        assertInitRejected("pub fnc init() : void  { return; }");
        assertInitRejected("actor fnc init() : void  { return; }");
        assertInitRejected("shared actor fnc init() : void  { return; }");
    }

    @Test
    void moduleInitUsesTheSameLifecycleRestrictions() {
        assertInitRejected("""
                define module bad
                  pub routine init() : void  { return; }
                end
                """);

        assertInitRejected("""
                define module bad
                  actor routine init() : void  { return; }
                end
                """);

        assertInitRejected("""
                define module bad
                  routine init(String value) : void  { return; }
                end
                """);
    }

    @Test
    void initCannotBeCalledDirectlyByGuestCode() {
        IllegalArgumentException rootCall = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc init() : void  { return; }

                        pub routine main() : void  {
                          init();
                          return;
                        }
                        """)));
        assertTrue(rootCall.getMessage().contains("cannot be called directly"));

        IllegalArgumentException moduleCall = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module lifecycle
                          fnc init() : void  { return; }
                        end

                        pub routine main() : void  {
                          lifecycle.init();
                          return;
                        }
                        """)));
        assertTrue(moduleCall.getMessage().contains("cannot be called directly"));
    }

    @Test
    void mainOnlyCommandCannotBypassInitializationBarrier() throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, """
                fnc init() : void  {
                  return;
                }

                pub routine main() : void  {
                  return;
                }
                """, "barrier.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            Value unit = context.parse(source);

            unit.execute(OresEvalRootNode.LINK_ONLY_COMMAND);

            PolyglotException premature = assertThrows(
                    PolyglotException.class,
                    () -> unit.execute(OresEvalRootNode.MAIN_ONLY_COMMAND));
            assertTrue(premature.getMessage().contains("main cannot run before successful initialization"));

            unit.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
            assertDoesNotThrow(() -> unit.execute(OresEvalRootNode.MAIN_ONLY_COMMAND));
        }
    }

    @Test
    void initFailureIsTerminalAndMainNeverRuns() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, """
                fnc init() : void  {
                  stdio.stdout.write("I");
                  val values = arr[1];
                  val impossible = values[9];
                  return;
                }

                pub routine main() : void  {
                  stdio.stdout.write("M");
                  return;
                }
                """, "failed-init.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            Value unit = context.parse(source);
            unit.execute(OresEvalRootNode.LINK_ONLY_COMMAND);

            assertThrows(PolyglotException.class, () -> unit.execute(OresEvalRootNode.INIT_ONLY_COMMAND));
            assertThrows(PolyglotException.class, () -> unit.execute(OresEvalRootNode.MAIN_ONLY_COMMAND));
        }

        assertEquals("I", output.toString(StandardCharsets.UTF_8));
    }

    private static void assertInitRejected(String source) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(
                error.getMessage().contains("init hook"),
                () -> "unexpected init diagnostic: " + error.getMessage());
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "init-barrier.ores")
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
