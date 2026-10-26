package com.jgo.engagement.application.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettingsTest {

    @Test
    void defaultsMatchApprovedAssumptions() {
        Settings settings = Settings.defaults();

        assertEquals(8, settings.maxConcurrency());
        assertEquals(4, settings.maxAttempts());
        assertEquals(10, settings.maxThrottleAttempts());
        assertEquals(Duration.ofSeconds(2), settings.baseBackoff());
        assertEquals(Duration.ofSeconds(60), settings.maxBackoff());
        assertEquals(Duration.ofMinutes(5), settings.leaseDuration());
        assertEquals(500, settings.pageSize());
    }

    @Test
    void rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(0, 4, 10, Duration.ZERO, Duration.ZERO, Duration.ofMinutes(1), 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 0, 10, Duration.ZERO, Duration.ZERO, Duration.ofMinutes(1), 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 0, Duration.ZERO, Duration.ZERO, Duration.ofMinutes(1), 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, Duration.ZERO, Duration.ofMinutes(1), 0));
    }

    @Test
    void rejectsNullDurations() {
        assertThrows(NullPointerException.class, () ->
                new Settings(1, 1, 1, null, Duration.ZERO, Duration.ofMinutes(1), 1));
        assertThrows(NullPointerException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, null, Duration.ofMinutes(1), 1));
        assertThrows(NullPointerException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, Duration.ZERO, null, 1));
    }

    @Test
    void rejectsNegativeDurationsAndNonPositiveLease() {
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 1, Duration.ofMillis(-1), Duration.ZERO,
                        Duration.ofMinutes(1), 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, Duration.ofMillis(-1),
                        Duration.ofMinutes(1), 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, Duration.ZERO, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, Duration.ZERO, Duration.ofMillis(-1), 1));
    }
}
