package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresValues;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeTest {
    @Test
    void freezesMessagesBeforeDelivery() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<List<?>> observed = new AtomicReference<>();
            var ref = runtime.<List<Integer>>spawn(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
            ref.send(mutable);
            mutable.add(3);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), observed.get());
            assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) observed.get()).add(9));
        }
    }

    @Test
    void rejectsUnknownMutableHostObjects() {
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(new StringBuilder("mutable")));
    }

    @Test
    void rejectsCyclicMessageGraphsInsteadOfRecursingForever() {
        ArrayList<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(cyclic));
        assertTrue(error.getMessage().contains("cyclic"));
    }

    @Test
    void publicSharedWrapperCannotSmuggleMutableAliases() {
        ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
        ActorRuntime.Shared<ArrayList<Integer>> untrustedWrapper = new ActorRuntime.Shared<>(mutable);

        @SuppressWarnings("unchecked")
        ActorRuntime.Shared<List<Integer>> frozen =
                (ActorRuntime.Shared<List<Integer>>) ActorRuntime.freeze(untrustedWrapper);

        mutable.add(3);
        assertEquals(List.of(1, 2), frozen.value());
        assertThrows(UnsupportedOperationException.class, () -> frozen.value().add(9));
    }

    @Test
    void rejectsPathologicallyDeepMessageGraphs() {
        Object value = 1;
        for (int i = 0; i < 300; i++) value = List.of(value);

        Object nested = value;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(nested));
        assertTrue(error.getMessage().contains("nesting depth"));
    }

    @Test
    void actorRefsCannotCrossRuntimePolicyBoundaries() throws Exception {
        try (ActorRuntime source = new ActorRuntime(IsolatePolicy.developer());
             ActorRuntime destination = new ActorRuntime(IsolatePolicy.strictFaas())) {
            var sourceRef = source.<String>spawn(() -> (message, context) -> { });
            var destinationRef = destination.<Object>spawn(() -> (message, context) -> { });

            IllegalArgumentException crossRuntime = assertThrows(IllegalArgumentException.class,
                    () -> destinationRef.send(sourceRef));
            assertTrue(crossRuntime.getMessage().contains("owning ActorRuntime"));

            IllegalArgumentException contextFree = assertThrows(IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(sourceRef));
            assertTrue(contextFree.getMessage().contains("owning ActorRuntime"));
        }
    }

    @Test
    void actorRefsRemainSendableInsideTheirOwningRuntime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> observed = new AtomicReference<>();
            var target = runtime.<String>spawn(() -> (message, context) -> { });
            var receiver = runtime.<Object>spawn(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            receiver.send(target);
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertSame(target, observed.get());
        }
    }

    @Test
    void runtimeOwnedOptionValuesDeepFreezeWithTheOuterBudget() {
        ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
        OresValues.OptionValue source = new OresValues.OptionValue(true, mutable);

        OresValues.OptionValue frozen = (OresValues.OptionValue) ActorRuntime.freeze(source);
        mutable.add(3);

        assertEquals(List.of(1, 2), frozen.value());
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) frozen.value()).add(9));
    }

    @Test
    void actorCarriesItsOwnStricterPolicy() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<IsolatePolicy> observed = new AtomicReference<>();
            IsolatePolicy strict = IsolatePolicy.strictFaas();

            var ref = runtime.<String>spawn(strict, () -> (message, context) -> {
                observed.set(context.policy());
                received.countDown();
            });
            ref.send("ping");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(strict.capabilities(), observed.get().capabilities());
            assertEquals(strict.maxMailboxMessages(), observed.get().maxMailboxMessages());
        }
    }

    @Test
    void childActorCannotEscalatePastRuntimePolicyCeiling() {
        IsolatePolicy ceiling = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            IsolatePolicy escalated = ceiling.withCapabilities(IsolatePolicy.Capability.PROCESS_INFO);
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawn(escalated, () -> (message, context) -> { }));
        }
    }

    @Test
    void readonlySharingDeepFreezesContainers() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = runtime.shareReadonly(Map.of("items", List.of(1, 2, 3)));
            assertNotNull(shared.value());
        }
    }
}
