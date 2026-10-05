package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class OwnershipAndClosureTest {

    @Test
    void lexicalClosureEscapesAndRetainsMutableCapturedState() throws Exception {
        String output = run("""
                fnc makeCounter() => (() -> int) {
                  let int count = 0;
                  return || -> {
                    count = count + 1;
                    return count;
                  };
                }

                pub routine main() => void {
                  val (() -> int) counter = makeCounter();
                  stdio.stdout.write(counter());
                  stdio.stdout.write(counter());
                  return;
                }
                """);
        assertEquals("12", output);
    }

    @Test
    void ordinaryParametersAreImmutableForFieldMutation() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc change(Bar b) => void {
                          b.foo = "foobar";
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("immutable parameter/binding"));
    }

    @Test
    void ownedMutParameterMayMutateAndReturnOwnership() throws Exception {
        String output = run("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc change(Bar mut b) => Bar {
                  b.foo = "foobar";
                  return b;
                }

                pub routine main() => void {
                  let Bar b = new Bar();
                  let Bar changed = change(b);
                  stdio.stdout.write(changed.foo);
                  return;
                }
                """);
        assertEquals("foobar", output);
    }

    @Test
    void mutableBorrowAllowsMutationWithoutMovingOwner() throws Exception {
        String output = run("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc change(&mut Bar b) => void {
                  b.foo = "borrowed";
                  return;
                }

                pub routine main() => void {
                  let Bar b = new Bar();
                  change(&mut b);
                  stdio.stdout.write(b.foo);
                  return;
                }
                """);
        assertEquals("borrowed", output);
    }

    @Test
    void immutableBorrowBlocksOverlappingMutableBorrow() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc mutate(&mut Bar b) => void {
                          b.foo = "changed";
                          return;
                        }

                        fnc bad() => void {
                          let Bar b = new Bar();
                          val &Bar read = &b;
                          mutate(&mut b);
                          stdio.println(read.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void useAfterMoveIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc consume(Bar b) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Bar b = new Bar();
                          consume(b);
                          stdio.println(b.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("use of moved value 'b'"));
    }

    @Test
    void borrowOfLocalCannotEscapeFunction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc bad() => &Bar {
                          let Bar b = new Bar();
                          return &b;
                        }
                        """)));
        assertTrue(error.getMessage().contains("outlive its owner"));
    }

    @Test
    void borrowedParameterCanBeReturned() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc identity(&Bar b) => &Bar {
                  return b;
                }
                """)));
    }


    @Test
    void multipleImmutableBorrowsMayCoexist() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc ok() => void {
                  let Bar b = new Bar();
                  val &Bar first = &b;
                  val &Bar second = &b;
                  stdio.println(first.foo);
                  stdio.println(second.foo);
                  return;
                }
                """)));
    }

    @Test
    void secondMutableBorrowIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc bad() => void {
                          let Bar b = new Bar();
                          val &mut Bar first = &mut b;
                          val &mut Bar second = &mut b;
                          stdio.println(first.foo);
                          stdio.println(second.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void moveWhileBorrowedIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc consume(Bar b) => void { return; }

                        fnc bad() => void {
                          let Bar b = new Bar();
                          val &Bar read = &b;
                          consume(b);
                          stdio.println(read.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("cannot move"));
    }

    @Test
    void lexicalScopeEndsStoredBorrow() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc mutate(&mut Bar b) => void {
                  b.foo = "changed";
                  return;
                }

                fnc ok() => void {
                  let Bar b = new Bar();
                  if true; do
                    val &Bar read = &b;
                    stdio.println(read.foo);
                  fi
                  mutate(&mut b);
                  return;
                }
                """)));
    }


    @Test
    void moveInBothIfBranchesIsAllowedButValueIsMovedAfterJoin() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc consume(Bar b) => void { return; }

                fnc ok(bool flag) => void {
                  let Bar b = new Bar();
                  if flag; do
                    consume(b);
                  else
                    consume(b);
                  fi
                  return;
                }
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc consume(Bar b) => void { return; }

                        fnc bad(bool flag) => void {
                          let Bar b = new Bar();
                          if flag; do
                            consume(b);
                          else
                            consume(b);
                          fi
                          stdio.println(b.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("use of moved value 'b'"));
    }

    @Test
    void immutableFieldStaysImmutableEvenThroughMutOwner() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub val String foo = "start";
                        end

                        fnc bad(Bar mut b) => void {
                          b.foo = "changed";
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("field 'Bar.foo' is immutable"));
    }

    @Test
    void moveOnlyCaptureTransfersIntoClosure() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val (() -> int) read = || -> {
                            return box.value;
                          };
                          stdio.println(box.value);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("use of moved value 'box'"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "ownership.ores")
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
