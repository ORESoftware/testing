package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

final class ConcurrencySyntaxContractTest {

    @Test
    void nbReadchAndNbWritechAreFutureReturningExpressions() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc arm(
                    Channel<int> input,
                    Channel<int> output
                ): void {
                  val Future<int> pending_read = nb readch input;
                  val Future<void> pending_write = nb writech output, 42;
                  return;
                }
                """)));
    }

    @Test
    void nbSelectIsActorOwnedAndReturnsImmediately() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc arm(Channel<int> input): void {
                  nb select {
                  case readch input: const value
                    stdio.println(value);
                  }
                  stdio.println("turn continues after arming select");
                  return;
                }
                """)));
    }

    @Disabled("CONTRACT GAP: add 'cb' syntax/lowering; callback must re-enter the owning actor scheduler turn")
    @Test
    void nbCbWritechIsVoidCallbackSurfaceRatherThanFutureSurface() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc arm(Channel<int> output): void {
                  nb cb writech output, 42 || -> {
                    stdio.println("write completed");
                  };
                  return;
                }
                """)));
    }
}
