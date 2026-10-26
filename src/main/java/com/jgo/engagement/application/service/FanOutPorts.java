package com.jgo.engagement.application.service;

import com.jgo.engagement.application.port.out.DeadLetterPort;
import com.jgo.engagement.application.port.out.EngagementIndexPort;
import com.jgo.engagement.application.port.out.EngagementStateProbePort;
import com.jgo.engagement.application.port.out.FanOutMetricsPort;
import com.jgo.engagement.application.port.out.PendingMarkerPort;
import com.jgo.engagement.application.port.out.ReleaseCatalogPort;
import com.jgo.engagement.application.port.out.WorkLeasePort;

import java.util.Objects;

/** The outbound ports the fan-out service needs, grouped to keep wiring readable. */
public record FanOutPorts(
        EngagementIndexPort index,
        PendingMarkerPort pendingMarkers,
        WorkLeasePort leases,
        EngagementStateProbePort probes,
        DeadLetterPort deadLetters,
        ReleaseCatalogPort releaseCatalog,
        FanOutMetricsPort metrics) {

    public FanOutPorts {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(pendingMarkers, "pendingMarkers");
        Objects.requireNonNull(leases, "leases");
        Objects.requireNonNull(probes, "probes");
        Objects.requireNonNull(deadLetters, "deadLetters");
        Objects.requireNonNull(releaseCatalog, "releaseCatalog");
        Objects.requireNonNull(metrics, "metrics");
    }
}
