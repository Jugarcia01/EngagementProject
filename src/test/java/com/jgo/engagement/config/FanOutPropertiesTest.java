package com.jgo.engagement.config;

import com.jgo.engagement.application.service.Settings;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class FanOutPropertiesTest {

    @Test
    void toSettingsMapsEveryBoundValue() {
        FanOutProperties properties = new FanOutProperties(
                3, 5, 7,
                Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMinutes(1),
                25, null);

        Settings settings = properties.toSettings();

        assertEquals(3, settings.maxConcurrency());
        assertEquals(5, settings.maxAttempts());
        assertEquals(7, settings.maxThrottleAttempts());
        assertEquals(Duration.ofMillis(10), settings.baseBackoff());
        assertEquals(Duration.ofMillis(50), settings.maxBackoff());
        assertEquals(Duration.ofMinutes(1), settings.leaseDuration());
        assertEquals(25, settings.pageSize());
    }

    @Test
    void nullLocalSectionGetsDefaults() {
        FanOutProperties properties = new FanOutProperties(
                8, 4, 10,
                Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofMinutes(5),
                500, null);

        assertTrue(properties.local().seed().enabled());
        assertTrue(properties.local().smoke().enabled());
        assertEquals(FanOutProperties.FailureMode.NONE, properties.local().probe().failureMode());
        assertEquals(0, properties.local().probe().failureBudget());
    }

    @Test
    void nullNestedSectionsGetDefaults() {
        FanOutProperties.Local local = new FanOutProperties.Local(null, null, null);

        assertNotNull(local.seed());
        assertNotNull(local.probe());
        assertNotNull(local.smoke());
        assertEquals("local-smoke-001", local.smoke().changeId());
    }
}
