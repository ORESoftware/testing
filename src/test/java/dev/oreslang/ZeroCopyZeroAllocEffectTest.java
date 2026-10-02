package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ZeroCopyZeroAllocEffectTest {

    @Test
    void noAllocRejectsDirectConstruction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        @NoAlloc
                        fnc hot() => Buffer {
                          return new Buffer(1);
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("new Buffer"));
    }

    @Test
    void noCopyRejectsCopyIntrinsic() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        struct Point {
                          x: int;
                        }

                        @NoCopy
                        fnc duplicate(Point p) => Point {
                          return copy(p);
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoCopy contract violated"));
        assertTrue(error.getMessage().contains("copy(...)"));
    }

    @Test
    void noAllocPropagatesTransitivelyAcrossKnownFunctions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        fnc allocate() => Buffer {
                          return new Buffer(1);
                        }

                        @NoAlloc
                        fnc hot() => Buffer {
                          return allocate();
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("call to function '__root__.allocate'"));
        assertTrue(error.getMessage().contains("new Buffer"));
    }

    @Test
    void noCopyPropagatesTransitivelyAcrossKnownFunctions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        struct Point {
                          x: int;
                        }

                        fnc duplicate(Point p) => Point {
                          return copy(p);
                        }

                        @NoCopy
                        fnc hot(Point p) => Point {
                          return duplicate(p);
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoCopy contract violated"));
        assertTrue(error.getMessage().contains("call to function '__root__.duplicate'"));
        assertTrue(error.getMessage().contains("copy(...)"));
    }

    @Test
    void recursiveCallGraphDiagnosticsReachActualEffectSource() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        fnc make() => Buffer {
                          return new Buffer(1);
                        }

                        fnc a(bool stop) => Buffer {
                          return b(stop);
                        }

                        fnc b(bool stop) => Buffer {
                          if stop; do
                            return a(false);
                          else
                            return make();
                          fi
                        }

                        @NoAlloc
                        fnc hot() => Buffer {
                          return a(true);
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("new Buffer"));
        assertTrue(error.getMessage().contains("make"));
    }

    @Test
    void borrowAndTakeAreZeroCopyZeroAllocOwnershipOperations() {
        assertDoesNotThrow(() -> check("""
                define class Buffer as
                  pub let int value = 0;
                end

                @NoAlloc
                @NoCopy
                fnc inspect(&Buffer b) => int {
                  return b.value;
                }

                @NoAlloc
                @NoCopy
                fnc pipeline(Buffer b) => int {
                  return inspect(borrow(b));
                }

                @NoAlloc
                @NoCopy
                fnc consume(Buffer b) => int {
                  let Buffer moved = take(b);
                  return moved.value;
                }
                """));
    }

    @Test
    void primitiveCopyIsNoAllocButStillCopying() {
        assertDoesNotThrow(() -> check("""
                @NoAlloc
                fnc duplicate(int value) => int {
                  return copy(value);
                }
                """));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        @NoCopy
                        fnc duplicate(int value) => int {
                          return copy(value);
                        }
                        """));
        assertTrue(error.getMessage().contains("@NoCopy contract violated"));
    }

    @Test
    void primitiveOnlyStructCopyIsNoAllocButStillCopying() {
        assertDoesNotThrow(() -> check("""
                struct Point {
                  x: int;
                  y: int;
                }

                @NoAlloc
                fnc duplicate(Point value) => Point {
                  return copy(value);
                }
                """));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        struct Point {
                          x: int;
                          y: int;
                        }

                        @NoCopy
                        fnc duplicate(Point value) => Point {
                          return copy(value);
                        }
                        """));
        assertTrue(error.getMessage().contains("@NoCopy contract violated"));
    }

    @Test
    void structCopyWithDynamicStorageIsAllocating() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        struct Bucket {
                          values: Array<int>;
                        }

                        @NoAlloc
                        fnc duplicate(Bucket value) => Bucket {
                          return copy(value);
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("copy(...) materializes"));
    }

    @Test
    void shareOfCompositeIsBothCopyingAndAllocating() {
        IllegalArgumentException allocError = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        define class Buffer as
                          pub let int value = 0;

                          pub copy() => Buffer {
                            return new Buffer(self.value);
                          }
                        end

                        @NoAlloc
                        fnc snapshot(Buffer b) => &Buffer {
                          return share(b);
                        }
                        """));
        assertTrue(allocError.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(allocError.getMessage().contains("share(...)"));

        IllegalArgumentException copyError = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        define class Buffer as
                          pub let int value = 0;

                          pub copy() => Buffer {
                            return new Buffer(self.value);
                          }
                        end

                        @NoCopy
                        fnc snapshot(Buffer b) => &Buffer {
                          return share(b);
                        }
                        """));
        assertTrue(copyError.getMessage().contains("@NoCopy contract violated"));
        assertTrue(copyError.getMessage().contains("share(...)"));
    }

    @Test
    void optionConstructionIsAZeroAllocTaggedValue() {
        assertDoesNotThrow(() -> check("""
                @NoAlloc
                @NoCopy
                fnc maybe(int value) => Option<int> {
                  return Some(value);
                }
                """));
    }

    @Test
    void noAllocRejectsOpaqueCallsWhoseEffectsCannotBeProven() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        @NoAlloc
                        fnc hot() => void {
                          stdio.println(1);
                          return;
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("unverified call 'stdio.println(...)'"));
    }

    @Test
    void localNumericBindingsDoNotLookLikeStringAllocation() {
        assertDoesNotThrow(() -> check("""
                @NoAlloc
                @NoCopy
                fnc sum(int a, int b) => int {
                  val int left = a;
                  val int right = b;
                  return left + right + 1;
                }
                """));
    }

    @Test
    void stringConcatenationIsAllocating() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        @NoAlloc
                        fnc join(String left, String right) => String {
                          val String prefix = left;
                          return prefix + right;
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("string concatenation"));
    }

    @Test
    void noAllocRejectsHeapLikeCollectionLiteralsButNotFixedTuples() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        @NoAlloc
                        fnc build() => Array<int> {
                          return arr[1, 2, 3];
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("array/list literal"));

        assertDoesNotThrow(() -> check("""
                @NoAlloc
                @NoCopy
                fnc first() => int {
                  val pair = (1, 2);
                  return pair[0];
                }
                """));
    }

    @Test
    void asyncCallableHasImplicitAllocationEffect() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        @NoAlloc
                        async fnc later() => int {
                          return 1;
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("async callable"));
    }

    @Test
    void privateAndStructMethodCallsCanPropagateExactEffects() {
        assertDoesNotThrow(() -> check("""
                define class Calculator as
                  private helper(int value) => int {
                    return value + 1;
                  }

                  @NoAlloc
                  @NoCopy
                  pub run(int value) => int {
                    return self.helper(value);
                  }
                end

                struct Pair {
                  x: int;
                  y: int;

                  @NoAlloc
                  @NoCopy
                  pub sum() => int {
                    return self.x + self.y;
                  }
                }
                """));
    }

    @Test
    void unconstrainedVirtualClassMethodCannotBeAssumedPure() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        define class Base as
                          pub value() => int {
                            return 1;
                          }
                        end

                        @NoAlloc
                        fnc read(Base value) => int {
                          return value.value();
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("virtual/dynamic"));
        assertTrue(error.getMessage().contains("no @NoAlloc contract"));
    }

    @Test
    void virtualClassMethodContractsAreComposable() {
        assertDoesNotThrow(() -> check("""
                define class Base as
                  @NoAlloc
                  @NoCopy
                  pub value() => int {
                    return 1;
                  }
                end

                @NoAlloc
                @NoCopy
                fnc read(Base value) => int {
                  return value.value();
                }
                """));
    }

    @Test
    void overridesMustHonorInheritedEffectContracts() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        define class Box as
                          pub val int value = 1;
                        end

                        define class Base as
                          @NoAlloc
                          pub value() => int {
                            return 1;
                          }
                        end

                        define class Child extends Base as
                          pub value() => int {
                            val Box box = new Box();
                            return box.value;
                          }
                        end
                        """));

        assertTrue(error.getMessage().contains("inherited @NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("Child.value"));
        assertTrue(error.getMessage().contains("new Box"));
    }

    @Test
    void defaultFieldInitializersParticipateInCopyEffects() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        struct Point {
                          x: int;
                        }

                        define class Holder as
                          pub val Point point = copy(Point { x = 1 });
                        end

                        @NoCopy
                        fnc make() => Holder {
                          return new Holder();
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoCopy contract violated"));
        assertTrue(error.getMessage().contains("copy(...)"));
    }

    @Test
    void methodContractsAreCheckedDirectly() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        define class Buffer as
                          pub let int value = 0;

                          @NoAlloc
                          pub duplicate() => Buffer {
                            return new Buffer(self.value);
                          }
                        end
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("Buffer.duplicate"));
    }

    @Test
    void effectContractAnnotationsDoNotAcceptArgumentsOrDuplicates() {
        IllegalArgumentException argError = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        @NoAlloc<int>
                        fnc bad() => int {
                          return 1;
                        }
                        """));
        assertTrue(argError.getMessage().contains("@NoAlloc does not accept arguments"));

        IllegalArgumentException duplicateError = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        @NoCopy
                        @NoCopy
                        fnc bad(int value) => int {
                          return value;
                        }
                        """));
        assertTrue(duplicateError.getMessage().contains("duplicate @NoCopy"));
    }

    @Test
    void genericPrimitiveStructCopyRemainsNoAlloc() {
        assertDoesNotThrow(() -> check("""
                struct Box<T> {
                  value: T;
                }

                @NoAlloc
                fnc duplicate(Box<int> value) => Box<int> {
                  return copy(value);
                }
                """));
    }

    @Test
    void immutableShareIsActuallyZeroCopy() {
        assertDoesNotThrow(() -> check("""
                @NoAlloc
                @NoCopy
                fnc expose(String value) => &String {
                  return share(value);
                }
                """));

        assertDoesNotThrow(() -> check("""
                @NoAlloc
                @NoCopy
                fnc expose(int value) => &int {
                  return share(value);
                }
                """));
    }

    @Test
    void traitOwnedDefaultInitializerCannotBeMaskedByPositionalArgs() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        struct Point {
                          x: int;
                        }

                        define trait HasDefault as
                          private val Point point = copy(Point { x = 1 });
                        end

                        define class Holder with HasDefault as
                          pub val int value;
                        end

                        @NoCopy
                        fnc make() => Holder {
                          return new Holder(5);
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoCopy contract violated"));
        assertTrue(error.getMessage().contains("copy(...)"));
    }

    @Test
    void shadowedCallableNamesRemainDynamicAndConservative() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        fnc target() => int {
                          return 1;
                        }

                        @NoAlloc
                        fnc invoke((() -> int) target) => int {
                          return target();
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("dynamic callable binding 'target(...)'"));
    }

    @Test
    void capturingClosureAllocatesButNonCapturingLambdaBodyDoesNotRunAtCreation() {
        IllegalArgumentException capture = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        @NoAlloc
                        fnc make() => int {
                          val int base = 41;
                          val callback = || -> {
                            return base + 1;
                          };
                          return 1;
                        }
                        """));
        assertTrue(capture.getMessage().contains("capturing closure/lambda environment"));

        assertDoesNotThrow(() -> check("""
                define class Buffer as
                  pub val int value = 1;
                end

                @NoAlloc
                fnc make() => int {
                  val callback = || -> {
                    val Buffer buffer = new Buffer();
                    return buffer.value;
                  };
                  return 1;
                }
                """));
    }

    @Test
    void forOfPropagatesHiddenIteratorEffects() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check("""
                        struct Bag {
                          [Symbol.iterator]() => Array<int> {
                            return arr[1, 2, 3];
                          }
                        }

                        @NoAlloc
                        fnc iterate(Bag bag) => int {
                          let int total = 0;
                          for (val item of bag) {
                            total = total + item;
                          }
                          return total;
                        }
                        """));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("Symbol.iterator"));
        assertTrue(error.getMessage().contains("array/list literal"));
    }

    @Test
    void forOfBuiltInArrayDoesNotInventIteratorAllocation() {
        assertDoesNotThrow(() -> check("""
                @NoAlloc
                @NoCopy
                fnc sum(Array<int> values) => int {
                  let int total = 0;
                  for (val item of values) {
                    total = total + item;
                  }
                  return total;
                }
                """));
    }

    private static void check(String source) {
        TypeChecker.check(Parser.parse(source));
    }
}
