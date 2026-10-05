package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorGroupConfigTest {

    private static ActorGroupConfig.GroupPolicy sharedPolicy(
            int minActors,
            int maxActors,
            String factory) {
        return new ActorGroupConfig.GroupPolicy(
                ActorRuntime.ActorKind.SHARED,
                minActors,
                maxActors,
                1024,
                4096,
                ActorGroupConfig.RestartStrategy.ONE_FOR_ONE,
                3,
                Duration.ofSeconds(5),
                ActorGroupConfig.RestartPolicy.PERMANENT,
                factory);
    }

    @Test
    void positiveMinimumRequiresFactory() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> sharedPolicy(1, 8, null));

        assertTrue(failure.getMessage().contains("requires a factory"));
    }

    @Test
    void dynamicTemplateRequiresOneForOne() {
        ActorGroupConfig.GroupPolicy policy = new ActorGroupConfig.GroupPolicy(
                ActorRuntime.ActorKind.SHARED,
                0,
                16,
                128,
                256,
                ActorGroupConfig.RestartStrategy.ONE_FOR_ALL,
                3,
                Duration.ofSeconds(5),
                ActorGroupConfig.RestartPolicy.PERMANENT,
                "workers/worker.ores::worker");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ActorGroupConfig.DynamicGroupTemplate(
                        "tenant-workers",
                        policy,
                        32));

        assertTrue(failure.getMessage().contains("ONE_FOR_ONE"));
    }

    @Test
    void dynamicRequestMayNarrowTemplateCapacity() {
        ActorGroupConfig.DynamicGroupTemplate template =
                new ActorGroupConfig.DynamicGroupTemplate(
                        "tenant-workers",
                        sharedPolicy(0, 32, "workers/tenant_worker.ores::worker"),
                        1024);

        ActorGroupConfig.ProcessConfig process =
                new ActorGroupConfig.ProcessConfig(
                        2048,
                        1024,
                        16_384,
                        List.of(),
                        List.of(template));

        ActorGroupConfig.GroupPolicy resolved = process.resolveDynamic(
                new ActorGroupConfig.DynamicGroupRequest(
                        "tenant:acme",
                        "tenant-workers",
                        4,
                        16,
                        512,
                        2048));

        assertEquals(4, resolved.minActors());
        assertEquals(16, resolved.maxActors());
        assertEquals(512, resolved.inboxCapacity());
        assertEquals(2048, resolved.outboxCapacity());
        assertEquals(ActorRuntime.ActorKind.SHARED, resolved.actorKind());
        assertEquals(ActorGroupConfig.RestartStrategy.ONE_FOR_ONE, resolved.restartStrategy());
        assertEquals("workers/tenant_worker.ores::worker", resolved.factory());
    }

    @Test
    void dynamicRequestCannotWidenTemplateCapacity() {
        ActorGroupConfig.DynamicGroupTemplate template =
                new ActorGroupConfig.DynamicGroupTemplate(
                        "tenant-workers",
                        sharedPolicy(0, 32, "workers/tenant_worker.ores::worker"),
                        1024);

        ActorGroupConfig.ProcessConfig process =
                new ActorGroupConfig.ProcessConfig(
                        2048,
                        1024,
                        16_384,
                        List.of(),
                        List.of(template));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> process.resolveDynamic(
                        new ActorGroupConfig.DynamicGroupRequest(
                                "tenant:acme",
                                "tenant-workers",
                                0,
                                33,
                                512,
                                2048)));

        assertTrue(failure.getMessage().contains("exceeds template ceiling"));
    }

    @Test
    void processRejectsDuplicateStaticNamesAndImpossibleStaticCapacity() {
        ActorGroupConfig.StaticGroupSpec first =
                new ActorGroupConfig.StaticGroupSpec(
                        "default",
                        sharedPolicy(0, 8, null));

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> new ActorGroupConfig.ProcessConfig(
                        8,
                        4,
                        32,
                        List.of(first, first),
                        List.of()));
        assertTrue(duplicate.getMessage().contains("duplicate static actor group"));

        ActorGroupConfig.StaticGroupSpec second =
                new ActorGroupConfig.StaticGroupSpec(
                        "workers",
                        sharedPolicy(0, 30, null));

        IllegalArgumentException capacity = assertThrows(
                IllegalArgumentException.class,
                () -> new ActorGroupConfig.ProcessConfig(
                        8,
                        4,
                        32,
                        List.of(first, second),
                        List.of()));
        assertTrue(capacity.getMessage().contains("exceeds process maxActors"));
    }

    @Test
    void staticAndTemplateIndexesAreStableImmutableViews() {
        ActorGroupConfig.StaticGroupSpec group =
                new ActorGroupConfig.StaticGroupSpec(
                        "default",
                        sharedPolicy(0, 8, null));
        ActorGroupConfig.DynamicGroupTemplate template =
                new ActorGroupConfig.DynamicGroupTemplate(
                        "tenant-workers",
                        sharedPolicy(0, 4, null),
                        10);

        ActorGroupConfig.ProcessConfig process =
                new ActorGroupConfig.ProcessConfig(
                        16,
                        8,
                        64,
                        List.of(group),
                        List.of(template));

        assertSame(group, process.staticGroupsByName().get("default"));
        assertSame(template, process.dynamicTemplatesByName().get("tenant-workers"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> process.staticGroupsByName().clear());
    }

    @Test
    void factoryCatalogValidatesGenerationAndActorDomainBeforeMain() {
        ActorFactoryCatalog catalog = new ActorFactoryCatalog(
                7,
                List.of(new ActorFactoryCatalog.Descriptor(
                        ActorFactoryCatalog.FactoryKey.parse(
                                "workers/tenant_worker.ores::worker"),
                        ActorRuntime.ActorKind.SHARED,
                        "TenantJob",
                        "TenantEvent",
                        "sha256:abc",
                        7)));

        ActorGroupConfig.DynamicGroupTemplate template =
                new ActorGroupConfig.DynamicGroupTemplate(
                        "tenant-workers",
                        sharedPolicy(
                                0,
                                32,
                                "workers/tenant_worker.ores::worker"),
                        1024);

        ActorGroupConfig.ProcessConfig process =
                new ActorGroupConfig.ProcessConfig(
                        2048,
                        1024,
                        16_384,
                        List.of(),
                        List.of(template));

        assertDoesNotThrow(() -> process.validateFactories(catalog));

        ActorFactoryCatalog wrongDomain = new ActorFactoryCatalog(
                7,
                List.of(new ActorFactoryCatalog.Descriptor(
                        ActorFactoryCatalog.FactoryKey.parse(
                                "workers/tenant_worker.ores::worker"),
                        ActorRuntime.ActorKind.PRIVATE,
                        "TenantJob",
                        "TenantEvent",
                        "sha256:def",
                        7)));

        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> process.validateFactories(wrongDomain));
        assertTrue(mismatch.getMessage().contains("declares actor kind"));
    }

    @Test
    void configRejectsMalformedFactoryKeysAndMissingCatalogEntries() {
        IllegalArgumentException malformed = assertThrows(
                IllegalArgumentException.class,
                () -> sharedPolicy(0, 8, "workers/worker.ores"));
        assertTrue(malformed.getMessage().contains("<path>::<symbol>"));

        ActorGroupConfig.StaticGroupSpec group =
                new ActorGroupConfig.StaticGroupSpec(
                        "workers",
                        sharedPolicy(1, 8, "workers/worker.ores::worker"));
        ActorGroupConfig.ProcessConfig process =
                new ActorGroupConfig.ProcessConfig(
                        16,
                        8,
                        64,
                        List.of(group),
                        List.of());

        ActorFactoryCatalog empty = new ActorFactoryCatalog(11, List.of());
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> process.validateFactories(empty));
        assertTrue(missing.getMessage().contains("not present in code generation 11"));
    }

}
