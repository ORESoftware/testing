package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorFncKeywordTest {
    @Test
    void actorFunctionsRequireFnc() {
        String legacy = "f" + "n";
        var error = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(("actor %s work(): void { return; }").formatted(legacy)));
        assertTrue(error.getMessage().contains("actor fnc"));
        assertDoesNotThrow(() ->
                Parser.parse("actor fnc work(): void { return; }"));
    }
}
