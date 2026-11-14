package com.argdigital.aronexa.appointment;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * R1-PILOT-05 TEST-ONLY gate. This runs BEFORE Flyway or any Spring datasource.
 * A unique out-of-band marker must exist in a loopback-only, disposable PG17
 * container. A URL/port alone is NEVER enough to authorize database writes.
 */
public final class Pilot05DisposableDatabaseGuard
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    static final String DATABASE = "aronexa_pilot05_disposable_only";
    static final String USER = "aronexa_pilot05_temporary";
    private static final String PASSWORD = "pilot05-disposable-password-only";

    static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("PILOT05 STOP: missing disposable test setting " + name);
        }
        return value;
    }

    static UUID runId() {
        return UUID.fromString(env("ARONEXA_PILOT05_RUN_ID"));
    }

    private static String validatedUrl() {
        String rawPort = env("ARONEXA_PILOT05_PORT");
        if (!rawPort.matches("[0-9]{4,5}")) {
            throw new IllegalStateException("PILOT05 STOP: invalid loopback port");
        }
        int port = Integer.parseInt(rawPort);
        if (port < 1024 || port > 65535 || port == 5433) {
            throw new IllegalStateException("PILOT05 STOP: unsafe port");
        }
        if (!"TEST_ONLY_DISPOSABLE".equals(env("ARONEXA_PILOT05_CONFIRM"))) {
            throw new IllegalStateException("PILOT05 STOP: missing disposable consent guard");
        }
        String phase = env("ARONEXA_PILOT05_PHASE");
        if (!("A".equals(phase) || "B".equals(phase))) {
            throw new IllegalStateException("PILOT05 STOP: phase must be A or B");
        }
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + DATABASE;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String jdbcUrl = validatedUrl();
        UUID expectedRun = runId();
        // Read the marker BEFORE any Spring context, migration or test seed.
        try (var c = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             var p = c.prepareStatement("""
                     SELECT count(*) FROM qa_guard.pilot05_run_marker
                     WHERE run_id=? AND database_name=?
                     """)) {
            p.setObject(1, expectedRun);
            p.setString(2, DATABASE);
            try (var rows = p.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1) {
                    throw new IllegalStateException(
                            "PILOT05 STOP: missing exact isolated database run marker");
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "PILOT05 STOP: exact disposable database marker was not verified", failure);
        }
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("pilot05-disposable-only", Map.of(
                        "spring.datasource.url", jdbcUrl,
                        "spring.datasource.username", USER,
                        "spring.datasource.password", PASSWORD,
                        "spring.flyway.enabled", "true",
                        "spring.flyway.clean-disabled", "true",
                        "server.address", "127.0.0.1",
                        "server.port", "0"
                )));
    }
}
