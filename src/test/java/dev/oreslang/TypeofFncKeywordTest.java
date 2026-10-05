package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TypeofFncKeywordTest {
    @Test
    void functionTypesRequireTypeofFnc() {
        String legacy = "f" + "n";
        var error = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(("type F = typeof %s() => void; pub routine main(): void { return; }").formatted(legacy)));
        assertTrue(error.getMessage().contains("typeof fnc"));
        assertDoesNotThrow(() ->
                Parser.parse("type F = typeof fnc() => void; pub routine main(): void { return; }"));
    }
}
