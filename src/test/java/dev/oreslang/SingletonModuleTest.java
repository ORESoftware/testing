package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.ProcessSingletonRegistry;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class SingletonModuleTest {
    @Test
    void parsesSingletonModuleAsDistinctModuleKind() {
        Ast.Program program = Parser.parse("""
                define singleton module process_cache as
                  let int count = 0;
                  pub fnc read() => int { return count; }
                end
                """);

        assertEquals(1, program.modules().size());
        assertTrue(program.modules().getFirst().singleton());
    }

    @Test
    void singletonStateIsMutableOnlyBehindActorSafeFunctions() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define singleton module process_counter as
                  let int count = 0;

                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app as
                  pub fnc main() => void {
                    val int first = await process_counter.next();
                    stdio.println(first);
                    return;
                  }
                end
                """)));

        IllegalArgumentException exposedField = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module bad_singleton as
                          pub let int count = 0;
                        end
                        """)));
        assertTrue(exposedField.getMessage().contains("cannot be public"));
    }

    @Test
    void singletonModulePersistsAcrossIndependentGraalContextsInOneProcess() throws Exception {
        String program = """
                define singleton module process_counter_cross_context_test as
                  let int count = 0;

                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app as
                  pub fnc main() => void {
                    val int n = await process_counter_cross_context_test.next();
                    stdio.println(n);
                    return;
                  }
                end
                """;

        String first = eval(program, "singleton-shared-unit.ores");
        String second = eval(program, "singleton-shared-unit.ores");

        assertTrue(first.contains("1"), first);
        assertTrue(second.contains("2"), second);
    }

    @Test
    void unrelatedCodeUnitsWithSameModuleNameDoNotAlias() throws Exception {
        String program = """
                namespace shared_tenant_namespace;

                define singleton module same_name_cache as
                  let int count = 0;

                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app as
                  pub fnc main() => void {
                    val int n = await same_name_cache.next();
                    stdio.println(n);
                    return;
                  }
                end
                """;

        String tenantA = eval(program, "tenant-a/cache.ores");
        String tenantB = eval(program, "tenant-b/cache.ores");

        assertTrue(tenantA.contains("1"), tenantA);
        assertTrue(tenantB.contains("1"), tenantB);
    }

    @Test
    void hotReloadKeepsStateWhenSingletonSchemaIsStable() throws Exception {
        String firstProgram = """
                define singleton module reload_stable_counter as
                  let int count = 0;

                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app as
                  pub fnc main() => void {
                    val int n = await reload_stable_counter.next();
                    stdio.println(n);
                    return;
                  }
                end
                """;

        String secondProgram = """
                define singleton module reload_stable_counter as
                  let int count = 0;

                  pub fnc next() => int {
                    count = count + 10;
                    return count;
                  }
                end

                define module app as
                  pub fnc main() => void {
                    val int n = await reload_stable_counter.next();
                    stdio.println(n);
                    return;
                  }
                end
                """;

        String first = eval(firstProgram, "reload-stable.ores");
        String second = eval(secondProgram, "reload-stable.ores");

        assertTrue(first.contains("1"), first);
        assertTrue(second.contains("11"), second);
    }

    @Test
    void hotReloadRejectsSingletonSchemaReinterpretationWithoutMigration() throws Exception {
        String firstProgram = """
                define singleton module reload_schema_guard_counter as
                  let int count = 0;

                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app as
                  pub fnc main() => void {
                    val int n = await reload_schema_guard_counter.next();
                    stdio.println(n);
                    return;
                  }
                end
                """;

        String incompatibleProgram = """
                define singleton module reload_schema_guard_counter as
                  let int count = 0;
                  let int extra = 5;

                  pub fnc next() => int {
                    count = count + extra;
                    return count;
                  }
                end

                define module app as
                  pub fnc main() => void {
                    val int n = await reload_schema_guard_counter.next();
                    stdio.println(n);
                    return;
                  }
                end
                """;

        assertTrue(eval(firstProgram, "reload-schema-guard.ores").contains("1"));
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> eval(incompatibleProgram, "reload-schema-guard.ores"));
        assertTrue(String.valueOf(error.getMessage()).contains("schema changed"), error.getMessage());
    }

    @Test
    void singletonStateRequiresExplicitFieldTypes() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module inferred_state as
                          let count = 0;
                          pub fnc read() => int { return count; }
                        end
                        """)));
        assertTrue(error.getMessage().contains("requires an explicit type"));
    }

    @Test
    void singletonInitializersAreContextFreeAndStateTypesAreStable() {
        IllegalArgumentException capabilityInitializer = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module unsafe_init as
                          let String captured = process.context_id;
                          pub fnc read() => String { return captured; }
                        end
                        """)));
        assertTrue(capabilityInitializer.getMessage().contains("context-free initializer"));

        IllegalArgumentException aliasedState = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module aliased_state as
                          type Count = int;
                          let Count count = 0;
                          pub fnc read() => int { return count; }
                        end
                        """)));
        assertTrue(aliasedState.getMessage().contains("type aliases"));
    }

    @Test
    void singletonPublicApiMustBeStaticallySendable() {
        IllegalArgumentException classBoundary = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          val int value;
                        end

                        define singleton module class_boundary as
                          pub fnc echo(Box value) => Box {
                            return value;
                          }
                        end
                        """)));
        assertTrue(classBoundary.getMessage().contains("not statically Sendable"));

        IllegalArgumentException mutableBoundary = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module mutable_boundary as
                          pub fnc consume(Array<int> mut values) => void {
                            return;
                          }
                        end
                        """)));
        assertTrue(mutableBoundary.getMessage().contains("cannot accept mut parameters"));
    }

    @Test
    void crossSingletonCallsMustBeImmediatelyAwaited() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module await_required as
                          pub fnc read() => int { return 1; }
                        end

                        define module app as
                          pub fnc main() => void {
                            await_required.read();
                            return;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("must be immediately awaited"));
    }

    @Test
    void singletonStateFlowsThroughClassAndStaticDispatchWithoutSelfMailboxReentry() throws Exception {
        String output = eval("""
                define singleton module method_state as
                  let int count = 0;

                  fnc bump() => int {
                    count = count + 1;
                    return count;
                  }

                  define class Helper as
                    pub run() => int {
                      return bump();
                    }

                    pub static fnc run_static() => int {
                      return bump();
                    }
                  end

                  pub fnc next() => int {
                    val helper = new Helper();
                    val int first = helper.run();
                    return Helper.run_static() + first;
                  }
                end

                define module app as
                  pub fnc main() => void {
                    val int result = await method_state.next();
                    stdio.println(result);
                    return;
                  }
                end
                """, "singleton-class-dispatch.ores");

        assertTrue(output.contains("3"), output);
    }

    @Test
    void singletonHelperClassesCannotBypassTheActorProxy() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module private_helpers as
                          define class Helper as
                            pub static fnc value() => int { return 1; }
                          end

                          pub fnc read() => int {
                            return Helper.value();
                          }
                        end

                        define module app as
                          pub fnc main() => void {
                            val int leaked = Helper.value();
                            return;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("actor-private"));
    }

    @Test
    void privateSingletonFunctionsCannotEscapeThroughTheGlobalFunctionIndex() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module private_calls as
                          fnc reset_secret() => int { return 99; }
                          pub fnc read() => int { return 1; }
                        end

                        define module app as
                          pub fnc main() => void {
                            val int leaked = await reset_secret();
                            return;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("actor-private"));
    }

    @Test
    void singletonHelperClassesCannotBeInheritedOutsideTheirActorModule() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module inherited_helpers as
                          define class Helper as
                            pub value() => int { return 1; }
                          end
                          pub fnc read() => int { return 1; }
                        end

                        define class Escape extends Helper as
                        end
                        """)));
        assertTrue(error.getMessage().contains("cannot be inherited"));
    }

    @Test
    void fileInitRunsOnceForEachActorLocalEvaluation() throws Exception {
        String program = """
                let int count = 1;

                init routine() => void {
                  count = count + 8;
                  return;
                }

                pub routine main() => void {
                  stdio.println(count);
                  return;
                }
                """;

        assertTrue(eval(program, "file-init-per-actor.ores").contains("9"));
        assertTrue(eval(program, "file-init-per-actor.ores").contains("9"));
    }

    @Test
    void ordinaryModuleInitRunsBeforeFirstActorLocalUse() throws Exception {
        String output = eval("""
                define module local_state as
                  let int count = 2;

                  init routine() => void {
                    count = count + 5;
                    return;
                  }

                  pub fnc read() => int {
                    return count;
                  }
                end

                define module app as
                  pub routine main() => void {
                    stdio.println(local_state.read());
                    return;
                  }
                end
                """, "module-init-per-actor.ores");

        assertTrue(output.contains("7"), output);
    }

    @Test
    void ordinaryModuleClassMethodsSeeActorLocalInitializedModuleState() throws Exception {
        String output = eval("""
                define module local_model as
                  let int base = 1;

                  init routine() => void {
                    base = base + 40;
                    return;
                  }

                  define class Reader as
                    pub read() => int {
                      return base;
                    }
                  end

                  pub fnc make() => Reader {
                    return new Reader();
                  }
                end

                define module app as
                  pub routine main() => void {
                    val Reader reader = local_model.make();
                    stdio.println(reader.read());
                    return;
                  }
                end
                """, "module-class-init-state.ores");

        assertTrue(output.contains("41"), output);
    }

    @Test
    void singletonInitCannotDependOnFirstCallerAmbientAuthority() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module caller_dependent_init as
                          let String first_context = "";

                          init routine() => void {
                            first_context = process.context_id;
                            return;
                          }

                          pub fnc read() => String {
                            return first_context;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("deterministic and context-free"));
        assertTrue(error.getMessage().contains("first caller"));
    }

    @Test
    void singletonModuleInitRunsExactlyOncePerOsProcessStateCell() throws Exception {
        String program = """
                define singleton module process_init_once_test as
                  let int count = 0;

                  init routine() => void {
                    count = count + 10;
                    return;
                  }

                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app as
                  pub routine main() => void {
                    stdio.println(await process_init_once_test.next());
                    return;
                  }
                end
                """;

        assertTrue(eval(program, "process-init-once.ores").contains("11"));
        assertTrue(eval(program, "process-init-once.ores").contains("12"));
    }

    @Test
    void singletonModuleCanExportOneProcessOwnedClassInstanceThroughProxy() throws Exception {
        String program = """
                define class ProcessValue as
                  val int value = 41;

                  pub read() => int {
                    return self.value;
                  }
                end

                define singleton module singleton_object_export_test as
                  let int calls = 0;
                  pub val ProcessValue value = new ProcessValue();

                  pub fnc note_call() => int {
                    calls = calls + 1;
                    return calls;
                  }
                end

                define module app as
                  pub routine main() => void {
                    val int object_value = await singleton_object_export_test.value.read();
                    val int call_count = await singleton_object_export_test.note_call();
                    stdio.println(object_value + call_count);
                    return;
                  }
                end
                """;

        assertTrue(eval(program, "singleton-object-export.ores").contains("42"));
        assertTrue(eval(program, "singleton-object-export.ores").contains("43"));
    }

    @Test
    void exportedSingletonObjectCannotCaptureActorLocalModuleState() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        let int actor_local_value = 41;

                        define class CapturingFoo as
                          pub read() => int {
                            return actor_local_value;
                          }
                        end

                        define singleton module process_owner as
                          pub val CapturingFoo foo = new CapturingFoo();
                        end
                        """)));

        assertTrue(error.getMessage().contains("cannot capture actor-local module state"));
    }

    @Test
    void exportedSingletonObjectCannotHideActorLocalCaptureInsideLocalStruct() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        let int actor_local_value = 41;

                        define class CapturingNestedFoo as
                          pub read() => int {
                            struct Hidden {
                              pub read_hidden() => int {
                                return actor_local_value;
                              }
                            }
                            return 0;
                          }
                        end

                        define singleton module nested_process_owner as
                          pub val CapturingNestedFoo foo = new CapturingNestedFoo();
                        end
                        """)));

        assertTrue(error.getMessage().contains("cannot capture actor-local module state"));
    }

    @Test
    void singletonObjectProxyCallsMustBeAwaitedAndFieldsStayPrivate() {
        IllegalArgumentException unawaited = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class ProcessBox as
                          let int value = 1;
                          pub read() => int { return self.value; }
                        end

                        define singleton module singleton_proxy_await_test as
                          pub val ProcessBox box = new ProcessBox();
                        end

                        define module app as
                          pub routine main() => void {
                            singleton_proxy_await_test.box.read();
                            return;
                          }
                        end
                        """)));
        assertTrue(unawaited.getMessage().contains("immediately awaited"));

        IllegalArgumentException fieldRead = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class ProcessBox2 as
                          let int value = 1;
                          pub read() => int { return self.value; }
                        end

                        define singleton module singleton_proxy_field_test as
                          pub val ProcessBox2 box = new ProcessBox2();
                        end

                        define module app as
                          pub routine main() => void {
                            val int leaked = singleton_proxy_field_test.box.value;
                            return;
                          }
                        end
                        """)));
        assertTrue(fieldRead.getMessage().contains("fields are actor-private"));
    }

    @Test
    void registryRejectsCrossSingletonWaitCyclesBeforeDeadlock() {
        String aKey = "cycle-a:" + UUID.randomUUID();
        String bKey = "cycle-b:" + UUID.randomUUID();
        AtomicReference<ProcessSingletonRegistry.Handle<Object>> aRef = new AtomicReference<>();
        AtomicReference<ProcessSingletonRegistry.Handle<Object>> bRef = new AtomicReference<>();

        aRef.set(ProcessSingletonRegistry.getOrCreate(aKey, Object::new));
        bRef.set(ProcessSingletonRegistry.getOrCreate(bKey, Object::new));

        CompletableFuture<Object> call = aRef.get().call(
                List.of(), 8, Duration.ofSeconds(2),
                (aState, ignored) -> bRef.get().call(
                        List.of(), 8, Duration.ofSeconds(2),
                        (bState, ignoredAgain) -> aRef.get().call(
                                List.of(), 8, Duration.ofSeconds(2),
                                (nestedA, finalArgs) -> "unreachable")
                                .toCompletableFuture().join())
                        .toCompletableFuture().join())
                .toCompletableFuture();

        RuntimeException failure = assertThrows(RuntimeException.class, call::join);
        assertTrue(causeChainContains(failure, "wait cycle"), String.valueOf(failure));
    }

    @Test
    void registryEnforcesCallerBackpressureAndRecoversAfterTimeout() throws Exception {
        String key = "backpressure:" + UUID.randomUUID();
        ProcessSingletonRegistry.Handle<AtomicInteger> handle =
                ProcessSingletonRegistry.getOrCreate(key, AtomicInteger::new);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Object> active = handle.call(
                List.of(), 1, Duration.ofSeconds(2),
                (state, ignored) -> {
                    entered.countDown();
                    release.await(1, TimeUnit.SECONDS);
                    return state.incrementAndGet();
                }).toCompletableFuture();

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        CompletableFuture<Object> queued = handle.call(
                List.of(), 1, Duration.ofSeconds(2),
                (state, ignored) -> state.incrementAndGet()).toCompletableFuture();
        CompletableFuture<Object> rejected = handle.call(
                List.of(), 1, Duration.ofSeconds(2),
                (state, ignored) -> state.incrementAndGet()).toCompletableFuture();

        assertTrue(rejected.isCompletedExceptionally());
        release.countDown();
        assertEquals(1L, ((Number) active.join()).longValue());
        assertEquals(2L, ((Number) queued.join()).longValue());

        CompletableFuture<Object> timed = handle.call(
                List.of(), 8, Duration.ofMillis(25),
                (state, ignored) -> {
                    while (true) ProcessSingletonRegistry.checkExecutionBudget();
                }).toCompletableFuture();
        assertThrows(RuntimeException.class, timed::join);

        Object afterTimeout = handle.call(
                List.of(), 8, Duration.ofSeconds(1),
                (state, ignored) -> state.incrementAndGet()).toCompletableFuture().join();
        assertEquals(3L, ((Number) afterTimeout).longValue());
    }

    @Test
    void isolateLocalStaticsCannotMasqueradeAsOsProcessSingletons() {
        assertDoesNotThrow(() -> ProcessSingletonRegistry.requireBackendFor(false));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ProcessSingletonRegistry.requireBackendFor(true));
        assertTrue(failure.getMessage().contains("trusted host coordinator"));
        assertTrue(failure.getMessage().contains("not process-global"));
    }

    @Test
    void strictFaasDoesNotGrantAmbientProcessSingletonAccess() {
        String source = """
                define singleton module forbidden_global as
                  let int value = 1;
                  pub fnc read() => int { return value; }
                end
                """;

        SecurityException denied = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(
                        TypeChecker.check(Parser.parse(source)),
                        IsolatePolicy.strictFaas()));
        assertTrue(denied.getMessage().contains("PROCESS_SINGLETON"));
    }

    @Test
    void singletonCodeChangesRequireHotCodeLoadAuthority() throws Exception {
        IsolatePolicy noHotLoad = new IsolatePolicy(
                Set.of(IsolatePolicy.Capability.STDOUT, IsolatePolicy.Capability.PROCESS_SINGLETON),
                64L * 1024 * 1024,
                64,
                Duration.ofSeconds(2));

        String firstProgram = """
                define singleton module no_hot_reload_guard as
                  let int count = 0;
                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app as
                  pub fnc main() => void {
                    val int n = await no_hot_reload_guard.next();
                    stdio.println(n);
                    return;
                  }
                end
                """;

        String changedProgram = """
                define singleton module no_hot_reload_guard as
                  let int count = 0;
                  pub fnc next() => int {
                    count = count + 10;
                    return count;
                  }
                end

                define module app as
                  pub fnc main() => void {
                    val int n = await no_hot_reload_guard.next();
                    stdio.println(n);
                    return;
                  }
                end
                """;

        assertTrue(evalWithPolicy(firstProgram, "no-hot-reload-guard.ores", noHotLoad).contains("1"));
        RuntimeException denied = assertThrows(RuntimeException.class,
                () -> evalWithPolicy(changedProgram, "no-hot-reload-guard.ores", noHotLoad));
        assertTrue(causeChainContains(denied, "HOT_CODE_LOAD"), String.valueOf(denied));
    }

    @Test
    void failedInitializationCanBeRetriedBeforeStateEverExists() {
        String key = "init-retry:" + UUID.randomUUID();

        ProcessSingletonRegistry.Handle<AtomicInteger> failed =
                ProcessSingletonRegistry.getOrCreate(key, () -> {
                    throw new IllegalStateException("boom");
                });
        assertThrows(CompletionException.class,
                () -> failed.call(List.of(), (state, ignored) -> 0).toCompletableFuture().join());

        ProcessSingletonRegistry.Handle<AtomicInteger> recovered =
                ProcessSingletonRegistry.getOrCreate(key, AtomicInteger::new);
        assertNotEquals(failed.instanceId(), recovered.instanceId());

        Object result = recovered.call(List.of(), (state, ignored) -> state.incrementAndGet())
                .toCompletableFuture().join();
        assertEquals(1L, ((Number) result).longValue());
    }

    @Test
    void registryCreatesOneActorAndSerializesConcurrentCalls() {
        String key = "test:" + UUID.randomUUID();
        AtomicInteger initializations = new AtomicInteger();

        ProcessSingletonRegistry.Handle<AtomicInteger> first =
                ProcessSingletonRegistry.getOrCreate(key, () -> {
                    initializations.incrementAndGet();
                    return new AtomicInteger();
                });
        ProcessSingletonRegistry.Handle<AtomicInteger> second =
                ProcessSingletonRegistry.getOrCreate(key, () -> {
                    fail("second factory must not initialize the same process singleton");
                    return new AtomicInteger();
                });

        assertEquals(first.instanceId(), second.instanceId());

        List<CompletableFuture<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            calls.add(first.call(List.of(), (state, ignored) -> state.incrementAndGet())
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, initializations.get());
        assertEquals(64L, ((Number) calls.getLast().join()).longValue());
    }

    private static boolean causeChainContains(Throwable failure, String text) {
        Throwable current = failure;
        while (current != null) {
            if (String.valueOf(current.getMessage()).contains(text)) return true;
            current = current.getCause();
        }
        return false;
    }

    private String evalWithPolicy(String program, String name, IsolatePolicy policy) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = policy.restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private String eval(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
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
    @Test
    void processEffectValidationInspectsCallableLocalStructBodies() {
        IllegalArgumentException hiddenMethod = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module hidden_local_struct_effect as
                          pub fnc value() => int {
                            struct Local {
                              pub context() => String {
                                return process.context_id;
                              }
                            }
                            return 1;
                          }
                        end
                        """)));

        assertTrue(hiddenMethod.getMessage().contains("ambient caller capability 'process'"));
        assertTrue(hiddenMethod.getMessage().contains("process-singleton code"));
    }

    @Test
    void processEffectValidationInspectsStructLiteralFieldValues() {
        IllegalArgumentException hiddenLiteral = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module hidden_struct_literal_effect as
                          pub fnc value() => String {
                            struct Local {
                              value: String;
                            }
                            val local = Local { value = process.context_id };
                            return local.value;
                          }
                        end
                        """)));

        assertTrue(hiddenLiteral.getMessage().contains("ambient caller capability 'process'"));
        assertTrue(hiddenLiteral.getMessage().contains("process-singleton code"));
    }

}
