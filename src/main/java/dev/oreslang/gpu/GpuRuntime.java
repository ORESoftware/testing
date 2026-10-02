package dev.oreslang.gpu;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Process-wide GPU backend SPI for Oreslang embeddings.
 *
 * GPU placement is mandatory: this runtime never evaluates a gpu callable on
 * the CPU as a fallback. Device resources are opaque and remain permanently
 * bound to the backend that created them.
 */
public final class GpuRuntime {
    private static final AtomicReference<Backend> PROCESS_BACKEND = new AtomicReference<>();

    public enum CallableKind { FNC, ROUTINE }

    /** Marker for private guest-runtime values that expose a safe GPU wire form. */
    public interface Transferable {
        Object freezeForGpu();
    }

    /** Stable public representation for Oreslang complex scalar values. */
    public record ComplexValue(double real, double imaginary) { }

    /** Stable public representation for Oreslang Option values. */
    public record OptionValue(boolean present, Object value) {
        public OptionValue {
            if (present && value == null) {
                throw new IllegalArgumentException("Some cannot carry standalone null");
            }
            if (!present && value != null) {
                throw new IllegalArgumentException("None cannot carry a value");
            }
        }
    }

    /** Backend result for collecting a stream into a concrete device array. */
    public record CollectedArray(Object backendToken, long length) {
        public CollectedArray {
            Objects.requireNonNull(backendToken, "backendToken");
            if (length < 0) {
                throw new IllegalArgumentException("collected GpuArray length cannot be negative");
            }
        }
    }

    public interface Backend {
        String name();

        default boolean supports(Invocation invocation) {
            return true;
        }

        Object invoke(Invocation invocation);

        default Object uploadArray(List<?> values) {
            throw new GpuCapabilityUnavailableException(
                    "GPU backend '" + name() + "' does not support GpuArray uploads");
        }

        default List<?> downloadArray(Object backendToken, long length) {
            throw new GpuCapabilityUnavailableException(
                    "GPU backend '" + name() + "' does not support GpuArray downloads");
        }

        default Object openStream(Object arrayBackendToken, long length) {
            return arrayBackendToken;
        }

        default CollectedArray collectStream(Object streamBackendToken, long lengthHint) {
            if (lengthHint < 0) {
                throw new GpuCapabilityUnavailableException(
                        "GPU backend '" + name()
                                + "' must report a concrete length when collecting an unknown-length GpuStream");
            }
            return new CollectedArray(streamBackendToken, lengthHint);
        }
    }

    /** Opaque GPU-resident array resource. Guest code cannot access backendToken. */
    public static final class ArrayHandle {
        private final Backend backend;
        private final Object backendToken;
        private final long length;

        private ArrayHandle(Backend backend, Object backendToken, long length) {
            this.backend = Objects.requireNonNull(backend, "backend");
            this.backendToken = Objects.requireNonNull(backendToken, "backendToken");
            if (length < 0) throw new IllegalArgumentException("GpuArray length cannot be negative");
            this.length = length;
        }

        public String backendName() { return backend.name(); }
        public long length() { return length; }

        /** Host/backend SPI only; Oreslang guest code has no reflective host access. */
        public Object backendToken() { return backendToken; }
    }

    /** Opaque GPU-resident sequential stream resource. */
    public static final class StreamHandle {
        private final Backend backend;
        private final Object backendToken;
        private final long lengthHint;

        private StreamHandle(Backend backend, Object backendToken, long lengthHint) {
            this.backend = Objects.requireNonNull(backend, "backend");
            this.backendToken = Objects.requireNonNull(backendToken, "backendToken");
            if (lengthHint < -1) {
                throw new IllegalArgumentException("GpuStream length hint must be -1 or non-negative");
            }
            this.lengthHint = lengthHint;
        }

        public String backendName() { return backend.name(); }
        public long lengthHint() { return lengthHint; }

        /** Host/backend SPI only; Oreslang guest code has no reflective host access. */
        public Object backendToken() { return backendToken; }
    }

