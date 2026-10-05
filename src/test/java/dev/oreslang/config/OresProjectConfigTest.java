package dev.oreslang.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class OresProjectConfigTest {
    @TempDir Path temp;

    @Test
    void discoversNearestManifestAndResolvesProjectPaths() throws Exception {
        Path project = temp.resolve("project");
        Path src = project.resolve("src");
        Path lib = project.resolve("lib");
        Files.createDirectories(src.resolve("nested"));
        Files.createDirectories(lib);
        Files.writeString(src.resolve("main.ores"), "pub fnc main() => void { return; }");
        Files.writeString(project.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"

                [project]
                name = "demo"
                version = "0.1.0"
                root = "."

                [source]
                roots = ["src"]
                import_paths = ["lib"]

                [entrypoints]
                main = "src/main.ores"
                """);

        OresProjectConfig config = OresProjectConfig.discover(
                src.resolve("nested").resolve("file.ores"),
                Map.of());

        assertEquals(project.toAbsolutePath().normalize(), config.projectRoot());
        assertEquals("demo", config.projectName().orElseThrow());
        assertEquals(src.toAbsolutePath().normalize(), config.sourceRoots().getFirst());
        assertEquals(lib.toAbsolutePath().normalize(), config.importPaths().getFirst());
        assertEquals(src.resolve("main.ores").toAbsolutePath().normalize(), config.mainEntrypoint().orElseThrow());
    }

    @Test
    void oreslangPathIsAnOrderedOsNativePathList() throws Exception {
        Path one = temp.resolve("one");
        Path two = temp.resolve("two");

        OresProjectConfig config = OresProjectConfig.discover(
                temp,
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH,
                        one + File.pathSeparator + File.pathSeparator + two + File.pathSeparator));

        assertEquals(
                java.util.List.of(one.toAbsolutePath().normalize(), two.toAbsolutePath().normalize()),
                config.environmentPaths());
    }

    @Test
    void projectRootsWinBeforeOreslangPathFallback() throws Exception {
        Path project = temp.resolve("project");
        Path src = project.resolve("src");
        Path ambient = temp.resolve("ambient");
        Files.createDirectories(src.resolve("pkg"));
        Files.createDirectories(ambient.resolve("pkg"));
        Files.writeString(src.resolve("pkg/value.ores"), "pub fnc value() => int { return 1; }");
        Files.writeString(ambient.resolve("pkg/value.ores"), "pub fnc value() => int { return 2; }");
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main() => void { return; }");
        Files.writeString(project.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"
                [source]
                roots = ["src"]
                """);

        OresProjectConfig config = OresProjectConfig.discover(
                importer,
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH, ambient.toString()));

        assertEquals(
                src.resolve("pkg/value.ores").toAbsolutePath().normalize(),
                config.resolveImport(importer, "pkg/value").orElseThrow());
    }

    @Test
    void relativeImportsNeverFallThroughToSearchRoots() throws Exception {
        Path importerDir = temp.resolve("src");
        Path ambient = temp.resolve("ambient");
        Files.createDirectories(importerDir);
        Files.createDirectories(ambient);
        Path importer = importerDir.resolve("main.ores");
        Files.writeString(importer, "pub fnc main() => void { return; }");
        Files.writeString(ambient.resolve("missing.ores"), "pub fnc value() => int { return 1; }");

        OresProjectConfig config = OresProjectConfig.discover(
                importer,
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH, ambient.toString()));

        assertTrue(config.resolveImport(importer, "./missing").isEmpty());
    }

    @Test
    void rejectsUnsupportedSchemaVersion() throws Exception {
        Path manifest = temp.resolve(OresProjectConfig.MANIFEST_NAME);
        Files.writeString(manifest, "schema_version = \"999\"\n");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));
        assertTrue(error.getMessage().contains("schema_version"));
    }
}
