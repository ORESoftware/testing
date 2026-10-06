package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class MethodOverloadTest {
    @Test
    void instanceMethodsDispatchOnlyByNameAndArityAtRuntime() throws Exception {
        String output = run("""
                define module m
                  define class C as
                    pub pick(): int { return 1; }
                    pub pick(int value): int { return value; }
                    pub pick(int left, int right): int { return left + right; }
                  end
                end

                pub routine main(): void {
                  val c = new m.C();
                  stdio.stdout.write(c.pick());
                  stdio.stdout.write(c.pick(4));
                  stdio.stdout.write(c.pick(2, 3));
                }
                """);

        assertEquals("145", output);
    }

    @Test
    void selectedInstanceArityStillRequiresEveryArgumentTypeToMatch() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub pick(int value): int { return value; }
                            pub pick(int left, String right): int { return left; }
                          end
                        end

                        fnc bad(): void {
                          val c = new m.C();
                          c.pick("wrong");
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("argument 1"));
    }

    @Test
    void selectedMultiArgumentArityChecksTypesPositionally() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub pick(int left, String right): int { return left; }
                          end
                        end

                        fnc bad(): void {
                          val c = new m.C();
                          c.pick(1, 2);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("argument 2"));
    }

    @Test
    void selectedStaticArityStillRequiresArgumentTypesToMatch() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub static fnc pick(int value): int { return value; }
                          end
                        end

                        fnc bad(): void {
                          m.C.pick("wrong");
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("argument 1"));
    }

    @Test
    void parameterTypesDoNotCreateDistinctOverloads() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub find(int value): int { return value; }
                            pub find(String value): int { return 1; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 1"));
        assertTrue(error.getMessage().contains("name + arity only"));
    }

    @Test
    void returnTypesDoNotCreateDistinctOverloads() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub find(int value): int { return value; }
                            pub find(int value): String { return "same slot"; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 1"));
        assertTrue(error.getMessage().contains("parameter types and return types do not participate"));
    }

    @Test
    void genericParameterNamesAndTypesDoNotCreateDistinctOverloads() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub identity<T>(T value): T { return value; }
                            pub identity<U>(U value): U { return value; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 1"));
    }

    @Test
    void explicitSelfReceiverDoesNotCountTowardOverloadArity() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module m
                  define class C as
                    pub read(self C)(): int { return 1; }
                    pub read(self C)(int value): int { return value; }
                  end
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub read(): int { return 1; }
                            pub read(self C)(): int { return 2; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 0"));
    }

    @Test
    void staticClassFunctionsUseTheSameArityOnlyRule() throws Exception {
        String output = run("""
                define module m
                  define class C as
                    pub static fnc pick(): int { return 2; }
                    pub static fnc pick(int value): int { return value; }
                  end
                end

                pub routine main(): void {
                  stdio.stdout.write(m.C.pick());
                  stdio.stdout.write(m.C.pick(7));
                }
                """);

        assertEquals("27", output);

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub static fnc find(int value): int { return value; }
                            pub static fnc find(String value): int { return 1; }
                          end
                        end
                        """)));
    }

    @Test
    void inheritedOverloadsRemainAvailableByArity() throws Exception {
        String output = run("""
                define module m
                  define class Base as
                    pub pick(): int { return 1; }
                    pub pick(int value): int { return value; }
                  end

                  define class Child extends Base as
                    pub pick(int left, int right): int { return left + right; }
                  end
                end

                pub routine main(): void {
                  val c = new m.Child();
                  stdio.stdout.write(c.pick());
                  stdio.stdout.write(c.pick(4));
                  stdio.stdout.write(c.pick(2, 3));
                }
                """);

        assertEquals("145", output);
    }

    @Test
    void changingParameterTypeInAChildOccupiesTheSameOverrideSlot() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Base as
                            pub find(int value): int { return value; }
                          end

                          define class Child extends Base as
                            pub find(String value): int { return 1; }
                          end
                        end
                        """)));
    }

    @Test
    void unrelatedParentsWithSameArityRequireExplicitChildOverrideEvenWhenTypesMatch() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Left as
                            pub pick(int value): int { return value; }
                          end

                          define class Right as
                            pub pick(int value): int { return value; }
                          end

                          define class Child extends Left, Right as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited method 'Child.pick' with arity 1"));
        assertTrue(error.getMessage().contains("m.Left"));
        assertTrue(error.getMessage().contains("m.Right"));
        assertTrue(error.getMessage().contains("argument and return types never select an overload"));
    }

    @Test
    void explicitChildOverrideResolvesSameArityMultipleInheritance() throws Exception {
        String output = run("""
                define module m
                  define class Left as
                    pub pick(int value): int { return value + 10; }
                  end

                  define class Right as
                    pub pick(int value): int { return value + 20; }
                  end

                  define class Child extends Left, Right as
                    pub pick(int value): int { return value + 30; }
                  end
                end

                pub routine main(): void {
                  val child = new m.Child();
                  stdio.stdout.write(child.pick(2));
                }
                """);

        assertEquals("32", output);
    }

    @Test
    void trueDiamondInheritanceOfTheSameDeclarationIsNotAmbiguous() throws Exception {
        String output = run("""
                define module m
                  define class Base as
                    pub pick(int value): int { return value + 1; }
                  end

                  define class Left extends Base as
                  end

                  define class Right extends Base as
                  end

                  define class Diamond extends Left, Right as
                  end
                end

                pub routine main(): void {
                  val value = new m.Diamond();
                  stdio.stdout.write(value.pick(4));
                }
                """);

        assertEquals("5", output);
    }

    @Test
    void unrelatedParentsCanContributeDifferentAritiesWithoutConflict() throws Exception {
        String output = run("""
                define module m
                  define class Left as
                    pub pick(): int { return 1; }
                  end

                  define class Right as
                    pub pick(int value): int { return value; }
                  end

                  define class Child extends Left, Right as
                  end
                end

                pub routine main(): void {
                  val child = new m.Child();
                  stdio.stdout.write(child.pick());
                  stdio.stdout.write(child.pick(7));
                }
                """);

        assertEquals("17", output);
    }

    @Test
    void inheritedStaticFunctionsAlsoRequireExplicitResolutionForSameArity() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Left as
                            pub static fnc pick(int value): int { return value; }
                          end

                          define class Right as
                            pub static fnc pick(int value): int { return value; }
                          end

                          define class Child extends Left, Right as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited static function 'Child.pick' with arity 1"));
    }

    @Test
    void parentOrderNeverChoosesAWinningSameArityMethod() {
        String leftFirst = """
                define module m
                  define class Left as
                    pub pick(int value): int { return value; }
                  end
                  define class Right as
                    pub pick(int value): int { return value; }
                  end
                  define class Child extends Left, Right as
                  end
                end
                """;
        String rightFirst = """
                define module m
                  define class Left as
                    pub pick(int value): int { return value; }
                  end
                  define class Right as
                    pub pick(int value): int { return value; }
                  end
                  define class Child extends Right, Left as
                  end
                end
                """;

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(leftFirst)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(rightFirst)));
    }

    @Test
    void asyncModifierDoesNotCreateASecondSameArityOverloadSlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub find(int value): int { return value; }
                            pub async find(int value): int { return value; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 1"));
    }

    @Test
    void staticFunctionCannotCaptureEnclosingClassGenericAsAnImplicitDispatchType() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Box<T> as
                            pub static fnc bad(T value): T { return value; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("cannot reference enclosing class generic 'T'"));
        assertTrue(error.getMessage().contains("declare a static-function generic parameter instead"));
    }

    @Test
    void staticFunctionMayUseItsOwnGenericWithoutChangingArityIdentity() throws Exception {
        String output = run("""
                define module m
                  define class Box<T> as
                    pub static fnc identity<U>(U value): U { return value; }
                  end
                end

                pub routine main(): void {
                  stdio.stdout.write(m.Box.identity(6));
                }
                """);

        assertEquals("6", output);
    }

    @Test
    void staticAndInstanceNamespacesRemainDistinct() throws Exception {
        String output = run("""
                define module m
                  define class C as
                    pub pick(int value): int { return value + 1; }
                    pub static fnc pick(int value): int { return value + 2; }
                  end
                end

                pub routine main(): void {
                  val c = new m.C();
                  stdio.stdout.write(c.pick(3));
                  stdio.stdout.write(m.C.pick(3));
                }
                """);

        assertEquals("45", output);
    }

    @Test
    void inheritedGenericMethodUsesConcreteParentBindingAfterAritySelection() throws Exception {
        String output = run("""
                define module m
                  define class Base<T> as
                    pub pick(T value): T { return value; }
                  end

                  define class Child extends Base<int> as
                  end
                end

                pub routine main(): void {
                  val child = new m.Child();
                  stdio.stdout.write(child.pick(7));
                }
                """);

        assertEquals("7", output);

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Base<T> as
                            pub pick(T value): T { return value; }
                          end

                          define class Child extends Base<int> as
                          end
                        end

                        fnc bad(m.Child child): void {
                          child.pick("wrong");
                          return;
                        }
                        """)));
    }

    @Test
    void concreteGenericParentContractChecksTheSelectedOverrideSlot() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module m
                  define class Base<T> as
                    pub pick(T value): T { return value; }
                  end

                  define class Good extends Base<int> as
                    pub pick(int value): int { return value; }
                  end
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Base<T> as
                            pub pick(T value): T { return value; }
                          end

                          define class Bad extends Base<int> as
                            pub pick(String value): String { return value; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("conflicting member 'instance$pick$arity1"), error.getMessage());
    }

    @Test
    void sameGenericDiamondViewOfOneDeclarationIsSafe() throws Exception {
        String output = run("""
                define module m
                  define class Base<T> as
                    pub pick(T value): T { return value; }
                  end

                  define class Left extends Base<int> as
                  end

                  define class Right extends Base<int> as
                  end

                  define class Child extends Left, Right as
                  end
                end

                pub routine main(): void {
                  val child = new m.Child();
                  stdio.stdout.write(child.pick(9));
                }
                """);

        assertEquals("9", output);
    }

    @Test
    void conflictingGenericDiamondViewsRequireExplicitChildResolution() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Base<T> as
                            pub pick(T value): T { return value; }
                          end

                          define class Left extends Base<int> as
                          end

                          define class Right extends Base<String> as
                          end

                          define class Child extends Left, Right as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited method 'Child.pick' with arity 1"));
        assertTrue(error.getMessage().contains("m.Base"));
    }

    @Test
    void interfacesAndImplementationsUseArityOnlyMethodSlots() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Lookup {
                  fnc find() => int;
                  fnc find(int value) => int;
                }

                define module m
                  define class C implements Lookup as
                    pub find(): int { return 0; }
                    pub find(int value): int { return value; }
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub interface Lookup {
                  fnc find(int value) => int;
                  fnc find(String value) => int;
                }
                """)));
    }

    @Test
    void genericMethodArgumentsMustUnifyAfterAritySelection() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module m
                  define class C as
                    pub same<T>(T left, T right): T { return left; }
                  end
                end

                fnc good(): int {
                  val c = new m.C();
                  return c.same(1, 2);
                }
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub same<T>(T left, T right): T { return left; }
                          end
                        end

                        fnc bad(): void {
                          val c = new m.C();
                          c.same(1, "wrong");
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("conflicting inference"));
    }

    @Test
    void nestedGenericMethodArgumentsMustUnifyPositionally() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub same<T>(Array<T> left, Array<T> right): T {
                              return left[0];
                            }
                          end
                        end

                        fnc bad(): void {
                          val c = new m.C();
                          c.same(arr[1], arr["wrong"]);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("conflicting inference"));
    }

    @Test
    void staticGenericMethodArgumentsMustAlsoUnify() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub static fnc same<T>(T left, T right): T { return left; }
                          end
                        end

                        fnc bad(): void {
                          m.C.same(1, "wrong");
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("conflicting inference"));
    }

    @Test
    void genericMethodReturnTypeIsInferredFromMatchedArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module m
                  define class C as
                    pub identity<T>(T value): T { return value; }
                  end
                end

                fnc good(): int {
                  val c = new m.C();
                  return c.identity(7);
                }
                """)));

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub identity<T>(T value): T { return value; }
                          end
                        end

                        fnc bad(): String {
                          val c = new m.C();
                          return c.identity(7);
                        }
                        """)));
    }

    @Test
    void genericMethodMustInferEveryMethodGenericFromArguments() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub make<T>(): T { return self.make<T>(); }
                          end
                        end

                        fnc bad(): void {
                          val c = new m.C();
                          c.make();
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot infer"), error::getMessage);
    }

    @Test
    void alphaRenamedGenericOverrideIsTheSameArityContract() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module m
                  define abstract class Base as
                    pub abstract identity<T>(T value): T;
                  end

                  define class Child extends Base as
                    pub identity<U>(U value): U { return value; }
                  end
                end
                """)));
    }

    @Test
    void genericWildcardAssignabilityCannotHideAnIncompatibleOverrideContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define abstract class Base as
                            pub abstract identity<T>(T value): T;
                          end

                          define class Child extends Base as
                            pub identity<U>(U value): int { return 1; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("conflicting member 'instance$identity$arity1"), error.getMessage());
    }

    @Test
    void alphaRenamedGenericInterfaceSlotsComposeAsOneContract() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Left {
                  fnc identity<T>(T value) => T;
                }

                pub interface Right {
                  fnc identity<U>(U value) => U;
                }

                pub interface Combined extends Left, Right {
                }

                define module m
                  define class C implements Combined as
                    pub identity<V>(V value): V { return value; }
                  end
                end
                """)));
    }

    @Test
    void incompatibleGenericInterfaceContractsCannotShareOneAritySlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub interface Left {
                          fnc identity<T>(T value) => T;
                        }

                        pub interface Right {
                          fnc identity<U>(U value) => int;
                        }

                        pub interface Combined extends Left, Right {
                        }
                        """)));

        assertTrue(error.getMessage().contains("conflicting member 'instance$identity$arity1"), error.getMessage());
    }

    @Test
    void genericInterfaceMethodSlotUsesConcreteTypeArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Lookup<T> {
                  fnc find(T value) => T;
                }

                define module m
                  define class C implements Lookup<int> as
                    pub find(int value): int { return value; }
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub interface Lookup<T> {
                  fnc find(T value) => T;
                }

                define module m
                  define class C implements Lookup<int> as
                    pub find(String value): String { return value; }
                  end
                end
                """)));
    }

    @Test
    void sameGenericInterfaceDiamondViewIsCompatible() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Base<T> {
                  fnc pick(T value) => T;
                }

                pub interface Left extends Base<int> {
                }

                pub interface Right extends Base<int> {
                }

                pub interface Combined extends Left, Right {
                }

                define module m
                  define class C implements Combined as
                    pub pick(int value): int { return value; }
                  end
                end
                """)));
    }

    @Test
    void conflictingGenericInterfaceDiamondCannotBecomeTypeBasedOverloads() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub interface Base<T> {
                          fnc pick(T value) => T;
                        }

                        pub interface Left extends Base<int> {
                        }

                        pub interface Right extends Base<String> {
                        }

                        pub interface Combined extends Left, Right {
                        }
                        """)));

        assertTrue(error.getMessage().contains("conflicting member"));
        assertTrue(error.getMessage().contains("pick$arity1"));
    }

    @Test
    void abstractMethodsCreateRequiredNameAndAritySlots() throws Exception {
        String output = run("""
                define module m
                  define abstract class Base as
                    pub abstract pick(): int;
                    pub abstract pick(int value): int;
                  end

                  define class Child extends Base as
                    pub pick(): int { return 1; }
                    pub pick(int value): int { return value; }
                  end
                end

                pub routine main(): void {
                  val child = new m.Child();
                  stdio.stdout.write(child.pick());
                  stdio.stdout.write(child.pick(8));
                }
                """);

        assertEquals("18", output);
    }

    @Test
    void concreteClassMustImplementEveryInheritedAbstractArity() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define abstract class Base as
                            pub abstract pick(): int;
                            pub abstract pick(int value): int;
                          end

                          define class Child extends Base as
                            pub pick(): int { return 1; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("unresolved abstract method 'pick' with arity 1"));
    }

    @Test
    void abstractImplementationCannotChangeTheContractWithinTheSameAritySlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define abstract class Base as
                            pub abstract pick(int value): int;
                          end

                          define class Child extends Base as
                            pub pick(String value): String { return value; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("conflicting member 'instance$pick$arity1"), error.getMessage());
    }

    @Test
    void abstractMethodCannotBePrivateBecauseTheSlotMustBeImplementable() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define abstract class Base as
                            abstract pick(int value): int;
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("must be public"));
    }

    @Test
    void concreteClassCannotDeclareAnUnresolvedAbstractSlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Concrete as
                            pub abstract pick(int value): int;
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("concrete class 'Concrete'"));
        assertTrue(error.getMessage().contains("arity 1"));
    }

    @Test
    void abstractClassesCannotBeInstantiated() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define abstract class Base as
                            pub abstract pick(int value): int;
                          end
                        end

                        fnc bad(): void {
                          val value = new m.Base();
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot instantiate abstract class 'Base'"));
    }

    @Test
    void symbolMethodsUseTheSameNameAndArityOverloadIdentity() throws Exception {
        String output = run("""
                define module m
                  define class Bag as
                    pub [Symbol.iterator](): Array<int> {
                      return arr[4, 5];
                    }

                    pub [Symbol.iterator](int value): Array<int> {
                      return arr[value];
                    }
                  end
                end

                pub routine main(): void {
                  val bag = new m.Bag();
                  for (val item of bag) {
                    stdio.stdout.write(item);
                  }
                }
                """);

        assertEquals("45", output);
    }

    @Test
    void duplicateSymbolMethodWithSameArityIsRejectedRegardlessOfTypes() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Bag as
                            pub [Symbol.iterator](): Array<int> {
                              return arr[1];
                            }

                            pub [Symbol.iterator](): Array<String> {
                              return arr["x"];
                            }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 0"));
    }

    @Test
    void inheritedSymbolMethodCollisionRequiresExplicitArityResolution() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Left as
                            pub [Symbol.iterator](): Array<int> {
                              return arr[1];
                            }
                          end

                          define class Right as
                            pub [Symbol.iterator](): Array<int> {
                              return arr[2];
                            }
                          end

                          define class Child extends Left, Right as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains(
                "ambiguous inherited method 'Child.Symbol.iterator' with arity 0"));
    }

    @Test
    void overloadedMethodValueRequiresCallArityForSelection() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C as
                            pub pick(): int { return 1; }
                            pub pick(int value): int { return value; }
                          end
                        end

                        fnc hold(): void {
                          val c = new m.C();
                          val callback = c.pick;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("direct-call-only"));
    }

    @Test
    void genericParameterCountDoesNotCreateAnotherInheritedClassSlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Base as
                            pub pick<T>(int value): int { return value; }
                          end
                          define class Child extends Base as
                            pub pick<T, U>(int value): int { return value; }
                          end
                        end
                        """)));
        assertTrue(error.getMessage().contains("incompatible generic arity"), error::getMessage);
    }

    @Test
    void interfaceGenericParameterCountDoesNotCreateAnotherLocalSlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub interface C {
                          fnc pick<T>(int value) => int;
                          fnc pick<T, U>(int value) => int;
                        }
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
    }

    @Test
    void interfaceGenericParameterCountDoesNotCreateAnotherInheritedSlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub interface Left { fnc pick<T>(int value) => int; }
                        pub interface Right { fnc pick<T, U>(int value) => int; }
                        pub interface Child extends Left, Right { }
                        """)));
        assertTrue(error.getMessage().contains("incompatible generic arity"), error::getMessage);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "method-overload.ores")
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
