package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GpuResidentDataLanguageTest {
    @Test
    void hostOrchestrationTypesRoundTripThroughGpuArrayAndStream() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc upload(Array<i32> values) => GpuArray<i32> {
                  return GpuArray.from_cpu(values);
                }

                fnc as_stream(GpuArray<i32> values) => GpuStream<i32> {
                  return values.stream();
                }

                fnc collect(GpuStream<i32> values) => GpuArray<i32> {
                  return values.collect();
                }

                fnc download(GpuArray<i32> values) => Array<i32> {
                  return values.copy_to_cpu();
                }
                """)));
    }

    @Test
    void gpuArrayIndexingIsDeviceOnly() {
        IllegalArgumentException hostFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(GpuArray<i32> values) => i32 {
                          return values[0];
                        }
                        """)));
        assertTrue(hostFailure.getMessage().contains("GpuArray indexing is GPU-only"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                gpu fnc first(GpuArray<i32> values) => i32 {
                  return values[0];
                }
                """)));
    }

    @Test
    void gpuStreamIsSequentialAndHostCannotConsumeItDirectly() {
        IllegalArgumentException hostFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(GpuStream<i32> values) => i32 {
                          for (val value of values) {
                            return value;
                          }
                          return 0;
                        }
                        """)));
        assertTrue(hostFailure.getMessage().contains("GpuStream consumption is GPU-only"));

        IllegalArgumentException indexFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        gpu fnc bad(GpuStream<i32> values) => i32 {
                          return values[0];
                        }
                        """)));
        assertTrue(indexFailure.getMessage().contains("GpuStream is sequential and cannot be indexed"));
    }

    @Test
    void transferAndOrchestrationCallsAreForbiddenInsideGpuCode() {
        IllegalArgumentException upload = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        gpu fnc bad(Array<i32> values) => GpuArray<i32> {
                          return GpuArray.from_cpu(values);
                        }
                        """)));
        assertTrue(upload.getMessage().contains("cannot run inside a gpu callable"));

        IllegalArgumentException download = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        gpu fnc bad(GpuArray<i32> values) => Array<i32> {
                          return values.copy_to_cpu();
                        }
                        """)));
        assertTrue(download.getMessage().contains("host orchestration"));
    }

    @Test
    void gpuCoreResourceNamesCannotBeShadowed() {
        IllegalArgumentException klass = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class GpuArray
                        end
                        """)));
        assertTrue(klass.getMessage().contains("reserved by the Oreslang GPU core prelude"));

        IllegalArgumentException alias = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        type GpuStream = i32;
                        """)));
        assertTrue(alias.getMessage().contains("reserved by the Oreslang GPU core prelude"));
    }

    @Test
    void residentElementsFailClosedOutsideCurrentDeviceScalarAbi() {
        IllegalArgumentException stringType = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Array<string> values) => GpuArray<string> {
                          return GpuArray.from_cpu(values);
                        }
                        """)));
        assertTrue(stringType.getMessage().contains("current device ABI"));

        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Array<Array<i32>> values) => GpuArray<Array<i32>> {
                          return GpuArray.from_cpu(values);
                        }
                        """)));
        assertTrue(nested.getMessage().contains("current device ABI"));
    }
}
