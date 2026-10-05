package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class StaticFncKeywordTest {
    @Test
    void staticFunctionsRequireFnc() {
        String legacy = "f" + "n";
        var error = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(("define class C as pub static %s build(): void { return; } end").formatted(legacy)));
        assertTrue(error.getMessage().contains("static fnc"));
        assertDoesNotThrow(() ->
                Parser.parse("define class C as pub static fnc build(): void { return; } end"));
    }
}
