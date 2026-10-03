package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ParserTest {
    @Test
    void supportsMultipleModulesAndComplexNumbers() {
        String source = """
                define module math as
                  fnc z() => complex {
                    return 3 + 4i;
                  }
                end

                define module app as
                  pub fnc main() => void {
                    const answer = 40 + 2;
                    [const first, let second] = [1, 2];
                    stdio.println("oreslang");
                    return;
                  }
                end
                """;

        Ast.Program program = TypeChecker.check(Parser.parse(source));
        assertEquals(2, program.modules().size());
        assertEquals("math", program.modules().getFirst().name());
    }

    @Test
    void parsesIfDoFiWithCommaAndPipeConditions() {
        String source = """
                define module app as
                  fnc choose(bool a, bool b) => int {
                    if a, b | false; do
                      return 1;
                    else
                      return 0;
                    fi
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void methodReceiverIsImplicitOrExplicitSelf() {
        String source = """
                define module model as
                  define class x as
                    @Ret<self>
                    find() {
                      return self;
                    }

                    @Ret<self>
                    find_with_arg(self x)(int foo) {
                      return self;
                    }
                  end
                end
                """;
        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertNull(klass.methods().getFirst().explicitReceiverType());
        assertEquals("x", klass.methods().get(1).explicitReceiverType().name());
    }

    @Test
    void classAndModuleDeclarationsRequireAsAndIsIsReserved() {
        IllegalArgumentException module = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module app
                          pub fnc main() => void { return; }
                        end
                        """));
        assertTrue(module.getMessage().contains("require 'as'"));

        IllegalArgumentException klass = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Box
                        end
                        """));
        assertTrue(klass.getMessage().contains("require 'as'"));

        var tokens = new Lexer("as is").scan();
        assertEquals(Token.Type.AS, tokens.get(0).type());
        assertEquals(Token.Type.IS, tokens.get(1).type());
    }

    @Test
    void asAndIsCannotBeDeclaredAsVariableNames() {
        for (String reserved : java.util.List.of("as", "is")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("""
                            define module app as
                              pub routine main() => void {
                                let int %s = 1;
                                return;
                              }
                            end
                            """.formatted(reserved)));
            assertTrue(error.getMessage().contains("expected binding name")
                    || error.getMessage().contains("expected"));
        }
    }

    @Test
    void parsesFileAndModuleInitRoutinesAsLifecycleDeclarations() {
        Ast.Program program = Parser.parse("""
                init routine() => void {
                  return;
                }

                define module app as
                  init routine() => void {
                    return;
                  }

                  pub fnc main() => void {
                    return;
                  }
                end
                """);

        Ast.ModuleDecl root = program.modules().stream()
                .filter(module -> module.name().equals(Parser.ROOT_MODULE))
                .findFirst().orElseThrow();
        Ast.ModuleDecl app = program.modules().stream()
                .filter(module -> module.name().equals("app"))
                .findFirst().orElseThrow();

        assertEquals(1, root.declarations().stream().filter(Ast.InitDecl.class::isInstance).count());
        assertEquals(1, app.declarations().stream().filter(Ast.InitDecl.class::isInstance).count());
    }

    @Test
    void singletonQualifierCannotApplyToAClass() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define singleton class Foo as
                        end
                        """));
        assertTrue(error.getMessage().contains("'singleton' may only qualify a module"));
    }

    @Test
    void classesCannotDeclareLifecycleInitRoutines() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Box as
                          init routine() => void {
                            return;
                          }
                        end
                        """));
        assertTrue(error.getMessage().contains("classes cannot declare init routine"));
    }

    @Test
    void lexerRecognizesLambdaAndFatReturnArrows() {
        var tokens = new Lexer("(int x) -> x + 1; fnc f() => int { return 1; }").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ARROW));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.FAT_ARROW));
    }
    @Test
    void compactTraitSyntaxParsesLikeLongTraitSyntax() {
        Ast.Program compact = Parser.parse("""
                trait Counting {
                  private val int count = 0;
                  pub value() => int { return self.count; }
                }
                """);

        Ast.Program longForm = Parser.parse("""
                define trait Counting as
                  private val int count = 0;
                  pub value() => int { return self.count; }
                end
                """);

        Ast.TraitDecl compactTrait = (Ast.TraitDecl) compact.modules().getFirst().declarations().getFirst();
        Ast.TraitDecl longTrait = (Ast.TraitDecl) longForm.modules().getFirst().declarations().getFirst();
        assertEquals(longTrait, compactTrait);
    }

    @Test
    void callableLocalTraitsAreAcceptedAsTypeDeclarations() {
        Ast.Program program = Parser.parse("""
                fnc make() => T {
                  trait Answering {
                    private val int seed = 41;
                    pub answer() => int { return self.seed + 1; }
                  }

                  struct T with Answering {
                    value: int;
                  }

                  return T { value = 1 };
                }
                """);

        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertTrue(fn.body().stream().anyMatch(stmt ->
                stmt instanceof Ast.TypeDeclStmt local && local.declaration() instanceof Ast.TraitDecl));
    }
    @Test
    void compactAndLongStructSyntaxNormalizeToSameAstWithContractsAndTraits() {
        Ast.ClassDecl longForm = (Ast.ClassDecl) Parser.parse("""
                define struct Box<T> is Named<T> with Measured<T> as
                  pub value: T;
                end
                """).modules().getFirst().declarations().getFirst();

        Ast.ClassDecl compact = (Ast.ClassDecl) Parser.parse("""
                struct Box<T> is Named<T> with Measured<T> {
                  pub value: T;
                }
                """).modules().getFirst().declarations().getFirst();

        assertEquals(longForm, compact);
        assertTrue(compact.isStruct());
    }

    @Test
    void compactAndLongInterfaceSyntaxNormalizeToSameAst() {
        Ast.InterfaceDecl longForm = (Ast.InterfaceDecl) Parser.parse("""
                define interface Lock<T> as
                  fnc lock() => T;
                end
                """).modules().getFirst().declarations().getFirst();

        Ast.InterfaceDecl compact = (Ast.InterfaceDecl) Parser.parse("""
                interface Lock<T> {
                  fnc lock() => T;
                }
                """).modules().getFirst().declarations().getFirst();

        assertEquals(longForm, compact);
    }
    @Test
    void structBindingKindSupportsTypeFirstAndNameFirstSugarEqually() {
        Ast.ClassDecl typeFirst = (Ast.ClassDecl) Parser.parse("""
                struct State {
                  pub let int count;
                  pub val String name;
                  pub const int limit = 10;
                }
                """).modules().getFirst().declarations().getFirst();

        Ast.ClassDecl nameFirst = (Ast.ClassDecl) Parser.parse("""
                struct State {
                  pub let count: int;
                  pub val name: String;
                  pub const limit: int = 10;
                }
                """).modules().getFirst().declarations().getFirst();

        assertEquals(typeFirst, nameFirst);
        assertEquals(Ast.BindingKind.LET, nameFirst.fields().get(0).bindingKind());
        assertEquals(Ast.BindingKind.VAL, nameFirst.fields().get(1).bindingKind());
        assertEquals(Ast.BindingKind.CONST, nameFirst.fields().get(2).bindingKind());
    }
}
