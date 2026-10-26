package com.jgo.engagement.domain.model;

import java.util.Objects;

/**
 * State-carrying catalog event. A null headVersion represents no currently published head
 * (for example, after withdrawal); consumers must use catalogSeq for ordering/fencing.
 */
public record HeadChange(
        String changeId,
        String templateId,
        String headVersion,
        long catalogSeq) {

    public HeadChange {
        requireText(changeId, "changeId");
        requireText(templateId, "templateId");
        if (headVersion != null && headVersion.isBlank()) {
            throw new IllegalArgumentException("headVersion must be null or non-blank");
        }
        if (catalogSeq < 0) {
            throw new IllegalArgumentException("catalogSeq must be >= 0");
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
