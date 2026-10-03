module dev.oreslang {
    requires java.base;
    requires java.logging;
    requires org.graalvm.polyglot;
    requires org.graalvm.truffle;

    exports dev.oreslang.launcher;

    provides com.oracle.truffle.api.provider.TruffleLanguageProvider
        with dev.oreslang.OresLanguageProvider;
}
