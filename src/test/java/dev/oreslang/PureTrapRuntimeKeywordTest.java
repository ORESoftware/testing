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

import static org.junit.jupiter.api.Assertions.*;

final class PureTrapRuntimeKeywordTest {
    @Test
    void reservesAllEffectAndRuntimeKeywords() {
        var tokens = new Lexer("pure trap lex nlex rt").scan();
        assertEquals(Token.Type.PURE, tokens.get(0).type());
        assertEquals(Token.Type.TRAP, tokens.get(1).type());
        assertEquals(Token.Type.LEX, tokens.get(2).type());
        assertEquals(Token.Type.NLEX, tokens.get(3).type());
        assertEquals(Token.Type.RT, tokens.get(4).type());
    }

    @Test
    void pureMayReadOutsideStateButCannotWriteIt() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  let int outside = 7;

                  pure fnc read_outside(): int {
                    return outside;
                  }
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          let int outside = 7;

                          pure fnc write_outside(): int {
                            outside = 8;
                            return outside;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("cannot write outside binding"));
    }

    @Test
    void pureCannotDeclareOrMutateInput() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pure fnc mutate_input(int mut value): int {
                          value = value + 1;
                          return value;
                        }
                        """)));
        assertTrue(error.getMessage().contains("mutable parameter"));
    }

    @Test
    void pureCallsAreTransitiveAndUnknownEffectsFailClosed() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pure fnc one(): int { return 1; }
                pure fnc two(): int { return one() + 1; }
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc impure(): int { return 1; }
                        pure fnc bad(): int { return impure(); }
                        """)));
        assertTrue(error.getMessage().contains("unproven-effect callable"));
    }

    @Test
    void lexCannotPierceAnInheritedNlexBarrier() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        nlex fnc factory(): (() => void) {
                          return lex || -> { return; };
                        }
                        """));
        assertTrue(error.getMessage().contains("cannot weaken"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                lex fnc factory(): (() => void) {
                  return lex || -> { return; };
                }
                """)));
    }

    @Test
    void lexAndNlexAreMutuallyExclusive() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        lex nlex fnc bad(): void { return; }
                        """));
        assertTrue(error.getMessage().contains("mutually exclusive"));
    }

    @Test
    void rtNamespaceIsMandatoryForDefer() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(): void {
                          defer || -> { return; };
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("runtime intrinsic"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): void {
                  rt defer || -> { return; };
                  rt defer(|| -> { return; });
                  return;
                }
                """)));
    }

    @Test
    void ownershipAndRecoveryRuntimeNamesAreReservedBehindRtAndFailClosedUntilRebased() {
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse("""
                fnc staged(int value): int {
                  return rt copy(value);
                }
                """));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed));
        assertTrue(error.getMessage().contains("rt copy is reserved"));
    }

    @Test
    void rtDeferCapturesCallbackAtRegistrationAndRunsLifo() throws Exception {
        String output = run("""
                pub fnc main(): void {
                  rt defer || -> {
                    stdio.println("first");
                    return;
                  };
                  rt defer(|| -> {
                    stdio.println("second");
                    return;
                  });
                  stdio.println("body");
                  return;
                }
                """);

        int body = output.indexOf("body");
        int second = output.indexOf("second");
        int first = output.indexOf("first");
        assertTrue(body >= 0 && second > body && first > second, output);
    }

    @Test
    void trapConvertsOrdinaryFailureToTupleButDoesNotChangeBodyReturnType() throws Exception {
        String output = run("""
                trap fnc remainder(int divisor): int {
                  return 10 % divisor;
                }

                pub fnc main(): void {
                  [const value, const err] = remainder(0);
                  stdio.println(value);
                  stdio.println(err);
                  return;
                }
                """);

        assertTrue(output.contains("None"), output);
        assertTrue(output.contains("Some("), output);
        assertTrue(output.contains("ArithmeticException"), output);
    }

    @Test
    void trapSupportsVoidWithUnitSuccessSlot() throws Exception {
        String output = run("""
                trap fnc cleanup(): void {
                  return;
                }

                pub fnc main(): void {
                  [const value, const err] = cleanup();
                  stdio.println(value);
                  stdio.println(err);
                  return;
                }
                """);

        assertTrue(output.contains("Some([])"), output);
        assertTrue(output.contains("None"), output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "effects.ores")
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
