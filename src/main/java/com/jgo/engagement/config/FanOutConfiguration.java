package com.jgo.engagement.config;

import com.jgo.engagement.adapter.out.metrics.Slf4jFanOutMetricsAdapter;
import com.jgo.engagement.application.port.in.ProcessHeadChangeUseCase;
import com.jgo.engagement.application.port.out.DeadLetterPort;
import com.jgo.engagement.application.port.out.EngagementIndexPort;
import com.jgo.engagement.application.port.out.EngagementStateProbePort;
import com.jgo.engagement.application.port.out.FanOutMetricsPort;
import com.jgo.engagement.application.port.out.PendingMarkerPort;
import com.jgo.engagement.application.port.out.ReleaseCatalogPort;
import com.jgo.engagement.application.port.out.WorkLeasePort;
import com.jgo.engagement.application.service.ProcessHeadChangeService;
import com.jgo.engagement.application.service.Settings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FanOutConfiguration {

    @Bean
    public FanOutMetricsPort fanOutMetricsPort() {
        return new Slf4jFanOutMetricsAdapter();
    }

    @Bean
    public ProcessHeadChangeUseCase processHeadChangeUseCase(
            EngagementIndexPort engagementIndexPort,
            PendingMarkerPort pendingMarkerPort,
            WorkLeasePort workLeasePort,
            EngagementStateProbePort engagementStateProbePort,
            DeadLetterPort deadLetterPort,
            ReleaseCatalogPort releaseCatalogPort,
            FanOutMetricsPort fanOutMetricsPort) {
        return new ProcessHeadChangeService(
                engagementIndexPort,
                pendingMarkerPort,
                workLeasePort,
                engagementStateProbePort,
                deadLetterPort,
                releaseCatalogPort,
                fanOutMetricsPort,
                Settings.defaults());
    }
}
