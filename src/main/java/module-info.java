module dev.oreslang {
    requires java.management;
    requires java.base;
    requires java.compiler;
    requires java.logging;
    requires org.graalvm.polyglot;
    requires org.graalvm.truffle;
    requires org.tomlj;

    exports dev.oreslang.launcher;
    exports dev.oreslang.compiler;

    provides com.oracle.truffle.api.provider.TruffleLanguageProvider
        with dev.oreslang.OresLanguageProvider;
}
