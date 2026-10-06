package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncAwaitLanguageTest {
    @Test
    void asyncMainAndNestedAsyncFunctionComposeThroughAwait() throws Exception {
        String program = """
                define module app
                  async fnc answer(): int {
                    return 42;
                  }

                  pub async fnc main(): void {
                    val result = await answer();
                    stdio.println(result);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "async-await.ores")
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
    void asyncCompositionRequiresAwaitRatherThanDirectTailTransfer() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        async fnc inner(): int {
                          return 7;
                        }

                        async fnc bad_outer(): int {
                          return inner();
                        }
                        """));

        String program = """
                async fnc inner(): int {
                  return 7;
                }

                async fnc outer(): int {
                  return await inner();
                }

                pub routine main(): void {
                  stdio.stdout.write(await outer());
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "async-tail-boundary.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("7"));
    }

    @Test
    void asyncTaskMovesOwnedArgumentsAndReturnsDetachedResult() throws Exception {
        String program = """
                define module app
                  define class Box as
                    pub let int value = 1;
                  end

                  async fnc change(Box mut box): Box {
                    box.value = 99;
                    return box;
                  }

                  pub fnc main(): void {
                    let original = new Box();
                    val changed = await change(original);
                    stdio.println(changed.value);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "async-owned-boundary.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("99"));
    }
    @Test
    void pendingRendezvousChannelReadAndWriteResumeThroughSourceTasks() throws Exception {
        String program = """
                define module app
                  pub let Channel<int> output = Channel.new<int>(0);

                  pub async actor fnc producer(): void {
                    writech app.output, 42;
                    return;
                  }

                  pub fnc main(): void {
                    val producer_done = producer();
                    val value = readch app.output;
                    await producer_done;
                    stdio.println(value);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "pending-rendezvous-channel.ores")
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
    void pendingStaticSelectResumesOnlyAfterWinningChannelRegistration() throws Exception {
        String program = """
                define module app
                  pub let Channel<int> input = Channel.new<int>(0);

                  pub async actor fnc producer(): void {
                    writech app.input, 73;
                    return;
                  }

                  async fnc wait(): int {
                    select {
                      case readch app.input: let value
                        return value;
                    }
                    return 0;
                  }

                  pub fnc main(): void {
                    val producer_done = producer();
                    val value = await wait();
                    await producer_done;
                    stdio.println(value);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "pending-static-select.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("73"));
    }

    @Test
    void awaitRejectsNonFutureExpressions() {
        String program = """
                define module app
                  pub fnc main(): void {
                    val value = await 42;
                    return;
                  }
                end
                """;

        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void asyncActorCallableUsesMailboxContinuationLowering() throws Exception {
        String program = """
                define module app
                  pub async actor fnc burn(int n): int {
                    return await immediate(n);
                  }

                  async fnc immediate(int n): int {
                    return n;
                  }

                  pub fnc main(): void {
                    val value = await burn(17);
                    stdio.println(value);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "async-actor-await.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("17"));
    }

    @Test
    void pendingAwaitResumesOnFreshSchedulerTurn() throws Exception {
        String program = """
                define module app
                  async fnc later(): int {
                    return await delayed();
                  }

                  async fnc delayed(): int {
                    return 23;
                  }

                  pub fnc main(): void {
                    stdio.println(await later());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pending-await.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("23"));
    }

    @Test
    void asyncMoveBoundaryRejectsCallerUseAfterTransfer() {
        String program = """
                define module app
                  define class Box as
                    pub let int value = 1;
                  end

                  async fnc change(Box mut box): int {
                    box.value = 99;
                    return box.value;
                  }

                  pub fnc main(): void {
                    let original = new Box();
                    val changed = await change(original);
                    stdio.println(original.value);
                    stdio.println(changed);
                    return;
                  }
                end
                """;

        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
    }

}