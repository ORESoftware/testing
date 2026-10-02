package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class IncrementalFunctorStaticTest {
    @Test
    void namespacesAndModulesAreFlat() {
        var program = TypeChecker.check(Parser.parse("""
                namespace payments;

                define module api
                  pub fnc ping() => int { return 1; }
                end
                """));
        assertEquals("payments", program.namespace());
        assertEquals("api", program.modules().getFirst().name());

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                namespace company.payments;
                fnc x() => void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module company.payments
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module outer
                  define module inner
                  end
                end
                """));
    }

    @Test
    void eachFileIsAnIncrementalCodeUnitAndDependentsInvalidateTransitively() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> firstSources = new LinkedHashMap<>();
        firstSources.put("math.ores", """
                namespace mathpkg;
                pub fnc answer() => int { return 42; }
                """);
        firstSources.put("app.ores", """
                import fnc {answer} from './math.ores';
                pub fnc main() => void { return; }
                """);
        firstSources.put("cli.ores", """
                import fnc {main} from './app.ores';
                pub fnc launch() => void { return; }
                """);
        firstSources.put("unrelated.ores", """
                pub fnc untouched() => int { return 7; }
                """);

        var first = compiler.compile(firstSources);
        assertEquals(4, first.rebuiltUnits().size());
        assertEquals("mathpkg", first.units().get("math.ores").packageId());
        assertEquals("app", first.units().get("app.ores").packageId());

        var second = compiler.compile(firstSources);
        assertEquals(0, second.rebuiltUnits().size());
        assertEquals(4, second.reusedUnits().size());

        Map<String, String> changed = new LinkedHashMap<>(firstSources);
        changed.put("math.ores", """
                namespace mathpkg;
                pub fnc answer() => int { return 43; }
                """);

        var third = compiler.compile(changed);
        assertTrue(third.rebuilt("math.ores"));
        assertTrue(third.reused("app.ores"), "implementation-only dependency edits must not rebuild importers");
        assertTrue(third.reused("cli.ores"), "implementation-only edits must stay local through the dependency graph");
        assertTrue(third.reused("unrelated.ores"));

        Map<String, String> abiChanged = new LinkedHashMap<>(changed);
        abiChanged.put("math.ores", """
                namespace mathpkg;
                pub fnc answer() => String { return "43"; }
                """);

        var fourth = compiler.compile(abiChanged);
        assertTrue(fourth.rebuilt("math.ores"));
        assertTrue(fourth.rebuilt("app.ores"), "public ABI changes must invalidate direct importers");
        assertTrue(fourth.rebuilt("cli.ores"), "ABI changes must conservatively invalidate the transitive reverse-import closure");
        assertTrue(fourth.reused("unrelated.ores"));
    }

    @Test
    void inferredPublicBindingsParticipateInAbiInvalidation() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> first = Map.of(
                "config.ores", "pub val setting = 1;",
                "consumer.ores", """
                        import * as config from "./config.ores";
                        pub routine main() => void { return; }
                        """);
        compiler.compile(first);

        Map<String, String> changed = Map.of(
                "config.ores", "pub val setting = \"one\";",
                "consumer.ores", first.get("consumer.ores"));
        var result = compiler.compile(changed);

        assertTrue(result.rebuilt("config.ores"));
        assertTrue(result.rebuilt("consumer.ores"));
    }

    @Test
    void incrementalUnitsRetainGeneratedGpuArtifacts() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        var build = compiler.compile(Map.of(
                "math.ores", """
                        gpu fnc add(i32 a, i32 b) => i32 {
                          return a + b;
                        }
                        """));

        var unit = build.units().get("math.ores");
        assertNotNull(unit.gpuProgram());
        assertTrue(unit.gpuProgram().hasKernels());
        assertEquals(1, unit.gpuProgram().kernels().size());
        assertTrue(unit.gpuProgram().source().contains("__kernel void"));
    }

    @Test
    void compiledUnitsCanStageDirectlyAsIndependentHotReloadGenerations() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        var build = compiler.compile(Map.of(
                "worker.ores", "pub routine main() => void { return; }",
                "helper.ores", "pub fnc help() => int { return 1; }"));
        var worker = build.units().get("worker.ores");
        var helper = build.units().get("helper.ores");

        try (HotReloadManager hot = new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var workerGeneration = hot.load(worker);
            var helperGeneration = hot.load(helper);
            assertEquals("worker.ores", workerGeneration.codeUnitId());
            assertEquals(worker.sourceDigest(), workerGeneration.sha256());
            assertSame(workerGeneration, hot.active("worker.ores"));
            assertSame(helperGeneration, hot.active("helper.ores"));
            assertEquals(2, hot.activeGenerations().size());
            assertFalse(workerGeneration.started());
            assertFalse(helperGeneration.started());
        }
    }

    @Test
    void unresolvedRelativeImportsFailIncrementalCompilation() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(Map.of(
                "app.ores", """
                        import fnc {missing} from "./missing.ores";
                        pub routine main() => void { return; }
                        """)));
    }

    @Test
    void staticClassFunctionsUseStaticFncAndDoNotReceiveSelf() throws Exception {
        String output = run("""
                define module model
                  define class Counter
                    pub val int value = 9;

                    pub static fnc twice(int x) => int {
                      return x * 2;
                    }

                    pub read() => int {
                      return self.value;
                    }
                  end
                end

                pub routine main() => void {
                  val c = new model.Counter();
                  stdio.stdout.write(model.Counter.twice(c.read()));
                }
                """);
        assertEquals("18", output);

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Bad
                  static nope() => int { return 1; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad
                  static fnc nope() => int { return self.value; }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad
                  static fnc make() => int { return 1; }
                end
                fnc bad() => int {
                  val b = new Bad();
                  return b.make();
                }
                """)));
    }

    @Test
    void functionAliasesNestedFunctionTypesAndPipeLambdasWork() throws Exception {
        String output = run("""
                type F = typeof fnc() -> int;

                fnc find(bool found) => F {
                  return || -> {
                    return found ? 5 : 6;
                  };
                }

                fnc sink() => ((bool foo) -> void) {
                  return |foo| -> {
                    if foo; do
                      stdio.stdout.write("T");
                    else
                      stdio.stdout.write("F");
                    fi
                    return;
                  };
                }

                pub routine main() => void {
                  val F result = find(true);
                  val ((bool flag) -> void) callback = sink();
                  stdio.stdout.write(result());
                  callback(true);
                }
                """);
        assertEquals("5T", output);
    }

    @Test
    void slimArrowBelongsToFunctionTypesAndLambdasFatArrowToNamedReturnTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Mapper = typeof fnc(bool value) -> int;
                fnc make() => ((bool value) -> int) {
                  return |value| -> {
                    if value; do
                      return 1;
                    else
                      return 0;
                    fi
                  };
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc wrong() -> int { return 1; }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc wrong() => int {
                  return || => { return 1; };
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc wrong() => int {
                  val Fnc<int> x = () -> 1;
                  return x();
                }
                """));
    }

    @Test
    void nonVoidLambdasMustReturnOnEveryPath() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                type F = typeof fnc(bool x) -> int;
                fnc make() => F {
                  return |x| -> {
                    if x; do
                      return 1;
                    fi
                  };
                }
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "incremental-functors.ores")
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
