package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReturnedDestructuringTest {
    @Test
    void unionArraysAndBindingKindPropagationTypeCheck() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type intOrBoolOrString = bool | int | string;

                pub fnc mixed() => Array<type intOrBoolOrString> {
                  return [3, true, "yes"];
                }

                pub fnc main() => void {
                  [const number, flag, answer] = mixed();
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  return;
                }
                """)));
    }

    @Test
    void finiteTupleReturnCanUseListBackedValueAndPrefixConstPattern() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool, string] {
                  return [3, true, "yes"];
                }

                pub fnc main() => void {
                  const [number, flag, answer] = fixed();
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  return;
                }
                """)));
    }

    @Test
    void finiteTupleReturnRejectsWrongArityAndWrongSlotType() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken() => [int, bool, string] {
                  return [3, true];
                }

                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken() => [int, bool, string] {
                  return [3, "not-bool", "yes"];
                }

                pub fnc main() => void { return; }
                """)));
    }

    @Test
    void explicitLetChangesPropagationForRemainingSequenceBindings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool, string] {
                  return [3, true, "yes"];
                }

                pub fnc main() => void {
                  [const number, let flag, answer] = fixed();
                  flag = false;
                  answer = "no";
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool, string] {
                  return [3, true, "yes"];
                }

                pub fnc main() => void {
                  [const number, flag, answer] = fixed();
                  flag = false;
                  return;
                }
                """)));
    }

    @Test
    void recordReturnSupportsBothObjectDestructureSpellings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc result() => {foo: int, bar: string} {
                  return obj{foo: 5, bar: "x"};
                }

                fnc prefixed() => void {
                  const {foo, bar} = result();
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }

                fnc inlineKinds() => void {
                  {const foo, const bar} = result();
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }

                pub fnc main() => void {
                  prefixed();
                  inlineKinds();
                  return;
                }
                """)));
    }

    @Test
    void recordReturnsAndDestructuresRejectMissingOrWrongMembers() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken() => {foo: int, bar: string} {
                  return obj{foo: 5};
                }

                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken() => {foo: int, bar: string} {
                  return obj{foo: "wrong", bar: "x"};
                }

                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc result() => {foo: int, bar: string} {
                  return obj{foo: 5, bar: "x"};
                }

                pub fnc main() => void {
                  const {foo, missing} = result();
                  return;
                }
                """)));
    }

    @Test
    void equivalentUnionAndRecordOrderingsHaveCanonicalSignatures() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                @Ret<string | int>
                fnc scalar() => int | string {
                  return 3;
                }

                @Ret<{bar: string, foo: int}>
                fnc object() => {foo: int, bar: string} {
                  return obj{foo: 5, bar: "x"};
                }

                pub fnc main() => void { return; }
                """)));
    }

    @Test
    void prefixedPatternSyntaxDoesNotStealFiniteTupleOrRecordTypedBindings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool, string] {
                  return [3, true, "yes"];
                }

                fnc result() => {foo: int, bar: string} {
                  return obj{foo: 5, bar: "x"};
                }

                pub fnc main() => void {
                  val [int, bool, string] tupleValue = fixed();
                  val {foo: int, bar: string} recordValue = result();
                  stdio.println(tupleValue[0]);
                  stdio.println(recordValue.foo);
                  return;
                }
                """)));
    }

    @Test
    void unionTupleReturnsDestructureWhenEveryAlternativeHasCompatibleArity() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc variant(bool flag) => [int, string] | [bool, string] {
                  if flag; do
                    return [3, "number"];
                  else
                    return [true, "boolean"];
                  fi
                }

                pub fnc main() => void {
                  const [value, label] = variant(true);
                  stdio.println(value);
                  stdio.println(label);
                  return;
                }
                """)));
    }

    @Test
    void unionRecordReturnsDestructureWhenEveryAlternativeProvidesRequestedMembers() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc variant(bool flag) => {foo: int, bar: string} | {foo: bool, bar: string} {
                  if flag; do
                    return obj{foo: 3, bar: "number"};
                  else
                    return obj{foo: true, bar: "boolean"};
                  fi
                }

                pub fnc main() => void {
                  const {foo, bar} = variant(false);
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }
                """)));
    }

    @Test
    void unionDestructureRejectsIncompatibleArityOrMissingMembers() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc variant(bool flag) => [int, string] | [bool, string, int] {
                  if flag; do
                    return (3, "number");
                  else
                    return (true, "boolean", 9);
                  fi
                }

                pub fnc main() => void {
                  const [value, label] = variant(true);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc variant(bool flag) => {foo: int, bar: string} | {foo: bool} {
                  if flag; do
                    return obj{foo: 3, bar: "number"};
                  else
                    return obj{foo: true};
                  fi
                }

                pub fnc main() => void {
                  const {foo, bar} = variant(false);
                  return;
                }
                """)));
    }

    @Test
    void bareDiscardIsRejectedInObjectPatterns() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc result() => {foo: int, bar: string} {
                  return obj{foo: 5, bar: "x"};
                }

                pub fnc main() => void {
                  const {foo, _} = result();
                  return;
                }
                """));
    }

    @Test
    void unionTupleAndArrayReturnsExecuteThroughRuntimeShapeChecks() throws Exception {
        String program = """
                type Scalar = int | bool | string;

                fnc tupleVariant(bool flag) => [int, string] | [bool, string] {
                  if flag; do
                    return [3, "number"];
                  else
                    return [true, "boolean"];
                  fi
                }

                fnc values() => Array<Scalar> {
                  return [3, true, "yes"];
                }

                pub fnc main() => void {
                  const [value, label] = tupleVariant(false);
                  [const number, flag, answer] = values();
                  stdio.println(value);
                  stdio.println(label);
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "union-return-destructure.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("true"));
        assertTrue(text.contains("boolean"));
        assertTrue(text.contains("3"));
        assertTrue(text.contains("yes"));
    }

    @Test
    void returnedTupleAndRecordDestructureAtRuntime() throws Exception {
        String program = """
                type FixedResult = [int, bool, string];
                type NamedResult = {foo: int, bar: string};

                fnc fixed() => FixedResult {
                  return [3, true, "yes"];
                }

                fnc result() => NamedResult {
                  return obj{foo: 5, bar: "x"};
                }

                pub fnc main() => void {
                  const [number, flag, answer] = fixed();
                  const {foo, bar} = result();
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "returned-destructure.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("3"));
        assertTrue(text.contains("true"));
        assertTrue(text.contains("yes"));
        assertTrue(text.contains("5"));
        assertTrue(text.contains("x"));
    }
}
