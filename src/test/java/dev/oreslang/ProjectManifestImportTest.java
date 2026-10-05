package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectManifestImportTest {
    @TempDir Path temp;

    @Test
    void manifestSourceRootsResolveBareImportsThroughCompileAndRuntimeLinking() throws Exception {
        Path project = temp.resolve("project");
        Path src = project.resolve("src");
        Files.createDirectories(src.resolve("pkg"));
        Path main = src.resolve("main.ores");

        Files.writeString(project.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"

                [project]
                name = "manifest-demo"
                root = "."

                [source]
                roots = ["src"]

                [entrypoints]
                main = "src/main.ores"
                """);
        Files.writeString(src.resolve("pkg/greeting.ores"), """
                pub fnc greeting() : string {
                  return "manifest-path";
                }
                """);
        Files.writeString(main, """
                import fnc greeting from "pkg/greeting";

                pub fnc main() : void {
                  stdio.println(greeting());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals(2, build.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("manifest-path"));
    }

    @Test
    void importedWildcardActorCallableCannotBypassActorCompositionBoundary() throws Exception {
        Path app = temp.resolve("actor-import");
        Files.createDirectories(app);
        Path child = app.resolve("child.ores");
        Path main = app.resolve("main.ores");

        Files.writeString(child, """
                pub actor fnc child(int value): int {
                  return value + 1;
                }
                """);

        Files.writeString(main, """
                import * as external from "./child.ores";

                actor fnc parent(int value): int {
                  return external.child(value);
                }

                pub routine main(): void {
                  stdio.stdout.write(parent(1));
                  return;
                }
                """);

        PolyglotException failure = assertThrows(
                PolyglotException.class,
                () -> LinkedProgramRunner.run(
                        main,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));

        assertTrue(failure.getMessage().contains("mailbox-oriented actor composition"));
    }

    @Test
    void wildcardNamespaceCannotExtractRoutineOrActorCallableValues() throws Exception {
        Path app = temp.resolve("wildcard-direct-only");
        Files.createDirectories(app);
        Path child = app.resolve("child.ores");
        Path routineMain = app.resolve("routine-main.ores");
        Path actorMain = app.resolve("actor-main.ores");

        Files.writeString(child, """
                pub routine direct_only(int value): int {
                  return value + 1;
                }

                pub actor fnc actor_only(int value): int {
                  return value + 1;
                }
                """);

        Files.writeString(routineMain, """
                import * as external from "./child.ores";

                pub routine main(): void {
                  val callback = external.direct_only;
                  return;
                }
                """);

        Files.writeString(actorMain, """
                import * as external from "./child.ores";

                pub routine main(): void {
                  val callback = external.actor_only;
                  return;
                }
                """);

        PolyglotException routineFailure = assertThrows(
                PolyglotException.class,
                () -> LinkedProgramRunner.run(
                        routineMain,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(routineFailure.getMessage().contains("direct-call-only"));

        PolyglotException actorFailure = assertThrows(
                PolyglotException.class,
                () -> LinkedProgramRunner.run(
                        actorMain,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(actorFailure.getMessage().contains("scheduler-dispatched"));
        assertTrue(actorFailure.getMessage().contains("cannot be extracted"));
    }

    @Test
    void importedTailCallsPreserveCallerReturnContracts() throws Exception {
        Path app = temp.resolve("tail-contract-import");
        Files.createDirectories(app);
        Path child = app.resolve("child.ores");
        Path namedMain = app.resolve("named-main.ores");
        Path wildcardMain = app.resolve("wildcard-main.ores");

        Files.writeString(child, """
                pub fnc wrong(): String {
                  return "not-an-int";
                }
                """);

        Files.writeString(namedMain, """
                import fnc wrong from "./child.ores";

                fnc wrapped(): int {
                  return wrong();
                }

                pub routine main(): void {
                  stdio.stdout.write(wrapped());
                  return;
                }
                """);

        Files.writeString(wildcardMain, """
                import * as external from "./child.ores";

                fnc wrapped(): int {
                  return external.wrong();
                }

                pub routine main(): void {
                  stdio.stdout.write(wrapped());
                  return;
                }
                """);

        for (Path entry : List.of(namedMain, wildcardMain)) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class,
                    () -> LinkedProgramRunner.run(
                            entry,
                            IsolatePolicy.developer(),
                            ExecutionProfile.serverJit(),
                            Set.of(),
                            Map.of(),
                            new ByteArrayOutputStream(),
                            new ByteArrayOutputStream()));
            assertTrue(failure.getMessage().contains("function wrapped returned String"));
            assertTrue(failure.getMessage().contains("name=int"));
        }
    }

    @Test
    void importFncRejectsDirectOnlyRoutineAndActorCallableAtLinkValidation() throws Exception {
        Path app = temp.resolve("fnc-import-contract");
        Files.createDirectories(app);
        Path routineUnit = app.resolve("routine.ores");
        Path actorUnit = app.resolve("actor.ores");
        Path routineMain = app.resolve("routine-main.ores");
        Path actorMain = app.resolve("actor-main.ores");

        Files.writeString(routineUnit, """
                pub routine work(int value): int {
                  return value + 1;
                }
                """);
        Files.writeString(actorUnit, """
                pub actor fnc work(int value): int {
                  return value + 1;
                }
                """);
        Files.writeString(routineMain, """
                import fnc work from "./routine.ores";
                pub routine main(): void { return; }
                """);
        Files.writeString(actorMain, """
                import fnc work from "./actor.ores";
                pub routine main(): void { return; }
                """);

        for (Path entry : List.of(routineMain, actorMain)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(entry));
            assertTrue(failure.getMessage().contains("does not match an exported declaration"));
        }
    }

    @Test
    void genericFncsCannotBeReifiedThroughCrossFileImports() throws Exception {
        Path app = temp.resolve("generic-fnc-import-contract");
        Files.createDirectories(app);
        Path child = app.resolve("child.ores");
        Path namedMain = app.resolve("named-main.ores");
        Path wildcardMain = app.resolve("wildcard-main.ores");
        Path classMain = app.resolve("class-main.ores");

        Files.writeString(child, """
                pub fnc identity<T>(T value): T {
                  return value;
                }

                define class GenericTools as
                  pub static fnc identity<T>(T value): T {
                    return value;
                  }
                end
                """);

        Files.writeString(namedMain, """
                import fnc identity from "./child.ores";
                pub routine main(): void { return; }
                """);

        IllegalArgumentException namedFailure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(namedMain));
        assertTrue(namedFailure.getMessage().contains("does not match an exported declaration"));

        Files.writeString(wildcardMain, """
                import * as external from "./child.ores";

                pub routine main(): void {
                  val callback = external.identity;
                  return;
                }
                """);

        PolyglotException wildcardFailure = assertThrows(
                PolyglotException.class,
                () -> LinkedProgramRunner.run(
                        wildcardMain,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(wildcardFailure.getMessage().contains("polymorphic function values are not supported yet"));

        Files.writeString(classMain, """
                import * as external from "./child.ores";

                pub routine main(): void {
                  val callback = external.GenericTools.identity;
                  return;
                }
                """);

        PolyglotException classFailure = assertThrows(
                PolyglotException.class,
                () -> LinkedProgramRunner.run(
                        classMain,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(classFailure.getMessage().contains("generic static fnc"));
        assertTrue(classFailure.getMessage().contains("polymorphic function values are not supported yet"));
    }

    @Test
    void oreslangPathResolvesBareImportsWithoutAManifest() throws Exception {
        Path app = temp.resolve("app");
        Path shared = temp.resolve("shared-root");
        Files.createDirectories(app);
        Files.createDirectories(shared.resolve("common"));
        Path main = app.resolve("main.ores");

        Files.writeString(shared.resolve("common/message.ores"), """
                pub fnc message() : string {
                  return "oreslang-path";
                }
                """);
        Files.writeString(main, """
                import fnc message from "common/message";

                pub fnc main() : void {
                  stdio.println(message());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH, shared.toString()),
                out,
                new ByteArrayOutputStream());

        assertEquals(2, build.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("oreslang-path"));
    }
}
