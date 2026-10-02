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
                define module math
                  pub fnc add(int a, int b) => int { return a + b; }
                end

                define module model
                  define class A
                    pub value() => int { return 7; }
                  end
                  define class B extends A
                  end
                end

                define module app
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
}
