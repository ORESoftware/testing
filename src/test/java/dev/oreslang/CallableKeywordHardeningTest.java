package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CallableKeywordHardeningTest {
    @Test
    void canonicalFunctionKeywordIsFnc() {
        assertDoesNotThrow(() -> Parser.parse("pub fnc run(): void { return; }"));
        String legacy = "f" + "n";
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(("pub %s run(): void { return; }").formatted(legacy)));
        assertTrue(error.getMessage().contains("fnc"));
    }
}
