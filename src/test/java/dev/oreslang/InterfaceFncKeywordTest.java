package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class InterfaceFncKeywordTest {
    @Test
    void interfaceFunctionsRequireFnc() {
        String legacy = "f" + "n";
        var error = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(("define interface Api %s run() => void; end").formatted(legacy)));
        assertTrue(error.getMessage().contains("fnc"));
        assertDoesNotThrow(() ->
                Parser.parse("define interface Api fnc run() => void; end"));
    }
}
