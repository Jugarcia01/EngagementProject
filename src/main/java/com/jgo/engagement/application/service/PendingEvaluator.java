package com.jgo.engagement.application.service;

import com.jgo.engagement.domain.model.TemplateState;
import com.jgo.engagement.application.port.out.ReleaseCatalogPort;

import java.util.Objects;

/**
 * Pure decision policy backed by release-catalog ancestry.
 * A declined target suppresses that exact version only; it does not advance the baseline.
 */
public final class PendingEvaluator {
    private final ReleaseCatalogPort releaseCatalog;

    public PendingEvaluator(ReleaseCatalogPort releaseCatalog) {
        this.releaseCatalog = Objects.requireNonNull(releaseCatalog, "releaseCatalog");
    }

    public Evaluation evaluate(TemplateState state, String currentHeadVersion) {
        Objects.requireNonNull(state, "state");

        if (currentHeadVersion == null || currentHeadVersion.isBlank()) {
            return Evaluation.notPending("NO_PUBLISHED_HEAD");
        }

        String applied = state.appliedVersion();
        if (currentHeadVersion.equals(applied)) {
            return Evaluation.notPending("ALREADY_APPLIED");
        }
        if (state.declinedVersions().contains(currentHeadVersion)) {
            return Evaluation.notPending("TARGET_VERSION_DECLINED");
        }

        boolean appliedIsAncestor = releaseCatalog.isAncestor(
                state.templateId(), applied, currentHeadVersion);
        if (!appliedIsAncestor) {
            return Evaluation.notPending("CROSS_BRANCH_OR_UNKNOWN_ANCESTRY");
        }

        // A target that is an ancestor of the applied version is not a forward update.
        boolean headIsAncestorOfApplied = releaseCatalog.isAncestor(
                state.templateId(), currentHeadVersion, applied);
        if (headIsAncestorOfApplied) {
            return Evaluation.notPending("TARGET_IS_ANCESTOR_OF_APPLIED");
        }

        return Evaluation.pending(applied, currentHeadVersion);
    }

    public record Evaluation(boolean pending, String fromVersion, String toVersion, String reason) {
        public static Evaluation pending(String from, String to) {
            return new Evaluation(true, from, to, "PENDING_UPDATE");
        }

        public static Evaluation notPending(String reason) {
            return new Evaluation(false, null, null, reason);
        }
    }
}
