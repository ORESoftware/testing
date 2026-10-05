package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

final class UntrustedActorRuntimeTest {

    @Test
    void hardLifetimeCannotExceedFiveMinutes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorRuntime.UntrustedActorLimits(
                        Duration.ofSeconds(301),
                        100,
                        1024,
                        1024,
                        1024));
    }

    @Test
    void fuelExhaustionFailsActorInsteadOfTrustingVoluntaryYield() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 8, 1024, 1024, 1024);

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    null,
                    ignored -> (message, turn) -> {
                        for (int i = 0; i < 100; i++) turn.checkpoint();
                    });

            ref.send("run");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorBudgetExceededException.class,
                    ref.failure().orElseThrow());
        }
    }

    @Test
    void earlyTerminationRemovesWatchdogRetentionRootImmediately() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(300), 100, 1024, 1024, 1024);

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    null,
                    ignored -> (message, turn) -> turn.self().stop());

            ref.send("done");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0, runtime.pendingUntrustedDeadlineCount(),
                    "terminated untrusted actors must not remain retained by canceled watchdog tasks");
        }
    }

    @Test
    void hardLifetimeReclaimsActorOwnedArenaMemory() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofMillis(100), 10_000, 1024, 1024, 1024);

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    null,
                    ignored -> (message, turn) -> {
                        turn.privateMemory().orElseThrow().allocatePrivateBytes(4096);
                    });

            ref.send("allocate");
            assertTrue(waitUntil(() -> runtime.privateMemoryBytes() >= 4096, 1000));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorLifetimeExceededException.class,
                    ref.failure().orElseThrow());
            assertEquals(0L, runtime.privateMemoryBytes(),
                    "untrusted actor teardown must release all actor-accounted private memory");
        }
    }

    @Test
    void tighterParentPolicyDeadlineBeatsActorLifetimeLimit() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy tighter = new IsolatePolicy(
                    java.util.Set.of(),
                    64L * 1024 * 1024,
                    64,
                    Duration.ofMillis(60),
                    true);
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(5), 100, 1024, 1024, 1024);

            var ref = runtime.<String>spawnUntrusted(
                    tighter,
                    limits,
                    null,
                    null,
                    ignored -> (message, turn) -> { });

            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorLifetimeExceededException.class,
                    ref.failure().orElseThrow());
        }
    }

    @Test
    void responseHeaderFloodIsBoundedIndependentlyOfBody() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            RecordingResponse response = new RecordingResponse();
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 1000, 32, 1024, 1024);

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    response,
                    ignored -> (message, turn) -> {
                        var out = turn.httpResponse().orElseThrow();
                        for (int i = 0; i < 129; i++) {
                            out.header("x-test-" + i, "v");
                        }
                    });

            ref.send("request");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.HttpResponseLimitExceededException.class,
                    ref.failure().orElseThrow());
            assertTrue(response.aborted.get());
        }
    }

    @Test
    void untrustedActorCannotSpawnChildren() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 100, 1024, 1024, 1024);

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    null,
                    ignored -> (message, turn) ->
                            turn.spawnPrivate(factoryContext ->
                                    (childMessage, childContext) -> { }));

            ref.send("spawn");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            SecurityException failure = assertInstanceOf(
                    SecurityException.class,
                    ref.failure().orElseThrow());
            assertTrue(failure.getMessage().contains("cannot spawn child actors"));
        }
    }

    @Test
    void runtimeCeilingAutomaticallyTightensUntrustedPolicy() throws Exception {
        IsolatePolicy ceiling = new IsolatePolicy(
                java.util.Set.of(),
                16L * 1024 * 1024,
                32,
                Duration.ofMillis(70),
                true);
        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            var ref = runtime.<String>spawnUntrusted(
                    ignored -> (message, turn) -> { });

            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorLifetimeExceededException.class,
                    ref.failure().orElseThrow());
        }
    }

    @Test
    void untrustedActorCannotStopAnotherActorThroughActorRef() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var victim = runtime.<Object>spawnPrivate(context -> (message, turn) -> { });
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 100, 1024, 1024, 1024);

            var sandbox = runtime.<Object>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    null,
                    ignored -> (message, turn) -> {
                        @SuppressWarnings("unchecked")
                        ActorRuntime.ActorRef<Object> target =
                                (ActorRuntime.ActorRef<Object>) message;
                        target.stop();
                    });

            sandbox.send(victim);
            assertTrue(sandbox.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, sandbox.failure().orElseThrow());
            assertTrue(victim.isAlive(),
                    "an explicit ActorRef must not grant termination authority to untrusted code");
        }
    }

    @Test
    void untrustedActorCanReplyThroughSendOnlyRecipient() throws Exception {
        java.util.concurrent.atomic.AtomicInteger replies = new java.util.concurrent.atomic.AtomicInteger();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<Object>spawnPrivateTrusted(
                    IsolatePolicy.developer(),
                    context -> (message, turn) -> replies.incrementAndGet());

            var sandbox = runtime.<Object>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofSeconds(2), 100, 1024, 1024, 1024),
                    null,
                    null,
                    ignored -> (message, turn) -> {
                        @SuppressWarnings("unchecked")
                        ActorRuntime.Recipient<Object> parentReply =
                                (ActorRuntime.Recipient<Object>) message;
                        parentReply.send("reply");
                        turn.self().stop();
                    });

            sandbox.send(parent.recipient());
            assertTrue(sandbox.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(waitUntil(() -> replies.get() == 1, 1000));
            assertTrue(sandbox.failure().isEmpty());
        }
    }

    @Test
    void recipientCannotCrossActorRuntimeBoundary() {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            var leftActor = left.<Object>spawnPrivate(context -> (message, turn) -> { });
            var rightActor = right.<Object>spawnPrivate(context -> (message, turn) -> { });

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> rightActor.send(leftActor.recipient()));
            assertTrue(failure.getMessage().contains("Recipient belongs to a different ActorRuntime"));
        }
    }

    @Test
    void recipientIsAChannelCapabilityNotPlainFreezableData() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Object>spawnPrivate(context -> (message, turn) -> { });
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(ref.recipient()));
        }
    }

    @Test
    void untrustedActorCannotInspectForeignFailureObject() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var victim = runtime.<Object>spawnPrivate(context -> (message, turn) -> {
                throw new IllegalStateException("trusted-secret-failure");
            });
            victim.send("fail");
            assertTrue(victim.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(victim.failure().isPresent());

            var sandbox = runtime.<Object>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofSeconds(2), 100, 1024, 1024, 1024),
                    null,
                    null,
                    ignored -> (message, turn) -> {
                        @SuppressWarnings("unchecked")
                        ActorRuntime.ActorRef<Object> target =
                                (ActorRuntime.ActorRef<Object>) message;
                        target.failure();
                    });

            sandbox.send(victim);
            assertTrue(sandbox.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, sandbox.failure().orElseThrow());
        }
    }

    @Test
    void deadTargetFailureCauseIsRedactedFromUntrustedSender() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var victim = runtime.<Object>spawnPrivate(context -> (message, turn) -> {
                throw new IllegalStateException("trusted-secret-failure");
            });
            victim.send("fail");
            assertTrue(victim.awaitTermination(2, TimeUnit.SECONDS));

            var sandbox = runtime.<Object>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofSeconds(2), 100, 1024, 1024, 1024),
                    null,
                    null,
                    ignored -> (message, turn) -> {
                        @SuppressWarnings("unchecked")
                        ActorRuntime.ActorRef<Object> target =
                                (ActorRuntime.ActorRef<Object>) message;
                        target.send("hello");
                    });

            sandbox.send(victim);
            assertTrue(sandbox.awaitTermination(2, TimeUnit.SECONDS));
            ActorRuntime.ActorTerminatedException failure = assertInstanceOf(
                    ActorRuntime.ActorTerminatedException.class,
                    sandbox.failure().orElseThrow());
            assertNull(failure.getCause(),
                    "trusted actor failure cause must be redacted across an untrusted ActorRef");
        }
    }

    @Test
    void untrustedActorCannotBlockWaitingForAnotherActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var victim = runtime.<Object>spawnPrivate(context -> (message, turn) -> { });
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 100, 1024, 1024, 1024);

            var sandbox = runtime.<Object>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    null,
                    ignored -> (message, turn) -> {
                        @SuppressWarnings("unchecked")
                        ActorRuntime.ActorRef<Object> target =
                                (ActorRuntime.ActorRef<Object>) message;
                        target.awaitTermination(1, TimeUnit.SECONDS);
                    });

            sandbox.send(victim);
            assertTrue(sandbox.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, sandbox.failure().orElseThrow());
            assertTrue(victim.isAlive());
        }
    }

    @Test
    void untrustedHttpRequestMetadataIsBounded() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.HttpRequestTransport request = new ActorRuntime.HttpRequestTransport() {
                @Override public String method() { return "GET"; }
                @Override public String path() { return "/" + "x".repeat(9000); }
                @Override public int read(ByteBuffer target) { return -1; }
            };
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 100, 1024, 1024, 1024);

            var sandbox = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    request,
                    null,
                    ignored -> (message, turn) ->
                            turn.httpRequest().orElseThrow().path());

            sandbox.send("request");
            assertTrue(sandbox.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.HttpRequestLimitExceededException.class,
                    sandbox.failure().orElseThrow());
        }
    }

    @Test
    void untrustedHttpHeaderLookupFloodIsBounded() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.HttpRequestTransport request = new ActorRuntime.HttpRequestTransport() {
                @Override public String method() { return "GET"; }
                @Override public String path() { return "/"; }
                @Override public java.util.Optional<String> header(String name) {
                    return java.util.Optional.of("v");
                }
                @Override public int read(ByteBuffer target) { return -1; }
            };
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 1000, 1024, 1024, 1024);

            var sandbox = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    request,
                    null,
                    ignored -> (message, turn) -> {
                        var http = turn.httpRequest().orElseThrow();
                        for (int i = 0; i < 129; i++) {
                            http.header("x-test");
                        }
                    });

            sandbox.send("request");
            assertTrue(sandbox.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.HttpRequestLimitExceededException.class,
                    sandbox.failure().orElseThrow());
        }
    }

    @Test
    void httpCapabilitiesCannotEscapeThroughActorMailboxes() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<Object>spawnPrivate(context -> (message, turn) -> { });
            RecordingResponse response = new RecordingResponse();
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 100, 1024, 1024, 1024);

            var sandbox = runtime.<Object>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    response,
                    ignored -> (message, turn) -> {
                        @SuppressWarnings("unchecked")
                        ActorRuntime.ActorRef<Object> target =
                                (ActorRuntime.ActorRef<Object>) message;
                        target.send(turn.httpResponse().orElseThrow());
                    });

            sandbox.send(parent);
            assertTrue(sandbox.awaitTermination(2, TimeUnit.SECONDS));
            IllegalArgumentException failure = assertInstanceOf(
                    IllegalArgumentException.class,
                    sandbox.failure().orElseThrow());
            assertTrue(failure.getMessage().contains("not Sendable"));
            assertTrue(parent.isAlive());
        }
    }

    @Test
    void outboundOutboxDataFromUntrustedActorIsCapped() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<Object>spawnPrivate(() -> (message, turn) -> { });
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 100, 8, 1024, 1024);

            var ref = runtime.<Object>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    null,
                    ignored -> (message, turn) -> {
                        @SuppressWarnings("unchecked")
                        ActorRuntime.ActorRef<Object> receiver =
                                (ActorRuntime.ActorRef<Object>) message;
                        receiver.send("0123456789");
                    });

            ref.send(target);
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            IllegalStateException failure = assertInstanceOf(
                    IllegalStateException.class,
                    ref.failure().orElseThrow());
            assertTrue(failure.getMessage().contains("outbox payload"));
        }
    }

    @Test
    void httpResponseStreamsWithoutInboxOutboxRoundTrip() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            RecordingResponse response = new RecordingResponse();
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 100, 32, 1024, 1024);

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    response,
                    ignored -> (message, turn) -> {
                        var out = turn.httpResponse().orElseThrow();
                        out.status(200);
                        out.header("content-type", "text/plain");
                        out.write(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
                        out.complete();
                        turn.self().stop();
                    });

            ref.send("request");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
            assertEquals(5, response.bytes.get());
            assertTrue(response.completed.get());
            assertFalse(response.aborted.get());
        }
    }

    @Test
    void httpResponseStateCannotMutateAfterStreamingStarts() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            RecordingResponse response = new RecordingResponse();
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 100, 1024, 1024, 1024);

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    response,
                    ignored -> (message, turn) -> {
                        var out = turn.httpResponse().orElseThrow();
                        out.status(200);
                        out.header("x-before", "ok");
                        out.write(ByteBuffer.wrap(new byte[]{'x'}));
                        out.header("x-after", "forbidden");
                    });

            ref.send("request");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    IllegalStateException.class,
                    ref.failure().orElseThrow());
            assertTrue(response.aborted.get());
            assertEquals(1, response.bytes.get());
        }
    }

    @Test
    void httpResponseLimitFailsClosed() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            RecordingResponse response = new RecordingResponse();
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2), 100, 32, 1024, 4);

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    response,
                    ignored -> (message, turn) ->
                            turn.httpResponse().orElseThrow().write(
                                    ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8))));

            ref.send("request");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.HttpResponseLimitExceededException.class,
                    ref.failure().orElseThrow());
            assertTrue(response.aborted.get());
            assertEquals(0, response.bytes.get());
        }
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) {
            Thread.sleep(2);
        }
        return condition.getAsBoolean();
    }

    private static final class RecordingResponse
            implements ActorRuntime.HttpResponseTransport {
        private final AtomicInteger bytes = new AtomicInteger();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean aborted = new AtomicBoolean();

        @Override
        public int write(ByteBuffer source) {
            int count = source.remaining();
            source.position(source.limit());
            bytes.addAndGet(count);
            return count;
        }

        @Override
        public void complete() {
            completed.set(true);
        }

        @Override
        public void abort(Throwable cause) {
            aborted.set(true);
        }
    }
}
