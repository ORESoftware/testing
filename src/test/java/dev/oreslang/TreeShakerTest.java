package dev.oreslang;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TreeShakerTest {
    @Test
    void cliDefinesOverrideEnvironmentDefinesDeterministically() {
        Map<String, String> merged = BuildOptions.mergeDefines(
                Map.of(
                        "ORESLANG_BUILD_DEFINES", "use_a=false,backend=jvm",
                        "ORESLANG_DEFINE_DEBUG", "false"),
                List.of("use_a=true", "backend=native"));

        assertEquals("true", merged.get("use_a"));
        assertEquals("native", merged.get("backend"));
        assertEquals("false", merged.get("DEBUG"));
    }

    @Test
    void buildDefineFoldsFunctionReferenceAndRemovesDeadModule() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub const bool use_a = false;

                define module A
                  pub fnc foo(): String {
                    return "hi";
                  }
                end

                define module B
                  pub fnc foo(): String {
                    return "bye";
                  }
                end

                type F = typeof fnc() => String;

                pub fnc choose(bool t): F {
                  return t ? A.foo : B.foo;
                }

                pub routine main(): void {
                  val F selected = choose(use_a);
                  stdio.stdout.write(selected());
                  return;
                }
                """, BuildOptions.executable(Map.of("use_a", "true")));

        assertTrue(result.retained("A.foo"));
        assertTrue(result.removed("B.foo"));
        assertTrue(result.removed(Parser.ROOT_MODULE + ".use_a"),
                "a const used only to fold build-time control flow should disappear");
        assertTrue(result.removed(Parser.ROOT_MODULE + ".choose"),
                "a constant-argument helper should disappear after specialization when no runtime call remains");

        Set<String> modules = result.program().modules().stream()
                .map(module -> module.name())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(modules.contains("A"));
        assertFalse(modules.contains("B"));
        assertTrue(modules.contains(Parser.ROOT_MODULE));
    }

    @Test
    void buildDefineFoldsIfStatementBeforeReachability() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub const bool debug_backend = false;

                define module DebugBackend
                  pub routine run(): void {
                    stdio.stdout.write("debug");
                    return;
                  }
                end

                define module ReleaseBackend
                  pub routine run(): void {
                    stdio.stdout.write("release");
                    return;
                  }
                end

                pub routine main(): void {
                  if debug_backend; do
                    DebugBackend.run();
                  else
                    ReleaseBackend.run();
                  fi
                  return;
                }
                """, BuildOptions.executable(Map.of("debug_backend", "true")));

        assertTrue(result.retained("DebugBackend.run"));
        assertTrue(result.removed("ReleaseBackend.run"));
    }

    @Test
    void reifiedModuleAliasRetainsItsRuntimeVisibleCallableSurface() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                define module service
                  pub fnc transform(int value): int {
                    return value + 1;
                  }

                  pub routine direct_only(int value): int {
                    return value + 2;
                  }

                  fnc private_helper(): int {
                    return 99;
                  }
                end

                pub routine main(): void {
                  val alias = service;
                  val Fnc<int, int> callback = alias.transform;
                  stdio.stdout.write(callback(4));
                  stdio.stdout.write(alias.direct_only(4));
                  return;
                }
                """, BuildOptions.executable(Map.of()));

        assertTrue(result.retained("service.transform"));
        assertTrue(result.retained("service.direct_only"));
        assertTrue(result.removed("service.private_helper"));
    }

    @Test
    void foldedConditionalPreservesItsLexicalScope() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub const bool enabled = true;

                pub routine main(): void {
                  if enabled {
                    val hidden = 1;
                    stdio.stdout.write(hidden);
                  } fi
                  return;
                }
                """, BuildOptions.executable(Map.of("enabled", "true")));

        Ast.FunctionDecl main = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals("main"))
                .findFirst()
                .orElseThrow();

        assertInstanceOf(Ast.BlockStmt.class, main.body().getFirst(),
                "constant folding must not flatten an if arm into its parent lexical scope");
    }

    @Test
    void directOnlyRoutineCallsAreEligibleForConstantInlining() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                routine plus_one(int value): int {
                  return value + 1;
                }

                pub routine main(): void {
                  stdio.stdout.write(plus_one(41));
                  return;
                }
                """, BuildOptions.executable(Map.of()));

        assertTrue(
                result.removed(Parser.ROOT_MODULE + ".plus_one"),
                "direct-only routines should be at least as inlineable as reifiable fnc declarations");
    }

    @Test
    void libraryBuildPreservesPublicApiButStillDropsPrivateDeadSymbols() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub fnc public_answer(): int { return 42; }
                fnc hidden_answer(): int { return 7; }
                """, BuildOptions.library(Map.of()));

        assertTrue(result.retained(Parser.ROOT_MODULE + ".public_answer"));
        assertTrue(result.removed(Parser.ROOT_MODULE + ".hidden_answer"));
    }

    @Test
    void defineMustTargetAConstDeclaration() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileForBuild("""
                pub val bool use_a = false;
                pub routine main(): void { return; }
                """, BuildOptions.executable(Map.of("use_a", "true"))));
    }

    @Test
    void treeShakingDoesNotHideTypeErrorsInDeadBranches() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileForBuild("""
                pub const bool use_a = true;

                define module A
                  pub fnc foo(): int { return 1; }
                end

                define module B
                  pub fnc foo(): int { return "not an int"; }
                end

                pub routine main(): void {
                  val chosen = use_a ? A.foo : B.foo;
                  chosen();
                  return;
                }
                """, BuildOptions.executable(Map.of("use_a", "true"))));
    }
}
