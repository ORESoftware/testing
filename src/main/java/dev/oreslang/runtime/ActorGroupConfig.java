package dev.oreslang.runtime;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validated host/deployment policy for Oreslang actor groups.
 *
 * This class deliberately contains no scheduler implementation. It defines the
 * fail-closed policy objects consumed by the runtime before actor creation is
 * admitted.
 */
public final class ActorGroupConfig {
    private ActorGroupConfig() { }

    public enum RestartStrategy {
        ONE_FOR_ONE,
        ONE_FOR_ALL,
        REST_FOR_ONE
    }

    public enum RestartPolicy {
        PERMANENT,
        TRANSIENT,
        TEMPORARY
    }

    /**
     * Shared group policy. A positive minimum requires a concrete actor factory
     * so the supervisor knows which actor behavior to create when reconciling
     * the floor.
     */
    public record GroupPolicy(
            ActorRuntime.ActorKind actorKind,
            int minActors,
            int maxActors,
            int inboxCapacity,
            int outboxCapacity,
            RestartStrategy restartStrategy,
            int maxRestarts,
            Duration restartWindow,
            RestartPolicy restartPolicy,
            String factory) {

        public GroupPolicy {
            Objects.requireNonNull(actorKind, "actorKind");
            Objects.requireNonNull(restartStrategy, "restartStrategy");
            Objects.requireNonNull(restartWindow, "restartWindow");
            Objects.requireNonNull(restartPolicy, "restartPolicy");

            if (minActors < 0) {
                throw new IllegalArgumentException("minActors must be >= 0");
            }
            if (maxActors <= 0) {
                throw new IllegalArgumentException("maxActors must be > 0");
            }
            if (minActors > maxActors) {
                throw new IllegalArgumentException("minActors cannot exceed maxActors");
            }
            if (inboxCapacity <= 0) {
                throw new IllegalArgumentException("inboxCapacity must be > 0");
            }
            if (outboxCapacity <= 0) {
                throw new IllegalArgumentException("outboxCapacity must be > 0");
            }
            if (maxRestarts < 0) {
                throw new IllegalArgumentException("maxRestarts must be >= 0");
            }
            if (restartWindow.isZero() || restartWindow.isNegative()) {
                throw new IllegalArgumentException("restartWindow must be > 0");
            }

            factory = normalizeOptional(factory);
            if (factory != null) {
                factory = ActorFactoryCatalog.FactoryKey.parse(factory).canonical();
            }
            if (minActors > 0 && factory == null) {
                throw new IllegalArgumentException(
                        "minActors > 0 requires a factory so the supervisor can maintain the floor");
            }
        }

        public boolean hasFactory() {
            return factory != null;
        }

        public GroupPolicy withRequestedDynamicCapacity(DynamicGroupRequest request) {
            Objects.requireNonNull(request, "request");
            if (request.maxActors() > maxActors) {
                throw new IllegalArgumentException(
                        "dynamic group maxActors " + request.maxActors()
                                + " exceeds template ceiling " + maxActors);
            }
            if (request.inboxCapacity() > inboxCapacity) {
                throw new IllegalArgumentException(
                        "dynamic group inboxCapacity " + request.inboxCapacity()
                                + " exceeds template ceiling " + inboxCapacity);
            }
            if (request.outboxCapacity() > outboxCapacity) {
                throw new IllegalArgumentException(
                        "dynamic group outboxCapacity " + request.outboxCapacity()
                                + " exceeds template ceiling " + outboxCapacity);
            }
            if (request.minActors() > request.maxActors()) {
                throw new IllegalArgumentException(
                        "dynamic group minActors cannot exceed requested maxActors");
            }
            if (request.minActors() > 0 && factory == null) {
                throw new IllegalArgumentException(
                        "dynamic group minActors > 0 requires a template factory");
            }

            return new GroupPolicy(
                    actorKind,
                    request.minActors(),
                    request.maxActors(),
                    request.inboxCapacity(),
                    request.outboxCapacity(),
                    restartStrategy,
                    maxRestarts,
                    restartWindow,
                    restartPolicy,
                    factory);
        }
    }

    public record StaticGroupSpec(String name, GroupPolicy policy) {
        public StaticGroupSpec {
            name = requireName(name, "static group");
            Objects.requireNonNull(policy, "policy");
        }
    }

    /**
     * Dynamic groups are instantiated from a deployment-owned template. The
     * template fixes execution domain, restart semantics, and capability shape;
     * runtime requests may only narrow resource capacities.
     */
    public record DynamicGroupTemplate(
            String name,
            GroupPolicy ceiling,
            int maxInstances) {

        public DynamicGroupTemplate {
            name = requireName(name, "dynamic group template");
            Objects.requireNonNull(ceiling, "ceiling");
            if (maxInstances <= 0) {
                throw new IllegalArgumentException("maxInstances must be > 0");
            }
            if (ceiling.restartStrategy() != RestartStrategy.ONE_FOR_ONE) {
                throw new IllegalArgumentException(
                        "dynamic group templates currently require ONE_FOR_ONE restart strategy");
            }
        }
    }

