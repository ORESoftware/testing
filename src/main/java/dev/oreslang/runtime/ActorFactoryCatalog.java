package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable compiler/linker-generated actor factory metadata for one code
 * generation. Actor definitions never populate this catalog by executing
 * import-time/static registration side effects.
 */
public final class ActorFactoryCatalog {
    public record FactoryKey(String codeUnit, String symbol) {
        public FactoryKey {
            codeUnit = requirePart(codeUnit, "actor factory code unit");
            symbol = requirePart(symbol, "actor factory symbol");
            if (codeUnit.contains("::")) {
                throw new IllegalArgumentException(
                        "actor factory code unit cannot contain '::'");
            }
            if (symbol.contains("::")) {
                throw new IllegalArgumentException(
                        "actor factory symbol cannot contain '::'");
            }
        }

        public static FactoryKey parse(String canonical) {
            Objects.requireNonNull(canonical, "canonical actor factory key");
            int separator = canonical.lastIndexOf("::");
            if (separator <= 0 || separator == canonical.length() - 2) {
                throw new IllegalArgumentException(
                        "actor factory key must be '<path>::<symbol>', got '" + canonical + "'");
            }
            if (canonical.indexOf("::") != separator) {
                throw new IllegalArgumentException(
                        "actor factory key must contain exactly one '::' separator");
            }
            return new FactoryKey(
                    canonical.substring(0, separator),
                    canonical.substring(separator + 2));
        }

        public String canonical() {
            return codeUnit + "::" + symbol;
        }

        @Override
        public String toString() {
            return canonical();
        }
    }

    /**
     * Generation-scoped descriptor for a compiler-generated persistent actor
     * constructor.
     *
     * <p>{@code inputType} names the actor mailbox message ABI consumed by the
     * single source-level {@code receive(In): void} ingress. {@code outputType}
     * describes the group's emitted-output contract when one exists. The ABI
     * digest covers the receive message/reply/error contract, constructor,
     * execution domain, and other generation-relevant actor ABI metadata.</p>
     */
    public record Descriptor(
            FactoryKey key,
            ActorRuntime.ActorKind actorKind,
            String inputType,
            String outputType,
            String abiDigest,
            long codeGeneration) {

        public Descriptor {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(actorKind, "actorKind");
            inputType = requirePart(inputType, "actor mailbox input ABI type");
            outputType = requirePart(outputType, "actor group output type");
            abiDigest = requirePart(abiDigest, "actor factory ABI digest");
            if (codeGeneration < 0) {
                throw new IllegalArgumentException("codeGeneration must be >= 0");
            }
        }
    }

    private final long codeGeneration;
    private final Map<FactoryKey, Descriptor> descriptors;

    public ActorFactoryCatalog(long codeGeneration, List<Descriptor> descriptors) {
        if (codeGeneration < 0) {
            throw new IllegalArgumentException("codeGeneration must be >= 0");
        }
        this.codeGeneration = codeGeneration;
        Objects.requireNonNull(descriptors, "descriptors");

        LinkedHashMap<FactoryKey, Descriptor> indexed = new LinkedHashMap<>();
        for (Descriptor descriptor : descriptors) {
            Objects.requireNonNull(descriptor, "descriptor");
            if (descriptor.codeGeneration() != codeGeneration) {
                throw new IllegalArgumentException(
                        "actor factory '" + descriptor.key()
                                + "' belongs to generation " + descriptor.codeGeneration()
                                + " but catalog generation is " + codeGeneration);
            }
            if (indexed.putIfAbsent(descriptor.key(), descriptor) != null) {
                throw new IllegalArgumentException(
                        "duplicate actor factory key '" + descriptor.key() + "'");
            }
        }
        this.descriptors = Map.copyOf(indexed);
    }

    public long codeGeneration() {
        return codeGeneration;
    }

    public Optional<Descriptor> find(FactoryKey key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(descriptors.get(key));
    }

    public Optional<Descriptor> find(String canonicalKey) {
        return find(FactoryKey.parse(canonicalKey));
    }

    public Descriptor require(String canonicalKey) {
        FactoryKey key = FactoryKey.parse(canonicalKey);
        Descriptor descriptor = descriptors.get(key);
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "actor factory '" + canonicalKey
                            + "' is not present in code generation " + codeGeneration);
        }
        return descriptor;
    }

    public Map<FactoryKey, Descriptor> descriptors() {
        return descriptors;
    }

    private static String requirePart(String value, String what) {
        Objects.requireNonNull(value, what);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(what + " cannot be empty");
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) {
                throw new IllegalArgumentException(
                        what + " cannot contain control characters");
            }
        }
        return normalized;
    }
}
