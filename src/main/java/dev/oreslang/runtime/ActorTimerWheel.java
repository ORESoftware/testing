package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process/runtime timer driver for actor continuations.
 *
 * This is a hashed timing wheel rather than one scheduled host task per actor
 * timer. The single driver thread only advances wheel slots and invokes small
 * runtime callbacks that enqueue actor work; it never executes guest actor code.
 */
final class ActorTimerWheel implements AutoCloseable {
    static final Duration DEFAULT_TICK = Duration.ofMillis(1);
    static final int DEFAULT_WHEEL_SIZE = 512;

    static final class Handle {
        private final TimerTask task;

        private Handle(TimerTask task) {
            this.task = task;
        }

        boolean cancel() {
            return task.cancel();
        }

        boolean isCancelled() {
            return task.state.get() == TimerTask.CANCELLED;
        }

        boolean isDone() {
            return task.state.get() != TimerTask.PENDING;
        }
    }

    private static final class TimerTask {
        private static final int PENDING = 0;
        private static final int FIRED = 1;
        private static final int CANCELLED = 2;

        private final long deadlineTick;
        private final Runnable callback;
        private final AtomicInteger state = new AtomicInteger(PENDING);
        private final AtomicLong pendingCounter;

        private TimerTask(long deadlineTick, Runnable callback, AtomicLong pendingCounter) {
            this.deadlineTick = deadlineTick;
            this.callback = callback;
            this.pendingCounter = pendingCounter;
        }

        private boolean cancel() {
            if (!state.compareAndSet(PENDING, CANCELLED)) return false;
            pendingCounter.decrementAndGet();
            return true;
        }

        private void fire() {
            if (!state.compareAndSet(PENDING, FIRED)) return;
            pendingCounter.decrementAndGet();
            callback.run();
        }
    }

    private final long tickNanos;
    private final int mask;
    private final ConcurrentLinkedQueue<TimerTask>[] slots;
    private final AtomicLong currentTick = new AtomicLong();
    private final AtomicLong pending = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadFactory driverThreadFactory;
    private volatile ScheduledThreadPoolExecutor driver;

    @SuppressWarnings("unchecked")
    ActorTimerWheel(String threadPrefix) {
        this(threadPrefix, DEFAULT_TICK, DEFAULT_WHEEL_SIZE);
    }

    @SuppressWarnings("unchecked")
    ActorTimerWheel(String threadPrefix, Duration tick, int wheelSize) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(tick, "tick");
        if (tick.isZero() || tick.isNegative()) {
            throw new IllegalArgumentException("timer-wheel tick must be positive");
        }
        if (wheelSize < 2 || Integer.bitCount(wheelSize) != 1) {
            throw new IllegalArgumentException("timer-wheel size must be a power of two >= 2");
        }
        long nanos;
        try {
            nanos = tick.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timer-wheel tick is too large", overflow);
        }
        if (nanos <= 0) throw new IllegalArgumentException("timer-wheel tick must be >= 1ns");
        this.tickNanos = nanos;
        this.mask = wheelSize - 1;
        this.slots = (ConcurrentLinkedQueue<TimerTask>[]) new ConcurrentLinkedQueue<?>[wheelSize];
        for (int i = 0; i < wheelSize; i++) slots[i] = new ConcurrentLinkedQueue<>();

        this.driverThreadFactory = task -> {
            Thread thread = new Thread(task, threadPrefix + "1");
            thread.setDaemon(true);
            return thread;
        };

        // Do not create or start a driver thread here. ActorTimerWheel is owned
        // by the process OresVM, which is itself a static runtime-kernel object.
        // Eager scheduling from this constructor would let GraalVM Native Image
        // capture a live build-host timer thread in the image heap.
        //
        // The first actual timer lazily starts the single driver instead.
        this.driver = null;
    }

    Handle schedule(Duration delay, Runnable callback) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(callback, "callback");
        if (delay.isNegative()) throw new IllegalArgumentException("timer delay cannot be negative");
        if (closed.get()) throw new IllegalStateException("timer wheel is closed");

        // Starting the driver is itself side-effectful (it creates a host
        // thread), so defer it until there is real timer work.
        ensureDriver();

        long delayNanos;
        try {
            delayNanos = delay.toNanos();
        } catch (ArithmeticException overflow) {
            delayNanos = Long.MAX_VALUE;
        }
        long ticks = Math.max(1L, ceilDivSaturated(delayNanos, tickNanos));
        long now = currentTick.get();
        long deadline = saturatedAdd(now, ticks);
        TimerTask task = new TimerTask(deadline, callback, pending);
        pending.incrementAndGet();
        slots[(int) (deadline & mask)].offer(task);

        if (closed.get() && task.cancel()) {
            throw new IllegalStateException("timer wheel is closed");
        }
        return new Handle(task);
    }

    long pendingCount() {
        return pending.get();
    }

    long currentTick() {
        return currentTick.get();
    }

    boolean driverStarted() {
        return driver != null;
    }

    private synchronized ScheduledThreadPoolExecutor ensureDriver() {
        if (closed.get()) {
            throw new IllegalStateException("timer wheel is closed");
        }

        ScheduledThreadPoolExecutor existing = driver;
        if (existing != null) return existing;

        ScheduledThreadPoolExecutor created =
                new ScheduledThreadPoolExecutor(1, driverThreadFactory);
        created.setRemoveOnCancelPolicy(true);
        created.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        created.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

        // Publish before scheduling. close() synchronizes on the same monitor,
        // so once the periodic task can create a worker the executor is already
        // reachable for shutdown.
        driver = created;
        try {
            created.scheduleAtFixedRate(
                    this::advanceOneTick,
                    tickNanos,
                    tickNanos,
                    TimeUnit.NANOSECONDS);
        } catch (RuntimeException | Error failure) {
            driver = null;
            created.shutdownNow();
            throw failure;
        }
        return created;
    }

    private void advanceOneTick() {
        if (closed.get()) return;
        long tick = currentTick.incrementAndGet();
        ConcurrentLinkedQueue<TimerTask> slot = slots[(int) (tick & mask)];

        // Only inspect tasks that were present when this slot visit began.
        // Long-delay tasks remain in the same bucket until a later wheel turn.
        int scan = slot.size();
        for (int i = 0; i < scan; i++) {
            TimerTask task = slot.poll();
            if (task == null) break;
            int state = task.state.get();
            if (state != TimerTask.PENDING) continue;
            if (task.deadlineTick <= tick) {
                try {
                    task.fire();
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable ignored) {
                    // Actor timer callbacks are scheduler notifications only.
                    // A bad callback must not kill the shared timer driver.
                }
            } else {
                slot.offer(task);
            }
        }
    }

    private static long ceilDivSaturated(long value, long divisor) {
        if (value <= 0) return 1L;
        long quotient = value / divisor;
        long remainder = value % divisor;
        if (remainder == 0) return quotient;
        return quotient == Long.MAX_VALUE ? Long.MAX_VALUE : quotient + 1L;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        ScheduledThreadPoolExecutor existing;
        synchronized (this) {
            existing = driver;
            driver = null;
        }
        if (existing != null) existing.shutdownNow();

        for (ConcurrentLinkedQueue<TimerTask> slot : slots) {
            TimerTask task;
            while ((task = slot.poll()) != null) task.cancel();
        }
    }
}
