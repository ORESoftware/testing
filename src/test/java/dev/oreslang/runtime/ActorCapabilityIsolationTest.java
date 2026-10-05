package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCapabilityIsolationTest {

    @Test
    void privateActorStaticallyDeniesReadonlySharingEvenUnderDeveloperPolicy() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                isoactor PrivateWorker {
                  pub receive(int message): void {
                    val shared = process.share_readonly(arr[1, 2, 3]);
                    stdio.println(shared);
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("ACTOR_SHARE_READONLY"));
    }

    @Test
    void privateActorCannotHideSharedMutexBehindTypeAlias() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                type SharedInt = SharedMutex<int>;

                isoactor PrivateWorker {
                  let SharedInt hidden;

                  pub receive(int message): void { return; }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotHideSharedMutexInsideOrdinaryStoredClass() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class SharedBox as
                  let SharedMutex<int> value;
                end

                isoactor PrivateWorker {
                  let SharedBox hidden;

                  pub receive(int message): void { return; }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotLaunderSharedMemoryThroughOrdinaryHelperFunction() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc build_shared() => void {
                          val shared = SharedMutex.new(1);
                          stdio.println(shared);
                          return;
                        }

                        isoactor PrivateWorker {
                          pub receive(message: int): void {
                            build_shared();
                            return;
                          }
                        }
                        """)));

        assertTrue(
                error.getMessage().contains("external shared mutable state")
                        || error.getMessage().contains("SharedMutex"),
                error.getMessage());
    }

    @Test
    void privateActorCannotLaunderSharedMemoryThroughStaticClassHelper() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Helpers as
                          pub static fnc build_shared() => void {
                            val shared = SharedMutex.new(1);
                            stdio.println(shared);
                            return;
                          }
                        end

                        isoactor PrivateWorker {
                          pub receive(message: int): void {
                            Helpers.build_shared();
                            return;
                          }
                        }
                        """)));

        assertTrue(
                error.getMessage().contains("external shared mutable state")
                        || error.getMessage().contains("SharedMutex"),
                error.getMessage());
    }

    @Test
    void privateActorCannotCarryObjectWhoseInstanceMethodUsesSharedAuthority() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class Helper as
                  pub use_shared() => void {
                    val shared = process.share_readonly(arr[1, 2, 3]);
                    stdio.println(shared);
                    return;
                  }
                end

                isoactor PrivateWorker {
                  let Helper helper;

                  pub receive(int message): void { return; }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("ACTOR_SHARE_READONLY"));
    }

    @Test
    void sharedActorMayUseTransitiveSharedStateWhenParentPolicyAllowsIt() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                type SharedInt = SharedMutex<int>;

                define class SharedBox as
                  let SharedInt value;
                end

                shared actor SharedWorker {
                  let SharedBox state;

                  pub receive(int message): void { return; }
                }
                """));

        assertDoesNotThrow(() ->
                CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void transitiveCapabilityScanHandlesSelfReferentialStoredTypes() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class Node as
                  let Node next;

                  pub identity(Node other) => Node {
                    return other;
                  }
                end

                isoactor PrivateWorker {
                  let Node root;

                  pub receive(int message): void { return; }
                }
                """));

        assertDoesNotThrow(() ->
                CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void actorLocalRuntimePolicyCannotBeBypassedByParentContextCapability() throws Exception {
        IsolatePolicy developer = IsolatePolicy.developer();

        try (ActorRuntime runtime = new ActorRuntime(developer)) {
            var privateSharedMemory = runtime.<String>spawnPrivate(
                    factoryContext -> (message, context) ->
                            OresContext.requireEffectiveCapability(
                                    IsolatePolicy.developer(),
                                    IsolatePolicy.Capability.SHARED_MEMORY,
                                    "indirect-helper-shared-memory"));

            var privateReadonlyShare = runtime.<String>spawnPrivate(
                    factoryContext -> (message, context) ->
                            OresContext.requireEffectiveCapability(
                                    IsolatePolicy.developer(),
                                    IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                                    "indirect-helper-readonly-share"));

            var shared = runtime.<String>spawnShared(factoryContext -> (message, context) -> {
                OresContext.requireEffectiveCapability(
                        IsolatePolicy.developer(),
                        IsolatePolicy.Capability.SHARED_MEMORY,
                        "shared-actor-shared-memory");
                OresContext.requireEffectiveCapability(
                        IsolatePolicy.developer(),
                        IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                        "shared-actor-readonly-share");
                context.self().stop();
            });

            privateSharedMemory.send("check");
            privateReadonlyShare.send("check");
            shared.send("check");

            assertTrue(privateSharedMemory.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(privateReadonlyShare.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(shared.awaitTermination(2, TimeUnit.SECONDS));

            assertInstanceOf(SecurityException.class, privateSharedMemory.failure().orElseThrow());
            assertInstanceOf(SecurityException.class, privateReadonlyShare.failure().orElseThrow());
            assertTrue(shared.failure().isEmpty());
        }
    }
    @Test
    void privateActorCannotLaunderSharedMemoryThroughFunctionValue() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc build_shared() => void {
                  val shared = SharedMutex.new(1);
                  stdio.println(shared);
                  return;
                }

                isoactor PrivateWorker {
                  pub receive(int message): void {
                    val callback = build_shared;
                    callback();
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotLaunderReadonlyShareThroughQualifiedFunctionValue() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module helpers
                  pub fnc expose() => void {
                    val shared = process.share_readonly(arr[1, 2, 3]);
                    stdio.println(shared);
                    return;
                  }
                end

                isoactor PrivateWorker {
                  pub receive(int message): void {
                    val callback = helpers.expose;
                    callback();
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("ACTOR_SHARE_READONLY"));
    }


    @Test
    void privateActorCannotLaunderSharedMemoryThroughStaticMethodValue() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class Helpers as
                  pub static fnc build_shared() => void {
                    val shared = SharedMutex.new(1);
                    stdio.println(shared);
                    return;
                  }
                end

                isoactor PrivateWorker {
                  pub receive(int message): void {
                    val callback = Helpers.build_shared;
                    callback();
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }


    @Test
    void invalidOreslangPrivateActorSharingFixtureIsRejected() throws Exception {
        String source = Files.readString(
                Path.of("examples/private-actor-sharing-invalid.ores"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));

        assertTrue(
                error.getMessage().contains("external shared mutable state")
                        || error.getMessage().contains("SharedMutex")
                        || error.getMessage().contains("RwLock"),
                error.getMessage());
    }

}
