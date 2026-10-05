package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ActorTimerWheelTest {

    @Test
    void constructionAndIdleCloseDoNotStartHostTimerThread() {
        ActorTimerWheel wheel =
                new ActorTimerWheel("test-lazy-timer-", Duration.ofMillis(1), 64);

        assertFalse(
                wheel.driverStarted(),
                "process VM construction must not eagerly create a timer driver");

        wheel.close();

        assertFalse(
                wheel.driverStarted(),
                "closing an unused wheel must not create a timer driver");
        assertThrows(
                IllegalStateException.class,
                () -> wheel.schedule(Duration.ofMillis(1), () -> { }));
    }

    @Test
    void firstRealTimerStartsDriverAndStillFires() throws Exception {
        try (ActorTimerWheel wheel =
                     new ActorTimerWheel(
                             "test-lazy-timer-",
                             Duration.ofMillis(1),
                             64)) {
            CountDownLatch fired = new CountDownLatch(1);

            assertFalse(wheel.driverStarted());

            ActorTimerWheel.Handle handle =
                    wheel.schedule(Duration.ofMillis(5), fired::countDown);

            assertTrue(
                    wheel.driverStarted(),
                    "the first actual timer should lazily start the driver");
            assertTrue(fired.await(2, TimeUnit.SECONDS));
            assertTrue(handle.isDone());
            assertFalse(handle.isCancelled());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (wheel.pendingCount() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(0L, wheel.pendingCount());
        }
    }
}
