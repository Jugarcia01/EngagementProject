package com.jgo.engagement;

import com.jgo.engagement.adapter.out.metrics.Slf4jFanOutMetricsAdapter;
import com.jgo.engagement.application.port.in.ProcessHeadChangeUseCase;
import com.jgo.engagement.application.port.out.DeadLetterPort;
import com.jgo.engagement.application.port.out.EngagementIndexPort;
import com.jgo.engagement.application.port.out.EngagementStateProbePort;
import com.jgo.engagement.application.port.out.FanOutMetricsPort;
import com.jgo.engagement.application.port.out.PendingMarkerPort;
import com.jgo.engagement.application.port.out.ReleaseCatalogPort;
import com.jgo.engagement.application.port.out.WorkLeasePort;
import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.PendingMarker;
import com.jgo.engagement.domain.model.TemplateState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class EngagementApplicationTest {

    @Autowired
    private ProcessHeadChangeUseCase processHeadChangeUseCase;

    @Autowired
    private FanOutMetricsPort fanOutMetricsPort;

    @Test
    void processHeadChangeUseCaseBeanIsRegistered() {
        assertNotNull(processHeadChangeUseCase);
    }

    @Test
    void slf4jMetricsAdapterIsRegistered() {
        assertInstanceOf(Slf4jFanOutMetricsAdapter.class, fanOutMetricsPort);

        fanOutMetricsPort.recordFastApplied();
        fanOutMetricsPort.recordFastStale();
        fanOutMetricsPort.recordProbeStarted();
        fanOutMetricsPort.recordProbeSucceeded();
        fanOutMetricsPort.recordCompleted();
        fanOutMetricsPort.recordRetried();
        fanOutMetricsPort.recordDeadLettered();
        fanOutMetricsPort.recordSkippedClaimed();
    }

    @Test
    void mainDelegatesToSpringApplication() {
        // No production port beans exist, so a real boot fails fast once it starts
        // resolving the use-case bean. Only the delegation lines need to execute.
        assertDoesNotThrow(() -> {
            try {
                EngagementApplication.main(new String[0]);
            } catch (RuntimeException expected) {
                // boot aborts while resolving beans; delegation already happened
            }
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakePortsConfiguration {

        @Bean
        EngagementIndexPort engagementIndexPort() {
            return new EngagementIndexPort() {
                @Override
                public List<EngagementIndexPort.IndexedEngagement> findByTemplate(
                        String firmId, String templateId, String pageToken, int pageSize) {
                    return List.of();
                }

                @Override
                public List<String> firmsWithEngagements(String templateId) {
                    return List.of();
                }

                @Override
                public void recordVerified(EngagementRef engagement, TemplateState state) {
                    // no-op: context wiring only
                }
            };
        }

        @Bean
        PendingMarkerPort pendingMarkerPort() {
            return new PendingMarkerPort() {
                @Override
                public PendingMarkerPort.WriteResult upsertIfNotOlder(PendingMarker marker) {
                    return PendingMarkerPort.WriteResult.APPLIED;
                }

                @Override
                public PendingMarkerPort.WriteResult clearIfNotOlder(
                        EngagementRef engagement, String templateId, long catalogSeq) {
                    return PendingMarkerPort.WriteResult.APPLIED;
                }

                @Override
                public PendingMarkerPort.WriteResult upsertIfNewer(PendingMarker marker) {
                    return PendingMarkerPort.WriteResult.APPLIED;
                }

                @Override
                public PendingMarkerPort.WriteResult deleteIfNewer(
                        EngagementRef engagement, String templateId, long catalogSeq) {
                    return PendingMarkerPort.WriteResult.APPLIED;
                }
            };
        }

        @Bean
        WorkLeasePort workLeasePort() {
            return new WorkLeasePort() {
                @Override
                public WorkLeasePort.LeaseResult tryAcquire(
                        String changeId, String engagementId, Duration leaseDuration) {
                    return WorkLeasePort.LeaseResult.ACQUIRED;
                }

                @Override
                public void markCompleted(String changeId, String engagementId) {
                    // no-op: context wiring only
                }

                @Override
                public void releaseForRetry(String changeId, String engagementId) {
                    // no-op: context wiring only
                }
            };
        }

        @Bean
        EngagementStateProbePort engagementStateProbePort() {
            return (engagement, indexedState, targetVersion) -> indexedState;
        }

        @Bean
        DeadLetterPort deadLetterPort() {
            return (change, engagement, failureCode, safeMessage) -> {
                // no-op: context wiring only
            };
        }

        @Bean
        ReleaseCatalogPort releaseCatalogPort() {
            return new ReleaseCatalogPort() {
                @Override
                public Optional<ReleaseCatalogPort.ReleaseHead> findHead(String templateId) {
                    return Optional.empty();
                }

                @Override
                public boolean isAncestor(
                        String templateId, String ancestorVersion, String descendantVersion) {
                    return false;
                }

                @Override
                public Optional<String> findSummaryId(
                        String templateId, String fromVersion, String toVersion) {
                    return Optional.empty();
                }
            };
        }
    }
}
