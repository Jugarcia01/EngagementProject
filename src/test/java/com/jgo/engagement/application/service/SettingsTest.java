package com.jgo.engagement.application.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettingsTest {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final Duration NEGATIVE_ONE_MILLI = Duration.ofMillis(-1);

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
                new Settings(0, 4, 10, Duration.ZERO, Duration.ZERO, ONE_MINUTE, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 0, 10, Duration.ZERO, Duration.ZERO, ONE_MINUTE, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 0, Duration.ZERO, Duration.ZERO, ONE_MINUTE, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, Duration.ZERO, ONE_MINUTE, 0));
    }

    @Test
    void rejectsNullDurations() {
        assertThrows(NullPointerException.class, () ->
                new Settings(1, 1, 1, null, Duration.ZERO, ONE_MINUTE, 1));
        assertThrows(NullPointerException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, null, ONE_MINUTE, 1));
        assertThrows(NullPointerException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, Duration.ZERO, null, 1));
    }

    @Test
    void rejectsNegativeDurationsAndNonPositiveLease() {
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 1, NEGATIVE_ONE_MILLI, Duration.ZERO, ONE_MINUTE, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, NEGATIVE_ONE_MILLI, ONE_MINUTE, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, Duration.ZERO, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new Settings(1, 1, 1, Duration.ZERO, Duration.ZERO, NEGATIVE_ONE_MILLI, 1));
    }
}
