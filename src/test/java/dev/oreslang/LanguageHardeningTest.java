package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
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

                define module app
                  pub fnc main(): void { return; }
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
                define module app
                  fnc main(): void {
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
                define module contracts
                  define interface MathApi
                    fnc add(int a, int b) => int;
                  end
                end

                @AdheresTo(contracts.MathApi)
                define module math
                  pub fnc add(int a, int b): int { return a + b; }
                end

                define module app
                  pub fnc main(): void {
                    val answer = math.add(40, 2);
                    stdio.println(answer);
                    return;
                  }
                end
                """)));
    }

    @Test
    void directOnlyRoutineStillParticipatesInModuleCallableContracts() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module contracts
                  define interface Api
                    fnc ping(int value) => int;
                  end
                end

                @AdheresTo(contracts.Api)
                define module service
                  pub routine ping(int value): int {
                    return value + 1;
                  }
                end

                fnc callDirectly(): int {
                  return service.ping(41);
                }
                """)));

        IllegalArgumentException extracted = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module service
                          pub routine ping(int value): int {
                            return value + 1;
                          }
                        end

                        fnc bad(): void {
                          val Fnc<int, int> callback = service.ping;
                        }
                        """)));
        assertTrue(extracted.getMessage().contains("direct-call-only"));
    }

    @Test
    void interfaceMethodsAreDirectOnlyAndInheritedGenericCallsStayTyped() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model
                  define interface Base<T>
                    fnc apply(T value) => T;
                  end

                  define interface IntApi extends Base<int>
                  end

                  fnc invoke(IntApi api): int {
                    return api.apply(41);
                  }
                end
                """)));

        IllegalArgumentException extracted = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model
                          define interface Base<T>
                            fnc apply(T value) => T;
                          end

                          define interface IntApi extends Base<int>
                          end

                          fnc bad(IntApi api): void {
                            val Fnc<int, int> callback = api.apply;
                            return;
                          }
                        end
                        """)));
        assertTrue(extracted.getMessage().contains("interface method"));
        assertTrue(extracted.getMessage().contains("direct-call-only"));

        IllegalArgumentException badArgument = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model
                          define interface Base<T>
                            fnc apply(T value) => T;
                          end

                          define interface IntApi extends Base<int>
                          end

                          fnc bad(IntApi api): int {
                            return api.apply("wrong");
                          }
                        end
                        """)));
        assertTrue(badArgument.getMessage().contains("argument 1"));
    }

    @Test
    void structuralMethodsAreDirectOnlyButDirectCallsRemainTyped() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model
                  define interface Api
                    fnc apply(int value) => int;
                  end

                  fnc invoke(@Structural Api api): int {
                    return api.apply(41);
                  }
                end
                """)));

        IllegalArgumentException extracted = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model
                          define interface Api
                            fnc apply(int value) => int;
                          end

                          fnc bad(@Structural Api api): void {
                            val Fnc<int, int> callback = api.apply;
                            return;
                          }
                        end
                        """)));
        assertTrue(extracted.getMessage().contains("structural method"));
        assertTrue(extracted.getMessage().contains("direct-call-only"));

        IllegalArgumentException badArgument = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model
                          define interface Api
                            fnc apply(int value) => int;
                          end

                          fnc bad(@Structural Api api): int {
                            return api.apply("wrong");
                          }
                        end
                        """)));
        assertTrue(badArgument.getMessage().contains("argument 1"));
    }

    @Test
    void genericStructuralMethodsInferAndSpecializeWithoutUnknownEscape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model
                  define interface GenericApi
                    fnc identity<T>(T value) => T;
                  end

                  fnc infer(@Structural GenericApi api): int {
                    return api.identity(41);
                  }

                  fnc explicit(@Structural GenericApi api): int {
                    return api.identity<int>(41);
                  }
                end
                """)));

        IllegalArgumentException explicitMismatch = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model
                          define interface GenericApi
                            fnc identity<T>(T value) => T;
                          end

                          fnc bad(@Structural GenericApi api): int {
                            return api.identity<int>("wrong");
                          }
                        end
                        """)));
        assertTrue(explicitMismatch.getMessage().contains("argument 1"));

        IllegalArgumentException inferredReturnMismatch = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model
                          define interface GenericApi
                            fnc identity<T>(T value) => T;
                          end

                          fnc bad(@Structural GenericApi api): int {
                            return api.identity("wrong");
                          }
                        end
                        """)));
        assertTrue(inferredReturnMismatch.getMessage().contains("return value"));
    }

    @Test
    void fieldAndInstanceMethodNamesCannotCollideLocallyOrThroughInheritance() {
        IllegalArgumentException local = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Bad as
                          pub val int value = 1;

                          pub value(): int {
                            return 2;
                          }
                        end
                        """)));
        assertTrue(local.getMessage().contains("both a field and an instance method"));

        IllegalArgumentException inherited = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class HasField as
                          pub val int value = 1;
                        end

                        define class HasMethod as
                          pub value(): int {
                            return 2;
                          }
                        end

                        define class Bad extends HasField, HasMethod as
                        end
                        """)));
        assertTrue(inherited.getMessage().contains("both a field and an instance method"));

        IllegalArgumentException iface = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface HasField
                          val int value;
                        end

                        define interface HasMethod
                          fnc value() => int;
                        end

                        define interface Bad extends HasField, HasMethod
                        end
                        """)));
        assertTrue(iface.getMessage().contains("both a field and a method"));
    }

    @Test
    void storageFieldNamesMustBeUniqueAcrossInheritanceButDiamondsMayShareOneAncestorSlot() {
        IllegalArgumentException shadow = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Parent as
                          private val int id = 1;
                        end

                        define class Child extends Parent as
                          private val int id = 2;
                        end
                        """)));
        assertTrue(shadow.getMessage().contains("must be unique across inheritance"));

        IllegalArgumentException siblingCollision = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Left as
                          private val int id = 1;
                        end

                        define class Right as
                          private val int id = 2;
                        end

                        define class Combined extends Left, Right as
                        end
                        """)));
        assertTrue(siblingCollision.getMessage().contains("must be unique across inheritance"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Root as
                  private val int id = 1;
                end

                define class Left extends Root as
                end

                define class Right extends Root as
                end

                define class Diamond extends Left, Right as
                end
                """)));
    }

    @Test
    void moduleAliasesPreserveFncReifiabilityButKeepRoutinesDirectOnly() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module service
                  pub fnc transform(int value): int {
                    return value + 1;
                  }

                  pub routine direct_only(int value): int {
                    return value + 2;
                  }
                end

                fnc good(): int {
                  val alias = service;
                  val Fnc<int, int> callback = alias.transform;
                  return callback(40) + alias.direct_only(0);
                }
                """)));

        IllegalArgumentException routineValue = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module service
                          pub routine direct_only(int value): int {
                            return value + 1;
                          }
                        end

                        fnc bad(): void {
                          val alias = service;
                          val Fnc<int, int> callback = alias.direct_only;
                          return;
                        }
                        """)));
        assertTrue(routineValue.getMessage().contains("direct-call-only"));
    }

    @Test
    void moduleRuntimeValueNamespaceRejectsCrossCategoryNameCollisions() {
        IllegalArgumentException fncVsBinding = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module bad
                          fnc value(): int {
                            return 1;
                          }

                          val int value = 2;
                        end
                        """)));
        assertTrue(fncVsBinding.getMessage().contains("runtime value namespace"));

        IllegalArgumentException classVsRoutine = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module bad
                          define class Worker as
                          end

                          routine Worker(): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(classVsRoutine.getMessage().contains("runtime value namespace"));
    }

    @Test
    void moduleAdherenceRejectsMissingExports() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module contracts
                  define interface Api
                    fnc ping() => int;
                  end
                end

                @AdheresTo(contracts.Api)
                define module broken
                  pub fnc pong(): int { return 1; }
                end
                """)));
        assertTrue(error.getMessage().contains("does not adhere"));
    }

    @Test
    void classesSupportMultipleParentsAndMultipleInterfaces() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model
                  define interface AApi
                    fnc a() => int;
                  end
                  define interface BApi
                    fnc b() => int;
                  end

                  define class A as
                    pub a(): int { return 1; }
                  end
                  define class B as
                    pub b(): int { return 2; }
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
                define module m
                  define class A extends B as
                  end
                  define class B extends A as
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module m
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
                define module app
                  pub fnc main(): void {
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
                define module app
                  fnc f(): void {
                    val x = 1;
                    x = 2;
                    return;
                  }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc f(): void {
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
                define module app
                  fnc bad(): String { return null; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(null x): void { return; }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc keep(Option<String> x): Option<String> { return x; }
                  fnc explicit_marker(Option<null> x): Option<null> { return x; }
                  fnc some_value(): Option<int> { return Some(1); }
                  fnc no_value(): Option<int> { return None; }
                end
                """)));
    }

    @Test
    void nonVoidFunctionsMustReturnOnEveryControlFlowPath() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc incomplete(bool flag): int {
                    if flag; do
                      return 1;
                    fi
                  }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc complete(bool flag): int {
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
                define module app
                end
                """)));
    }
    @Test
    void strictFaasRejectsSharedActorDeclarationsAtAdmission() {
        Ast.Program sharedActor = TypeChecker.check(Parser.parse("""
                shared actor Account {
                  let balance = 100;

                  pub fnc current(): int {
                    return self.balance;
                  }
                }
                """));

        assertThrows(SecurityException.class, () ->
                CapabilityChecker.check(sharedActor, IsolatePolicy.strictFaas()));
        assertDoesNotThrow(() ->
                CapabilityChecker.check(sharedActor, IsolatePolicy.developer()));
    }



