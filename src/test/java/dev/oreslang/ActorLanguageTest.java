package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ActorLanguageTest {

    @Test
    void namedFunctionCanRunAsActorEntrypoint() throws Exception {
        String output = run("""
                fnc worker(str message) => void {
                  stdio.stdout.write(message);
                  return;
                }

                pub routine main() => void {
                  val worker_ref = actor.spawn(worker);
                  actor.send(worker_ref, "hello-actor");
                  actor.stop(worker_ref);
                  actor.join(worker_ref);
                  return;
                }
                """);

        assertEquals("hello-actor", output);
    }

    @Test
    void actorRefsRemainTypedUsableHandlesAfterCrossingAMailbox() throws Exception {
        String output = run("""
                fnc target(String message) => void {
                  stdio.stdout.write(message);
                  return;
                }

                fnc relay(ActorRef<String> target_ref) => void {
                  stdio.stdout.write(target_ref.id == target_ref.id ? "id:" : "bad:");
                  actor.send(target_ref, "ping");
                  return;
                }

                pub routine main() => void {
                  val target_ref = actor.spawn(target);
                  val relay_ref = actor.spawn(relay);
                  actor.send(relay_ref, target_ref);
                  actor.stop(relay_ref);
                  actor.join(relay_ref);
                  actor.stop(target_ref);
                  actor.join(target_ref);
                  return;
                }
                """);

        assertEquals("id:ping", output);
    }

    @Test
    void singletonActorReusesOneNamedProcess() throws Exception {
        String output = run("""
                fnc worker(str message) => void {
                  stdio.stdout.write(message);
                  return;
                }

                pub routine main() => void {
                  val first = actor.singleton("cache_primary", worker);
                  val second = actor.singleton("cache_primary", worker);
                  stdio.stdout.write(first.id == second.id ? "same:" : "different:");
                  actor.send(second, "ok");
                  actor.stop(first);
                  actor.join(first);
                  return;
                }
                """);

        assertEquals("same:ok", output);
    }

    @Test
    void lexicalLambdaCannotBecomeActorEntrypoint() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                pub routine main() => void {
                  val int outer = 7;
                  val callback = |str message| -> {
                    stdio.stdout.write(message);
                    stdio.stdout.write(outer);
                    return;
                  };
                  actor.spawn(callback);
                  return;
                }
                """));

        String message = failure instanceof PolyglotException polyglot ? polyglot.getMessage() : failure.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("nlex") || message.contains("actor"));
    }

    @Test
    void inlineNlexActorLambdaGetsAStaticActorContract() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val worker_ref = actor.spawn(nlex |String message| -> {
                    stdio.stdout.write(message);
                    return;
                  });
                  actor.send(worker_ref, "inline");
                  actor.stop(worker_ref);
                  actor.join(worker_ref);
                  return;
                }
                """);

        assertEquals("inline", output);
    }

    @Test
    void inlineActorLambdaMustBeNlexAndTypeItsMessage() {
        Exception lexical = assertThrows(Exception.class, () -> run("""
                pub routine main() => void {
                  actor.spawn(|String message| -> {
                    return;
                  });
                  return;
                }
                """));
        assertTrue(lexical.getMessage().contains("nlex"));

        Exception inferredMessage = assertThrows(Exception.class, () -> run("""
                pub routine main() => void {
                  actor.spawn(nlex |message| -> {
                    return;
                  });
                  return;
                }
                """));
        assertTrue(inferredMessage.getMessage().contains("explicit type"));
    }

    @Test
    void statefulActorKeepsPrivateStateAcrossMessages() throws Exception {
        String output = run("""
                fnc counter(str message, int state) => int {
                  stdio.stdout.write(state);
                  return state + 1;
                }

                pub routine main() => void {
                  val counter_ref = actor.spawn(counter, 0);
                  actor.send(counter_ref, "tick");
                  actor.send(counter_ref, "tick");
                  actor.stop(counter_ref);
                  actor.join(counter_ref);
                  return;
                }
                """);

        assertEquals("01", output);
    }

    @Test
    void supervisorCanMonitorChildFailure() throws Exception {
        String output = run("""
                pub interface DownMessage {
                  event: String;
                  reason: String;
                }

                fnc supervisor(@Structural DownMessage message) => void {
                  stdio.stdout.write(message.event);
                  stdio.stdout.write(":");
                  stdio.stdout.write(message.reason);
                  return;
                }

                fnc crashing_worker(str message) => void {
                  actor.join(actor.self);
                  return;
                }

                pub routine main() => void {
                  val supervisor_ref = actor.spawn(supervisor);
                  val child_ref = actor.spawn(crashing_worker);
                  actor.monitor(child_ref, supervisor_ref);
                  actor.send(child_ref, "boom");
                  actor.join(child_ref);
                  actor.stop(supervisor_ref);
                  actor.join(supervisor_ref);
                  return;
                }
                """);

        assertEquals("DOWN:failed", output);
    }

    @Test
    void actorSendRejectsWrongMessageTypeBeforeExecution() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                fnc worker(String message) => void {
                  return;
                }

                pub routine main() => void {
                  val worker_ref = actor.spawn(worker);
                  actor.send(worker_ref, 42);
                  return;
                }
                """));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("actor.send") || failure.getMessage().contains("assignable"));
    }

    @Test
    void actorMailboxRejectsUnconstrainedGenericMessageTypes() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                fnc worker<T>(T message) => void {
                  return;
                }

                pub routine main() => void {
                  actor.spawn(worker);
                  return;
                }
                """));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("unconstrained generic")
                || failure.getMessage().contains("concrete transport type"));
    }

    @Test
    void actorMailboxRejectsInteropNullMarkerTypes() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                fnc worker(Option<null> message) => void {
                  return;
                }

                pub routine main() => void {
                  actor.spawn(worker);
                  return;
                }
                """));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("null marker")
                || failure.getMessage().contains("Option"));
    }

    @Test
    void statefulActorRequiresHandlerToReturnItsStateType() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                fnc bad_worker(String message, int state) => String {
                  return "wrong";
                }

                pub routine main() => void {
                  actor.spawn(bad_worker, 0);
                  return;
                }
                """));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("return state") || failure.getMessage().contains("assignable"));
    }

    @Test
    void shareIntrinsicIsAReadOnlyCapabilityCheckedAlias() throws Exception {
        String output = run("""
                fnc worker(Array<String> message) => void {
                  stdio.stdout.write(message[0]);
                  return;
                }

                pub routine main() => void {
                  let values = arr["shared"];
                  val shared = share(values);
                  val worker_ref = actor.spawn(worker);
                  actor.send(worker_ref, shared);
                  actor.stop(worker_ref);
                  actor.join(worker_ref);
                  stdio.stdout.write(":");
                  stdio.stdout.write(values[0]);
                  return;
                }
                """);

        assertEquals("shared:shared", output);
    }

    @Test
    void readonlySharedValuesAreTypedAndDoNotConsumeTheOriginal() throws Exception {
        String output = run("""
                fnc worker(Array<String> message) => void {
                  stdio.stdout.write(message[0]);
                  return;
                }

                pub routine main() => void {
                  val values = arr["shared"];
                  val shared = process.share_readonly(values);
                  val worker_ref = actor.spawn(worker);
                  actor.send(worker_ref, shared);
                  actor.stop(worker_ref);
                  actor.join(worker_ref);
                  stdio.stdout.write(":");
                  stdio.stdout.write(values[0]);
                  return;
                }
                """);

        assertEquals("shared:shared", output);
    }

    @Test
    void ordinaryMutableMessageIsCopiedIntoActorOwnership() throws Exception {
        String output = run("""
                fnc worker(Array<String> mut message) => void {
                  message[0] = "actor";
                  stdio.stdout.write(message[0]);
                  return;
                }

                pub routine main() => void {
                  let values = arr["sender"];
                  val worker_ref = actor.spawn(worker);
                  actor.send(worker_ref, values);
                  actor.stop(worker_ref);
                  actor.join(worker_ref);
                  stdio.stdout.write(":");
                  stdio.stdout.write(values[0]);
                  return;
                }
                """);

        assertEquals("actor:sender", output);
    }

    @Test
    void literalSingletonNameCannotChangeItsActorContract() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                fnc strings(String message) => void {
                  return;
                }

                fnc numbers(int message) => void {
                  return;
                }

                pub routine main() => void {
                  val first = actor.singleton("registry", strings);
                  val second = actor.singleton("registry", numbers);
                  return;
                }
                """));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("singleton") || failure.getMessage().contains("incompatible"));
    }

    @Test
    void dynamicSingletonNameStillEnforcesRuntimeProtocolContract() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                fnc strings(String message) => void {
                  return;
                }

                fnc numbers(int message) => void {
                  return;
                }

                pub routine main() => void {
                  val String registry_name = "dynamic_registry";
                  val first = actor.singleton(registry_name, strings);
                  val second = actor.singleton(registry_name, numbers);
                  return;
                }
                """));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("incompatible runtime contract")
                || failure.getMessage().contains("singleton"));
    }

    @Test
    void unchangedReadonlySharedStateKeepsOnlyHandleCharge() throws Exception {
        String output = run("""
                fnc shared_state(String message, Array<String> state) => Array<String> {
                  stdio.stdout.write(actor.status(actor.self).owned_state_bytes);
                  stdio.stdout.write(":");
                  return state;
                }

                pub routine main() => void {
                  val values = arr["shared-state"];
                  val shared = process.share_readonly(values);
                  val worker_ref = actor.spawn(shared_state, shared);
                  actor.send(worker_ref, "one");
                  actor.send(worker_ref, "two");
                  actor.stop(worker_ref);
                  actor.join(worker_ref);
                  return;
                }
                """);

        assertEquals("64:64:", output);
    }

    @Test
    void complexOptionAndClassValuesPreserveRuntimeTypeAcrossMailboxes() throws Exception {
        String output = run("""
                define class Box
                  val int value;

                  pub get() => int {
                    return self.value;
                  }
                end

                fnc box_worker(Box box) => void {
                  stdio.stdout.write(box.get());
                  return;
                }

                fnc option_worker(Option<int> option) => void {
                  stdio.stdout.write(option);
                  return;
                }

                fnc complex_worker(complex value) => void {
                  stdio.stdout.write(value);
                  return;
                }

                pub routine main() => void {
                  val box = new Box(7);
                  val box_ref = actor.spawn(box_worker);
                  actor.send(box_ref, box);
                  actor.stop(box_ref);
                  actor.join(box_ref);
                  stdio.stdout.write(box.get());
                  stdio.stdout.write(":");

                  val option_ref = actor.spawn(option_worker);
                  actor.send(option_ref, Some(5));
                  actor.stop(option_ref);
                  actor.join(option_ref);
                  stdio.stdout.write(":");

                  val complex_ref = actor.spawn(complex_worker);
                  actor.send(complex_ref, 3 + 4i);
                  actor.stop(complex_ref);
                  actor.join(complex_ref);
                  return;
                }
                """);

        assertEquals("77:Some(5):3.0+4.0i", output);
    }

    @Test
    void closuresCannotBeMailboxValuesOrReadonlySharedRegions() {
        Exception mailboxFailure = assertThrows(Exception.class, () -> run("""
                fnc consumer(Fnc<int, int> callback) => void {
                  return;
                }

                pub routine main() => void {
                  val ref = actor.spawn(consumer);
                  return;
                }
                """));
        assertTrue(mailboxFailure.getMessage().contains("callable")
                || mailboxFailure.getMessage().contains("actor"));

        Exception sharedFailure = assertThrows(Exception.class, () -> run("""
                pub routine main() => void {
                  val Fnc<int, int> callback = |int value| -> {
                    return value + 1;
                  };
                  process.share_readonly(callback);
                  return;
                }
                """));
        assertTrue(sharedFailure.getMessage().contains("callable")
                || sharedFailure.getMessage().contains("share_readonly"));
    }

    @Test
    void ordinaryClassMessagesAreMutableCopiesButSharedClassesAreReadonly() throws Exception {
        String copied = run("""
                define class Bar
                  pub let String foo = "start";
                end

                fnc mutate_copy(Bar mut bar) => void {
                  bar.foo = "actor-copy";
                  stdio.stdout.write(bar.foo);
                  return;
                }

                pub routine main() => void {
                  let Bar original = new Bar();
                  val ref = actor.spawn(mutate_copy);
                  actor.send(ref, original);
                  actor.stop(ref);
                  actor.join(ref);
                  stdio.stdout.write(":");
                  stdio.stdout.write(original.foo);
                  return;
                }
                """);
        assertEquals("actor-copy:start", copied);

        String shared = run("""
                pub interface DownMessage {
                  reason: String;
                }

                define class Bar
                  pub let String foo = "start";
                end

                fnc supervisor(@Structural DownMessage message) => void {
                  stdio.stdout.write(message.reason);
                  return;
                }

                fnc mutate_shared(Bar mut bar) => void {
                  bar.foo = "forbidden";
                  return;
                }

                pub routine main() => void {
                  let Bar original = new Bar();
                  val shared_bar = process.share_readonly(original);
                  val supervisor_ref = actor.spawn(supervisor);
                  val worker_ref = actor.spawn(mutate_shared);
                  actor.monitor(worker_ref, supervisor_ref);
                  actor.send(worker_ref, shared_bar);
                  actor.join(worker_ref);
                  actor.stop(supervisor_ref);
                  actor.join(supervisor_ref);
                  return;
                }
                """);
        assertEquals("failed", shared);
    }

    @Test
    void classWithCallableFieldIsRejectedAsActorDataAtCompileTime() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                define class Holder
                  val Fnc<int, int> callback;
                end

                fnc consume(Holder holder) => void {
                  return;
                }

                pub routine main() => void {
                  val ref = actor.spawn(consume);
                  return;
                }
                """));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("callable")
                || failure.getMessage().contains("Holder.callback"));
    }

    @Test
    void actorGcIsLocalInIntentAndTracksCurrentActorRequests() throws Exception {
        String output = run("""
                fnc worker(String message) => void {
                  val result = actor.gc();
                  stdio.stdout.write(result.actor_local_physical_collection);
                  stdio.stdout.write(":");
                  stdio.stdout.write(actor.status(actor.self).manual_gc_requests);
                  return;
                }

                pub routine main() => void {
                  val ref = actor.spawn(worker);
                  actor.send(ref, "gc");
                  actor.stop(ref);
                  actor.join(ref);
                  return;
                }
                """);

        assertEquals("false:1", output);
    }

    @Test
    void actorGcOutsideActorDoesNotFallBackToGlobalGc() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                pub routine main() => void {
                  actor.gc();
                  return;
                }
                """));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("only available inside an actor")
                || failure.getMessage().contains("actor.gc"));
    }

    @Test
    void processGcIsExplicitAndCallable() throws Exception {
        String previous = System.getProperty("oreslang.gc.host-hint");
        System.setProperty("oreslang.gc.host-hint", "false");
        try {
            assertEquals("ok", run("""
                    pub routine main() => void {
                      process.gc();
                      stdio.stdout.write("ok");
                      return;
                    }
                    """));
        } finally {
            if (previous == null) System.clearProperty("oreslang.gc.host-hint");
            else System.setProperty("oreslang.gc.host-hint", previous);
        }
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "actors.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
