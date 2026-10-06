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

final class EqualityIdentityOperatorTest {
    @Test
    void equalityAndIdentityWordsAreReservedOperatorsAndAliasesNormalize() {
        var tokens = new Lexer("eq neq is").scan();
        assertEquals(Token.Type.EQ, tokens.get(0).type());
        assertEquals(Token.Type.NEQ, tokens.get(1).type());
        assertEquals(Token.Type.IS, tokens.get(2).type());

        assertEquals("eq", binary("left eq right").operator());
        assertEquals("neq", binary("left neq right").operator());
        assertEquals("neq", binary("left !eq right").operator());
        assertEquals("eq", binary("left == right").operator());
        assertEquals("neq", binary("left != right").operator());
        assertEquals("is", binary("left is right").operator());

        Ast.Expr negated = new Parser(new Lexer("!(left eq right)").scan()).parseExpression();
        Ast.UnaryExpr unary = assertInstanceOf(Ast.UnaryExpr.class, negated);
        assertEquals("!", unary.operator());
        assertEquals("eq", assertInstanceOf(Ast.BinaryExpr.class, unary.operand()).operator());
    }

    @Test
    void equalityKeywordsCannotMasqueradeAsBindingsAndBangEqMustBeAdjacent() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(int eq): int {
                  return eq;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(int left, int right): bool {
                  return left ! eq right;
                }
                """));
    }

    @Test
    void assignmentInsideCallArgumentRemainsAssignmentNotNamedArgument() {
        Ast.Expr expression = new Parser(new Lexer("callStuff(foo = \"bar\")").scan()).parseExpression();
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, expression);
        assertEquals(1, call.arguments().size());
        Ast.AssignExpr assignment = assertInstanceOf(Ast.AssignExpr.class, call.arguments().getFirst());
        assertEquals("foo", assertInstanceOf(Ast.NameExpr.class, assignment.target()).name());
        assertEquals("bar", assertInstanceOf(Ast.LiteralExpr.class, assignment.value()).value());
    }

    @Test
    void expressionTypeTestsUseExplicitTypeMarkerWhileMatchPatternsStayCompact() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc classify(Animal animal): int {
                  if animal is type Dog dog then
                    return 1;
                  else
                    return 0;
                  fi
                }

                fnc matchClassify(Animal animal): int {
                  match first animal
                    is Dog dog -> { return 1; }
                    else -> { return 0; }
                  end
                }
                """)));
    }

    @Test
    void runtimeUsesOneValueEqualityContractAcrossOperatorsSwitchAndPatterns() throws Exception {
        String program = """
                pub fnc main(): void {
                  stdio.println(5 eq 5.0);
                  stdio.println(5 neq 6);
                  stdio.println(5 !eq 6);
                  stdio.println(!(5 eq 6));
                  stdio.println(5 == 5.0);
                  stdio.println(5 != 6);

                  switch 5.0
                    case 5 -> { stdio.println(true); }
                    default -> { stdio.println(false); }
                  end

                  match 5.0
                    5 -> { stdio.println(true); }
                    else -> { stdio.println(false); }
                  end
                  return;
                }
                """;

        assertEquals(
                List.of("true", "true", "true", "true", "true", "true", "true", "true"),
                run(program, "equality-surfaces.ores"));
    }

    @Test
    void structuralValuesUseValueEqualityWhileIdentityTracksReferences() throws Exception {
        String program = """
                define class Box as
                end

                pub fnc main(): void {
                  val Box first = new Box();
                  val Box second = new Box();
                  val values = arr[1, 2];
                  val same_values = values;
                  val other_values = arr[1, 2];

                  stdio.println(first eq first);
                  stdio.println(first eq second);
                  stdio.println(first is first);
                  stdio.println(first is second);

                  stdio.println(values eq other_values);
                  stdio.println(values is same_values);
                  stdio.println(values is other_values);
                  return;
                }
                """;

        assertEquals(
                List.of("true", "false", "true", "false", "true", "true", "false"),
                run(program, "identity.ores"));
    }

    @Test
    void identityRejectsScalarsAndValueSemanticWrappers() {
        IllegalArgumentException scalar = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(int left, int right): bool {
                          return left is right;
                        }
                        """)));
        assertTrue(scalar.getMessage().contains("identity-bearing"));

        IllegalArgumentException option = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Option<int> left, Option<int> right): bool {
                          return left is right;
                        }
                        """)));
        assertTrue(option.getMessage().contains("identity-bearing"));

        IllegalArgumentException result = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Result<int, string> left, Result<int, string> right): bool {
                          return left is right;
                        }
                        """)));
        assertTrue(result.getMessage().contains("identity-bearing"));

        IllegalArgumentException function = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Fnc<int, bool> left, Fnc<int, bool> right): bool {
                          return left is right;
                        }
                        """)));
        assertTrue(function.getMessage().contains("identity-bearing"));
    }

    @Test
    void identityRejectsProvablyDisjointReferenceDomains() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Array<int> left, Future<int> right): bool {
                          return left is right;
                        }
                        """)));
        assertTrue(error.getMessage().contains("disjoint runtime domains"));
    }

    @Test
    void identityRemainsAvailableForReferenceCollections() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc same(Array<int> left, Array<int> right): bool {
                  return left is right;
                }
                """)));
    }

    private static Ast.BinaryExpr binary(String source) {
        Ast.Expr expression = new Parser(new Lexer(source).scan()).parseExpression();
        return assertInstanceOf(Ast.BinaryExpr.class, expression);
    }

    private static List<String> run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        return output.toString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.isBlank())
                .toList();
    }
}
