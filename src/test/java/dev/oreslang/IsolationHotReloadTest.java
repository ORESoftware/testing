package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class IsolationHotReloadTest {
    @Test
    void executionProfilesCoverJitAotAndHybrid() {
        assertTrue(ExecutionProfile.serverJit().guestJitAllowed());
        assertFalse(ExecutionProfile.serverJit().hostAheadOfTime());

        ExecutionProfile hybrid = ExecutionProfile.serverHybrid();
        assertTrue(hybrid.guestJitAllowed());
        assertTrue(hybrid.hostAheadOfTime());

        ExecutionProfile ios = ExecutionProfile.mobileAot(ExecutionProfile.Platform.IOS);
        assertTrue(ios.hostAheadOfTime());
        assertFalse(ios.guestJitAllowed());
        assertTrue(ios.supportsSourceHotReload());

        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionProfile(ExecutionProfile.Mode.JIT, ExecutionProfile.Platform.IOS));
    }

    @Test
    void capabilityAdmissionRejectsForbiddenApiBeforeGuestExecution() {
        var program = TypeChecker.check(Parser.parse("""
                pub routine main() => void {
                  stdio.stdout.write(process.context_id);
                }
                """));

        SecurityException denied = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertTrue(denied.getMessage().contains("PROCESS_INFO"));

        assertDoesNotThrow(() -> CapabilityChecker.check(program,
                IsolatePolicy.strictFaas().withCapabilities(IsolatePolicy.Capability.PROCESS_INFO)));
    }

    @Test
    void runtimeCapabilityCheckCannotBeBypassedByFacadeDispatch() throws Exception {
        IsolatePolicy noOutput = new IsolatePolicy(Set.of(), 64L * 1024 * 1024, 32, Duration.ofSeconds(5));
        Source source = Source.newBuilder(OresLanguage.ID, """
                pub routine main() => void {
                  stdio.stdout.write("forbidden");
                }
                """, "denied.ores").mimeType(OresLanguage.MIME_TYPE).buildLiteral();

        RuntimeException error = assertThrows(RuntimeException.class, () -> {
            try (Context context = noOutput.restrictedContextBuilder(ExecutionProfile.serverJit()).build()) {
                context.eval(source);
            }
        });
        assertTrue(error.getMessage().contains("STDOUT"));
    }

    @Test
    void hotReloadCreatesDistinctVersionedContextsWithoutFfi() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var first = hot.load("service.ores", """
                    pub routine main() => void { return; }
                    """);
            var second = hot.load("service.ores", """
                    pub routine main() => void {
                      val version = 2;
                      return;
                    }
                    """);

            assertNotEquals(first.id(), second.id());
            assertNotEquals(first.sha256(), second.sha256());
            assertNull(hot.active(), "staged code must not become active before successful startup");
            assertEquals(2, hot.liveGenerations());

            first.start();
            first.activate();
            assertEquals(first.id(), hot.active().id());

            second.start();
            second.activate();
            assertEquals(second.id(), hot.active().id());
            assertTrue(first.closed(), "unpinned old generations should be reclaimed after the switch");
            assertEquals(1, hot.liveGenerations());
        }
    }

    @Test
    void hotLoadStagesWithoutRunningMainUntilExplicitStart() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.load("staged.ores", """
                    pub routine main() => void {
                      val values = arr[1];
                      val boom = values[99];
                      return;
                    }
                    """);
            assertFalse(generation.started());
            assertThrows(RuntimeException.class, generation::start);
            assertTrue(generation.closed());
        }
    }

    @Test
    void rootInitNameIsOrdinaryAndNeverRunsImplicitly() throws Exception {
        String program = """
                fnc init(value: int) -> void {
                  val values = arr[1];
                  val boom = values[99];
                  return;
                }

                pub routine main() -> void {
                  return;
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "no-implicit-init.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .build()) {
            assertDoesNotThrow(() -> context.eval(source),
                    "a function merely named init must not execute during link/start");
        }
    }

    @Test
    void hotReloadRequiresExplicitCapability() {
        assertThrows(SecurityException.class,
                () -> new HotReloadManager(IsolatePolicy.strictFaas(), ExecutionProfile.serverJit()));
    }

    @Test
    void allExplicitStructuralParameterSpellingsWork() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Bar {
                  marker: 'brand'
                }

                pub interface Foo extends Bar {
                  markerBrand: 'marking/branding'
                }

                fnc first(y structural Foo) => void {
                  return;
                }

                @AllowStructural(y)
                fnc second(y Foo) => void {
                  return;
                }

                fnc third(@Structural Foo y) => void {
                  return;
                }

                pub routine main() => void {
                  val branded = obj{marker: "brand", markerBrand: "marking/branding"};
                  first(branded);
                  second(branded);
                  third(branded);
                  return;
                }
                """)));
    }

    @Test
    void structuralPermissionIsNotImplicit() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub interface Foo {
                  marker: 'brand'
                }

                fnc nominal(Foo y) => void { return; }

                pub routine main() => void {
                  val branded = obj{marker: "brand"};
                  nominal(branded);
                  return;
                }
                """)));
    }

    @Test
    void extractedMethodValueKeepsReceiverAndSelfCannotBeRebound() throws Exception {
        String output = run("""
                define class Box as
                  val int value;

                  pub get() => int {
                    return self.value;
                  }
                end

                pub routine main() => void {
                  val box = new Box(17);
                  val Fnc<int> callback = box.get;
                  stdio.stdout.write(callback())
                }
                """);
        assertEquals("17", output);

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub bad() => void {
                    self = new Box();
                    return;
                  }
                end
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "receiver.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
