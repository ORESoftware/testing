package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class OwnershipIntrinsicsTest {

    @Test
    void structsHaveBuiltInCopySemantics() throws Exception {
        String output = run("""
                struct Point {
                  x: int;
                  y: int;
                }

                fnc consume(Point value) => int {
                  return value.x + value.y;
                }

                pub routine main() => void {
                  let Point p = Point { x = 2, y = 3 };
                  stdio.stdout.write(consume(copy(p)));
                  stdio.stdout.write(p.x);
                  return;
                }
                """);

        assertEquals("52", output);
    }

    @Test
    void classesRequireHumanCopyImplementation() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 1;
                        end

                        fnc bad() => void {
                          let Buffer b = new Buffer();
                          let Buffer c = copy(b);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("public instance copy()"));
    }

    @Test
    void classCopyImplementationProducesIndependentOwner() throws Exception {
        String output = run("""
                define class Buffer as
                  pub let int value = 0;

                  pub copy() => Buffer {
                    return new Buffer(self.value);
                  }
                end

                pub routine main() => void {
                  let Buffer original = new Buffer(7);
                  let Buffer duplicate = copy(original);
                  duplicate.value = 9;
                  stdio.stdout.write(original.value);
                  stdio.stdout.write(duplicate.value);
                  return;
                }
                """);

        assertEquals("79", output);
    }

    @Test
    void dishonestClassCopyCannotRetainMutableSourceStorage() {
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                define class Buffer as
                  pub let int value = 0;

                  pub copy() => Buffer {
                    return self;
                  }
                end

                pub routine main() => void {
                  let Buffer original = new Buffer(7);
                  let Buffer duplicate = copy(original);
                  stdio.stdout.write(duplicate.value);
                  return;
                }
                """));

        assertTrue(error.getMessage().contains("Copy contract"));
    }

    @Test
    void classCopyCannotHideNestedMutableAliases() {
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                define class Bucket as
                  pub let Array<int> values = arr[1];

                  pub copy() => Bucket {
                    return new Bucket(self.values);
                  }
                end

                pub routine main() => void {
                  let Bucket original = new Bucket(arr[1, 2]);
                  let Bucket duplicate = copy(original);
                  stdio.stdout.write(duplicate.values[0]);
                  return;
                }
                """));

        assertTrue(error.getMessage().contains("retaining mutable storage"));
    }

    @Test
    void classCopyMayExplicitlyCopyNestedMutableStorage() throws Exception {
        String output = run("""
                define class Bucket as
                  pub let Array<int> values = arr[1];

                  pub copy() => Bucket {
                    return new Bucket(copy(self.values));
                  }
                end

                pub routine main() => void {
                  let Bucket original = new Bucket(arr[1, 2]);
                  let Bucket duplicate = copy(original);
                  stdio.stdout.write(original.values[0]);
                  stdio.stdout.write(duplicate.values[0]);
                  return;
                }
                """);

        assertEquals("11", output);
    }

    @Test
    void borrowIntrinsicIsPointerFreeBorrowSyntax() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Buffer as
                  pub let int value = 1;
                end

                fnc inspect(&Buffer value) => int {
                  return value.value;
                }

                fnc ok() => int {
                  let Buffer b = new Buffer();
                  return inspect(borrow(b));
                }
                """)));
    }

    @Test
    void takeIntrinsicExplicitlyMovesOwner() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 1;
                        end

                        fnc consume(Buffer value) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Buffer b = new Buffer();
                          consume(take(b));
                          stdio.println(b.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("use of moved value 'b'"));
    }

    @Test
    void shareCreatesReadonlySnapshotWithoutBorrowingOriginal() throws Exception {
        String output = run("""
                define class Buffer as
                  pub let int value = 0;

                  pub copy() => Buffer {
                    return new Buffer(self.value);
                  }
                end

                fnc inspect(&Buffer value) => int {
                  return value.value;
                }

                pub routine main() => void {
                  let Buffer original = new Buffer(7);
                  val &Buffer shared = share(original);
                  original.value = 9;
                  stdio.stdout.write(inspect(shared));
                  stdio.stdout.write(original.value);
                  return;
                }
                """);

        assertEquals("79", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "ownership-intrinsics.ores")
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
