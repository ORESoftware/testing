package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class CallableSemanticsTest {

    @Test
    void namespaceModulesMainFncRoutineAndLambdaCompose() throws Exception {
        String output = run("""
                namespace demo;

                define module math
                  pub fnc factorial(int n) => int {
                    return n == 0 ? 1 : n * factorial(n - 1);
                  }

                  pub fnc offset(int x) => int {
                    return x + 10;
                  }
                end

                pub routine main() => void {
                  val int base = 7;
                  val Fnc<int, int> lexical = |int x| -> {
                    return x + base;
                  };
                  val Fnc<int, int> isolated = nlex |int x| -> {
                    val int base = 1;
                    return math.offset(x) + base;
                  };
                  stdio.stdout.write(math.factorial(5));
                  stdio.stdout.write(":");
                  stdio.stdout.write(lexical(2));
                  stdio.stdout.write(":");
                  stdio.stdout.write(isolated(2));
                  return;
                }
                """);

        assertEquals("120:9:13", output);
    }

    @Test
    void deepGuestRecursionFailsBeforeJvmStackOverflow() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                fnc descend(int n) => int {
                  return n == 0 ? 0 : descend(n - 1);
                }

                pub routine main() => void {
                  stdio.stdout.write(descend(200));
                  return;
                }
                """));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("call-depth limit"));
    }

    @Test
    void explicitNlexLambdaCannotCaptureOuterLocal() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc make() => (() -> int) {
                          val int local = 42;
                          return nlex || -> {
                            return local;
                          };
                        }
                        """)));
        assertTrue(error.getMessage().contains("local") || error.getMessage().contains("unknown name"));
    }

    @Test
    void nlexNamedCallableMakesNestedLambdasNonCapturing() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        nlex fnc make() => (() -> int) {
                          val int local = 42;
                          return || -> {
                            return local;
                          };
                        }
                        """)));
        assertTrue(error.getMessage().contains("local") || error.getMessage().contains("unknown name"));
    }

    @Test
    void nlexStillResolvesModulesGlobalsAndOwnShadowingLocals() throws Exception {
        String output = run("""
                define module math
                  pub fnc one() => int { return 1; }
                end

                pub routine main() => void {
                  val int value = 99;
                  val Fnc<int> callback = nlex || -> {
                    val int value = 2;
                    return math.one() + value;
                  };
                  stdio.stdout.write(callback());
                  stdio.stdout.write(value);
                  return;
                }
                """);
        assertEquals("399", output);
    }

    @Test
    void contextualLambdaTypesSupportTheDemoProgram() throws Exception {
        String output = run("""
                namespace nlex_demo;

                type IntFn = typeof fnc(int value) -> int;

                define module math
                  pub fnc factorial(int n) => int {
                    return n == 0 ? 1 : n * factorial(n - 1);
                  }

                  pub fnc offset(int value) => int {
                    return value + 10;
                  }
                end

                nlex fnc makeOffsetter() => IntFn {
                  return |value| -> {
                    return math.offset(value);
                  };
                }

                pub routine main() => void {
                  val int outer_bias = 100;

                  val IntFn lexical = |value| -> {
                    return value + outer_bias;
                  };

                  val IntFn explicit_nlex = nlex |value| -> {
                    val int outer_bias = 1;
                    return math.offset(value) + outer_bias;
                  };

                  val IntFn inherited_nlex = makeOffsetter();

                  stdio.stdout.write(math.factorial(5));
                  stdio.stdout.write(":");
                  stdio.stdout.write(lexical(2));
                  stdio.stdout.write(":");
                  stdio.stdout.write(explicit_nlex(2));
                  stdio.stdout.write(":");
                  stdio.stdout.write(inherited_nlex(5));
                  return;
                }
                """);

        assertEquals("120:102:13:15", output);
    }

    @Test
    void deferRunsAllCleanupsAndPreservesCleanupFailure() throws Exception {
        String program = """
                fnc explode() => int {
                  return 1 / 0;
                }

                pub routine main() => void {
                  defer stdio.stdout.write("A");
                  defer explode();
                  defer stdio.stdout.write("B");
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "defer-failure.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            Exception failure = assertThrows(Exception.class, () -> context.eval(source));
            assertNotNull(failure.getMessage());
            assertTrue(failure.getMessage().contains("division by zero"));
        }

        assertEquals("BA", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void guestCatchHandlesOreslangRuntimeErrors() throws Exception {
        String output = run("""
                pub routine main() => void {
                  try {
                    val ignored = 1 / 0;
                  } catch (err) {
                    stdio.stdout.write("arithmetic");
                  } finally {
                    stdio.stdout.write(":finally");
                  }

                  val values = arr["only"];
                  try {
                    stdio.stdout.write(values[9]);
                  } catch (err) {
                    stdio.stdout.write(":bounds");
                  } finally {
                    stdio.stdout.write(":done");
                  }
                  return;
                }
                """);

        assertEquals("arithmetic:finally:bounds:done", output);
    }

    @Test
    void guestCatchDoesNotTurnStaticOrHostBugsIntoApplicationControlFlow() {
        IllegalArgumentException staticFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          try {
                            unknown_name();
                          } catch (err) {
                            stdio.stdout.write("must-not-run");
                          } finally {
                          }
                          return;
                        }
                        """)));
        assertTrue(staticFailure.getMessage().contains("unknown name"));
    }

    @Test
    void routineAndFncRemainSemanticallyDistinct() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        routine loop() => void {
                          loop();
                        }
                        """)));

        assertDoesNotThrow(() ->
                TypeChecker.check(Parser.parse("""
                        fnc loop(bool finished) => void {
                          if finished; do
                            return;
                          else
                            loop(true);
                            return;
                          fi
                        }
                        """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "callables.ores")
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
