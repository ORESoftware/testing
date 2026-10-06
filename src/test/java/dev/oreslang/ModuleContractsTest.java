package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ModuleContractsTest {
    @TempDir
    Path tempDir;


    @Test
    void modulesConformWithFunctionsAndFieldsAndCanBeIteratedAsTypedHandles() throws Exception {
        String output = run("""
                pub define trait StorageModule as
                  kind: String;
                  fnc health(): int;
                end

                define module RedisStorage with StorageModule as
                  pub const String kind = "redis";
                  pub fnc health(): int { return 1; }
                end

                define module PostgresStorage with StorageModule as
                  pub const String kind = "postgres";
                  pub fnc health(): int { return 2; }
                end

                pub routine main(): void {
                  val List<Module<StorageModule>> stores = [RedisStorage, PostgresStorage];
                  for store of stores do
                    stdio.stdout.write(store.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(store.health());
                    stdio.stdout.write("|");
                  done
                  return;
                }
                """);

        assertEquals("redis:1|postgres:2|", output);
    }

    @Test
    void explicitModuleContractsRejectMissingFieldsAndBadCallableShapes() {
        IllegalArgumentException missingField = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub define trait StorageModule as
                          kind: String;
                          fnc health(): int;
                        end

                        define module Broken with StorageModule as
                          pub fnc health(): int { return 1; }
                        end
                        """)));
        assertTrue(missingField.getMessage().contains("does not adhere"));

        IllegalArgumentException badCallable = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub define trait StorageModule as
                          kind: String;
                          fnc health(): int;
                        end

                        define module Broken with StorageModule as
                          pub const String kind = "broken";
                          pub fnc health(): String { return "bad"; }
                        end
                        """)));
        assertTrue(badCallable.getMessage().contains("does not adhere"));
    }

    @Test
    void moduleHandleTypeCannotBeSatisfiedByAnOrdinaryStructuralRecord() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub define trait StorageModule as
                  kind: String;
                  fnc health(): int;
                end

                fnc use(Module<StorageModule> storage): int {
                  return storage.health();
                }

                fnc bad(): int {
                  val record = {kind: "not-a-module"};
                  return use(record);
                }
                """)));
    }

    @Test
    void importedTraitsCanContractMultipleModulesAcrossFiles() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("storage-contract.ores", """
                pub define trait StorageModule as
                  kind: String;
                  fnc health(): int;
                end
                """);
        sources.put("redis.ores", """
                import trait StorageModule from "./storage-contract.ores";

                define module RedisStorage with StorageModule as
                  pub const String kind = "redis";
                  pub fnc health(): int { return 1; }
                end

                pub fnc selected(): Module<StorageModule> {
                  return RedisStorage;
                }
                """);
        sources.put("postgres.ores", """
                import trait StorageModule as BackendContract from "./storage-contract.ores";

                define module PostgresStorage with BackendContract as
                  pub const String kind = "postgres";
                  pub fnc health(): int { return 2; }
                end

                pub fnc selected(): Module<BackendContract> {
                  return PostgresStorage;
                }
                """);

        var result = compiler.compile(sources);
        assertEquals(3, result.rebuiltUnits().size());
        assertTrue(result.units().containsKey("redis.ores"));
        assertTrue(result.units().containsKey("postgres.ores"));
    }

    @Test
    void linkedRuntimeKeepsImportedContractTypesForProgrammaticModuleDispatch() throws Exception {
        Path contract = tempDir.resolve("storage-contract.ores");
        Path redis = tempDir.resolve("redis.ores");
        Path app = tempDir.resolve("app.ores");

        Files.writeString(contract, """
                pub define trait StorageModule as
                  kind: String;
                  fnc health(): int;
                end
                """);

        Files.writeString(redis, """
                import trait StorageModule from "./storage-contract.ores";

                define module RedisStorage with StorageModule as
                  pub const String kind = "redis";
                  pub fnc health(): int { return 7; }
                end
                """);

        Files.writeString(app, """
                import trait StorageModule from "./storage-contract.ores";
                import module RedisStorage from "./redis.ores";

                fnc inspect(Module<StorageModule> storage): void {
                  stdio.stdout.write(storage.kind);
                  stdio.stdout.write(":");
                  stdio.stdout.write(storage.health());
                  return;
                }

                pub routine main(): void {
                  inspect(RedisStorage);
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                app,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                error);

        assertEquals("redis:7", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void importedModuleContractFailsClosedWhenImplementationDrifts() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = Map.of(
                "storage-contract.ores", """
                        pub define trait StorageModule as
                          kind: String;
                          fnc health(): int;
                        end
                        """,
                "broken.ores", """
                        import trait StorageModule from "./storage-contract.ores";

                        define module Broken with StorageModule as
                          pub const String kind = "broken";
                          pub fnc health(): String { return "bad"; }
                        end
                        """);

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> compiler.compile(sources));
        assertTrue(failure.getMessage().contains("does not adhere"));
    }

    @Test
    void contractFieldsCanRequireConstValOrLetBindings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub define trait StatefulModule as
                  const version: String;
                  val label: String;
                  let count: int;
                end

                define module Good with StatefulModule as
                  pub const String version = "v1";
                  pub val String label = "good";
                  pub let int count = 0;
                end
                """)));

        IllegalArgumentException wrongBinding = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub define trait VersionedModule as
                          const version: String;
                        end

                        define module Broken with VersionedModule as
                          pub val String version = "v1";
                        end
                        """)));
        assertTrue(wrongBinding.getMessage().contains("does not adhere"));
    }

    @Test
    void unqualifiedContractFieldsRequireReadabilityButNotABindingKind() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub define trait ReadableModule as
                  value: String;
                end

                define module Mutable with ReadableModule as
                  pub let String value = "mutable";
                end

                define module Constant with ReadableModule as
                  pub const String value = "constant";
                end
                """)));
    }

    @Test
    void hotLoaderCanRequireAnExternalModuleContractBeforeStaging() {
        var contractProgram = TypeChecker.check(Parser.parse("""
                pub define trait PluginModule as
                  const kind: String;
                  fnc health(): int;
                end
                """));
        var required = TypeChecker.moduleContractType(
                contractProgram,
                dev.oreslang.ast.Ast.TypeRef.simple("PluginModule"));

        try (HotReloadManager hot = new HotReloadManager(
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit())) {
            var good = hot.loadModule(
                    "good-plugin.ores",
                    """
                    define module GoodPlugin as
                      pub const String kind = "good";
                      pub fnc health(): int { return 1; }
                    end
                    """,
                    "GoodPlugin",
                    required);

            assertFalse(good.started());
            assertEquals(1, hot.liveGenerations());

            IllegalArgumentException bad = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.loadModule(
                            "bad-plugin.ores",
                            """
                            define module BadPlugin as
                              pub val String kind = "bad";
                            end
                            """,
                            "BadPlugin",
                            required));
            assertTrue(bad.getMessage().contains("does not satisfy required"));
            assertEquals(
                    1,
                    hot.liveGenerations(),
                    "failed contract validation must happen before staging a generation");
        }
    }

    @Test
    void hotLoaderRequiresAnActualNamedModuleNotARecordLikeShape() {
        var contractProgram = TypeChecker.check(Parser.parse("""
                pub define trait PluginModule as
                  kind: String;
                end
                """));
        var required = TypeChecker.moduleContractType(
                contractProgram,
                dev.oreslang.ast.Ast.TypeRef.simple("PluginModule"));

        try (HotReloadManager hot = new HotReloadManager(
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit())) {
            IllegalArgumentException missingModule = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.loadModule(
                            "record-only.ores",
                            """
                            pub const record_like = obj{kind: "not-a-module"};
                            """,
                            "record_like",
                            required));
            assertTrue(missingModule.getMessage().contains("unknown exported module"));
            assertEquals(0, hot.liveGenerations());
        }
    }

    @Test
    void importedModuleValuesDoNotDegradeToUnknown() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("storage-contract.ores", """
                pub define trait StorageModule as
                  kind: String;
                  fnc health(): int;
                end
                """);
        sources.put("loose.ores", """
                define module LooseStorage as
                  pub const String kind = "loose";
                end
                """);
        sources.put("app.ores", """
                import trait StorageModule from "./storage-contract.ores";
                import module LooseStorage from "./loose.ores";

                fnc inspect(Module<StorageModule> storage): int {
                  return storage.health();
                }

                pub routine main(): void {
                  inspect(LooseStorage);
                  return;
                }
                """);

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> compiler.compile(sources));
        assertTrue(
                failure.getMessage().contains("argument")
                        || failure.getMessage().contains("assign"),
                "imported module values must retain a precise module shape: " + failure.getMessage());
    }

    @Test
    void traitAndInterfaceSelectorsRemainDistinct() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = Map.of(
                "contracts.ores", """
                        pub define trait StorageModule as
                          fnc health(): int;
                        end
                        """,
                "app.ores", """
                        import interface StorageModule from "./contracts.ores";
                        pub routine main(): void { return; }
                        """);

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> compiler.compile(sources));
        assertTrue(failure.getMessage().contains("does not match an exported declaration"));
    }

    @Test
    void moduleContractsParticipateInAbiInvalidation() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        Map<String, String> first = new LinkedHashMap<>();
        first.put("contracts.ores", """
                pub define trait A as
                  fnc run(): int;
                end

                pub define trait B as
                  fnc run(): int;
                end
                """);
        first.put("provider.ores", """
                import trait A from "./contracts.ores";
                import trait B from "./contracts.ores";

                define module Worker with A as
                  pub fnc run(): int { return 1; }
                end
                """);
        first.put("consumer.ores", """
                import module Worker from "./provider.ores";
                pub routine main(): void { return; }
                """);

        compiler.compile(first);

        Map<String, String> changed = new LinkedHashMap<>(first);
        changed.put("provider.ores", """
                import trait A from "./contracts.ores";
                import trait B from "./contracts.ores";

                define module Worker with B as
                  pub fnc run(): int { return 1; }
                end
                """);

        var result = compiler.compile(changed);
        assertTrue(result.rebuilt("provider.ores"));
        assertTrue(result.rebuilt("consumer.ores"),
                "changing a module's declared contract is part of its public ABI");
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "module-contracts.ores")
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
