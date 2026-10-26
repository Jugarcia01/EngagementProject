package com.jgo.engagement.adapter.inbound.local;

import com.jgo.engagement.adapter.outbound.local.InMemoryEngagementIndex;
import com.jgo.engagement.application.port.in.ProcessHeadChangeUseCase;
import com.jgo.engagement.config.FanOutProperties;
import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.HeadChange;
import com.jgo.engagement.domain.model.TemplateState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.Objects;
import java.util.Set;

/**
 * Local-profile smoke driver: seeds the in-memory index with synthetic unverified
 * engagements, then fires one synthetic {@link HeadChange} through
 * {@link ProcessHeadChangeUseCase#process} so all six stub adapters are exercised
 * together on startup. Disabled via {@code fanout.local.smoke.enabled: false}.
 */
public final class LocalSmokeRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(LocalSmokeRunner.class);

    private final ProcessHeadChangeUseCase useCase;
    private final InMemoryEngagementIndex index;
    private final FanOutProperties.Local local;

    public LocalSmokeRunner(
            ProcessHeadChangeUseCase useCase,
            InMemoryEngagementIndex index,
            FanOutProperties.Local local) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.index = Objects.requireNonNull(index, "index");
        this.local = Objects.requireNonNull(local, "local");
    }

    @Override
    public void run(ApplicationArguments args) {
        FanOutProperties.Local.Smoke smoke = local.smoke();
        if (!smoke.enabled()) {
            LOG.info("fanout.smoke.skipped reason=disabled");
            return;
        }

        int seeded = seedIndex();
        HeadChange change = new HeadChange(
                smoke.changeId(),
                local.seed().templateId(),
                smoke.headVersion(),
                smoke.catalogSeq());

        LOG.info(
                "fanout.smoke.start changeId={} templateId={} headVersion={} catalogSeq={} seededEngagements={}",
                change.changeId(),
                change.templateId(),
                change.headVersion(),
                change.catalogSeq(),
                seeded);
        useCase.process(change);
        LOG.info("fanout.smoke.done changeId={}", change.changeId());
    }

    private int seedIndex() {
        FanOutProperties.Local.Seed seed = local.seed();
        if (!seed.enabled()) {
            return 0;
        }
        int count = 0;
        for (String firm : seed.firms()) {
            for (int i = 0; i < seed.engagementsPerFirm(); i++) {
                index.add(
                        new EngagementRef(firm, firm + "-" + i),
                        new TemplateState(
                                seed.templateId(),
                                seed.appliedVersion(),
                                Set.of(),
                                TemplateState.VerificationStatus.UNVERIFIED,
                                0));
                count++;
            }
        }
        return count;
    }
}
