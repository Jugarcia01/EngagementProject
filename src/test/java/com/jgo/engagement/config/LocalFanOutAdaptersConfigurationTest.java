package com.jgo.engagement.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class LocalFanOutAdaptersConfigurationTest {

    @Test
    void stubProfileGuardAcceptsLocalAndDev() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertDoesNotThrow(() -> new LocalFanOutAdaptersConfiguration(local).assertStubProfile());

        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertDoesNotThrow(() -> new LocalFanOutAdaptersConfiguration(dev).assertStubProfile());
    }

    @Test
    void stubProfileGuardRefusesRealProfiles() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new LocalFanOutAdaptersConfiguration(prod).assertStubProfile());
        assertTrue(failure.getMessage().contains("local"));
    }

    @Test
    void stubProfileGuardRefusesDefaultProfile() {
        MockEnvironment noProfile = new MockEnvironment();

        assertThrows(IllegalStateException.class,
                () -> new LocalFanOutAdaptersConfiguration(noProfile).assertStubProfile());
    }
}
