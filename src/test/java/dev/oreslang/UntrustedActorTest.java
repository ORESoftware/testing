package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class UntrustedActorTest {
    @Test
    void lexerAndParserExposeFirstClassUntrustedActorKind() {
        assertTrue(new Lexer("untrusted actor Worker { }").scan().stream()
                .anyMatch(token -> token.type() == Token.Type.UNTRUSTED));

        Ast.Program klassProgram = Parser.parse("""
                untrusted actor Worker {
                  pub fnc run() => void {
                    return;
                  }
                }
                """);
        Ast.ClassDecl klass = (Ast.ClassDecl) klassProgram.modules()
                .getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.UNTRUSTED, klass.actorKind());

        Ast.Program fnProgram = Parser.parse("""
                pub untrusted actor fnc worker() => void {
                  for (let i = 0; i < 10; i = i + 1) {
                    val x = i;
                  }
                  return;
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) fnProgram.modules()
                .getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.UNTRUSTED, fn.actorKind());
    }

    @Test
    void conflictingOrDetachedUntrustedModifierIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared untrusted actor Worker {
                  pub fnc run() => void { return; }
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                untrusted fnc nope() => void {
                  return;
                }
                """));
    }

    @Test
    void capabilityAdmissionUsesFixedUntrustedPolicy() {
        Ast.Program program = Parser.parse("""
                pub untrusted actor fnc worker() => void {
                  val id = process.context_id;
                  return;
                }
                """);

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(denied.getMessage().contains("PROCESS_INFO"));
    }

    @Test
    void localHelperCannotHideForbiddenCapability() {
        Ast.Program program = Parser.parse("""
                fnc helper() => void {
                  val id = process.context_id;
                  return;
                }

                pub untrusted actor fnc worker() => void {
                  helper();
                  return;
                }
                """);

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(denied.getMessage().contains("PROCESS_INFO"));
    }

    @Test
    void importedCodeRequiresEffectMetadataForUntrustedActors() {
        Ast.Program program = Parser.parse("""
                import * as plugin from "./plugin.ores";

                pub untrusted actor fnc worker() => void {
                  plugin.run();
                  return;
                }
                """);

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(denied.getMessage().contains("effect metadata"));
    }

    @Test
    void capabilityFacadesCannotBeLaunderedThroughAliases() {
        Ast.Program program = Parser.parse("""
                pub untrusted actor fnc worker() => void {
                  val p = process;
                  return;
                }
                """);

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(denied.getMessage().contains("restricted capability facade"));
    }

    @Test
    void privilegedHelpersCannotBeLaunderedAsFunctionValues() {
        Ast.Program program = Parser.parse("""
                fnc privileged() => void {
                  val id = process.context_id;
                  return;
                }

                pub untrusted actor fnc worker() => void {
                  val callback = privileged;
                  return;
                }
                """);

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(denied.getMessage().contains("PROCESS_INFO"));
    }

    @Test
    void actorModifiersRemainContextualIdentifiers() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc ordinary() => int {
                  val shared = 1;
                  val untrusted = shared + 1;
                  return untrusted;
                }
                """));
    }

    @Test
    void runtimeForcesZeroCapabilityAdversarialPolicyAndPrivateMemory() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var ref = runtime.<String>spawnUntrusted(context -> {
                assertEquals(ActorRuntime.ActorKind.UNTRUSTED, context.kind());
                assertTrue(context.policy().adversarial());
                assertTrue(context.policy().capabilities().isEmpty());
                assertTrue(context.privateMemory().isPresent());
                assertTrue(context.policy().maxHeapBytes()
                        <= IsolatePolicy.untrustedActor().maxHeapBytes());
                return (message, turn) -> turn.self().stop();
            });

            ref.send("ping");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void untrustedActorCannotSpawnAnotherActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var ref = runtime.<String>spawnUntrusted(context ->
                    (message, turn) -> turn.runtime().spawnPrivate(
                            child -> (childMessage, childTurn) -> { }));

            ref.send("go");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            Throwable failure = ref.failure().orElseThrow();
            assertInstanceOf(SecurityException.class, failure);
            assertTrue(failure.getMessage().contains("ACTOR_SPAWN"));
        }
    }

    @Test
    void untrustedActorCannotUseActorMessagingAsIpc() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var ref = runtime.<String>spawnUntrusted(context ->
                    (message, turn) -> turn.self().send("again"));

            ref.send("go");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            Throwable failure = ref.failure().orElseThrow();
            assertInstanceOf(SecurityException.class, failure);
            assertTrue(failure.getMessage().contains("cannot send actor messages"));
        }
    }

    @Test
    void untrustedInboundMessagesAreDataOnly() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var ordinary = runtime.<String>spawnPrivate(
                    () -> (message, context) -> { });
            var untrusted = runtime.<Object>spawnUntrusted(
                    context -> (message, turn) -> { });

            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> untrusted.send(ordinary));
            assertTrue(denied.getMessage().contains("data-only"));
        }
    }

    @Test
    void untrustedSafepointsTerminateCpuHogInsteadOfTrustingSourceYield() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var ref = runtime.<String>spawnUntrusted(context -> (message, turn) -> {
                for (int i = 0; i < 100_000; i++) {
                    turn.runtime().schedulerSafepoint();
                }
            });

            ref.send("burn");
            assertTrue(ref.awaitTermination(3, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.UntrustedActorQuotaExceededException.class,
                    ref.failure().orElseThrow());
        }
    }

    @Test
    void adversarialHotLoadRejectsOversizedSourceBeforeCompilation() {
        try (HotReloadManager hot = HotReloadManager.forUntrustedActors(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.load("huge.ores", "x".repeat(1_048_577)));
            assertTrue(denied.getMessage().contains("maximum character count"));
        }
    }

    @Test
    void trustedHotLoaderAuthorityIsSeparatedFromUntrustedGuestAuthority() {
        IsolatePolicy supervisor = IsolatePolicy.developer();
        assertTrue(supervisor.allows(IsolatePolicy.Capability.HOT_CODE_LOAD));

        try (HotReloadManager hot =
                     HotReloadManager.forUntrustedActors(supervisor, ExecutionProfile.serverJit())) {
            assertSame(supervisor, hot.supervisorPolicy());
            assertTrue(hot.guestPolicy().adversarial());
            assertTrue(hot.guestPolicy().capabilities().isEmpty());
            assertFalse(hot.guestPolicy().allows(IsolatePolicy.Capability.HOT_CODE_LOAD));
        }

        assertThrows(
                SecurityException.class,
                () -> HotReloadManager.forUntrustedActors(
                        IsolatePolicy.strictFaas(), ExecutionProfile.serverJit()));
    }
}
