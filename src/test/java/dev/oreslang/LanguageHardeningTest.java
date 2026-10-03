package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LanguageHardeningTest {
    @Test
    void parsesAllRequestedImportForms() {
        Ast.Program program = Parser.parse("""
                import module foo from "../xyz";
                import module {bar, baz} from '../xyz';
                import class {x} from '../xyz';
                import fnc * as funcs from '../xyz';
                import * as everything from './xyz';

                define module app as
                  pub fnc main() => void { return; }
                end
                """);

        assertEquals(5, program.imports().size());
        assertEquals(Ast.ImportKind.MODULE, program.imports().getFirst().kind());
        assertEquals("foo", program.imports().getFirst().names().getFirst());
        assertEquals("funcs", program.imports().get(3).namespace());
        assertEquals(Ast.ImportKind.ALL, program.imports().get(4).kind());
        assertEquals("everything", program.imports().get(4).namespace());
    }

    @Test
    void fiIsARealDistinctTokenAndEndDoesNotCloseIf() {
        var tokens = new Lexer("if true; do return; fi end").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.FI));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.END));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app as
                  fnc main() => void {
                    if true; do
                      return;
                    end
                  }
                end
                """));
    }

    @Test
    void modulesAreTypedNamespacesAndCanAdhereToInterfaces() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module contracts as
                  define interface MathApi
                    fnc add(int a, int b) => int;
                  end
                end

                @AdheresTo(contracts.MathApi)
                define module math as
                  pub fnc add(int a, int b) => int { return a + b; }
                end

                define module app as
                  pub fnc main() => void {
                    val answer = math.add(40, 2);
                    stdio.println(answer);
                    return;
                  }
                end
                """)));
    }

    @Test
    void moduleAdherenceRejectsMissingExports() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module contracts as
                  define interface Api
                    fnc ping() => int;
                  end
                end

                @AdheresTo(contracts.Api)
                define module broken as
                  pub fnc pong() => int { return 1; }
                end
                """)));
        assertTrue(error.getMessage().contains("does not adhere"));
    }

    @Test
    void classesSupportMultipleParentsAndMultipleInterfaces() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define interface AApi
                    fnc a() => int;
                  end
                  define interface BApi
                    fnc b() => int;
                  end

                  define class A as
                    pub a() => int { return 1; }
                  end
                  define class B as
                    pub b() => int { return 2; }
                  end

                  define class Combined extends A, B implements AApi, BApi as
                  end

                  define class ObjectChild extends Object as
                  end
                  define class ListChild extends List as
                  end
                end
                """)));
    }

    @Test
    void inheritanceCyclesAndConflictingDiamondsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module m as
                  define class A extends B as
                  end
                  define class B extends A as
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module m as
                  define class A as
                    pub val int id = 1;
                  end
                  define class B as
                    pub val String id = "b";
                  end
                  define class C extends A, B as
                  end
                end
                """)));
    }

    @Test
    void objArrTupleIndexAndLetAssignmentAreStaticallyChecked() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  pub fnc main() => void {
                    val person = obj{name: "ore", age: 1};
                    val values = arr[10, 20, 30];
                    val first = values[0];
                    [const left, let right] = (1, "two");
                    let n = first;
                    n = 99;
                    stdio.println(person.name);
                    stdio.println(right);
                    return;
                  }
                end
                """)));
    }

    @Test
    void valAndConstCannotBeReassigned() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc f() => void {
                    val x = 1;
                    x = 2;
                    return;
                  }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc f() => void {
                    const x = 1;
                    x = 2;
                    return;
                  }
                end
                """)));
    }

    @Test
    void nullIsForbiddenAsAValueOrStandaloneTypeButOptionNullIsExplicitlyAllowed() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app as
                  fnc bad() => String { return null; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc bad(null x) => void { return; }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc keep(Option<String> x) => Option<String> { return x; }
                  fnc explicit_marker(Option<null> x) => Option<null> { return x; }
                  fnc some_value() => Option<int> { return Some(1); }
                  fnc no_value() => Option<int> { return None; }
                end
                """)));
    }

    @Test
    void nonVoidFunctionsMustReturnOnEveryControlFlowPath() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc incomplete(bool flag) => int {
                    if flag; do
                      return 1;
                    fi
                  }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc complete(bool flag) => int {
                    if flag; do
                      return 1;
                    else
                      return 2;
                    fi
                  }
                end
                """)));
    }

    @Test
    void duplicateImportBindingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                import module foo from './a';
                import class {foo} from './b';
                define module app as
                end
                """)));
    }
    @Test
    void genericAggregateConstructionUsesConcreteTypeArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                struct Box<T> {
                  value: T;
                }

                fnc good() => Box<int> {
                  return Box<int> { value = 7 };
                }

                fnc inferred() => Box<int> {
                  return Box<> { value = 8 };
                }
                """)));

        IllegalArgumentException wrongField = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        struct Box<T> {
                          value: T;
                        }

                        fnc bad() => Box<int> {
                          return Box<int> { value = "wrong" };
                        }
                        """)));
        assertTrue(wrongField.getMessage().contains("struct field Box.value"));

        IllegalArgumentException rawGeneric = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        struct Box<T> {
                          value: T;
                        }

                        fnc bad() => Box<int> {
                          return Box { value = 7 };
                        }
                        """)));
        assertTrue(rawGeneric.getMessage().contains("expects 1 type argument"));

        IllegalArgumentException unresolved = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        struct Marker<T> {
                          value: int;
                        }

                        fnc bad() => Marker<int> {
                          return Marker<> { value = 7 };
                        }
                        """)));
        assertTrue(unresolved.getMessage().contains("cannot infer generic 'T'"));

        IllegalArgumentException conflicting = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        struct Pair<T> {
                          left: T;
                          right: T;
                        }

                        fnc bad() => Pair<int> {
                          return Pair<> { left = 7, right = "wrong" };
                        }
                        """)));
        assertTrue(conflicting.getMessage().contains("incompatible types for generic 'T'"));
    }

    @Test
    void declaredGenericTypesRequireConcreteArityAndInterfacesSubstituteArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define interface ValueApi<T> as
                  fnc value() => T;
                end

                define class IntBox implements ValueApi<int> as
                  pub value() => int { return 7; }
                end

                fnc use(IntBox box) => ValueApi<int> {
                  return box;
                }
                """)));

        IllegalArgumentException diamondType = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        struct Box<T> {
                          value: T;
                        }

                        fnc bad(Box<> value) => void {
                          return;
                        }
                        """)));
        assertTrue(diamondType.getMessage().contains("explicit type arguments"));

        IllegalArgumentException rawType = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        struct Box<T> {
                          value: T;
                        }

                        fnc bad(Box value) => void {
                          return;
                        }
                        """)));
        assertTrue(rawType.getMessage().contains("expects 1 type argument"));

        IllegalArgumentException wrongInterface = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface ValueApi<T> as
                          fnc value() => T;
                        end

                        define class Bad implements ValueApi<int> as
                          pub value() => String { return "wrong"; }
                        end
                        """)));
        assertTrue(wrongInterface.getMessage().contains("does not implement interface"));
    }

    @Test
    void nominalInterfaceReceiversResolveGenericMethodContracts() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define interface ValueApi<T> as
                  fnc value() => T;
                  fnc replace(T value) => T;
                end

                fnc read(ValueApi<int> api) => int {
                  return api.value();
                }

                fnc replace(ValueApi<int> api) => int {
                  return api.replace(7);
                }
                """)));

        IllegalArgumentException wrongReturn = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface ValueApi<T> as
                          fnc value() => T;
                        end

                        fnc bad(ValueApi<int> api) => String {
                          return api.value();
                        }
                        """)));
        assertTrue(wrongReturn.getMessage().contains("return"));

        IllegalArgumentException wrongArgument = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface ValueApi<T> as
                          fnc replace(T value) => T;
                        end

                        fnc bad(ValueApi<int> api) => int {
                          return api.replace("wrong");
                        }
                        """)));
        assertTrue(wrongArgument.getMessage().contains("interface argument"));

        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface ValueApi<T> as
                          fnc value() => T;
                        end

                        fnc bad(ValueApi<int> api) => int {
                          return api.missing();
                        }
                        """)));
        assertTrue(unknown.getMessage().contains("no interface method"));
    }

    @Test
    void privateAggregateMembersAreLexicalNotReceiverBased() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Secret as
                  private val int hidden = 7;

                  private read() => int {
                    return self.hidden;
                  }

                  private static fnc twice(int x) => int {
                    return x * 2;
                  }

                  pub expose() => int {
                    return self.read();
                  }

                  pub static fnc exposed_twice(int x) => int {
                    return Secret.twice(x);
                  }
                end
                """)));

        IllegalArgumentException field = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Secret as
                          private val int hidden = 7;
                        end

                        fnc leak() => int {
                          val secret = new Secret();
                          return secret.hidden;
                        }
                        """)));
        assertTrue(field.getMessage().contains("private to Secret"));

        IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Secret as
                          private read() => int { return 7; }
                        end

                        fnc leak() => int {
                          val secret = new Secret();
                          return secret.read();
                        }
                        """)));
        assertTrue(method.getMessage().contains("private to Secret"));

        IllegalArgumentException inherited = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Parent as
                          private read() => int { return 7; }
                        end

                        define class Child extends Parent as
                          pub leak() => int {
                            return self.read();
                          }
                        end
                        """)));
        assertTrue(inherited.getMessage().contains("private to Parent"));

        IllegalArgumentException staticFn = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Secret as
                          private static fnc twice(int x) => int { return x * 2; }
                        end

                        fnc leak() => int {
                          return Secret.twice(3);
                        }
                        """)));
        assertTrue(staticFn.getMessage().contains("private to Secret"));
    }

    @Test
    void genericMemberReadsAndWritesKeepConcreteFieldTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box<T> as
                  pub let T value;
                end

                fnc read(Box<int> box) => int {
                  return box.value;
                }

                fnc write(Box<int> mut box) => Box<int> {
                  box.value = 9;
                  return box;
                }
                """)));

        IllegalArgumentException read = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box<T> as
                          pub let T value;
                        end

                        fnc wrong(Box<int> box) => String {
                          return box.value;
                        }
                        """)));
        assertTrue(read.getMessage().contains("return"));

        IllegalArgumentException write = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box<T> as
                          pub let T value;
                        end

                        fnc wrong(Box<int> mut box) => void {
                          box.value = "wrong";
                          return;
                        }
                        """)));
        assertTrue(write.getMessage().contains("assignment"));
    }
    @Test
    void structuralGenericShapesUseConcreteAndInheritedArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box<T> as
                  pub val T value;

                  pub get() => T {
                    return self.value;
                  }
                end

                fnc direct(@Structural Box<int> box) => int {
                  return box.get();
                }

                define class Parent<T> as
                  pub val T value;
                  pub get() => T {
                    return self.value;
                  }
                end

                define class Child<T> extends Parent<T> as
                end

                fnc inherited(@Structural Child<int> child) => int {
                  return child.get();
                }
                """)));

        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box<T> as
                          pub val T value;
                          pub get() => T {
                            return self.value;
                          }
                        end

                        fnc bad(@Structural Box<int> box) => String {
                          return box.get();
                        }
                        """)));

        assertTrue(wrong.getMessage().contains("return"));
    }
    @Test
    void inheritedGenericConstructorFieldsUseParentInstantiation() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Parent<T> as
                  pub val T value;
                end

                define class Child<T> extends Parent<T> as
                end

                fnc explicit() => Child<int> {
                  return new Child<int>(7);
                }

                fnc inferred() => Child<int> {
                  return new Child<>(8);
                }
                """)));

        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Parent<T> as
                          pub val T value;
                        end

                        define class Child<T> extends Parent<T> as
                        end

                        fnc bad() => Child<int> {
                          return new Child<int>("wrong");
                        }
                        """)));
        assertTrue(wrong.getMessage().contains("constructor field value"));
    }
    @Test
    void inheritedGenericMethodsUseDeclaringOwnerInstantiation() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Parent<U> as
                  pub val U value;

                  pub get() => U {
                    return self.value;
                  }
                end

                define class Child<T> extends Parent<T> as
                end

                fnc read(Child<int> child) => int {
                  return child.get();
                }
                """)));

        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Parent<U> as
                          pub val U value;
                          pub get() => U { return self.value; }
                        end

                        define class Child<T> extends Parent<T> as
                        end

                        fnc bad(Child<int> child) => String {
                          return child.get();
                        }
                        """)));
        assertTrue(wrong.getMessage().contains("return"));
    }

    @Test
    void nominalGenericSubtypeProjectionPreservesArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Parent<U> as
                end

                define class Child<T> extends Parent<T> as
                end

                fnc class_upcast(Child<int> child) => Parent<int> {
                  return child;
                }

                define interface ValueApi<U> as
                  fnc get() => U;
                end

                define interface DerivedApi<T> extends ValueApi<T> as
                end

                define class Impl<T> is DerivedApi<T> as
                  pub val T value;
                  pub get() => T {
                    return self.value;
                  }
                end

                fnc interface_upcast(Impl<int> value) => ValueApi<int> {
                  return value;
                }
                """)));

        IllegalArgumentException wrongClass = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Parent<U> as
                        end
                        define class Child<T> extends Parent<T> as
                        end

                        fnc bad(Child<int> child) => Parent<String> {
                          return child;
                        }
                        """)));
        assertTrue(wrongClass.getMessage().contains("return"));

        IllegalArgumentException wrongInterface = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface ValueApi<U> as
                          fnc get() => U;
                        end

                        define class Impl<T> is ValueApi<T> as
                          pub val T value;
                          pub get() => T { return self.value; }
                        end

                        fnc bad(Impl<int> value) => ValueApi<String> {
                          return value;
                        }
                        """)));
        assertTrue(wrongInterface.getMessage().contains("return"));
    }
    @Test
    void callableGenericsAreRigidInBodiesAndInferredAtCalls() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc identity<T>(T value) => T {
                  return value;
                }

                define module helpers as
                  pub fnc pair<T>(T left, T right) => T {
                    return left;
                  }
                end

                define class Echo as
                  pub echo<T>(T value) => T {
                    return value;
                  }

                  pub static fnc static_echo<T>(T value) => T {
                    return value;
                  }
                end

                fnc run() => int {
                  val a = identity(7);
                  val b = helpers.pair(8, 9);
                  val e = new Echo();
                  val c = e.echo(10);
                  val d = Echo.static_echo(11);
                  return a + b + c + d;
                }
                """)));

        IllegalArgumentException fabricated = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad<T>(T value) => T {
                          return 7;
                        }
                        """)));
        assertTrue(fabricated.getMessage().contains("return"));

        IllegalArgumentException conflicting = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc same<T>(T left, T right) => T {
                          return left;
                        }

                        fnc bad() => int {
                          return same(1, "wrong");
                        }
                        """)));
        assertTrue(conflicting.getMessage().contains("incompatible types for generic 'T'"));

        IllegalArgumentException genericValue = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc identity<T>(T value) => T {
                          return value;
                        }

                        fnc bad() => void {
                          val f = identity;
                          return;
                        }
                        """)));
        assertTrue(genericValue.getMessage().contains("polymorphic function values"));
    }
    @Test
    void staticFunctionsInGenericClassesDoNotCaptureClassTypeParameters() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box<T> as
                  pub static fnc answer() => int {
                    return 42;
                  }

                  pub static fnc echo<T>(T value) => T {
                    return value;
                  }
                end

                fnc use() => int {
                  return Box.answer() + Box.echo(1);
                }
                """)));

        IllegalArgumentException signature = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box<T> as
                          pub static fnc bad(T value) => T {
                            return value;
                          }
                        end
                        """)));
        assertTrue(signature.getMessage().contains("cannot use enclosing class generic parameter"));

        IllegalArgumentException nested = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box<T> as
                          pub static fnc bad() => void {
                            type Item = T;
                            return;
                          }
                        end
                        """)));
        assertTrue(nested.getMessage().contains("cannot use enclosing class generic parameter"));
    }
    @Test
    void declaredGenericTypesRequireExplicitConcreteArguments() {
        IllegalArgumentException rawClass = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box<T> as
                          pub val T value;
                        end

                        fnc bad(Box value) => void {
                          return;
                        }
                        """)));
        assertTrue(rawClass.getMessage().contains("expects 1 type argument"));

        IllegalArgumentException diamondAnnotation = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box<T> as
                          pub val T value;
                        end

                        fnc bad(Box<> value) => void {
                          return;
                        }
                        """)));
        assertTrue(diamondAnnotation.getMessage().contains("use <> only at construction"));

        IllegalArgumentException listDiamond = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(List<> value) => void {
                          return;
                        }
                        """)));
        assertTrue(listDiamond.getMessage().contains("diamond inference"));
    }

    @Test
    void genericInterfaceConformanceUsesConcreteArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define interface BoxApi<T> as
                  fnc get() => T;
                end

                define class IntBox implements BoxApi<int> as
                  pub get() => int { return 7; }
                end
                """)));

        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface BoxApi<T> as
                          fnc get() => T;
                        end

                        define class BadBox implements BoxApi<int> as
                          pub get() => String { return "wrong"; }
                        end
                        """)));
        assertTrue(mismatch.getMessage().contains("does not implement interface"));
    }
    @Test
    void aggregateFieldAssignmentRespectsValConstAndLet() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                struct MutablePoint {
                  let int x;
                }

                fnc move(MutablePoint mut point) => MutablePoint {
                  point.x = 9;
                  return point;
                }
                """)));

        IllegalArgumentException structVal = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        struct Point {
                          int x;
                        }

                        fnc bad(Point mut point) => void {
                          point.x = 9;
                          return;
                        }
                        """)));
        assertTrue(structVal.getMessage().contains("cannot be assigned"));

        IllegalArgumentException classConst = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Config as
                          pub const int version = 1;
                        end

                        fnc bad(Config mut config) => void {
                          config.version = 2;
                          return;
                        }
                        """)));
        assertTrue(classConst.getMessage().contains("cannot be assigned"));
    }
    @Test
    void aggregateFieldAssignmentRequiresLetBindingKind() {
        IllegalArgumentException valField = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        struct Point {
                          val int x;
                        }

                        fnc bad(Point p) => void {
                          p.x = 2;
                          return;
                        }
                        """)));
        assertTrue(valField.getMessage().contains("cannot be assigned"));

        IllegalArgumentException constField = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Counter as
                          const int limit = 10;

                          pub set() => void {
                            self.limit = 20;
                            return;
                          }
                        end
                        """)));
        assertTrue(constField.getMessage().contains("cannot be assigned"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                struct MutablePoint {
                  let int x;
                }

                fnc ok(MutablePoint mut p) => void {
                  p.x = 2;
                  return;
                }
                """)));
    }
}
