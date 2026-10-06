package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ImportSelectorSyntaxTest {
    @TempDir
    Path temp;

    @Test
    void commaAndParenthesizedTypeSelectionsAreEquivalent() {
        Ast.ImportDecl comma = Parser.parse("""
                import types X, Y, Z from '../foo';
                pub routine main(): void { return; }
                """).imports().getFirst();

        Ast.ImportDecl parenthesized = Parser.parse("""
                import types (X, Y, Z) from '../foo';
                pub routine main(): void { return; }
                """).imports().getFirst();

        assertEquals(Ast.ImportKind.TYPES, comma.kind());
        assertEquals(List.of("X", "Y", "Z"), comma.names());
        assertEquals(comma, parenthesized);
    }

    @Test
    void parsesTheCompleteExplicitImportVocabulary() {
        Ast.Program program = Parser.parse("""
                import * as package from './dep';
                import module Service from './dep';
                import actor Worker from './dep';
                import class Box from './dep';
                import fnc make from './dep';
                import interface Api from './dep';
                import trait Retryable from './dep';
                import struct Point from './dep';
                import type Identifier from './dep';
                import types A, B, C from './dep';

                pub routine main(): void { return; }
                """);

        assertEquals(List.of(
                        Ast.ImportKind.ALL,
                        Ast.ImportKind.MODULE,
                        Ast.ImportKind.ACTOR,
                        Ast.ImportKind.CLASS,
                        Ast.ImportKind.FUNCTION,
                        Ast.ImportKind.INTERFACE,
                        Ast.ImportKind.TRAIT,
                        Ast.ImportKind.STRUCT,
                        Ast.ImportKind.TYPE,
                        Ast.ImportKind.TYPES),
                program.imports().stream().map(Ast.ImportDecl::kind).toList());
    }

    @Test
    void linkerDistinguishesActorClassInterfaceAndTypeSelections() throws Exception {
        Path child = temp.resolve("models.ores");
        Path main = temp.resolve("main.ores");

        Files.writeString(child, """
                define module Service
                  pub fnc value(): int { return 1; }
                end

                shared actor Worker {
                  pub fnc value(): int { return 2; }
                }

                define class Box as
                end

                pub interface Api {
                  fnc value() => int;
                }

                pub interface ExtraApi {
                  fnc value() => int;
                }

                type Identifier = int;
                type OtherIdentifier = int;
                """);

        Files.writeString(main, """
                import module Service from './models';
                import actor Worker from './models';
                import class Box from './models';
                import interface Api from './models';
                import type Identifier from './models';
                import types ExtraApi, OtherIdentifier from './models';

                pub routine main(): void { return; }
                """);

        LinkedProgramRunner.validate(main);
    }

    @Test
    void classAndActorSelectorsDoNotAliasEachOther() throws Exception {
        Path child = temp.resolve("actors.ores");
        Files.writeString(child, """
                shared actor Worker {
                  pub fnc value(): int { return 1; }
                }

                define class Box as
                end
                """);

        Path actorAsClass = temp.resolve("actor-as-class.ores");
        Files.writeString(actorAsClass, """
                import class Worker from './actors';
                pub routine main(): void { return; }
                """);

        Path classAsActor = temp.resolve("class-as-actor.ores");
        Files.writeString(classAsActor, """
                import actor Box from './actors';
                pub routine main(): void { return; }
                """);

        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(actorAsClass));
        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(classAsActor));
    }

    @Test
    void importedActorClassIsNotExposedAsAnUnspawnableRuntimeValue() throws Exception {
        Path child = temp.resolve("worker.ores");
        Path main = temp.resolve("main.ores");

        Files.writeString(child, """
                shared actor Worker {
                  pub fnc value(): int { return 1; }
                }
                """);

        Files.writeString(main, """
                import actor Worker from './worker';

                pub routine main(): void {
                  val selected = Worker;
                  return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(failure.getMessage().contains("unknown name 'Worker'"), failure.getMessage());
    }
}
