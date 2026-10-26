package com.jgo.engagement.application.service;

import java.time.Duration;
import java.util.Objects;

/** Adjustable fan-out assumptions. These are defaults, not measurements. */
public record Settings(
        int maxConcurrency,
        int maxAttempts,
        int maxThrottleAttempts,
        Duration baseBackoff,
        Duration maxBackoff,
        Duration leaseDuration,
        int pageSize) {

    public Settings {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (maxThrottleAttempts < 1) {
            throw new IllegalArgumentException("maxThrottleAttempts must be >= 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        baseBackoff = Objects.requireNonNull(baseBackoff, "baseBackoff");
        maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff");
        leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (baseBackoff.isNegative()) {
            throw new IllegalArgumentException("baseBackoff must be >= 0");
        }
        if (maxBackoff.isNegative()) {
            throw new IllegalArgumentException("maxBackoff must be >= 0");
        }
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be > 0");
        }
    }

    public Settings() {
        this(8, 4, 10, Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofMinutes(5), 500);
    }

    public static Settings defaults() {
        return new Settings();
    }
}
