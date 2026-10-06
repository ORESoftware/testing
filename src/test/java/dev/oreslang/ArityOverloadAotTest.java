package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ArityOverloadAotTest {

    @Test
    void boundAndStaticCallableValuesParticipateInProperTailCalls() throws Exception {
        String program = """
                define class Counter as
                  pub descend(int n): int {
                    if n == 0; do return 0; fi
                    val Fnc<int, int> next = self.descend;
                    return next(n - 1);
                  }
                  pub static fnc count(int n): int {
                    if n == 0; do return 0; fi
                    val Fnc<int, int> next = Counter.count;
                    return next(n - 1);
                  }
                end
                pub routine main(): void {
                  val counter = new Counter();
                  stdio.stdout.write(counter.descend(50000));
                  stdio.stdout.write(":");
                  stdio.stdout.write(Counter.count(50000));
                  return;
                }
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "bound-tailcalls.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("0:0", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void privateCallbackSlotsRetainLexicalAuthorityWithoutBecomingPublic() throws Exception {
        String program = """
                define class Box as
                  val int base;
                  private read(): int { return self.base; }
                  private read(int extra): int { return self.base + extra; }
                  private static fnc secret(): int { return 1; }
                  private static fnc secret(int value): int { return value * 2; }
                  pub reader(): Fnc<int> { return self.read; }
                  pub static fnc multiplier(): Fnc<int, int> { return Box.secret; }
                end
                pub routine main(): void {
                  val box = new Box(10);
                  val read = box.reader();
                  val multiply = Box.multiplier();
                  stdio.stdout.write(read());
                  stdio.stdout.write(":");
                  stdio.stdout.write(multiply(9));
                  return;
                }
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "private-callbacks.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("10:18", out.toString(StandardCharsets.UTF_8));

        for (String extraction : new String[]{
                "val Fnc<int> callback = new Box().read;",
                "val Fnc<int, int> callback = Box.secret;"}) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse("""
                            define class Box as
                              private read(): int { return 1; }
                              private static fnc secret(): int { return 1; }
                              private static fnc secret(int value): int { return value; }
                            end
                            fnc bad(): void {
                            """ + extraction + "\nreturn;\n}")));
            assertTrue(denied.getMessage().contains("private"), denied.getMessage());
        }
    }

    private static final String PROGRAM = """
            pub interface Picker {
              fnc pick() => String;
              fnc pick(int x) => String;
              fnc pick(int x, int y) => String;
            }

            define class Base implements Picker as
              pub pick() : String {
                return "base0";
              }

              pub pick(int x) : String {
                return "base1";
              }

              pub pick(int x, int y) : String {
                return "base2";
              }

              pub same() : String {
                return "instance";
              }

              pub static fnc same() : String {
                return "static";
              }

              pub static fnc choose() : String {
                return "static0";
              }

              pub static fnc choose(int x) : String {
                return "static1";
              }
            end

            define class Child extends Base as
              pub pick(int x) : String {
                return "child1";
              }
            end

            pub routine main() : void {
              val base = new Base();
              val child = new Child();

              stdio.println(base.pick());
              stdio.println(base.pick(1));
              stdio.println(base.pick(1, 2));
              stdio.println(base.same());
              stdio.println(Base.same());
              stdio.println(Base.choose());
              stdio.println(Base.choose(1));

              stdio.println(child.pick());
              stdio.println(child.pick(1));
              stdio.println(child.pick(1, 2));
              stdio.println(Child.choose());
              stdio.println(Child.choose(1));
              return;
            }
            """;

    @Test
    void classInterfaceAndStaticOverloadsResolveOnlyByArity() throws Exception {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(PROGRAM)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, PROGRAM, "arity-overload.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(out)
                .build()) {
            context.eval(source);
        }

        assertEquals("""
                base0
                base1
                base2
                instance
                static
                static0
                static1
                base0
                child1
                base2
                static0
                static1
                """, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void sameNameAndArityCannotOverloadByParameterType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Bad as
                          pub pick(int value) : int {
                            return value;
                          }

                          pub pick(String value) : String {
                            return value;
                          }
                        end
                        """)));

        assertTrue(failure.getMessage().contains("already has arity 1"));
        assertTrue(failure.getMessage().contains("overload identity is name + arity only"));
    }

    @Test
    void genericArityDoesNotCreateAnotherOverloadSlot() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Bad as
                          pub pick<T>(T value) : T {
                            return value;
                          }

                          pub pick<U, V>(U value) : U {
                            return value;
                          }
                        end
                        """)));

        assertTrue(failure.getMessage().contains("already has arity 1"));
    }

    @Test
    void multipleInheritanceRequiresExplicitResolutionForSameAritySlot() {
        IllegalArgumentException ambiguous = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Left as
                          pub ping() : int { return 1; }
                        end

                        define class Right as
                          pub ping() : int { return 2; }
                        end

                        define class Ambiguous extends Left, Right as
                        end
                        """)));

        assertTrue(ambiguous.getMessage().contains("ambiguous inherited method"));
        assertTrue(ambiguous.getMessage().contains("arity 0"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Left as
                  pub ping() : int { return 1; }
                end

                define class Right as
                  pub ping() : int { return 2; }
                end

                define class Resolved extends Left, Right as
                  pub ping() : int { return 3; }
                end
                """)));
    }

    @Test
    void ordinaryDiamondInheritanceOfSameDeclarationKeepsOneSlot() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Root as
                  pub ping() : int { return 1; }
                end

                define class Left extends Root as
                end

                define class Right extends Root as
                end

                define class Diamond extends Left, Right as
                end
                """)));
    }

    @Test
    void overloadedMethodValueStillNeedsContextWhenArityIsUnknown() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub pick() : int { return 0; }
                          pub pick(int value) : int { return value; }
                        end

                        pub fnc bad() : void {
                          val box = new Box();
                          val callback = box.pick;
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("direct-call-only"), failure.getMessage());
    }

    @Test
    void expectedFunctionTypeBindsOverloadedMethodValueAndSelfWithoutCloningMethodCode() throws Exception {
        String program = """
                pub fnc call0(Fnc<int> callback) : int {
                  return callback();
                }

                pub fnc call1(Fnc<int, int> callback) : int {
                  return callback(5);
                }

                define class Box as
                  val int base;

                  pub pick() : int {
                    return self.base;
                  }

                  pub pick(int delta) : int {
                    return self.base + delta;
                  }

                  pub via_self0() : int {
                    return call0(self.pick);
                  }

                  pub via_self1() : int {
                    return call1(self.pick);
                  }

                  pub static fnc select() : int {
                    return 70;
                  }

                  pub static fnc select(int delta) : int {
                    return 70 + delta;
                  }
                end

                pub routine main() : void {
                  val left = new Box(10);
                  val right = new Box(20);
                  val typed = new Box(30);

                  stdio.println(call0(left.pick));
                  stdio.println(call1(right.pick));

                  val Fnc<int> callback = typed.pick;
                  stdio.println(callback());

                  stdio.println(new Box(40).via_self0());
                  stdio.println(new Box(50).via_self1());
                  stdio.println(call0(Box.select));
                  stdio.println(call1(Box.select));
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "bound-method-values.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(out)
                .build()) {
            context.eval(source);
        }

        assertEquals("""
                10
                25
                30
                40
                55
                70
                75
                """, out.toString(StandardCharsets.UTF_8));
    }
}
