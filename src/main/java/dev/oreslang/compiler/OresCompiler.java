package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;

/** Trusted compiler front-end API for build systems and isolate admission. */
public final class OresCompiler {
    private OresCompiler() { }

    public record CompilationResult(Ast.Program program, GpuKernelCompiler.GpuProgram gpuProgram) { }

    public static CompilationResult compile(String source) {
        Ast.Program program = TypeChecker.check(Parser.parse(source));
        GpuSafetyChecker.check(program);
        GpuKernelCompiler.GpuProgram gpuProgram = GpuKernelCompiler.compile(program);
        return new CompilationResult(program, gpuProgram);
    }

    public static Ast.Program parseAndTypeCheck(String source) {
        return compile(source).program();
    }

    /**
     * Performs syntax, type, and language-capability admission without
     * executing guest code.
     */
    public static Ast.Program validateForIsolate(String source, IsolatePolicy policy) {
        Ast.Program program = parseAndTypeCheck(source);
        CapabilityChecker.check(program, policy);
        return program;
    }
}