    public record Invocation(String callable, CallableKind callableKind, List<?> arguments) {
        public Invocation {
            Objects.requireNonNull(callable, "callable");
            Objects.requireNonNull(callableKind, "callableKind");
            arguments = freezeArguments(Objects.requireNonNull(arguments, "arguments"));
        }
    }

    public static void installBackend(Backend backend) {
        PROCESS_BACKEND.set(Objects.requireNonNull(backend, "backend"));
    }

    public static void clearBackend() {
        PROCESS_BACKEND.set(null);
    }

    public static ArrayHandle adoptArray(Backend backend, Object backendToken, long length) {
        return new ArrayHandle(backend, backendToken, length);
    }

    public static StreamHandle adoptStream(Backend backend, Object backendToken, long lengthHint) {
        return new StreamHandle(backend, backendToken, lengthHint);
    }

    public boolean available() {
        return PROCESS_BACKEND.get() != null;
    }

    public String backendName() {
        Backend backend = PROCESS_BACKEND.get();
        return backend == null ? "unavailable" : backend.name();
    }

    public Object dispatch(String callable, CallableKind callableKind, List<?> arguments) {
        Invocation invocation = new Invocation(callable, callableKind, arguments);
        Backend backend = requireBackend("gpu callable '" + callable + "'");
        validateDeviceOwnership(invocation.arguments(), backend);

        boolean supported = backendCall(backend, "supports(" + callable + ")",
                () -> backend.supports(invocation));
        if (!supported) {
            throw new GpuCapabilityUnavailableException(
                    "GPU backend '" + backend.name() + "' cannot execute gpu callable '" + callable + "'");
        }

        Object result = backendCall(backend, "invoke(" + callable + ")", () -> backend.invoke(invocation));
        Object frozen = freezeValue(result, true);
        validateDeviceOwnership(frozen, backend);
        return frozen;
    }

    public ArrayHandle uploadArray(List<?> values) {
        Objects.requireNonNull(values, "values");
        Backend backend = requireBackend("GpuArray.from_cpu()");
        List<?> frozen = freezeArguments(values);
        Object token = backendCall(backend, "uploadArray", () -> backend.uploadArray(frozen));
        if (token == null) {
            throw new GpuExecutionException(
                    "GPU backend '" + backend.name() + "' returned null from uploadArray", null);
        }
        return new ArrayHandle(backend, token, frozen.size());
    }

    public List<?> downloadArray(ArrayHandle array) {
        Objects.requireNonNull(array, "array");
        if (array.length > Integer.MAX_VALUE) {
            throw new GpuTransferException(
                    "GpuArray length " + array.length + " cannot be materialized as a host Array/List");
        }
        List<?> downloaded = backendCall(array.backend, "downloadArray",
                () -> array.backend.downloadArray(array.backendToken, array.length));
        Object frozen = freezeValue(downloaded, false);
        if (!(frozen instanceof List<?> list)) {
            throw new GpuTransferException(
                    "GPU backend '" + array.backend.name() + "' downloadArray must return a List");
        }
        if (list.size() != (int) array.length) {
            throw new GpuTransferException(
                    "GPU backend '" + array.backend.name() + "' returned " + list.size()
                            + " element(s) for GpuArray length " + array.length);
        }
        return list;
    }

    public StreamHandle stream(ArrayHandle array) {
        Objects.requireNonNull(array, "array");
        Object token = backendCall(array.backend, "openStream",
                () -> array.backend.openStream(array.backendToken, array.length));
        if (token == null) {
            throw new GpuExecutionException(
                    "GPU backend '" + array.backend.name() + "' returned null from openStream", null);
        }
        return new StreamHandle(array.backend, token, array.length);
    }

    public ArrayHandle collect(StreamHandle stream) {
        Objects.requireNonNull(stream, "stream");
        CollectedArray collected = backendCall(stream.backend, "collectStream",
                () -> stream.backend.collectStream(stream.backendToken, stream.lengthHint));
        if (collected == null) {
            throw new GpuExecutionException(
                    "GPU backend '" + stream.backend.name() + "' returned null from collectStream", null);
        }
        return new ArrayHandle(stream.backend, collected.backendToken(), collected.length());
    }

    public List<?> downloadStream(StreamHandle stream) {
        return downloadArray(collect(stream));
    }

