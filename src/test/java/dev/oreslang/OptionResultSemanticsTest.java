package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class OptionResultSemanticsTest {
    @Test
    void typechecksOptionAndResultExtractionSurface() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc option_value(Option<int> value): int {
                    return value.unwrap();
                  }

                  fnc option_safe(Option<int> value): Result<int, OptionUnwrapError> {
                    return value.unwrap_safe();
                  }

                  fnc option_default(Option<int> value): int {
                    return value.unwrap_or(7);
                  }

                  fnc result_value(Result<int, String> value): int {
                    return value.expect("expected an integer");
                  }

                  fnc result_safe(Result<int, String> value): Result<int, String> {
                    return value.unwrap_safe();
                  }

                  fnc constructors(): Result<int, String> {
                    val Option<int> some = Some(42);
                    val Option<int> none = None;
                    val Result<int, String> ok = Ok(some.unwrap());
                    if none.is_none(); do
                      return ok;
                    else
                      return Err("impossible");
                    fi
                  }
                end
                """)));
    }

    @Test
    void sumTypesRejectCrossVariantAndUnknownMembers() {
        IllegalArgumentException option = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Option<int> value): bool {
                  return value.is_ok();
                }
                """)));
        assertTrue(option.getMessage().contains("unknown Option member"));

        IllegalArgumentException result = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Result<int, String> value): bool {
                  return value.is_some();
                }
                """)));
        assertTrue(result.getMessage().contains("unknown Result member"));
    }

    @Test
    void resultRequiresTwoExplicitTypeArguments() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(Result<int> value): int {
                    return 1;
                  }
                end
                """)));
    }

    @Test
    void runtimeSupportsSafeAndPanickingExtraction() throws Exception {
        String program = """
                define module app
                  pub fnc main(): void {
                    val some = Some(42);
                    stdio.println(some.is_some());
                    stdio.println(some.unwrap());

                    val missing = None;
                    stdio.println(missing.is_none());
                    val safe = missing.unwrap_safe();
                    stdio.println(safe.is_err());
                    stdio.println(safe);

                    val Result<int, String> err = Err("bad");
                    stdio.println(err.unwrap_or(7));
                    stdio.println(Ok(9).unwrap());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "option-result.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("true"));
        assertTrue(text.contains("42"));
        assertTrue(text.contains("Err(OptionUnwrapError(None))"));
        assertTrue(text.contains("7"));
        assertTrue(text.contains("9"));
    }

    @Test
    void stringPayloadConstructorsWidenToString() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc option(): Option<String> {
                  return Some("hello");
                }

                fnc result(): Result<int, String> {
                  return Err("bad");
                }
                """)));
    }

    @Test
    void unwrapNoneRaisesLanguagePanicThatOrdinaryCatchCannotSwallow() throws Exception {
        String program = """
                define module app
                  pub fnc main(): void {
                    try {
                      None.unwrap();
                    } catch (err) {
                      stdio.println("caught");
                    }
                    return;
                  }
                end
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "option-panic.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        Throwable thrown;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            thrown = assertThrows(Throwable.class, () -> context.eval(source));
        }
        assertNotNull(thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Option::unwrap"));
    }

    @Test
    void moveOnlyOptionsStayAffineEvenWhenInitializedWithNone() {
        IllegalArgumentException moved = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Box as
                end

                fnc consume(Option<Box> value): void {
                  return;
                }

                fnc bad(): void {
                  val Option<Box> value = None;
                  consume(value);
                  consume(value);
                  return;
                }
                """)));
        assertTrue(moved.getMessage().contains("moved value"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc consume(Option<int> value): void {
                  return;
                }

                fnc good(): void {
                  val Option<int> value = None;
                  consume(value);
                  consume(value);
                  return;
                }
                """)));
    }

    @Test
    void someMovesMoveOnlyPayloadAndUnwrapConsumesMoveOnlyOption() {
        IllegalArgumentException wrappedTwice = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Box as
                end

                fnc bad(): void {
                  let Box box = new Box();
                  val first = Some(box);
                  val second = Some(box);
                  return;
                }
                """)));
        assertTrue(wrappedTwice.getMessage().contains("moved value"));

        IllegalArgumentException unwrappedTwice = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Box as
                end

                fnc bad(): void {
                  val Option<Box> value = Some(new Box());
                  val first = value.unwrap();
                  val second = value.unwrap();
                  return;
                }
                """)));
        assertTrue(unwrappedTwice.getMessage().contains("moved value"));
    }

    @Test
    void ownedSumValuesCannotHideStackBorrows() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Box as
                end

                fnc bad(): void {
                  let Box box = new Box();
                  val maybe = Some(&box);
                  return;
                }
                """)));
        assertTrue(error.getMessage().contains("cannot store a borrow"));
    }

    @Test
    void mutexTryLockOptionCanBeSafelyUnwrappedIntoLinearGuard() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                end

                fnc good(): void {
                  let Mutex<Box> mutex = Mutex.new(new Box());
                  val maybe = mutex.try_lock();
                  val guard = maybe.unwrap();
                  guard.release();
                  return;
                }
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Box as
                end

                fnc bad(): void {
                  let Mutex<Box> mutex = Mutex.new(new Box());
                  val maybe = mutex.try_lock();
                  val guard = maybe.unwrap();
                  val again = maybe.unwrap();
                  guard.release();
                  return;
                }
                """)));
        assertTrue(error.getMessage().contains("moved value"));
    }
}
