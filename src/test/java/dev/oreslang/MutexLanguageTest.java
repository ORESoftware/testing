package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class MutexLanguageTest {
    @Test
    void nestedSharedMutexTypesAreRejectedConservatively() {
        var program = Parser.parse("""
                define module app
                  fnc bad(SharedMutex<SharedMutex<int>> value): void {
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("shared-safe"));
    }

    @Test
    void sharedActorFunctionsRejectBlockingSharedMutexLock() {
        var program = Parser.parse("""
                pub shared actor fnc worker(SharedMutex<int> mutex): void {
                  val guard = mutex.lock();
                  guard.release();
                  return;
                }
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains(
                "actor code cannot use blocking SharedMutex.lock"));
    }

    @Test
    void sharedActorMethodsRejectBlockingSharedMutexWithLock() {
        var program = Parser.parse("""
                shared actor Worker {
                  pub fnc run(SharedMutex<int> mutex): void {
                    mutex.with_lock(|value| -> {
                      return;
                    });
                    return;
                  }
                }
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains(
                "actor code cannot use blocking SharedMutex.with_lock"));
    }

    @Test
    void actorCodeMayUseNonblockingSharedMutexOperations() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub shared actor fnc try_worker(SharedMutex<int> mutex): void {
                  val maybe_guard = mutex.try_lock();
                  stdio.println(mutex.is_poisoned());
                  return;
                }

                pub shared actor fnc async_worker(SharedMutex<int> mutex): void {
                  val future_guard = mutex.lock_async();
                  return;
                }
                """)));
    }

    @Test
    void sharedMutexAcceptsFullySharedSafeUnionTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc good(SharedMutex<int | String> value): void {
                    return;
                  }
                end
                """)));
    }

    @Test
    void sharedMutexRejectsUnionWhenAnyAlternativeIsActorLocal() {
        var program = Parser.parse("""
                define module app
                  fnc bad(SharedMutex<int | Mutex<int>> value): void {
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("shared-safe"));
    }

    @Test
    void sharedMutexSubstitutesGenericsInsideUnionFields() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model
                  define class Box<T> as
                    pub val T | int value;
                  end
                end

                define module app
                  fnc good(Box<String> box): void {
                    val shared = SharedMutex.new(box);
                    stdio.println(shared.is_poisoned());
                    return;
                  }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model
                  define class Box<T> as
                    pub val T | int value;
                  end
                end

                define module app
                  fnc collapsed(Box<int> box): void {
                    val shared = SharedMutex.new(box);
                    stdio.println(shared.is_poisoned());
                    return;
                  }
                end
                """)));

        var unsafe = Parser.parse("""
                define module model
                  define class Box<T> as
                    pub val T | int value;
                  end
                end

                define module app
                  fnc bad(Box<Mutex<int>> box): void {
                    val shared = SharedMutex.new(box);
                    stdio.println(shared.is_poisoned());
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(unsafe));
        assertTrue(error.getMessage().contains("shared-safe"));
    }

    @Test
    void declaredSharedMutexTypesMustAlsoBeSharedSafe() {
        var unsafe = Parser.parse("""
                define module app
                  fnc bad(SharedMutex<Mutex<int>> value): void {
                    return;
                  }
                end
                """);
        IllegalArgumentException unsafeError = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(unsafe));
        assertTrue(unsafeError.getMessage().contains("concrete shared-safe type"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc good(SharedMutex<int> value): void {
                    return;
                  }
                end
                """)));
    }

    @Test
    void unconstrainedGenericSharedMutexTypesAreRejectedConservatively() {
        var program = Parser.parse("""
                define module app
                  fnc bad<T>(SharedMutex<T> value): void {
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("concrete shared-safe type"));
    }

    @Test
    void sharedMutexRejectsActorLocalMutexValues() {
        var program = Parser.parse("""
                define module app
                  fnc bad(): void {
                    val local = Mutex.new(arr[1, 2, 3]);
                    val shared = SharedMutex.new(local);
                    stdio.println(shared.is_poisoned());
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("shared-safe owned data"));
    }

    @Test
    void sharedMutexRejectsClosuresAndPendingFutures() {
        var closureProgram = Parser.parse("""
                define module app
                  fnc bad(): void {
                    val callback = || -> { return; };
                    val shared = SharedMutex.new(callback);
                    stdio.println(shared.is_poisoned());
                    return;
                  }
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(closureProgram));

        var futureProgram = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  async fnc bad(): void {
                    val mutex = SharedMutex.new(new Counter());
                    val pending = mutex.lock_async();
                    val nested = SharedMutex.new(pending);
                    stdio.println(nested.is_poisoned());
                    return;
                  }
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(futureProgram));
    }

    @Test
    void sharedMutexPreservesParentGenericBindingsDuringSafetyCheck() {
        var program = Parser.parse("""
                define module model
                  define class Parent<T> as
                    pub val T value;
                  end

                  define class Child<T> extends Parent<Mutex<T>> as
                  end
                end

                define module app
                  fnc bad(Child<int> child): void {
                    val shared = SharedMutex.new(child);
                    stdio.println(shared.is_poisoned());
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("shared-safe owned data"));
    }

    @Test
    void sharedMutexRecursivelyChecksClassFields() {
        var program = Parser.parse("""
                define module model
                  define class UnsafeBox as
                    pub val Fnc<void> callback = || -> { return; };
                  end
                end

                define module app
                  fnc bad(): void {
                    val shared = SharedMutex.new(new UnsafeBox());
                    stdio.println(shared.is_poisoned());
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("shared-safe owned data"));
    }

    @Test
    void sharedMutexRequiresExplicitSharedMemoryCapability() {
        var program = Parser.parse("""
                define module app
                  fnc main(): void {
                    val shared = SharedMutex.new(arr[1, 2, 3]);
                    stdio.println(shared.is_poisoned());
                    return;
                  }
                end
                """);

        assertThrows(SecurityException.class, () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertDoesNotThrow(() -> CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void sharedMutexAcceptsOrdinaryOwnedClassState() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc good(): void {
                    val shared = SharedMutex.new(new Counter());
                    stdio.println(shared.is_poisoned());
                    return;
                  }
                end
                """)));
    }

    @Test
    void sharedMutexRejectsActorLocalStateGraph() {
        var program = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc bad(): void {
                    val local = Mutex.new(new Counter());
                    val shared = SharedMutex.new(local);
                    stdio.println(shared.is_poisoned());
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("SharedMutex<T> requires shared-safe owned data"));
    }

    @Test
    void mutexGuardTransparentlyProtectsClassStateAndReleasesLexically() throws Exception {
        String program = """
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  pub fnc main(): void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    guard.value = guard.value + 2;
                    stdio.println(guard.value);
                    guard.release();

                    val again = mutex.lock();
                    stdio.println(again.value);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "mutex.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.lines().filter("2"::equals).count() >= 2);
    }

    @Test
    void awaitWhileHoldingGuardIsRejectedStatically() {
        var program = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  async fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    await mutex.lock_async();
                    guard.value = 1;
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("cannot await while holding a MutexGuard"));
    }

    @Test
    void withLockProvidesMutableProtectedValue() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc good(): void {
                    val mutex = Mutex.new(new Counter());
                    mutex.with_lock(|counter| -> {
                      counter.value = counter.value + 1;
                      return;
                    });
                    return;
                  }
                end
                """)));
    }

    @Test
    void awaitInsideWithLockCriticalSectionIsRejected() {
        var program = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  async fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    mutex.with_lock(|counter| -> {
                      await mutex.lock_async();
                      return;
                    });
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("cannot await while holding a MutexGuard"));
    }

    @Test
    void guardBearingResultsMustBeBoundAndCannotBeOverwritten() {
        var discarded = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end
                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    mutex.lock();
                    return;
                  }
                end
                """);
        IllegalArgumentException discardedError = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(discarded));
        assertTrue(discardedError.getMessage().contains("cannot be discarded"));

        var mutableBinding = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end
                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    let guard = mutex.lock();
                    guard.release();
                    return;
                  }
                end
                """);
        IllegalArgumentException mutableError = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(mutableBinding));
        assertTrue(mutableError.getMessage().contains("cannot use let"));
    }

    @Test
    void guardBearingValuesCannotCrossArbitraryCallsOrNestedMutexes() {
        var callProgram = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end
                define module app
                  fnc consume<T>(T value): void { return; }
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    consume(guard);
                    return;
                  }
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(callProgram));

        var nestedProgram = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end
                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    val nested = Mutex.new(guard);
                    stdio.println(nested.is_poisoned());
                    return;
                  }
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(nestedProgram));
    }

    @Test
    void guardedMoveOnlyFieldsAndMethodValuesCannotEscape() {
        var fieldProgram = Parser.parse("""
                define module model
                  define class Child as
                    pub let int value = 1;
                  end
                  define class Holder as
                    pub val Child child = new Child();
                  end
                end
                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Holder());
                    val guard = mutex.lock();
                    val escaped = guard.child;
                    stdio.println(escaped);
                    return;
                  }
                end
                """);
        IllegalArgumentException fieldError = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(fieldProgram));
        assertTrue(fieldError.getMessage().contains("cannot extract move-only field"));

        var methodProgram = Parser.parse("""
                define module model
                  define class Counter as
                    pub read(): int { return 1; }
                  end
                end
                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    val callback = guard.read;
                    stdio.println(callback);
                    return;
                  }
                end
                """);
        IllegalArgumentException methodError = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(methodProgram));
        assertTrue(methodError.getMessage().contains("direct-call-only"));
    }

    @Test
    void directGuardedCopyReturningMethodCallIsAllowed() throws Exception {
        String program = """
                define module model
                  define class Counter as
                    pub read(): int { return 7; }
                  end
                end
                define module app
                  pub fnc main(): void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    val value = guard.read();
                    stdio.println(value);
                    guard.release();
                    return;
                  }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "guard-method.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).lines().anyMatch("7"::equals));
    }

    @Test
    void withLockProtectedBorrowCannotMoveOrReturnState() {
        var moved = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end
                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    mutex.with_lock(|counter| -> {
                      val escaped = counter;
                      stdio.println(escaped);
                      return;
                    });
                    return;
                  }
                end
                """);
        IllegalArgumentException movedError = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(moved));
        assertTrue(movedError.getMessage().contains("protected with_lock/recover state cannot be moved"));

        var returned = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end
                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    mutex.with_lock(|counter| -> {
                      return counter.value;
                    });
                    return;
                  }
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(returned));
    }

    @Test
    void withLockRequiresInlineLambda() {
        var program = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end
                define module app
                  fnc mutate(Counter mut counter): void {
                    counter.value = counter.value + 1;
                    return;
                  }
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    mutex.with_lock(mutate);
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("requires an inline one-argument lambda"));
    }

    @Test
    void guardCannotBeStoredInAggregate() {
        var program = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    val escaped = arr[guard];
                    stdio.println(escaped);
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("MutexGuard cannot be stored"));
    }

    @Test
    void guardCannotEscapeThroughClosureCapture() {
        var program = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    val callback = || -> {
                      stdio.println(guard.value);
                      return;
                    };
                    callback();
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("closure cannot capture guard-bearing value"));
    }

    @Test
    void sharedMutexCapabilityAdmissionCoversSignaturesAndAliases() {
        var signature = Parser.parse("""
                define module app
                  fnc pass(SharedMutex<int> value): void {
                    return;
                  }
                end
                """);
        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(signature, IsolatePolicy.strictFaas()));
        assertDoesNotThrow(
                () -> CapabilityChecker.check(signature, IsolatePolicy.developer()));

        var alias = Parser.parse("""
                define module app
                  type SharedCounter = SharedMutex<int>;
                  fnc main(): void {
                    return;
                  }
                end
                """);
        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(alias, IsolatePolicy.strictFaas()));
    }

    @Test
    void bareSharedMutexNamespaceRequiresCapabilityBeforeRebinding() {
        var program = Parser.parse("""
                define module app
                  fnc main(): void {
                    val factory = SharedMutex;
                    return;
                  }
                end
                """);

        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertDoesNotThrow(
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
    }


    @Test
    void guardBearingValuesCannotBeHiddenInConstructedObjects() {
        var direct = Parser.parse("""
                define module model
                  define class Box<T> as
                    pub let T value;
                  end

                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    val hidden = new Box<>(guard);
                    stdio.println(hidden);
                    return;
                  }
                end
                """);

        IllegalArgumentException directError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(direct));
        assertTrue(directError.getMessage().contains(
                "MutexGuard cannot be stored in a constructed object"));

        var pending = Parser.parse("""
                define module model
                  define class Box<T> as
                    pub let T value;
                  end

                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    val futureGuard = mutex.lock_async();
                    val hidden = new Box<>(futureGuard);
                    stdio.println(hidden);
                    return;
                  }
                end
                """);

        IllegalArgumentException pendingError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(pending));
        assertTrue(pendingError.getMessage().contains(
                "MutexGuard cannot be stored in a constructed object"));

        var borrowed = Parser.parse("""
                define module model
                  define class Box<T> as
                    pub let T value;
                  end

                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc bad(): void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    val hidden = new Box<>(&guard);
                    stdio.println(hidden);
                    return;
                  }
                end
                """);

        IllegalArgumentException borrowedError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(borrowed));
        assertTrue(borrowedError.getMessage().contains(
                "MutexGuard cannot be stored in a constructed object"));
    }


    @Test
    void sharedSafeUnionPayloadsRequireEveryArmToBeSafe() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc good(SharedMutex<int | string> value): void {
                    return;
                  }
                end
                """)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad(SharedMutex<int | Mutex<int>> value): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("concrete shared-safe type"));
    }


    @Test
    void mutexPayloadsMustBeOwnedRatherThanBorrowed() {
        IllegalArgumentException factoryError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad(): void {
                            val data = arr[1, 2, 3];
                            val mutex = Mutex.new(&data);
                            stdio.println(mutex);
                            return;
                          }
                        end
                        """)));
        assertTrue(factoryError.getMessage().contains("requires owned data"));

        IllegalArgumentException declaredError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model
                          define class Counter as
                            pub let int value = 0;
                          end
                        end

                        define module app
                          fnc bad(Mutex<&Counter> value): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(declaredError.getMessage().contains("owned value type"));
    }


    @Test
    void conditionalExpressionsCannotEraseGuardLinearity() {
        var awaitProgram = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  async fnc bad(bool choose): void {
                    val mutex = Mutex.new(new Counter());
                    val maybe_guard = choose ? 1 : mutex.lock();
                    await mutex.lock_async();
                    stdio.println(maybe_guard);
                    return;
                  }
                end
                """);

        IllegalArgumentException awaitError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(awaitProgram));
        assertTrue(awaitError.getMessage().contains(
                "cannot await while holding a MutexGuard"));

        var returnProgram = Parser.parse("""
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc bad(bool choose): int | Counter {
                    val mutex = Mutex.new(new Counter());
                    return choose ? 1 : mutex.lock();
                  }
                end
                """);

        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(returnProgram));
    }

}