    public record DynamicGroupRequest(
            String name,
            String template,
            int minActors,
            int maxActors,
            int inboxCapacity,
            int outboxCapacity) {

        public DynamicGroupRequest {
            name = requireName(name, "dynamic group");
            template = requireName(template, "dynamic group template reference");
            if (minActors < 0) {
                throw new IllegalArgumentException("minActors must be >= 0");
            }
            if (maxActors <= 0) {
                throw new IllegalArgumentException("maxActors must be > 0");
            }
            if (inboxCapacity <= 0) {
                throw new IllegalArgumentException("inboxCapacity must be > 0");
            }
            if (outboxCapacity <= 0) {
                throw new IllegalArgumentException("outboxCapacity must be > 0");
            }
        }
    }

    /**
     * Process-level group policy loaded before guest main() is admitted.
     */
    public record ProcessConfig(
            int maxGroups,
            int maxDynamicGroups,
            int maxActors,
            List<StaticGroupSpec> staticGroups,
            List<DynamicGroupTemplate> dynamicTemplates) {

        public ProcessConfig {
            if (maxGroups <= 0) {
                throw new IllegalArgumentException("maxGroups must be > 0");
            }
            if (maxDynamicGroups < 0) {
                throw new IllegalArgumentException("maxDynamicGroups must be >= 0");
            }
            if (maxDynamicGroups > maxGroups) {
                throw new IllegalArgumentException("maxDynamicGroups cannot exceed maxGroups");
            }
            if (maxActors <= 0) {
                throw new IllegalArgumentException("maxActors must be > 0");
            }

            staticGroups = List.copyOf(Objects.requireNonNull(staticGroups, "staticGroups"));
            dynamicTemplates = List.copyOf(
                    Objects.requireNonNull(dynamicTemplates, "dynamicTemplates"));

            if (staticGroups.size() > maxGroups) {
                throw new IllegalArgumentException(
                        "configured static group count exceeds maxGroups");
            }

            LinkedHashMap<String, StaticGroupSpec> groupsByName = new LinkedHashMap<>();
            long staticMaxActorSum = 0L;
            for (StaticGroupSpec group : staticGroups) {
                if (groupsByName.putIfAbsent(group.name(), group) != null) {
                    throw new IllegalArgumentException(
                            "duplicate static actor group name '" + group.name() + "'");
                }
                staticMaxActorSum = Math.addExact(
                        staticMaxActorSum,
                        (long) group.policy().maxActors());
            }
            if (staticMaxActorSum > maxActors) {
                throw new IllegalArgumentException(
                        "sum of static group maxActors exceeds process maxActors");
            }

            LinkedHashMap<String, DynamicGroupTemplate> templatesByName =
                    new LinkedHashMap<>();
            for (DynamicGroupTemplate template : dynamicTemplates) {
                if (templatesByName.putIfAbsent(template.name(), template) != null) {
                    throw new IllegalArgumentException(
                            "duplicate dynamic actor group template name '"
                                    + template.name() + "'");
                }
            }
        }

        public Map<String, StaticGroupSpec> staticGroupsByName() {
            LinkedHashMap<String, StaticGroupSpec> result = new LinkedHashMap<>();
            for (StaticGroupSpec group : staticGroups) {
                result.put(group.name(), group);
            }
            return Map.copyOf(result);
        }

        public Map<String, DynamicGroupTemplate> dynamicTemplatesByName() {
            LinkedHashMap<String, DynamicGroupTemplate> result = new LinkedHashMap<>();
            for (DynamicGroupTemplate template : dynamicTemplates) {
                result.put(template.name(), template);
            }
            return Map.copyOf(result);
        }

        public GroupPolicy resolveDynamic(DynamicGroupRequest request) {
            Objects.requireNonNull(request, "request");
            DynamicGroupTemplate template = dynamicTemplatesByName().get(request.template());
            if (template == null) {
                throw new IllegalArgumentException(
                        "unknown dynamic actor group template '" + request.template() + "'");
            }
            return template.ceiling().withRequestedDynamicCapacity(request);
        }

        /**
         * Link configured symbolic factory keys against the immutable catalog
         * for the exact code generation that will execute this process.
         *
         * This must run before main() is admitted. It is intentionally separate
         * from actor construction: resolving a descriptor never executes guest
         * code or self-registers an actor definition.
         */
        public void validateFactories(ActorFactoryCatalog catalog) {
            Objects.requireNonNull(catalog, "catalog");
            for (StaticGroupSpec group : staticGroups) {
                validateFactory(
                        "static actor group '" + group.name() + "'",
                        group.policy(),
                        catalog);
            }
            for (DynamicGroupTemplate template : dynamicTemplates) {
                validateFactory(
                        "dynamic actor group template '" + template.name() + "'",
                        template.ceiling(),
                        catalog);
            }
        }

        private static void validateFactory(
                String owner,
                GroupPolicy policy,
                ActorFactoryCatalog catalog) {
            if (!policy.hasFactory()) return;
            ActorFactoryCatalog.Descriptor descriptor = catalog.require(policy.factory());
            if (descriptor.actorKind() != policy.actorKind()) {
                throw new IllegalArgumentException(
                        owner + " declares actor kind " + policy.actorKind()
                                + " but factory '" + descriptor.key()
                                + "' is " + descriptor.actorKind());
            }
        }
    }

    private static String requireName(String value, String what) {
        Objects.requireNonNull(value, what);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(what + " name cannot be empty");
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) {
                throw new IllegalArgumentException(what + " name cannot contain control characters");
            }
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
