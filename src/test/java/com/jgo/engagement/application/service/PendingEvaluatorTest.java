package com.jgo.engagement.application.service;

import com.jgo.engagement.application.port.out.ReleaseCatalogPort;
import com.jgo.engagement.domain.model.TemplateState;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PendingEvaluatorTest {
    private final ReleaseCatalogPort catalog = new ReleaseCatalogPort() {
        @Override
        public Optional<ReleaseHead> findHead(String templateId) {
            return Optional.empty();
        }

        @Override
        public boolean isAncestor(String templateId, String ancestorVersion, String descendantVersion) {
            return switch (ancestorVersion + "->" + descendantVersion) {
                case "v4->v7", "v5->v7", "v4->v5", "v5->v6", "v3->v5", "v3->v7", "v6->v7" -> true;
                default -> false;
            };
        }

        @Override
        public Optional<String> findSummaryId(String templateId, String fromVersion, String toVersion) {
            return Optional.of(fromVersion + "-" + toVersion);
        }
    };

    private final PendingEvaluator evaluator = new PendingEvaluator(catalog);

    @Test
    void offersHeadWhenItIsADescendantOfAppliedVersion() {
        var state = state("v4", Set.of());

        var result = evaluator.evaluate(state, "v7");

        assertTrue(result.pending());
        assertEquals("v4", result.fromVersion());
        assertEquals("v7", result.toVersion());
    }

    @Test
    void decliningOneVersionSuppressesThatExactTarget() {
        var state = state("v4", Set.of("v5"));

        assertFalse(evaluator.evaluate(state, "v5").pending());
        assertTrue(evaluator.evaluate(state, "v7").pending());
    }

    @Test
    void doesNotOfferSameVersionOrWithdrawnHead() {
        var state = state("v4", Set.of());

        assertFalse(evaluator.evaluate(state, "v4").pending());
        assertFalse(evaluator.evaluate(state, null).pending());
        assertFalse(evaluator.evaluate(state, " ").pending());
    }

    @Test
    void doesNotOfferCrossBranchOrUnknownAncestry() {
        var state = state("v4", Set.of());

        assertFalse(evaluator.evaluate(state, "v9").pending());
    }

    @Test
    void ancestorOfAppliedHeadIsNotPending() {
        var result = evaluator.evaluate(state("v6", Set.of()), "v5");

        assertFalse(result.pending());
        assertEquals("TARGET_IS_ANCESTOR_OF_APPLIED", result.reason());
    }

    @Test
    void branchVersionIsNotAutoOffered() {
        var result = evaluator.evaluate(state("v4x", Set.of()), "v5");

        assertFalse(result.pending());
        assertEquals("CROSS_BRANCH_OR_UNKNOWN_ANCESTRY", result.reason());
    }

    @Test
    void withdrawalRecomputesHeadAndDeclinedFallbackIsQuiet() {
        var state = state("v3", Set.of("v5"));

        assertTrue(evaluator.evaluate(state, "v7").pending());

        var withdrawn = evaluator.evaluate(state, null);
        assertFalse(withdrawn.pending());
        assertEquals("NO_PUBLISHED_HEAD", withdrawn.reason());

        var declinedFallback = evaluator.evaluate(state, "v5");
        assertFalse(declinedFallback.pending());
        assertEquals("TARGET_VERSION_DECLINED", declinedFallback.reason());
    }

    @Test
    void cumulativeDiffIsAppliedToHead() {
        var result = evaluator.evaluate(state("v3", Set.of("v5")), "v7");

        assertTrue(result.pending());
        assertEquals("v3", result.fromVersion());
        assertEquals("v7", result.toVersion());
        assertEquals("PENDING_UPDATE", result.reason());
    }

    private static TemplateState state(String appliedVersion, Set<String> declined) {
        return new TemplateState("template-1", appliedVersion, declined,
                TemplateState.VerificationStatus.VERIFIED, 1);
    }
}
