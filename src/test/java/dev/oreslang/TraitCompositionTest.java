package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TraitCompositionTest {
    @Test
    void interfacesAreStorageFreeContracts() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define interface Bad as
                          val int state;
                        end
                        """));

        assertTrue(error.getMessage().contains("storage-free contracts"));
    }

    @Test
    void traitCanCarryPrivateStateBehaviorAndAnInterfaceContract() {
        Ast.Program checked = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define interface CounterApi as
                    fnc bump() => int;
                  end

                  define trait Counter is CounterApi as
                    private let int count = 10;

                    pub bump(self &mut self)() => int {
                      self.count = self.count + 1;
                      return self.count;
                    }
                  end

                  define class Box with Counter as
                    pub val int value;
                  end
                end
                """)));

        Ast.ModuleDecl model = checked.modules().stream()
                .filter(module -> module.name().equals("model"))
                .findFirst()
                .orElseThrow();
        Ast.ClassDecl box = model.declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .filter(klass -> klass.name().equals("Box"))
                .findFirst()
                .orElseThrow();

        assertTrue(box.traits().isEmpty());
        assertTrue(box.interfaces().stream().anyMatch(type -> type.name().equals("CounterApi")));
        assertTrue(box.fields().stream().anyMatch(field ->
                field.name().equals("count") && field.composed()));
        assertTrue(box.methods().stream().anyMatch(method ->
                method.name().equals("bump") && method.composed()));
    }

    @Test
    void traitStateIsNotAPositionalConstructorParameter() throws Exception {
        String program = """
                define module model as
                  define trait Counter as
                    private let int count = 10;

                    pub bump(self &mut self)() => int {
                      self.count = self.count + 1;
                      return self.count;
                    }
                  end

                  define class Box with Counter as
                    pub val int value;
                  end
                end

                define module app as
                  pub fnc main() => void {
                    let box = new Box(5);
                    stdio.println(box.value);
                    stdio.println(box.bump());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "traits.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("5"));
        assertTrue(text.contains("11"));
    }

    @Test
    void mutableTraitMethodsRequireAMutableOwnerAtCallSite() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Counter as
                            private let int count = 0;

                            pub bump(self &mut self)() => int {
                              self.count = self.count + 1;
                              return self.count;
                            }
                          end

                          define class Box with Counter as
                          end

                          pub fnc bad() => int {
                            val box = new Box();
                            return box.bump();
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("cannot mutate method 'bump' receiver"));
        assertTrue(error.getMessage().contains("immutable"));
    }

    @Test
    void mutableTraitMethodsCannotBeExtractedAsBoundValues() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Counter as
                            private let int count = 0;

                            pub bump(self &mut self)() => int {
                              self.count = self.count + 1;
                              return self.count;
                            }
                          end

                          define class Box with Counter as
                          end

                          pub fnc bad() => void {
                            let box = new Box();
                            val callback = box.bump;
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("cannot be extracted"));
        assertTrue(error.getMessage().contains("persistent &mut"));
    }

    @Test
    void multipleConcreteTraitMethodsRequireExplicitClassResolution() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait A as
                            pub value() => int { return 1; }
                          end

                          define trait B as
                            pub value() => int { return 2; }
                          end

                          define class Bad with A, B as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous trait method"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define trait A as
                    pub value() => int { return 1; }
                  end

                  define trait B as
                    pub value() => int { return 2; }
                  end

                  define class Good with A, B as
                    pub value() => int { return 3; }
                  end
                end
                """)));
    }

    @Test
    void traitRequirementsMustBeImplementedByConcreteClasses() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Loads as
                            pub abstract load() => int;

                            pub read() => int {
                              return self.load();
                            }
                          end

                          define class Bad with Loads as
                          end
                        end
                        """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define trait Loads as
                    pub abstract load() => int;

                    pub read() => int {
                      return self.load();
                    }
                  end

                  define class Good with Loads as
                    pub load() => int { return 42; }
                  end
                end
                """)));
    }

    @Test
    void traitsCannotBeInstantiatedDirectly() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Counter as
                            private val int count = 0;
                          end

                          pub fnc make() => void {
                            val counter = new Counter();
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("cannot be instantiated"));
        assertTrue(error.getMessage().contains("with"));
    }

    @Test
    void privateTraitMethodsRemainLexicalToTheTrait() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define trait Secret as
                    private helper() => int {
                      return 7;
                    }

                    pub read() => int {
                      return self.helper();
                    }
                  end

                  define class Good with Secret as
                  end
                end
                """)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Secret as
                            private helper() => int {
                              return 7;
                            }

                            pub read() => int {
                              return self.helper();
                            }
                          end

                          define class Bad with Secret as
                            pub leak() => int {
                              return self.helper();
                            }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("trait-private method"));
        assertTrue(error.getMessage().contains("Secret.helper"));
    }

    @Test
    void hostClassesCannotOverridePrivateTraitHelpers() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Secret as
                            private helper() => int {
                              return 7;
                            }

                            pub read() => int {
                              return self.helper();
                            }
                          end

                          define class Bad with Secret as
                            pub helper() => int {
                              return 99;
                            }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("trait-private method"));
        assertTrue(error.getMessage().contains("cannot be overridden"));
    }

    @Test
    void privateAbstractTraitRequirementsAreRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Impossible as
                            private abstract load() => int;
                          end

                          define abstract class Host with Impossible as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("private abstract method"));
        assertTrue(error.getMessage().contains("must be public"));
    }

    @Test
    void traitsCannotAppearInRuntimeTypePositions() {
        IllegalArgumentException direct = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Counter as
                          end

                          pub fnc use(Counter value) => void {
                            return;
                          }
                        end
                        """)));

        assertTrue(direct.getMessage().contains("cannot be used as a runtime type"));

        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Counter as
                          end

                          pub fnc use(Option<Counter> value) => void {
                            return;
                          }
                        end
                        """)));

        assertTrue(nested.getMessage().contains("cannot be used as a runtime type"));
    }

    @Test
    void traitGenericSubstitutionRewritesMethodBodyTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define trait Echo<T> as
                    pub echo(T value) => T {
                      val T copy = value;
                      return copy;
                    }
                  end

                  define class IntEcho with Echo<int> as
                  end

                  pub fnc run() => int {
                    val echo = new IntEcho();
                    return echo.echo(7);
                  }
                end
                """)));
    }

    @Test
    void traitGenericSubstitutionRewritesCallableLocalTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define trait LocalFactory<T> as
                    pub make(T value) => T {
                      struct Box {
                        item: T;
                      }
                      val Box box = Box { item = value };
                      return box.item;
                    }
                  end

                  define class IntFactory with LocalFactory<int> as
                  end

                  pub fnc run() => int {
                    val factory = new IntFactory();
                    return factory.make(7);
                  }
                end
                """)));
    }

    @Test
    void traitNamesShareTheTypeNamespace() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define interface Named as
                            fnc name() => String;
                          end

                          define trait Named as
                            pub name() => String { return "trait"; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("collides with an existing class/interface/type alias"));
    }

    @Test
    void privateTraitStateStaysLexicallyOwnedByTheTrait() {
        IllegalArgumentException leak = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Secret as
                            private val int secret = 7;
                            pub read() => int { return self.secret; }
                          end

                          define class Bad with Secret as
                            pub leak() => int { return self.secret; }
                          end
                        end
                        """)));

        assertTrue(leak.getMessage().contains("private to that trait"));

        IllegalArgumentException hiddenDependency = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait HostReader as
                            pub read() => int { return self.host_value; }
                          end

                          define class Bad with HostReader as
                            pub val int host_value = 9;
                          end
                        end
                        """)));

        assertTrue(hiddenDependency.getMessage().contains("undeclared self member"));
    }
    @Test
    void callableLocalStructsComposeTraitsAndInitializeTraitOwnedState() throws Exception {
        String program = """
                define module app as
                  define trait Answering as
                    private val int seed = 41;

                    pub answer() => int {
                      return self.seed + 1;
                    }
                  end

                  fnc make() => T {
                    struct T with Answering {
                      pub name: string;
                    }
                    return obj{name: "local"};
                  }

                  pub fnc main() => void {
                    val value = make();
                    stdio.stdout.write(value.answer());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "local-traits.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("42"));
    }

    @Test
    void callersCannotInjectComposedTraitStateIntoStructLiterals() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          define trait Secret as
                            private val int hidden = 7;
                          end

                          fnc bad() => T {
                            struct T with Secret {
                              pub name: string;
                            }
                            return T { name = "x", hidden = 99 };
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("trait-owned struct field"));
    }

    @Test
    void traitsCannotMasqueradeAsRuntimeValueTypes() {
        IllegalArgumentException parameter = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          define trait Stateful as
                            private val int state = 1;
                          end

                          fnc bad(Stateful value) => void {
                            return;
                          }
                        end
                        """)));
        assertTrue(parameter.getMessage().contains("no runtime/value type identity"));

        IllegalArgumentException allocation = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          define trait Stateful as
                            private val int state = 1;
                          end

                          fnc bad() => void {
                            val value = new Stateful();
                            return;
                          }
                        end
                        """)));
        assertTrue(allocation.getMessage().contains("no runtime/value type identity"));
    }

    @Test
    void aggregateContractTraitAndAliasNamesShareOneTypeNamespace() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          define trait Thing as
                            private val int state = 1;
                          end

                          struct Thing {
                            value: int;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("share one namespace"));
    }
    @Test
    void privateTraitMethodsRemainOwnedByTheirTraitAfterComposition() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  define trait SecretMath as
                    private helper() => int {
                      return 41;
                    }

                    pub answer() => int {
                      return self.helper() + 1;
                    }
                  end

                  define class Good with SecretMath as
                  end
                end
                """)));

        IllegalArgumentException leak = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          define trait SecretMath as
                            private helper() => int {
                              return 41;
                            }
                          end

                          define class Bad with SecretMath as
                            pub leak() => int {
                              return self.helper();
                            }
                          end
                        end
                        """)));

        assertTrue(leak.getMessage().contains("private to that trait"));
    }
    @Test
    void callableLocalTraitsComposeLexicallyAndAreErased() {
        Ast.Program checked = TypeChecker.check(Parser.parse("""
                define module app as
                  fnc make() => T {
                    trait Answering {
                      private val int seed = 41;
                      pub answer() => int { return self.seed + 1; }
                    }

                    struct T with Answering {
                      pub value: int;
                    }

                    return T { value = 1 };
                  }
                end
                """));

        Ast.FunctionDecl make = checked.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("make"))
                .findFirst().orElseThrow();

        assertFalse(make.body().stream().anyMatch(stmt ->
                stmt instanceof Ast.TypeDeclStmt local && local.declaration() instanceof Ast.TraitDecl));

        Ast.ClassDecl localStruct = make.body().stream()
                .filter(Ast.TypeDeclStmt.class::isInstance)
                .map(Ast.TypeDeclStmt.class::cast)
                .map(Ast.TypeDeclStmt::declaration)
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();

        assertTrue(localStruct.traits().isEmpty(), "local traits must be flattened before later compiler passes");
        assertTrue(localStruct.methods().stream().anyMatch(method -> method.name().equals("answer")));
        assertTrue(localStruct.fields().stream().anyMatch(field -> field.name().equals("seed") && field.composed()));
    }

    @Test
    void callableLocalTraitScopeDoesNotLeakAcrossFunctions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc first() => A {
                          trait Local {
                            pub value() => int { return 1; }
                          }
                          struct A with Local {}
                          return A {};
                        }

                        fnc second() => B {
                          struct B with Local {}
                          return B {};
                        }
                        """)));

        assertTrue(error.getMessage().contains("unknown trait 'Local'"));
    }

    @Test
    void callableLocalTraitNamesShareTheLocalTypeNamespace() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc make() => T {
                          trait Same {
                            pub value() => int { return 1; }
                          }

                          struct Same {
                            value: int;
                          }

                          struct T with Same {
                            value: int;
                          }
                          return T { value = 1 };
                        }
                        """)));

        assertTrue(error.getMessage().contains("callable-local type name 'Same'"));
    }

    @Test
    void callableLocalTraitsCannotBeUsedAsRuntimeValues() {
        IllegalArgumentException parameter = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          trait Local {
                            pub value() => int { return 1; }
                          }

                          let Local value = 1;
                          return;
                        }
                        """)));
        assertTrue(parameter.getMessage().contains("trait 'Local'"));

        IllegalArgumentException initializer = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          trait Local {
                            pub value() => int { return 1; }
                          }

                          val value = Local {};
                          return;
                        }
                        """)));
        assertTrue(initializer.getMessage().contains("cannot be"));
    }
}
