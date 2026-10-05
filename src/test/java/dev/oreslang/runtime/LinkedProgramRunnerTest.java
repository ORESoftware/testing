package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class LinkedProgramRunnerTest {

    @Test
    void sandboxSafeOutputForwardsWithoutClosingCallerOwnedStream()
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicBoolean targetClosed = new AtomicBoolean();

        OutputStream target = new OutputStream() {
            @Override
            public void write(int value) {
                bytes.write(value);
            }

            @Override
            public void write(byte[] source, int offset, int length) {
                bytes.write(source, offset, length);
            }

            @Override
            public void close() {
                targetClosed.set(true);
            }
        };

        OutputStream redirected = LinkedProgramRunner.sandboxSafeOutput(target);
        assertNotSame(target, redirected,
                "UNTRUSTED sandbox must not receive System.out/System.err directly");

        redirected.write("ores".getBytes(StandardCharsets.UTF_8));
        redirected.flush();
        redirected.close();

        assertEquals("ores", bytes.toString(StandardCharsets.UTF_8));
        assertFalse(targetClosed.get(),
                "closing a Context must not close a caller-owned launcher stream");
    }

    @Test
    void sandboxSafeOutputRejectsNullTarget() {
        assertThrows(
                NullPointerException.class,
                () -> LinkedProgramRunner.sandboxSafeOutput(null));
    }
}
