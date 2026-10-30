#!/usr/bin/env bash
set -Eeuo pipefail

EXPECTED_PROJECT_PATH="/c/PROJECTS/JavaCode/java21code/EngagementProject"
BASE_PACKAGE="com.jgo.engagement"
PACKAGE_PATH="com/jgo/engagement"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v cygpath >/dev/null 2>&1 || fail "Run this script from Git Bash on Windows (cygpath is required)."
CURRENT_PATH="$(pwd -P)"
EXPECTED_PATH="$(cd "$EXPECTED_PROJECT_PATH" 2>/dev/null && pwd -P)" || fail "Expected project directory not found: $EXPECTED_PROJECT_PATH"

[[ "${CURRENT_PATH,,}" == "${EXPECTED_PATH,,}" ]] || fail "Run from the project root: $EXPECTED_PROJECT_PATH"
[[ -f pom.xml && -d .git && -d src/main/java ]] || fail "Expected a bootstrapped Maven/Spring Boot project with .git and src/main/java."

MAIN_ROOT="src/main/java/$PACKAGE_PATH"
TEST_ROOT="src/test/java/$PACKAGE_PATH"

# Do not overwrite any files from a prior attempt.
FILES=(
  "$MAIN_ROOT/domain/model/EngagementRef.java"
  "$MAIN_ROOT/domain/model/TemplateState.java"
  "$MAIN_ROOT/domain/model/HeadChange.java"
  "$MAIN_ROOT/domain/model/PendingMarker.java"
  "$MAIN_ROOT/application/port/in/ProcessHeadChangeUseCase.java"
  "$MAIN_ROOT/application/port/out/EngagementIndexPort.java"
  "$MAIN_ROOT/application/port/out/PendingMarkerPort.java"
  "$MAIN_ROOT/application/port/out/ReleaseCatalogPort.java"
  "$MAIN_ROOT/application/port/out/SlowVerificationPort.java"
  "$MAIN_ROOT/application/port/out/WorkLeasePort.java"
  "$MAIN_ROOT/application/port/out/DeadLetterPort.java"
  "$MAIN_ROOT/application/service/PendingEvaluator.java"
  "$TEST_ROOT/domain/model/TemplateStateTest.java"
  "$TEST_ROOT/application/service/PendingEvaluatorTest.java"
)
for file in "${FILES[@]}"; do
  [[ ! -e "$file" ]] || fail "Refusing to overwrite existing file: $file"
done

mkdir -p \
  "$MAIN_ROOT/domain/model" \
  "$MAIN_ROOT/application/port/in" \
  "$MAIN_ROOT/application/port/out" \
  "$MAIN_ROOT/application/service" \
  "$MAIN_ROOT/adapter/in/event" \
  "$MAIN_ROOT/adapter/out" \
  "$MAIN_ROOT/config" \
  "$TEST_ROOT/domain/model" \
  "$TEST_ROOT/application/service"

cat > "$MAIN_ROOT/domain/model/EngagementRef.java" <<'JAVA'
package com.jgo.engagement.domain.model;

import java.util.Objects;

