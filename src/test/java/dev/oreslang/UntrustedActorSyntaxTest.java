package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class UntrustedActorSyntaxTest {

    @Test
    void parsesUntrustedActorDeclaration() {
        Ast.Program program = Parser.parse("""
                untrusted actor RequestHandler {
                  let requests = 0;

                  pub receive(int value): void {
                    self.requests = self.requests + 1;
                    return;
                  }
                }
                """);

        Ast.ClassDecl actor =
                (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.UNTRUSTED, actor.actorKind());
        assertEquals("RequestHandler", actor.name());

        Ast.Program typed = TypeChecker.check(program);
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void parsesUntrustedActorFunction() {
        Ast.Program program = Parser.parse("""
                pub untrusted actor fnc render(int value) => int {
                  return value;
                }
                """);

        Ast.FunctionDecl actor =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.UNTRUSTED, actor.actorKind());
    }

    @Test
    void sharedAndUntrustedAreMutuallyExclusive() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        shared untrusted actor Bad {
                          pub fnc run() => void {
                            return;
                          }
                        }
                        """));
        assertTrue(failure.getMessage().contains("both 'shared' and 'untrusted'"));
    }

    @Test
    void untrustedModifierCannotFloatOntoOrdinaryFunction() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        untrusted fnc nope() => void {
                          return;
                        }
                        """));
    }

    @Test
    void untrustedActorSourceCannotSelfGrantFilesystemAuthority() {
        Ast.Program program = Parser.parse("""
                untrusted actor RequestHandler {
                  pub receive(int message): void {
                    fs.write("out.txt", "nope");
                    return;
                  }
                }
                """);

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(failure.getMessage().contains("FILESYSTEM_WRITE"));
    }
}
