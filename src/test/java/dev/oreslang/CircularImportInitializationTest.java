package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class CircularImportInitializationTest {
    @TempDir
    Path tempDir;

    @Test
    void twoFilesMayImportEachOtherAndInitRunsOnlyAfterBothAreLinked() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");

        Files.writeString(a, """
                import fnc {b_value} from "./b.ores";

                pub fnc a_value(): String {
                  return "A";
                }

                fnc init(): void {
                  stdio.stdout.write("init-a:");
                  stdio.stdout.write(b_value());
                  stdio.stdout.write("|");
                  return;
                }

                pub routine main(): void {
                  stdio.stdout.write("main:");
                  stdio.stdout.write(a_value());
                  stdio.stdout.write(b_value());
                  return;
                }
                """);

        Files.writeString(b, """
                import fnc {a_value} from "./a.ores";

                pub fnc b_value(): String {
                  return "B";
                }

                fnc init(): void {
                  stdio.stdout.write("init-b:");
                  stdio.stdout.write(a_value());
                  stdio.stdout.write("|");
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        IncrementalCompiler.BuildResult build = LinkedProgramRunner.run(
                a,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                error);

        assertEquals("init-a:B|init-b:A|main:AB", output.toString(StandardCharsets.UTF_8));

        List<List<String>> groups = build.initializationGroups();
        assertEquals(1, groups.size(), "the A<->B cycle should form one initialization barrier");
        assertEquals(2, groups.getFirst().size());
        assertTrue(groups.getFirst().contains(a.toAbsolutePath().normalize().toString().replace('\\', '/')));
        assertTrue(groups.getFirst().contains(b.toAbsolutePath().normalize().toString().replace('\\', '/')));
    }

    @Test
    void initHookHasAClosedLifecycleSignature() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc init(): void { return; }
                pub routine main(): void { return; }
                """)));

        IllegalArgumentException withArgs = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc init(int value): void { return; }
                        """)));
        assertTrue(withArgs.getMessage().contains("init hook"));

        IllegalArgumentException publicInit = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc init(): void { return; }
                        """)));
        assertTrue(publicInit.getMessage().contains("init hook"));
    }
}
