package com.jgo.engagement.adapter.outbound.local;

import com.jgo.engagement.application.port.out.EngagementIndexPort.IndexedEngagement;
import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.TemplateState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryEngagementIndexTest {
    private static final String TEMPLATE = "T";

    private final InMemoryEngagementIndex index = new InMemoryEngagementIndex();

    @Test
    void firmsWithEngagementsReturnsOnlyFirmsHoldingTheTemplate() {
        index.add(new EngagementRef("f1", "e1"), state(TEMPLATE, "v3", false));
        index.add(new EngagementRef("f2", "e1"), state("OTHER", "v3", false));
        index.add(new EngagementRef("f2", "e2"), state(TEMPLATE, "v3", false));

        assertEquals(List.of("f1", "f2"), index.firmsWithEngagements(TEMPLATE));
        assertEquals(List.of("f2"), index.firmsWithEngagements("OTHER"));
        assertEquals(List.of(), index.firmsWithEngagements("NONE"));
    }

    @Test
    void findByTemplatePagesWithOffsetTokens() {
        for (int i = 0; i < 5; i++) {
            index.add(new EngagementRef("f1", "e" + i), state(TEMPLATE, "v3", false));
        }

        List<IndexedEngagement> page1 = index.findByTemplate("f1", TEMPLATE, null, 2);
        assertEquals(2, page1.size());
        assertEquals("2", page1.getLast().nextPageToken());

        List<IndexedEngagement> page2 = index.findByTemplate("f1", TEMPLATE, "2", 2);
        assertEquals(2, page2.size());
        assertEquals("4", page2.getLast().nextPageToken());
        assertEquals("e2", page2.getFirst().engagement().engagementId());

        List<IndexedEngagement> page3 = index.findByTemplate("f1", TEMPLATE, "4", 2);
        assertEquals(1, page3.size());
        assertNull(page3.getLast().nextPageToken());

        assertTrue(index.findByTemplate("f1", TEMPLATE, "5", 2).isEmpty());
    }

    @Test
    void findByTemplateTreatsBlankTokenAsStartAndGarbageAsEnd() {
        index.add(new EngagementRef("f1", "e0"), state(TEMPLATE, "v3", false));

        assertEquals(1, index.findByTemplate("f1", TEMPLATE, " ", 10).size());
        assertTrue(index.findByTemplate("f1", TEMPLATE, "not-a-number", 10).isEmpty());
        assertTrue(index.findByTemplate("missing", TEMPLATE, null, 10).isEmpty());
    }

    @Test
    void findByTemplateFiltersOtherTemplates() {
        index.add(new EngagementRef("f1", "e1"), state(TEMPLATE, "v3", false));
        index.add(new EngagementRef("f1", "e2"), state("OTHER", "v3", false));

        List<IndexedEngagement> page = index.findByTemplate("f1", TEMPLATE, null, 10);
        assertEquals(1, page.size());
        assertEquals("e1", page.getFirst().engagement().engagementId());
    }

    @Test
    void recordVerifiedUpdatesTheStoredState() {
        EngagementRef engagement = new EngagementRef("f1", "e1");
        index.add(engagement, state(TEMPLATE, "v3", false));

        index.recordVerified(engagement, state(TEMPLATE, "v3", true));

        IndexedEngagement row = index.findByTemplate("f1", TEMPLATE, null, 10).getFirst();
        assertEquals(TemplateState.VerificationStatus.VERIFIED, row.state().verificationStatus());
    }

    @Test
    void recordVerifiedIgnoresUnknownRows() {
        assertDoesNotThrow(() -> index.recordVerified(
                new EngagementRef("ghost", "e9"), state(TEMPLATE, "v3", true)));
    }

    @Test
    void addReplacesExistingRowAndTracksSize() {
        EngagementRef engagement = new EngagementRef("f1", "e1");
        index.add(engagement, state(TEMPLATE, "v3", false));
        index.add(engagement, state(TEMPLATE, "v4", false));

        assertEquals(1, index.size());
        assertEquals("v4", index.findByTemplate("f1", TEMPLATE, null, 10).getFirst().state().appliedVersion());
    }

    private static TemplateState state(String templateId, String applied, boolean verified) {
        return new TemplateState(
                templateId,
                applied,
                Set.of(),
                verified
                        ? TemplateState.VerificationStatus.VERIFIED
                        : TemplateState.VerificationStatus.UNVERIFIED,
                1);
    }
}