@Test
    void destructureDiscardNeverBecomesAReadableBinding() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc f(): int {
                    [_, const value] = (1, 2);
                    return _;
                  }
                end
                """)));

        assertTrue(error.getMessage().contains("unknown name '_'"));
    }

@Test
    void explicitBindingKindOnUnderscoreIsAlsoDiscarded() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc f(): int {
                    [const _, let value] = (1, 2);
                    [let _, const next] = (3, 4);
                    return value + next;
                  }
                end
                """)));
    }

    @Test
    void declarationIdentitiesCannotBeCreatedFromExecutableBlocks() {
        IllegalArgumentException module = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc main() : void {
                          define module RuntimeMade
                          end
                          return;
                        }
                        """));
        assertTrue(module.getMessage().contains("module declarations are compile-time"));

        IllegalArgumentException klass = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc main() : void {
                          define class RuntimeMade as
                          end
                          return;
                        }
                        """));
        assertTrue(klass.getMessage().contains("class declarations are compile-time"));

        IllegalArgumentException namespace = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc main() : void {
                          namespace runtime;
                          return;
                        }
                        """));
        assertTrue(namespace.getMessage().contains("namespace declarations are file-scope"));
    }

    @Test
    void structAndTraitAreReservedForStaticTypeDeclarations() {
        var tokens = new Lexer("struct Point trait Display").scan();
        assertEquals(Token.Type.STRUCT, tokens.get(0).type());
        assertEquals(Token.Type.IDENT, tokens.get(1).type());
        assertEquals(Token.Type.TRAIT, tokens.get(2).type());
        assertEquals(Token.Type.IDENT, tokens.get(3).type());

        IllegalArgumentException trait = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc main() : void {
                          trait RuntimeTrait
                          return;
                        }
                        """));
        assertTrue(trait.getMessage().contains("runtime trait creation is forbidden"));
    }


}
