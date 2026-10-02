package dev.oreslang.gpu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GpuRuntimeTest {
    @AfterEach
    void clearBackend() {
        GpuRuntime.clearBackend();
    }

    @Test
    void neverFallsBackToCpuWhenNoBackendIsInstalled() {
        GpuRuntime runtime = new GpuRuntime();
        GpuRuntime.GpuUnavailableException failure = assertThrows(
                GpuRuntime.GpuUnavailableException.class,
                () -> runtime.dispatch("math.add", GpuRuntime.CallableKind.FNC, List.of(1L, 2L)));
        assertTrue(failure.getMessage().contains("CPU fallback is forbidden"));
    }

    @Test
    void freezesGuestCollectionsBeforeBackendInvocation() {
        ArrayList<Object> nested = new ArrayList<>();
        nested.add(7L);
        ArrayList<Object> original = new ArrayList<>();
        original.add(nested);

        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "freeze-test"; }

            @Override
            public Object invoke(GpuRuntime.Invocation invocation) {
                List<?> outer = assertInstanceOf(List.class, invocation.arguments().getFirst());
                List<?> inner = assertInstanceOf(List.class, outer.getFirst());
                assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) outer).add(9L));
                assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) inner).add(9L));
                nested.set(0, 99L);
                assertEquals(7L, inner.getFirst());
                return 41L;
            }
        });

        assertEquals(41L, new GpuRuntime().dispatch(
                "test.freeze", GpuRuntime.CallableKind.FNC, List.of(original)));
    }

    @Test
    void deviceHandlesRemainBoundToCreatingBackend() {
        GpuRuntime.Backend first = new TransferBackend("first");
        GpuRuntime.Backend second = new TransferBackend("second");

        GpuRuntime.installBackend(first);
        GpuRuntime.ArrayHandle array = new GpuRuntime().uploadArray(List.of(1L, 2L));

        GpuRuntime.installBackend(second);
        GpuRuntime.GpuTransferException failure = assertThrows(
                GpuRuntime.GpuTransferException.class,
                () -> new GpuRuntime().dispatch(
                        "test.consume", GpuRuntime.CallableKind.FNC, List.of(array)));
        assertTrue(failure.getMessage().contains("belongs to backend 'first'"));
    }

    @Test
    void streamCollectPreservesBackendAndConcreteCardinality() {
        GpuRuntime.installBackend(new TransferBackend("stream-test"));
        GpuRuntime runtime = new GpuRuntime();

        GpuRuntime.ArrayHandle uploaded = runtime.uploadArray(List.of(1L, 2L, 3L));
        GpuRuntime.StreamHandle stream = runtime.stream(uploaded);
        GpuRuntime.ArrayHandle collected = runtime.collect(stream);

        assertEquals("stream-test", stream.backendName());
        assertEquals(3L, stream.lengthHint());
        assertEquals("stream-test", collected.backendName());
        assertEquals(3L, collected.length());
        assertEquals(List.of(1L, 2L, 3L), runtime.downloadArray(collected));
    }

    @Test
    void rejectsBackendDownloadLengthMismatch() {
        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "bad-length"; }
            @Override public Object invoke(GpuRuntime.Invocation invocation) { return null; }
            @Override public Object uploadArray(List<?> values) { return List.copyOf(values); }
            @Override public List<?> downloadArray(Object token, long length) { return List.of(1L); }
        });

        GpuRuntime runtime = new GpuRuntime();
        GpuRuntime.ArrayHandle array = runtime.uploadArray(List.of(1L, 2L));

        GpuRuntime.GpuTransferException failure = assertThrows(
                GpuRuntime.GpuTransferException.class,
                () -> runtime.downloadArray(array));
        assertTrue(failure.getMessage().contains("returned 1 element(s) for GpuArray length 2"));
    }

    @Test
    void wrapsUnexpectedBackendFailuresWithoutMaskingGpuExceptions() {
        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "boom"; }
            @Override public Object invoke(GpuRuntime.Invocation invocation) {
                throw new IllegalStateException("driver exploded");
            }
        });

        GpuRuntime.GpuExecutionException failure = assertThrows(
                GpuRuntime.GpuExecutionException.class,
                () -> new GpuRuntime().dispatch(
                        "test.boom", GpuRuntime.CallableKind.FNC, List.of(1L)));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("invoke(test.boom)"));
    }

    private record TransferBackend(String name) implements GpuRuntime.Backend {
        @Override public Object invoke(GpuRuntime.Invocation invocation) { return null; }
        @Override public Object uploadArray(List<?> values) { return List.copyOf(values); }
        @Override public List<?> downloadArray(Object token, long length) { return (List<?>) token; }
    }
}
