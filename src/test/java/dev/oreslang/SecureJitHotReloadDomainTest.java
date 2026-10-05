package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SecureJitHotReloadDomainTest {
    private static final String NOOP = """
            pub routine main() => void {
              return;
            }
            """;

    @Test
    void supervisorLoaderAuthorityIsNeverInheritedByGuestGeneration() {
        IsolatePolicy supervisor = IsolatePolicy.developer();
        IsolatePolicy requestedGuest = IsolatePolicy.developer();

        try (HotReloadManager hot = new HotReloadManager(
                supervisor,
                requestedGuest,
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.TRUSTED_JIT)) {
            assertTrue(hot.supervisorPolicy().allows(IsolatePolicy.Capability.HOT_CODE_LOAD));
            assertFalse(hot.guestPolicy().allows(IsolatePolicy.Capability.HOT_CODE_LOAD));
            assertEquals(HotReloadManager.ExecutionDomain.TRUSTED_JIT, hot.executionDomain());
        }
    }

    @Test
    void untrustedJitDomainFailsClosedEvenWhenSupervisorIsPrivileged() {
        IsolatePolicy privilegedGuestRequest = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FFI,
                IsolatePolicy.Capability.NATIVE,
                IsolatePolicy.Capability.REFLECTION,
                IsolatePolicy.Capability.THREAD_CREATE,
                IsolatePolicy.Capability.POLYGLOT,
                IsolatePolicy.Capability.NETWORK,
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE,
                IsolatePolicy.Capability.ENVIRONMENT);

        try (HotReloadManager hot = new HotReloadManager(
                IsolatePolicy.developer(),
                privilegedGuestRequest,
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.UNTRUSTED_JIT)) {
            assertTrue(hot.guestPolicy().adversarial());
            assertTrue(hot.guestPolicy().capabilities().isEmpty(),
                    "untrusted hot-loaded code gets no ambient authority");
            assertTrue(hot.guestPolicy().maxHeapBytes()
                    <= IsolatePolicy.untrustedActor().maxHeapBytes());
            assertTrue(hot.guestPolicy().maxWallTime().compareTo(
                    IsolatePolicy.untrustedActor().maxWallTime()) <= 0);
        }
    }

    @Test
    void trustedIsoactorJitStaysPrimaryWhileStrippingHostEscapeHatches() {
        IsolatePolicy requested = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FFI,
                IsolatePolicy.Capability.NATIVE,
                IsolatePolicy.Capability.REFLECTION,
                IsolatePolicy.Capability.THREAD_CREATE,
                IsolatePolicy.Capability.POLYGLOT);

        try (HotReloadManager hot = new HotReloadManager(
                IsolatePolicy.developer(),
                requested,
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.TRUSTED_ISOACTOR_JIT)) {
            assertFalse(hot.executionDomain().spawnedIsolate(),
                    "trusted isoactors must remain in the primary Graal isolate");
            assertFalse(hot.guestPolicy().adversarial(),
                    "trusted isoactor confinement is not the untrusted/adversarial isolate boundary");
            assertFalse(hot.guestPolicy().allows(IsolatePolicy.Capability.FFI));
            assertFalse(hot.guestPolicy().allows(IsolatePolicy.Capability.NATIVE));
            assertFalse(hot.guestPolicy().allows(IsolatePolicy.Capability.REFLECTION));
            assertFalse(hot.guestPolicy().allows(IsolatePolicy.Capability.THREAD_CREATE));
            assertFalse(hot.guestPolicy().allows(IsolatePolicy.Capability.POLYGLOT));
            assertFalse(hot.guestPolicy().allows(IsolatePolicy.Capability.HOT_CODE_LOAD));
        }
    }

    @Test
    void jitDomainRejectsAotOnlyExecutionProfile() {
        assertThrows(IllegalArgumentException.class, () -> new HotReloadManager(
                IsolatePolicy.developer(),
                IsolatePolicy.developer(),
                ExecutionProfile.mobileAot(ExecutionProfile.Platform.IOS),
                HotReloadManager.ExecutionDomain.TRUSTED_JIT));
    }

    @Test
    void activationDrainsPinnedOldGenerationAndReclaimsAfterLeaseRelease() throws Exception {
        try (HotReloadManager hot = new HotReloadManager(
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit())) {
            HotReloadManager.Generation first = hot.loadAndStart("service.ores", NOOP);
            assertEquals(HotReloadManager.GenerationState.ACTIVE, first.state());

            HotReloadManager.GenerationLease lease = hot.pinActive("service.ores");
            HotReloadManager.Generation second = hot.load("service.ores", """
                    pub routine main() => void {
                      val version = 2;
                      return;
                    }
                    """);
            second.start();
            second.activate();

            assertEquals(second.id(), hot.active("service.ores").id());
            assertEquals(HotReloadManager.GenerationState.DRAINING, first.state());
            assertFalse(first.closed(),
                    "an actor/request pin must keep the old context alive while it drains");
            assertEquals(2, hot.liveGenerations());

            lease.close();

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while ((!first.closed() || hot.liveGenerations() != 1)
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(first.closed(),
                    "control-plane generation closure must complete after the final lease release");
            assertEquals(1, hot.liveGenerations(),
                    "control-plane reclamation must remove the closed generation from ownership maps");
            assertEquals(HotReloadManager.GenerationState.ACTIVE, second.state());
        }
    }

    @Test
    void generationIdsRemainMonotonicAcrossLoaderInstances() {
        long firstId;
        try (HotReloadManager first = new HotReloadManager(
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit())) {
            firstId = first.load("first.ores", NOOP).id();
        }

        try (HotReloadManager second = new HotReloadManager(
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit())) {
            long secondId = second.load("second.ores", NOOP).id();
            assertTrue(secondId > firstId,
                    "new loader instances must not reuse process-visible generation ids");
        }
    }

    @Test
    void failedStagedGenerationNeverDisplacesHealthyActiveGeneration() {
        try (HotReloadManager hot = new HotReloadManager(
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit())) {
            HotReloadManager.Generation healthy =
                    hot.loadAndStart("service.ores", NOOP);

            HotReloadManager.Generation broken = hot.load("service.ores", """
                    pub routine main() => void {
                      val values = arr[1];
                      val boom = values[99];
                      return;
                    }
                    """);

            assertThrows(RuntimeException.class, broken::start);
            assertEquals(healthy.id(), hot.active("service.ores").id());
            assertEquals(HotReloadManager.GenerationState.ACTIVE, healthy.state());
            assertTrue(broken.closed());
        }
    }
}
