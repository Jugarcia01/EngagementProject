package com.jgo.engagement;

import com.jgo.engagement.adapter.inbound.local.LocalSmokeRunner;
import com.jgo.engagement.adapter.outbound.local.InMemoryEngagementIndex;
import com.jgo.engagement.adapter.outbound.local.InMemoryPendingMarkerStore;
import com.jgo.engagement.adapter.outbound.local.InMemoryWorkLease;
import com.jgo.engagement.adapter.outbound.local.LocalReleaseCatalog;
import com.jgo.engagement.adapter.outbound.local.LoggingDeadLetterAdapter;
import com.jgo.engagement.adapter.outbound.local.SimulatedProbe;
import com.jgo.engagement.application.port.in.ProcessHeadChangeUseCase;
import com.jgo.engagement.application.port.out.DeadLetterPort;
import com.jgo.engagement.application.port.out.EngagementIndexPort;
import com.jgo.engagement.application.port.out.EngagementStateProbePort;
import com.jgo.engagement.application.port.out.PendingMarkerPort;
import com.jgo.engagement.application.port.out.ReleaseCatalogPort;
import com.jgo.engagement.application.port.out.WorkLeasePort;
import com.jgo.engagement.domain.model.PendingMarker;
import com.jgo.engagement.domain.model.TemplateState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the full application context under the {@code local} profile: every outbound
 * port must resolve to its in-memory stub and the smoke runner must have driven one
 * synthetic head change through the whole fan-out.
 */
@SpringBootTest
@ActiveProfiles("local")
class LocalProfileWiringTest {

    @Autowired
    private ProcessHeadChangeUseCase useCase;

    @Autowired
    private EngagementIndexPort engagementIndexPort;

    @Autowired
    private PendingMarkerPort pendingMarkerPort;

    @Autowired
    private WorkLeasePort workLeasePort;

    @Autowired
    private EngagementStateProbePort engagementStateProbePort;

    @Autowired
    private DeadLetterPort deadLetterPort;

    @Autowired
    private ReleaseCatalogPort releaseCatalogPort;

    @Autowired
    private LocalSmokeRunner localSmokeRunner;

    @Test
    void allOutboundPortsResolveToLocalStubs() {
        assertInstanceOf(InMemoryEngagementIndex.class, engagementIndexPort);
        assertInstanceOf(InMemoryPendingMarkerStore.class, pendingMarkerPort);
        assertInstanceOf(InMemoryWorkLease.class, workLeasePort);
        assertInstanceOf(SimulatedProbe.class, engagementStateProbePort);
        assertInstanceOf(LoggingDeadLetterAdapter.class, deadLetterPort);
        assertInstanceOf(LocalReleaseCatalog.class, releaseCatalogPort);
        assertNotNull(useCase);
        assertNotNull(localSmokeRunner);
    }

    @Test
    void smokeRunProcessedSeededEngagementsEndToEnd() {
        InMemoryPendingMarkerStore store = (InMemoryPendingMarkerStore) pendingMarkerPort;
        InMemoryWorkLease leases = (InMemoryWorkLease) workLeasePort;

        assertEquals(3, store.size(), "smoke run must leave one pending marker per seeded engagement");
        assertTrue(store.all().stream().allMatch(this::isExpectedMarker));

        assertTrue(leases.isCompleted("local-smoke-001", "local-firm-0"));
        assertTrue(leases.isCompleted("local-smoke-001", "local-firm-1"));
        assertTrue(leases.isCompleted("local-smoke-001", "local-firm-2"));
        assertEquals(0, leases.activeLeaseCount());

        InMemoryEngagementIndex index = (InMemoryEngagementIndex) engagementIndexPort;
        assertEquals(3, index.size());
        assertTrue(index.findByTemplate("local-firm", "T-LOCAL", null, 10).stream()
                .allMatch(row -> row.state().verificationStatus()
                        == TemplateState.VerificationStatus.VERIFIED));
    }

    private boolean isExpectedMarker(PendingMarker marker) {
        return "T-LOCAL".equals(marker.templateId())
                && "v3".equals(marker.appliedVersion())
                && "v5".equals(marker.targetVersion())
                && "summary-v3-v5".equals(marker.summaryId())
                && marker.evaluatedAtSeq() == 100;
    }
}
