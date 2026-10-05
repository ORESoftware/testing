package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class PatternMatchingHardeningTest {
    @Test
    void lexerAndParserSeparateTypeTestsPatternsCasesAndArrowRoles() {
        var tokens = new Lexer("is matches match when case default first switch").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IS));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.MATCHES));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.MATCH));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.WHEN));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.CASE));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.DEFAULT));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IDENT && t.lexeme().equals("first")),
                "first is contextual in 'match first', not a globally reserved identifier");

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(bool flag): void {
                  if flag {
                    return;
                  }
                }
                """));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc good(bool flag): void {
                  if flag {
                    return;
                  } fi
                  return;
                }
                """)));

        IllegalArgumentException fatMatch = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Option<int> value): void {
                  match value
                    Some(v) => { return; }
                    None -> { return; }
                  end
                }
                """));
        assertTrue(fatMatch.getMessage().contains("slim arrow"));

        IllegalArgumentException fatSwitch = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(int value): void {
                  switch value
                    case 1 => { return; }
                    default -> { return; }
                  end
                }
                """));
        assertTrue(fatSwitch.getMessage().contains("slim arrow"));
    }

    @Test
    void isNarrowsAndBindsInsideIfFi() {
        assertDoesNotThrow(() -> {
            var typed = TypeChecker.check(Parser.parse("""
                    define class Animal as
                    end

                    define class Dog extends Animal as
                    end

                    fnc acceptDog(Dog dog): int {
                      return 7;
                    }

                    fnc classify(Animal animal): int {
                      if animal is Dog dog then
                        return acceptDog(dog);
                      else
                        return 0;
                      fi
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void matchesCanDestructureSumTypesInsideIfFi() {
        assertDoesNotThrow(() -> {
            var typed = TypeChecker.check(Parser.parse("""
                    fnc valueOrZero(Option<int> value): int {
                      if value matches Some(inner) then
                        return inner;
                      else
                        return 0;
                      fi
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void exclusiveMatchProvesConstructorsAndRejectsOverlaps() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc valueOrZero(Option<int> value): int {
                  match value
                    Some(inner) -> { return inner; }
                    None -> { return 0; }
                  end
                }
                """)));

        IllegalArgumentException overlap = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc classify(Animal animal): int {
                  match animal
                    is Dog dog -> { return 1; }
                    is Animal any -> { return 2; }
                  end
                }
                """)));
        assertTrue(overlap.getMessage().contains("overlapping match arms"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc classify(Animal animal): int {
                  match first animal
                    is Dog dog -> { return 1; }
                    is Animal any -> { return 2; }
                  end
                }
                """)));
    }

    @Test
    void exclusiveMatchCanProveSimpleGuardPartitionsAndFallbackIsComplement() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc sign(int n): int {
                  match n
                    is int x when x < 0 -> { return -1; }
                    is int x when x == 0 -> { return 0; }
                    is int x when x > 0 -> { return 1; }
                    else -> { return 99; }
                  end
                }
                """)));

        IllegalArgumentException overlap = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc ambiguous(int n): int {
                  match n
                    is int x when x >= 0 -> { return 1; }
                    is int x when x <= 10 -> { return 2; }
                    else -> { return 3; }
                  end
                }
                """)));
        assertTrue(overlap.getMessage().contains("overlapping match arms"));
    }

    @Test
    void matchRequiresExhaustivenessWithoutFallback() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc incomplete(Option<int> value): int {
                  match value
                    Some(inner) -> { return inner; }
                  end
                }
                """)));
        assertTrue(error.getMessage().contains("non-exhaustive match"));
    }

    @Test
    void switchIsConstantDispatchAndRejectsDuplicateCases() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc classify(int value): int {
                  switch value
                    case 1 -> { return 10; }
                    case 2, 3 -> { return 20; }
                    default -> { return 0; }
                  end
                }
                """)));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc classify(int value): int {
                  switch value
                    case 1 -> { return 10; }
                    case 1 -> { return 20; }
                    default -> { return 0; }
                  end
                }
                """)));
        assertTrue(duplicate.getMessage().contains("duplicate switch case"));
    }

    @Test
    void castsAreCheckedAndOptionalWithoutJavaHostTypeSemantics() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc checked(Animal animal): Dog {
                  return animal as Dog;
                }

                fnc optional(Animal animal): Option<Dog> {
                  return animal as? Dog;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Dog as
                end

                fnc impossible(int value): Dog {
                  return value as Dog;
                }
                """)));
    }

    @Test
    void runtimeExecutesNativeSemanticTypeAndPatternRelations() throws Exception {
        String program = """
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc classify(Animal animal): int {
                  if animal is Dog dog then
                    return 7;
                  else
                    return 0;
                  fi
                }

                fnc fromOption(Option<int> value): int {
                  match value
                    Some(inner) -> { return inner; }
                    None -> { return 0; }
                  end
                }

                fnc fromSwitch(int value): int {
                  switch value
                    case 1 -> { return 11; }
                    case 2 -> { return 22; }
                    default -> { return 0; }
                  end
                }

                pub fnc main(): void {
                  val Animal animal = new Dog();
                  stdio.println(classify(animal));
                  stdio.println(fromOption(Some(42)));
                  stdio.println(fromSwitch(2));
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pattern-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("7"));
        assertTrue(text.contains("42"));
        assertTrue(text.contains("22"));
    }

    @Test
    void refinementAliasCannotDuplicateMoveOnlyOwnership() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> {
            var typed = TypeChecker.check(Parser.parse("""
                    define class Animal as
                    end

                    define class Dog extends Animal as
                    end

                    fnc consume(Dog dog): void {
                      return;
                    }

                    fnc bad(Animal animal): void {
                      if animal is Dog dog then
                        consume(dog);
                        consume(dog);
                      fi
                      return;
                    }
                    """));
            OwnershipChecker.check(typed);
        });
        assertTrue(error.getMessage().contains("moved value"));
    }
}
