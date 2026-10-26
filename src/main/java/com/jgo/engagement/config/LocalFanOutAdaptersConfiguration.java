package com.jgo.engagement.config;

import com.jgo.engagement.adapter.inbound.local.LocalSmokeRunner;
import com.jgo.engagement.adapter.outbound.local.InMemoryEngagementIndex;
import com.jgo.engagement.adapter.outbound.local.InMemoryPendingMarkerStore;
import com.jgo.engagement.adapter.outbound.local.InMemoryWorkLease;
import com.jgo.engagement.adapter.outbound.local.LocalReleaseCatalog;
import com.jgo.engagement.adapter.outbound.local.LoggingDeadLetterAdapter;
import com.jgo.engagement.adapter.outbound.local.SimulatedProbe;
import com.jgo.engagement.application.port.in.ProcessHeadChangeUseCase;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * In-memory stub adapters for the six outbound ports. Bound only under the
 * {@code local}/{@code dev} profiles; real adapters must be wired under
 * {@code !local/!dev} instead.
 */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "dev"})
public class LocalFanOutAdaptersConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(LocalFanOutAdaptersConfiguration.class);

    private final Environment environment;

    public LocalFanOutAdaptersConfiguration(Environment environment) {
        this.environment = environment;
    }

    /**
     * Startup safeguard: these stubs must never serve a real environment. The
     * {@code @Profile} guard already scopes them, so this only fires if the class is
     * somehow imported outside its intended profiles.
     */
    @PostConstruct
    void assertStubProfile() {
        if (!environment.acceptsProfiles(Profiles.of("local", "dev"))) {
            throw new IllegalStateException(
                    "Local in-memory fan-out adapters were wired without the 'local' or 'dev' "
                            + "profile. These stubs silently discard nothing but persist nothing: "
                            + "refusing to start with them in a real environment.");
        }
        LOG.warn("fanout.local_adapters active — in-memory stubs only, no real persistence");
    }

    @Bean
    InMemoryEngagementIndex engagementIndexPort() {
        return new InMemoryEngagementIndex();
    }

    @Bean
    InMemoryPendingMarkerStore pendingMarkerPort() {
        return new InMemoryPendingMarkerStore();
    }

    @Bean
    InMemoryWorkLease workLeasePort() {
        return new InMemoryWorkLease();
    }

    @Bean
    SimulatedProbe engagementStateProbePort(FanOutProperties properties) {
        return new SimulatedProbe(properties.local().probe());
    }

    @Bean
    LoggingDeadLetterAdapter deadLetterPort() {
        return new LoggingDeadLetterAdapter();
    }

    @Bean
    LocalReleaseCatalog releaseCatalogPort() {
        return new LocalReleaseCatalog();
    }

    @Bean
    LocalSmokeRunner localSmokeRunner(
            ProcessHeadChangeUseCase useCase,
            InMemoryEngagementIndex index,
            FanOutProperties properties) {
        return new LocalSmokeRunner(useCase, index, properties.local());
    }
}
