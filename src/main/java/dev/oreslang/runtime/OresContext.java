package dev.oreslang.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.oreslang.OresLanguage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class OresContext implements AutoCloseable {
    private static final ContextReference<OresContext> REFERENCE = ContextReference.create(OresLanguage.class);

    private final OresLanguage language;
    private final TruffleLanguage.Env env;
    private final BufferedReader input;
    private final PrintWriter output;
    private final ActorRuntime actors;
    private final GcController gc;
    private final UUID contextId = UUID.randomUUID();
    private final AtomicLong schedulerSafepoints = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<CompletableFuture<?>, Thread> asyncTasks = new ConcurrentHashMap<>();
    private final Semaphore asyncPermits;
    private final IsolatePolicy isolatePolicy;
    private final ExecutionProfile executionProfile;

    public OresContext(OresLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.input = new BufferedReader(new InputStreamReader(env.in()));
        this.output = new PrintWriter(env.out(), true);
        this.isolatePolicy = IsolatePolicy.fromApplicationArguments(env.getApplicationArguments());
        this.executionProfile = IsolatePolicy.executionProfileFromApplicationArguments(env.getApplicationArguments());
        this.actors = new ActorRuntime(isolatePolicy);
        this.gc = new GcController(actors);
        this.asyncPermits = new Semaphore(Math.max(1, isolatePolicy.maxActors()), true);
    }

    public static OresContext get(Node node) {
        return REFERENCE.get(node);
    }

    public OresLanguage language() { return language; }
    public TruffleLanguage.Env env() { return env; }
    public BufferedReader input() { return input; }
    public PrintWriter output() { return output; }
    public ActorRuntime actors() { return actors; }
    public GcController gc() { return gc; }
    public UUID contextId() { return contextId; }
    public IsolatePolicy isolatePolicy() { return isolatePolicy; }
    public ExecutionProfile executionProfile() { return executionProfile; }

    public void requireCapability(IsolatePolicy.Capability capability, String api) {
        isolatePolicy.require(capability, api);
    }

    /**
     * Compiler-injected cooperative scheduling checkpoint. Loops call this on
     * every iteration so a future supervisor/control mailbox can interrupt
     * long-running actor code without requiring recursion-only looping.
     */
    public void schedulerSafepoint() {
        schedulerSafepoints.incrementAndGet();
        actors.schedulerSafepoint();
    }

    public long schedulerSafepoints() { return schedulerSafepoints.get(); }

    /**
     * Bounded structured-at-context async execution. Async callables use virtual
     * threads, but task creation is admission-controlled and every live task is
     * owned by this context so close() can cancel it.
     */
    public <T> CompletionStage<T> submitAsync(Callable<T> task) {
        java.util.Objects.requireNonNull(task, "task");
        if (closed.get()) throw new java.util.concurrent.CancellationException("Oreslang context is closed");
        if (!asyncPermits.tryAcquire()) {
            throw new IllegalStateException(
                    "async task limit exceeded for isolate: maxAsyncTasks=" + isolatePolicy.maxActors());
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Thread thread = Thread.ofVirtual().name("ores-async-" + contextId).unstarted(() -> {
                try {
                    if (closed.get()) throw new java.util.concurrent.CancellationException("Oreslang context is closing");
                    future.complete(task.call());
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                } finally {
                    asyncTasks.remove(future);
                    asyncPermits.release();
                }
            });
            asyncTasks.put(future, thread);
            if (closed.get()) {
                asyncTasks.remove(future);
                asyncPermits.release();
                future.cancel(true);
                throw new java.util.concurrent.CancellationException("Oreslang context is closing");
            }
            thread.start();
            return future;
        } catch (Throwable startFailure) {
            if (asyncTasks.remove(future) != null) asyncPermits.release();
            throw startFailure;
        }
    }

    public int activeAsyncTasks() {
        return asyncTasks.size();
    }

    public Map<String, Object> processDescriptor() {
        return Map.ofEntries(
                Map.entry("context_id", contextId.toString()),
                Map.entry("runtime", "graalvm-truffle"),
                Map.entry("language", "oreslang"),
                Map.entry("execution_mode", executionProfile.mode().name()),
                Map.entry("platform", executionProfile.platform().name()),
                Map.entry("scheduler_safepoints", schedulerSafepoints.get()),
                Map.entry("active_actors", actors.activeActorCount()),
                Map.entry("active_monitors", actors.activeMonitorCount()),
                Map.entry("max_actors", isolatePolicy.maxActors()),
                Map.entry("actor_heap_backend", "logical_jvm"),
                Map.entry("actor_physical_heap_isolation", false),
                Map.entry("manual_gc_requests", gc.requests()));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        var tasks = java.util.List.copyOf(asyncTasks.entrySet());
        for (Map.Entry<CompletableFuture<?>, Thread> entry : tasks) {
            entry.getKey().cancel(true);
            entry.getValue().interrupt();
        }

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        for (Map.Entry<CompletableFuture<?>, Thread> entry : tasks) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            try {
                long millis = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining));
                entry.getValue().join(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        asyncTasks.clear();
        actors.close();
        output.flush();
    }
}
