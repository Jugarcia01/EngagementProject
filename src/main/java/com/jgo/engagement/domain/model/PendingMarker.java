package com.jgo.engagement.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Materialized, recomputable pending indicator for one engagement/template pair. */
public record PendingMarker(
        EngagementRef engagement,
        String templateId,
        String appliedVersion,
        String targetVersion,
        String summaryId,
        long evaluatedAtSeq,
        Instant evaluatedAt) {

    public PendingMarker {
        Objects.requireNonNull(engagement, "engagement");
        requireText(templateId, "templateId");
        requireText(appliedVersion, "appliedVersion");
        requireText(targetVersion, "targetVersion");
        requireText(summaryId, "summaryId");
        if (evaluatedAtSeq < 0) {
            throw new IllegalArgumentException("evaluatedAtSeq must be >= 0");
        }
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
