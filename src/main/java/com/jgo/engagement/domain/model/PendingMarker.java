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
        engagement = Objects.requireNonNull(engagement, "engagement");
        templateId = requireText(templateId, "templateId");
        appliedVersion = requireText(appliedVersion, "appliedVersion");
        targetVersion = requireText(targetVersion, "targetVersion");
        summaryId = requireText(summaryId, "summaryId");
        if (evaluatedAtSeq < 0) {
            throw new IllegalArgumentException("evaluatedAtSeq must be >= 0");
        }
        evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
