package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class CallableLocalTypeTest {
    @Test
    void callableLocalStructCanDefineAnEscapingReturnShapeWithoutLeakingItsName() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  pub fnc getat() => T {
                    struct T {
                      foo: string
                    }

                    return obj{
                      foo: 'hello'
                    };
                  }

                  pub fnc use_it() => string {
                    val result = getat();
                    return result.foo;
                  }
                end
                """)));

        IllegalArgumentException leaked = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          pub fnc getat() => T {
                            struct T { foo: string }
                            return obj{foo: "hello"};
                          }

                          fnc misuse() => void {
                            val T leaked = getat();
                            return;
                          }
                        end
                        """)));
        assertTrue(leaked.getMessage().contains("initializer for leaked"));
    }

    @Test
    void sameLocalTypeNameIsNominallyDistinctAcrossCallables() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc left() => T {
                    struct T { left: string }
                    return obj{left: "L"};
                  }

                  fnc right() => T {
                    struct T { right: int }
                    return obj{right: 7};
                  }

                  pub fnc main() => void {
                    val a = left();
                    val b = right();
                    stdio.println(a.left);
                    stdio.println(b.right);
                    return;
                  }
                end
                """)));
    }

    @Test
    void objectLiteralConversionToStructIsExactAtTypedBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc missing() => T {
                    struct T { foo: string; count: int }
                    return obj{foo: "hello"};
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc extra() => T {
                    struct T { foo: string }
                    return obj{foo: "hello", extra: 1};
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc wrong() => T {
                    struct T { foo: string }
                    return obj{foo: 42};
                  }
                end
                """)));
    }

    @Test
    void localInterfaceRemainsAStorageFreeMethodContract() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc use_contract() => void {
                    interface LocalApi {
                      fnc ping() => int;
                    }
                    return;
                  }
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module app as
                          fnc bad_contract() => void {
                            interface LocalApi {
                              value: int;
                            }
                            return;
                          }
                        end
                        """));
        assertTrue(error.getMessage().contains("storage-free contracts"));
    }

    @Test
    void shortAndLongStructFormsBothWorkInsideCallables() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc short_form() => T {
                    struct T { foo: string }
                    return obj{foo: "short"};
                  }

                  fnc long_form() => T {
                    define struct T as
                      foo: string
                    end
                    return obj{foo: "long"};
                  }
                end
                """)));
    }

    @Test
    void runtimePromotesReturnedObjToLocalNominalStructSoMethodsStillDispatch() throws Exception {
        String program = """
                define module app as
                  fnc getat() => T {
                    struct T {
                      foo: string;

                      pub greeting() => string {
                        return self.foo + " world";
                      }
                    }

                    return obj{foo: "hello"};
                  }

                  pub fnc main() => void {
                    val result = getat();
                    stdio.println(result.foo);
                    stdio.println(result.greeting());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "local-types.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("hello"));
        assertTrue(text.contains("hello world"));
    }
    @Test
    void contextualObjectPromotionFillsDefaultedLocalStructFields() throws Exception {
        String output = runProgram("""
                define module app as
                  fnc make() => T {
                    struct T {
                      pub name: string;
                      pub count: int = 7;
                    }
                    return obj{name: "hello"};
                  }

                  pub fnc main() => void {
                    val result = make();
                    stdio.stdout.write(result.name);
                    stdio.stdout.write(result.count);
                    return;
                  }
                end
                """);

        assertEquals("hello7", output);
    }

    @Test
    void methodLocalStructCanEscapeThroughMethodSignatureAndKeepDispatch() throws Exception {
        String output = runProgram("""
                define class Factory as
                  pub make() => T {
                    struct T {
                      pub value: int;

                      pub doubled() => int {
                        return self.value * 2;
                      }
                    }
                    return obj{value: 21};
                  }
                end

                pub fnc main() => void {
                  val factory = new Factory();
                  val result = factory.make();
                  stdio.stdout.write(result.doubled());
                  return;
                }
                """);

        assertEquals("42", output);
    }

    @Test
    void callableLocalTypeAliasCanAppearInSignatureWithoutLeakingItsName() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  pub fnc answer() => Answer {
                    type Answer = int;
                    return 42;
                  }

                  pub fnc use_it() => int {
                    val result = answer();
                    return result;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc answer() => Answer {
                    type Answer = int;
                    return 42;
                  }

                  fnc misuse() => void {
                    val Answer leaked = answer();
                    return;
                  }
                end
                """)));
    }

    @Test
    void genericLocalAliasIsHoistedAndErasesToItsResolvedType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc values() => Values<int> {
                    type Values<T> = Array<T>;
                    return arr[1, 2, 3];
                  }

                  pub fnc main() => void {
                    val xs = values();
                    stdio.println(xs[0]);
                    return;
                  }
                end
                """)));
    }

    @Test
    void genericLocalInterfaceCanEscapeAsStructuralCallableShape() throws Exception {
        String output = runProgram("""
                define module app as
                  fnc make() => View<int> {
                    interface View<T> {
                      fnc get() => T;
                    }

                    struct Box is View<int> {
                      value: int;

                      pub get() => int {
                        return self.value;
                      }
                    }

                    return Box { value = 7 };
                  }

                  pub fnc main() => void {
                    val result = make();
                    stdio.stdout.write(result.get());
                    return;
                  }
                end
                """);

        assertEquals("7", output);
    }

    @Test
    void genericLocalInterfaceInheritanceSubstitutesParentTypeArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc make() => View<int> {
                    interface Readable<T> {
                      fnc get() => T;
                    }

                    interface View<T> extends Readable<T> {
                      fnc describe() => string;
                    }

                    struct Box is View<int> {
                      value: int;

                      pub get() => int {
                        return self.value;
                      }

                      pub describe() => string {
                        return "box";
                      }
                    }

                    return Box { value = 7 };
                  }
                end
                """)));
    }

    @Test
    void nestedBlocksCanShadowLocalTypesWithoutChangingTheOuterBinding() throws Exception {
        String output = runProgram("""
                define module app as
                  pub fnc main() => void {
                    struct T {
                      value: int;
                    }

                    if true do
                      struct T {
                        text: string;
                      }
                      val inner = T { text = "inner" };
                      stdio.stdout.write(inner.text);
                    fi

                    val outer = T { value = 9 };
                    stdio.stdout.write(outer.value);
                    return;
                  }
                end
                """);

        assertEquals("inner9", output);
    }

    @Test
    void localAliasCyclesAreRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          fnc bad() => A {
                            type A = B;
                            type B = A;
                            return 1;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("type alias cycle"));
    }

    @Test
    void localTypeDeclarationsAreHoistedRegardlessOfTextualOrder() throws Exception {
        String output = runProgram("""
                define module app as
                  fnc make() => View<int> {
                    struct Box is View<int> {
                      value: Value;

                      pub get() => Value {
                        return self.value;
                      }
                    }

                    type Value = int;

                    interface View<T> {
                      fnc get() => T;
                    }

                    return Box { value = 7 };
                  }

                  pub fnc main() => void {
                    val result = make();
                    stdio.stdout.write(result.get());
                    return;
                  }
                end
                """);

        assertEquals("7", output);
    }

    @Test
    void nestedLocalAliasesShadowAndThenRestoreOuterTypeBindings() throws Exception {
        String output = runProgram("""
                define module app as
                  pub fnc main() => void {
                    type T = int;

                    if true do
                      type T = string;
                      val T inner = "inner";
                      stdio.stdout.write(inner);
                    fi

                    val T outer = 9;
                    stdio.stdout.write(outer);
                    return;
                  }
                end
                """);

        assertEquals("inner9", output);
    }

    @Test
    void localTypeAndValueNamespacesAreIndependent() throws Exception {
        String output = runProgram("""
                define module app as
                  pub fnc main() => void {
                    struct T {
                      value: int;
                    }

                    val int T = 3;
                    val item = T { value = 4 };
                    stdio.stdout.write(T);
                    stdio.stdout.write(item.value);
                    return;
                  }
                end
                """);

        assertEquals("34", output);
    }

    @Test
    void duplicateLocalTypeNamesAcrossKindsAreRejectedInOneScope() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          fnc bad() => void {
                            type T = int;
                            interface T {
                              fnc read() => int;
                            }
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("duplicate callable-local type 'T'"));
    }

    @Test
    void localTypeNamesCannotCollideWithCallableGenericParameters() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          fnc bad<T>() => void {
                            struct T {
                              value: int;
                            }
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("collides with an in-scope generic type parameter"));
    }

    @Test
    void nestedLocalTypeNamesCannotShadowMethodOrClassGenericParameters() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box<T> as
                          pub read<U>() => void {
                            if true do
                              type U = int;
                            fi
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("collides with an in-scope generic type parameter"));
    }

    private static String runProgram(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "callable-local-types.ores")
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
