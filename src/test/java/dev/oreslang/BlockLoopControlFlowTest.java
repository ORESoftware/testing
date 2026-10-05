package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class BlockLoopControlFlowTest {
    @Test
    void blockCreatesAStandaloneLexicalScope() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  block {
                    val hidden = 1;
                    stdio.stdout.write(hidden);
                  }
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  block {
                    val hidden = 1;
                  }
                  stdio.stdout.write(hidden);
                }
                """)));
    }

    @Test
    void blockHasIndependentShadowingAndDeferLifetime() throws Exception {
        String output = run("""
                pub routine main(): void {
                  val value = "outer";
                  block {
                    val value = "inner";
                    stdio.stdout.write(value);
                    defer stdio.stdout.write("-defer");
                  }
                  stdio.stdout.write("-");
                  stdio.stdout.write(value);
                }
                """);

        assertEquals("inner-defer-outer", output);
    }

    @Test
    void forOfBreakAndContinueTargetTheIteratorLoop() throws Exception {
        String output = run("""
                pub routine main(): void {
                  for (val item of arr[1, 2, 3, 4]) {
                    if item == 2 {
                      continue;
                    } fi
                    stdio.stdout.write(item);
                    if item == 3 {
                      break;
                    } fi
                  }
                }
                """);

        assertEquals("13", output);
    }

    @Test
    void blockAndLoopPreserveProperTailCallPosition() throws Exception {
        String output = run("""
                fnc countdown(int remaining): int {
                  block {
                    if remaining == 0 {
                      return 7;
                    } fi
                    loop {
                      return countdown(remaining - 1);
                    }
                  }
                }

                pub routine main(): void {
                  stdio.stdout.write(countdown(20000));
                }
                """);

        assertEquals("7", output);
    }

    @Test
    void loopSupportsBreakContinueAndMandatorySafepoints() throws Exception {
        String output = run("""
                pub routine main(): void {
                  let i = 0;
                  loop {
                    i = i + 1;
                    if i == 2 {
                      continue;
                    } fi
                    if i == 4 {
                      break;
                    } fi
                    stdio.stdout.write(i);
                  }
                  stdio.stdout.write(process.descriptor.scheduler_safepoints);
                }
                """);

        assertEquals("134", output);
    }

    @Test
    void returnEscapesLoopAndTheEnclosingCallable() throws Exception {
        String output = run("""
                fnc answer(): int {
                  loop {
                    return 7;
                  }
                }

                pub routine main(): void {
                  stdio.stdout.write(answer());
                }
                """);

        assertEquals("7", output);
    }

    @Test
    void breakAndContinueAreRejectedOutsideLoops() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  break;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  continue;
                }
                """)));
    }

    @Test
    void loopControlCannotCrossLambdaBoundary() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  loop {
                    val callback = || -> {
                      break;
                    };
                    break;
                  }
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  loop {
                    val callback = || -> {
                      continue;
                    };
                    break;
                  }
                }
                """)));
    }

    @Test
    void nestedLoopControlTargetsTheNearestLoop() throws Exception {
        String output = run("""
                pub routine main(): void {
                  let outer = 0;
                  loop {
                    outer = outer + 1;
                    let inner = 0;
                    loop {
                      inner = inner + 1;
                      if inner == 1 {
                        continue;
                      } fi
                      break;
                    }
                    stdio.stdout.write(outer);
                    if outer == 2 {
                      break;
                    } fi
                  }
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void conventionalForContinueStillRunsTheUpdateExpression() throws Exception {
        String output = run("""
                pub routine main(): void {
                  let seen = 0;
                  for (let i = 0; i < 3; i = i + 1) {
                    if i < 2 {
                      continue;
                    } fi
                    seen = i;
                  }
                  stdio.stdout.write(seen);
                }
                """);

        assertEquals("2", output);
    }

    @Test
    void loopControlRunsFinallyAndIsNotCaughtAsAGuestException() throws Exception {
        String output = run("""
                pub routine main(): void {
                  loop {
                    try {
                      break;
                    } catch (err) {
                      stdio.stdout.write("caught");
                    } finally {
                      stdio.stdout.write("finally");
                    }
                  }
                  stdio.stdout.write("after");
                }
                """);

        assertEquals("finallyafter", output);
    }

    @Test
    void deferRunsWhenBreakContinueAndReturnUnwindScopes() throws Exception {
        String output = run("""
                fnc finish(): void {
                  defer stdio.stdout.write("r");
                  loop {
                    return;
                  }
                }

                pub routine main(): void {
                  let i = 0;
                  loop {
                    i = i + 1;
                    defer stdio.stdout.write(i);
                    if i == 1 {
                      continue;
                    } fi
                    break;
                  }
                  finish();
                }
                """);

        assertEquals("12r", output);
    }

    @Test
    void loopAndBlockRemainUsableAsCallableNamesAndFirstClassReferences() throws Exception {
        String output = run("""
                fnc loop(): int {
                  return 3;
                }

                fnc block(): int {
                  return 4;
                }

                pub routine main(): void {
                  val loop_ref = loop;
                  val block_ref = block;
                  stdio.stdout.write(loop_ref());
                  stdio.stdout.write(block_ref());
                }
                """);

        assertEquals("34", output);
    }

    @Test
    void ifSupportsBraceAndThenFiForms() throws Exception {
        String braces = run("""
                pub routine main(): void {
                  val value = 2;
                  if value == 1 {
                    stdio.stdout.write("a");
                  } elseif value == 2 {
                    stdio.stdout.write("b");
                  } else {
                    stdio.stdout.write("c");
                  } fi
                }
                """);
        assertEquals("b", braces);

        String keywordDelimited = run("""
                pub routine main(): void {
                  val value = 2;
                  if value == 1 then
                    stdio.stdout.write("a");
                  elseif value == 2 then
                    stdio.stdout.write("b");
                  else
                    stdio.stdout.write("c");
                  fi
                }
                """);
        assertEquals("b", keywordDelimited);
    }

    @Test
    void legacyIfDoFiRemainsSourceCompatible() throws Exception {
        String output = run("""
                pub routine main(): void {
                  if true do
                    stdio.stdout.write("ok");
                  fi
                }
                """);
        assertEquals("ok", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "block-loop.ores")
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
