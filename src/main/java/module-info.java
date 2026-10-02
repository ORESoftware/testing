module dev.oreslang {
    requires java.base;
    requires java.logging;
    requires org.graalvm.polyglot;
    requires org.graalvm.truffle;

    exports dev.oreslang.launcher;
    exports dev.oreslang.compiler;
    exports dev.oreslang.ast;
    exports dev.oreslang.gpu;

    provides com.oracle.truffle.api.provider.TruffleLanguageProvider
        with dev.oreslang.OresLanguageProvider;
}
