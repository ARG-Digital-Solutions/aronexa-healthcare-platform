package com.argdigital.aronexa.appointment;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class Win11pPortableRuntimeGuardTests {
    @Test void acceptsTheActualRuntimeOnly() throws Exception {
        String runtime = System.getProperty("java.home");
        assertThatCode(() -> Win11pPortableRuntimeGuard.verify(runtime, runtime))
                .doesNotThrowAnyException();
    }
    @Test void rejectsDifferentRuntimeAndMissingPath() {
        assertThatThrownBy(() -> Win11pPortableRuntimeGuard.verify(
                System.getProperty("user.dir"), System.getProperty("java.home")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Win11pPortableRuntimeGuard.verify(
                "missing-win11p-runtime", System.getProperty("java.home")))
                .isInstanceOf(java.io.IOException.class);
    }
}
