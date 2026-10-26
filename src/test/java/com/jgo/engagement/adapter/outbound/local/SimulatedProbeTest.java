package com.jgo.engagement.adapter.outbound.local;

import com.jgo.engagement.application.port.out.ProbeException;
import com.jgo.engagement.config.FanOutProperties;
import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.TemplateState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedProbeTest {
    private static final EngagementRef ENGAGEMENT = new EngagementRef("f1", "e1");

    @Test
    void successfulProbeMarksStateVerifiedAndBumpsRevision() throws ProbeException {
        SimulatedProbe probe = new SimulatedProbe(Duration.ZERO, null, 0);
        TemplateState indexed = indexedState();

        TemplateState verified = probe.verify(ENGAGEMENT, indexed, "v5");

        assertEquals(TemplateState.VerificationStatus.VERIFIED, verified.verificationStatus());
        assertEquals(indexed.revision() + 1, verified.revision());
        assertEquals(indexed.appliedVersion(), verified.appliedVersion());
        assertEquals(indexed.declinedVersions(), verified.declinedVersions());
    }

    @Test
    void failureBudgetProbesThenSucceeds() throws ProbeException {
        SimulatedProbe probe = new SimulatedProbe(
                Duration.ZERO, ProbeException.Kind.TRANSIENT, 2);

        ProbeException first = assertThrows(ProbeException.class,
                () -> probe.verify(ENGAGEMENT, indexedState(), "v5"));
        assertEquals(ProbeException.Kind.TRANSIENT, first.kind());
        assertThrows(ProbeException.class, () -> probe.verify(ENGAGEMENT, indexedState(), "v5"));

        assertNotNull(probe.verify(ENGAGEMENT, indexedState(), "v5"));
    }

    @Test
    void eachFailureModeMapsToItsProbeKind() {
        assertThrows(ProbeException.class, () -> probeWith(FanOutProperties.FailureMode.TRANSIENT, 1)
                .verify(ENGAGEMENT, indexedState(), "v5"));
        ProbeException permanent = assertThrows(ProbeException.class, () -> probeWith(FanOutProperties.FailureMode.PERMANENT, 1)
                .verify(ENGAGEMENT, indexedState(), "v5"));
        assertEquals(ProbeException.Kind.PERMANENT, permanent.kind());
        ProbeException throttled = assertThrows(ProbeException.class, () -> probeWith(FanOutProperties.FailureMode.THROTTLED, 1)
                .verify(ENGAGEMENT, indexedState(), "v5"));
        assertEquals(ProbeException.Kind.THROTTLED, throttled.kind());
    }

    @Test
    void manualSimulationHooksOverrideTheBudget() {
        SimulatedProbe probe = new SimulatedProbe(Duration.ZERO, null, 0);
        probe.setFailureKind(ProbeException.Kind.THROTTLED);
        probe.setFailureBudget(1);

        ProbeException failure = assertThrows(ProbeException.class,
                () -> probe.verify(ENGAGEMENT, indexedState(), "v5"));
        assertEquals(ProbeException.Kind.THROTTLED, failure.kind());
        assertDoesNotThrow(() -> probe.verify(ENGAGEMENT, indexedState(), "v5"));
    }

    private static SimulatedProbe probeWith(FanOutProperties.FailureMode mode, int budget) {
        return new SimulatedProbe(new FanOutProperties.Local.Probe(Duration.ZERO, mode, budget));
    }

    private static TemplateState indexedState() {
        return new TemplateState(
                "T",
                "v3",
                Set.of(),
                TemplateState.VerificationStatus.UNVERIFIED,
                1);
    }
}
