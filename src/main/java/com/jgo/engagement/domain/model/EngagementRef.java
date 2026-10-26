package com.jgo.engagement.domain.model;

import java.util.Objects;

/** Stable reference to an engagement within its regional firm boundary. */
public record EngagementRef(String firmId, String engagementId) {
    public EngagementRef {
        requireText(firmId, "firmId");
        requireText(engagementId, "engagementId");
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
