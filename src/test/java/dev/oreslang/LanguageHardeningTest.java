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
}
