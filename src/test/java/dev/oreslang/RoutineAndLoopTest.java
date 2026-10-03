package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class RoutineAndLoopTest {
    @Test
    void exactFncProgramCompilesAndRuns() throws Exception {
        String program = """
                define module x as
                  define class y as
                  end
                end

                pub fnc main() => void {
                  val y = new x.y();
                  stdio.stdout.write(y);
                }
                """;
        String output = run(program);
        assertTrue(output.contains("y{}"));
    }

    @Test
    void routineMainCompilesWithSafeSemicolonOmission() throws Exception {
        String program = """
                define module x as
                  define class y as
                  end
                end

                pub routine main() => void {
                  val y = new x.y();
                  stdio.stdout.write(y)
                }
                """;
        String output = run(program);
        assertTrue(output.contains("y{}"));
    }

    @Test
    void routinesCannotParticipateInRecursionButFncsCan() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                routine spin() => void {
                  spin();
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc recurse(bool stop) => void {
                  if stop; do
                    return;
                  else
                    recurse(true);
                    return;
                  fi
                }
                """)));
    }

    @Test
    void methodsOverloadOnlyByArity() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module m as
                  define class C as
                    pub find() => int { return 0; }
                    pub find(int value) => int { return value; }
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module m as
                  define class C as
                    pub find(int value) => int { return value; }
                    pub find(String value) => int { return 1; }
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc find() => int { return 0; }
                fnc find(int value) => int { return value; }
                """)));
    }

    @Test
    void explicitlyTypedLambdasCanRecurse() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let Fnc<int, int> fact = |int n| -> {
                    return n == 0 ? 1 : n * fact(n - 1);
                  };
                  stdio.stdout.write(fact(5))
                }
                """);
        assertEquals("120", output);
    }

    @Test
    void ternaryWorksWithOption() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc find(bool found) => Option<int> {
                  return found ? Some(42) : None;
                }
                """)));
    }

    @Test
    void structuralParametersAreOptInAndSupportBrandedInterfaces() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Bar {
                  fnc markerBrand() => String;
                }

                pub interface Foo extends Bar {
                }

                define class Branded as
                  pub markerBrand() => String {
                    return "marking/branding";
                  }
                end

                fnc structural(@Structural Foo value) => String {
                  return value.markerBrand();
                }

                fnc main() => void {
                  val branded = new Branded();
                  stdio.println(structural(branded));
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub interface Foo {
                  fnc markerBrand() => String;
                }

                define class Branded as
                  pub markerBrand() => String {
                    return "marking/branding";
                  }
                end

                fnc nominal(Foo value) => String {
                  return value.markerBrand();
                }

                fnc main() => void {
                  val branded = new Branded();
                  stdio.println(nominal(branded));
                  return;
                }
                """)));
    }

    @Test
    void forOfInjectsSchedulerSafepoints() throws Exception {
        String output = run("""
                pub routine main() => void {
                  for (val item of arr[1, 2, 3]) {
                    stdio.stdout.write(item);
                  }
                  stdio.stdout.write(process.descriptor.scheduler_safepoints)
                }
                """);
        assertTrue(output.startsWith("123"));
        assertTrue(output.endsWith("3"));
    }

    @Test
    void conventionalForLoopAlsoInjectsSafepoints() throws Exception {
        String output = run("""
                pub routine main() => void {
                  for (let i = 0; i < 3; i = i + 1) {
                    stdio.stdout.write(i);
                  }
                  stdio.stdout.write(process.descriptor.scheduler_safepoints)
                }
                """);
        assertEquals("0123", output);
    }

    @Test
    void customJavascriptStyleIteratorDrivesForOf() throws Exception {
        String output = run("""
                define module collections as
                  define class Bag as
                    pub [Symbol.iterator]() => Array<int> {
                      return arr[4, 5];
                    }
                  end
                end

                pub routine main() => void {
                  val bag = new collections.Bag();
                  for (val item of bag) {
                    stdio.stdout.write(item)
                  }
                }
                """);
        assertEquals("45", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "together.ores")
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
