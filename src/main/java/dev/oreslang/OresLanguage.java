package dev.oreslang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresInteropRootNode;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresContext;
import org.graalvm.polyglot.SandboxPolicy;

@TruffleLanguage.Registration(
        id = OresLanguage.ID,
        name = "Oreslang",
        version = "0.1.0",
        defaultMimeType = OresLanguage.MIME_TYPE,
        characterMimeTypes = OresLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE,
        sandbox = SandboxPolicy.UNTRUSTED,
        website = "https://github.com/ores-truffle-oreslang/oreslang-source.java")
public final class OresLanguage extends TruffleLanguage<OresContext> {
    public static final String ID = "ores";
    public static final String MIME_TYPE = "application/x-oreslang";

    @Override
    protected OresContext createContext(Env env) {
        return new OresContext(this, env);
    }

    /**
     * Each actor is exactly one host-owned worker virtual thread. Guest source
     * still has no raw thread-creation authority; that remains controlled by
     * IsolatePolicy and the Polyglot Context builder.
     *
     * JVM carrier threads used underneath virtual threads are not Oreslang
     * workers and have no language identity.
     */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return singleThreaded || ActorRuntime.isActorWorkerThread();
    }

    @Override
    protected void initializeMultiThreading(OresContext context) {
        // All mutable language state used by actor turns is context-owned,
        // actor-owned, immutable, or explicitly synchronized.
    }

    @Override
    protected void disposeContext(OresContext context) {
        context.close();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        String text = request.getSource().getCharacters().toString();
        Ast.Program program = OresCompiler.parseAndTypeCheck(text);
        RootCallTarget evaluator = new OresEvalRootNode(this, program).getCallTarget();
        return new OresInteropRootNode(this, evaluator).getCallTarget();
    }
}
