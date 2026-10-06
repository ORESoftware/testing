package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class OreslangActorIsolationExampleTest {

    @Test
    void oreslangExampleKeepsSharedAndPrivateActorDomainsDistinct() throws Exception {
        String source = Files.readString(
                Path.of("examples/shared-private-actor-isolation.ores"));

        Ast.Program program = TypeChecker.check(Parser.parse(source));

        Ast.ClassDecl shared = null;
        Ast.ClassDecl isolated = null;

        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl actor) {
                    if (actor.name().equals("SharedCounter")) shared = actor;
                    if (actor.name().equals("PrivateCounter")) isolated = actor;
                }
            }
        }

        assertNotNull(shared);
        assertNotNull(isolated);
        assertEquals(Ast.ActorKind.SHARED, shared.actorKind());
        assertEquals(Ast.ActorKind.PRIVATE, isolated.actorKind());

        assertDoesNotThrow(() ->
                CapabilityChecker.check(program, IsolatePolicy.developer()));
    }
}
