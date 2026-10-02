package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class GpuExecutionTest {
    @Test
    void parsesNamedGpuFunctionAndContextualParallelBatch() {
        Ast.Program named = Parser.parse("""
                define module math
                  gpu fnc add(i32 a, i32 b) => i32 {
                    return a + b;
                  }
                end
                """);

        Ast.FunctionDecl add = (Ast.FunctionDecl) named.modules().getFirst().declarations().getFirst();
        assertTrue(Ast.hasGpuPlacement(add.annotations()));

        Ast.Program batched = Parser.parse("""
                define module app
                  fnc main() => void {
                    const parallel = 7;
                    const funcs = gpu parallel List.of(
                      || -> { return; },
                      || -> { return; },
                      || -> { return; }
                    );
                    return;
                  }
                end
                """);

        Ast.FunctionDecl main = (Ast.FunctionDecl) batched.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt binding = (Ast.BindingStmt) main.body().get(1);
        Ast.GpuExpr gpu = assertInstanceOf(Ast.GpuExpr.class, binding.initializer());
        assertEquals(Ast.GpuMode.PARALLEL, gpu.mode());
        Ast.CallExpr listOf = assertInstanceOf(Ast.CallExpr.class, gpu.expression());
        assertEquals(3, listOf.arguments().size());
        assertTrue(listOf.arguments().stream().allMatch(Ast.LambdaExpr.class::isInstance));
    }

    @Test
    void admitsAConservativeGpuFunction() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define module math
                  gpu fnc add(i32 a, i32 b) => i32 {
                    let i32 sum = a + b;
                    return sum;
                  }
                end
                """));
    }

    @Test
    void rejectsCpuCallsFromGpuCode() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module math
                          fnc cpu(i32 x) => i32 {
                            return x + 1;
                          }

                          gpu fnc kernel(i32 x) => i32 {
                            return cpu(x);
                          }
                        end
                        """));

        assertTrue(failure.getMessage().contains("only statically known gpu functions"));
    }

    @Test
    void rejectsHostCapturesInGpuBatchLambdas() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module app
                          fnc main() => void {
                            const i32 hostValue = 7;
                            const funcs = gpu parallel [
                              || -> { return hostValue; },
                              || -> { return; }
                            ];
                            return;
                          }
                        end
                        """));

        assertTrue(failure.getMessage().contains("cannot capture or read host/global value"));
    }

    @Test
    void rejectsDynamicMembersOfGpuParallelBatches() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module app
                          fnc main() => void {
                            const f = || -> { return; };
                            const funcs = gpu parallel [f];
                            return;
                          }
                        end
                        """));

        assertTrue(failure.getMessage().contains("only direct lambda literals"));
    }

    @Test
    void rejectsGpuAsyncAndMisplacedGpuModifiers() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  gpu async fnc bad() => void { return; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  gpu define class Bad
                  end
                end
                """));
    }


    @Test
    void gpuLambdaParametersRequireExplicitDeviceAbiTypes() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module app
                          fnc main() => void {
                            const work = gpu |x| -> {
                              return;
                            };
                            return;
                          }
                        end
                        """));

        assertTrue(failure.getMessage().contains("explicit GPU-transferable type"));
    }

    @Test
    void gpuParallelDirectLambdaBatchIsAValidConstInitializer() {
        assertDoesNotThrow(() -> OresCompiler.compile("""
                define module app
                  fnc host() => void {
                    const funcs = gpu parallel List.of(
                      || -> { return; },
                      || -> { return; },
                      || -> { return; }
                    );
                    return;
                  }
                end
                """));
    }

    @Test
    void gpuForInitializerBindingRemainsVisibleThroughConditionUpdateAndBody() {
        assertDoesNotThrow(() -> OresCompiler.compile("""
                define module math
                  gpu fnc scale(Array<f32> mut xs, i64 n, f32 factor) => void {
                    for (let i64 i = 0; i < n; i = i + 1) {
                      xs[i] = xs[i] * factor;
                    }
                    return;
                  }
                end
                """));
    }

    @Test
    void compilerActuallyEmitsOpenClForNamedGpuFunctions() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                define module math
                  gpu fnc add(i32 a, i32 b) => i32 {
                    let i32 sum = a + b;
                    return sum;
                  }
                end
                """);

        GpuKernelCompiler.GpuProgram gpu = compilation.gpuProgram();
        assertTrue(gpu.hasKernels());
        assertEquals(GpuKernelCompiler.BACKEND, gpu.backend());
        assertEquals(1, gpu.kernels().size());

        GpuKernelCompiler.GpuKernel kernel = gpu.kernels().getFirst();
        assertEquals("add", kernel.sourceName());
        assertEquals(GpuKernelCompiler.ExecutionShape.SINGLE_WORK_ITEM, kernel.executionShape());
        assertEquals("i32", kernel.resultType());
        assertTrue(gpu.source().contains("static inline int ores_dev_math_add_a2"));
        assertTrue(gpu.source().contains("__kernel void ores_kernel_math_add_a2"));
        assertTrue(gpu.source().contains("__global int* restrict __ores_out"));
        assertTrue(gpu.source().contains("__global int* restrict __ores_error"));
        assertTrue(gpu.source().contains("__ores_out[0] = " + kernel.helperSymbol()
                + "(ores_v_a, ores_v_b, __ores_error)"));
        assertTrue(kernel.helperSymbol().matches("ores_dev_math_add_a2_[0-9a-f]{12}"));
        assertTrue(kernel.kernelSymbol().matches("ores_kernel_math_add_a2_[0-9a-f]{12}"));
        assertTrue(gpu.source().contains("#define ORES_GPU_ABI_VERSION 1"));
        assertTrue(gpu.manifest().startsWith("abi=" + GpuKernelCompiler.ABI_VERSION + "\n"));
        assertTrue(kernel.hiddenParameters().stream()
                .anyMatch(p -> p.purpose() == GpuKernelCompiler.HiddenPurpose.RESULT));
        assertTrue(kernel.hiddenParameters().stream()
                .anyMatch(p -> p.purpose() == GpuKernelCompiler.HiddenPurpose.ERROR_SLOTS));
    }

    @Test
    void compilerAutoParallelizesCanonicalTopLevelBufferLoops() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                define module math
                  gpu fnc scale(Array<f32> mut xs, i64 n, f32 factor) => void {
                    for (let i64 i = 0; i < n; i = i + 1) {
                      xs[i] = xs[i] * factor;
                    }
                    return;
                  }
                end
                """);

        GpuKernelCompiler.GpuKernel kernel = compilation.gpuProgram().kernels().getFirst();
        assertEquals(GpuKernelCompiler.ExecutionShape.DATA_PARALLEL_1D, kernel.executionShape());
        assertEquals("n", kernel.launchExtentExpression());
        assertEquals("n", kernel.launchPlan().globalWorkItemsExpression());
        assertEquals("n", kernel.launchPlan().errorSlotsExpression());
        assertEquals("0", kernel.launchPlan().resultSlotsExpression());
        assertTrue(kernel.launchPlan().clampNegativeGlobalWorkItemsToZero());
        assertTrue(kernel.launchPlan().enforceNoAliasBuffers());
        String source = compilation.gpuProgram().source();
        assertTrue(source.contains("__global float* restrict ores_v_xs"));
        assertTrue(source.contains("ulong __ores_len_xs"));
        assertTrue(source.contains("__global int* restrict __ores_error"));
        assertTrue(source.contains("#define ORES_GPU_ERR_BOUNDS 1"));
        assertTrue(source.contains("get_global_id(0)"));
        assertTrue(source.contains("__ores_len_xs"));
        assertTrue(source.contains("__ores_error[get_global_id(0)] |= ORES_GPU_ERR_BOUNDS"));
        assertTrue(kernel.hiddenParameters().stream()
                .anyMatch(p -> p.purpose() == GpuKernelCompiler.HiddenPurpose.BUFFER_LENGTH
                        && "xs".equals(p.sourceParameter())));
        assertTrue(kernel.parameters().stream()
                .filter(GpuKernelCompiler.GpuParameter::buffer)
                .allMatch(GpuKernelCompiler.GpuParameter::noAlias));
        assertTrue(compilation.gpuProgram().manifest().contains("DATA_PARALLEL_1D"));
        assertTrue(compilation.gpuProgram().manifest()
                .contains(" param=xs|Array<f32>|__global float* restrict|true|true|true"));
    }

    @Test
    void gpuParallelListOfLowersToIndependentConcurrentKernelEntries() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                define module app
                  fnc main() => void {
                    const funcs = gpu parallel List.of(
                      || -> { return; },
                      || -> { return; },
                      || -> { return; }
                    );
                    return;
                  }
                end
                """);

        var kernels = compilation.gpuProgram().kernels();
        assertEquals(3, kernels.size());
        assertTrue(kernels.stream().allMatch(k -> k.kind() == GpuKernelCompiler.KernelKind.LAMBDA));
        assertTrue(kernels.stream().allMatch(k -> k.mode() == Ast.GpuMode.PARALLEL));
        assertNotNull(kernels.getFirst().batchId());
        assertTrue(kernels.stream().allMatch(k -> kernels.getFirst().batchId().equals(k.batchId())));
        assertEquals(3, kernels.stream().map(GpuKernelCompiler.GpuKernel::kernelSymbol).distinct().count());

        assertEquals(1, compilation.gpuProgram().batches().size());
        GpuKernelCompiler.GpuBatch batch = compilation.gpuProgram().batches().getFirst();
        assertEquals(3, batch.workItems());
        assertEquals(GpuKernelCompiler.BatchExecutionPolicy.CONCURRENT_KERNELS, batch.preferredPolicy());
        assertTrue(batch.hasDispatcher());
        assertEquals("3", batch.fallbackDispatcherLaunchPlan().globalWorkItemsExpression());
        assertEquals("3", batch.fallbackDispatcherLaunchPlan().errorSlotsExpression());
        assertEquals("0", batch.fallbackDispatcherLaunchPlan().resultSlotsExpression());
        assertFalse(batch.fallbackDispatcherLaunchPlan().clampNegativeGlobalWorkItemsToZero());
        assertFalse(batch.fallbackDispatcherLaunchPlan().enforceNoAliasBuffers());
        assertTrue(compilation.gpuProgram().manifest().contains("CONCURRENT_KERNELS"));
        assertTrue(compilation.gpuProgram().source().contains("__kernel void " + batch.dispatcherSymbol()));
        assertTrue(compilation.gpuProgram().source().contains("switch (__ores_task)"));
        assertTrue(compilation.gpuProgram().source().contains("__ores_error[__ores_task] = 0"));
        assertTrue(batch.hiddenParameters().stream()
                .anyMatch(p -> p.purpose() == GpuKernelCompiler.HiddenPurpose.ERROR_SLOTS));
    }

    @Test
    void deviceCallsPropagateBufferLengthsAndErrorState() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                define module math
                  gpu fnc load(Array<f32> xs, i64 i) => f32 {
                    return xs[i];
                  }

                  gpu fnc outer(Array<f32> xs, i64 i) => f32 {
                    return load(xs, i);
                  }
                end
                """);

        String source = compilation.gpuProgram().source();
        GpuKernelCompiler.GpuKernel load = compilation.gpuProgram().kernels().stream()
                .filter(k -> k.sourceName().equals("load"))
                .findFirst().orElseThrow();
        assertTrue(source.contains(load.helperSymbol()
                + "(ores_v_xs, __ores_len_xs, ores_v_i, __ores_error)"));
        assertTrue(source.contains("((ulong)(ores_v_i) < __ores_len_xs)"));
        assertTrue(source.contains("ORES_GPU_ERR_BOUNDS"));
    }

    @Test
    void integerDivisionIsRejectedUntilDeviceTrapSemanticsExist() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        gpu fnc div(i64 a, i64 b) => i64 {
                          return a / b;
                        }
                        """));

        assertTrue(failure.getMessage().contains("integer / is rejected in GPU code"));
    }

    @Test
    void autoParallelizerFallsBackWhenMutableBufferAccessesWouldRace() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                define module math
                  gpu fnc unsafe(Array<f32> mut xs, i64 n) => void {
                    for (let i64 i = 0; i < n; i = i + 1) {
                      xs[0] = xs[i];
                    }
                    return;
                  }
                end
                """);

        GpuKernelCompiler.GpuKernel kernel = compilation.gpuProgram().kernels().getFirst();
        assertEquals(GpuKernelCompiler.ExecutionShape.SINGLE_WORK_ITEM, kernel.executionShape());
        assertNull(kernel.launchExtentExpression());
    }

    @Test
    void fp64ExtensionIsRequiredOnlyWhenGeneratedCodeUsesDouble() {
        var ints = OresCompiler.compile("""
                gpu fnc add(i32 a, i32 b) => i32 {
                  return a + b;
                }
                """).gpuProgram();
        assertFalse(ints.requiredExtensions().contains("cl_khr_fp64"));
        assertFalse(ints.source().contains("cl_khr_fp64"));

        var doubles = OresCompiler.compile("""
                gpu fnc add(f64 a, f64 b) => f64 {
                  return a + b;
                }
                """).gpuProgram();
        assertTrue(doubles.requiredExtensions().contains("cl_khr_fp64"));
        assertTrue(doubles.source().contains("#pragma OPENCL EXTENSION cl_khr_fp64 : enable"));


        var identifierOnly = OresCompiler.compile("""
                gpu fnc add(i32 double_counter, i32 x) => i32 {
                  return double_counter + x;
                }
                """).gpuProgram();
        assertFalse(identifierOnly.requiredExtensions().contains("cl_khr_fp64"));
        assertFalse(identifierOnly.source().contains("#pragma OPENCL EXTENSION cl_khr_fp64 : enable"));
    }

    @Test
    void generatedStaticFunctionSymbolsCannotCollideAcrossClassMethodBoundaries() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                define module math
                  define class A_B
                    gpu static fnc c(i32 x) => i32 {
                      return x;
                    }
                  end

                  define class A
                    gpu static fnc B_c(i32 x) => i32 {
                      return x;
                    }
                  end
                end
                """);

        var kernels = compilation.gpuProgram().kernels();
        assertEquals(2, kernels.size());
        assertEquals(2, kernels.stream().map(GpuKernelCompiler.GpuKernel::helperSymbol).distinct().count());
        assertEquals(2, kernels.stream().map(GpuKernelCompiler.GpuKernel::kernelSymbol).distinct().count());
        assertTrue(kernels.stream().allMatch(k -> k.helperSymbol().matches("ores_dev_math_A_B_c_a1_[0-9a-f]{12}")));
    }

    @Test
    void gpuAbiRejectsArrayReturnsAndRecursiveDeviceCallGraphs() {
        IllegalArgumentException arrayReturn = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        define module math
                          gpu fnc bad(Array<f32> xs) => Array<f32> {
                            return xs;
                          }
                        end
                        """));
        assertTrue(arrayReturn.getMessage().contains("must return a scalar or void"));

        IllegalArgumentException recursion = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        define module math
                          gpu fnc recurse(i32 x) => i32 {
                            if x <= 0; do
                              return 0;
                            fi
                            return recurse(x - 1);
                          }
                        end
                        """));
        assertTrue(recursion.getMessage().contains("recursive GPU call graph"));
    }

    @Test
    void acceptedGpuCodeRejectsHostOnlyAggregatesBeforeLowering() {
        IllegalArgumentException listLiteral = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        define module math
                          gpu fnc bad(i32 x) => i32 {
                            val ys = [x, x + 1];
                            return x;
                          }
                        end
                        """));
        assertTrue(listLiteral.getMessage().contains("cannot allocate list/array literals"));

        IllegalArgumentException forOf = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        define module math
                          gpu fnc bad(Array<i32> xs) => void {
                            for (val x of xs) {
                              return;
                            }
                          }
                        end
                        """));
        assertTrue(forOf.getMessage().contains("for-of is not supported inside GPU code"));
    }

    @Test
    void gpuLoweringPreservesExactScalarWidthsAndContextualizesLiterals() {
        var i32 = OresCompiler.compile("""
                gpu fnc inc(i32 x) => i32 {
                  return x + 1;
                }
                """).gpuProgram();
        assertTrue(i32.source().contains("((int)1L)"));
        assertFalse(i32.source().contains("double"));

        var f32 = OresCompiler.compile("""
                gpu fnc bump(f32 x) => f32 {
                  return x + 1.0;
                }
                """).gpuProgram();
        assertTrue(f32.source().contains("((float)((double)1.0))"));

        IllegalArgumentException narrowing = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        gpu fnc bad(i64 x) => i32 {
                          return x;
                        }
                        """));
        assertTrue(narrowing.getMessage().contains("implicit GPU numeric conversion"));

        IllegalArgumentException mixedSign = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        gpu fnc bad(i32 x, u32 y) => i32 {
                          return x + y;
                        }
                        """));
        assertTrue(mixedSign.getMessage().contains("mixed-width/sign arithmetic"));
    }

    @Test
    void gpuAssignmentsCannotImplicitlyNarrowDeviceValues() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        gpu fnc bad(i64 x) => void {
                          let i32 y = x;
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("GPU binding 'y'"));
        assertTrue(failure.getMessage().contains("implicit GPU numeric conversion"));
    }

    @Test
    void gpuTypeAliasesResolveWithinTheirOwningModule() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                define module left
                  type Scalar = i32;
                  gpu fnc add(Scalar a, Scalar b) => Scalar {
                    return a + b;
                  }
                end

                define module right
                  type Scalar = f32;
                  gpu fnc add(Scalar a, Scalar b) => Scalar {
                    return a + b;
                  }
                end
                """);

        assertEquals(2, compilation.gpuProgram().kernels().size());
        assertTrue(compilation.gpuProgram().kernels().stream()
                .anyMatch(k -> k.sourceName().equals("add") && k.resultType().equals("i32")));
        assertTrue(compilation.gpuProgram().kernels().stream()
                .anyMatch(k -> k.sourceName().equals("add") && k.resultType().equals("f32")));
    }

    @Test
    void generatedGpuProgramsPassARealOpenClFrontendWhenValidationIsEnabled() throws Exception {
        if (!"1".equals(System.getenv("ORES_GPU_VALIDATE_OPENCL"))) return;

        validateOpenCl(OresCompiler.compile("""
                gpu fnc add(i32 a, i32 b) => i32 {
                  return a + b;
                }
                """).gpuProgram());

        validateOpenCl(OresCompiler.compile("""
                gpu fnc scale(Array<f32> mut xs, i64 n, f32 factor) => void {
                  for (let i64 i = 0; i < n; i = i + 1) {
                    xs[i] = xs[i] * factor;
                  }
                  return;
                }
                """).gpuProgram());

        validateOpenCl(OresCompiler.compile("""
                gpu fnc load(Array<f32> xs, i64 i) => f32 {
                  return xs[i];
                }
                gpu fnc outer(Array<f32> xs, i64 i) => f32 {
                  return load(xs, i);
                }
                """).gpuProgram());

        validateOpenCl(OresCompiler.compile("""
                fnc host() => void {
                  const funcs = gpu parallel List.of(
                    || -> { return; },
                    || -> { return; },
                    || -> { return; }
                  );
                  return;
                }
                """).gpuProgram());
    }

    private static void validateOpenCl(GpuKernelCompiler.GpuProgram program) throws Exception {
        Path source = Files.createTempFile("ores-gpu-", ".cl");
        try {
            Files.writeString(source, program.source(), StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(
                    "clang",
                    "-x", "cl",
                    "-cl-std=CL1.2",
                    "-fsyntax-only",
                    source.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "clang OpenCL validation timed out");
            assertEquals(0, process.exitValue(),
                    () -> "generated OpenCL failed frontend validation:\n" + output + "\nSOURCE:\n" + program.source());
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void signedParallelBoundsCarryAClampContractAndUnsignedBoundsDoNot() {
        var signed = OresCompiler.compile("""
                gpu fnc scale(Array<f32> mut xs, i64 n) => void {
                  for (let i64 i = 0; i < n; i = i + 1) {
                    xs[i] = xs[i] + 1.0;
                  }
                  return;
                }
                """).gpuProgram().kernels().getFirst();
        assertTrue(signed.launchPlan().clampNegativeGlobalWorkItemsToZero());

        var unsigned = OresCompiler.compile("""
                gpu fnc scale(Array<f32> mut xs, u64 n) => void {
                  for (let u64 i = 0; i < n; i = i + 1) {
                    xs[i] = xs[i] + 1.0;
                  }
                  return;
                }
                """).gpuProgram().kernels().getFirst();
        assertFalse(unsigned.launchPlan().clampNegativeGlobalWorkItemsToZero());
    }

    @Test
    void gpuExecutionNeverFallsBackToCpuInterpreter() throws Exception {
        String program = """
                define module app
                  gpu fnc kernel(i32 x) => i32 {
                    return x + 1;
                  }

                  pub fnc main() => void {
                    kernel(41);
                    return;
                  }
                end
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "gpu-no-fallback.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> context.eval(source));
            assertTrue(failure.getMessage().contains("no GPU lowering/backend")
                    || failure.getMessage().contains("never falls back to CPU"));
        }
    }
}
