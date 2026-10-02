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

final class AsyncAndNumericHardeningTest {

    @Test
    void asyncCallableProducesFutureAndAwaitUnwrapsIt() throws Exception {
        String program = """
                define module app
                  pub async fnc plus_one(int x) => int {
                    return x + 1;
                  }

                  pub fnc main() => void {
                    val pending = plus_one(41);
                    val answer = await pending;
                    stdio.println(answer);
                    return;
                  }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "async.ores")
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
    void awaitRejectsNonAsyncValuesAtCompileTime() {
        String program = """
                define module app
                  pub fnc main() => void {
                    val answer = await 42;
                    return;
                  }
                end
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(failure.getMessage().contains("await requires"));
    }

    @Test
    void asyncResultCannotBeUsedAsPlainValueWithoutAwait() {
        String program = """
                define module app
                  pub async fnc plus_one(int x) => int {
                    return x + 1;
                  }

                  pub fnc consume(int value) => void {
                    return;
                  }

                  pub fnc main() => void {
                    consume(plus_one(41));
                    return;
                  }
                end
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(failure.getMessage().contains("argument"));
    }

    @Test
    void asyncResultCannotBeSilentlyDiscarded() {
        String program = """
                define module app
                  pub async fnc work() => int {
                    return 1;
                  }

                  pub fnc main() => void {
                    work();
                    return;
                  }
                end
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(failure.getMessage().contains("cannot be discarded"));
    }

    @Test
    void deferCannotImplicitlyDetachAsyncWork() {
        String program = """
                define module app
                  pub async fnc cleanup() => int {
                    return 1;
                  }

                  pub fnc main() => void {
                    defer cleanup();
                    return;
                  }
                end
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(failure.getMessage().contains("defer"));
        assertTrue(failure.getMessage().contains("async"));
    }

    @Test
    void boundFutureMustBeAwaitedOrTransferredBeforeScopeExit() {
        String program = """
                define module app
                  pub async fnc work() => int {
                    return 1;
                  }

                  pub fnc main() => void {
                    val pending = work();
                    return;
                  }
                end
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(failure.getMessage().contains("Future"));
        assertTrue(failure.getMessage().contains("awaited or transferred"));
    }

    @Test
    void awaitConsumesFutureBindingAndProducesPayloadOwnershipType() {
        String program = """
                define module app
                  pub async fnc work() => int {
                    return 41;
                  }

                  pub fnc main() => void {
                    val pending = work();
                    val answer = await pending;
                    val copy = copy(answer);
                    stdio.println(copy + 1);
                    return;
                  }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void futureCannotBecomeActorMessageOrSharedMemory() {
        String actorProgram = """
                define module app
                  pub fnc worker(Future<int> pending) => void {
                    return;
                  }

                  pub fnc main() => void {
                    val ref = actor.spawn(worker);
                    return;
                  }
                end
                """;

        IllegalArgumentException actorFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(actorProgram)));
        assertTrue(actorFailure.getMessage().contains("Future"));

        String sharedProgram = """
                define module app
                  pub async fnc work() => int {
                    return 1;
                  }

                  pub fnc main() => void {
                    val pending = work();
                    val shared = process.share_readonly(pending);
                    return;
                  }
                end
                """;

        IllegalArgumentException sharedFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(sharedProgram)));
        assertTrue(sharedFailure.getMessage().contains("Future"));
    }

    @Test
    void asyncCallableCannotCaptureCallScopedBorrow() {
        String program = """
                define module app
                  pub async fnc bad(Borrow<int> value) => int {
                    return 1;
                  }
                end
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(failure.getMessage().contains("Borrow"));
        assertTrue(failure.getMessage().contains("async"));
    }

    @Test
    void asyncInstanceMethodIsRejectedUntilReceiverOwnershipIsExplicit() {
        String program = """
                define module app
                  define class Box
                    pub async value() => int {
                      return 1;
                    }
                  end
                end
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(failure.getMessage().contains("async"));
        assertTrue(failure.getMessage().contains("receiver"));
    }

    @Test
    void integerDivisionMatchesStaticIntType() throws Exception {
        String program = """
                define module app
                  pub fnc main() => void {
                    stdio.println(5 / 2);
                    return;
                  }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "integer-division.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("2"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("2.5"));
    }

    @Test
    void integerDivisionByZeroIsAlwaysAnError() throws Exception {
        String program = """
                define module app
                  pub fnc main() => void {
                    stdio.println(1 / 0);
                    return;
                  }
                end
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "division-zero.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            PolyglotException failure = assertThrows(PolyglotException.class, () -> context.eval(source));
            assertTrue(failure.getMessage().contains("division by zero"));
        }
    }

    @Test
    void signedIntegerOverflowNeverSilentlyWraps() throws Exception {
        String program = """
                define module app
                  pub fnc main() => void {
                    stdio.println(9223372036854775807 + 1);
                    return;
                  }
                end
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "overflow.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            PolyglotException failure = assertThrows(PolyglotException.class, () -> context.eval(source));
            assertTrue(failure.getMessage().contains("integer overflow"));
        }
    }
}
