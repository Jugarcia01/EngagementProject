package com.jgo.engagement.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TemplateStateTest {
    @Test
    void copiesDeclinedVersionsToPreventExternalMutation() {
        var declined = new java.util.HashSet<>(Set.of("v5"));
        var state = new TemplateState("template-1", "v4", declined,
                TemplateState.VerificationStatus.VERIFIED, 1);
        declined.add("v6");

        assertEquals(Set.of("v5"), state.declinedVersions());
    }

    @Test
    void rejectsNegativeRevision() {
        assertThrows(IllegalArgumentException.class, () ->
                new TemplateState("template-1", "v4", Set.of(),
                        TemplateState.VerificationStatus.VERIFIED, -1));
        assertThrows(IllegalArgumentException.class, () ->
                new TemplateState(" ", "v4", Set.of(),
                        TemplateState.VerificationStatus.VERIFIED, 1));
    }
}
