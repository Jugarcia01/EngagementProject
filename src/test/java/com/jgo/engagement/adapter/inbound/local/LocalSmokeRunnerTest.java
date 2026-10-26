package com.jgo.engagement.adapter.inbound.local;

import com.jgo.engagement.adapter.outbound.local.InMemoryEngagementIndex;
import com.jgo.engagement.application.port.in.ProcessHeadChangeUseCase;
import com.jgo.engagement.application.port.out.EngagementIndexPort.IndexedEngagement;
import com.jgo.engagement.config.FanOutProperties;
import com.jgo.engagement.domain.model.HeadChange;
import com.jgo.engagement.domain.model.TemplateState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalSmokeRunnerTest {

    @Test
    void runSeedsIndexAndProcessesTheConfiguredChange() throws Exception {
        AtomicReference<HeadChange> processed = new AtomicReference<>();
        ProcessHeadChangeUseCase useCase = processed::set;
        InMemoryEngagementIndex index = new InMemoryEngagementIndex();
        FanOutProperties.Local local = local(true, true);

        new LocalSmokeRunner(useCase, index, local).run(new DefaultApplicationArguments());

        HeadChange change = processed.get();
        assertNotNull(change);
        assertEquals("smoke-x", change.changeId());
        assertEquals("T-TEST", change.templateId());
        assertEquals("v9", change.headVersion());
        assertEquals(42, change.catalogSeq());

        List<IndexedEngagement> rows = index.findByTemplate("firm-a", "T-TEST", null, 10);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(row ->
                row.state().verificationStatus() == TemplateState.VerificationStatus.UNVERIFIED
                        && "v3".equals(row.state().appliedVersion())));
    }

    @Test
    void disabledSmokeDoesNothing() throws Exception {
        AtomicReference<HeadChange> processed = new AtomicReference<>();
        InMemoryEngagementIndex index = new InMemoryEngagementIndex();

        new LocalSmokeRunner(processed::set, index, local(true, false))
                .run(new DefaultApplicationArguments());

        assertNull(processed.get());
        assertEquals(0, index.size());
    }

    @Test
    void disabledSeedStillRunsTheChange() throws Exception {
        AtomicReference<HeadChange> processed = new AtomicReference<>();
        InMemoryEngagementIndex index = new InMemoryEngagementIndex();

        new LocalSmokeRunner(processed::set, index, local(false, true))
                .run(new DefaultApplicationArguments());

        assertNotNull(processed.get());
        assertEquals(0, index.size());
    }

    @Test
    void constructorRejectsNullDependencies() {
        FanOutProperties.Local local = local(true, true);
        InMemoryEngagementIndex index = new InMemoryEngagementIndex();
        assertThrows(NullPointerException.class, () -> new LocalSmokeRunner(null, index, local));
        assertThrows(NullPointerException.class, () -> new LocalSmokeRunner(c -> { }, null, local));
        assertThrows(NullPointerException.class, () -> new LocalSmokeRunner(c -> { }, index, null));
    }

    private static FanOutProperties.Local local(boolean seedEnabled, boolean smokeEnabled) {
        return new FanOutProperties.Local(
                new FanOutProperties.Local.Seed(
                        seedEnabled, List.of("firm-a"), 2, "T-TEST", "v3"),
                new FanOutProperties.Local.Probe(
                        Duration.ZERO, FanOutProperties.FailureMode.NONE, 0),
                new FanOutProperties.Local.Smoke(
                        smokeEnabled, "smoke-x", "v9", 42));
    }
}
