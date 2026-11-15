package com.argdigital.aronexa.appointment;

import java.io.IOException;
import java.nio.file.Path;

/** Test-only assertion: a requested portable JVM must not fall back to build Java. */
final class Win11pPortableRuntimeGuard {
    private Win11pPortableRuntimeGuard() {}

    static void verify(String expectedHome, String actualHome) throws IOException {
        if (!Path.of(expectedHome).toRealPath().equals(Path.of(actualHome).toRealPath())) {
            throw new IllegalStateException("WIN11P STOP: backend is not running on the requested portable runtime");
        }
    }
}
