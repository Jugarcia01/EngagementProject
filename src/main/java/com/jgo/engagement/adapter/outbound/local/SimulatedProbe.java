package com.jgo.engagement.adapter.outbound.local;

import com.jgo.engagement.application.port.out.EngagementStateProbePort;
import com.jgo.engagement.application.port.out.ProbeException;
import com.jgo.engagement.config.FanOutProperties;
import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.TemplateState;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Local-profile {@link EngagementStateProbePort}. Returns the indexed state marked
 * {@code VERIFIED} with a bumped revision; latency and a bounded failure budget are
 * configurable via {@code fanout.local.probe.*} so retries, throttling and dead-letter
 * paths can be exercised manually.
 */
public final class SimulatedProbe implements EngagementStateProbePort {
    private final long latencyNanos;
    private volatile ProbeException.Kind failureKind;
    private final AtomicInteger failureBudget = new AtomicInteger();

    public SimulatedProbe(FanOutProperties.Local.Probe props) {
        this(
                Objects.requireNonNull(props, "props").latency().toNanos(),
                toKind(props.failureMode()),
                props.failureBudget());
    }

    public SimulatedProbe(java.time.Duration latency, ProbeException.Kind failureKind, int failureBudget) {
        this(latency.toNanos(), failureKind, failureBudget);
    }

    private SimulatedProbe(long latencyNanos, ProbeException.Kind failureKind, int failureBudget) {
        this.latencyNanos = Math.max(0, latencyNanos);
        this.failureKind = failureKind;
        this.failureBudget.set(Math.max(0, failureBudget));
    }

    @Override
    public TemplateState verify(EngagementRef engagement, TemplateState indexedState, String targetVersion)
            throws ProbeException {
        Objects.requireNonNull(indexedState, "indexedState");
        if (latencyNanos > 0) {
            LockSupport.parkNanos(latencyNanos);
        }
        if (failureKind != null
                && failureBudget.getAndUpdate(remaining -> remaining > 0 ? remaining - 1 : 0) > 0) {
            throw new ProbeException(failureKind, "simulated " + failureKind.name().toLowerCase()
                    + " probe failure for " + engagement.engagementId());
        }
        return new TemplateState(
                indexedState.templateId(),
                indexedState.appliedVersion(),
                indexedState.declinedVersions(),
                TemplateState.VerificationStatus.VERIFIED,
                indexedState.revision() + 1);
    }

    /** Manual simulation hook: how the probe fails while the budget lasts (null = never). */
    public void setFailureKind(ProbeException.Kind failureKind) {
        this.failureKind = failureKind;
    }

    /** Manual simulation hook: number of upcoming probes that will fail. */
    public void setFailureBudget(int failureBudget) {
        this.failureBudget.set(Math.max(0, failureBudget));
    }

    private static ProbeException.Kind toKind(FanOutProperties.FailureMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case TRANSIENT -> ProbeException.Kind.TRANSIENT;
            case PERMANENT -> ProbeException.Kind.PERMANENT;
            case THROTTLED -> ProbeException.Kind.THROTTLED;
            case NONE -> null;
        };
    }
}
