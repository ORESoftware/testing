package dev.oreslang.launcher;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CompilerCheckModeTest {
    @Test
    void validationCompilesWithoutExecutingMain() throws Exception {
        Path source = Files.createTempFile("ores-check-", ".ores");
        Files.writeString(source, """
                pub fnc main(): void {
                  let denominator = 0;
                  let result = 1 / denominator;
                  return;
                }
                """);

        IncrementalCompiler.BuildResult result = LinkedProgramRunner.validate(source);

        assertEquals(1, result.units().size());
        assertTrue(result.units().containsKey(source.toAbsolutePath().normalize().toString().replace('\\', '/')));
    }

    @Test
    void positionedParserErrorsBecomeEditorDiagnostics() {
        Path source = Path.of("demo.ores");
        String diagnostic = OresMain.formatCheckDiagnostic(
                source,
                new IllegalArgumentException("Oreslang parse error at 7:13: expected expression"));

        assertTrue(diagnostic.endsWith("demo.ores:7:13: error: expected expression"));
    }

    @Test
    void unpositionedErrorsFallBackToFileStart() {
        Path source = Path.of("demo.ores");
        String diagnostic = OresMain.formatCheckDiagnostic(
                source,
                new IllegalArgumentException("unknown binding 'value'"));

        assertTrue(diagnostic.endsWith("demo.ores:1:1: error: unknown binding 'value'"));
    }
}
