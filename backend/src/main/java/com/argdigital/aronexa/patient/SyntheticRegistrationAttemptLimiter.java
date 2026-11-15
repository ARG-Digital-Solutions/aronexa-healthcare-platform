package com.argdigital.aronexa.patient;

import java.time.Clock;
import java.time.Duration;

/** Source-compatible wrapper for the existing synthetic test contract. */
final class SyntheticRegistrationAttemptLimiter extends RegistrationAttemptLimiter {
    SyntheticRegistrationAttemptLimiter(Clock clock, Duration window, int perStaff,
            int perTenant, int maxStaffScopes, int maxTenantScopes) {
        super(clock,window,perStaff,perTenant,maxStaffScopes,maxTenantScopes);
    }
    static SyntheticRegistrationAttemptLimiter syntheticDefaults() {
        return new SyntheticRegistrationAttemptLimiter(
                Clock.systemUTC(),Duration.ofMinutes(1),12,120,4096,1024);
    }
}
