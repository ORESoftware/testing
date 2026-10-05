package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import dev.oreslang.compiler.OresCompiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FutureAllLanguageTest {

    @Test
    void awaitFutureAllRunsIndependentAsyncCallsAndPreservesOrder() throws Exception {
        String program = """
                async fnc left(): int {
                  return 20;
                }

                async fnc right(): int {
                  return 22;
                }

                pub fnc main(): void {
                  val values = await Future.all([left(), right()]);
                  stdio.stdout.write(values[0]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(values[1]);
                  return;
                }
                """;

        assertEquals("20:22", run(program));
    }

    @Test
    void raceReturnsAValueThroughTheLanguageAwaitBoundary() throws Exception {
        assertEquals("7", run("""
                async fnc value(): int { return 7; }
                pub routine main(): void {
                  stdio.stdout.write(await Future.race(arr[value()]));
                  return;
                }
                """));
    }

    @Test
    void combinatorsRejectNonFutureInputsAndWrongArity() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                  val invalid = Future.all(arr[1, 2]);
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                  val invalid = Future.race();
                  return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "future-all.ores")
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