    private Backend requireBackend(String operation) {
        Backend backend = PROCESS_BACKEND.get();
        if (backend == null) {
            throw new GpuUnavailableException(
                    operation + " requires a GPU backend; none is installed and CPU fallback is forbidden");
        }
        return backend;
    }

    private static <T> T backendCall(Backend backend, String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (GpuException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new GpuExecutionException(
                    "GPU backend '" + backend.name() + "' failed during " + operation, failure);
        }
    }

    private static void validateDeviceOwnership(Object value, Backend expected) {
        if (value == null) return;
        if (value instanceof ArrayHandle array) {
            if (array.backend != expected) {
                throw new GpuTransferException(
                        "GpuArray belongs to backend '" + array.backend.name()
                                + "' but callable is executing on backend '" + expected.name() + "'");
            }
            return;
        }
        if (value instanceof StreamHandle stream) {
            if (stream.backend != expected) {
                throw new GpuTransferException(
                        "GpuStream belongs to backend '" + stream.backend.name()
                                + "' but callable is executing on backend '" + expected.name() + "'");
            }
            return;
        }
        if (value instanceof OptionValue option) {
            if (option.present()) validateDeviceOwnership(option.value(), expected);
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) validateDeviceOwnership(item, expected);
            return;
        }
        if (value instanceof Set<?> set) {
            for (Object item : set) validateDeviceOwnership(item, expected);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                validateDeviceOwnership(entry.getKey(), expected);
                validateDeviceOwnership(entry.getValue(), expected);
            }
        }
    }

    private static List<?> freezeArguments(List<?> arguments) {
        ArrayList<Object> frozen = new ArrayList<>(arguments.size());
        for (Object argument : arguments) frozen.add(freezeValue(argument, false));
        return List.copyOf(frozen);
    }

    private static Object freezeValue(Object value, boolean allowRootVoidNull) {
        if (value == null) {
            if (allowRootVoidNull) return null;
            throw new GpuTransferException(
                    "standalone null cannot cross the Oreslang GPU boundary; use Option");
        }

        if (value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal || value instanceof Enum<?>
                || value instanceof UUID || value instanceof ComplexValue
                || value instanceof ArrayHandle || value instanceof StreamHandle) {
            return value;
        }

        if (value instanceof OptionValue option) {
            return option.present()
                    ? new OptionValue(true, freezeValue(option.value(), false))
                    : new OptionValue(false, null);
        }

        if (value instanceof Transferable transferable) {
            Object transferred = transferable.freezeForGpu();
            if (transferred == value) {
                throw new GpuTransferException(
                        "GPU transferable values must produce a distinct wire representation");
            }
            return freezeValue(transferred, false);
        }

        if (value instanceof List<?> list) {
            ArrayList<Object> frozen = new ArrayList<>(list.size());
            for (Object item : list) frozen.add(freezeValue(item, false));
            return List.copyOf(frozen);
        }

        if (value instanceof Set<?> set) {
            LinkedHashSet<Object> frozen = new LinkedHashSet<>();
            for (Object item : set) frozen.add(freezeValue(item, false));
            return Collections.unmodifiableSet(frozen);
        }

        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> frozen = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                frozen.put(freezeValue(entry.getKey(), false), freezeValue(entry.getValue(), false));
            }
            return Collections.unmodifiableMap(frozen);
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            ArrayList<Object> frozen = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                frozen.add(freezeValue(Array.get(value, i), false));
            }
            return List.copyOf(frozen);
        }

        throw new GpuTransferException(
                "value of type " + value.getClass().getName()
                        + " cannot cross the Oreslang GPU boundary");
    }

    public static class GpuException extends RuntimeException {
        public GpuException(String message) { super(message); }
        public GpuException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class GpuUnavailableException extends GpuException {
        public GpuUnavailableException(String message) { super(message); }
    }

    public static final class GpuCapabilityUnavailableException extends GpuException {
        public GpuCapabilityUnavailableException(String message) { super(message); }
    }

    public static final class GpuExecutionException extends GpuException {
        public GpuExecutionException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class GpuTransferException extends GpuException {
        public GpuTransferException(String message) { super(message); }
    }
}
