package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class UntrustedOutboundHttpTest {

    @Test
    void defaultLimitIsFiveAndSixthCallNeverReachesTransport() throws Exception {
        AtomicInteger transportCalls = new AtomicInteger();
        ActorRuntime.OutboundHttpTransport transport = request -> {
            transportCalls.incrementAndGet();
            return new CompletableFuture<>();
        };

        try (ActorRuntime runtime = new ActorRuntime()) {
            var limits = ActorRuntime.UntrustedActorLimits.defaults();
            assertEquals(5, limits.maxConcurrentHttpCalls());

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    null,
                    transport,
                    ignored -> (message, turn) -> {
                        var http = turn.outboundHttp().orElseThrow();
                        if (http.maxConcurrent() != 5) {
                            throw new AssertionError("expected max concurrency 5");
                        }
                        for (int i = 0; i < 5; i++) {
                            var started = http.send(get("https://example.test/" + i));
                            if (started.isDone()) {
                                throw new AssertionError("transport future should still be pending");
                            }
                        }
                        OresFuture<ActorRuntime.OutboundHttpResponse> sixth =
                                http.send(get("https://example.test/overflow"));
                        if (!sixth.isCompletedExceptionally()) {
                            throw new AssertionError("sixth concurrent request must fail immediately");
                        }
                        turn.self().stop();
                    });

            ref.send("run");
            assertTrue(ref.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty(), () -> "actor failed: " + ref.failure());
            assertEquals(5, transportCalls.get(),
                    "the sixth request must not reach the host HTTP transport");
        }
    }

    @Test
    void concurrencyLimitIsConfigurablePerUntrustedActor() throws Exception {
        AtomicInteger transportCalls = new AtomicInteger();
        ActorRuntime.OutboundHttpTransport transport = request -> {
            transportCalls.incrementAndGet();
            return new CompletableFuture<>();
        };

        try (ActorRuntime runtime = new ActorRuntime()) {
            var limits = new ActorRuntime.UntrustedActorLimits(
                    Duration.ofSeconds(2),
                    10_000,
                    1024,
                    1024,
                    1024,
                    2);

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    null,
                    transport,
                    ignored -> (message, turn) -> {
                        var http = turn.outboundHttp().orElseThrow();
                        http.send(get("https://example.test/one"));
                        http.send(get("https://example.test/two"));
                        OresFuture<ActorRuntime.OutboundHttpResponse> third =
                                http.send(get("https://example.test/three"));
                        if (!third.isCompletedExceptionally()) {
                            throw new AssertionError("configured third request must be rejected");
                        }
                        turn.self().stop();
                    });

            ref.send("run");
            assertTrue(ref.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty(), () -> "actor failed: " + ref.failure());
            assertEquals(2, transportCalls.get());
        }
    }

    @Test
    void cancellationDoesNotReleasePermitUntilTransportActuallyTerminates() throws Exception {
        class NonCancellingFuture<T> extends CompletableFuture<T> {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }
        }

        java.util.List<NonCancellingFuture<ActorRuntime.OutboundHttpResponse>> upstream =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        ActorRuntime.OutboundHttpTransport transport = request -> {
            NonCancellingFuture<ActorRuntime.OutboundHttpResponse> future =
                    new NonCancellingFuture<>();
            upstream.add(future);
            return future;
        };

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofSeconds(2),
                            10_000,
                            1024,
                            1024,
                            1024,
                            1),
                    null,
                    null,
                    transport,
                    ignored -> (message, turn) -> {
                        var http = turn.outboundHttp().orElseThrow();
                        OresFuture<ActorRuntime.OutboundHttpResponse> first =
                                http.send(get("https://example.test/one"));
                        if (!first.cancel(true)) {
                            throw new AssertionError("first request should be cancellable");
                        }

                        OresFuture<ActorRuntime.OutboundHttpResponse> blocked =
                                http.send(get("https://example.test/two"));
                        if (!blocked.isCompletedExceptionally()) {
                            throw new AssertionError(
                                    "cancel request must not release permit before host termination");
                        }
                        // Host-side transport call accounting is asserted after
                        // the actor terminates. Keeping that host list out of this
                        // closure is itself part of the capture-free sandbox contract.

                        // This transport intentionally ignores cancellation.
                        // Therefore the host operation is still genuinely in
                        // flight and its permit must remain occupied.
                        turn.self().stop();
                    });

            ref.send("run");
            assertTrue(ref.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty(), () -> "actor failed: " + ref.failure());
            assertEquals(1, upstream.size());
        }
    }

    @Test
    void capabilityRejectsTcpTunnelsAndProtocolUpgradesBeforeTransport() throws Exception {
        AtomicInteger transportCalls = new AtomicInteger();
        ActorRuntime.OutboundHttpTransport transport = request -> {
            transportCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new ActorRuntime.OutboundHttpResponse(200, Map.of(), new byte[0]));
        };

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    ActorRuntime.UntrustedActorLimits.defaults(),
                    null,
                    null,
                    transport,
                    ignored -> (message, turn) -> {
                        var http = turn.outboundHttp().orElseThrow();
                        expectSecurity(() -> http.send(new ActorRuntime.OutboundHttpRequest(
                                "CONNECT",
                                URI.create("https://example.test"),
                                Map.of(),
                                new byte[0])));
                        expectSecurity(() -> http.send(new ActorRuntime.OutboundHttpRequest(
                                "GET",
                                URI.create("wss://example.test/socket"),
                                Map.of(),
                                new byte[0])));
                        expectSecurity(() -> http.send(new ActorRuntime.OutboundHttpRequest(
                                "GET",
                                URI.create("https://example.test"),
                                Map.of("Upgrade", java.util.List.of("websocket")),
                                new byte[0])));
                        turn.self().stop();
                    });

            ref.send("run");
            assertTrue(ref.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty(), () -> "actor failed: " + ref.failure());
            assertEquals(0, transportCalls.get());
        }
    }

    @Test
    void oversizedOutboundMetadataAndUrisNeverReachTransport() throws Exception {
        AtomicInteger transportCalls = new AtomicInteger();
        ActorRuntime.OutboundHttpTransport transport = request -> {
            transportCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new ActorRuntime.OutboundHttpResponse(200, Map.of(), new byte[0]));
        };

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    ActorRuntime.UntrustedActorLimits.defaults(),
                    null,
                    null,
                    transport,
                    ignored -> (message, turn) -> {
                        var http = turn.outboundHttp().orElseThrow();

                        try {
                            http.send(new ActorRuntime.OutboundHttpRequest(
                                    "GET",
                                    URI.create("https://example.test/" + "x".repeat(9_000)),
                                    Map.of(),
                                    new byte[0]));
                            throw new AssertionError("expected oversized URI rejection");
                        } catch (ActorRuntime.HttpRequestLimitExceededException expected) {
                            // expected
                        }

                        try {
                            http.send(new ActorRuntime.OutboundHttpRequest(
                                    "GET",
                                    URI.create("https://example.test"),
                                    Map.of("X-Large", java.util.List.of("x".repeat(70_000))),
                                    new byte[0]));
                            throw new AssertionError("expected oversized header rejection");
                        } catch (ActorRuntime.HttpRequestLimitExceededException expected) {
                            // expected
                        }

                        turn.self().stop();
                    });

            ref.send("run");
            assertTrue(ref.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty(), () -> "actor failed: " + ref.failure());
            assertEquals(0, transportCalls.get());
        }
    }

    @Test
    void configuredConcurrencyCannotExceedHardCeiling() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorRuntime.UntrustedActorLimits(
                        Duration.ofSeconds(1),
                        100,
                        1024,
                        1024,
                        1024,
                        ActorRuntime.HARD_MAX_UNTRUSTED_MAX_CONCURRENT_HTTP_CALLS + 1));
    }

    private static ActorRuntime.OutboundHttpRequest get(String uri) {
        return new ActorRuntime.OutboundHttpRequest(
                "GET",
                URI.create(uri),
                Map.of(),
                new byte[0]);
    }

    private static void expectSecurity(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("expected SecurityException");
        } catch (SecurityException expected) {
            // expected
        }
    }
}
