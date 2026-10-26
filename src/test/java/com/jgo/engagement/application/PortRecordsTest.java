package com.jgo.engagement.application;

import com.jgo.engagement.application.port.out.ReleaseCatalogPort;
import com.jgo.engagement.application.port.out.SlowVerificationPort;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Construction and accessor coverage for the record types embedded in the outbound ports. */
class PortRecordsTest {

    @Test
    void verificationResultExposesFields() {
        var result = new SlowVerificationPort.VerificationResult(true, "checked");

        assertTrue(result.applicable());
        assertEquals("checked", result.reason());
    }

    @Test
    void releaseHeadExposesFields() {
        var head = new ReleaseCatalogPort.ReleaseHead("T", "v5", 5);

        assertEquals("T", head.templateId());
        assertEquals("v5", head.headVersion());
        assertEquals(5, head.catalogSeq());
    }

    @Test
    void resolveSummaryIdUsesRichIdWhenPresent() {
        ReleaseCatalogPort catalog = new ReleaseCatalogPort() {
            @Override
            public Optional<ReleaseHead> findHead(String templateId) {
                return Optional.of(new ReleaseHead(templateId, "v5", 5));
            }

            @Override
            public boolean isAncestor(String templateId, String ancestorVersion, String descendantVersion) {
                return false;
            }

            @Override
            public Optional<String> findSummaryId(String templateId, String fromVersion, String toVersion) {
                return Optional.of("rich-1");
            }
        };

        assertEquals("rich-1", catalog.resolveSummaryId("T", "v3", "v5"));
        assertTrue(catalog.findHead("T").isPresent());
    }
}
