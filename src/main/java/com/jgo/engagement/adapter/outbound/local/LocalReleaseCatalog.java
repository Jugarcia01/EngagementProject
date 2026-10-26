package com.jgo.engagement.adapter.outbound.local;

import com.jgo.engagement.application.port.out.ReleaseCatalogPort;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local-profile {@link ReleaseCatalogPort}: no stored heads or summaries
 * ({@link #resolveSummaryId} falls back to the deterministic pair id), and ancestry is
 * a deterministic linear ordering on {@code vN} version numbers so the smoke run
 * exercises the pending-marker path end to end.
 */
public final class LocalReleaseCatalog implements ReleaseCatalogPort {
    private static final Pattern VERSION = Pattern.compile("^v(\\d+)$");

    @Override
    public Optional<ReleaseHead> findHead(String templateId) {
        return Optional.empty();
    }

    @Override
    public boolean isAncestor(String templateId, String ancestorVersion, String descendantVersion) {
        Long ancestor = versionNumber(ancestorVersion);
        Long descendant = versionNumber(descendantVersion);
        return ancestor != null && descendant != null && ancestor < descendant;
    }

    @Override
    public Optional<String> findSummaryId(String templateId, String fromVersion, String toVersion) {
        return Optional.empty();
    }

    private static Long versionNumber(String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
