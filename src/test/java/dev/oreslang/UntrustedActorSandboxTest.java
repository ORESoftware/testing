package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class UntrustedActorSandboxTest {
    private static final class RecordingRequestTransport implements ActorRuntime.HttpRequestTransport {
        private final ByteBuffer body;

        private RecordingRequestTransport(String body) {
            this.body = ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override public String method() { return "POST"; }
        @Override public String path() { return "/sandbox"; }

        @Override
        public java.util.Optional<String> header(String name) {
            return name.equalsIgnoreCase("content-type")
                    ? java.util.Optional.of("text/plain")
                    : java.util.Optional.empty();
        }

        @Override
        public int read(ByteBuffer target) {
            if (!body.hasRemaining()) return -1;
            int count = Math.min(body.remaining(), target.remaining());
            ByteBuffer window = body.duplicate();
            window.limit(body.position() + count);
            target.put(window);
            body.position(body.position() + count);
            return count;
        }
    }

    private static final class RecordingTransport implements ActorRuntime.HttpResponseTransport {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private volatile boolean completed;
        private volatile Throwable aborted;

        @Override
        public int write(ByteBuffer source) {
            int count = source.remaining();
            byte[] bytes = new byte[count];
            source.get(bytes);
            body.writeBytes(bytes);
            return count;
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void abort(Throwable cause) {
            aborted = cause;
        }

        String bodyUtf8() {
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void parserModelsUntrustedAsFirstClassActorKind() {
        Ast.Program program = Parser.parse("""
                untrusted actor Sandbox {
                  let int counter = 0;

                  pub receive(int message): void {
                    self.counter = self.counter + 1;
                    return;
                  }
                }

                pub untrusted actor fnc task(int value) => int {
                  return value;
                }
                """);

        Ast.ClassDecl actor = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        Ast.FunctionDecl task = (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        assertEquals(Ast.ActorKind.UNTRUSTED, actor.actorKind());
        assertEquals(Ast.ActorKind.UNTRUSTED, task.actorKind());

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared untrusted actor Bad {
                  let int value = 1;
                }
                """));
    }

    @Test
    void untrustedSourceHasNoAmbientFilesystemNetworkOrFfiAuthority() {
        Ast.Program program = Parser.parse("""
                untrusted actor Sandbox {
                  pub receive(int message): void {
                    fs.read("/tmp/secret");
                    return;
                  }
                }
                """);

        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void lifetimeCanNeverExceedFiveMinutes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorRuntime.UntrustedActorLimits(
                        Duration.ofSeconds(301),
                        10,
                        1024,
                        1024));
    }

    @Test
    void idleUntrustedActorExpiresAtHardDeadline() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.UntrustedActorLimits limits =
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofMillis(40),
                            100,
                            1024,
                            1024);

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    context -> (message, turn) -> { });

            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorLifetimeExceededException.class,
                    ref.failure().orElseThrow());
        }
    }

    @Test
    void fuelStopsAnUncooperativeGuestAtMandatoryCheckpoints() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.UntrustedActorLimits limits =
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofSeconds(5),
                            8,
                            1024,
                            1024);

            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    context -> (message, turn) -> {
                        while (true) {
                            turn.checkpoint();
                        }
                    });

            ref.send("run");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorBudgetExceededException.class,
                    ref.failure().orElseThrow());
        }
    }

    @Test
    void untrustedActorCannotSpawnChildren() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofSeconds(5),
                            100,
                            1024,
                            1024),
                    null,
                    context -> (message, turn) -> {
                        turn.spawnPrivate(child -> (ignored, childTurn) -> { });
                    });

            ref.send("run");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, ref.failure().orElseThrow());
        }
    }

    @Test
    void outboundOutboxDataFromUntrustedActorIsIndependentlyBounded() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<Object> parent =
                    runtime.<Object>spawnPrivate(context -> (message, turn) -> { });

            var sandbox = runtime.<Object>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofSeconds(5),
                            100,
                            16,
                            1024),
                    null,
                    context -> (message, turn) -> {
                        @SuppressWarnings("unchecked")
                        ActorRuntime.ActorRef<Object> destination =
                                (ActorRuntime.ActorRef<Object>) ((java.util.List<?>) message).getFirst();
                        destination.send("x".repeat(17));
                    });

            sandbox.send(java.util.List.of(parent));
            assertTrue(sandbox.awaitTermination(2, TimeUnit.SECONDS));
            IllegalStateException failure = assertInstanceOf(
                    IllegalStateException.class,
                    sandbox.failure().orElseThrow());
            assertTrue(failure.getMessage().contains("outbox payload exceeds 16 bytes"));
        }
    }

    @Test
    void directHttpResponseBypassesInboxOutboxAndIsByteBounded() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofSeconds(5),
                            100,
                            1024,
                            5),
                    transport,
                    context -> (message, turn) -> {
                        ActorRuntime.HttpResponseCapability response =
                                turn.httpResponse().orElseThrow();
                        response.write(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
                        assertThrows(
                                ActorRuntime.HttpResponseLimitExceededException.class,
                                () -> response.write(ByteBuffer.wrap(new byte[]{'!'})));
                        response.complete();
                        turn.self().stop();
                    });

            ref.send("respond");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals("hello", transport.bodyUtf8());
            assertTrue(transport.completed);
            assertNull(transport.aborted);
        }
    }

    @Test
    void directHttpRequestBodyCanStreamWithoutMailboxCopiesAndIsBounded() throws Exception {
        RecordingRequestTransport request = new RecordingRequestTransport("ping-extra");
        RecordingTransport responseTransport = new RecordingTransport();

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofSeconds(5),
                            100,
                            1024,
                            4,
                            16),
                    request,
                    responseTransport,
                    context -> (message, turn) -> {
                        ActorRuntime.HttpRequestCapability httpRequest =
                                turn.httpRequest().orElseThrow();
                        ActorRuntime.HttpResponseCapability httpResponse =
                                turn.httpResponse().orElseThrow();

                        assertEquals("POST", httpRequest.method());
                        assertEquals("/sandbox", httpRequest.path());
                        assertEquals(
                                "text/plain",
                                httpRequest.header("content-type").orElseThrow());

                        ActorRuntime.PrivateMemoryBlock chunk =
                                turn.privateMemory().orElseThrow().allocatePrivateBytes(4);
                        assertEquals(4, chunk.readFrom(httpRequest, 0, 4));

                        assertThrows(
                                ActorRuntime.HttpRequestLimitExceededException.class,
                                () -> httpRequest.read(ByteBuffer.allocate(1)));

                        assertEquals(4, chunk.writeTo(httpResponse, 0, 4));
                        httpResponse.complete();
                        turn.self().stop();
                    });

            ref.send("handle-request");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals("ping", responseTransport.bodyUtf8());
            assertTrue(responseTransport.completed);
            assertNull(responseTransport.aborted);
        }
    }

    @Test
    void actorOwnedNativeMemoryIsReleasedOnTermination() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var ref = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofSeconds(5),
                            100,
                            1024,
                            1024),
                    null,
                    context -> (message, turn) -> {
                        ActorRuntime.PrivateMemoryBlock block =
                                turn.privateMemory().orElseThrow().allocatePrivateBytes(4096);
                        block.writeLong(0, 42L);
                        if (block.readLong(0) != 42L) throw new AssertionError("private memory mismatch");
                        turn.self().stop();
                    });

            ref.send("allocate");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }
}
