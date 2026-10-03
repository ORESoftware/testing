package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class PolyglotFeatureTest {
    @Test
    void executesNamespacesCollectionsAssignmentAndInheritedMethods() throws Exception {
        String program = """
                define module math as
                  pub fnc add(int a, int b) => int { return a + b; }
                end

                define module model as
                  define class A as
                    pub value() => int { return 7; }
                  end
                  define class B extends A as
                  end
                end

                define module app as
                  pub fnc main() => void {
                    let answer = math.add(1, 2);
                    answer = answer + 4;
                    val values = arr[answer, 9];
                    val person = obj{name: "ores"};
                    val inherited = new B();
                    stdio.println(values[0]);
                    stdio.println(person.name);
                    stdio.println(inherited.value());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "features.ores")
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
        assertTrue(text.contains("ores"));
    }
    @Test
    void executesInheritedGenericConstructorFields() throws Exception {
        String program = """
                define class Parent<T> as
                  pub val T value;

                  pub get() => T {
                    return self.value;
                  }
                end

                define class Child<T> extends Parent<T> as
                end

                pub fnc main() => void {
                  val child = new Child<>(7);
                  stdio.stdout.write(child.get());
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "generic-inheritance.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("7"));
    }
    @Test
    void executesGenericCallsAfterCompileTimeInference() throws Exception {
        String program = """
                fnc identity<T>(T value) => T {
                  return value;
                }

                define module helpers as
                  pub fnc choose<T>(T left, T right) => T {
                    return right;
                  }
                end

                define class Echo as
                  pub echo<T>(T value) => T {
                    return value;
                  }

                  pub static fnc static_echo<T>(T value) => T {
                    return value;
                  }
                end

                pub fnc main() => void {
                  val echo = new Echo();
                  val a = identity(10);
                  val b = helpers.choose(20, 21);
                  val c = echo.echo(30);
                  val d = Echo.static_echo(40);
                  stdio.stdout.write(a + b + c + d);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "generic-calls.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("101"));
    }
}
