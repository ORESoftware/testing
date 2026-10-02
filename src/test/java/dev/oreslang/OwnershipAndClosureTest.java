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
                        define class Bar
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
                define class Bar
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
                define class Bar
                  pub let String foo = "start";
                end

                fnc change(BorrowMut<Bar> b) => void {
                  b.foo = "borrowed";
                  return;
                }

                pub routine main() => void {
                  let Bar b = new Bar();
                  change(borrow_mut(b));
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
                        define class Bar
                          pub let String foo = "start";
                        end

                        fnc mutate(BorrowMut<Bar> b) => void {
                          b.foo = "changed";
                          return;
                        }

                        fnc bad() => void {
                          let Bar b = new Bar();
                          val Borrow<Bar> read = borrow(b);
                          mutate(borrow_mut(b));
                          stdio.println(read.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void legacyAmpersandBorrowSyntaxIsRejectedWithMigrationGuidance() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(Borrow<String> value) => void {
                          val Borrow<String> other = &value;
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("borrow(value)"));
    }

    @Test
    void explicitTakeMakesMoveIntentVisible() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar
                          pub let String foo = "start";
                        end

                        fnc consume(Bar b) => void { return; }

                        fnc bad() => void {
                          let Bar b = new Bar();
                          consume(take(b));
                          stdio.println(b.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("use of moved value 'b'"));
    }

    @Test
    void copyRejectsMoveOnlyHeapValuesUntilExplicitCopySemanticsExist() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar
                          pub let String foo = "start";
                        end

                        fnc bad() => void {
                          let Bar b = new Bar();
                          val Bar c = copy(b);
                          stdio.println(c.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("Copy") || error.getMessage().contains("copy"));
    }

    @Test
    void copyWorksForStaticallyCopyScalarsWithoutConsumingTheSource() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val int x = 21;
                  val int y = copy(x);
                  stdio.stdout.write(x + y);
                  return;
                }
                """);
        assertEquals("42", output);
    }

    @Test
    void deferCapturesCopyValuesAtRegistrationTime() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let int x = 1;
                  defer stdio.stdout.write(x);
                  x = 2;
                  stdio.stdout.write(x);
                  return;
                }
                """);
        assertEquals("21", output);
    }

    @Test
    void deferOwnsMoveOnlyCapturesImmediately() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box
                          pub let String value = "x";
                        end

                        fnc cleanup(Box box) => void { return; }
                        fnc consume(Box box) => void { return; }

                        pub routine main() => void {
                          let Box box = new Box();
                          defer cleanup(box);
                          consume(box);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("moved value 'box'"));
    }

    @Test
    void deferCannotCaptureBorrowedOrDirectlyMutatedBindings() {
        IllegalArgumentException borrowed = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box
                          pub let String value = "x";
                        end

                        fnc inspect(Borrow<Box> box) => void { return; }

                        pub routine main() => void {
                          let Box box = new Box();
                          val Borrow<Box> view = borrow(box);
                          defer inspect(view);
                          return;
                        }
                        """)));
        assertTrue(borrowed.getMessage().contains("defer"));
        assertTrue(borrowed.getMessage().contains("borrow"));

        IllegalArgumentException write = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          let int x = 1;
                          defer x = 2;
                          return;
                        }
                        """)));
        assertTrue(write.getMessage().contains("defer"));
        assertTrue(write.getMessage().contains("mutate"));
    }

    @Test
    void malformedBorrowTypesGetCompilerDiagnostics() {
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Borrow value) => void { return; }
                        """)));
        assertTrue(missing.getMessage().contains("exactly one"));

        IllegalArgumentException extra = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(BorrowMut<String, String> value) => void { return; }
                        """)));
        assertTrue(extra.getMessage().contains("exactly one"));
    }

    @Test
    void useAfterMoveIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar
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
                        define class Bar
                          pub let String foo = "start";
                        end

                        fnc bad() => Borrow<Bar> {
                          let Bar b = new Bar();
                          return borrow(b);
                        }
                        """)));
        assertTrue(error.getMessage().contains("outlive its owner"));
    }

    @Test
    void borrowedParameterCanBeReturned() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar
                  pub let String foo = "start";
                end

                fnc identity(Borrow<Bar> b) => Borrow<Bar> {
                  return b;
                }
                """)));
    }


    @Test
    void multipleImmutableBorrowsMayCoexist() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar
                  pub let String foo = "start";
                end

                fnc ok() => void {
                  let Bar b = new Bar();
                  val Borrow<Bar> first = borrow(b);
                  val Borrow<Bar> second = borrow(b);
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
                        define class Bar
                          pub let String foo = "start";
                        end

                        fnc bad() => void {
                          let Bar b = new Bar();
                          val BorrowMut<Bar> first = borrow_mut(b);
                          val BorrowMut<Bar> second = borrow_mut(b);
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
                        define class Bar
                          pub let String foo = "start";
                        end

                        fnc consume(Bar b) => void { return; }

                        fnc bad() => void {
                          let Bar b = new Bar();
                          val Borrow<Bar> read = borrow(b);
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
                define class Bar
                  pub let String foo = "start";
                end

                fnc mutate(BorrowMut<Bar> b) => void {
                  b.foo = "changed";
                  return;
                }

                fnc ok() => void {
                  let Bar b = new Bar();
                  if true; do
                    val Borrow<Bar> read = borrow(b);
                    stdio.println(read.foo);
                  fi
                  mutate(borrow_mut(b));
                  return;
                }
                """)));
    }


    @Test
    void moveInBothIfBranchesIsAllowedButValueIsMovedAfterJoin() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar
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
                        define class Bar
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
                        define class Bar
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
                        define class Box
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
