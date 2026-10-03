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

                define module api as
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
                define module outer as
                  define module inner as
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
                define module model as
                  define class Counter as
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
                define class Bad as
                  static nope() => int { return 1; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad as
                  static fnc nope() => int { return self.value; }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad as
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

    @Test
    void escapedLocalStructAbiTracksOnlyPublicSurface() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        Map<String, String> first = Map.of(
                "factory.ores", """
                        pub fnc make() => T {
                          struct T {
                            pub value: int;
                            private hidden: int = 1;
                          }
                          return obj{value: 7};
                        }
                        """,
                "consumer.ores", """
                        import fnc {make} from "./factory.ores";
                        pub fnc consume() => void { return; }
                        """);
        compiler.compile(first);

        Map<String, String> privateLayoutChange = Map.of(
                "factory.ores", """
                        pub fnc make() => T {
                          struct T {
                            pub value: int;
                            private hidden: String = "internal";
                          }
                          return obj{value: 7};
                        }
                        """,
                "consumer.ores", first.get("consumer.ores"));
        var second = compiler.compile(privateLayoutChange);
        assertTrue(second.rebuilt("factory.ores"));
        assertTrue(second.reused("consumer.ores"),
                "private local-struct layout changes must not invalidate importers");

        Map<String, String> publicShapeChange = Map.of(
                "factory.ores", """
                        pub fnc make() => T {
                          struct T {
                            pub value: int;
                            pub label: String = "visible";
                            private hidden: String = "internal";
                          }
                          return obj{value: 7};
                        }
                        """,
                "consumer.ores", first.get("consumer.ores"));
        var third = compiler.compile(publicShapeChange);
        assertTrue(third.rebuilt("factory.ores"));
        assertTrue(third.rebuilt("consumer.ores"),
                "public local-struct shape changes must invalidate importers");
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
    @Test
    void traitCompositionParticipatesInExportedAbiInvalidation() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        Map<String, String> first = Map.of(
                "model.ores", """
                        define module model as
                          define trait Behavior as
                            pub value() => int { return 1; }
                          end

                          define class Box with Behavior as
                          end
                        end
                        """,
                "consumer.ores", """
                        import class {Box} from "./model.ores";
                        pub fnc accept(Box value) => void { return; }
                        """);
        compiler.compile(first);

        Map<String, String> implementationOnly = Map.of(
                "model.ores", """
                        define module model as
                          define trait Behavior as
                            pub value() => int { return 2; }
                          end

                          define class Box with Behavior as
                          end
                        end
                        """,
                "consumer.ores", first.get("consumer.ores"));
        var second = compiler.compile(implementationOnly);
        assertTrue(second.rebuilt("model.ores"));
        assertTrue(second.reused("consumer.ores"),
                "trait method body changes must not invalidate importers when composed ABI is unchanged");

        Map<String, String> publicShapeChange = Map.of(
                "model.ores", """
                        define module model as
                          define trait Behavior as
                            pub value() => int { return 2; }
                            pub label() => String { return "box"; }
                          end

                          define class Box with Behavior as
                          end
                        end
                        """,
                "consumer.ores", first.get("consumer.ores"));
        var third = compiler.compile(publicShapeChange);
        assertTrue(third.rebuilt("model.ores"));
        assertTrue(third.rebuilt("consumer.ores"),
                "trait changes that alter a composed public class shape must invalidate importers");
    }
    @Test
    void nonEscapingCallableLocalTypeChangesDoNotInvalidateImporters() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        Map<String, String> first = Map.of(
                "producer.ores", """
                        pub fnc value() => int {
                          struct Helper {
                            value: int;
                          }
                          return 1;
                        }
                        """,
                "consumer.ores", """
                        import fnc {value} from "./producer.ores";
                        pub fnc main() => void {
                          stdio.println(value());
                          return;
                        }
                        """);
        compiler.compile(first);

        Map<String, String> changed = Map.of(
                "producer.ores", """
                        pub fnc value() => int {
                          struct Helper {
                            label: string;
                            count: int;
                          }
                          return 1;
                        }
                        """,
                "consumer.ores", first.get("consumer.ores"));

        var second = compiler.compile(changed);
        assertTrue(second.rebuilt("producer.ores"));
        assertTrue(second.reused("consumer.ores"),
                "implementation-only local type changes must not pollute exported ABI");
    }

    @Test
    void escapingCallableLocalAliasChangesInvalidateImporters() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        Map<String, String> first = Map.of(
                "producer.ores", """
                        pub fnc value() => Out {
                          type Out = int;
                          return 1;
                        }
                        """,
                "consumer.ores", """
                        import fnc {value} from "./producer.ores";
                        pub fnc main() => void {
                          stdio.println(value());
                          return;
                        }
                        """);
        compiler.compile(first);

        Map<String, String> changed = Map.of(
                "producer.ores", """
                        pub fnc value() => Out {
                          type Out = string;
                          return "one";
                        }
                        """,
                "consumer.ores", first.get("consumer.ores"));

        var second = compiler.compile(changed);
        assertTrue(second.rebuilt("producer.ores"));
        assertTrue(second.rebuilt("consumer.ores"),
                "escaping local aliases are part of the callable ABI");
    }

}
