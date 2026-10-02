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
    void isolatePolicyRoundTripsActorResourceCeilingsIntoGuestArguments() {
        IsolatePolicy policy = new IsolatePolicy(
                Set.of(
                        IsolatePolicy.Capability.STDOUT,
                        IsolatePolicy.Capability.ACTOR_SPAWN),
                64L * 1024L * 1024L,
                77,
                9,
                5,
                Duration.ofSeconds(12),
                false);

        IsolatePolicy parsed = IsolatePolicy.fromApplicationArguments(
                policy.applicationArguments(ExecutionProfile.serverJit()));

        assertEquals(policy.maxHeapBytes(), parsed.maxHeapBytes());
        assertEquals(77, parsed.maxMailboxMessages());
        assertEquals(9, parsed.maxActors());
        assertEquals(5, parsed.maxAsyncTasks());
        assertEquals(Duration.ofSeconds(12), parsed.maxWallTime());
        assertEquals(policy.capabilities(), parsed.capabilities());
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
    void gcAndActorCapabilitiesRemainIndependentlyDeniedInStrictFaas() {
        var gcProgram = TypeChecker.check(Parser.parse("""
                pub routine main() => void {
                  process.gc();
                  return;
                }
                """));
        SecurityException gcDenied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(gcProgram, IsolatePolicy.strictFaas()));
        assertTrue(gcDenied.getMessage().contains("GC_CONTROL"));

        var actorProgram = TypeChecker.check(Parser.parse("""
                fnc worker(String message) => void {
                  return;
                }

                pub routine main() => void {
                  val ref = actor.spawn(worker);
                  actor.send(ref, "hello");
                  return;
                }
                """));
        SecurityException actorDenied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(actorProgram, IsolatePolicy.strictFaas()));
        assertTrue(actorDenied.getMessage().contains("ACTOR_SPAWN")
                || actorDenied.getMessage().contains("ACTOR_SEND"));

        IsolatePolicy messagingOnly = IsolatePolicy.strictFaas().withCapabilities(
                IsolatePolicy.Capability.ACTOR_SPAWN,
                IsolatePolicy.Capability.ACTOR_SEND);
        assertDoesNotThrow(() -> CapabilityChecker.check(actorProgram, messagingOnly));
        assertThrows(SecurityException.class, () -> CapabilityChecker.check(gcProgram, messagingOnly));
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
            var first = hot.loadAndStart("v1.ores", """
                    pub routine main() => void { return; }
                    """);
            var second = hot.loadAndStart("v2.ores", """
                    pub routine main() => void {
                      val version = 2;
                      return;
                    }
                    """);

            assertNotEquals(first.id(), second.id());
            assertNotEquals(first.sha256(), second.sha256());
            assertNotSame(first.context(), second.context());
            assertEquals(second.id(), hot.active().id());
            assertEquals(2, hot.liveGenerations());

            hot.retire(first.id());
            assertEquals(1, hot.liveGenerations());
        }
    }

    @Test
    void retiredGenerationWaitsForOutstandingLeaseThenReclaimsContext() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.loadAndStart("leased.ores", """
                    pub routine main() => void { return; }
                    """);
            HotReloadManager.Lease lease = generation.acquire();

            assertEquals(1L, generation.leaseCount());
            hot.retire(generation.id());

            assertTrue(generation.retired());
            assertFalse(generation.closed());
            assertEquals(0, hot.liveGenerations());
            assertEquals(1, hot.retainedGenerations());

            lease.close();

            assertTrue(generation.closed());
            assertEquals(0, hot.retainedGenerations());
        }
    }

    @Test
    void activatingReplacementRetiresOldGenerationOnlyAfterItsLeaseDrains() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var first = hot.loadAndStart("worker.ores", """
                    pub routine main() => void { return; }
                    """);
            HotReloadManager.Lease lease = first.acquire();

            var second = hot.load("worker.ores", """
                    pub routine main() => void {
                      val version = 2;
                      return;
                    }
                    """);
            assertFalse(second.activated());
            assertSame(first, hot.active("worker.ores"));

            second.start();
            hot.activate(second);

            assertSame(second, hot.active("worker.ores"));
            assertTrue(first.retired());
            assertFalse(first.closed());
            assertEquals(1L, first.leaseCount());

            lease.close();
            assertTrue(first.closed());
            assertEquals(1, hot.retainedGenerations());
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
            assertFalse(generation.activated());
            assertNull(hot.active("staged.ores"));
            assertThrows(RuntimeException.class, generation::start);
            assertTrue(generation.closed());
            assertEquals(0, hot.liveGenerations());
            assertEquals(0, hot.retainedGenerations());
        }
    }

    @Test
    void stagedHotReloadGenerationsAreResourceBounded() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit(), 2)) {
            var first = hot.load("one.ores", "pub routine main() => void { return; }");
            hot.load("two.ores", "pub routine main() => void { return; }");
            assertEquals(2, hot.retainedGenerations());

            IllegalStateException full = assertThrows(
                    IllegalStateException.class,
                    () -> hot.load("three.ores", "pub routine main() => void { return; }"));
            assertTrue(full.getMessage().contains("generation limit"));

            first.close();
            assertEquals(1, hot.retainedGenerations());
            assertDoesNotThrow(() ->
                    hot.load("three.ores", "pub routine main() => void { return; }"));
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
                define class Box
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
                define class Box
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
