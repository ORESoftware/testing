package dev.oreslang;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.provider.TruffleLanguageProvider;

import java.util.Collection;
import java.util.List;

/**
 * Module-path service provider for Oreslang.
 *
 * <p>The Truffle DSL normally generates this class from
 * {@link TruffleLanguage.Registration}. This source tree is intentionally
 * self-contained, so the module service boundary is kept explicit.</p>
 */
@TruffleLanguage.Registration(
        id = OresLanguage.ID,
        name = "Oreslang",
        version = "0.1.0",
        defaultMimeType = OresLanguage.MIME_TYPE,
        characterMimeTypes = OresLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE)
public final class OresLanguageProvider
        extends TruffleLanguageProvider {

    @Override
    protected String getLanguageClassName() {
        return OresLanguage.class.getName();
    }

    @Override
    protected Object create() {
        return new OresLanguage();
    }

    @Override
    protected Collection<String> getServicesClassNames() {
        return List.of();
    }

    @Override
    protected List<?> createFileTypeDetectors() {
        return List.of();
    }
}
