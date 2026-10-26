package com.jgo.engagement.domain.model;

import java.util.Objects;
import java.util.Set;

/**
 * State carried by an engagement-index entry.
 * Version ancestry is resolved by the release-catalog port, not guessed here.
 */
public record TemplateState(
        String templateId,
        String appliedVersion,
        Set<String> declinedVersions,
        VerificationStatus verificationStatus,
        long revision) {

    public TemplateState {
        requireText(templateId, "templateId");
        requireText(appliedVersion, "appliedVersion");
        declinedVersions = Set.copyOf(Objects.requireNonNull(declinedVersions, "declinedVersions"));
        Objects.requireNonNull(verificationStatus, "verificationStatus");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum VerificationStatus {
        VERIFIED,
        UNVERIFIED
    }
}
