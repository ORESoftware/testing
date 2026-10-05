package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class GenericsAndOperatorsHardeningTest {
    @Test
    void infersGenericFunctionArgumentsWithoutTreatingTAsAny() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc identity<T>(T value) => T {
                    return value;
                  }

                  fnc use() => void {
                    val int number = identity(42);
                    val String label = identity("ores");
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc unsound<T>() => T {
                    return 42;
                  }
                end
                """)));
    }

    @Test
    void enforcesKnownGenericArityAndSubstitutesClassMembers() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Box<T> as
                    pub val T value;

                    pub get() => T {
                      return self.value;
                    }
                  end

                  fnc read(Box<int> box) => int {
                    return box.get();
                  }

                  fnc make() => Box<int> {
                    return new Box<int>(7);
                  }
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Pair<A, B> as
                    val A left;
                    val B right;
                  end

                  fnc bad(Pair<int> pair) => void {
                    return;
                  }
                end
                """)));
        assertTrue(error.getMessage().contains("expects 2 type argument"));
    }

    @Test
    void explicitGenericCallsAndInferenceMarkersAreChecked() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc identity<T>(T value) => T {
                    return value;
                  }

                  fnc use() => void {
                    val explicit = identity<int>(42);
                    val inferred = identity<>(42);
                    stdio.println(explicit);
                    stdio.println(explicit);
                    stdio.println(inferred);
                    stdio.println(inferred);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc identity<T>(T value) => T { return value; }
                  fnc bad() => void {
                    val int value = identity<int>("wrong");
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc plain(int value) => int { return value; }
                  fnc bad() => int { return plain<>(1); }
                end
                """)));
    }

    @Test
    void genericInheritanceSubstitutesFieldsMethodsConstructorsAndInterfaces() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface HasValue<T>
                    value: T;
                  end

                  define class Parent<T> as
                    pub val T value;

                    pub get() => T {
                      return self.value;
                    }
                  end

                  define class Child<U> extends Parent<U> implements HasValue<U> as
                  end

                  define class IntChild extends Parent<int> as
                  end

                  fnc read(Child<int> child) => int {
                    return child.get();
                  }

                  fnc reuseInheritedGenericResult(Child<int> child) => void {
                    val value = child.get();
                    stdio.println(value);
                    stdio.println(value);
                    return;
                  }

                  fnc readField(IntChild child) => int {
                    return child.value;
                  }

                  fnc reuseInheritedGenericField(IntChild child) => void {
                    val value = child.value;
                    stdio.println(value);
                    stdio.println(value);
                    return;
                  }

                  fnc make() => Child<int> {
                    return new Child<int>(7);
                  }
                end
                """)));
    }

    @Test
    void qualifiedGenericCallsInferButUnspecializedGenericValuesAreRejected() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module util
                  pub fnc identity<T>(T value) => T {
                    return value;
                  }
                end

                define module app
                  fnc use() => void {
                    val value = util.identity<>(7);
                    stdio.println(value);
                    stdio.println(value);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc identity<T>(T value) => T { return value; }

                  fnc bad() => void {
                    val f = identity;
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module util
                  pub fnc identity<T>(T value) => T { return value; }
                end

                define module app
                  fnc bad() => void {
                    val f = util.identity;
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Box as
                    pub map<T>(T value) => T { return value; }
                  end

                  fnc bad(Box box) => void {
                    val f = box.map;
                    return;
                  }
                end
                """)));
    }

    @Test
    void genericInferenceRejectsUnknownArgumentShapes() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc identity<T>(T value) => T {
                            return value;
                          }

                          fnc bad() => void {
                            val value = identity(arr[]);
                            return;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("cannot infer"));
    }

    @Test
    void genericObjectDestructuringPreservesConcreteFieldTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Box<T> as
                    pub val T value;
                  end

                  fnc use(Box<int> box) => void {
                    const {value} = box;
                    stdio.println(value);
                    stdio.println(value);
                    return;
                  }
                end
                """)));
    }

    @Test
    void genericInterfaceMethodsAreAlphaEquivalentButPreserveGenericArity() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Mapper<T>
                    fnc map<U>(T input, U fallback) => U;
                  end

                  define class Good<T> implements Mapper<T> as
                    pub map<V>(T input, V fallback) => V {
                      return fallback;
                    }
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Mapper<T>
                    fnc map<U>(T input, U fallback) => U;
                  end

                  define class Bad<T> implements Mapper<T> as
                    pub map<A, B>(T input, A fallback) => A {
                      return fallback;
                    }
                  end
                end
                """)));
    }

    @Test
    void genericNominalSubtypingPreservesConcreteArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface HasValue<T>
                    value: T;
                  end

                  define interface ExtendedValue<T> extends HasValue<T>
                  end

                  define class Parent<T> as
                    pub val T value;
                  end

                  define class Child<T> extends Parent<T> implements ExtendedValue<T> as
                  end

                  fnc takeParentInt(Parent<int> value) => void { return; }
                  fnc takeValueInt(HasValue<int> value) => void { return; }

                  fnc ok(Child<int> parentValue, Child<int> interfaceValue) => void {
                    takeParentInt(parentValue);
                    takeValueInt(interfaceValue);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Parent<T> as
                    pub val T value;
                  end

                  define class Child<T> extends Parent<T> as
                  end

                  fnc takeString(Parent<String> value) => void { return; }

                  fnc bad(Child<int> value) => void {
                    takeString(value);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface HasValue<T>
                    value: T;
                  end

                  define interface ExtendedValue<T> extends HasValue<T>
                  end

                  define class Box<T> implements ExtendedValue<T> as
                    pub val T value;
                  end

                  fnc takeString(HasValue<String> value) => void { return; }

                  fnc bad(Box<int> value) => void {
                    takeString(value);
                    return;
                  }
                end
                """)));
    }

    @Test
    void staticFunctionsOwnTheirGenericParametersAndCannotCaptureClassGenerics() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model
                  define class Box<T> as
                    pub static fnc identity<U>(U value) => U {
                      return value;
                    }
                  end
                end

                define module app
                  fnc use() => void {
                    val value = model.Box.identity<>(7);
                    stdio.println(value);
                    stdio.println(value);
                    return;
                  }
                end
                """)));

        IllegalArgumentException captured = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          define class Bad<T> as
                            pub static fnc leak(T value) => T {
                              return value;
                            }
                          end
                        end
                        """)));
        assertTrue(captured.getMessage().contains("cannot reference enclosing class generic"));

        IllegalArgumentException bodyCapture = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          define class Bad<T> as
                            pub static fnc leakInside() => void {
                              val T value = process.dynamic;
                              return;
                            }
                          end
                        end
                        """)));
        assertTrue(bodyCapture.getMessage().contains("cannot reference enclosing class generic"));
    }

    @Test
    void inferredConstructorsBindClassGenericsIncludingInheritedFields() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Box<T> as
                    pub val T value;
                  end

                  define class Parent<T> as
                    pub val T value;
                  end

                  define class Child<U> extends Parent<U> as
                  end

                  fnc use() => void {
                    val box = new Box<>(7);
                    val number = box.value;
                    stdio.println(number);
                    stdio.println(number);

                    val child = new Child<>("ores");
                    val label = child.value;
                    stdio.println(label);
                    stdio.println(label);
                    return;
                  }
                end
                """)));
    }

    @Test
    void inferredConstructorsRejectMissingAndConflictingBindings() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          define class Phantom<T> as
                          end

                          fnc bad() => void {
                            val value = new Phantom<>();
                            return;
                          }
                        end
                        """)));
        assertTrue(missing.getMessage().contains("cannot infer class generic"));

        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          define class Same<T> as
                            pub val T left;
                            pub val T right;
                          end

                          fnc bad() => void {
                            val value = new Same<>(1, "mixed");
                            return;
                          }
                        end
                        """)));
        assertTrue(conflict.getMessage().contains("conflicting inference"));
    }

    @Test
    void spacedComparisonsAreNotMistakenForGenericCalls() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc between(int a, int b, int c) => bool {
                    return a < b && b > (c);
                  }
                end
                """)));
    }

    @Test
    void nestedGenericClosersDoNotBecomeShiftOperators() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc keep(Option<Array<int>> value) => Option<Array<int>> {
                    return value;
                  }
                end
                """)));
    }

    @Test
    void logicalAndBitwiseFamiliesHaveDistinctTypesAndPrecedence() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bits(int a, int b) => int {
                    return ((~a & b) | (a ^ b)) << 1 >> 1 >>> 1;
                  }

                  fnc logic(bool a, bool b) => bool {
                    return a && b || a ^^ b;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(bool a, bool b) => bool {
                    return a | b;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(float a, int b) => int {
                    return a & b;
                  }
                end
                """)));
    }

    @Test
    void zeroArgumentLambdaStillUsesDoublePipeAndRuntimeExecutesBitwiseOps() throws Exception {
        String program = """
                define module app
                  fnc callback() => (() -> int) {
                    return || -> {
                      return 7;
                    };
                  }

                  pub fnc main() => void {
                    stdio.println((5 & 3) | (8 >> 2));
                    stdio.println(true ^^ false);
                    stdio.println(false && [true][99]);
                    stdio.println(true || [false][99]);
                    stdio.println(~0);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "operators.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("3"));
        assertTrue(text.contains("true"));
        assertTrue(text.contains("-1"));
    }
}
