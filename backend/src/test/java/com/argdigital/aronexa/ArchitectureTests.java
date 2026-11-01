package com.argdigital.aronexa;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTests {
    @Test
    void modulesHaveNoCyclesOrIllegalDependencies() {
        ApplicationModules.of(AronexaApplication.class).verify();
    }
}
