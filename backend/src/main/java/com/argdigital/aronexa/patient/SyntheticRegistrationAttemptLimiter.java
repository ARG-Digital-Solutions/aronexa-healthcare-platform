package com.argdigital.aronexa.patient;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * C2-S2 synthetic-only bounded admission budget, not a production distributed
 * limiter. No patient attributes, browser headers or raw bearer tokens used.
 * One monitor serializes the check and increment across actor/tenant quotas.
 */
final class SyntheticRegistrationAttemptLimiter {
    private record StaffKey(UUID tenant, UUID membership) {}
    private static final class Window {
        long startedMs;
        int count;
        Window(long now) { startedMs = now; }
    }

    private final Clock clock;
    private final long windowMs;
    private final int perStaff;
    private final int perTenant;
    private final int maxStaffScopes;
    private final int maxTenantScopes;
    private final Map<StaffKey, Window> staffWindows = new HashMap<>();
    private final Map<UUID, Window> tenantWindows = new HashMap<>();
    private long calls;

    SyntheticRegistrationAttemptLimiter(Clock clock, Duration window,
                                       int perStaff, int perTenant,
                                       int maxStaffScopes, int maxTenantScopes) {
        if (clock == null || window == null || window.isNegative() || window.isZero()
                || perStaff < 1 || perTenant < perStaff
                || maxStaffScopes < 1 || maxTenantScopes < 1) {
            throw new IllegalArgumentException("Invalid synthetic throttle configuration");
        }
        this.clock = clock;
        this.windowMs = window.toMillis();
        if (windowMs < 1) throw new IllegalArgumentException("Window must be at least 1 ms");
        this.perStaff = perStaff;
        this.perTenant = perTenant;
        this.maxStaffScopes = maxStaffScopes;
        this.maxTenantScopes = maxTenantScopes;
    }

    static SyntheticRegistrationAttemptLimiter syntheticDefaults() {
        return new SyntheticRegistrationAttemptLimiter(
                Clock.systemUTC(), Duration.ofMinutes(1), 12, 120, 4096, 1024);
    }

    synchronized boolean allow(UUID tenant, UUID membership) {
        if (tenant == null || membership == null) return false;
        long now = clock.millis();
        StaffKey key = new StaffKey(tenant, membership);
        if ((++calls & 63L) == 0L) reapExpired(now);
        Window staff = staffWindows.get(key);
        Window tenantWindow = tenantWindows.get(tenant);
        if (staff != null && expired(staff, now)) {
            staffWindows.remove(key);
            staff = null;
        }
        if (tenantWindow != null && expired(tenantWindow, now)) {
            tenantWindows.remove(tenant);
            tenantWindow = null;
        }
        // If the cache is full, evict ONLY expired windows; otherwise deny,
        // never drop live counters to make a rate-limit bypass.
        if ((staff == null && staffWindows.size() >= maxStaffScopes)
                || (tenantWindow == null && tenantWindows.size() >= maxTenantScopes)) {
            reapExpired(now);
            staff = staffWindows.get(key);
            tenantWindow = tenantWindows.get(tenant);
            if ((staff == null && staffWindows.size() >= maxStaffScopes)
                    || (tenantWindow == null && tenantWindows.size() >= maxTenantScopes)) {
                return false;
            }
        }
        if ((staff != null && staff.count >= perStaff)
                || (tenantWindow != null && tenantWindow.count >= perTenant)) {
            return false;
        }
        if (staff == null) {
            staff = new Window(now);
            staffWindows.put(key, staff);
        }
        if (tenantWindow == null) {
            tenantWindow = new Window(now);
            tenantWindows.put(tenant, tenantWindow);
        }
        staff.count++;
        tenantWindow.count++;
        return true;
    }

    private boolean expired(Window w, long now) {
        return now >= w.startedMs && now - w.startedMs >= windowMs;
    }

    private void reapExpired(long now) {
        staffWindows.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        tenantWindows.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    }

    synchronized int trackedStaffScopes() { return staffWindows.size(); }
    synchronized int trackedTenantScopes() { return tenantWindows.size(); }
}
