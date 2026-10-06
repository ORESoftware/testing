package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ReservedKeywordsDynamicStructTest {
    @Test
    void reservedWordsRemainCallableNamesAndMapKeysOnly() {
        var tokens = new Lexer("stop do done").scan();
        assertEquals(Token.Type.STOP, tokens.get(0).type());
        assertEquals(Token.Type.DO, tokens.get(1).type());
        assertEquals(Token.Type.DONE, tokens.get(2).type());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc stop(): int { return 1; }
                  routine do(): int { return 2; }
                  fnc done(): int { return 3; }

                  pub fnc main(): int {
                    val values = obj{stop: 4, 'do': 5, "done": 6};
                    return stop() + do() + done()
                        + values["stop"] + values["do"] + values["done"];
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc stop(): int { return 1; }
                  fnc main(): void {
                    val callback = stop;
                    return;
                  }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc main(): void {
                    val stop = 1;
                    return;
                  }
                end
                """));
    }

    @Test
    void objectKeysSupportSingleDoubleQuotedReservedAndBacktickForms() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc make(string key): DynamicStruct<int> {
                    return obj{
                      stop: 1,
                      'do': 2,
                      "done": 3,
                      `key`: 4
                    };
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(): void {
                    const key = 42;
                    const value = obj{`key`: 1};
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(): void {
                    const value = obj{'same': 1, "same": 2};
                    return;
                  }
                end
                """)));
    }

    @Test
    void dynamicStructAllowsArbitraryStringKeysWithTypedValuesAtRuntime() throws Exception {
        String program = """
                define module app
                  pub fnc main(): void {
                    let DynamicStruct<int> bag = new DynamicStruct<int>();
                    bag["stop"] = 1;
                    bag["done"] = 2;
                    const key = "do";
                    bag[key] = 3;
                    stdio.println(bag["stop"] + bag["done"] + bag[key]);
                    return;
                  }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "dynamic-struct.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("6"));
    }

    @Test
    void dynamicStructRejectsWrongValueAndNonStringIndexTypes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(): void {
                    let DynamicStruct<int> bag = new DynamicStruct<int>();
                    bag.answer = "forty-two";
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(): void {
                    let DynamicStruct<int> bag = new DynamicStruct<int>();
                    bag[1] = 42;
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(): void {
                    let DynamicStruct<void> bag = new DynamicStruct<void>();
                    return;
                  }
                end
                """)));
    }
}
