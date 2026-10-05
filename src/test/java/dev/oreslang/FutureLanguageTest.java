package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class FutureLanguageTest {

    @Test
    void futuresAllRaceAndStateMethodsTypeCheck() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc collect(Future<int> first, Future<int> second) => List<int> {
                    val both = Futures.all([first, second]);
                    return await both;
                  }

                  fnc fastest(Future<int> first, Future<int> second) => int {
                    return await Futures.race([first, second]);
                  }

                  fnc observe(Future<int> work) => bool {
                    if work.is_cancelled(); do
                      return false;
                    fi
                    return work.is_done();
                  }
                end
                """)));
    }

    @Test
    void futuresAllRejectsNonFutureElementsAtTypeCheckTime() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad() => void {
                            val nope = Futures.all([1, 2, 3]);
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("Future"));
    }

    @Test
    void futureOfMutexGuardMayBeAggregatedButAcquiredGuardMayNot() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc aggregate_pending(Mutex<int> first_mutex, Mutex<int> second_mutex) => void {
                    val first = first_mutex.lock_async();
                    val second = second_mutex.lock_async();
                    val pending = [first, second];
                    return;
                  }
                end
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc aggregate_acquired(Mutex<int> mutex) => void {
                            val guard = mutex.lock();
                            val invalid = [guard];
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("MutexGuard cannot be stored in an array/list"));
    }

    @Test
    void futuresAllRunsThroughLanguageRuntimeWithoutBlockingRootCarrier() throws Exception {
        String program = """
                pub routine main() => void {
                  val first_mutex = Mutex.new(1);
                  val second_mutex = Mutex.new(2);
                  val first = first_mutex.lock_async();
                  val second = second_mutex.lock_async();
                  val guards = await Futures.all([first, second]);
                  stdio.println("futures-all-ok");
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "futures.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("futures-all-ok"));
    }
}