/** Stable reference to an engagement within its regional firm boundary. */
public record EngagementRef(String firmId, String engagementId) {
    public EngagementRef {
        firmId = requireText(firmId, "firmId");
        engagementId = requireText(engagementId, "engagementId");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
JAVA

cat > "$MAIN_ROOT/domain/model/TemplateState.java" <<'JAVA'
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
        templateId = requireText(templateId, "templateId");
        appliedVersion = requireText(appliedVersion, "appliedVersion");
        declinedVersions = Set.copyOf(Objects.requireNonNull(declinedVersions, "declinedVersions"));
        verificationStatus = Objects.requireNonNull(verificationStatus, "verificationStatus");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum VerificationStatus {
        VERIFIED,
        UNVERIFIED
    }
}
JAVA

cat > "$MAIN_ROOT/domain/model/HeadChange.java" <<'JAVA'
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
        changeId = requireText(changeId, "changeId");
        templateId = requireText(templateId, "templateId");
        if (headVersion != null && headVersion.isBlank()) {
            throw new IllegalArgumentException("headVersion must be null or non-blank");
        }
        if (catalogSeq < 0) {
            throw new IllegalArgumentException("catalogSeq must be >= 0");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
JAVA

cat > "$MAIN_ROOT/domain/model/PendingMarker.java" <<'JAVA'
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
JAVA

cat > "$MAIN_ROOT/application/port/in/ProcessHeadChangeUseCase.java" <<'JAVA'
package com.jgo.engagement.application.port.in;

import com.jgo.engagement.domain.model.HeadChange;

/** Application entry point for a state-carrying template-head change. */
public interface ProcessHeadChangeUseCase {
    void process(HeadChange change);
}
JAVA

cat > "$MAIN_ROOT/application/port/out/EngagementIndexPort.java" <<'JAVA'
package com.jgo.engagement.application.port.out;

import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.TemplateState;

import java.util.List;

/** Reads regional index entries in bounded pages; must not rehydrate full engagements. */
public interface EngagementIndexPort {
    List<IndexedEngagement> findByTemplate(String firmId, String templateId, String pageToken, int pageSize);

    record IndexedEngagement(EngagementRef engagement, TemplateState state, String nextPageToken) {}
}
JAVA

cat > "$MAIN_ROOT/application/port/out/PendingMarkerPort.java" <<'JAVA'
package com.jgo.engagement.application.port.out;

import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.PendingMarker;

public interface PendingMarkerPort {
    /** Write only if the supplied catalog sequence is not older than the stored sequence. */
    WriteResult upsertIfNotOlder(PendingMarker marker);

    /** Remove/clear a marker only if the supplied catalog sequence is not older than stored state. */
    WriteResult clearIfNotOlder(EngagementRef engagement, String templateId, long catalogSeq);

    enum WriteResult {
        APPLIED,
        STALE_IGNORED
    }
}
JAVA

cat > "$MAIN_ROOT/application/port/out/ReleaseCatalogPort.java" <<'JAVA'
package com.jgo.engagement.application.port.out;

import java.util.Optional;

/** Global template metadata only; never stores regional engagement-derived data. */
public interface ReleaseCatalogPort {
    Optional<ReleaseHead> findHead(String templateId);

    boolean isAncestor(String templateId, String ancestorVersion, String descendantVersion);

    Optional<String> findSummaryId(String templateId, String fromVersion, String toVersion);

    record ReleaseHead(String templateId, String headVersion, long catalogSeq) {}
}
JAVA

cat > "$MAIN_ROOT/application/port/out/SlowVerificationPort.java" <<'JAVA'
package com.jgo.engagement.application.port.out;

import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.TemplateState;

/** Capacity-constrained fallback for entries that cannot be evaluated from verified index data. */
public interface SlowVerificationPort {
    VerificationResult verify(EngagementRef engagement, TemplateState state, String targetVersion);

    record VerificationResult(boolean applicable, String reason) {}
}
JAVA

cat > "$MAIN_ROOT/application/port/out/WorkLeasePort.java" <<'JAVA'
package com.jgo.engagement.application.port.out;

import java.time.Duration;

/** Idempotency/coordination ledger for one change and engagement pair. */
public interface WorkLeasePort {
    LeaseResult tryAcquire(String changeId, String engagementId, Duration leaseDuration);

    void markCompleted(String changeId, String engagementId);

    void releaseForRetry(String changeId, String engagementId);

    enum LeaseResult {
        ACQUIRED,
        ALREADY_COMPLETED,
        HELD_BY_OTHER_WORKER
    }
}
JAVA

cat > "$MAIN_ROOT/application/port/out/DeadLetterPort.java" <<'JAVA'
package com.jgo.engagement.application.port.out;

import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.HeadChange;

public interface DeadLetterPort {
    void publish(HeadChange change, EngagementRef engagement, String failureCode, String safeMessage);
}
JAVA

cat > "$MAIN_ROOT/application/service/PendingEvaluator.java" <<'JAVA'
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
JAVA

cat > "$TEST_ROOT/domain/model/TemplateStateTest.java" <<'JAVA'
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
    }
}
JAVA

cat > "$TEST_ROOT/application/service/PendingEvaluatorTest.java" <<'JAVA'
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
                case "v4->v7", "v5->v7", "v4->v5" -> true;
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
    }

    @Test
    void doesNotOfferCrossBranchOrUnknownAncestry() {
        var state = state("v4", Set.of());

        assertFalse(evaluator.evaluate(state, "v9").pending());
    }

    private static TemplateState state(String appliedVersion, Set<String> declined) {
        return new TemplateState("template-1", appliedVersion, declined,
                TemplateState.VerificationStatus.VERIFIED, 1);
    }
}
JAVA

printf '\nArchitecture skeleton created.\n\n'
printf 'Generated Java files:\n'
printf '  %s\n' "${FILES[@]}"
printf '\nRunning Maven verification...\n'
./mvnw clean verify

printf '\n=== Git status (read-only) ===\n'
git status --short --branch
printf '\nNo files were staged or committed.\n'
