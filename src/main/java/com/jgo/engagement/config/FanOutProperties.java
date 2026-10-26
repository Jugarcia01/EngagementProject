package com.jgo.engagement.config;

import com.jgo.engagement.application.service.Settings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Externalized fan-out knobs. Production adapters consume the top-level settings;
 * the {@link Local} section tunes the in-memory stub adapters that only exist under
 * the {@code local}/{@code dev} profiles.
 */
@ConfigurationProperties(prefix = "fanout")
public record FanOutProperties(
        @DefaultValue("8") int maxConcurrency,
        @DefaultValue("4") int maxAttempts,
        @DefaultValue("10") int maxThrottleAttempts,
        @DefaultValue("2s") Duration baseBackoff,
        @DefaultValue("60s") Duration maxBackoff,
        @DefaultValue("5m") Duration leaseDuration,
        @DefaultValue("500") int pageSize,
        Local local) {

    public FanOutProperties {
        if (local == null) {
            local = new Local(null, null, null);
        }
    }

    public Settings toSettings() {
        return new Settings(
                maxConcurrency,
                maxAttempts,
                maxThrottleAttempts,
                baseBackoff,
                maxBackoff,
                leaseDuration,
                pageSize);
    }

    /** Knobs for the in-memory stub adapters; ignored by real adapters. */
    public record Local(Seed seed, Probe probe, Smoke smoke) {

        public Local {
            if (seed == null) {
                seed = new Seed(true, List.of("local-firm"), 3, "T-LOCAL", "v3");
            }
            if (probe == null) {
                probe = new Probe(Duration.ZERO, FailureMode.NONE, 0);
            }
            if (smoke == null) {
                smoke = new Smoke(true, "local-smoke-001", "v5", 100);
            }
        }

        /** Synthetic engagements written into the local index before the smoke change. */
        public record Seed(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("local-firm") List<String> firms,
                @DefaultValue("3") int engagementsPerFirm,
                @DefaultValue("T-LOCAL") String templateId,
                @DefaultValue("v3") String appliedVersion) {
        }

        /** Simulated downstream behavior for the local probe adapter. */
        public record Probe(
                @DefaultValue("0ms") Duration latency,
                @DefaultValue("none") FailureMode failureMode,
                @DefaultValue("0") int failureBudget) {
        }

        /** Synthetic head change executed once on startup to exercise the whole fan-out. */
        public record Smoke(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("local-smoke-001") String changeId,
                @DefaultValue("v5") String headVersion,
                @DefaultValue("100") long catalogSeq) {
        }
    }

    /** Failure flavor the local probe should simulate while its budget lasts. */
    public enum FailureMode {
        NONE,
        TRANSIENT,
        PERMANENT,
        THROTTLED
    }
}
