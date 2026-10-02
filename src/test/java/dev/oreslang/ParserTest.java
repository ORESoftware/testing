package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
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
    void numericLiteralFailuresUseOreslangDiagnostics() {
        IllegalArgumentException integer = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module app
                          pub fnc main() => void {
                            val x = 999999999999999999999999999999999999;
                            return;
                          }
                        end
                        """));
        assertTrue(integer.getMessage().contains("signed 64-bit"));

        IllegalArgumentException floating = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module app
                          pub fnc main() => void {
                            val x = 1e9999;
                            return;
                          }
                        end
                        """));
        assertTrue(floating.getMessage().contains("finite"));
    }

    @Test
    void signedMinimumLiteralIsRepresentableButLargerMagnitudesAreNot() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc min_value() => int {
                    return -9223372036854775808;
                  }
                end
                """)));

        IllegalArgumentException tooSmall = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module app
                          pub fnc bad() => int {
                            return -9223372036854775809;
                          }
                        end
                        """));
        assertTrue(tooSmall.getMessage().contains("signed 64-bit"));
    }

    @Test
    void unsupportedNumericFamiliesFailInsteadOfMasqueradingAsLongOrDouble() {
        for (String type : java.util.List.of(
                "i8", "i16", "i32", "u8", "u16", "u32", "u64",
                "uint", "bigint", "f32", "decimal", "complex64")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "fnc bad(" + type + " value) => void { return; }")));
            assertTrue(failure.getMessage().contains("reserved but not implemented"), type);
        }

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc supported(i64 integer_value, f64 float_value, complex128 complex_value) => void {
                  return;
                }
                """)));
    }

    @Test
    void lexerRecognizesLambdaAndFatReturnArrows() {
        var tokens = new Lexer("(int x) -> x + 1; fnc f() => int { return 1; }").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ARROW));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.FAT_ARROW));
    }
}
